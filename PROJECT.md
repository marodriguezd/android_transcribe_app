# Project: Offline Voice Input Android Application Optimizations

## Architecture
- **Language**: Kotlin/Java on Android, Rust for high-performance voice processing and transcription.
- **IPC / Process**: Unifying the IME service and Main activities under a single process to share native ONNX model weights (~700 MB JVM / native heap memory).
- **Core Engine**: `libandroid_transcribe_app.so` runs the ONNX Runtime-based transcription engine.
- **Audio pipeline**: CPAL input stream (Rust) writes audio buffers to transcription engine.
- **Storage**: Load assets directly from APK via file descriptors, eliminating extracting to internal storage.

## Code Layout
- `app/src/main/AndroidManifest.xml` - Android Manifest defining services, activities, and process configurations.
- `app/src/main/java/dev/notune/transcribe/` - Java/Kotlin source code:
  - `MainActivity.java` - Main launcher activity.
  - `TranscribeFileActivity.java` - Downsamples and transcribes audio files.
  - `MicLevelView.java` - Microphone volume visualization.
  - `SettingsManager.java` - SharedPreferences settings manager.
  - `RustInputMethodService.java` - IME service.
  - `LiveSubtitleService.java` - Overlay subtitle service.
- `src/` - Rust source code:
  - `lib.rs` - JNI interface definitions.
  - `assets.rs` - Asset handling and model extraction.
  - `engine.rs` - ONNX model manager and transcription engine.
  - `voice_session.rs` - CPAL recorder and audio callback handler.
  - `subtitle.rs` - Live subtitle audio processor.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Direct Asset Loading via FD (R1) | Package models uncompressed, retrieve FD, and pass to engine | None | IN_PROGRESS (50ef758e-d9e8-4cf1-9804-8bd8052e2858) |
| 2 | Process Unification (R2) | Remove process attribute from Manifest to unify process | None | PLANNED |
| 3 | Audio Callback JNI Decoupling (R3) | Introduce atomic state for audio level, poll from Java JNI | None | PLANNED |
| 4 | CPAL Formats & Resampler LPF (R4) | Dynamically query CPAL formats; implement low-pass resampler in Java | None | PLANNED |
| 5 | UI & Settings Polish (R5) | Exponential smoothing in MicLevelView; SettingsManager migration; subtitle timeout | None | PLANNED |
| 6 | E2E Testing & Hardening | Run E2E tests, execute adversarial coverage hardening | M1-M5 | PLANNED |

## Interface Contracts
### JNI Interface Updates
- `nativeGetAudioLevel()` to read the atomic audio level from Java.
- `nativeLoadModelFromFd(AssetFileDescriptor fd, ...)` or passing raw FD, offset, and size to Rust to load the model directly from the APK assets.
