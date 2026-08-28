# Changelog

Change log of **Aura Transcribe** (Next-Gen evolution of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app)).

# v0.2.2

Major audio & hardware release introducing native `AudioRecordBridge` communication pipeline, zero-latency Bluetooth pre-warming handshake, dynamic headset visual indicators, real-time microphone diagnostics, poison-tolerant concurrency hardening, and 100% i18n parity:

- **Dedicated Native `AudioRecordBridge` Communication Pipeline:** Implemented high-performance Java `AudioRecord` bridge operating in `VOICE_COMMUNICATION` mode (16 kHz 16-bit mono PCM) with direct `ByteBuffer` allocation and native JNI streaming (`pushAudioDirect`), forcing active microphone capture on wireless Bluetooth earbuds (AirPods, Galaxy Buds, Pixel Buds, Sony, etc.) and headset SCO/BLE devices.
- **Zero-Latency Bluetooth Pre-Warming Handshake:** Pre-warms the Bluetooth communication pipe in the background when the on-screen keyboard (`RustInputMethodService`) or floating overlay bubble (`FloatingOverlayService`) opens, eliminating the 300–500 ms connection handshake lag.
- **Dynamic Headset / Mic Indicators:** Visual real-time indicator on both the keyboard record button and floating dictation bubble, dynamically rendering 🎧 (`ic_headset`) for Bluetooth input and 🎙️ (`ic_mic`) for internal microphone.
- **Microphone Diagnostics & Live Sound Test:** Comprehensive sound test UI in `MainActivity` with real-time RMS decibel level meter (`MicLevelView`), active device indicator, and 1-tap start/stop verification.
- **Multi-Agent Robustness & Concurrency Hardening (Victory Audit):** Bounded `ByteBuffer` reuse, poison-tolerant mutex guards in Rust, atomic session generation validation, idempotent `cleanupNative` teardowns, and comprehensive `AudioRecordBridgeTest.java` suite (109 JVM tests passing).
- **100% i18n Parity (266 Strings across 6 Locales):** Complete translation coverage across German, Spanish, French, Italian, Portuguese, and Russian.

# v0.2.1

Quality, audio, and intelligence release introducing the universal WhisperFlow prompt engine, curated model packs for lightweight/offline workflows, default internal microphone selection, wireless Bluetooth headset detection, AI post-processing configuration guardrails, and smart UI feedback:

- **Universal WhisperFlow-Level Prompt Engine:** Engineered an advanced system prompt for all model tiers (7B–27B up to 70B/120B) capable of context-adaptive formatting (Markdown bullet/numbered lists for shopping/tasks, atomic technical casing for coding/agent prompts, fluent conversational cadence), disfluency removal, self-correction resolution, and meta-voice command execution (*"borra eso"*, *"entre comillas"*, *"en negrita"*).
- **Curated Recommended Model Packs:** Added interactive packs dialog in `ModelsActivity` featuring 1-tap presets for *⚡ Ultralight Offline Pack (Canary 180M / Parakeet 110M + S1-mini)*, *🏆 Pro Integrated Pack (Nemotron 0.6B + AI Fix Cloud)*, and *🚀 Whisper Extended Pack (Whisper Large-v3-Turbo)*.
- **Built-in Device Mic by Default:** Default microphone mode is set to `MIC_MODE_BUILTIN_ONLY` ensuring 100% out-of-the-box capture reliability. Users can toggle "Automático" or "Solo Bluetooth" on demand.
- **Wireless Bluetooth Headset Detection & Routing:** Overhauled `AudioDeviceManager` to seamlessly detect and route audio from wireless earbuds (SCO, A2DP, BLE Audio, Hearing Aids), USB mics, and wired headsets with runtime `BLUETOOTH_CONNECT` permission handling on Android 12–15+.
- **AI Fix Configuration Guardrails:** `SettingsManager.isPostProcessEnabled()` requires `isPostProcessConfigured()`. AI post-processing cannot run or stay enabled without a valid API key (Cloud providers) or the S1-mini model installed (Local provider).
- **Smart Toggle Feedback:** On-screen keyboard (`RustInputMethodService`) and floating overlay (`FloatingOverlayService`) reject turning on "AI Fix" when not configured, showing a localized toast guidance message.
- **Settings Screen Error Handling:** `PostProcessSettingsActivity` validates API key presence and model installation upon toggling and saving.
- **100% i18n Parity (257 Strings across 6 Locales):** Complete translation coverage in all supported locales (`de`, `es`, `fr`, `it`, `pt`, `ru`).
- **Guardrail & Audio Test Suite:** Added plain-JVM decoupled unit test suite `PostProcessConfigurationGuardTest.java` and audio device routing suite `AudioDeviceManagerTest.java`.

# v0.2.0

Major Milestone Release: Official rebranding to **Aura Transcribe** (`com.auratranscribe.app`), Adaptive Icon architecture, Bluetooth audio routing, and AI post-processing layer:

- **Official Rebranding:** Redesigned application namespace and manifest to `com.auratranscribe.app` ("Aura Transcribe") with unified IME and speech recognition service labeling.
- **Adaptive Icon Architecture:** Implemented vector-layered Adaptive Icons (`ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`) with bioluminescent aura gradient, minimalist microphone, and Material You dynamic theming (Android 8.0–15+). Purged legacy 1MB raster bitmap.
- **FUTO-Style Dynamic Audio Routing:** Added `AudioDeviceManager` supporting seamless switching between Bluetooth SCO headsets, BLE Audio, USB external microphones, and internal phone microphones.
- **AI Post-Processing Layer:** Integrated SuperWhisper S1-mini and OpenAI-compatible API providers (Groq, Cerebras, OpenRouter, OpenAI, Mistral, Together, Ollama) with 4 formatting presets (*Clean*, *Formal*, *Casual*, *Verbatim*).
- **Floating Overlay Dictation:** Built lightweight overlay bubble (`SYSTEM_ALERT_WINDOW`) and Accessibility auto-paste service.
- **Pure-JVM Test Harness & Benchmarks:** Expanded unit test coverage to 14 suites (100% JVM decoupled pass rate) and automated SIMD latency benchmarks.
- **Hardened CI/CD Pipeline:** Fully automated multi-gate GitHub Actions build workflows with direct Telegram APK delivery and automated error extraction.

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
