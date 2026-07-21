use std::collections::HashMap;

use ort::ep;
use ort::inputs;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;
use ort::value::Tensor;

use super::mel_128;

const MAX_SEQUENCE_LENGTH: usize = 1024;
const MEL_DIM: usize = 128;

#[derive(Debug, Clone)]
pub struct TimestampedResult {
    pub text: String,
    pub timestamps: Vec<f32>,
    pub tokens: Vec<String>,
}

/// Language selection for the Canary 180M AED model. Canary's decoder is fed a
/// 10-token prefix where positions 4 + 5 are source/target language — to make
/// Spanish/German/French transcription work, those positions must NOT always
/// be `<|en|>`. `Auto` uses `<|unklang|>` on both sides so the model itself
/// detects the language from the audio (Canary-180m-flash supports
/// English, Spanish, German, French; anything else will fall back to one of
/// these four or to English).
///
/// Mapping to vocab tokens (loaded from `canary-180m-flash-int8/vocab.txt`):
///   en     -> <|en|>      (id 62)
///   es     -> <|es|>      (id 169)
///   de     -> <|de|>      (id 76)
///   fr     -> <|fr|>      (id 69)
///   auto   -> <|unklang|> (id 21) on both source/target sides
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CanaryLanguage {
    #[default]
    Auto,
    En,
    Es,
    De,
    Fr,
}

impl CanaryLanguage {
    /// Parse a preference string. Accepts `"auto"` (default) or the ISO-639-1
    /// two-letter code. Unknown / empty values fall back to `Auto`.
    /// `transcription_language` preference from Java is a plain string
    /// (`"auto"`, `"en"`, `"es"`, `"de"`, `"fr"`); unknown values are
    /// coerced to `Auto` so a typo in the pref file does NOT crash the load.
    pub fn from_pref(s: &str) -> Self {
        match s.trim().to_ascii_lowercase().as_str() {
            "en" => CanaryLanguage::En,
            "es" => CanaryLanguage::Es,
            "de" => CanaryLanguage::De,
            "fr" => CanaryLanguage::Fr,
            // "auto" and anything else (incl. "") maps to Auto
            _ => CanaryLanguage::Auto,
        }
    }
}

#[derive(thiserror::Error, Debug)]
pub enum Parakeet180mError {
    #[error("ONNX Runtime error: {0}")]
    Ort(#[from] ort::Error),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Vocabulary error: {0}")]
    Vocab(String),
    #[error("Missing special token: {0}")]
    MissingToken(String),
    #[error("Model input not found: {0}")]
    InputNotFound(String),
    #[error("Failed to get tensor shape for input: {0}")]
    TensorShape(String),
}

pub struct Parakeet180mModel {
    encoder: Session,
    decoder: Session,
    vocab: Vec<String>,
    eos_token_id: i64,
    /// Per-language source/target token IDs (positions 4 and 5 of the
    /// 10-token decoder prefix). `lang_unksrc_id` is the id of
    /// `<|unklang|>` which we use as both source AND target when the user
    /// picks Auto — Canary then language-detects from the audio itself.
    lang_en_id: i64,
    lang_es_id: i64,
    lang_de_id: i64,
    lang_fr_id: i64,
    lang_unksrc_id: i64,
    /// Static prefix tokens (positions 0..=3 and 6..=9 of the 10-token
    /// decoder prefix). These are language-independent: space, then the
    /// Canary context/transcript delimiters, then the emo/PnC/ITN/timestamp/
    /// diarize toggles. Kept as plain fields so we can rebuild the prefix
    /// per call without re-tokenising.
    pre_space_id: i64,
    pre_startofcontext_id: i64,
    pre_startoftranscript_id: i64,
    pre_emo_undefined_id: i64,
    pre_pnc_id: i64,
    pre_noitn_id: i64,
    pre_notimestamp_id: i64,
    pre_nodiarize_id: i64,
    /// Current selection — defaults to Auto so a fresh install behaves the
    /// way the model was trained for (no language forcing). Update via
    /// [`Self::set_language`] from the JNI bridge when the user toggles
    /// their preference; no ONNX reload is needed because the prefix is
    /// re-tokenised at the top of every `transcribe_samples` call.
    current_lang: CanaryLanguage,
    decoder_num_layers: i64,
    decoder_hidden_size: i64,
}

impl Drop for Parakeet180mModel {
    fn drop(&mut self) {
        log::info!("Dropping Parakeet180mModel, releasing ORT sessions");
    }
}

impl Parakeet180mModel {
    pub fn from_memory(
        encoder_bytes: &[u8],
        decoder_bytes: &[u8],
        vocab_content: &str,
    ) -> Result<Self, Parakeet180mError> {
        let encoder = init_session_from_memory(encoder_bytes)?;
        let decoder = init_session_from_memory(decoder_bytes)?;

        let (vocab, token_map) = parse_vocab(vocab_content)?;

        let eos_token_id = *token_map.get("<|endoftext|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|endoftext|>".into()))?;

        // Static (language-independent) prefix tokens. We resolve each
        // `<|...|>` ID once at load time; the language-dependent positions
        // (4 + 5) are looked up dynamically per call from `lang_*_id`.
        let pre_space_id = *token_map
            .get(" ")
            .ok_or_else(|| Parakeet180mError::MissingToken("space ' '".into()))?;
        let pre_startofcontext_id = *token_map
            .get("<|startofcontext|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|startofcontext|>".into()))?;
        let pre_startoftranscript_id = *token_map
            .get("<|startoftranscript|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|startoftranscript|>".into()))?;
        let pre_emo_undefined_id = *token_map
            .get("<|emo:undefined|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|emo:undefined|>".into()))?;
        let pre_pnc_id = *token_map
            .get("<|pnc|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|pnc|>".into()))?;
        let pre_noitn_id = *token_map
            .get("<|noitn|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|noitn|>".into()))?;
        let pre_notimestamp_id = *token_map
            .get("<|notimestamp|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|notimestamp|>".into()))?;
        let pre_nodiarize_id = *token_map
            .get("<|nodiarize|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|nodiarize|>".into()))?;

        // Language tokens for source/target positions (4 + 5). Canary's
        // decoder treats these as a tag-and-generate pair: <|en|> <|es|>
        // means "transcribe English audio, output Spanish text", which is
        // how cross-lingual translation works in the Canary family. We
        // always set source == target (pure ASR, no translation) and let
        // the user pick from {en, es, de, fr} or fall through to
        // <|unklang|> on both sides for auto-detection.
        let lang_en_id = *token_map
            .get("<|en|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|en|>".into()))?;
        let lang_es_id = *token_map
            .get("<|es|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|es|>".into()))?;
        let lang_de_id = *token_map
            .get("<|de|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|de|>".into()))?;
        let lang_fr_id = *token_map
            .get("<|fr|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|fr|>".into()))?;
        let lang_unksrc_id = *token_map
            .get("<|unklang|>")
            .ok_or_else(|| Parakeet180mError::MissingToken("<|unklang|>".into()))?;

        let decoder_inputs = decoder.inputs();
        for input in decoder_inputs {
            log::info!(
                "180M decoder input: name={}, dtype={:?}, shape={:?}",
                input.name(),
                input.dtype(),
                input.dtype().tensor_shape()
            );
        }
        let decoder_mems_shape = decoder_inputs
            .iter()
            .find(|input| input.name() == "decoder_mems")
            .ok_or_else(|| {
                Parakeet180mError::InputNotFound("decoder_mems".to_string())
            })?
            .dtype()
            .tensor_shape()
            .ok_or_else(|| {
                Parakeet180mError::TensorShape("decoder_mems".to_string())
            })?;

        let decoder_num_layers = decoder_mems_shape[0];
        let decoder_hidden_size = decoder_mems_shape[3];

        log::info!(
            "180M decoder_mems: [{}, ?, ?, {}] (layers={}, hidden={})",
            decoder_num_layers,
            decoder_hidden_size,
            decoder_num_layers,
            decoder_hidden_size
        );

        log::info!(
            "Loaded 180M AED model: {} tokens, eos_id={}",
            vocab.len(),
            eos_token_id
        );

        Ok(Self {
            encoder,
            decoder,
            vocab,
            eos_token_id,
            lang_en_id,
            lang_es_id,
            lang_de_id,
            lang_fr_id,
            lang_unksrc_id,
            pre_space_id,
            pre_startofcontext_id,
            pre_startoftranscript_id,
            pre_emo_undefined_id,
            pre_pnc_id,
            pre_noitn_id,
            pre_notimestamp_id,
            pre_nodiarize_id,
            current_lang: CanaryLanguage::Auto,
            decoder_num_layers,
            decoder_hidden_size,
        })
    }

    /// Update the source/target language for subsequent `transcribe_samples`
    /// calls. Does NOT touch the loaded ONNX sessions — the decoder
    /// re-tokenises the prefix on every call, so only the two `lang_*_id`
    /// slots change. Safe to call from the JNI bridge thread.
    pub fn set_language(&mut self, lang: CanaryLanguage) {
        log::info!("180M set_language: {:?} -> {:?}", self.current_lang, lang);
        self.current_lang = lang;
    }

    /// Returns the language the next `transcribe_samples` call will ask
    /// the decoder to use. Useful for diagnostics / logcat smoke-testing.
    pub fn current_language(&self) -> CanaryLanguage {
        self.current_lang
    }

    /// Build the 10-token decoder prefix for `lang`. Positions 4 + 5
    /// (source/target) are derived from `lang`; all other positions are
    /// the static Canary transcript tokens (space + delimiters +
    /// emo + pnc/noitn/notimestamp/nodiarize). Pure function of `lang`
    /// and the cached `lang_*_id` fields so it can be inlined into the
    /// autoregressive loop without re-allocation per call.
    fn build_prefix(&self, lang: CanaryLanguage) -> Vec<i64> {
        let (src, tgt) = match lang {
            CanaryLanguage::Auto => (self.lang_unksrc_id, self.lang_unksrc_id),
            CanaryLanguage::En => (self.lang_en_id, self.lang_en_id),
            CanaryLanguage::Es => (self.lang_es_id, self.lang_es_id),
            CanaryLanguage::De => (self.lang_de_id, self.lang_de_id),
            CanaryLanguage::Fr => (self.lang_fr_id, self.lang_fr_id),
        };
        vec![
            self.pre_space_id,
            self.pre_startofcontext_id,
            self.pre_startoftranscript_id,
            self.pre_emo_undefined_id,
            src,
            tgt,
            self.pre_pnc_id,
            self.pre_noitn_id,
            self.pre_notimestamp_id,
            self.pre_nodiarize_id,
        ]
    }

    pub fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
    ) -> Result<TimestampedResult, Parakeet180mError> {
        if samples.is_empty() {
            return Ok(TimestampedResult {
                text: String::new(),
                timestamps: Vec::new(),
                tokens: Vec::new(),
            });
        }

        let features = mel_128::extract_mel_features(&samples);
        let num_frames = features.nrows();
        if num_frames == 0 {
            return Ok(TimestampedResult {
                text: String::new(),
                timestamps: Vec::new(),
                tokens: Vec::new(),
            });
        }

        // Build the 10-token decoder prefix at the TOP of
        // transcribe_samples — before any self.encoder / self.decoder
        // borrow opens. Doing it here avoids the E0502 that would fire
        // if we called self.build_prefix later (after self.encoder.run),
        // because ort's Session::run keeps a mutable borrow of
        // self.encoder tied to the lifetime of the returned outputs
        // reference. The Vec<i64> returned from build_prefix is owned
        // and does not borrow self anywhere, so it stays disjoint from
        // the decoder-loop borrows below.
        let mut input_ids = self.build_prefix(self.current_lang);
        let prefix_len = input_ids.len();

        let features_t = features.t();
        let audio_signal_data: Vec<f32> = features_t.iter().copied().collect();
        let audio_signal = Tensor::from_array((
            vec![1i64, MEL_DIM as i64, num_frames as i64],
            audio_signal_data.into_boxed_slice(),
        ))
        .map_err(Parakeet180mError::Ort)?;

        let length = Tensor::from_array((
            vec![1i64],
            vec![num_frames as i64].into_boxed_slice(),
        ))
        .map_err(Parakeet180mError::Ort)?;

        let outputs = self
            .encoder
            .run(inputs!["audio_signal" => audio_signal, "length" => length])
            .map_err(Parakeet180mError::Ort)?;

        let enc_emb_tensor = &outputs[0];
        let (enc_emb_shape, enc_emb_data) = enc_emb_tensor
            .try_extract_tensor::<f32>()
            .map_err(Parakeet180mError::Ort)?;
        let encoder_embeddings_data: Vec<f32> = enc_emb_data.to_vec();
        let enc_t_dim = enc_emb_shape[1] as usize;

        let enc_mask_tensor = &outputs[1];
        let (enc_mask_shape, enc_mask_data) = enc_mask_tensor
            .try_extract_tensor::<i64>()
            .map_err(Parakeet180mError::Ort)?;
        let encoder_mask_data: Vec<i64> = enc_mask_data.to_vec();
        let mask_t_dim = enc_mask_shape[1] as usize;

        log::info!(
            "180M encoder output: embeddings=[{}, {}, {}], mask=[{}, {}], frames={}",
            enc_emb_shape[0], enc_emb_shape[1], enc_emb_shape[2],
            enc_mask_shape[0], enc_mask_shape[1],
            num_frames
        );

        // Snapshot the language at the start of the call so a concurrent
        // `set_language` from the JNI bridge mid-transcribe does not flip
        // the prefix under our feet. The decode loop is short (a few
        // hundred steps max for typical dictation) so this is more than
        // accurate enough.
        // input_ids was already built at the top of transcribe_samples
        // before any encoder borrow opened (see the comment around
        // `let mut input_ids = self.build_prefix(...)` near features_t).
        // We just re-read the slice length here for use in the prefix
        // loop below; no re-tokenisation needed.
        let prefix_len = input_ids.len();

        // Access self.current_lang directly — CanaryLanguage is Copy
        // so reading the field does not hold a borrow on self, which is
        // important here because we are mid-function with the encoder
        // borrow (produced by self.encoder.run earlier) potentially
        // still tracked by NLL.
        log::info!(
            "180M input_ids: len={}, lang={:?}, values={:?}",
            input_ids.len(),
            self.current_lang,
            input_ids.iter().map(|x| *x as i64).collect::<Vec<_>>()
        );

        let encoder_embeddings_tensor = Tensor::from_array((
            vec![1i64, enc_t_dim as i64, enc_emb_shape[2]],
            encoder_embeddings_data.into_boxed_slice(),
        ))
        .map_err(Parakeet180mError::Ort)?;

        let encoder_mask_tensor = Tensor::from_array((
            vec![1i64, mask_t_dim as i64],
            encoder_mask_data.into_boxed_slice(),
        ))
        .map_err(Parakeet180mError::Ort)?;

        let d0: i64 = self.decoder_num_layers;
        let d3: i64 = self.decoder_hidden_size;

        let mut decoder_mems_data: Vec<f32> = vec![0.0f32; (d0 * d3) as usize];
        let mut decoder_mems_seq_len: i64 = 1;

        log::info!(
            "180M decoder: prefix={}, mems_init=[{},1,1,{}], enc_emb=[1,{},{}], enc_mask=[1,{}]",
            prefix_len,
            d0, d3, enc_t_dim, enc_emb_shape[2], mask_t_dim
        );

        for i in 0..prefix_len {
            let token = input_ids[i];
            log::info!("180M prefix step {}: token={}", i, token);
            let input_tensor = Tensor::from_array((
                vec![1i64, 1i64],
                vec![token].into_boxed_slice(),
            ))
            .map_err(Parakeet180mError::Ort)?;

            let mems_tensor = Tensor::from_array((
                vec![d0, 1i64, decoder_mems_seq_len, d3],
                decoder_mems_data.clone().into_boxed_slice(),
            ))
            .map_err(Parakeet180mError::Ort)?;

            let dec_outputs = self
                .decoder
                .run(inputs! {
                    "input_ids" => input_tensor,
                    "encoder_embeddings" => encoder_embeddings_tensor.clone(),
                    "encoder_mask" => encoder_mask_tensor.clone(),
                    "decoder_mems" => mems_tensor,
                })
                .map_err(|e| {
                    log::error!("180M decoder.run() error: {}", e);
                    Parakeet180mError::Ort(e)
                })?;

            let mems_out = &dec_outputs[1];
            let (mems_out_shape, mems_out_data) = mems_out
                .try_extract_tensor::<f32>()
                .map_err(Parakeet180mError::Ort)?;
            decoder_mems_seq_len = mems_out_shape[2];
            decoder_mems_data = mems_out_data.to_vec();
        }

        log::info!(
            "180M decoder: prefix done, generating from {} tokens, mems_seq_len={}",
            input_ids.len(),
            decoder_mems_seq_len
        );

        let mut gen_step = 0usize;
        loop {
            let last_token = input_ids.last().copied().unwrap_or(self.eos_token_id);
            if gen_step % 10 == 0 {
                log::info!("180M gen step {}: token={}", gen_step, last_token);
            }
            gen_step += 1;
            let input_tensor = Tensor::from_array((
                vec![1i64, 1i64],
                vec![last_token].into_boxed_slice(),
            ))
            .map_err(Parakeet180mError::Ort)?;

            let mems_tensor = Tensor::from_array((
                vec![d0, 1i64, decoder_mems_seq_len, d3],
                decoder_mems_data.clone().into_boxed_slice(),
            ))
            .map_err(Parakeet180mError::Ort)?;

            let dec_outputs = self
                .decoder
                .run(inputs! {
                    "input_ids" => input_tensor,
                    "encoder_embeddings" => encoder_embeddings_tensor.clone(),
                    "encoder_mask" => encoder_mask_tensor.clone(),
                    "decoder_mems" => mems_tensor,
                })
                .map_err(|e| {
                    log::error!("180M decoder.run() error: {}", e);
                    Parakeet180mError::Ort(e)
                })?;

            let logits_tensor = &dec_outputs[0];
            let (logits_shape, logits_data) = logits_tensor
                .try_extract_tensor::<f32>()
                .map_err(Parakeet180mError::Ort)?;

            let seq_len = logits_shape[1] as usize;
            let vocab_size = logits_shape[2] as usize;
            let last_step_start = (seq_len.saturating_sub(1)) * vocab_size;
            let last_step_logits = &logits_data[last_step_start..last_step_start + vocab_size];

            if last_step_logits.iter().any(|&x| x.is_nan()) {
                break;
            }

            let next_token = last_step_logits
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(idx, _)| idx as i64)
                .unwrap_or(0);

            if next_token == self.eos_token_id {
                log::info!("180M gen step {}: EOS ({})", gen_step, self.eos_token_id);
                break;
            }

            log::info!("180M gen step {}: next token={}", gen_step, next_token);
            input_ids.push(next_token);

            let mems_out = &dec_outputs[1];
            let (mems_out_shape, mems_out_data) = mems_out
                .try_extract_tensor::<f32>()
                .map_err(Parakeet180mError::Ort)?;
            decoder_mems_seq_len = mems_out_shape[2];
            decoder_mems_data = mems_out_data.to_vec();

            if input_ids.len() >= MAX_SEQUENCE_LENGTH {
                break;
            }
        }

        let token_strs: Vec<String> = input_ids[prefix_len..]
            .iter()
            .filter_map(|&id| {
                let idx = id as usize;
                if idx < self.vocab.len() {
                    let token = &self.vocab[idx];
                    if !token.starts_with("<|") && !token.is_empty() {
                        Some(token.clone())
                    } else {
                        None
                    }
                } else {
                    None
                }
            })
            .collect();

        let raw_text = token_strs.join("");
        let text = raw_text
            .replace('\u{2581}', " ")
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");

        Ok(TimestampedResult {
            text,
            timestamps: Vec::new(),
            tokens: token_strs,
        })
    }
}

fn init_session_from_memory(model_bytes: &[u8]) -> Result<Session, Parakeet180mError> {
    let mut providers = Vec::new();
    #[cfg(target_os = "android")]
    {
        providers.push(ep::NNAPI::default().build());
        providers.push(ep::XNNPACK::default().build());
    }
    providers.push(ep::CPU::default().build());

    let session = Session::builder()
        .map_err(|e| Parakeet180mError::Ort(e.into()))?
        .with_optimization_level(GraphOptimizationLevel::Level3)
        .map_err(|e| Parakeet180mError::Ort(e.into()))?
        .with_execution_providers(providers)
        .map_err(|e| Parakeet180mError::Ort(e.into()))?
        .with_parallel_execution(true)
        .map_err(|e| Parakeet180mError::Ort(e.into()))?
        .commit_from_memory(model_bytes)
        .map_err(|e| Parakeet180mError::Ort(e.into()))?;

    Ok(session)
}

fn parse_vocab(
    content: &str,
) -> Result<(Vec<String>, HashMap<String, i64>), Parakeet180mError> {
    let mut max_id = 0usize;
    let mut tokens_with_ids: Vec<(String, usize)> = Vec::new();

    for line in content.lines() {
        let line = line.trim_end();
        if line.is_empty() {
            continue;
        }
        if let Some((token, id_str)) = line.rsplit_once(' ') {
            if let Ok(id) = id_str.parse::<usize>() {
                tokens_with_ids.push((token.to_string(), id));
                max_id = max_id.max(id);
            }
        }
    }

    let mut vocab = vec![String::new(); max_id + 1];
    let mut token_map = HashMap::new();
    for (token, id) in &tokens_with_ids {
        let clean_token = token.replace('\u{2581}', " ");
        vocab[*id] = clean_token.clone();
        token_map.insert(clean_token, *id as i64);
    }

    Ok((vocab, token_map))
}
