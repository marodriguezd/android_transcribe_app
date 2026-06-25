# Milestone 1 Review Report (Direct Asset Loading via FD)

## Part 1: Quality Review Summary

**Verdict**: APPROVE

Overall, the Milestone 1 implementation is of high quality, complete, and robust. It correctly accomplishes the goal of loading ONNX models directly from APK assets via file descriptors without extracting them to internal storage. 

---

## Part 2: Quality Findings

### [Minor] Finding 1: Potential Java AssetFileDescriptor leak on early JNI errors

- **What**: Java-side `AssetFileDescriptor` is not closed on early return errors in `mmap_asset`.
- **Where**: `src/assets.rs`, inside `mmap_asset` (lines 183-217).
- **Why**: JNI calls such as `getStartOffset`, `getLength`, `getParcelFileDescriptor`, or `getFd` propagate errors using the `?` operator. If any of these calls return an `Err`, the function returns early. Since `asset_fd_obj.close()` is only called on lines 222 and 227, the early returns bypass closing the descriptor.
- **Suggestion**: Wrap the body of `mmap_asset` that retrieves offsets and descriptor in a block, or use a custom scope guard that calls Java's `close()` method when dropped. (Alternatively, rely on Java garbage collection finalizers, which will eventually close the file descriptor, but explicit JNI cleanup is safer).

### [Minor] Finding 2: Lack of target gating in upstream Whisper integration tests

- **What**: The integration test file `transcribe-rs/tests/whisper.rs` imports `transcribe_rs::engines::whisper` unconditionally.
- **Where**: `transcribe-rs/tests/whisper.rs` (line 4).
- **Why**: Since `pub mod whisper` in `transcribe-rs/src/engines/mod.rs` is conditional on `#[cfg(not(target_os = "android"))]`, it compiles on host Linux but depends on the `whisper-rs` crate, which fails to build if C++ dependencies/Vulkan are missing or target dependency resolution is bypassed. This prevents full host workspace test execution (`cargo test`).
- **Suggestion**: Gating `tests/whisper.rs` under `#[cfg(not(target_os = "android"))]` or disabling the test target in `transcribe-rs/Cargo.toml` under certain host environments would make workspace checks more robust.

---

## Part 3: Verified Claims

- **APK Debug Build** → verified via `./build.sh debug` → **PASS**
  - The Android app compiles successfully with the native Rust library linked, producing `app/build/outputs/apk/debug/app-debug.apk`.
- **Android Unit Tests** → verified via `./gradlew test` → **PASS** (Zero tests executed)
  - The command completes successfully (`testDebugUnitTest NO-SOURCE` and `testReleaseUnitTest NO-SOURCE`), indicating no JVM-side tests are defined.
- **Rust Integration Tests** → verified via `cargo test --test parakeet` (with symlinked models) → **PASS**
  - Spawning `ParakeetEngine` both from files and directly from memory buffers (`test_jfk_transcription_from_memory` and `test_jfk_transcription`) passes successfully, producing the exact expected transcription text from `jfk.wav`.

---

## Part 4: Coverage Gaps & Unverified Items

- **Asset Pack Bundle Builds** — Risk Level: **Low**
  - We verified the APK build where assets are routed to the main app assets. Bundle builds (`.aab`) use dynamic delivery and place assets in the `:model_assets` pack. We did not test downloading and loading from dynamic asset packs in an actual Android emulator/device, but the JNI interface contract is identical. Recommendation: Accept risk for Milestone 1, verify in end-to-end integration tests (Milestone 6).

---

## Part 5: Adversarial Review & Challenges

**Overall risk assessment**: LOW

### [Medium] Challenge 1: Memory Alignment and Offset Safety

- **Assumption challenged**: The file offset of the model asset inside the APK zip is assumed to be page-aligned.
- **Attack scenario**: APK packaging could place assets at arbitrary, non-page-aligned byte boundaries. Mapping them directly using raw offsets would result in `mmap` returning `MAP_FAILED` (EINVAL).
- **Blast radius**: The transcription engine would fail to load models on startup, crashing or rendering transcription unusable.
- **Mitigation**: The code correctly implements page alignment by calculating:
  ```rust
  let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
  let offset = start_offset as usize;
  let aligned_offset = (offset / page_size) * page_size;
  let alignment_difference = offset - aligned_offset;
  let mapped_length = (length as usize) + alignment_difference;
  ```
  It maps memory starting from the aligned offset and then skips `alignment_difference` bytes when retrieving the slice (`as_slice()`). This is mathematically robust and standard for POSIX `mmap` systems.

### [High] Challenge 2: Mapped Memory Lifetimes vs ONNX Runtime Sessions

- **Assumption challenged**: It is assumed safe to unmap the memory mapped regions (`munmap`) immediately after the ONNX Runtime sessions are initialized.
- **Attack scenario**: If the `ort` library or the underlying ONNX Runtime C library maintains pointers referencing the memory slice instead of copying the model data, subsequent inference runs would trigger a segmentation fault or access garbage memory once the asset is unmapped.
- **Blast radius**: Sudden segfault/crashes during speech-to-text operations.
- **Mitigation**: ONNX Runtime C API `CreateSessionFromArray` explicitly copies the model bytes into the session during construction. Thus, the original buffer is not needed after `commit_from_memory` returns. This is verified by `test_jfk_transcription_from_memory` where the model bytes are immediately dropped/freed, yet transcription runs successfully without crashes.

---

## Part 6: Integrity & Cheat Detection

We ran active checks for the following integrity violations:
- **Hardcoded test results**: None found.
- **Dummy/facade implementations**: None found; the JNI calls, memory mapping alignment, and ORT memory-based session initializations are real and functional.
- **Fabricated verification outputs**: None.
