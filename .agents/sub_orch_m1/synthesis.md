# Synthesis of Explorer Findings — Milestone 1 (Direct Asset Loading via FD)

## Consensus
- **Model Storage**: Small configuration metadata and vocabulary (`vocab.txt`) reside in `app/src/main/assets/parakeet-tdt-0.6b-v3-int8`. Large models reside in `model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8/`.
- **Extraction Mechanism**: Currently handled by `extract_assets` in `src/assets.rs`, which writes the files to `getFilesDir()`.
- **Target Files to Package Uncompressed**: `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `nemo128.onnx`, and `vocab.txt` (or at least the `.onnx` files).
- **Packaging Configuration**: `aaptOptions` / `androidResources { noCompress += listOf("onnx", "txt") }` in `app/build.gradle.kts` to package assets uncompressed.
- **Loading in Rust**: Use `ort`'s `SessionBuilder::commit_from_memory` or custom file descriptor / offset based loaders.
- **Reading `vocab.txt`**: To fully avoid copying, the contents of `vocab.txt` can be read directly using the `AssetManager` API via JNI, and passed as a Rust `String`/`&str` without extracting/copying files.

## Resolution of Approaches
We will use **Option B** (JNI `AssetFileDescriptor` querying, retrieving raw FD, offset, and size, and using page-aligned `mmap` in Rust to load using `commit_from_memory`) as recommended by the Explorers. This approach is highly robust and doesn't depend on raw NDK-sys linking assumptions, and allows clean memory-mapped lifetime management.
- Specifically, the `aligned_offset` logic dynamically queries system page size (`sysconf(_SC_PAGESIZE)`) to handle both 4KB and 16KB Android devices safely.
- In Rust, `ort`'s `commit_from_memory` internally copies the model bytes when building the session. This means we can safely `munmap` the mapped memory and close the duplicated file descriptor immediately after the session is created, making memory management simple and leak-free.
- We will update the JNI logic in `src/engine.rs` to load all assets via `AssetManager.openFd` (or open/read for text files).
- No model files under `parakeet-tdt-0.6b-v3-int8` will be extracted or copied. `extract_assets` will be bypassed or cleaned up.

## Action Plan for Worker
1. **Packaging**: Add `androidResources { noCompress += listOf("onnx") }` to `app/build.gradle.kts` to keep the models uncompressed in assets.
2. **Rust Engine / Model Interface**:
   - Update `ParakeetModel` in `transcribe-rs/src/engines/parakeet/model.rs` and `ParakeetEngine` in `transcribe-rs/src/engines/parakeet/engine.rs` to support loading from `&[u8]` buffers (via `commit_from_memory`) and vocabulary contents from a string.
3. **Rust JNI / Asset Loader**:
   - Implement `mmap_asset` in `src/assets.rs` or `src/engine.rs` that calls JNI methods:
     - `context.getAssets()`
     - `AssetManager.openFd("parakeet-tdt-0.6b-v3-int8/...")`
     - Retrieve `ParcelFileDescriptor`, its `getFd()`, `getStartOffset()`, and `getLength()`.
     - Duplicate the fd using `libc::dup(fd)` (so we can safely close the Java-side fd), call `libc::mmap` with appropriate page alignment.
   - Implement `read_asset_to_string` in `src/assets.rs` to read `vocab.txt` using the JNI stream reader.
   - Modify `do_load` in `src/engine.rs` to use these JNI methods to retrieve buffers, initialize `ParakeetEngine` from memory, and drop the mmap allocations and duplicated FDs immediately after load.
4. **Verification**:
   - Ensure the app builds (`./build.sh`).
   - Run tests (`./gradlew test`).
   - Audit check to verify no files are written under `parakeet-tdt-0.6b-v3-int8` in `files` directory.
