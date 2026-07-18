# Project: Offline Voice Input (android_transcribe_app)

## Architecture

Rust + JNI Android app for offline speech-to-text using NVIDIA Parakeet models.

- **Java UI**: Activities/Services in `app/src/main/java/dev/notune/transcribe/`
- **Rust JNI layer**: `src/` — bridges Java ↔ transcribe-rs
- **transcribe-rs**: ONNX Runtime based Parakeet inference

## Model variants

| Variant | Size | Files | Engine |
|---------|------|-------|--------|
| 180m (fastest) | ~395 MB | `encoder-model.int8.onnx`, `decoder-model.int8.onnx`, `vocab.txt` | `Parakeet180mModel` (model_180m.rs in transcribe-rs) |
| 0.6B (fast) | ~640 MB | `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `nemo128.onnx`, `vocab.txt` | `ParakeetEngine` (engine.rs in transcribe-rs) |

## Model loading

Models are stored in `getFilesDir()/models/parakeet-tdt-0.6b-v3-int8/` for 0.6B and `getFilesDir()/models/canary-180m-flash-int8/` for 180M. Downloaded via `ModelDownloadManager` from Hugging Face.

## Key files

| File | Purpose |
|------|---------|
| `MainActivity.java` | Main UI, model download/status |
| `SettingsManager.java` | SharedPreferences for model_variant, post-processing prompt |
| `App.java` | Application class, download manager singleton |
| `ModelDownloadManager.java` | Downloads model files, multi-callback support |
| `ModelDownloadForegroundService.java` | Foreground service for download |
| `PostProcessor.java` | AI post-processing (LLM) with prompt template |
| `PostProcessSettingsActivity.java` | Settings UI for post-processing config |
| `WordCorrector.java` | Fuzzy matching (Levenshtein + Soundex) for custom words |
| `DictionaryManager.java` | Manages dictionary entries with JSON persistence, import/export |
| `DictionaryListActivity.java` | List/manage dictionaries, import/export UI |
| `DictionaryEditActivity.java` | Edit individual dictionary entries |
| `src/engine.rs` | Global engine singleton, model loading/switching |
| `src/main_activity.rs` | JNI bridge for initNative/switchModel |

## Current version

- **v0.8.3** (versionCode 26) — "Fix: 180M decoder_mems dimensions read from ONNX metadata"
- Released: 2026-07-18
- APK: TBD
- URL: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.0

## Branches

- `main` (1986c8e) and `develop` (df8d2cd) are aligned at v0.8.0
- Tags `v0.8.0` and `v0.8.0` APK uploads updated to v0.8.3

## Build & release

```sh
# Build Rust + APK (requires NDK + cargo-ndk)
./gradlew assembleRelease

# Create release (after bumping version + versionCode)
gh release create vX.Y.Z --repo marodriguezd/android_transcribe_app \
  --title "Title (vX.Y.Z)" --notes "..." app/build/outputs/apk/release/app-release.apk#APK
```

## Session history (v0.8.3)

### What was done
1. **Download cache bug**: `downloadCache` not invalidated after `onComplete()` — caused infinite download loop on radio button click. Fixed by adding `settingsManager.invalidateModelCache(variant)` in `onComplete()` callback.
2. **UI refresh bug**: `onComplete()` didn't update status text, delete buttons, or model cache. Added `updateModelStatus()`, `updateDeleteButtons()`, and explicit "Downloaded" text.
3. **Premature listener fire**: `setupModelSelection()` initial `setChecked` triggered the RadioGroup listener during `onCreate()` without `modelSelectionChanging` guard. Fixed by wrapping with the flag.
4. **Dead code in reconnectDownloadCallbacks**: `isDownloadActive()` branch never executed because flags reset before callbacks. Replaced with working invalidation + refresh logic.
5. **RadioGroup visual glitch**: After deleting a model, multiple radio buttons appeared checked. Fixed by replacing `RadioButton.setChecked(true)` with `RadioGroup.check(id)` everywhere — the proper Android API for programmatic selection.
6. **Welcome dialog order**: Buttons reordered to match main UI: Fastest (180M) → Fast (0.6B) → Use without model. Positive=Fastest, Neutral=Fast, Negative=Skip.
7. **Download speed**: Read buffer increased from 8 KB to 64 KB in `ModelDownloadManager`, reducing read syscalls by 8×.
8. **180M crash**: ONNX Runtime "Invalid dimension #3" error — first-iteration decoder mems tensor had shape `[64, 1, 0, 128]` (dimension #2 was 0). Changed to `[64, 1, 1, 128]` filled with zeros in `model_180m.rs`. All 180M transcriptions had been failing since v0.8.0.
9. **180M decoder_mems dimensions**: `d0=64` (layers) and `d3=128` (hidden size) were wrong — model expects `[6, ?, ?, 1024]`. Added `decoder_num_layers` and `decoder_hidden_size` fields to `Parakeet180mModel`, read from ONNX metadata via `decoder.inputs()` in `from_memory()`. Added `InputNotFound` and `TensorShape` error variants. Added log line showing extracted shapes. Same pattern as 0.6B model's `create_decoder_state()`.
10. **"Use without model" feature**: New `ModelVariant::None` variant in Rust engine + Java UI. Third radio button that unloads the engine without downloading a model. Handled in `SettingsManager.isModelDownloaded`, `deleteModel`, `ModelDownloadManager` constructor.
11. **Release v0.8.3** (Jul 18): version bump (25→26), APK build, upload to release v0.8.0 on GitHub.

### Verification
- Rust: **0 warnings** in both `android_transcribe_app` + `transcribe-rs`
- Only 3 pre-existing Java compiler warnings (source/target version deprecation)

### Next steps (planned)
- Product website (VoxLocal.app) landing page: Hero → Features → How it Works → Model Comparison → Privacy → Open Source
- **Refactor**: `DEFAULT_PROMPT` in `SettingsManager.java` should read from `R.string.label_prompt` instead of duplicating the string, keeping a single source of truth (both currently identical; risk of drift)

## Safety & hardening (v0.8.0+)

### Rust
- `LOAD_STATE` wrapped in `catch_unwind(AssertUnwindSafe(...))` — panics during `do_load` transition to `Failed` state + `cvar.notify_all()`, preventing permanent deadlock
- All `Mutex::lock()` calls use `unwrap_or_else(|poisoned| { log::error!; poisoned.into_inner() })` — recovers from poisoned mutexes instead of cascading panic
- All `extern "system"` JNI entry points guard local refs with `AutoLocalFrame::new(&env, 16)` (RAII struct in `src/lib.rs`)
- All JNI `extern "system"` functions use `JObject` (not `JClass`) for instance-method `this` parameter
- All `.expect()` / `.unwrap()` in JNI FFI functions replaced with error handling + early return
- All `usize` subtractions use `.saturating_sub()` (prevent underflow)
- `var.max(0.0).sqrt().max(1e-10)` in mel_128 prevents NaN from float precision
- `mel_to_hz` clamps overflow to `MEL_HIGH_FREQ` preventing `inf` propagation
- Transcription buffers zeroed via `Zeroize::zeroize()` after JNI delivery (voice_session, recog_service, subtitle, transcribe_file)
- `MemoryMappedAsset` uses `MAP_PRIVATE` + overflow guard on `length as usize`

### Java
- Download callbacks use `CopyOnWriteArrayList` with `clearCallbacks()` on terminal events + `removeCallback()` on lifecycle transitions
- `MainActivity` uses `WeakReference<MainActivity>` for all background threads + `isFinishing()/isDestroyed()` checks
- `onSaveInstanceState` in MainActivity, RecognizeActivity, LiveSubtitleActivity
- 30s timeout clears stale "Switching model…" status text
- `isModelDownloaded` cached in `HashMap` per variant, invalidated on delete/download
- `SettingsManager.getContext()` returns `getApplicationContext()` (not raw Activity context)
- `DictionaryManager` uses lazy `ensureLoaded()` instead of synchronous file I/O in constructor
- `WordCorrector` pre-computes Soundex codes at entry creation + pre-compiles regex `Pattern` constants
- `RustInputMethodService`: `volatile boolean destroyed` guards `initNative` thread; `onDestroy` removes all `Handler` callbacks
- `ModelDownloadManager`: `volatile WakeLock`, 5min timeout, `shutdown()` method, `executor.shutdownNow()` on cancel
- Model files verified after download (non-empty, size > 0)
- `App.startDownload()` stops old ForegroundService + shuts down old manager before replacing
- `ModelDownloadForegroundService`: `ACTION_RETRY` for persistent error notification with retry button
- API key stored via `EncryptedSharedPreferences` (AES256_GCM) with plaintext fallback
- API key input uses `endIconMode="password_toggle"` for visibility control
- `PostProcessor`: OkHttpClient with connect/read/write timeouts + hostnameVerifier; logcat redacted (no error bodies, no stack traces)
- `isErrorStatus(String)` helper used instead of fragile `startsWith("Error")` in IME + TranscribeFileActivity

## Important patterns

- Engine singleton: `GLOBAL_ENGINE` (`Lazy<Mutex<Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)>>>`) — variants: V0_6b, V180m, None
- Loading coordination: `LOAD_STATE` mutex + Condvar to serialize loads
- Model switching: `switch_model()` first acquires LOAD_STATE lock, then sets engine to None (old engine stays valid during reload — TOCTOU fixed by `ensure_loaded` returning engine reference directly)
- ORT providers on Android: NNAPI, XNNPACK, CPU (in priority order)
- `ensure_loaded` / `ensure_loaded_from_thread` return `Result<Option<engine_state>>` — callers use the returned reference instead of a second `get_engine()` call
- Download callbacks stored in `CopyOnWriteArrayList` — removed after terminal events and lifecycle transitions
- Post-processing prompt: defined in `SettingsManager.DEFAULT_PROMPT` and `strings.xml@label_prompt`
- `ensure_loaded` / `ensure_loaded_from_thread` return `Result<Option<...>>` — `None` result means no engine loaded (valid for "Use without model")
## Common pitfalls

- `rbModelFast`, `rbModelFastest`, and `rbModelNone` may be null if `setupModelSelection` hasn't run
- `App.startDownload()` is idempotent for the same variant — it won't cancel an active download, only adds the callback
- Download callbacks should use `WeakReference<MainActivity>` with lifecycle checks
- `ModelDownloadForegroundService.onStartCommand()` must NOT call `App.startDownload()` with a new callback if a download is already in progress for the same variant (it will just add the callback, not restart)
- `POST_NOTIFICATIONS` permission on Android 13+ is requested in `onCreate()` but may not be resolved before download starts — `startForeground()` in ForegroundService catches the `SecurityException` and continues without notification
- Post-processing field shows `DEFAULT_PROMPT` from `SettingsManager` as the text, and `label_prompt` from `strings.xml` as the hint
- Dictionary import uses `ActivityResultContracts.OpenDocument` for JSON/text files; export uses `ActivityResultContracts.CreateDocument("application/json")`
- The 180M AED model does not support hotwords yet
