# Handoff Report — Explorer Init

## 1. Observation
Direct codebase observations:
- **Model Files & Extraction**:
  - Download target directories are configured in `app/build.gradle.kts` (lines 250-252):
    - `appAssetsDir = project.file("src/main/assets/parakeet-tdt-0.6b-v3-int8")`
    - `packAssetsDir = rootProject.file("model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8")`
  - Extraction mechanism is located in `src/assets.rs` line 25: `let model_dir = base_path.join("parakeet-tdt-0.6b-v3-int8");`. If `.extraction_complete` is missing, it copies files from context assets recursively (lines 51-54).
- **Process Configuration**:
  - `AndroidManifest.xml` (line 93) declares: `android:process=":ime"` for the IME service.
  - Rust static variable `GLOBAL_ENGINE` is defined in `src/engine.rs` line 12.
- **JNI Transitions in Audio Callback**:
  - In `src/voice_session.rs` line 103, CPAL streams call JNI inside the callback:
    ```rust
    if let Ok(mut env) = jvm.attach_current_thread() {
        let obj = target_ref.as_obj();
        notify_level(&mut env, obj, level);
    }
    ```
- **CPAL Config & Resampler**:
  - CPAL configuration in `src/voice_session.rs` lines 90-94 is hardcoded to `cpal::SampleRate(16000)` and `channels: 1`.
  - Resampler in `TranscribeFileActivity.java` line 291 implements linear interpolation:
    `output[i] = (float) (input[idx] * (1.0 - frac) + input[idx + 1] * frac);`
- **UI & Settings**:
  - `MicLevelView.java` line 38 instantiates animator objects on every level change: `animator = ValueAnimator.ofFloat(current, target);`.
  - Boolean settings are managed via files in `MainActivity.java` line 68: `File autoRecordFile = new File(getFilesDir(), "auto_record");` (and similar files for `select_transcription` and `pause_audio`).
  - Silence checks in `src/subtitle.rs` lines 159-160 send data only when `rms > 0.002`. No timeout message is sent to Java when it is below this threshold.

## 2. Logic Chain
- **R1 Model Copying**: Because the code copies assets to `getFilesDir()` and expects a path on the file system, disk storage is duplicated, and startup is slow. Loading models directly from APK requires keep-uncompressed packing rules in Gradle and `AssetFileDescriptor` parsing in the native loader.
- **R2 Memory Duplication**: Because the `:ime` process is separated, the JVM attaches and loads a separate copy of `libandroid_transcribe_app.so` for the keyboard, creating a duplicate `GLOBAL_ENGINE` and duplicating the loaded model weights (~700 MB each, total ~1.4 GB). Sharing the default process by removing `android:process=":ime"` will cause them to share the library instance and native memory.
- **R3 JNI Audio Thread**: Because CPAL threads attach to JVM and perform JNI method invocations on every buffer processing frame, the critical audio thread is prone to JNI call overhead and Java garbage collection pauses. Transitioning to atomic native state values that Java queries via polling will decouple the thread.
- **R4 Audio Quality & Formats**: Because sample format is hardcoded to `f32` and sample rate is hardcoded to 16kHz, unsupported devices fail to record. Querying supported configs and converting sample formats in the callback is required. Downsampling files without a low-pass filter introduces aliasing; a digital filter (e.g. 8kHz cutoff) must be introduced.
- **R5 UI Polish**: Creating `ValueAnimator` objects in rapid succession (every 50ms) introduces heavy GC allocation pressure. This can be resolved with allocation-free exponential decay interpolation. Settings should use standard `SharedPreferences` instead of raw files. Subtitle view should clear after 2 seconds of silence.

## 3. Caveats
- Host tests `cargo test -p transcribe-rs` require a local copy of model files and the host ONNX Runtime library, which are not set up on this container. The investigation was completed via file parsing rather than runtime verification.
- The anti-aliasing filter implementation must be efficient enough to run on-device.

## 4. Conclusion
The codebase is currently organized cleanly but contains specific, easily rectifiable architectural, memory, and performance flaws corresponding exactly to R1-R5 requirements. Modifying build files and code locations identified in `analysis.md` will address these issues.

## 5. Verification Method
- **Build verification**:
  Run `./build.sh debug` or `./gradlew downloadModels cargoNdkBuild assembleDebug` to verify that code compiles cleanly.
- **Unit test check**:
  Run `cargo check` and `cargo clippy` in the workspace root to verify Rust syntax.
