# Analysis Report: ONNX Model Packaging and Direct Asset Loading

## BRIEFING — 2026-06-25T17:00:00Z

### Mission
Analyze the codebase to locate where the `parakeet-tdt-0.6b-v3-int8` model files are stored, packaged, extracted, and loaded, and design a precise plan to pack the models uncompressed and load them directly from the APK assets using file descriptors, offsets, and sizes.

### 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: Investigator, Analyzer, Synthesizer
- Working directory: `/home/marodriguezd/Github/android_transcribe_app/.agents/explorer_m1_3`
- Original parent: `50ef758e-d9e8-4cf1-9804-8bd8052e2858`
- Milestone: Milestone 1

### 🔒 Key Constraints
- Read-only investigation — do NOT implement.
- Network mode: CODE_ONLY (no external web search/access).
- All changes/plans must be fully documented inside our folder.

---

## Progress Tracker
- [x] Locate ONNX model files under `parakeet-tdt-0.6b-v3-int8` in codebase
- [x] Trace packaging and runtime extraction mechanisms
- [x] Trace model loading code in Rust (`transcribe-rs`)
- [x] Formulate precise plan to package models uncompressed in assets
- [x] Reconcile and formulate multiple direct loading methods:
  - Method A: Page-Aligned Memory Mapping (`mmap`)
  - Method B: Direct File Descriptor Reading (`pread`)
  - Method C: `/proc/self/fd/` with standard Rust `FileExt::read_exact_at`
  - Method D: Native NDK `AAssetManager`
- [x] Document briefing, progress tracker, and complete findings in `analysis.md`
- [x] Document handoff in `handoff.md`

---

## Synthesis of Findings

### 1. Model Storage, Packaging, and Extraction at Runtime
* **Storage and Download**:
  * The model files for `parakeet-tdt-0.6b-v3-int8` are defined in `app/build.gradle.kts` (lines 170-184).
  * They are downloaded from HuggingFace (`https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main`) via a Gradle task `downloadModels` (lines 245-261).
  * **Metadata files** (`config.json` and `vocab.txt`) reside under:
    `app/src/main/assets/parakeet-tdt-0.6b-v3-int8/`
  * **Large ONNX models** reside under:
    `model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8/`
    * `encoder-model.int8.onnx` (~595 MB)
    * `decoder_joint-model.int8.onnx` (~46 MB)
    * `nemo128.onnx` (~27 MB)

* **Packaging**:
  * The large ONNX model weights are packaged using Android **Play Asset Delivery**.
  * The module `:model_assets` is defined as an install-time asset pack in `model_assets/build.gradle.kts` using the `com.android.asset-pack` plugin.
  * In `app/build.gradle.kts`, the asset pack is registered via `assetPacks += listOf(":model_assets")` (line 64).
  * For local debug builds, the build script adds the asset pack assets directory directly to the main assets source set (lines 75-81) to bypass Google Play dynamic split delivery.

* **Runtime Extraction**:
  * Contained in `src/assets.rs` (`extract_assets` on lines 10-58).
  * On app startup, during JNI engine initialization, the background thread in `src/engine.rs` calls `assets::extract_assets(env, context)`.
  * It queries the app's private files directory: `context.getFilesDir().getAbsolutePath()` (usually `/data/data/dev.notune.transcribe/files/`).
  * If `/data/data/dev.notune.transcribe/files/parakeet-tdt-0.6b-v3-int8/.extraction_complete` does not exist:
    1. It wipes any existing files in that folder.
    2. It lists assets recursively under `parakeet-tdt-0.6b-v3-int8` using `AssetManager.list()`.
    3. It reads each file byte-by-byte using `AssetManager.open()` (InputStream) and writes it into a file in the app private files directory.
    4. Writes `.extraction_complete` marker file upon completion.
  * **Overhead**: This duplicate copy doubles the model storage size on the device (from ~670 MB to ~1.3 GB) and causes long, intensive disk I/O write operations at first startup.

### 2. Model Loading in Rust
* **Call Sequence**:
  1. `src/engine.rs` calls `assets::extract_assets(env, context)` to extract assets and retrieve the path.
  2. `src/engine.rs` calls `ParakeetEngine::load_model_with_params(&path, ParakeetModelParams::int8())`.
  3. `transcribe-rs/src/engines/parakeet/engine.rs` delegates to `ParakeetModel::new(model_path, quantized)`.
  4. `transcribe-rs/src/engines/parakeet/model.rs` initializes the three sessions by calling `init_session` which uses `builder.commit_from_file(path)` (line 149):
     ```rust
     let session = builder.commit_from_file(model_dir.as_ref().join(&model_filename))?;
     ```
  5. Vocabulary `vocab.txt` is loaded via `std::fs::read_to_string` from the extracted directory.

---

## 3. Direct Loading Design and Implementation Plan

### Part A: Packaging Models Uncompressed in APK Assets
To enable direct memory-mapped or file descriptor loading of assets from the APK, the models must be stored uncompressed (`STORED` mode in ZIP) so they form a contiguous chunk of bytes at a specific offset.

1. **Gradle noCompress Configuration**:
   In `app/build.gradle.kts` (and any asset pack module configuration if applicable), disable compression for `.onnx` files by adding:
   ```kotlin
   android {
       ...
       androidResources {
           noCompress += listOf("onnx")
       }
   }
   ```
2. **Alternative Rename Suffix Trick**:
   AAPT2 automatically skips compression for certain media formats. As a fallback, we can rename files to `.onnx.wav` during the `downloadModels` task. This guarantees they will never be compressed by any gradle build variant.

---

### Part B: Java to Rust JNI Asset Retrieval
To obtain the file descriptor, start offset, and size of each model, we can query the `AssetManager` directly from Rust using JNI.

Add the following helper struct and function in `src/assets.rs`:
```rust
pub struct AssetFdInfo {
    pub fd: i32,
    pub offset: i64,
    pub length: i64,
}

pub fn get_asset_fd_info(env: &mut JNIEnv, context: &JObject, asset_path: &str) -> anyhow::Result<AssetFdInfo> {
    let asset_manager = env
        .call_method(context, "getAssets", "()Landroid/content/res/AssetManager;", &[])?
        .l()?;

    let asset_path_j = env.new_string(asset_path)?;
    let afd = env
        .call_method(
            &asset_manager,
            "openFd",
            "(Ljava/lang/String;)Landroid/content/res/AssetFileDescriptor;",
            &[(&asset_path_j).into()],
        )?
        .l()?;

    let pfd = env
        .call_method(
            &afd,
            "getParcelFileDescriptor",
            "()Landroid/os/ParcelFileDescriptor;",
            &[],
        )?
        .l()?;

    let fd = env.call_method(&pfd, "getFd", "()I", &[])?.i()?;
    let offset = env.call_method(&afd, "getStartOffset", "()J", &[])?.j()?;
    let length = env.call_method(&afd, "getLength", "()J", &[])?.j()?;

    // Duplicate FD to safely close the Java AssetFileDescriptor
    let dup_fd = unsafe { libc::dup(fd) };
    if dup_fd < 0 {
        return Err(anyhow::anyhow!("Failed to dup FD: {}", std::io::Error::last_os_error()));
    }

    // Close the original Java descriptor to release resources
    let _ = env.call_method(&afd, "close", "()V", &[]);

    Ok(AssetFdInfo {
        fd: dup_fd,
        offset,
        length,
    })
}
```

---

### Part C: Options for In-Memory Loading in Rust

Since ONNX Runtime (`ort` version `2.0.0-rc.12`) exposes the method:
`pub fn commit_from_memory(&mut self, model_bytes: &[u8]) -> Result<Session>`
we can load the models directly from memory slices. We propose three options for obtaining this slice from the duplicated file descriptor `fd`, `offset`, and `length`.

#### Method A: Page-Aligned Memory Mapping (`libc::mmap`)
* **Mechanism**: Maps the file directly into virtual memory.
* **Caveat**: `mmap` requires the offset parameter to be page-aligned. We must perform page calculations:
```rust
pub struct MappedAsset {
    ptr: *mut libc::c_void,
    mapped_length: usize,
    pub slice: &'static [u8],
}

impl MappedAsset {
    pub fn map(info: &AssetFdInfo) -> anyhow::Result<Self> {
        let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
        let start_offset = info.offset as usize;
        let length = info.length as usize;

        // Align offset to page boundary
        let aligned_offset = (start_offset / page_size) * page_size;
        let padding = start_offset - aligned_offset;
        let mapped_length = length + padding;

        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                mapped_length,
                libc::PROT_READ,
                libc::MAP_PRIVATE,
                info.fd,
                aligned_offset as libc::off_t,
            )
        };

        if ptr == libc::MAP_FAILED {
            return Err(anyhow::anyhow!("mmap failed: {}", std::io::Error::last_os_error()));
        }

        let slice = unsafe {
            std::slice::from_raw_parts((ptr as *const u8).add(padding), length)
        };

        Ok(Self {
            ptr,
            mapped_length,
            slice,
        })
    }
}

impl Drop for MappedAsset {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.ptr, self.mapped_length);
        }
    }
}
```

#### Method B: Direct File Descriptor Reading (`libc::pread`)
* **Mechanism**: Reads the model contents directly from `fd` into a heap-allocated `Vec<u8>` without affecting the file cursor/seek position.
* **Advantage**: Page-alignment is not required, avoiding complex pointer arithmetic and memory mapping limits.
```rust
pub fn read_asset_bytes(info: &AssetFdInfo) -> anyhow::Result<Vec<u8>> {
    let mut buffer = vec![0u8; info.length as usize];
    let mut total_read = 0;
    while total_read < info.length as usize {
        let read_now = unsafe {
            libc::pread(
                info.fd,
                buffer.as_mut_ptr().add(total_read) as *mut libc::c_void,
                (info.length as usize) - total_read,
                (info.offset as libc::off_t) + (total_read as libc::off_t),
            )
        };
        if read_now < 0 {
            return Err(anyhow::anyhow!("pread failed: {}", std::io::Error::last_os_error()));
        }
        if read_now == 0 {
            break;
        }
        total_read += read_now as usize;
    }
    Ok(buffer)
}
```

#### Method C: `/proc/self/fd/` with Standard Rust `FileExt`
* **Mechanism**: Open the file descriptor in Rust via `/proc/self/fd/<fd>` and read the exact slice using platform-specific extensions.
* **Advantage**: Avoids raw `unsafe` `libc` code.
```rust
use std::os::unix::fs::FileExt;

pub fn read_asset_proc(info: &AssetFdInfo) -> anyhow::Result<Vec<u8>> {
    let file = std::fs::File::open(format!("/proc/self/fd/{}", info.fd))?;
    let mut buffer = vec![0u8; info.length as usize];
    file.read_exact_at(&mut buffer, info.offset as u64)?;
    Ok(buffer)
}
```

#### Method D: Native NDK `AAssetManager`
* **Mechanism**: Convert Java's `AssetManager` directly to NDK's `AAssetManager` in Rust and open the files. Uncompressed files can be memory-mapped via `AAsset_getBuffer()`.
* **Advantage**: Does not require manual FD, offset, and size management.

---

### Part D: Integrating Memory Loading into `transcribe-rs`
To support loading from memory, extend `ParakeetModel` and `ParakeetEngine` to accept byte buffers.

1. **Update `transcribe-rs/src/engines/parakeet/model.rs`**:
```rust
impl ParakeetModel {
    pub fn from_memory(
        encoder_bytes: &[u8],
        decoder_joint_bytes: &[u8],
        preprocessor_bytes: &[u8],
        vocab_content: &str,
    ) -> Result<Self, ParakeetError> {
        let encoder = Self::init_session_from_memory(encoder_bytes, None)?;
        let decoder_joint = Self::init_session_from_memory(decoder_joint_bytes, None)?;
        let preprocessor = Self::init_session_from_memory(preprocessor_bytes, None)?;

        let (vocab, blank_idx) = Self::parse_vocab(vocab_content)?;
        let vocab_size = vocab.len();

        Ok(Self {
            encoder,
            decoder_joint,
            preprocessor,
            vocab,
            blank_idx,
            vocab_size,
        })
    }

    fn init_session_from_memory(
        model_bytes: &[u8],
        intra_threads: Option<usize>,
    ) -> Result<Session, ParakeetError> {
        let mut providers = Vec::new();
        #[cfg(target_os = "android")]
        {
            providers.push(ep::NNAPI::default().build());
            providers.push(ep::XNNPACK::default().build());
        }
        providers.push(ep::CPU::default().build());

        let mut builder = Session::builder()
            .map_err(|e| ParakeetError::Ort(e.into()))?
            .with_optimization_level(GraphOptimizationLevel::Level3)
            .map_err(|e| ParakeetError::Ort(e.into()))?
            .with_execution_providers(providers)
            .map_err(|e| ParakeetError::Ort(e.into()))?
            .with_parallel_execution(true)
            .map_err(|e| ParakeetError::Ort(e.into()))?;

        if let Some(threads) = intra_threads {
            builder = builder
                .with_intra_threads(threads)
                .map_err(|e| ParakeetError::Ort(e.into()))?
                .with_inter_threads(threads)
                .map_err(|e| ParakeetError::Ort(e.into()))?;
        }

        let session = builder.commit_from_memory(model_bytes)
            .map_err(|e| ParakeetError::Ort(e.into()))?;

        Ok(session)
    }

    pub fn parse_vocab(content: &str) -> Result<(Vec<String>, i32), ParakeetError> {
        let mut max_id = 0;
        let mut tokens_with_ids: Vec<(String, usize)> = Vec::new();
        let mut blank_idx: Option<usize> = None;

        for line in content.lines() {
            let parts: Vec<&str> = line.trim_end().split(' ').collect();
            if parts.len() >= 2 {
                let token = parts[0].to_string();
                if let Ok(id) = parts[1].parse::<usize>() {
                    if token == "<blk>" {
                        blank_idx = Some(id);
                    }
                    tokens_with_ids.push((token, id));
                    max_id = max_id.max(id);
                }
            }
        }

        let mut vocab = vec![String::new(); max_id + 1];
        for (token, id) in tokens_with_ids {
            vocab[id] = token.replace('\u{2581}', " ");
        }

        let blank_idx = blank_idx.ok_or_else(|| {
            ParakeetError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "Missing <blk> token in vocabulary",
            ))
        })? as i32;

        Ok((vocab, blank_idx))
    }
}
```

2. **Update `transcribe-rs/src/engines/parakeet/engine.rs`**:
```rust
impl ParakeetEngine {
    pub fn load_model_from_memory(
        &mut self,
        encoder_bytes: &[u8],
        decoder_joint_bytes: &[u8],
        preprocessor_bytes: &[u8],
        vocab_content: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let model = ParakeetModel::from_memory(
            encoder_bytes,
            decoder_joint_bytes,
            preprocessor_bytes,
            vocab_content,
        )?;
        self.model = Some(model);
        self.loaded_model_path = None;
        Ok(())
    }
}
```

---

### Part E: Wiring the Loader in `src/engine.rs`
Update `do_load` in `src/engine.rs` to fetch model details from APK assets and load them:

```rust
// Since vocab.txt is small, read it directly using standard JNI Asset Stream
pub fn read_vocab_asset(env: &mut JNIEnv, context: &JObject) -> anyhow::Result<String> {
    let asset_manager = env
        .call_method(context, "getAssets", "()Landroid/content/res/AssetManager;", &[])?
        .l()?;

    let vocab_path_j = env.new_string("parakeet-tdt-0.6b-v3-int8/vocab.txt")?;
    let stream_obj = env
        .call_method(
            &asset_manager,
            "open",
            "(Ljava/lang/String;)Ljava/io/InputStream;",
            &[(&vocab_path_j).into()],
        )?
        .l()?;

    let mut content = Vec::new();
    let mut buffer = [0u8; 8192];
    let buffer_j = env.new_byte_array(8192)?;

    loop {
        let bytes_read = env
            .call_method(&stream_obj, "read", "([B)I", &[(&buffer_j).into()])?
            .i()?;

        if bytes_read == -1 {
            break;
        }

        let bytes_read_usize = bytes_read as usize;
        let buffer_slice = unsafe {
            std::slice::from_raw_parts_mut(buffer.as_mut_ptr() as *mut i8, bytes_read_usize)
        };

        env.get_byte_array_region(&buffer_j, 0, buffer_slice)?;
        content.extend_from_slice(&buffer[0..bytes_read_usize]);
    }

    let _ = env.call_method(&stream_obj, "close", "()V", &[]);
    String::from_utf8(content).map_err(Into::into)
}

fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    notify_status(env, context, "Reading vocabulary...");
    let vocab_content = assets::read_vocab_asset(env, context).map_err(|e| e.to_string())?;

    notify_status(env, context, "Opening model assets...");
    let enc_info = assets::get_asset_fd_info(env, context, "parakeet-tdt-0.6b-v3-int8/encoder-model.int8.onnx").map_err(|e| e.to_string())?;
    let dec_info = assets::get_asset_fd_info(env, context, "parakeet-tdt-0.6b-v3-int8/decoder_joint-model.int8.onnx").map_err(|e| e.to_string())?;
    let prep_info = assets::get_asset_fd_info(env, context, "parakeet-tdt-0.6b-v3-int8/nemo128.onnx").map_err(|e| e.to_string())?;

    notify_status(env, context, "Loading models into memory...");
    
    // Choose Method B/C (Heap reading) or Method A (mmap).
    // Here we show Method B (pread) as it does not require page alignment adjustments:
    let enc_bytes = assets::read_asset_bytes(&enc_info).map_err(|e| e.to_string())?;
    let dec_bytes = assets::read_asset_bytes(&dec_info).map_err(|e| e.to_string())?;
    let prep_bytes = assets::read_asset_bytes(&prep_info).map_err(|e| e.to_string())?;

    let mut eng = ParakeetEngine::new();
    eng.load_model_from_memory(
        &enc_bytes,
        &dec_bytes,
        &prep_bytes,
        &vocab_content,
    ).map_err(|e| e.to_string())?;

    // Close duplicated FDs
    unsafe {
        libc::close(enc_info.fd);
        libc::close(dec_info.fd);
        libc::close(prep_info.fd);
    }

    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(eng)));
    notify_status(env, context, "Ready");
    Ok(())
}
```
*(Note: If Method A/mmap is preferred, we preserve the mapping instances in memory alongside the engine to prevent unmapping while the engine uses it, or ONNX Runtime copies the buffers anyway).*
