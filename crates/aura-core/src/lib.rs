//! # aura-core
//!
//! Pure Rust text processing engine for speech recognition post-processing,
//! text normalization, Spanish/English phonetic encoding, orthographic bigram
//! cosine tiebreaking, and custom dictionary phonetic correction.
//!
//! Designed to run 100% offline with zero Android/NDK dependencies, making it
//! fully testable and runnable natively on any architecture.

pub mod bigram;
pub mod corrector;
pub mod normalizer;
pub mod phonetic;

// Convenient re-exports
pub use bigram::{bigram_cosine, compute_bigrams, cosine_similarity_precomputed};
pub use corrector::{
    apply_case, best_term, correct, is_word_char, parse_dict, tokenize, Dictionary, Segment, Term,
    MAX_PHONETIC_DISTANCE,
};
pub use normalizer::{
    build_s1_prompt, get_control_line_for_preset, normalize_transcript, DEFAULT_S1_MODEL_FILE,
    S1_SYSTEM_PROMPT,
};
pub use phonetic::{collapse_duplicates, levenshtein_distance, phonetic_distance, phonetic_key};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_end_to_end_aura_core_pipeline() {
        let raw_dict = "
            # Custom technical & geographical terms
            Madrid
            Barcelona
            Whisper
            SuperWhisper
            New York
        ";
        let dict = parse_dict(raw_dict);
        assert_eq!(dict.len(), 5);

        // Phonetic correction
        let input = "ayer viaje a madriz y luego a barselona con wisper en new york";
        let corrected = correct(input, &dict);
        assert_eq!(
            corrected,
            "ayer viaje a Madrid y luego a Barcelona con Whisper en New York"
        );

        // Normalizer prompt generation
        let prompt = build_s1_prompt(&corrected, "formal", None);
        assert!(prompt.contains("[Styling: formal]"));
        assert!(prompt.contains("Madrid"));
        assert!(prompt.contains("Barcelona"));
    }
}
