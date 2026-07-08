//! Transcribe a 16 kHz mono WAV file with the Parakeet engine.
//!
//! Usage:
//!   cargo run --release --example parakeet_cli -- <model_dir> <wav_file>

use std::path::PathBuf;
use std::time::Instant;

use transcribe_rs::engines::parakeet::{ParakeetEngine, ParakeetModelParams};
use transcribe_rs::TranscriptionEngine;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();

    let mut args = std::env::args().skip(1);
    let model_dir = PathBuf::from(args.next().expect("usage: parakeet_cli <model_dir> <wav>"));
    let wav_path = PathBuf::from(args.next().expect("usage: parakeet_cli <model_dir> <wav>"));

    let mut engine = ParakeetEngine::new();
    let load_start = Instant::now();
    engine.load_model_with_params(&model_dir, ParakeetModelParams::int8())?;
    println!("Model loaded in {:.2?}", load_start.elapsed());

    let start = Instant::now();
    let result = engine.transcribe_file(&wav_path, None)?;
    println!("Transcribed in {:.2?}", start.elapsed());
    println!("---\n{}", result.text.trim());

    Ok(())
}
