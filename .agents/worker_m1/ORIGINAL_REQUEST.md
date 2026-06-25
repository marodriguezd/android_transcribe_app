## 2026-06-25T17:00:10Z

You are the Worker for Milestone 1. Your working directory is /home/marodriguezd/Github/android_transcribe_app/.agents/worker_m1.

Objective:
Implement the changes to package the ONNX models uncompressed in assets and load them directly from APK assets via File Descriptors & memory mapping (no extraction/copying of models to the application files directory /data/.../files/ at startup).

Instructions:
1. Read the synthesis plan at /home/marodriguezd/Github/android_transcribe_app/.agents/sub_orch_m1/synthesis.md.
2. Modify `app/build.gradle.kts` to set `androidResources.noCompress` to prevent compressing `.onnx` files.
3. Modify `transcribe-rs/src/engines/parakeet/model.rs` and `engine.rs` to support loading model sessions from in-memory byte buffers via `Session::builder().commit_from_memory()` and passing vocabulary content as a string.
4. Modify `src/assets.rs` and `src/engine.rs` (JNI layer) to:
   - Use JNI calls to `AssetManager.openFd` to obtain the uncompressed model assets' file descriptors, offsets, and sizes.
   - Use `libc::mmap` with correct page-boundary alignment (using sysconf(_SC_PAGESIZE) or similar) to map the model files into memory.
   - Read `vocab.txt` using JNI stream reader into a String.
   - Load the engine using these memory buffers and String, bypassing the extraction code completely.
   - Ensure the mapped memories and duplicated file descriptors are safely unmapped and closed after the sessions are created.
5. Compile the app using `./build.sh` or `./gradlew assembleDebug` and run unit tests using `./gradlew test`.
6. Write a handoff report to /home/marodriguezd/Github/android_transcribe_app/.agents/worker_m1/handoff.md documenting the changes made, compilation output, and verification results.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
