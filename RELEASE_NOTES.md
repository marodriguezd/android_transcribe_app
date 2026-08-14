# v0.1.36 — Phonetic Dictionary Hotfix & Hallucination Fix (2026-08-14)

`versionCode 38` — Critical hotfix for phonetic corrector matching accuracy and custom dictionary stability.

- **Fixed Phonetic Corrector Accuracy:** Restored robust Unicode-aware `strsim` Levenshtein distance with length pre-filtering in `corrector.rs`, resolving false positive matches on unrelated vocabulary.
- **Eliminated Dictionary Word Overrides:** Corrected dictionary behavior so that everyday vocabulary in transcriptions is never replaced or corrupted by custom dictionary terms.
- **Added Regression Test Suite:** Verified that unrelated conversational words are preserved 100% intact across all sentences.

The full version history is maintained in [`CHANGELOG.md`](CHANGELOG.md).
