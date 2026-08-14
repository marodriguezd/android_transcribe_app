# v0.1.35 — Extreme Latency, SIMD NEON & Hardware Optimizations (2026-08-14)

`versionCode 37` — Comprehensive mobile latency, SIMD NEON vector acceleration, lock-free audio pipelines, and hardware engine optimizations.

- **Streaming Latency Slashed by 220 ms:** Live streaming tick reduced from 300 ms to **80 ms** (-220 ms latency, 3.75x cadence boost) with hypothesis deduplication for immediate visual feedback.
- **ARM64 NEON Vectorization:** Native `vfmaq_f32` intrinsics (unrolled 16x across 4 vector accumulators) for RMS and sliding quiet-split energy detection (**1.81x faster**).
- **100% Lock-Free Real-Time Audio:** Replaced CPAL mutexes with atomic primitives (`AtomicU64`, `AtomicU32` CAS), eliminating lock contention and frame drops on high-priority audio threads.
- **Zero-Allocation Streaming Pipeline:** Direct mutable buffer draining in streaming cycles and vectorized PCM short-to-float conversions in Java ART.
- **Accelerated Phonetic Corrector:** Precomputed bigram cosine vectors (**2.41x faster**) and stack-allocated banded Levenshtein with early exit (**1.96x - 2.35x faster**, 0 heap allocations).
- **Hardware Acceleration Selector:** Added UI and marker settings for CPU (ARM NEON), NPU (NNAPI), and GPU (Vulkan) inference backends with 7-locale support.
- **Aggressive Compiler Flags:** C++ `-O3 -flto -ffast-math` and Rust `target-feature=+neon,+fp16,+dotprod` with Fat LTO profile.

The full version history is maintained in [`CHANGELOG.md`](CHANGELOG.md).
