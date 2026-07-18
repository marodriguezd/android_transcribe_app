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
    transcribe_input: Vec<i64>,
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

        let transcribe_input = vec![
            *token_map.get(" ")
                .ok_or_else(|| Parakeet180mError::MissingToken("space ' '".into()))?,
            *token_map.get("<|startofcontext|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|startofcontext|>".into()))?,
            *token_map.get("<|startoftranscript|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|startoftranscript|>".into()))?,
            *token_map.get("<|emo:undefined|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|emo:undefined|>".into()))?,
            *token_map.get("<|en|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|en|>".into()))?,
            *token_map.get("<|en|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|en|> (target)".into()))?,
            *token_map.get("<|pnc|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|pnc|>".into()))?,
            *token_map.get("<|noitn|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|noitn|>".into()))?,
            *token_map.get("<|notimestamp|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|notimestamp|>".into()))?,
            *token_map.get("<|nodiarize|>")
                .ok_or_else(|| Parakeet180mError::MissingToken("<|nodiarize|>".into()))?,
        ];

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
            transcribe_input,
            decoder_num_layers,
            decoder_hidden_size,
        })
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

        let mut input_ids = self.transcribe_input.clone();
        let prefix_len = input_ids.len();

        log::info!(
            "180M input_ids: len={}, values={:?}",
            input_ids.len(),
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
