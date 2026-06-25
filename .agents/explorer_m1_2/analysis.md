# Analysis Report — Explorer 2 (Milestone 1)

## Mission
Analyze ONNX model packaging, extraction, and loading in Rust/Java, and formulate a plan to load uncompressed models directly from APK assets via FD/offset/size.

## 🔒 My Identity
- Archetype: explorer
- Roles: Teamwork explorer
- Working directory: /home/marodriguezd/Github/android_transcribe_app/.agents/explorer_m1_2
- Original parent: 50ef758e-d9e8-4cf1-9804-8bd8052e2858
- Milestone: Milestone 1

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Network mode: CODE_ONLY (no external web search/access)

## Current Parent
- Conversation ID: 50ef758e-d9e8-4cf1-9804-8bd8052e2858
- Updated: 2026-06-25T17:05:00Z

## Investigation State
- **Explored paths**:
  - `app/build.gradle.kts`
  - `model_assets/build.gradle.kts`
  - `src/assets.rs`
  - `src/engine.rs`
  - `src/main_activity.rs`
  - `transcribe-rs/src/engines/parakeet/engine.rs`
  - `transcribe-rs/src/engines/parakeet/model.rs`
  - `Cargo.toml`
  - `Cargo.lock`
- **Key findings**:
  - Detailed locations and mechanics of model storage, packaging, extraction, and Rust loading.
  - Complete plan for zero-copy memory-mapped loading directly from APK assets.
- **Unexplored areas**: None.

## Key Decisions Made
- Formulate two different options for Java-to-Rust JNI integration (NDK AAssetManager vs AssetFileDescriptor passing) to provide a robust implementation path.

---

# Complete Findings & Analysis

## 1. Storage, Packaging, and Extraction at Runtime

### Storage & Packaging
* **Source Repository**: The quantized NVIDIA Parakeet TDT 0.6B models (~670 MB) are hosted on HuggingFace at `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main`.
* **Download Gradle Task**: `app/build.gradle.kts` defines a task `downloadModels` (lines 245–261) which is hooked into the `preBuild` lifecycle.
  * Small metadata files (`config.json`, `vocab.txt`) are downloaded to the app base assets directory: `app/src/main/assets/parakeet-tdt-0.6b-v3-int8`.
  * Large ONNX models are downloaded to the asset pack directory: `model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8/`.
    * `encoder-model.int8.onnx`
    * `decoder_joint-model.int8.onnx`
    * `nemo128.onnx`
* **Packaging Method**: The `model_assets` folder is declared as an install-time delivery Play Asset Delivery pack (`com.android.asset-pack` plugin in `model_assets/build.gradle.kts`). During build and installation:
  * For APK/development builds, Gradle merges the assets of the asset pack into the base APK assets.
  * For Android App Bundle (AAB) builds, AGP packages it as a separate split APK that is installed alongside the base APK.

### Runtime Extraction
* **Location**: The extraction logic is contained in `src/assets.rs` in `extract_assets` (lines 10–58).
* **Extraction Trigger**: On app startup, during native engine initialization, the background thread in `src/engine.rs` calls `assets::extract_assets(env, context)`.
* **Extraction Mechanism**:
  1. The code resolves the application's internal private storage path: `context.getFilesDir().getAbsolutePath()`.
  2. It checks for a marker file named `.extraction_complete` under `getFilesDir() + "/parakeet-tdt-0.6b-v3-int8/"`.
  3. If missing, it deletes any existing incomplete files and recreates the target directory.
  4. It recursively lists the assets under the directory path `"parakeet-tdt-0.6b-v3-int8"` using JNI calls to `AssetManager.list(...)`.
  5. For each file, it calls JNI `AssetManager.open(...)` to get a Java `InputStream`, allocates a byte array buffer, reads it chunk-by-chunk, and writes it to a file in the app private internal storage directory.
  6. Upon completion, it writes the `.extraction_complete` marker.
* **Storage Footprint Overhead**: Copying the ~670 MB models doubles the app storage footprint on the device to ~1.3 GB (the models reside both inside the APK and in `getFilesDir()`), and causes significant disk I/O and CPU overhead on first launch.

---

## 2. Model Loading in Rust

* **Call Entrypoint**: Currently, `src/engine.rs` line 204 calls `eng.load_model_with_params(&path, ...)` where `path` points to the extracted folder in the private files directory.
* **Crate Engine Implementation**: `transcribe-rs/src/engines/parakeet/engine.rs` (lines 256–270) invokes `ParakeetModel::new(model_path, quantized)`.
* **ONNX Session Construction**: `transcribe-rs/src/engines/parakeet/model.rs` (lines 73–95) initializes three sessions for the models:
  ```rust
  let encoder = Self::init_session(&model_dir, "encoder-model", None, quantized)?;
  let decoder_joint = Self::init_session(&model_dir, "decoder_joint-model", None, quantized)?;
  let preprocessor = Self::init_session(&model_dir, "nemo128", None, false)?;
  ```
  Each session is built by calling `commit_from_file` (line 149):
  ```rust
  let session = builder.commit_from_file(model_dir.as_ref().join(&model_filename))?;
  ```
* **Vocabulary Loading**: The vocabulary is read in `load_vocab` (lines 164–200) from disk:
  ```rust
  let vocab_path = model_dir.as_ref().join("vocab.txt");
  let content = fs::read_to_string(vocab_path)?;
  ```

---

## 3. Recommended Plan to Package Uncompressed & Load Directly from APK

To enable direct memory-mapped loading of assets from the APK without extraction, we propose a three-step implementation plan.

### Step 1: Pack Models Uncompressed in APK Assets
We must configure Android asset packaging (AAPT2) to package the models uncompressed (`STORED` mode in ZIP) so they occupy a contiguous offset in the APK.

1. **Configure Gradle noCompress**:
   In `app/build.gradle.kts`, under the `android` block, configure the resources packaging to not compress the `.onnx` and `.txt` files:
   ```kotlin
   android {
       ...
       androidResources {
           noCompress += listOf("onnx", "txt")
       }
   }
   ```
2. **Robust Zero-Config Fallback (Optional but Recommended)**:
   AAPT2 automatically skips compression for certain media extensions. We can append `.wav` or `.png` to the downloaded filenames during the `downloadModels` Gradle task (e.g. `encoder-model.int8.onnx.wav`). This guarantees the models will *never* be compressed in any build variant (including asset packs in split bundles), without relying on Gradle config.

---

### Step 2: Java to Rust JNI/NDK Asset Access
There are two elegant options for loading the asset offsets and passing them to Rust:

#### Option A: Native NDK `AAssetManager` (Recommended — Easiest integration)
Since `android_transcribe_app` already links the `ndk` and `ndk-sys` crates, we can do everything directly in Rust! We do not need to modify any Java JNI function signatures.
1. The Java side continues to pass the `Context` object.
2. In Rust, we obtain the `AssetManager` from the context:
   ```rust
   let asset_manager_obj = env.call_method(context, "getAssets", "()Landroid/content/res/AssetManager;", &[])?.l()?;
   ```
3. Convert the JNI reference to a native NDK `AAssetManager` pointer:
   ```rust
   use ndk_sys::AAssetManager_fromJava;
   let asset_mgr_ptr = unsafe { AAssetManager_fromJava(env.get_native_interface(), asset_manager_obj.as_raw()) };
   ```
4. Use NDK APIs to open and map the asset. For uncompressed assets, `AAsset_getBuffer` memory-maps the data directly from the APK (zero-copy):
   ```rust
   // Open asset
   let asset = unsafe { ndk_sys::AAssetManager_open(asset_mgr_ptr, c_path.as_ptr(), ndk_sys::AASSET_MODE_BUFFER) };
   let len = unsafe { ndk_sys::AAsset_getLength(asset) };
   let buffer = unsafe { ndk_sys::AAsset_getBuffer(asset) }; // directly points to APK memory
   let slice = unsafe { std::slice::from_raw_parts(buffer as *const u8, len as usize) };
   ```

#### Option B: Java-side `AssetFileDescriptor` & `/proc/self/fd`
If NDK integration is avoided, we can retrieve the raw file descriptor, offset, and size:
1. Call `context.getAssets().openFd(path)` via JNI.
2. Retrieve the `ParcelFileDescriptor` -> `getFd()`.
3. Retrieve `startOffset` and `length` from `AssetFileDescriptor`.
4. In Rust, construct the memory mapping using `libc::mmap`:
   ```rust
   let ptr = unsafe {
       libc::mmap(
           std::ptr::null_mut(),
           length,
           libc::PROT_READ,
           libc::MAP_SHARED,
           fd,
           offset as libc::off_t,
       )
   };
   ```
5. Construct a slice `&[u8]` from `ptr` and `length`.

*Note on `/proc/self/fd/`*: Attempting to load `commit_from_file("/proc/self/fd/<fd>")` directly fails for assets embedded in an APK because ONNX Runtime reads from the start of the file descriptor (reading the entire APK file as a model). Therefore, we must map the specific offset using `mmap` and load using `commit_from_memory`.

---

### Step 3: Load Models directly from memory slices in Rust

#### 1. Implement Memory-Mapped Asset Wrapper
Define a RAII wrapper in `src/assets.rs` or `src/engine.rs` to manage the lifecycle of mapped slices:
```rust
use std::os::raw::c_void;

pub struct MmappedModel {
    ptr: *mut c_void,
    len: usize,
}

impl MmappedModel {
    pub unsafe fn new(fd: i32, offset: u64, len: usize) -> Result<Self, std::io::Error> {
        let ptr = libc::mmap(
            std::ptr::null_mut(),
            len,
            libc::PROT_READ,
            libc::MAP_SHARED,
            fd,
            offset as libc::off_t,
        );
        if ptr == libc::MAP_FAILED {
            return Err(std::io::Error::last_os_error());
        }
        Ok(Self { ptr, len })
    }

    pub fn as_slice(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr as *const u8, self.len) }
    }
}

impl Drop for MmappedModel {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.ptr, self.len);
        }
    }
}
```

#### 2. Modify `transcribe-rs` Engine for In-Memory Loading
Extend `ParakeetModel` in `transcribe-rs/src/engines/parakeet/model.rs` to allow initialization from memory buffers:
```rust
impl ParakeetModel {
    pub fn from_bytes(
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
    
    // Auxiliary function to load from &[u8] using Session builder
    fn init_session_from_memory(
        model_bytes: &[u8],
        intra_threads: Option<usize>,
    ) -> Result<Session, ParakeetError> {
        // ... (standard session setup with NNAPI/XNNPACK execution providers)
        let session = builder.commit_from_memory(model_bytes)
            .map_err(|e| ParakeetError::Ort(e.into()))?;
        Ok(session)
    }
}
```

Add the corresponding API inside `ParakeetEngine` in `transcribe-rs/src/engines/parakeet/engine.rs` to store the loaded engine and optional mappings to control lifetime:
```rust
pub struct ParakeetEngine {
    loaded_model_path: Option<PathBuf>,
    model: Option<ParakeetModel>,
    // RAII containers for mapped memory (e.g. MmappedModel) to prevent dropping during engine lifespan
    _mappings: Option<Vec<Box<dyn std::any::Any + Send + Sync>>>,
}

impl ParakeetEngine {
    pub fn load_model_from_bytes(
        &mut self,
        encoder_bytes: &[u8],
        decoder_joint_bytes: &[u8],
        preprocessor_bytes: &[u8],
        vocab_content: &str,
        mappings: Option<Vec<Box<dyn std::any::Any + Send + Sync>>>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let model = ParakeetModel::from_bytes(
            encoder_bytes,
            decoder_joint_bytes,
            preprocessor_bytes,
            vocab_content,
        )?;
        self.model = Some(model);
        self.loaded_model_path = None;
        self._mappings = mappings;
        Ok(())
    }
}
```

#### 3. Update the App Loader `do_load`
Update `do_load` in `src/engine.rs` to call JNI helper methods, perform the memory mappings, retrieve `vocab.txt` contents, and call `load_model_from_bytes` on the engine instance:
1. Read `vocab.txt` using the JNI AssetManager to a Rust string directly (small file, no mmap required, or mmap'd and decoded).
2. Fetch FDs, offsets, and sizes of the three models.
3. Construct `MmappedModel` instances for the models.
4. Construct `Box<dyn Any>` vectors containing the mappings to preserve their lifespans.
5. Invoke `load_model_from_bytes` and store the engine inside `GLOBAL_ENGINE`.
6. Safe cleanup: JNI references and file descriptors are closed safely, while the memory mappings remain alive inside the engine singleton.

---

# Verification Method

1. **Gradle Build Verification**:
   Build the project using `./build.sh` or `./gradlew assembleDebug`.
   Verify the built APK (e.g. `app/build/outputs/apk/debug/app-debug.apk`) structure using a zip inspect utility (or standard android studio APK analyzer) to ensure `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, and `nemo128.onnx` are packed with compression type `Stored` (uncompressed).
2. **Runtime Verification**:
   Run the application on an Android device or emulator.
   Check the application files directory `/data/data/dev.notune.transcribe/files/`. Ensure it remains empty of model files and no extraction process takes place.
   Verify that transcription functions properly, proving that ONNX Runtime sessions initialized successfully from the memory-mapped assets.
