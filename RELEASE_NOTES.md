# ✨ Aura Transcribe v0.2.0 — Next-Gen Offline AI Dictation Engine

`versionCode 39` — Major Milestone Release: Official rebirth and evolution of the project into **Aura Transcribe** (`com.auratranscribe.app`).

### 🌟 Key Highlights & Major Features

- **Rebranding to Aura Transcribe:** Complete project identity evolution with standalone application ID, unified multi-surface branding, and independent next-gen architecture.
- **Adaptive Bioluminescent Icon System:** Full modern Adaptive Icon suite (`ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`) featuring the radiant purple/cyan/indigo Aura ring, minimalist microphone, and Material You themed icon support on Android 8.0–15+.
- **Unified On-Screen Keyboard Branding:** Clean `RustInputMethodService` and `RecognitionService` manifests configured with `"Aura Transcribe"` across all Android system keyboard selectors and settings.
- **FUTO-Style Dynamic Audio Routing:** Full dynamic acquisition and automatic/manual selection between Bluetooth SCO headsets, BLE Audio, USB external mics, and device internal mics with persistent 3-way toggle.
- **AI Post-Processing Engine:** Integrated on-device SuperWhisper S1-mini and high-speed cloud LLMs (Groq, OpenAI, Cerebras, OpenRouter, Mistral, Together, Ollama) with 4 one-touch styles (*Clean*, *Formal*, *Casual*, *Verbatim*).
- **Floating Overlay Dictation & Auto-Paste:** Lightweight draggable overlay bubble (`SYSTEM_ALERT_WINDOW`) with instant Accessibility Service auto-paste.
- **Real-Time Live Subtitles & ADB AppOps Bypass:** On-device captions for any audio stream with zero-dialog `PROJECT_MEDIA` consent bypass instructions.
- **Phonetic & Dictionary Optimizations:** Early-exit banded Levenshtein dynamic programming, SIMD NEON vector acceleration, and full 247-string parity across all 6 locales (`de`, `es`, `fr`, `it`, `pt`, `ru`).
- **Hardened CI/CD & Automated Delivery:** Multi-gate automated pipeline covering Rust formatting, translation parity, pure-JVM test suite (14 suites, 100% pass), latency benchmarks, NDK toolchain compilation, and direct Telegram delivery.

---

### 📦 Assets

- `Aura_Transcribe_v0.2.0.apk` (Release APK with bundled model downloading capability)
