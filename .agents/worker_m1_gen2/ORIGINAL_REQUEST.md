## 2026-06-25T17:21:22Z
You are Worker 2 (Worker Generation 2) for Milestone 1. Your working directory is /home/marodriguezd/Github/android_transcribe_app/.agents/worker_m1_gen2.

Task:
Fix critical bugs in the memory-mapped asset loading implementation.

Instructions:
1. In `src/engine.rs`, define a static variable to keep the memory-mapped model allocations alive in memory, preventing them from being unmapped when `do_load` exits:
   ```rust
   static MODEL_MAPPINGS: Lazy<Mutex<Option<(assets::MemoryMappedAsset, assets::MemoryMappedAsset, assets::MemoryMappedAsset)>>> =
       Lazy::new(|| Mutex::new(None));
   ```
2. In `do_load` in `src/engine.rs`, store the mapped asset variables `encoder_mapped`, `decoder_mapped`, and `preprocessor_mapped` in `MODEL_MAPPINGS` on success (at the same time `GLOBAL_ENGINE` is set):
   ```rust
   *MODEL_MAPPINGS.lock().unwrap() = Some((encoder_mapped, decoder_mapped, preprocessor_mapped));
   ```
3. In `mmap_asset` in `src/assets.rs`, restructure the code to ensure that Java-side `AssetFileDescriptor` is always closed even if an early JNI method call fails. Wrap JNI queries in a closure:
   ```rust
   let res = (|| -> anyhow::Result<(i64, i64, i32)> {
       // getStartOffset, getLength, getParcelFileDescriptor, getFd, and dup
   })();
   let _ = env.call_method(&asset_fd_obj, "close", "()V", &[]);
   let (start_offset, length, dup_fd) = res?;
   ```
4. Verify that the codebase compiles and tests pass by running `./build.sh debug` and `./gradlew test`.
5. Deliver a handoff report at /home/marodriguezd/Github/android_transcribe_app/.agents/worker_m1_gen2/handoff.md documenting the changes made, compilation results, and verify that the file descriptor leak and mmap lifetime issues are fully resolved.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
