# ✨ Aura Transcribe (Android)

[![Build & Deliver](https://img.shields.io/badge/CI%2FCD-100%25%20Passing-brightgreen?style=flat-square&logo=githubactions)](https://github.com/marodriguezd/android_transcribe_app/actions)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--35%2B)-blue?style=flat-square&logo=android)](https://github.com/marodriguezd/android_transcribe_app)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64%20(aarch64)%20%2B%2016KB%20Pages-orange?style=flat-square&logo=arm)](https://github.com/marodriguezd/android_transcribe_app)
[![Rust Core](https://img.shields.io/badge/Core-Rust%202021%20%2B%20ARM%20NEON%20SIMD-red?style=flat-square&logo=rust)](https://github.com/marodriguezd/android_transcribe_app)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

**Aura Transcribe** is a next-generation, privacy-first offline speech recognition, live dictation, and AI post-processing system for Android. Powered by safe Rust, ARM NEON SIMD acceleration, and native ARM64 GGML inference kernels, Aura Transcribe delivers studio-grade speech-to-text directly on your device — zero telemetry, zero audio leakage, and zero mandatory network connection.

[<img src="https://i.ibb.co/q0mdc4Z/get-it-on-github.png" alt="Get it on GitHub" height="80">](https://github.com/marodriguezd/android_transcribe_app/releases/latest)

---

## 🧬 Heritage & Project Evolution

Aura Transcribe originated from the pioneering work in [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app) (*Offline Voice Input v0.1.18*). While proudly maintaining this lineage, robust core foundation, and offline-first philosophy, Aura Transcribe has completely evolved into an independent, advanced speech-to-text powerhouse featuring dynamic multi-device audio routing, on-device and cloud AI post-processing, floating dictation overlays, pure Rust crates, and a hardened multi-gate CI/CD infrastructure.

### 📊 Evolution Matrix: v0.1.18 vs Aura Transcribe

| Feature / Dimension | Original Roots (`v0.1.18`) | ✨ Aura Transcribe (`v0.2.2+`) |
| :--- | :--- | :--- |
| **Application Identity** | Offline Voice Input (`dev.notune.transcribe`) | **Aura Transcribe (`com.auratranscribe.app`)** |
| **Android Architecture** | Android 8–14 legacy (API 34) | **Android 17 Ready (API 35+), Edge-to-Edge, 16KB Page Size Alignment** |
| **Visual Architecture** | Legacy static raster bitmap | **Adaptive Bioluminescent Vector Icons + Material You Themed** |
| **Audio Capture & Routing** | Internal mic only, scalar loops | **Native CPAL + ARM NEON SIMD RMS, Bluetooth SCO/BLE + Built-in Mode Switching** |
| **AI Post-Processing** | None | **On-Device SuperWhisper S1-mini + Cloud LLMs (Groq, OpenAI, Cerebras, OpenRouter)** |
| **Modular Core** | Monolithic Rust cdylib | **Decoupled `crates/aura-core` Crate + Metaphone ES/EN + Fast Levenshtein DP** |
| **Stylistic Formatting** | Raw transcription output | **4 One-Touch Styles (Clean, Formal, Casual, Verbatim) + Custom Prompts** |
| **Dictation Overlay** | Activity popup only | **Floating Bubble Overlay (`SYSTEM_ALERT_WINDOW`) + Accessibility Auto-Paste** |
| **Live Subtitles** | Basic window | **Real-Time Subtitles + ADB AppOps Zero-Dialog Consent Bypass** |
| **Continuous Integration** | Manual local builds | **Fastlane Automation + Multi-Gate GitHub Actions + Telegram APK Delivery** |

---

## ⚡ Core Capabilities & Features

```
                   ┌────────────────────────────────────────┐
                   │          AURA TRANSCRIBE CORE          │
                   └───────────────────┬────────────────────┘
                                       │
       ┌───────────────────────────────┼───────────────────────────────┐
       ▼                               ▼                               ▼
┌──────────────┐             ┌───────────────────┐           ┌──────────────────┐
│ Audio Engine │             │  Inference Core   │           │ Post-Processing  │
├──────────────┤             ├───────────────────┤           ├──────────────────┤
│• Bluetooth   │             │• Nemotron 3.5 ASR │           │• S1-mini (Local) │
│• BLE Audio   │ ──(Audio)─► │• Whisper.cpp/GGML │ ──(Text)► │• Groq / Cerebras │
│• USB Headset │             │• 40 Locales / ASR │           │• OpenAI / Router │
│• Internal    │             │• Streaming Chunks │           │• Clean / Formal  │
└──────────────┘             └───────────────────┘           └────────┬─────────┘
                                                                      │
                                                                      ▼
                                                            ┌───────────────────┐
                                                            │  Output Surfaces  │
                                                            ├───────────────────┤
                                                            │• Keyboard Popup   │
                                                            │• System Speech IME│
                                                            │• Floating Overlay │
                                                            │• Live Subtitles   │
                                                            └───────────────────┘
```

- **🎙️ Seamless Voice Input in Any App:** Tap the microphone on SwiftKey, AnySoftKeyboard, HeliBoard, or any browser voice search. Transcribes directly into active text fields.
- **🔒 100% On-Device & Private:** The default Nemotron 3.5 ASR Streaming model (Q8_0) runs completely on your CPU. No audio samples ever leave your device.
- **🎧 Dynamic Bluetooth & External Mic Router:** Automatically switches between Bluetooth SCO headsets, BLE Audio, USB external microphones, and internal phone mics with manual override switches (*Auto*, *Bluetooth Only*, *Built-in Only*).
- **✨ Intelligent AI Post-Processing Layer:** Transform raw dictation into polished text using either **local on-device S1-mini** or high-speed cloud providers (**Groq, OpenAI, Cerebras, OpenRouter, Mistral, Together, Ollama**).
  - **Clean:** Removes hesitation sounds ("um", "ah"), stutters, and adds clean punctuation.
  - **Formal:** Formats dictation into professional emails and business documentation.
  - **Casual:** Formats speech into natural, conversational messaging.
  - **Verbatim:** Exact phonetic transcript with zero alterations.
- **💬 Floating Dictation Overlay & Auto-Paste:** Trigger dictation from any screen with a lightweight draggable overlay bubble and optional Accessibility Service auto-paste.
- **📺 Real-Time Live Subtitles:** Instant on-device transcription for podcasts, videos, and phone calls with zero lag.
- **🌐 40+ Language Locales:** Native streaming language auto-detection for English, Spanish, German, French, Italian, Portuguese, Russian, Japanese, Mandarin, and more.

---

## 📱 Screenshots & User Interface

<p float="left">
  <img src=".screenshots/screenshot_home.png" width="30%" alt="Home Dashboard" />
  <img src=".screenshots/screenshot_recording.png" width="30%" alt="Voice Recording Panel" />
  <img src=".screenshots/screenshot_subtitles.png" width="30%" alt="Live Subtitles Overlay" />
</p>

---

## 🚀 Getting Started & Usage

### 1. Standard Voice Input (Recommended)
1. Open **Aura Transcribe** and grant the Microphone permission.
2. In any app, tap the **microphone** icon on your keyboard (e.g. Microsoft SwiftKey) or a voice search field.
3. The compact Aura Transcribe bottom sheet slides up, transcribes your speech with live partial hypotheses, and inserts the final text upon silence or tap.

### 2. Dedicated Voice Keyboard (IME)
Prefer a dedicated dictation keyboard?
1. Enable **Aura Transcribe** in *Android Settings → System → Languages & Input → On-Screen Keyboards*.
2. Switch to Aura Transcribe using the globe/keyboard switcher key on your keyboard.
3. Tap the recording surface to speak. Text is retained and inserted seamlessly even across app switches.

### 3. Live Subtitles & Zero-Dialog ADB Permission
Tap **Start Live Subtitles** and select *Share entire screen* for real-time captions.

To permanently bypass the Android media projection consent prompt on your personal device:
```bash
adb shell appops set --user 0 dev.notune.transcribe PROJECT_MEDIA allow
```
*(To revert: `adb shell appops set --user 0 dev.notune.transcribe PROJECT_MEDIA default`)*

---

## ⌨️ Keyboard Interoperability Guide

| Keyboard App | Compatibility Mode | Integration Details |
| :--- | :--- | :--- |
| **FUTO Keyboard** | IME Switcher & Native IME | Seamlessly switch to Aura Transcribe via globe/switch key for enhanced offline dictation with AI post-processing, Bluetooth mic routing, and floating overlays. |
| **Microsoft SwiftKey** | Native Popup Panel | Opens the compact bottom panel directly. *(Turn off "Multi-modal voice typing" in SwiftKey Rich Input settings)* |
| **AnySoftKeyboard** | Native Speech Intent | Mic key launches Aura Transcribe panel directly. |
| **HeliBoard / FlorisBoard** | IME Switcher | Mic key switches to the Aura Transcribe voice keyboard instantly. |
| **Fossify / OpenBoard / Unexpected** | IME Switcher | Switches to Aura Transcribe voice keyboard. |
| **Gboard** | Incompatible | Google hardcodes voice typing exclusively to Google Speech Services. |

---

## 🛠️ Prerequisites & Building from Source

### Toolchain Requirements
| Component | Minimum Version | Installation / Source |
| :--- | :--- | :--- |
| **JDK** | JDK 17 (LTS) | Android Studio bundled JBR or OpenJDK 17 |
| **Android SDK** | API 34 (Android 14) | SDK Manager |
| **Android NDK** | `28.0.13004108` | `sdkmanager "ndk;28.0.13004108"` |
| **Rust** | `1.78.0+` (edition 2021) | `rustup target add aarch64-linux-android` |
| **cargo-ndk** | Latest | `cargo install cargo-ndk` |
| **CMake & Ninja** | Latest | `apt install cmake ninja-build` |

### Build Commands

```bash
# 1. Clone repository
git clone https://github.com/marodriguezd/android_transcribe_app.git
cd android_transcribe_app

# 2. Compile Rust native libraries for ARM64
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release

# 3. Assemble Debug APK
./gradlew assembleDebug
# Artifact generated at: app/build/outputs/apk/debug/app-debug.apk

# 4. Run automated test suites & quality gates
./gradlew testDebugUnitTest
python3 scripts/check_translations.py
python3 scripts/bench_performance.py
```

---

## 📂 Repository Topology

```text
android_transcribe_app/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # App declaration, permissions & IME registration
│   │   ├── java/dev/notune/transcribe/  # Android Java layer (AudioDeviceManager, IME, UI)
│   │   ├── res/
│   │   │   ├── drawable/                # Bioluminescent vector & adaptive icon drawables
│   │   │   ├── mipmap-anydpi-v26/       # Android 8.0–15+ adaptive icon manifests
│   │   │   ├── values/ (and values-*/)  # 266 strings across 6 localized languages
│   │   │   └── xml/                     # IME method descriptor & accessibility configs
│   │   └── jniLibs/arm64-v8a/           # libandroid_transcribe_app.so & libc++_shared.so
│   └── build.gradle.kts                 # Android Gradle Plugin configuration (AGP 8.7.3)
├── crates/
│   └── aura-core/                       # Pure Rust modular crate (Phonetics, Levenshtein, Bigrams)
├── src/                                 # Rust native engine & JNI bridge (cdylib)
│   ├── engine.rs                        # transcribe.cpp GGML inference binding & streaming pump
│   ├── audio.rs                         # ARM NEON SIMD RMS, sum squares & quietest split
│   ├── post_processor.rs                # AI Post-processor & WhisperFlow templates
│   └── lib.rs                           # JNI bridge entry points
├── fastlane/                            # Automated deployment & metadata publishing
├── scripts/
│   ├── check_translations.py            # Automated i18n parity quality gate (266 strings)
│   ├── bench_performance.py             # Latency, RMS SIMD & Levenshtein DP benchmark
│   └── live_audio_diagnostics.sh        # Real-time ADB mic & RMS audio monitor
├── .github/workflows/
│   ├── android_release.yml              # Official signed release pipeline on tag v*
│   └── debug_telegram.yml               # Automated CI/CD pipeline with Telegram delivery
└── Cargo.toml                           # Rust workspace manifest & release profile
```

---

## 🤝 Acknowledgments & Open-Source Lineage

- **Original Project Root:** [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app) (*Offline Voice Input v0.1.18*).
- **Speech Model Architecture:** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) by NVIDIA (GGUF quantization by [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf), licensed under CC-BY 4.0).
- **Inference Engine:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) and [ggml](https://github.com/ggerganov/ggml) by CJ Pais and Georgi Gerganov.

---

## 📄 License

Distributed under the [MIT License](LICENSE).
