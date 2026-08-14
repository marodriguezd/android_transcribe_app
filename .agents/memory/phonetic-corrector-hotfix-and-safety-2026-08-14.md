# Memory: Phonetic Corrector Accuracy Hotfix & Algorithmic Guardrails (2026-08-14)

> **Date:** 2026-08-14 20:45 UTC  
> **Release:** `v0.1.36` (versionCode 38)  
> **Status:** CI Green (`✓ build in 6m31s`, all gates PASS)  
> **Affected Component:** `src/corrector.rs` (custom dictionary post-ASR phonetic matching)  

---

## 1. Context & Incident Description

In release `v0.1.35`, a recursive optimization pass introduced `levenshtein_bounded(a, b, max_dist=2)` in `src/corrector.rs` with the intention of achieving zero heap allocations using stack-allocated row buffers `[usize; 65]` and banded dynamic programming.

Immediately following deployment, testing revealed that the phonetic corrector began **hallucinating and forcing dictionary word replacements into everyday sentences** (e.g. spoken words like *"como"*, *"para"*, *"hacer"* were being replaced by unrelated dictionary terms like *"whisper"*, *"gemini"*, *"modelo"*).

---

## 2. Root Cause Analysis

### The DP Matrix Propagation Flaw

The banded dynamic programming implementation in `levenshtein_bounded` suffered from uninitialized zero propagation outside the active band:

```rust
// BUGGY IMPLEMENTATION IN v0.1.35
let mut prev_row = [0usize; 65];
let mut curr_row = [0usize; 65];

for (i, &c1) in s1.iter().enumerate() {
    curr_row[0] = i + 1;
    let start_j = (i + 1).saturating_sub(max_dist).max(1);
    let end_j = (i + 1 + max_dist).min(len2);

    for j in start_j..=end_j {
        // ... calculated values ...
        curr_row[j] = val;
    }
    // Cells with index j > end_j REMAINED 0 in curr_row!
    prev_row[..=len2].copy_from_slice(&curr_row[..=len2]);
}
```

When index $j$ expanded to the right in subsequent iterations $i + 1$, the deletion formula evaluated:
$$\text{deletion} = \text{prev\_row}[j] + 1 = 0 + 1 = 1$$

Because `prev_row[j]` was `0` instead of infinity ($\infty$), almost **any pair of words with length difference $\le 2$ evaluated to an edit distance of $1$ or $2$**, regardless of phonetic or orthographic dissimilarity.

### Empirical Confirmation
- Spoken *"como"* (key: `komo`) vs *"whisper"* (key: `wisper`): actual Levenshtein is **6**, but `levenshtein_bounded` returned **`1`**!
- Spoken *"para"* (key: `para`) vs *"gemini"* (key: `hemini`): actual Levenshtein is **6**, but `levenshtein_bounded` returned **`1`**!
- Because calculated distance was $\le 2$, the corrector treated virtually all conversational vocabulary as misrecognized dictionary terms.

---

## 3. Resolution (v0.1.36)

1. **Restored Standard Unicode-Aware Levenshtein:**
   Removed `levenshtein_bounded` and restored [`strsim::levenshtein`](https://crates.io/crates/strsim), which is mathematically exact, thoroughly audited, and handles UTF-8 correctly.

2. **$O(1)$ Length Pre-Filtering:**
   To maintain sub-millisecond throughput without risky custom DP matrices, added an instant necessity filter in [`best_term`](file:///root/GitHub/android_transcribe_app/src/corrector.rs#L380-L395):
   ```rust
   let len_diff = key_len.abs_diff(t.key.len());
   if len_diff > MAX_PHONETIC_DISTANCE {
       continue;
   }
   let dist = strsim::levenshtein(&key, &t.key);
   if dist > MAX_PHONETIC_DISTANCE {
       continue;
   }
   ```
   Because $|\text{len}(a) - \text{len}(b)| \le \text{dist}(a, b)$, candidates with a length difference $> 2$ are skipped in 1 CPU instruction.

3. **Negative Regression Test Suite:**
   Added unit test [`unrelated_words_are_never_replaced`](file:///root/GitHub/android_transcribe_app/src/corrector.rs#L585-L592) asserting that full conversational sentences containing common vocabulary are never modified when tested against custom dictionaries.

---

## 4. Permanent Guardrails & Future Invariants

To prevent similar algorithmic regressions in future optimization passes:

1. **Mandatory Negative Unit Tests for Matching / Heuristics:**
   Any change to tokenizers, phonetic encoders, distance functions, or VAD endpointing must include negative test cases testing that completely unrelated inputs produce zero matches / zero false positives.
2. **Favor Proven Foundation Libraries over Micro-Optimized Matrix Reinventions:**
   Distance metrics and cryptographic/phonetic algorithms should use audited crates (`strsim`, `ring`, etc.) with cheap boundary filters (e.g. length pruning) rather than custom unsafe or banded stack tables unless covered by fuzzing and property-based testing.
3. **No Optimization Without Full Phrase Smoke Verification:**
   Performance improvements must be validated not only with single-word unit tests but with realistic multi-word sentences to detect false-positive cascades across sentences.
