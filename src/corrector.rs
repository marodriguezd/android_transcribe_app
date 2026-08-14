//! Custom-word phonetic correction (post-ASR, pre-delivery).
//!
//! Applies a user-maintained dictionary of "correct" terms to transcript text:
//! for each word that does not already match a dictionary term, it finds the
//! closest term by phonetic similarity (a Spanish+English-aware encoder +
//! Levenshtein distance) and replaces it, preserving the original word's
//! capitalization. When several terms are equally close phonetically, an
//! orthographic character-bigram cosine similarity breaks the tie.
//!
//! Design notes:
//! - Runs inside [`crate::engine::transcribe_shared`], so it covers every
//!   surface (IME, voice popup, live subtitles, SpeechRecognizer, file
//!   transcription, benchmark) without per-surface wiring.
//! - Deterministic, offline, no network. The only added dependency is `strsim`
//!   (pure Rust, MIT) for Levenshtein.
//! - The dictionary is a marker file in filesDir (`custom_words`), one term
//!   per line, matching the project's marker-file convention (AGENTS.md §4.5).
//!   Its presence and non-emptiness is the opt-in: no separate toggle.
//! - The filesDir path is published once from `engine::do_load` via
//!   [`set_files_dir`]; before that (engine not yet loaded) correction is a
//!   no-op, so it can never block the first transcription.
//! - The dictionary is cached with an mtime check so repeated transcriptions
//!   (e.g. live-subtitle partials) do not re-read the file each time.
//! - Correction never loses text: any I/O failure or panic falls back to the
//!   raw transcript (the engine wraps this call in `catch_unwind` too).

use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

// HashMap key = word count of the term.

/// Marker file in filesDir holding the user's terms, one per line. Lines
/// starting with `#` are comments; blank lines are ignored.
pub const CUSTOM_WORDS_FILE: &str = "custom_words";

/// Maximum Levenshtein distance between two phonetic keys to count as a match.
/// 2 tolerates a single phoneme swap plus a vowel shift, which covers the
/// common ASR confusions (e.g. "madriz" → "Madrid", "barselona" → "Barcelona")
/// without drifting into unrelated words.
const MAX_PHONETIC_DISTANCE: usize = 2;

/// Cached dictionary + the mtime of the file it was loaded from, so we only
/// re-read when the user edits the list.
static CACHE: Lazy<Mutex<Option<CachedDict>>> = Lazy::new(|| Mutex::new(None));

/// filesDir path, published by the engine once it has resolved it. Stored
/// globally because `transcribe_shared` runs on worker threads without JNI
/// access; the engine's `do_load` (which has JNIEnv) sets it via
/// [`set_files_dir`].
static FILES_DIR: Lazy<Mutex<Option<PathBuf>>> = Lazy::new(|| Mutex::new(None));

#[derive(Clone)]
struct CachedDict {
    mtime: SystemTime,
    dict: Arc<Dictionary>,
}

#[derive(Clone, Default)]
struct Dictionary {
    /// Single-word terms and their phonetic keys.
    single: Vec<Term>,
    /// Multi-word terms (≥2 whitespace-separated words), grouped by word
    /// count so a window of N transcript words only matches N-word terms
    /// (avoids out-of-bounds panics and spurious cross-length matches).
    multi: HashMap<usize, Vec<Term>>,
}

#[derive(Clone)]
struct Term {
    /// The dictionary term as the user wrote it (preserves capitalization,
    /// e.g. "Madrid", "New York").
    text: String,
    /// Lowercased term, precomputed once so the per-word hot path never
    /// re-lowercases (exact match and the orthographic tiebreak need it).
    lower: String,
    /// Phonetic key of the lowercased term.
    key: String,
    /// Precomputed character-bigram counts for zero-allocation cosine tiebreaking.
    bigrams: HashMap<String, u32>,
    /// Precomputed L2 norm of bigram vector.
    bigram_norm: f64,
}

/// Publish the filesDir path so the corrector can locate the dictionary.
/// Called once from `engine::do_load` after it resolves filesDir.
pub fn set_files_dir(dir: &PathBuf) {
    // Recover from poison (matching the engine's resilience pattern,
    // AGENTS.md §5.1): if a previous thread panicked while holding this
    // lock, the guard is still usable.
    *FILES_DIR.lock().unwrap_or_else(|p| p.into_inner()) = Some(dir.clone());
}

/// Corrects `text` against the user's custom dictionary, if one is present
/// and non-empty. Returns the text unchanged when no dictionary exists, when
/// the engine hasn't published a filesDir yet, or on any I/O error —
/// correction must never lose text, so the safe fallback is always the raw
/// transcript.
pub fn correct_if_enabled(text: &str) -> String {
    if text.trim().is_empty() {
        return text.to_string();
    }
    let dict = match load_dict() {
        Some(d) => d,
        None => return text.to_string(),
    };
    if dict.single.is_empty() && dict.multi.is_empty() {
        return text.to_string();
    }
    correct(text, &dict)
}

/// Loads the dictionary from disk, returning the cached copy when the file's
/// mtime is unchanged. Returns `None` if no filesDir is set, the file is
/// absent, or reading fails — all treated as "no correction" (safe fallback).
fn load_dict() -> Option<Arc<Dictionary>> {
    let dir = FILES_DIR
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()?;
    let path = dir.join(CUSTOM_WORDS_FILE);
    let metadata = std::fs::metadata(&path).ok()?;
    let mtime = metadata.modified().ok()?;

    {
        let cache = CACHE.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(cached) = cache.as_ref() {
            if cached.mtime == mtime {
                return Some(cached.dict.clone());
            }
        }
    }

    let raw = match std::fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("custom_words: failed to read {}: {}", path.display(), e);
            return None;
        }
    };
    let dict = parse_dict(&raw);
    log::info!(
        "custom_words: loaded {} single + {} multi term(s)",
        dict.single.len(),
        dict.multi.values().map(|v| v.len()).sum::<usize>()
    );
    let dict = Arc::new(dict);
    *CACHE.lock().unwrap_or_else(|p| p.into_inner()) = Some(CachedDict {
        mtime,
        dict: dict.clone(),
    });
    // Arc keeps repeated transcriptions (e.g. live-subtitle partials) from
    // deep-cloning the whole dictionary on every call (O2).
    Some(dict)
}

/// Parses raw dictionary text into single- and multi-word terms. Multi-word
/// terms are grouped by word count so a window of N transcript words only
/// competes against N-word dictionary terms.
fn parse_dict(raw: &str) -> Dictionary {
    let mut single = Vec::new();
    let mut multi: HashMap<usize, Vec<Term>> = HashMap::new();
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let lower = trimmed.to_lowercase();
        let key = phonetic_key(&lower);
        let (bigrams, bigram_norm) = compute_bigrams(&lower);
        let word_count = trimmed.split_whitespace().count();
        let term = Term {
            text: trimmed.to_string(),
            lower,
            key,
            bigrams,
            bigram_norm,
        };
        if word_count > 1 {
            multi.entry(word_count).or_default().push(term);
        } else {
            single.push(term);
        }
    }
    Dictionary { single, multi }
}

/// Encodes a word (or space-joined phrase) into a phonetic key that groups
/// similar-sounding words together. Handles Spanish and English orthography:
///
/// - Spanish: seseo/yeísmo (`z`→`s`, `ce`/`ci`→`se`/`si`, `ll`→`y`), silent
///   `h`, `v`→`b`, `qu`→`k`, `g`/`j` before `e`/`i`→`h` (/x/), `ñ`→`ny`,
///   `gue`/`gui`→`ge`/`gi`, `gü`→`gw`.
/// - English: `ph`→`f`, `gh`→`f`, `kn`→`n`, `wr`→`r`, `th`→`d`, `wh`→`w`,
///   `c` before `e`/`i`/`y`→`s`, `c` before `a`/`o`/`u`→`k`, `x`→`ks`.
///
/// Spaces are preserved so multi-word keys align with joined transcript
/// windows. Consecutive duplicate characters collapse (lenient: `rr`→`r`
/// helps where an ASR trill is heard as a single tap). The encoder is
/// deterministic, so a transcript word and its dictionary target always
/// produce comparable keys.
fn phonetic_key(input: &str) -> String {
    let s: Vec<char> = input.to_lowercase().chars().collect();
    let mut out = String::with_capacity(s.len());
    let n = s.len();
    let mut i = 0;
    while i < n {
        let c = s[i];
        let n1 = s.get(i + 1).copied().unwrap_or('\0');
        let n2 = s.get(i + 2).copied().unwrap_or('\0');

        // Digraphs/trigraphs first (first match wins).
        match (c, n1, n2) {
            ('c', 'h', _) => {
                out.push('x');
                i += 2;
            } // ch → x
            ('l', 'l', _) => {
                out.push('y');
                i += 2;
            } // ll → y
            ('q', 'u', _) => {
                out.push('k');
                i += 2;
            } // qu → k
            ('g', 'u', 'e') | ('g', 'u', 'i') => {
                out.push('g');
                out.push(n2);
                i += 3;
            } // gue/gui
            ('g', 'ü', _) => {
                out.push('g');
                out.push('w');
                out.push(n2);
                i += 3;
            } // güe/güi
            ('p', 'h', _) => {
                out.push('f');
                i += 2;
            } // ph → f
            ('g', 'h', _) => {
                out.push('f');
                i += 2;
            } // gh → f
            ('k', 'n', _) => {
                out.push('n');
                i += 2;
            } // kn → n
            ('w', 'r', _) => {
                out.push('r');
                i += 2;
            } // wr → r
            ('s', 'h', _) => {
                out.push('s');
                i += 2;
            } // sh → s
            ('t', 'h', _) => {
                out.push('d');
                i += 2;
            } // th → d
            ('w', 'h', _) => {
                out.push('w');
                i += 2;
            } // wh → w
            ('c', 'e', _) | ('c', 'i', _) => {
                out.push('s');
                out.push(n1);
                i += 2;
            } // ce/ci → se/si
            ('c', 'a', _) | ('c', 'o', _) | ('c', 'u', _) => {
                out.push('k');
                out.push(n1);
                i += 2;
            } // c(a/o/u) → k
            ('c', 'y', _) => {
                out.push('s');
                out.push('y');
                i += 2;
            } // cy → sy
            ('g', 'e', _) | ('g', 'i', _) | ('j', 'e', _) | ('j', 'i', _) => {
                out.push('h');
                out.push(n1);
                i += 2;
            } // g/j + e/i → h
            ('x', _, _) => {
                out.push('k');
                out.push('s');
                i += 1;
            } // x → ks
            ('v', _, _) => {
                out.push('b');
                i += 1;
            } // v → b
            ('z', _, _) => {
                out.push('s');
                i += 1;
            } // z → s
            ('ñ', _, _) => {
                out.push('n');
                out.push('y');
                i += 1;
            } // ñ → ny
            ('h', _, _) => {
                i += 1;
            } // silent h
            _ => {
                out.push(c);
                i += 1;
            }
        }
    }
    collapse_duplicates(&out)
}

/// Collapses consecutive duplicate characters (e.g. `rr`→`r`, `ee`→`e`).
fn collapse_duplicates(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev = '\0';
    for c in s.chars() {
        if c != prev {
            out.push(c);
            prev = c;
        }
    }
    out
}

/// Character-bigram counts and L2 norm for a string.
fn compute_bigrams(s: &str) -> (HashMap<String, u32>, f64) {
    let chars: Vec<char> = s.chars().collect();
    let mut m = HashMap::new();
    for w in chars.windows(2) {
        let bg: String = w.iter().collect();
        *m.entry(bg).or_insert(0u32) += 1;
    }
    let norm = m.values().map(|&v| (v as f64).powi(2)).sum::<f64>().sqrt();
    (m, norm)
}

/// Character-bigram cosine similarity between two strings. Used as an
/// orthographic tiebreak when several dictionary terms are phonetically
/// equidistant.
#[allow(dead_code)]
fn bigram_cosine(a: &str, b: &str) -> f64 {
    let (ca, na) = compute_bigrams(a);
    let (cb, nb) = compute_bigrams(b);
    if na == 0.0 || nb == 0.0 {
        return 0.0;
    }
    let mut dot = 0u32;
    for (g, &va) in ca.iter() {
        if let Some(&vb) = cb.get(g) {
            dot += va * vb;
        }
    }
    dot as f64 / (na * nb)
}

/// Returns the best matching dictionary term for `word_lower`, or `None`.
/// Tries an exact case-insensitive match first (so a transcript "madrid" is
/// upgraded to the dictionary's "Madrid" without fuzzy matching), then a
/// phonetic Levenshtein pass with an orthographic cosine tiebreak.
fn best_term(word_lower: &str, terms: &[Term]) -> Option<String> {
    // Exact match (precomputed lowercase, same Unicode-aware folding).
    for t in terms {
        if t.lower == word_lower {
            return Some(t.text.clone());
        }
    }
    // Phonetic match: minimize Levenshtein on phonetic keys, break ties with
    // the highest orthographic cosine (stored negated so min-selection wins).
    let key = phonetic_key(word_lower);
    let key_len = key.len();
    let mut best: Option<(usize, f64, usize)> = None; // (distance, -cosine, index)
    let mut word_bigrams: Option<(HashMap<String, u32>, f64)> = None;

    for (idx, t) in terms.iter().enumerate() {
        // Cheap necessity filter: |len(a) - len(b)| <= dist for Levenshtein,
        // so candidates whose key length cannot reach are skipped before the
        // quadratic-ish scan (O2 hot path).
        let len_diff = key_len.abs_diff(t.key.len());
        if len_diff > MAX_PHONETIC_DISTANCE {
            continue;
        }
        let dist = strsim::levenshtein(&key, &t.key);
        if dist > MAX_PHONETIC_DISTANCE {
            continue;
        }

        // Lazy-compute bigrams for the search word only when a candidate passes
        // phonetic distance filter
        let (wb, wb_norm) = word_bigrams.get_or_insert_with(|| compute_bigrams(word_lower));
        let sim = if *wb_norm == 0.0 || t.bigram_norm == 0.0 {
            0.0
        } else {
            let mut dot = 0u32;
            for (g, &va) in wb.iter() {
                if let Some(&vb) = t.bigrams.get(g) {
                    dot += va * vb;
                }
            }
            dot as f64 / (*wb_norm * t.bigram_norm)
        };
        let neg = -sim;
        match best {
            Some((bd, bn, _)) if (dist, neg) >= (bd, bn) => {}
            _ => best = Some((dist, neg, idx)),
        }
    }
    best.map(|(_, _, idx)| terms[idx].text.clone())
}

/// Applies the capitalization context of `original` to `term`. If `original`
/// is all uppercase (and has letters), uppercases `term`; otherwise returns
/// `term` with the dictionary's own casing, so proper nouns keep their form
/// (e.g. transcript "madriz" → dictionary "Madrid", not "madrid").
fn apply_case(term: &str, original: &str) -> String {
    let letters: Vec<char> = original.chars().filter(|c| c.is_alphabetic()).collect();
    if !letters.is_empty() && letters.iter().all(|c| c.is_uppercase()) {
        term.to_uppercase()
    } else {
        term.to_string()
    }
}

enum Segment {
    Word(String),
    Sep(String),
}

/// Splits text into word and separator segments, preserving whitespace and
/// punctuation exactly for reassembly. A character is part of a word if it is
/// alphanumeric, an apostrophe, or `.`/`-`/`_` flanked by alphanumeric
/// characters (so "transcribe.cpp" and "state-of-the-art" stay single tokens
/// while a trailing period stays a separator).
fn tokenize(text: &str) -> Vec<Segment> {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut out = Vec::new();
    let mut buf = String::new();
    let mut is_word = false;
    for i in 0..n {
        let c = chars[i];
        let prev = if i > 0 { chars[i - 1] } else { '\0' };
        let next = chars.get(i + 1).copied().unwrap_or('\0');
        let wc = is_word_char(c, prev, next);
        if wc {
            if !is_word {
                if !buf.is_empty() {
                    out.push(Segment::Sep(std::mem::take(&mut buf)));
                }
                is_word = true;
            }
            buf.push(c);
        } else {
            if is_word {
                if !buf.is_empty() {
                    out.push(Segment::Word(std::mem::take(&mut buf)));
                }
                is_word = false;
            }
            buf.push(c);
        }
    }
    if !buf.is_empty() {
        if is_word {
            out.push(Segment::Word(buf));
        } else {
            out.push(Segment::Sep(buf));
        }
    }
    out
}

fn is_word_char(c: char, prev: char, next: char) -> bool {
    if c.is_alphanumeric() || c == '\'' || c == '\u{2019}' {
        return true;
    }
    if (c == '.' || c == '-' || c == '_') && prev.is_alphanumeric() && next.is_alphanumeric() {
        return true;
    }
    false
}

/// Corrects `text` against `dict`: multi-word terms are matched against
/// sliding windows of consecutive transcript words (longest first), then
/// single-word terms are matched word-by-word. Replacements preserve the
/// speaker's capitalization context (all-caps shouting → uppercased term).
fn correct(text: &str, dict: &Dictionary) -> String {
    let segments = tokenize(text);
    let word_indices: Vec<usize> = segments
        .iter()
        .enumerate()
        .filter_map(|(i, s)| {
            if matches!(s, Segment::Word(_)) {
                Some(i)
            } else {
                None
            }
        })
        .collect();
    let words: Vec<String> = word_indices
        .iter()
        .filter_map(|&i| {
            if let Segment::Word(w) = &segments[i] {
                Some(w.to_lowercase())
            } else {
                None
            }
        })
        .collect();

    let max_window = dict.multi.keys().copied().max().unwrap_or(0);

    // Reassembly buffer: start from the original segments, mutate word slots.
    let mut output: Vec<String> = segments
        .iter()
        .map(|s| match s {
            Segment::Word(w) => w.clone(),
            Segment::Sep(s) => s.clone(),
        })
        .collect();

    let mut i = 0;
    while i < words.len() {
        // Multi-word match (longest window first to prefer specific phrases).
        let mut matched = false;
        if max_window >= 2 {
            let upper = max_window.min(words.len() - i);
            for window in (2..=upper).rev() {
                // Only compete against dictionary terms with the same word
                // count as the window — prevents out-of-bounds when a longer
                // term matches, and spurious matches against shorter terms.
                let candidates = match dict.multi.get(&window) {
                    Some(c) if !c.is_empty() => c.as_slice(),
                    _ => continue,
                };
                let joined: String = (i..i + window)
                    .map(|j| words[j].as_str())
                    .collect::<Vec<_>>()
                    .join(" ");
                if let Some(term) = best_term(&joined, candidates) {
                    let term_words: Vec<&str> = term.split_whitespace().collect();
                    // term_words.len() == window by construction (candidates
                    // were filtered to the same word count), so i+j is safe.
                    for (j, tw) in term_words.iter().enumerate() {
                        let seg_idx = word_indices[i + j];
                        if let Segment::Word(orig) = &segments[seg_idx] {
                            output[seg_idx] = apply_case(tw, orig);
                        }
                    }
                    matched = true;
                    i += window;
                    break;
                }
            }
        }
        if matched {
            continue;
        }
        // Single-word match.
        let w = &words[i];
        if let Some(term) = best_term(w, &dict.single) {
            let seg_idx = word_indices[i];
            if let Segment::Word(orig) = &segments[seg_idx] {
                output[seg_idx] = apply_case(&term, orig);
            }
        }
        i += 1;
    }

    output.concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dict_single(words: &[&str]) -> Dictionary {
        Dictionary {
            single: words
                .iter()
                .map(|w| Term {
                    text: w.to_string(),
                    lower: w.to_lowercase(),
                    key: phonetic_key(w),
                })
                .collect(),
            multi: HashMap::new(),
        }
    }

    #[test]
    fn exact_match_upgrades_casing() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("I live in madrid", &d), "I live in Madrid");
    }

    #[test]
    fn phonetic_match_replaces_misspelling() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("I love madriz", &d), "I love Madrid");
    }

    #[test]
    fn preserves_allcaps_context() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("MADRIZ is great", &d), "MADRID is great");
    }

    #[test]
    fn no_match_leaves_word_unchanged() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("hello world", &d), "hello world");
    }

    #[test]
    fn seseo_matches_barcelona() {
        // "barselona" (seseo of "Barcelona": ce→se) should match.
        let d = dict_single(&["Barcelona"]);
        assert_eq!(correct("I visited barselona", &d), "I visited Barcelona");
    }

    #[test]
    fn v_to_b_matches() {
        let d = dict_single(&["vaca"]);
        assert_eq!(correct("I saw a baca", &d), "I saw a vaca");
    }

    #[test]
    fn punctuation_preserved() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("madrid, city of", &d), "Madrid, city of");
    }

    #[test]
    fn empty_dictionary_passthrough() {
        let d = Dictionary::default();
        assert_eq!(correct("anything goes", &d), "anything goes");
    }

    #[test]
    fn multi_word_term_matched() {
        let mut multi = HashMap::new();
        multi.insert(
            2,
            vec![Term {
                text: "New York".to_string(),
                lower: "new york".to_string(),
                key: phonetic_key("New York"),
            }],
        );
        let d = Dictionary {
            single: Vec::new(),
            multi,
        };
        assert_eq!(correct("I live in new york", &d), "I live in New York");
    }

    #[test]
    fn phonetic_key_is_case_folded() {
        // The encoder lowercases first, so casing of the input never matters.
        assert_eq!(phonetic_key("Madrid"), phonetic_key("madrid"));
        assert_eq!(phonetic_key("BARCELONA"), phonetic_key("Barcelona"));
    }

    #[test]
    fn apply_case_upper_original_uppers_term() {
        assert_eq!(apply_case("madrid", "MADRID"), "MADRID");
    }

    #[test]
    fn apply_case_mixed_keeps_term_casing() {
        assert_eq!(apply_case("Madrid", "Mad"), "Madrid");
    }

    #[test]
    fn apply_case_no_letters_returns_term() {
        // Digits/punctuation only → original has no upper letters → term verbatim.
        assert_eq!(apply_case("madrid", "123"), "madrid");
    }

    #[test]
    fn word_not_in_dictionary_is_preserved() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("café", &d), "café");
    }

    #[test]
    fn empty_input_passthrough() {
        let d = dict_single(&["Madrid"]);
        assert_eq!(correct("", &d), "");
    }
}
