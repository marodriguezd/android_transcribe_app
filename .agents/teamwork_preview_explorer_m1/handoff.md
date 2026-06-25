# Milestone 1 Handoff Report: Test Environment Exploration

## 1. Observation
We explored the host operating system, SDK environment, and build setups, observing the following:

- **Host Operating System and Architecture**:
  - `uname -a`: `Linux fedora 7.0.12-201.fc44.x86_64 #1 SMP PREEMPT_DYNAMIC Thu Jun 11 01:30:16 UTC 2026 x86_64 GNU/Linux`
  - `arch`: `x86_64`
- **JDK/Android SDK/NDK Directories**:
  - `JAVA_HOME`: `/home/marodriguezd/jdk-21/jdk-21.0.2+13` (OpenJDK version `21.0.2` Temurin)
  - `sdk.dir` in `local.properties`: `/home/marodriguezd/Android/Sdk`
  - NDK directory: `/home/marodriguezd/Android/Sdk/ndk/28.2.13676358` (NDK version `28.2.13676358`)
- **SDK Tools and Emulator Setup**:
  - `ls /home/marodriguezd/Android/Sdk` lists:
    ```
    build-tools, cmake, cmdline-tools, licenses, ndk, platforms, platform-tools
    ```
    *(Note: No `emulator` or `system-images` directories exist).*
  - `adb devices` returns:
    ```
    List of devices attached
    ```
    *(Note: Empty list, no running devices).*
  - `/home/marodriguezd/Android/Sdk/emulator/emulator -list-avds` returns:
    ```
    bash: /home/marodriguezd/Android/Sdk/emulator/emulator: No existe el fichero o el directorio
    ```
- **Gradle Tasks and Test Configuration**:
  - `./gradlew tasks` output lists Verification tasks including `test`, `testDebugUnitTest`, `connectedAndroidTest`, ` connectedCheck`.
  - `app/build.gradle.kts` dependencies block contains:
    ```kotlin
    dependencies {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
    ```
    *(Note: No test dependencies, e.g., JUnit, Robolectric, Mockito, or Espresso are configured).*
- **Rust Host Compilation**:
  - `cargo check` (targeting host `x86_64-unknown-linux-gnu`) fails with 71 errors in `whisper-rs` (v0.13.2) during compiling of `whisper-rs-sys`. Verbatim error:
    ```
    error[E0609]: no field `progress_callback` on type `whisper_full_params`
       --> /home/marodriguezd/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/whisper-rs-0.13.2/src/whisper_params.rs:585:25
    ```
- **Rust Android Compilation**:
  - `cargo ndk -t arm64-v8a build --release` targeting `aarch64-linux-android` succeeds without errors:
    ```
    Finished `release` profile [optimized] target(s) in 1m 04s
    ```
- **Current JNI codebase status**:
  - `src/engine.rs` contains the memory-mapped assets loading logic (`load_model_from_memory`) and compiles successfully with the JNI error fixed:
    ```rust
    let asset_manager_obj = env
        .call_method(context, "getAssets", "()Landroid/content/res/AssetManager;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("Failed to get AssetManager: {}", e))?;
    ```

## 2. Logic Chain
1. Since the host SDK has no `emulator` or `system-images` directories and `adb devices` is empty, **instrumented tests on a real or emulated Android device (via `connectedAndroidTest`) are impossible** in this environment.
2. The core JNI layer of the app (`libandroid_transcribe_app.so`) only references and initializes the `ParakeetEngine` (NVIDIA NeMo Parakeet models via ONNX Runtime). The Whisper engine (and its transitive dependency `whisper-rs`) is never utilized by the Android app.
3. On host Linux targets, Cargo attempts to compile `whisper-rs` due to the target-specific dependency:
   ```toml
   [target.'cfg(target_os = "linux")'.dependencies]
   whisper-rs = { version = "0.13.2", features = ["vulkan"] }
   ```
   This fails compilation due to a bindgen struct mismatch on Linux, which prevents building `transcribe-rs` or the JNI library for the host `x86_64-unknown-linux-gnu` architecture.
4. On Android targets (`aarch64-linux-android`), Cargo does not build `whisper-rs` (since it is only defined for macos, windows, and linux targets in `transcribe-rs/Cargo.toml`). Combined with `cargo-ndk` providing the correct clang++ toolchain paths, Android builds compile successfully.
5. If we modify `transcribe-rs/Cargo.toml` to remove or disable the target-specific `whisper-rs` dependency on Linux, the Rust native JNI library can compile successfully for the host architecture (`x86_64-unknown-linux-gnu`).
6. Once the native library is compiled for the host architecture, we can place it on the Java library path and load it via `System.loadLibrary("android_transcribe_app")` inside local JVM JUnit/Robolectric tests.
7. Robolectric shadows the Android framework APIs (like `AssetManager`, `ParcelFileDescriptor`, `SharedPreferences`), enabling integration testing of the JNI boundary and verification of all optimization requirements (R1-R5) entirely on the host JVM.

## 3. Caveats
- We assume that the user's environment has Clang and the Vulkan development headers required if `whisper-rs` was to compile, but since we suggest bypassing it, this should not affect test execution.
- We did not attempt to run a compiled Android APK, as no device/emulator was available. Verification of the Android build was done solely through static syntax checks, compilation (`cargo ndk`), and Gradle build tasks.

## 4. Conclusion
We recommend using **local JVM tests (Robolectric + JUnit 4/5)** running on the host, utilizing a **host-compiled native library** (`libandroid_transcribe_app.so` for `x86_64-unknown-linux-gnu`) with the unused `whisper-rs` dependency disabled or removed.

### E2E Verification Plan for R1-R5:
- **R1 (Direct Asset Loading via FD)**:
  - Write a Robolectric integration test that retrieves an `AssetFileDescriptor` from a test APK asset.
  - Pass the FD details via JNI to the Rust engine and verify that `load_model_from_memory` successfully initializes the ONNX Runtime sessions.
  - Assert that no files are written to the internal app storage (i.e., `context.getFilesDir()` is clean).
- **R2 (Process Unification)**:
  - Perform static verification during the build to assert that `android:process` is removed from `RustInputMethodService` in `app/src/main/AndroidManifest.xml`.
- **R3 (Audio Callback JNI Decoupling)**:
  - Implement a Robolectric test that mocks CPAL's input stream callback updating a thread-safe atomic float in Rust.
  - Periodically call `nativeGetAudioLevel()` from Java and verify it retrieves the updated level, ensuring the JNI thread boundaries are correct.
- **R4 (CPAL Formats & Resampler LPF)**:
  - Feed non-16kHz audio signals (e.g. 48kHz) through the Java low-pass resampler in a unit test, verify the output downsamples properly without aliasing, and verify the resulting audio transcribes correctly.
- **R5 (UI & Settings Polish)**:
  - Write Robolectric tests to verify SharedPreferences settings migrations in `SettingsManager`, and assert that the overlay window in `LiveSubtitleService` closes after the expected inactivity timeout.

## 5. Verification Method
To verify this proposed approach:
1. Open `transcribe-rs/Cargo.toml` and comment out the `whisper-rs` dependency under `[target.'cfg(target_os = "linux")'.dependencies]`.
2. Compile the JNI library for the host target:
   ```bash
   cargo build --release
   ```
   Verify that `target/release/libandroid_transcribe_app.so` is built successfully.
3. Configure `app/build.gradle.kts` to add Robolectric and JUnit dependencies:
   ```kotlin
   testImplementation("org.robolectric:robolectric:4.11.1")
   testImplementation("junit:junit:4.13.2")
   ```
4. Configure the test task in `app/build.gradle.kts` to set the system library path:
   ```kotlin
   tasks.withType<Test> {
       systemProperty("java.library.path", file("../target/release").absolutePath)
   }
   ```
5. Run the local test command:
   ```bash
   ./gradlew testDebugUnitTest
   ```
