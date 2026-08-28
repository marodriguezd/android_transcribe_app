# Original User Request

## 2026-08-28T18:38:31Z

This is a single self-contained fix; keep it small and focused.

Implement a bulletproof Bluetooth headset audio capture pipeline, zero-latency pre-warming handshake, live visual indicator, and real-time microphone diagnostics in `android_transcribe_app` for v0.2.2 debug iterations.

Working directory: /data/data/com.termux/files/home/android_transcribe_app
Integrity mode: development

## Requirements

### R1. Java AudioRecord Pipeline with VOICE_COMMUNICATION & setPreferredDevice
Implement a dedicated Android `AudioRecord` bridge for Bluetooth/communication routing:
- Configure `MediaRecorder.AudioSource.VOICE_COMMUNICATION` with sample rate 16000 Hz, mono PCM 16-bit.
- Bind the recording stream directly to the target `AudioDeviceInfo` (Bluetooth SCO / BLE Headset) via `AudioRecord.setPreferredDevice()`.
- Stream raw PCM buffers directly into the Rust transcription engine (`voice_session.rs` / `recog_service.rs`) through efficient JNI direct buffers.

### R2. Zero-Latency Pre-Warming Handshake
- Pre-warm the Bluetooth communication channel in the background when the voice keyboard (`RustInputMethodService`) or floating bubble (`FloatingOverlayService`) is shown.
- Ensure tapping "Grabar" starts capturing immediately (0 ms latency) without clipping the speaker's first syllables.

### R3. Visual Indicator & In-App Microphone Diagnostics
- Display an active 🎧 icon indicator on the voice keyboard and floating overlay when audio is capturing from a Bluetooth wireless headset.
- Add a live "Microphone Diagnostics & Sound Test" section in Settings (`MainActivity.java`) displaying the active input device name and a real-time RMS input level meter.

### R4. Automated Pure-JVM Tests & Debug Delivery
- All business logic, device routing state machines, and configuration managers must pass 100% in pure-JVM unit tests (`./gradlew testDebugUnitTest`).
- CI/CD workflow `.github/workflows/debug_telegram.yml` must build and deliver `Aura_Transcribe_v0.2.2-debug.apk` directly to the user's Telegram chat.

## Acceptance Criteria

### Audio Capture & Bluetooth Routing
- [ ] Bluetooth headset microphone captures voice accurately on Android 12–15+ using `VOICE_COMMUNICATION` and `setPreferredDevice()`.
- [ ] Pre-warming on keyboard/overlay show eliminates initial Bluetooth connection delay when starting recording.
- [ ] Clean teardown releases `MODE_IN_COMMUNICATION`, resets `AudioRecord`, and restores system media audio to `MODE_NORMAL`.

### UI & Diagnostics
- [ ] Keyboard and overlay surface display a clear 🎧 icon when recording from a Bluetooth headset.
- [ ] Settings screen includes a real-time mic test visualizer confirming the active input device and live audio level.

### CI/CD & Build Integrity
- [ ] `check_translations.py` reports 100% parity across all 6 locales (257+ strings).
- [ ] Pure-JVM test suite passes with 0 failures on plain JVM.
- [ ] Debug APK is built and dispatched via Telegram bot without creating any GitHub release tags.
