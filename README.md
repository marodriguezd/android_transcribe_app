# Offline Voice Input (Android)

> A clean-up release (v0.9.0): narrative rebuilt around three things this fork does differently from upstream (`notune/android_transcribe_app`).

Offline, privacy-focused voice input keyboard, live subtitle tool, and AI-powered text refiner for Android. Built with Rust.

[<img src="https://i.ibb.co/q0mdc4Z/get-it-on-github.png"
alt="Get it on GitHub"
height="80">](https://github.com/marodriguezd/android_transcribe_app/releases/latest)

## Three pillars

**1. Transcription engine.** We kept the Rust ONNX pipeline (transcribe-rs) + NVIDIA Parakeet TDT 0.6B + Canary 180M Flash, INT8 quantized. Upstream moved to `transcribe.cpp` + Whisper; this fork does not.

- **Fast** (0.6B, ~640 MB on disk) — best accuracy for general dictation
- **Fastest** (180M Flash, ~395 MB) — smaller footprint, fits older / low-RAM devices
- **Use without model** — register as the speech-to-text provider without using it; download later

Once downloaded, models live in the app sandbox and load via `mmap` with zero copy. NNAPI + XNNPACK providers on Android.

**2. AI post-processing (optional).** Sends the raw transcript to any OpenAI-compatible endpoint (OpenAI, Gemini, Groq, LM Studio, local Ollama) with the prompt of your choosing.

- **Multi-prompt templates** — switch the active prompt per dictation (formal tone, casual tone, list-cleanup, target language) without editing inline
- **Edit, duplicate, import, export** prompts as JSON
- **Default app prompt is editable + exportable** — customise once; "Reset to default" returns to the canonical version
- **Default "My words" dictionary is editable + exportable** — same model
- Built-in prompt is tuned for speech-to-text repair (self-corrections, filler removal, ITN, list-formatting) without paraphrasing the speaker's words

**3. Model selection is a real first-run choice.** Welcome dialog shows the three options with their footprint inline (`Fast / 0.6B`, `Fastest / 180M`, `Use without / No model`). Switch models anytime. Skip on first launch if you only want the speech-to-text provider registered.

## Features

- **Offline Transcription:** Uses deep learning models (Parakeet TDT, Canary 180M Flash) to transcribe speech entirely on-device.
- **Zero-Copy Model Loading:** ONNX models are memory-mapped (`mmap`) directly from APK assets — no extraction step, instant startup.
- **Hardware Acceleration:** Powered by **NNAPI** and **XNNPACK** for high-performance inference on mobile NPUs and CPUs.
- **Lock-Free Audio Pipeline:** Audio level updates use atomic operations (`AtomicU32`), fully decoupled from the JVM — no GC pauses during capture.
- **Supported Languages:** Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish, Russian, Ukrainian
- **Voice Input Keyboard** — register as your device's speech-to-text provider; tap the mic on a SwiftKey / HeliBoard / OpenBoard keyboard and choose "Offline Voice Input".
- **Live Subtitles:** Real-time captions for any audio/video playing on your device, with automatic silence detection and clearing.
- **AI Post-Processing:** Optional text refinement using LLMs (OpenAI, Gemini, Ollama, Groq, LM Studio). Multi-prompt template system.
- **Custom Dictionary & Hotwords:** Use a custom dictionary to instantly fix common misspellings (`error=correction`) or provide AI context hints for your specific vocabulary. The default "My words" dictionary is editable and exportable.
- **Privacy-First:** No audio data leaves your device. Local transcription by default; post-processing is opt-in to a URL you control.
- **Rust Backend:** High-performance native code using **transcribe-rs** and **ONNX Runtime 1.25.0**.

## How it differs from upstream

This fork (`marodriguezd/android_transcribe_app`) diverges from `notune/android_transcribe_app` since the v0.1.17 split point. The three pillars above summarise the divergences. The two packages share the namespace `dev.notune.transcribe`, so they cannot coexist on the same device.

Upstream's recent direction (v0.1.18) switched the engine to `transcribe.cpp` + Whisper and added a thread-count setting. We did not follow either of those changes — staying with `transcribe-rs` + Parakeet/Canary because:

- Parakeet / Canary ship as quantized INT8 ONNX models that load via `mmap` — no model-side dependencies, no separate inference process
- The Rust + ONNX Runtime pipeline is the same `cdylib` Android already expects
- Canary 180M Flash gives us a smaller on-device variant for older devices

## Prerequisites

| Dependency | Installation |
|---|---|
| **JDK 17** | Android Studio (bundled) or `sudo pacman -S jdk17-openjdk` |
| **Android SDK** | Via Android Studio or `sdkmanager` |
| **Android NDK** | `sdkmanager "ndk;28.2.13676358"` |
| **Rust** | [rustup.rs](https://rustup.rs) + `rustup target add aarch64-linux-android` |
| **cargo-ndk** | `cargo install cargo-ndk` |

### Local Configuration

Create a `local.properties` file in the project root (this file is gitignored):

```properties
sdk.dir=/path/to/your/Android/Sdk
```

If your default Java is not JDK 17, uncomment and set `org.gradle.java.home` in `gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk17
# Examples:
#   /opt/android-studio/jbr          (Android Studio bundled JBR)
#   /usr/lib/jvm/java-17-openjdk     (System JDK 17)
```

## Building

### Quick Build (Recommended)
```bash
./build.sh
# Builds the Rust native library and assembles the release APK in one step.
# Output: app/build/outputs/apk/release/app-release.apk
```

### Debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release APK
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Signing

For release builds, place a `release.keystore` in the project root and set these environment variables:

```bash
export KEY_ALIAS=release
export KEY_PASS=yourpassword
export STORE_PASS=yourpassword
```

### Model Assets

The Parakeet TDT model files (~670 MB) are automatically downloaded from HuggingFace during the first build via a Gradle task. Checksums are verified with SHA-256. No manual download is needed. At runtime, models are loaded directly from the APK via memory mapping — no extra disk space is required beyond the APK itself.

### AI Post-Processing

To enable AI-powered text refinement:
1. Open the app and navigate to **Post-Processing Settings**.
2. Enable the feature and enter your **API Base URL** (e.g., `https://api.openai.com/v1`).
3. Provide your **API Key** (optional for local models like Ollama).
4. Tap **Refresh** next to the Model Name to fetch available models from `/v1/models`.
5. Open **Manage prompts** and pick an active template, or write your own. Use `${output}` as a placeholder for the raw text.
6. Save and start dictating!  
   Multiple prompts are supported — switch the active one between dictations without retyping. The default app prompt and the default "My words" dictionary are editable; export them as JSON to share across devices.

## Project Structure

```
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/notune/transcribe/   # Android Java code
│       ├── res/                          # Resources (layouts, drawables, etc.)
│       ├── assets/                       # Model files (downloaded at build time)
│       └── jniLibs/                      # Native .so files (built by cargo-ndk)
├── src/                                  # Rust source code (cdylib)
├── transcribe-rs/                        # Rust transcription library (submodule)
├── Cargo.toml                            # Rust workspace
├── build.gradle.kts                      # Root Gradle config
├── app/build.gradle.kts                  # App module config (AGP 8.7.3)
├── build.sh                              # One-command build script
├── settings.gradle.kts
├── gradle.properties
└── fastlane/metadata/android/            # F-Droid metadata
```

## Acknowledgments

- **Speech Models:**
    - [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by NVIDIA, ONNX-quantized by [istupakov](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx). CC-BY 4.0.
    - [Canary 180M Flash](https://huggingface.co/nvidia/canary-180m-flash) by NVIDIA, ONNX-quantized by [istupakov](https://huggingface.co/istupakov/canary-180m-flash-onnx). CC-BY 4.0.
- **Inference Backend:** [transcribe-rs](https://github.com/cjpais/transcribe-rs) by CJ Pais.
- **Upstream fork point:** [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app) v0.1.17.

## License

[MIT](LICENSE)
