# Handoff Report

## 1. Observation
* **Model definition & download**: In `app/build.gradle.kts` (lines 170–183, 250–252):
  ```kotlin
  val appAssetFiles = listOf(
      ModelFile("config.json", ""),
      ModelFile("vocab.txt", ""),
  )
  ...
  val modelPackFiles = listOf(
      ModelFile("encoder-model.int8.onnx",
          "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
      ModelFile("decoder_joint-model.int8.onnx",
          "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
      ModelFile("nemo128.onnx",
          "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
  )
  ...
  val appAssetsDir = project.file("src/main/assets/parakeet-tdt-0.6b-v3-int8")
  val packAssetsDir = rootProject.file("model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8")
  ```
* **Extraction runtime**: In `src/assets.rs` (lines 24-29):
  ```rust
  let base_path = PathBuf::from(path_string);
  let model_dir = base_path.join("parakeet-tdt-0.6b-v3-int8");
  let marker_file = model_dir.join(EXTRACTION_COMPLETE_MARKER);

  // Only skip extraction if the marker file exists (proves prior extraction completed)
  if marker_file.exists() {
      return Ok(model_dir);
  }
  ```
* **Engine loading**: In `src/engine.rs` (line 204-208):
  ```rust
  let mut eng = ParakeetEngine::new();
  match eng.load_model_with_params(
      &path,
      transcribe_rs::engines::parakeet::ParakeetModelParams::int8(),
  )
  ```
* **ONNX Session setup**: In `transcribe-rs/src/engines/parakeet/model.rs` (lines 149–150):
  ```rust
  let session = builder.commit_from_file(model_dir.as_ref().join(&model_filename))
      .map_err(|e| ParakeetError::Ort(e.into()))?;
  ```
* **ORT method support**: Found in cargo registry cached sources of `ort-2.0.0-rc.12` crate:
  ```rust
  pub fn commit_from_memory(&mut self, model_bytes: &[u8]) -> Result<Session>
  ```

---

## 2. Logic Chain
1. *Observation*: The app currently copies the models from `AssetManager` to the app's internal filesystem (`/data/data/dev.notune.transcribe/files/parakeet-tdt-0.6b-v3-int8`) during runtime initialization if the marker file `.extraction_complete` is missing.
2. *Observation*: The model files are loaded from files in the extracted directory in Rust (`transcribe-rs`) using the `ort` crate's `commit_from_file`.
3. *Observation*: The `ort-2.0.0-rc.12` crate contains `commit_from_memory(&mut self, model_bytes: &[u8])` which loads models directly from memory slices.
4. *Deduction*: If we package the models uncompressed (`STORED`) in the APK assets (by configuring AAPT2 `noCompress` or using renaming suffixes), the asset files are laid out contiguously inside the APK file.
5. *Deduction*: We can retrieve the raw file descriptor, offset, and size of the models from Java's `AssetManager.openFd` (or native NDK equivalent).
6. *Conclusion*: By passing these descriptor details to Rust, we can obtain the contiguous bytes either by memory-mapping the file descriptor at the given offset and size (`libc::mmap`), reading them directly into memory (`libc::pread` or `std::os::unix::fs::FileExt::read_exact_at`), or using native NDK `AAssetManager_open` / `AAsset_getBuffer`. Once we have the byte slices, we can call `commit_from_memory` to initialize the sessions directly, eliminating runtime disk extraction entirely.

---

## 3. Caveats
* **Memory footprint**: Reading the model bytes into the heap (Methods B/C) requires allocating ~670 MB of heap memory during load time, which may result in temporary high-memory pressure. Using memory mapping (`mmap`, Method A) avoids this allocation overhead as the OS maps the file directly, but requires careful page alignment calculations and pointer offsets because `mmap` requires page-aligned start offsets (4096-byte boundaries).
* **Play Asset Delivery packaging**: When packaged as an `.aab` (Android App Bundle), the asset packs are split by Google Play. If uncompressed assets are packaged inside split asset packs, we must ensure Google Play does not compress them on their servers. The filename suffix trick (e.g. renaming `.onnx` to `.onnx.wav`) is the most reliable way to force zero-compression on Google Play servers.

---

## 4. Conclusion
We have identified the exact model storage, extraction, and loading pipelines in the codebase. We conclude that we can load the models directly from the APK assets without extracting them by:
1. Configuring AAPT2 `noCompress` or renaming ONNX files to a non-compressed format (e.g. `.onnx.wav`).
2. Querying `AssetManager` in Rust to retrieve the file descriptor, offset, and size.
3. Loading the bytes using memory mapping (`mmap`), standard file offsets (`read_exact_at`), or raw file descriptor reading (`pread`).
4. Initializing the ONNX Runtime sessions using `commit_from_memory`.

---

## 5. Verification Method
* **AAPT2 Verification**:
  1. Build the APK: `./gradlew assembleDebug` or run `./build.sh`.
  2. Inspect the resulting APK (e.g., using `unzip -v app/build/outputs/apk/debug/app-debug.apk`) to verify that `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, and `nemo128.onnx` are stored with method `Stored` (0% compression).
* **Code Compilation Verification**:
  * Run cargo check on both the main crate and `transcribe-rs` to verify that any changes to `transcribe-rs` compile successfully:
    `cargo check` and `cargo check -p transcribe-rs`.
* **Runtime Verification**:
  1. Run the app on an Android device or emulator.
  2. Verify that `/data/data/dev.notune.transcribe/files/parakeet-tdt-0.6b-v3-int8` remains empty (no model extraction occurs).
  3. Verify that transcription works successfully, showing the model has loaded correctly.
