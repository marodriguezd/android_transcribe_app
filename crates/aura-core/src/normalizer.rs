//! Text normalization and prompt construction for speech post-processing models.

/// Default S1-mini GGUF model file name.
pub const DEFAULT_S1_MODEL_FILE: &str = "s1-mini-q4_k_m.gguf";

/// Default system prompt expected by the text normalization model.
pub const S1_SYSTEM_PROMPT: &str =
    "You are a text normalizer for speech-to-text transcripts. The input begins with a control line specifying the styling, structure, and context settings; clean the transcript to match those settings and output only the cleaned text.";

/// Builds the formatted ChatML prompt and control line for S1-mini normalization.
pub fn build_s1_prompt(raw_text: &str, preset: &str, custom_prompt: Option<&str>) -> String {
    let control_line = if let Some(custom) = custom_prompt {
        if !custom.trim().is_empty() {
            custom.trim().to_string()
        } else {
            get_control_line_for_preset(preset)
        }
    } else {
        get_control_line_for_preset(preset)
    };

    format!(
        "<|im_start|>system\n{}<|im_end|>\n<|im_start|>user\n{}\n{}<|im_end|>\n<|im_start|>assistant\n<think>\n</think>\n",
        S1_SYSTEM_PROMPT,
        control_line,
        raw_text.trim()
    )
}

/// Maps preset names to steerable control axis parameters.
pub fn get_control_line_for_preset(preset: &str) -> String {
    match preset.to_lowercase().as_str() {
        "formal" => "[Styling: formal] [Structure: prose] [Context: general]".to_string(),
        "casual" => "[Styling: casual] [Structure: prose] [Context: general]".to_string(),
        "email" => "[Styling: semi-formal] [Structure: prose] [Context: email]".to_string(),
        "lists" => "[Styling: semi-formal] [Structure: lists] [Context: general]".to_string(),
        _ => "[Styling: semi-formal] [Structure: prose] [Context: general]".to_string(), // "clean" / default
    }
}

/// Normalizes raw transcript according to the requested preset mode.
///
/// If preset is "verbatim" or "literal", the text is returned as-is.
/// Otherwise trims leading/trailing whitespace.
pub fn normalize_transcript(raw_text: &str, preset: &str, _custom_prompt: Option<&str>) -> String {
    if raw_text.trim().is_empty() {
        return raw_text.to_string();
    }
    if preset.eq_ignore_ascii_case("verbatim") || preset.eq_ignore_ascii_case("literal") {
        return raw_text.to_string();
    }
    raw_text.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_build_s1_prompt_presets() {
        let p_formal = build_s1_prompt("hola mundo", "formal", None);
        assert!(p_formal.contains("[Styling: formal]"));
        assert!(p_formal.contains("hola mundo"));
        assert!(p_formal.contains(S1_SYSTEM_PROMPT));

        let p_casual = build_s1_prompt("hola mundo", "casual", None);
        assert!(p_casual.contains("[Styling: casual]"));

        let p_email = build_s1_prompt("estimado equipo", "email", None);
        assert!(p_email.contains("[Context: email]"));

        let p_lists = build_s1_prompt("uno dos tres", "lists", None);
        assert!(p_lists.contains("[Structure: lists]"));

        let p_default = build_s1_prompt("prueba", "clean", None);
        assert!(p_default.contains("[Styling: semi-formal]"));
    }

    #[test]
    fn test_custom_prompt_override() {
        let custom = "Traduce al inglés y resume en 3 bullets";
        let prompt = build_s1_prompt("texto de entrada", "formal", Some(custom));
        assert!(prompt.contains(custom));
        assert!(!prompt.contains("[Styling: formal]"));
    }

    #[test]
    fn test_normalize_transcript_verbatim() {
        let text = "   umm ahh verbatim text   ";
        assert_eq!(normalize_transcript(text, "verbatim", None), text);
        assert_eq!(normalize_transcript(text, "literal", None), text);
        assert_eq!(
            normalize_transcript(text, "clean", None),
            "umm ahh verbatim text"
        );
    }
}
