# Handoff Report — Explorer 1 (Milestone 1)

## 1. Observation
- Large model files are stored in a separate asset pack `:model_assets`.
  - In `app/build.gradle.kts` line 250-252:
    ```kotlin
    val appAssetsDir = project.file("src/main/assets/parakeet-tdt-0.6b-v3-int8")
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8")
    ```
  - In `model_assets/build.gradle.kts` line 5-9:
    ```kotlin
    assetPack {
        packName.set("model_assets")
        dynamicDelivery {
            deliveryType.set("install-time")
        }
    }
    ```
- Extraction logic is in `src/assets.rs` line 25:
  ```rust
  let model_dir = base_path.join("parakeet-tdt-0.6b-v3-int8");
  ```
  It recursively calls `AssetManager.open()` and copies all asset files into the app's internal files directory `/data/user/0/dev.notune.transcribe/files/parakeet-tdt-0.6b-v3-int8`.
- Model loading is triggered in `src/engine.rs` line 204:
  ```rust
  let mut eng = ParakeetEngine::new();
  match eng.load_model_with_params(
      &path,
      transcribe_rs::engines::parakeet::ParakeetModelParams::int8(),
  )
  ```
- The actual model files are loaded in `transcribe-rs/src/engines/parakeet/model.rs` line 74-76 and line 149:
  ```rust
  let encoder = Self::init_session(&model_dir, "encoder-model", None, quantized)?;
  ...
  let session = builder.commit_from_file(model_dir.as_ref().join(&model_filename))
  ```

## 2. Logic Chain
1. *Observation 1*: The models are currently packaged in standard APK assets/install-time asset packs and then copied to private internal files directory `/data/.../files/parakeet-tdt-0.6b-v3-int8` before loading.
2. *Observation 2*: By default, Android compresses files in assets, which makes them unmappable via `AssetManager.openFd` (throwing `FileNotFoundException`). Adding `androidResources { noCompress += listOf("onnx") }` to `app/build.gradle.kts` packages them uncompressed.
3. *Observation 3*: When files are uncompressed, `AssetManager.openFd(path)` returns an `AssetFileDescriptor` containing a raw file descriptor (pointing to the APK), an offset, and a length.
4. *Observation 4*: Using JNI, we can retrieve this raw FD, offset, and size in Rust, duplicate the FD with `libc::dup(fd)`, close the Java descriptor, and memory-map the duplicated FD via `libc::mmap`.
5. *Observation 5*: Standard Linux `mmap` requires page-aligned offsets. We can align the start offset to a page boundary (`aligned_offset`), calculate the `padding`, mapping the page-aligned range, and obtaining a safe byte slice starting at `pointer + padding` for `length` bytes.
6. *Observation 6*: The `ort` crate's `SessionBuilder::commit_from_memory` API accepts a byte slice `&[u8]`. Since it internally copies the model bytes when building the session, we can unmap the mapped memory and close the duplicated file descriptor immediately after the session is created.
7. *Observation 7*: The small text file `vocab.txt` can be read directly into a String using `InputStream` stream copy JNI calls, avoiding disk writes or complex mapping logic.
8. *Conclusion*: By combining uncompressed packaging, JNI `AssetFileDescriptor` querying, page-aligned `mmap`, and `ort`'s `commit_from_memory`, we can load the models directly from the APK assets without writing any files to `/data/.../files/` at runtime.

## 3. Caveats
- Android App Bundle (AAB) splits: For bundle builds, the asset pack is delivered as a split APK. The `AssetManager` automatically merges the asset namespaces, making it transparent. However, we assume that `AssetManager.openFd` functions correctly across split APKs, which is supported by Android 5.0+.
- Page size variance: Newer Android 15 devices may use 16KB page sizes instead of 4KB. The proposed alignment calculation using `sysconf(_SC_PAGESIZE)` dynamically handles both 4KB and 16KB pages.

## 5. Verification Method
1. Verify the layout compliance: ensure all changes to the app remain in standard files and no source/test code is added to `.agents/`.
2. Inspect the packaging: Run `./gradlew assembleDebug` or build the bundle, unzip the generated APK, and confirm that `encoder-model.int8.onnx` is uncompressed (compression method stored in zip headers is DEFLATE = 0, i.e., STORED).
3. Verify build and test behavior:
   - `./gradlew test` (verified: completed successfully in 57s, though Java unit tests are currently `NO-SOURCE`).
   - `cargo test --package transcribe-rs` (runs native Rust unit tests).
   - `./gradlew assembleDebug` (compiles and packages).
4. Invalidation conditions: If `AssetManager.openFd` throws an exception, the ONNX model files are compressed or missing, meaning `noCompress` was not applied correctly.
