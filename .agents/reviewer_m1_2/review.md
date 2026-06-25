# Milestone 1 Review Report (Direct Asset Loading via FD)

**Reviewer**: Reviewer 2 (Adversarial Critic)  
**Date**: 2026-06-25  
**Verdict**: REQUEST_CHANGES  

---

## 1. Quality Review Summary

We have evaluated the Milestone 1 implementation across correctness, robustness, and memory safety. The implementation builds successfully for Android, but contains a **Critical Use-After-Free/Segfault bug** in memory mapping lifetime management, and a minor **File Descriptor Leak** risk in JNI error paths.

### Correctness & Completeness
* **Assets Compression**: The configuration `noCompress += "onnx"` in `app/build.gradle.kts` successfully prevents the ONNX models from being compressed inside the APK, allowing page-aligned direct reading.
* **Mmap Offsets**: Page alignment arithmetic in `assets.rs` is technically correct and correctly aligns start offsets to system page boundaries.
* **Vocab Loading**: The vocabulary is read from memory and correctly parsed into the native model structure.

---

## 2. Findings

### [Critical] Finding 1: Memory Use-After-Free / Application Crash (SIGSEGV)
* **What**: Memory-mapped slices for ONNX models are unmapped immediately after the initialization function completes, while the ONNX Runtime sessions are still active and reference them.
* **Where**: `src/engine.rs`, lines 208-237 (`do_load`), and `src/assets.rs`, lines 175-181 (`MemoryMappedAsset` drop implementation).
* **Why**: 
  In `do_load`, the variables `encoder_mapped`, `decoder_mapped`, and `preprocessor_mapped` are local variables of type `MemoryMappedAsset`. When `do_load` returns, these variables go out of scope and their `Drop` implementation is invoked, which calls `libc::munmap`.
  
  However, the `ort` crate's `SessionBuilder::commit_from_memory` API accepts a byte slice `&[u8]`. Because the returned `Session` does not have a lifetime parameter, the lifetime check is erased by the crate. ONNX Runtime's native layer does **not** copy the model bytes; it keeps a raw pointer to the buffer.
  
  Once the memory is unmapped via `munmap`, any subsequent call to transcribe audio (e.g. `stop_recording` calling `transcribe_samples`) will read from the unmapped address space, leading to an immediate **Segmentation Fault (SIGSEGV)** and crashing the application.
* **Suggestion**: 
  Store the `MemoryMappedAsset` structures in a wrapper type or static variable alongside the loaded engine to keep the mappings alive for the entire lifetime of `GLOBAL_ENGINE`. For example:
  ```rust
  struct LoadedEngine {
      engine: ParakeetEngine,
      _encoder_mmap: MemoryMappedAsset,
      _decoder_mmap: MemoryMappedAsset,
      _preprocessor_mmap: MemoryMappedAsset,
  }
  static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<LoadedEngine>>>>> = ...
  ```

### [Medium] Finding 2: Potential Java AssetFileDescriptor Leak in JNI Error Paths
* **What**: If JNI method calls fail inside `mmap_asset`, the function returns early, leaking the Java `AssetFileDescriptor` reference.
* **Where**: `src/assets.rs`, lines 198-216 (`mmap_asset`).
* **Why**: 
  The JNI calls `getStartOffset`, `getLength`, `getParcelFileDescriptor`, and `getFd` are all propagated using the `?` operator. If any of these calls fail and throw a JNI exception, the function exits early before `asset_fd_obj.close()` is called. In Android, failing to close `AssetFileDescriptor` can leak the underlying system file descriptor.
* **Suggestion**: 
  Use a helper guard or explicitly catch errors, ensuring `close()` is always called on the JNI `AssetFileDescriptor` if the function exits early.

### [Minor] Finding 3: Host Unit Tests Deadlock on Missing Library
* **What**: Running unit tests on the host system via `cargo test` deadlocks if the ONNX Runtime dynamic library is missing, rather than failing gracefully.
* **Where**: Upstream `ort` crate error path (`load_dylib_from_path` calling `ort::api()` recursively on failure).
* **Why**: 
  When `load-dynamic` is enabled (as in the workspace root `Cargo.toml`), and the system cannot load `libonnxruntime.so`, it calls `ort::Error::new()`. The error formatter calls `ort::api()` recursively to get dynamic error strings, which attempts to lock the `G_ORT_API` `OnceLock` that is already in the middle of being locked, resulting in a permanent deadlock.
* **Suggestion**: Document this behavior so developers know they must configure `ORT_DYLIB_PATH` on the host to run tests.

---

## 3. Verified Claims

* **Uncompressed ONNX Assets Packaging** → Verified via `app/build.gradle.kts` inspection → **PASS**
* **Native Code Compilation (Android Target)** → Verified via `./build.sh debug` → **PASS**
* **Java/Kotlin Unit Tests** → Verified via `./gradlew test` (results in NO-SOURCE, no Java tests present) → **PASS (No-op)**
* **ORT Session Buffer Retention** → Verified via stack-trace analysis of the `ort` crate's `load_dylib_from_path` and `SessionBuilder` implementation → **FAIL** (Unmapping buffer leads to segfault)

---

## 4. Adversarial Review (Challenge Report)

**Overall risk assessment**: CRITICAL

### [Critical] Challenge 1: Memory Mapped Lifetime Assumption
* **Assumption challenged**: The assumption that `ort`'s `commit_from_memory` internally copies the model bytes when building the session, allowing `MemoryMappedAsset` to be unmapped immediately.
* **Attack scenario**: The model is initialized. The user starts recording audio and then stops it. The stop action triggers native transcription, which executes inference on the sessions whose model weights reside in the unmapped `MemoryMappedAsset` address range.
* **Blast radius**: The application crashes instantly with a SIGSEGV.
* **Mitigation**: Bind the lifetime of the memory maps to the session lifetime (or store memory maps in a static variable along with the engine).

### [Medium] Challenge 2: JNI Exception/Error Leak
* **Assumption challenged**: That the happy path is always followed in JNI calls.
* **Attack scenario**: A JNI call fails (e.g. Android runs out of memory or there is a JNI method signature mismatch during initialization).
* **Blast radius**: System file descriptor leak, which could lead to exhaustion of file descriptors and subsequent app failure if initialization is retried.
* **Mitigation**: Clean up JNI local references and close file descriptors in error paths.

---

## 5. Build and Test Verification

### command: `./build.sh debug`
**Result**: Success
**Output**:
```
--- Preparing build environment ---
--- Building Debug APK ---
> Task :app:downloadModels UP-TO-DATE
> Task :app:extractOrt UP-TO-DATE
> Task :app:cargoNdkBuild UP-TO-DATE
...
> Task :app:assembleDebug
BUILD SUCCESSFUL in 5s
--- Build Successful ---
APK Location: app/build/outputs/apk/debug/app-debug.apk
```

### command: `./gradlew test`
**Result**: Success (No-op)
**Output**:
```
> Task :app:testDebugUnitTest NO-SOURCE
> Task :app:testReleaseUnitTest NO-SOURCE
> Task :app:test UP-TO-DATE
BUILD SUCCESSFUL in 906ms
```

### command: `cargo test -p transcribe-rs --test parakeet`
**Result**: FAILED / HUNG (Deadlock)
**Description**: The test process hangs on x86_64 Linux because of the dynamic library loading failure in the `ort` crate under `load-dynamic` feature, which deadlocks on `OnceLock` recursion.
