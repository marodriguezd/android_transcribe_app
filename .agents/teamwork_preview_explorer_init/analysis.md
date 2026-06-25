# Codebase Investigation Report: Offline Voice Input Android Application

This report details the investigation of the Offline Voice Input Android codebase regarding requirements R1 through R5. It outlines the current design, identifies performance/architectural issues, and defines how each requirement applies to the existing source files.

---

## Executive Summary
1. **Model Storage & Loading (R1)**: ONNX model files are currently downloaded from Hugging Face into a local directory and bundled via the `:model_assets` asset pack. At runtime, the app extracts/copies these files to the internal storage (`getFilesDir()`), resulting in dual-storage overhead (~1.4 GB total) and slow first-time start.
2. **Process Architecture (R2)**: The IME service (`RustInputMethodService`) is configured to run in a separate `:ime` process. As a result, the IME and Activities run in distinct JVM instances with separate memory spaces, causing the 700 MB ONNX model to load twice (totaling ~1.4 GB RAM usage).
3. **Audio Callback Thread (R3)**: The CPAL real-time audio input callback makes blocking JNI transitions (`jvm.attach_current_thread` and calling `onAudioLevel` Java method) every 50ms, which runs on the high-priority audio callback thread and exposes it to GC-related jitter.
4. **CPAL Input & Resampling (R4)**: CPAL initialization hardcodes the sample format as `f32` and sample rate as 16kHz, which fails on devices supporting only `i16` input or other sample rates. The audio resampler in `TranscribeFileActivity` performs simple linear interpolation without an anti-aliasing low-pass filter, resulting in high-frequency distortion.
5. **UI & Settings (R5)**: `MicLevelView` instantiates new `ValueAnimator` and listener objects on every call to `setLevel()`, causing garbage collection overhead. Settings (`auto_record`, `select_transcription`, `pause_audio`) are stored as empty file markers in `getFilesDir()` instead of using `SharedPreferences`. The Live Subtitle service does not clear the subtitle overlay text when silence is detected.

---

## 1. Location of ONNX Model Files and Loading Mechanism (R1)
- **Source Paths**:
  - Small metadata files (`config.json`, `vocab.txt`) reside under `app/src/main/assets/parakeet-tdt-0.6b-v3-int8`.
  - Large ONNX models are downloaded into `model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8/` during the `downloadModels` Gradle task.
  - The models are:
    1. `encoder-model.int8.onnx` (~652 MB)
    2. `decoder_joint-model.int8.onnx` (~18.2 MB)
    3. `nemo128.onnx` (~139 KB)
- **Gradle Packaging**:
  - In `app/build.gradle.kts`, the `:model_assets` module is added as an asset pack via `assetPacks += listOf(":model_assets")`.
  - For APK builds (non-bundle), Gradle includes them in the source assets: `assets.srcDirs("src/main/assets", rootProject.file("model_assets/src/main/assets"))`.
- **Runtime Extraction**:
  - The extraction occurs in `src/assets.rs` (`extract_assets`).
  - At first startup, the app calls Java's `AssetManager.open()` to copy the model directory from APK assets to the private internal files directory `getFilesDir() + "/parakeet-tdt-0.6b-v3-int8"`.
  - A completion marker file `.extraction_complete` is created to skip subsequent extractions.
- **Model Loading**:
  - The model is loaded in `src/engine.rs` (`do_load`) by calling `ParakeetEngine::load_model_with_params(&path, ...)`.
  - If loading fails (e.g., due to corrupt files), the `.extraction_complete` marker is deleted to trigger re-extraction.

---

## 2. Process Configuration and `GLOBAL_ENGINE` Initialization (R2)
- **AndroidManifest.xml Configuration**:
  - The Input Method Service is declared at `app/src/main/AndroidManifest.xml` (lines 89-101):
    ```xml
    <service
        android:name=".RustInputMethodService"
        android:label="Offline Voice Input"
        android:permission="android.permission.BIND_INPUT_METHOD"
        android:process=":ime"
        android:exported="true">
        ...
    </service>
    ```
  - The `:ime` suffix assigns `RustInputMethodService` to a separate process, isolating its JVM environment.
- **Single-Process Constraint & Model Duplication**:
  - Because of `android:process=":ime"`, the activities (which default to the main application process `dev.notune.transcribe`) and the service run in separate processes.
  - The engine static singleton `GLOBAL_ENGINE` in `src/engine.rs` is allocated in the global static space of the loaded `libandroid_transcribe_app.so` binary.
  - Since the JVM loads the library independently for each process, *two* instances of the transcription engine are created and two sets of model weights are loaded into RAM (totalling ~1.4 GB of duplication).

---

## 3. JNI Interface and Real-Time Audio Callback Thread (R3)
- **CPAL Callback Setup**:
  - In `src/voice_session.rs` (`start_recording`), CPAL builds the input stream using `device.build_input_stream`:
    ```rust
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);
            // compute RMS
            let mut sum = 0.0f32;
            for &x in data { sum += x * x; }
            let rms = (sum / (data.len() as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            // throttle updates to 50ms
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();
                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        ...
    )
    ```
- **JNI Boundary Transitions**:
  - Inside the callback closure, the high-priority real-time audio thread attempts to attach to the JVM (`jvm.attach_current_thread()`) and calls the Java object's `onAudioLevel` method (`notify_level`).
  - This design causes audio thread preemption and micro-stuttering due to thread synchronization and potential Garbage Collection locks during execution.

---

## 4. CPAL Audio Setup, Format Queries, and Resampler (R4)
- **CPAL Microphone Setup**:
  - The configuration in `src/voice_session.rs` (lines 90-94) is hardcoded:
    ```rust
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };
    ```
  - It does not query the device's supported formats via `device.supported_input_configs()`.
  - It forces an `f32` input stream build, which fails on devices that only support `i16` input configurations.
- **Resampler Implementation in `TranscribeFileActivity`**:
  - Defined in `app/src/main/java/dev/notune/transcribe/TranscribeFileActivity.java` (lines 291-309).
  - It implements a basic linear interpolation algorithm:
    ```java
    double ratio = (double) fromRate / toRate;
    int outputLength = (int) (input.length / ratio);
    ...
    output[i] = (float) (input[idx] * (1.0 - frac) + input[idx + 1] * frac);
    ```
  - Downsampling from sample rates (such as 44.1kHz or 48kHz) to 16kHz without applying an anti-aliasing low-pass filter introduces high-frequency aliasing distortion, reducing transcription quality.

---

## 5. UI Components, Settings Storage, and Live Subtitles Timeout (R5)
- **`MicLevelView` ValueAnimator Allocations**:
  - Located in `app/src/main/java/dev/notune/transcribe/MicLevelView.java`.
  - Inside `setLevel(float level)`, it cancels the current animator and instantiates a new `ValueAnimator` and a new update listener on every update:
    ```java
    if (animator != null) animator.cancel();
    animator = ValueAnimator.ofFloat(current, target);
    animator.setDuration(60);
    animator.addUpdateListener(a -> {
        current = (float) a.getAnimatedValue();
        invalidate();
    });
    animator.start();
    ```
  - This allocation pattern puts pressure on the Java heap, causing garbage collector sweeps and frame drops.
- **Settings Storage**:
  - Standard settings in `MainActivity.java` (lines 70-110) are managed by writing empty marker files to raw file storage:
    - `auto_record` switch creates/deletes `new File(getFilesDir(), "auto_record")`.
    - `select_transcription` switch creates/deletes `new File(getFilesDir(), "select_transcription")`.
    - `pause_audio` switch creates/deletes `new File(getFilesDir(), "pause_audio")`.
  - This bypasses standard `SharedPreferences` (managed by `SettingsManager.java` for API/Post-processing keys) and executes blockages/disk operations on the main UI thread.
- **Live Subtitles Timeout**:
  - Inside `src/subtitle.rs` (`pushAudio`), the service calculates the RMS of the buffer:
    ```rust
    let rms = (sum_sq / buffer_len as f32).sqrt();
    if rms > 0.002 {
        let _ = state.worker_tx.send((buffer.clone(), start_time));
    }
    ```
  - If `rms <= 0.002` (silence), it does not send data to the worker thread.
  - However, there is no timeout mechanism to notify the Java UI to clear the subtitle overlay, causing the last transcribed phrase to remain on the screen indefinitely.

---

## 6. Build and Test Commands
- **Environment Requirements**:
  ```bash
  export ANDROID_SDK_ROOT=/home/marodriguezd/Android/Sdk
  export JAVA_HOME=/home/marodriguezd/jdk-21/jdk-21.0.2+13
  ```
- **Building the Application**:
  - **Debug APK**:
    ```bash
    ./gradlew downloadModels cargoNdkBuild assembleDebug
    ```
    Alternatively, using the wrapper script:
    ```bash
    ./build.sh debug
    ```
  - **Release APK**:
    ```bash
    ./gradlew downloadModels cargoNdkBuild assembleRelease
    ```
    Alternatively, using the wrapper script:
    ```bash
    ./build.sh
    ```
- **Testing and Verification**:
  - **Rust Crate Unit Tests**:
    To verify transcription logic locally on host, navigate to the library submodule and run:
    ```bash
    cargo test -p transcribe-rs
    ```
    *(Note: Requires placing model assets in `transcribe-rs/models/` and sample audio in `transcribe-rs/samples/`, and compiling with host-compatible ONNX runtime library paths).*
  - **Linting & Analysis**:
    ```bash
    cargo check
    cargo clippy
    ```
