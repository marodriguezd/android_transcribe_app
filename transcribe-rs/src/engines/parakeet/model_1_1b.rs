//! Parakeet 1.1B TDT v3 model implementation.
//!
//! The 1.1B model uses a different architecture from 0.6B:
//! - Separate encoder, decoder, and joiner (not combined decoder_joint)
//! - 80-dim mel features extracted in Rust (no ONNX preprocessor)
//! - Different decoder input/output names

use ndarray::{s, Array1, Array3};
use ort::ep;
use ort::inputs;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;
use ort::value::Tensor;

use super::mel;

const ENCODER_DIM: usize = 1024;
const DECODER_HIDDEN: usize = 640;
const SUBSAMPLING_FACTOR: usize = 8;
const WINDOW_SIZE: f32 = 0.01;
const MAX_CHUNK_SAMPLES: usize = 60 * 16_000;
const CHUNK_SPLIT_SEARCH_START: usize = 45 * 16_000;
const SPLIT_WINDOW_SAMPLES: usize = 1_600;

#[derive(Debug, Clone)]
pub struct TimestampedResult {
    pub text: String,
    pub timestamps: Vec<f32>,
    pub tokens: Vec<String>,
}

#[derive(thiserror::Error, Debug)]
pub enum Parakeet1_1bError {
    #[error("ONNX Runtime error: {0}")]
    Ort(#[from] ort::Error),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Shape error: {0}")]
    Shape(String),
    #[error("Model input not found: {0}")]
    InputNotFound(String),
    #[error("Model output not found: {0}")]
    OutputNotFound(String),
}

pub struct Parakeet1_1bModel {
    encoder: Session,
    decoder: Session,
    joiner: Session,
    vocab: Vec<String>,
    blank_id: i32,
    decoder_state1: Option<Array3<f32>>,
    decoder_state2: Option<Array3<f32>>,
}

impl Parakeet1_1bModel {
    pub fn from_memory(
        encoder_bytes: &[u8],
        decoder_bytes: &[u8],
        joiner_bytes: &[u8],
        vocab_content: &str,
    ) -> Result<Self, Parakeet1_1bError> {
        let encoder = init_session_from_memory(encoder_bytes)?;
        let decoder = init_session_from_memory(decoder_bytes)?;
        let joiner = init_session_from_memory(joiner_bytes)?;

        let (vocab, blank_id) = parse_vocab(vocab_content)?;
        log::info!(
            "Loaded 1.1B model: {} tokens, blank_id={}",
            vocab.len(),
            blank_id
        );

        Ok(Self {
            encoder,
            decoder,
            joiner,
            vocab,
            blank_id,
            decoder_state1: None,
            decoder_state2: None,
        })
    }

    pub fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
    ) -> Result<TimestampedResult, Parakeet1_1bError> {
        if samples.is_empty() {
            return Ok(TimestampedResult {
                text: String::new(),
                timestamps: Vec::new(),
                tokens: Vec::new(),
            });
        }

        if samples.len() <= MAX_CHUNK_SAMPLES {
            return self.transcribe_chunk(samples);
        }

        log::info!(
            "1.1B: Audio has {} samples ({:.1}s), chunking",
            samples.len(),
            samples.len() as f64 / 16_000.0,
        );

        let mut merged_text = String::new();
        let mut merged_tokens: Vec<String> = Vec::new();
        let mut merged_timestamps: Vec<f32> = Vec::new();

        let mut offset: usize = 0;
        while offset < samples.len() {
            let remaining = samples.len() - offset;
            let end = if remaining <= MAX_CHUNK_SAMPLES {
                samples.len()
            } else {
                find_quietest_split(
                    &samples,
                    offset + CHUNK_SPLIT_SEARCH_START,
                    offset + MAX_CHUNK_SAMPLES,
                )
            };
            let chunk_time_offset = offset as f32 / 16_000.0;

            let result = self.transcribe_chunk(samples[offset..end].to_vec())?;
            let trimmed = result.text.trim();
            if !trimmed.is_empty() {
                if !merged_text.is_empty() {
                    merged_text.push(' ');
                }
                merged_text.push_str(trimmed);
                for (token, &ts) in result.tokens.iter().zip(result.timestamps.iter()) {
                    merged_tokens.push(token.clone());
                    merged_timestamps.push(ts + chunk_time_offset);
                }
            }
            offset = end;
        }

        Ok(TimestampedResult {
            text: merged_text,
            timestamps: merged_timestamps,
            tokens: merged_tokens,
        })
    }

    fn transcribe_chunk(&mut self, samples: Vec<f32>) -> Result<TimestampedResult, Parakeet1_1bError> {
        // Extract 80-dim mel features
        let features = mel::extract_mel_features(&samples);
        log::debug!("1.1B: Mel features shape: {:?}", features.shape());

        // Transpose to (batch, features, time) for encoder
        let num_frames = features.nrows();
        let num_features = features.ncols();
        let mut encoder_input_data = Vec::with_capacity(num_features * num_frames);
        for col in 0..num_features {
            for row in features.outer_iter() {
                encoder_input_data.push(row[col]);
            }
        }

        let audio_signal = Tensor::from_array((
            vec![1usize, num_features, num_frames],
            encoder_input_data.into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let length = Tensor::from_array((
            vec![1usize],
            vec![num_frames as i64].into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        // Run encoder
        let (enc_shape_vec, enc_data_vec) = {
            let outputs = self
                .encoder
                .run(inputs!["audio_signal" => audio_signal, "length" => length])
                .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

            let enc_out_tensor = &outputs[0];
            let (enc_shape, enc_data) = enc_out_tensor.try_extract_tensor::<f32>().map_err(|e| {
                Parakeet1_1bError::Ort(e.into())
            })?;

            let shape_vec: Vec<usize> = enc_shape.iter().map(|&d| d as usize).collect();
            let data_vec = enc_data.to_vec();
            (shape_vec, data_vec)
        }; // outputs dropped here

        let encoder_out = Array3::from_shape_vec(
            (enc_shape_vec[0], enc_shape_vec[1], enc_shape_vec[2]),
            enc_data_vec,
        )
        .map_err(|e| Parakeet1_1bError::Shape(e.to_string()))?;

        let enc_frames = encoder_out.shape()[2];

        // Reset decoder state for new chunk
        self.decoder_state1 = Some(Array3::zeros((2, 1, DECODER_HIDDEN)));
        self.decoder_state2 = Some(Array3::zeros((2, 1, DECODER_HIDDEN)));

        let mut tokens = Vec::new();
        let mut timestamps = Vec::new();

        // Initialize decoder with blank token
        let mut decoder_out = self.run_decoder(&[self.blank_id as i64])?;

        let mut t = 0;

        while t < enc_frames {
            let encoder_frame = encoder_out.slice(s![0, .., t]).to_owned();
            let logits = self.run_joiner(&encoder_frame, &decoder_out)?;

            let logits_slice = logits.as_slice().unwrap();
            let vocab_size = self.vocab.len();
            let num_durations = logits_slice.len() - vocab_size;

            let token_logits = &logits_slice[..vocab_size];
            let duration_logits = if num_durations > 0 {
                &logits_slice[vocab_size..]
            } else {
                &[][..]
            };

            // Greedy token selection
            let (y, _) = token_logits
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap())
                .unwrap();
            let y = y as i64;

            // Greedy duration selection
            let mut skip = if !duration_logits.is_empty() {
                duration_logits
                    .iter()
                    .enumerate()
                    .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap())
                    .map(|(idx, _)| idx)
                    .unwrap_or(0)
            } else {
                0
            };

            if y != self.blank_id as i64 {
                tokens.push(y);
                timestamps.push(t);
                decoder_out = self.run_decoder(&[y])?;
            }

            // Skip logic
            if skip > 0 {
                // ok
            } else if y == self.blank_id as i64 {
                skip = 1;
            }

            if skip > 0 {
                t += skip;
            }
        }

        // Convert tokens to text
        let token_strs: Vec<String> = tokens
            .iter()
            .filter_map(|&id| {
                let idx = id as usize;
                if idx < self.vocab.len() {
                    Some(self.vocab[idx].clone())
                } else {
                    None
                }
            })
            .collect();

        let raw_text = token_strs.join("");
        // Apply spacing: replace \u2581 with space, collapse spaces
        let text = raw_text
            .replace('\u{2581}', " ")
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");

        let float_timestamps: Vec<f32> = timestamps
            .iter()
            .map(|&t| WINDOW_SIZE * SUBSAMPLING_FACTOR as f32 * t as f32)
            .collect();

        Ok(TimestampedResult {
            text,
            timestamps: float_timestamps,
            tokens: token_strs,
        })
    }

    fn run_decoder(&mut self, tokens: &[i64]) -> Result<Array1<f32>, Parakeet1_1bError> {
        let batch_size = 1;
        let seq_len = tokens.len();

        let targets_i32: Vec<i32> = tokens.iter().map(|&t| t as i32).collect();
        let targets = Tensor::from_array((
            vec![batch_size, seq_len],
            targets_i32.into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let target_length = Tensor::from_array((
            vec![batch_size],
            vec![seq_len as i32].into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let state1 = self.decoder_state1.as_ref().unwrap();
        let state2 = self.decoder_state2.as_ref().unwrap();

        let state1_tensor = Tensor::from_array((
            vec![2, batch_size, DECODER_HIDDEN],
            state1.as_slice().unwrap().to_vec().into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let state2_tensor = Tensor::from_array((
            vec![2, 1, DECODER_HIDDEN],
            state2.as_slice().unwrap().to_vec().into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let outputs = self
            .decoder
            .run(inputs![
                "targets" => targets,
                "target_length" => target_length,
                "states.1" => state1_tensor,
                "onnx::Slice_3" => state2_tensor
            ])
            .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        // Extract decoder output (batch, hidden_size, seq_len)
        let dec_tensor = &outputs[0];
        let (dec_shape, dec_data) = dec_tensor.try_extract_tensor::<f32>().map_err(|e| {
            Parakeet1_1bError::Ort(e.into())
        })?;

        let batch = dec_shape[0] as usize;
        let hidden = dec_shape[1] as usize;
        let seq = dec_shape[2] as usize;

        let dec_3d = Array3::from_shape_vec((batch, hidden, seq), dec_data.to_vec())
            .map_err(|e| Parakeet1_1bError::Shape(e.to_string()))?;

        // Last frame
        let last_frame = dec_3d.slice(s![0, .., seq - 1]).to_owned();

        // Update states
        if let Ok((s1_shape, s1_data)) = outputs[2].try_extract_tensor::<f32>() {
            self.decoder_state1 = Some(
                Array3::from_shape_vec(
                    (s1_shape[0] as usize, s1_shape[1] as usize, s1_shape[2] as usize),
                    s1_data.to_vec(),
                )
                .unwrap(),
            );
        }
        if let Ok((s2_shape, s2_data)) = outputs[3].try_extract_tensor::<f32>() {
            self.decoder_state2 = Some(
                Array3::from_shape_vec(
                    (s2_shape[0] as usize, s2_shape[1] as usize, s2_shape[2] as usize),
                    s2_data.to_vec(),
                )
                .unwrap(),
            );
        }

        Ok(last_frame)
    }

    fn run_joiner(
        &mut self,
        encoder_frame: &Array1<f32>,
        decoder_out: &Array1<f32>,
    ) -> Result<Array1<f32>, Parakeet1_1bError> {
        let enc_tensor = Tensor::from_array((
            vec![1, ENCODER_DIM, 1],
            encoder_frame.to_vec().into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let dec_tensor = Tensor::from_array((
            vec![1, DECODER_HIDDEN, 1],
            decoder_out.to_vec().into_boxed_slice(),
        ))
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let outputs = self
            .joiner
            .run(inputs!["encoder_outputs" => enc_tensor, "decoder_outputs" => dec_tensor])
            .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

        let logits_tensor = &outputs[0];
        let (_shape, data) = logits_tensor.try_extract_tensor::<f32>().map_err(|e| {
            Parakeet1_1bError::Ort(e.into())
        })?;

        Ok(Array1::from_vec(data.to_vec()))
    }
}

fn init_session_from_memory(model_bytes: &[u8]) -> Result<Session, Parakeet1_1bError> {
    let mut providers = Vec::new();
    #[cfg(target_os = "android")]
    {
        providers.push(ep::NNAPI::default().build());
        providers.push(ep::XNNPACK::default().build());
    }
    providers.push(ep::CPU::default().build());

    let session = Session::builder()
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?
        .with_optimization_level(GraphOptimizationLevel::Level3)
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?
        .with_execution_providers(providers)
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?
        .with_parallel_execution(true)
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?
        .commit_from_memory(model_bytes)
        .map_err(|e| Parakeet1_1bError::Ort(e.into()))?;

    Ok(session)
}

fn parse_vocab(content: &str) -> Result<(Vec<String>, i32), Parakeet1_1bError> {
    let mut max_id = 0;
    let mut tokens_with_ids: Vec<(String, usize)> = Vec::new();
    let mut blank_idx: Option<usize> = None;

    for line in content.lines() {
        let parts: Vec<&str> = line.trim_end().split(' ').collect();
        if parts.len() >= 2 {
            let token = parts[0].to_string();
            if let Ok(id) = parts[1].parse::<usize>() {
                if token == "<blk>" {
                    blank_idx = Some(id);
                }
                tokens_with_ids.push((token, id));
                max_id = max_id.max(id);
            }
        }
    }

    let mut vocab = vec![String::new(); max_id + 1];
    for (token, id) in tokens_with_ids {
        vocab[id] = token.replace('\u{2581}', " ");
    }

    let blank_idx = blank_idx.ok_or_else(|| {
        Parakeet1_1bError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "Missing <blk> token in vocabulary",
        ))
    })? as i32;

    Ok((vocab, blank_idx))
}

fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    let to = to.min(samples.len());
    if from + SPLIT_WINDOW_SAMPLES > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + SPLIT_WINDOW_SAMPLES <= to {
        let energy: f32 = samples[i..i + SPLIT_WINDOW_SAMPLES]
            .iter()
            .map(|&x| x * x)
            .sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + SPLIT_WINDOW_SAMPLES / 2;
        }
        i += SPLIT_WINDOW_SAMPLES / 2;
    }
    best_pos
}
