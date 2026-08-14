# Taskboard: Extreme Low-Latency & Mobile Hardware Optimization

| Task ID | Description | Priority | Status | Owner | Verification |
| --- | --- | --- | --- | --- | --- |
| OPT-1 | SIMD/NEON vector math & zero-alloc audio buffers (`audio.rs`, `voice_session.rs`, `recog_service.rs`, `subtitle.rs`) | P0 | IN_PROGRESS | oma-executor | Rust tests + fast_rms / zero-alloc pipeline |
| OPT-2 | Streaming tick latency reduction (300ms -> 80ms) & partial deduplication (`engine.rs`) | P0 | PENDING | oma-executor | Engine streaming latency verification |
| OPT-3 | Compiler release optimization (Fat LTO, codegen-units=1) & C++ flags (`Cargo.toml`, `build.gradle.kts`) | P0 | PENDING | oma-executor | Release profile & cmake args verification |
| OPT-4 | Hardware backend selector (CPU / NPU / GPU) (`ModelsActivity.java`, layout, 7 locales, `engine.rs`) | P1 | PENDING | oma-executor | `check_translations.py` + UI selector |
| OPT-5 | Phonetic corrector performance optimizations (`corrector.rs`) | P1 | PENDING | oma-executor | Corrector tests & zero-alloc parsing |
| OPT-6 | Automated unit tests & GitHub Actions CI/CD Gauntlet verification | P0 | PENDING | oma-verifier | `testDebugUnitTest` + CI run evidence |
