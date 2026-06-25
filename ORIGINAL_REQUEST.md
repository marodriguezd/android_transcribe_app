# Original User Request

## Initial Request — 2026-06-25T16:54:26Z

Resolve the performance, memory, and architectural issues in the Offline Voice Input Android application based on the audit findings.

Working directory: /home/marodriguezd/Github/android_transcribe_app
Integrity mode: development

## Requirements

### R1. Eliminate Model Copying and Dual-Storage Overhead
Modify the app to load the ONNX model files directly from the APK/AAB assets without extracting/copying them to the app's internal filesystem (`getFilesDir()`). Ensure model files are packaged uncompressed to support direct file descriptor loading.

### R2. Eliminate Multi-Process Memory Duplication
Ensure that the Input Method Service (IME) and the main activities (such as `MainActivity` and `TranscribeFileActivity`) run within the same process so they can share a single native instance of the loaded model weights, saving ~700 MB of RAM.

### R3. Remove JNI Calls from the Real-Time Audio Callback Thread
Decouple the real-time CPAL audio thread from JNI boundary transitions. Instead of calling JNI methods like `onAudioLevel` from the callback thread, write updates to a thread-safe / atomic native state and poll this state from Java/Kotlin.

### R4. Improve CPAL Audio Formats Compatibility and Resampler Quality
Update the Rust/CPAL microphone recording to dynamically query the device's supported audio formats (handling both `i16` and `f32` input sample formats) instead of hardcoding `f32`. For importing files, apply a proper anti-aliasing low-pass filter when downsampling audio to 16kHz.

### R5. UI Polish and Code Cleanups
- Replace `ValueAnimator` allocations in `MicLevelView` with a smooth, allocation-free exponential smoothing algorithm to avoid GC stutter.
- Move all boolean settings (`auto_record`, `select_transcription`, `pause_audio`) from empty files to standard `SharedPreferences` via `SettingsManager`.
- Implement a silence timeout in the Live Subtitles service to clear the screen overlay when no speech is detected.

## Acceptance Criteria

### Storage & Startup
- [ ] No model files under `parakeet-tdt-0.6b-v3-int8` are copied to the app's private files directory (`/data/data/dev.notune.transcribe/files/`) at startup.
- [ ] The app successfully initializes and runs inference using the uncompressed assets directly via File Descriptors (`/proc/self/fd/`).

### Memory & Process sharing
- [ ] The `android:process=":ime"` attribute is removed from `AndroidManifest.xml`.
- [ ] The IME and activities share the same process ID at runtime, and only one copy of `GLOBAL_ENGINE` is initialized.

### Audio Thread & Compatibility
- [ ] No call to JNIEnv is made within the real-time CPAL input callback closure.
- [ ] Audio level changes are read via a JNI getter method rather than pushed from the audio callback.
- [ ] The CPAL stream builder queries supported configs and handles `i16` input formats.
- [ ] The resampler in `TranscribeFileActivity` uses low-pass filtering to prevent aliasing distortion.

### User Settings & Subtitles
- [ ] `auto_record`, `select_transcription`, and `pause_audio` flags are stored and retrieved using `SettingsManager` SharedPreferences.
- [ ] The Live Subtitle view clears text after 2 seconds of silence (RMS < 0.002).
- [ ] `MicLevelView` does not instantiate new `ValueAnimator` objects in `setLevel()`.
