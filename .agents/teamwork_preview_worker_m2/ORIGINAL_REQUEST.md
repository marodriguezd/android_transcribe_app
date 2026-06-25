## 2026-06-25T17:07:27Z
You are teamwork_preview_worker_m2.
Your working directory is /home/marodriguezd/Github/android_transcribe_app/.agents/teamwork_preview_worker_m2.
Your mission is to implement the E2E Test Suite and infrastructure for the Offline Voice Input Android application optimizations.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Tasks:
1. Comment out the `whisper-rs` dependency under `[target.'cfg(target_os = "linux")'.dependencies]` in `transcribe-rs/Cargo.toml` to allow compiling the Rust native library on host Linux x86_64.
2. Edit `app/build.gradle.kts` to:
   - Add test dependencies (Robolectric and JUnit):
     ```kotlin
     testImplementation("org.robolectric:robolectric:4.11.1")
     testImplementation("junit:junit:4.13.2")
     ```
   - Configure the test task to pass the system library path:
     ```kotlin
     tasks.withType<Test> {
         systemProperty("java.library.path", file("../target/release").absolutePath)
     }
     ```
3. Run the host native compilation command:
   ```bash
   cargo build --release
   ```
   Verify that `target/release/libandroid_transcribe_app.so` is built successfully.
4. Create the JUnit/Robolectric test file: `app/src/test/java/dev/notune/transcribe/OfflineVoiceInputE2ETest.java`.
   Design and implement a comprehensive test suite covering the 5 main features across 4 Tiers:
   - Feature 1: Model Initialization & Storage Management (R1)
   - Feature 2: Process & Resource Sharing (R2)
   - Feature 3: Audio Callback JNI Decoupling (R3)
   - Feature 4: CPAL Audio Format Compatibility & Resampler LPF (R4)
   - Feature 5: UI & Settings Polish (R5)
   
   The test suite must cover:
   - Tier 1: Feature coverage (at least 5 distinct test cases per feature, total >= 25 cases).
   - Tier 2: Boundary & corner cases (at least 5 distinct test cases per feature, total >= 25 cases).
   - Tier 3: Cross-feature combinations (at least 5 pairwise interaction tests).
   - Tier 4: Real-world application scenarios (at least 5 comprehensive end-to-end user flows).
   Note: Use Robolectric's `@RunWith(RobolectricTestRunner.class)` to run JVM tests with mocked Android context, components, and SharedPreferences.
5. Create a test runner shell script at the project root `run_e2e_tests.sh` that compiles the host native library, configures the environment, and runs `./gradlew testDebugUnitTest --info`. Make the script executable.
6. Write the `TEST_INFRA.md` file at the project root, defining the E2E test infrastructure, 4-tier hierarchy, feature inventory, runner instructions, and coverage status.
7. Run the test suite using `run_e2e_tests.sh` and document the results. Note which tests pass and which tests fail (expected since implementation might be in-progress).
8. Write a completion report to handoff.md in your working directory and notify the parent orchestrator via send_message.

Use the run_command, view_file, write_to_file, and replace_file_content tools as needed to implement the changes and verify them.
