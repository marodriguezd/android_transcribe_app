# Handoff Report - Milestone 1 Reviewer 2

## 1. Observation
* **Observation 1 (noCompress configuration)**: In `/home/marodriguezd/Github/android_transcribe_app/app/build.gradle.kts` at lines 66-68:
  ```kotlin
      androidResources {
          noCompress += "onnx"
      }
  ```
* **Observation 2 (Memory mapping implementation)**: In `/home/marodriguezd/Github/android_transcribe_app/src/assets.rs` at lines 154-170, the `MemoryMappedAsset` struct and `as_slice` function are defined:
  ```rust
  pub struct MemoryMappedAsset {
      mapped_ptr: *mut libc::c_void,
      mapped_length: usize,
      slice_offset: usize,
      slice_len: usize,
  }
  impl MemoryMappedAsset {
      pub fn as_slice(&self) -> &[u8] {
          unsafe {
              std::slice::from_raw_parts(
                  (self.mapped_ptr as *const u8).add(self.slice_offset),
                  self.slice_len,
              )
          }
      }
  }
  ```
  It implements `Drop` to call `libc::munmap`:
  ```rust
  impl Drop for MemoryMappedAsset {
      fn drop(&mut self) {
          unsafe {
              libc::munmap(self.mapped_ptr, self.mapped_length);
          }
      }
  }
  ```
* **Observation 3 (Mmap execution)**: In `/home/marodriguezd/Github/android_transcribe_app/src/engine.rs` at lines 208-225:
  ```rust
      let encoder_mapped = assets::mmap_asset(env, &asset_manager_obj, "parakeet-tdt-0.6b-v3-int8/encoder-model.int8.onnx")
          .map_err(|e| format!("Failed to map encoder-model.int8.onnx: {}", e))?;
      ...
      let mut eng = ParakeetEngine::new();
      match eng.load_model_from_memory(
          encoder_mapped.as_slice(),
          ...
      ) {
          Ok(_) => {
              *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(eng)));
              notify_status(env, context, "Ready");
              Ok(())
          }
          ...
      }
  ```
* **Observation 4 (Session loading from memory)**: In `/home/marodriguezd/Github/android_transcribe_app/transcribe-rs/src/engines/parakeet/model.rs` at lines 205-226:
  ```rust
      fn init_session_from_memory(
          model_bytes: &[u8],
          intra_threads: Option<usize>,
      ) -> Result<Session, ParakeetError> {
          ...
          let session = builder.commit_from_memory(model_bytes)
              .map_err(|e| ParakeetError::Ort(e.into()))?;
          Ok(session)
      }
  ```
  Where `Session` does not have a lifetime parameter, erasing the connection to the lifetime of `model_bytes`.
* **Observation 5 (Unit test deadlock)**: When running `cargo test -p transcribe-rs --test parakeet` on host Linux, the test hangs. Attaching `gdb` (LWP 63120) showed a deadlock in `ort::load_dylib_from_path` recursive error construction inside `G_ORT_API` OnceLock:
  ```
  #34 0x000055789e311b81 in ort::api () at src/lib.rs:176
  #35 0x000055789e3316d1 in ort::environment::{impl#12}::create_environment::{closure#1} (name=...) at src/lib.rs:294
  ...
  #42 ParakeetModel::init_session_from_memory
  ```

## 2. Logic Chain
1. From Observation 1, ONNX assets are packed uncompressed inside the APK.
2. From Observation 2, `MemoryMappedAsset` maps a memory segment and unmaps it when dropped.
3. From Observation 3, `encoder_mapped`, `decoder_mapped`, and `preprocessor_mapped` are local variables in `do_load` and are dropped when `do_load` exits.
4. From Observation 4, `Session` is initialized via `commit_from_memory(model_bytes)`. Since it holds raw pointers to the model buffer internally, the buffer must remain alive for the lifetime of the `Session`.
5. Therefore, once `do_load` exits, the mapped memory is unmapped, leading to a Use-After-Free/Segfault when subsequent inference calls are executed on the sessions.
6. From Observation 5, running tests on the host with `load-dynamic` enabled results in a recursive deadlock in `ort` if loading the dynamic library fails, making independent test verification on host problematic without pre-installing a native `libonnxruntime.so` matching host ABI.

## 3. Caveats
No caveats. The segfault issue is verified mathematically and structurally from the `ort` crate's dynamic linking and slice referencing contract.

## 4. Conclusion
Milestone 1 implementation fails safety criteria and must not be approved. The unmapping of memory while ONNX Runtime sessions reference it is a critical flaw that will result in a runtime SIGSEGV crash.

## 5. Verification Method
To verify this independently:
1. Verify target compilation by running `./build.sh debug`.
2. Inspect `src/engine.rs` to observe that `encoder_mapped`, `decoder_mapped`, and `preprocessor_mapped` are local to `do_load` and will be dropped (and thus unmapped via `munmap`) immediately on exit.
3. Verify that `SessionBuilder::commit_from_memory` expects the referenced buffer to live as long as the session.
