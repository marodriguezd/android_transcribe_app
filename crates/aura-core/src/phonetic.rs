//! Phonetic encoding and distance computation for Spanish and English speech.
//!
//! Encodes words and multi-word phrases into phonetic representation keys where
//! phonetically similar words yield identical or low-edit-distance keys.

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
/// windows. Consecutive duplicate characters collapse (e.g. `rr`→`r`).
pub fn phonetic_key(input: &str) -> String {
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
pub fn collapse_duplicates(s: &str) -> String {
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

/// Computes the Levenshtein distance between two strings.
#[inline]
pub fn levenshtein_distance(a: &str, b: &str) -> usize {
    strsim::levenshtein(a, b)
}

/// Computes the phonetic distance (Levenshtein distance between their phonetic keys).
#[inline]
pub fn phonetic_distance(a: &str, b: &str) -> usize {
    let key_a = phonetic_key(a);
    let key_b = phonetic_key(b);
    levenshtein_distance(&key_a, &key_b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_case_folding_and_determinism() {
        assert_eq!(phonetic_key("Madrid"), phonetic_key("madrid"));
        assert_eq!(phonetic_key("BARCELONA"), phonetic_key("Barcelona"));
        assert_eq!(phonetic_key("Whisper"), phonetic_key("whisper"));
    }

    #[test]
    fn test_spanish_phonetic_equivalences() {
        // seseo: z/c(e,i) -> s
        assert_eq!(phonetic_key("zapato"), phonetic_key("sapato"));
        assert_eq!(phonetic_key("cena"), phonetic_key("sena"));
        assert_eq!(phonetic_key("cielo"), phonetic_key("sielo"));

        // yeísmo: ll -> y
        assert_eq!(phonetic_key("calle"), phonetic_key("caye"));
        assert_eq!(phonetic_key("lluvia"), phonetic_key("yubia"));

        // v -> b
        assert_eq!(phonetic_key("vaca"), phonetic_key("baca"));
        assert_eq!(phonetic_key("vino"), phonetic_key("bino"));

        // silent h
        assert_eq!(phonetic_key("hola"), phonetic_key("ola"));
        assert_eq!(phonetic_key("huevo"), "uebo");

        // ñ -> ny
        assert_eq!(phonetic_key("caña"), "kanya");

        // qu -> k
        assert_eq!(phonetic_key("queso"), "keso");
    }

    #[test]
    fn test_english_phonetic_digraphs() {
        // ph -> f
        assert_eq!(phonetic_key("phone"), "fone");
        // gh -> f
        assert_eq!(phonetic_key("ghost"), "fost");
        // kn -> n
        assert_eq!(phonetic_key("knife"), "nife");
        // wr -> r
        assert_eq!(phonetic_key("write"), "rite");
        // th -> d
        assert_eq!(phonetic_key("this"), "dis");
        // wh -> w
        assert_eq!(phonetic_key("what"), "wat");
        // sh -> s
        assert_eq!(phonetic_key("ship"), "sip");
        // x -> ks
        assert_eq!(phonetic_key("box"), "boks");
    }

    #[test]
    fn test_collapse_duplicates() {
        assert_eq!(collapse_duplicates("perro"), "pero");
        assert_eq!(collapse_duplicates("cooperative"), "coperative");
        assert_eq!(collapse_duplicates("aaaa"), "a");
        assert_eq!(collapse_duplicates(""), "");
    }

    #[test]
    fn test_phonetic_distance() {
        assert_eq!(phonetic_distance("Madrid", "madriz"), 1);
        assert_eq!(phonetic_distance("Barcelona", "barselona"), 0);
        assert_eq!(
            phonetic_distance("hello", "world"),
            levenshtein_distance(&phonetic_key("hello"), &phonetic_key("world"))
        );
    }
}
