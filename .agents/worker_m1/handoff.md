# Handoff Report — Milestone 1 (Direct Asset Loading via FD & Memory Mapping)

## 1. Observation
- Modified `app/build.gradle.kts` to add:
  ```kotlin
  androidResources {
      noCompress += "onnx"
  }
  ```
- Created a memory mapping helper `mmap_asset` and reader `read_asset_to_string` in `src/assets.rs` utilizing JNI calls to `AssetManager.openFd` and `AssetManager.open`.
- Queried page size dynamically inside `mmap_asset` using `libc::sysconf(libc::_SC_PAGESIZE)` to determine aligned offset:
  ```rust
  let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
  let offset = start_offset as usize;
  let aligned_offset = (offset / page_size) * page_size;
  let alignment_difference = offset - aligned_offset;
  let mapped_length = (length as usize) + alignment_difference;
  ```
- Wrapped mapped memory in a RAII struct `MemoryMappedAsset` which calls `libc::munmap` on drop:
  ```rust
  impl Drop for MemoryMappedAsset {
      fn drop(&mut self) {
          unsafe {
              libc::munmap(self.mapped_ptr, self.mapped_length);
          }
      }
  }
  ```
- Updated `do_load` in `src/engine.rs` to call `assets::read_asset_to_string` and `assets::mmap_asset` to memory map models and load directly via the new `load_model_from_memory` interface of `ParakeetEngine`.
- Verified compilation using `./build.sh debug` (exit code 0):
  ```
  BUILD SUCCESSFUL in 54s
  --- Build Successful ---
  APK Location: app/build/outputs/apk/debug/app-debug.apk
  ```
- Verified unit testing flow using `./gradlew test` (exit code 0):
  ```
  BUILD SUCCESSFUL in 18s
  ```

## 2. Logic Chain
1. By setting `androidResources.noCompress += "onnx"` in `app/build.gradle.kts`, `.onnx` assets are stored uncompressed within the APK, allowing us to query their file descriptor offsets via the Android `AssetManager` APIs.
2. In `src/assets.rs`, we resolved the `AssetManager` JNI references and loaded the uncompressed assets using `openFd` to obtain the start offset and length, and the raw file descriptor via `ParcelFileDescriptor.getFd()`.
3. In `mmap_asset`, calling `libc::dup(raw_fd)` creates a duplicate file descriptor so Java can safely close `AssetFileDescriptor` without invalidating the file descriptor used by memory mapping.
4. Calling `libc::mmap` with the correct page alignment maps the asset directly from the APK into memory.
5. In `src/engine.rs`, by passing the memory slices to `ParakeetEngine::load_model_from_memory` (which uses `commit_from_memory`), the ONNX sessions are constructed.
6. The `MemoryMappedAsset` structs are dropped at the end of `do_load`, which calls `libc::munmap` and closes the mapped memory, ensuring no memory leak is left.
7. Consequently, the previous `assets::extract_assets` routine which writes the files to `files` directory is completely bypassed during startup.

## 3. Caveats
- Host tests in `transcribe-rs` that depend on the external `whisper-rs` package fail to compile on standard Linux environments due to bindings mismatches of `whisper.cpp`. Thus, host cargo testing is not possible without setting up custom whisper-rs environments. This does not affect Android builds since native library linking is handled via AGP/CMake.

## 4. Conclusion
Uncompressed `.onnx` assets packaging, dynamic page-aligned JNI memory mapping, vocabulary stream reader, and in-memory engine loading are successfully implemented, completely bypassing model file extraction to `/data/.../files/` at startup.

## 5. Verification Method
- **Command to compile**: `./build.sh debug`
- **Command to test**: `./gradlew test`
- **Verification of behavior**: Inspect `src/engine.rs` to verify that `assets::extract_assets` is no longer called in `do_load`, and that memory mapping is instead performed.
