use std::path::PathBuf;
use transcribe_rs::engines::parakeet::{ParakeetEngine, ParakeetModelParams};
use transcribe_rs::TranscriptionEngine;

#[test]
fn test_jfk_transcription() {
    let mut engine = ParakeetEngine::new();

    // Load the model
    let model_path = PathBuf::from("models/parakeet-tdt-0.6b-v3-int8");
    engine
        .load_model_with_params(&model_path, ParakeetModelParams::int8())
        .expect("Failed to load model");

    // Load the JFK audio file
    let audio_path = PathBuf::from("samples/jfk.wav");

    // Transcribe with default params
    let result = engine
        .transcribe_file(&audio_path, None)
        .expect("Failed to transcribe");

    let expected = "And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country.";
    assert_eq!(
        result.text.trim(),
        expected,
        "\nExpected: '{}'\nActual: '{}'",
        expected,
        result.text.trim()
    );
}

#[test]
fn test_jfk_transcription_from_memory() {
    let mut engine = ParakeetEngine::new();

    let model_path = PathBuf::from("models/parakeet-tdt-0.6b-v3-int8");
    let encoder_bytes = std::fs::read(model_path.join("encoder-model.int8.onnx")).expect("Failed to read encoder");
    let decoder_bytes = std::fs::read(model_path.join("decoder_joint-model.int8.onnx")).expect("Failed to read decoder");
    let preprocessor_bytes = std::fs::read(model_path.join("nemo128.onnx")).expect("Failed to read preprocessor");
    let vocab_content = std::fs::read_to_string(model_path.join("vocab.txt")).expect("Failed to read vocab");

    engine
        .load_model_from_memory(&encoder_bytes, &decoder_bytes, &preprocessor_bytes, &vocab_content)
        .expect("Failed to load model from memory");

    // Load the JFK audio file
    let audio_path = PathBuf::from("samples/jfk.wav");

    // Transcribe with default params
    let result = engine
        .transcribe_file(&audio_path, None)
        .expect("Failed to transcribe");

    let expected = "And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country.";
    assert_eq!(
        result.text.trim(),
        expected,
        "\nExpected: '{}'\nActual: '{}'",
        expected,
        result.text.trim()
    );
}

