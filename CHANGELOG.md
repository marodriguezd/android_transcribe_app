# Changelog

Change log of **android_transcribe_app** (fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app)).

# v0.1.36

Hotfix for custom dictionary phonetic matching and hallucination issue:

- **Fixed Phonetic Corrector Accuracy:** Restored robust Unicode-aware `strsim` Levenshtein distance with length pre-filtering in `corrector.rs`, replacing the flawed banded algorithm that caused false positive matches on unrelated vocabulary.
- **Eliminated Dictionary Hallucinations:** Transcripts now preserve all non-dictionary words verbatim, ensuring only valid phonetically similar misrecognitions are corrected.
- **Added Regression Suite:** Added unit test coverage ensuring arbitrary conversational words are never replaced by custom dictionary terms.

# v0.1.35

Comprehensive mobile latency, SIMD NEON vector acceleration, lock-free audio pipelines, and hardware engine optimizations:

- **Live Streaming Tick Reduced to 80 ms:** Slashed streaming latency by 220 ms (3.75x cadence boost) with hypothesis deduplication for immediate visual feedback.
- **ARM64 NEON Vectorization:** Native `vfmaq_f32` intrinsics (unrolled 16x across 4 vector accumulators) for RMS and sliding quiet-split energy detection (**1.81x faster**).
- **100% Lock-Free Real-Time Audio:** Replaced CPAL mutexes with atomic primitives (`AtomicU64`, `AtomicU32` CAS), eliminating lock contention and frame drops on high-priority audio threads.
- **Zero-Allocation Streaming Pipeline:** Direct mutable buffer draining in streaming cycles and vectorized PCM short-to-float conversions in Java ART.
- **Accelerated Phonetic Corrector:** Precomputed bigram cosine vectors (**2.41x faster**) and stack-allocated banded Levenshtein with early exit (**1.96x - 2.35x faster**, 0 heap allocations).
- **Hardware Acceleration Selector:** Added UI and marker settings for CPU (ARM NEON), NPU (NNAPI), and GPU (Vulkan) inference backends with 7-locale support.
- **Aggressive Compiler Flags:** C++ `-O3 -flto -ffast-math` and Rust `target-feature=+neon,+fp16,+dotprod` with Fat LTO profile.

# v0.1.34

Refined and enhanced default AI post-processing prompt:

- **Enhanced Markdown Structure & Spacing:** Restructured the built-in system prompt with clear markdown headings, horizontal dividers, and clean vertical rhythm for maximum readability and LLM parsing accuracy.
- **Structured Rule Categorization:** Reorganized editing rules (oral clutter removal, on-the-fly self-correction resolution, phonetic reconstruction, language consistency, dictation commands, and technical casing preservation) with explicit examples and formatting tags.
- **Atomic Technical Token Handling:** Explicit guidelines for preserving `camelCase`, `PascalCase`, `snake_case`, `kebab-case`, `SCREAMING_SNAKE_CASE`, CLI flags (`--force`), URLs, and code identifiers.
- **Strict Output Constraints:** Formatted constraints ensuring zero conversational filler, greetings, or unwanted markdown code block wrapping in model responses.
