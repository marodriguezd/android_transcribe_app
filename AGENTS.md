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

- **v0.8.5** (versionCode 28) — "Fix: propagate `initNative` engine-load errors to UI (supplements v0.8.4)"
- Released: 2026-07-18
- APK: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.5
- Supersedes **v0.8.4** (released same day): a real bug in `transcribe_file.rs::initNative` silently dropped the `Err` from the engine load via `let _ = …`, leaving the UI hung on the last status text. The bug also applied to debug builds; the v0.8.4 release on the A059 was reproduced and confirmed. v0.8.5 release onward now reaches `notify_status("Error: …")` in Java so the user sees the failure reason instead of an indefinite hang.
- URL: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.5

## Device / ADB

- **Device connected (Wi-Fi)**: `192.168.1.36:42841` (Samsung A059, AsteroidsEEA, **Android 16 / SDK 36**)
- **Alternate ADB (TLS)**: `adb-00143154F001971-AbAnvz._adb-tls-connect._tcp` (same device)
- **Debug APK installed**: debug build with diagnostic logging active on device, versionCode 28 (v0.8.5 + manual-WAV-reader pipeline + initNative error propagation)
- **To launch**: `adb -s 192.168.1.36:42841 shell am start -n dev.notune.transcribe/.MainActivity`
- **To capture logs**: `adb -s 192.168.1.36:42841 logcat -d | grep "180M"`
- **Note**: port can rotate (was `38075`, now `42841`). Always check `adb devices` first.

## 180M broadcast error (fixed)

**Root cause**: The 180M decoder's ONNX graph concatenates one frame from `decoder_mems` to the token embedding via `Concat_1 (axis=2)` to form K/V sequences. With 10 prefix tokens + 1 mem frame → K/V has 11 positions. But the causal attention mask (`Trilu`) is built from `input_ids` length (10) → shape `[1,1,10,10]`. The attention scores `Q*K^T = [1,8,10,11]` can't broadcast with the mask `[1,1,10,10]` → axis 3: `10 ≠ 11, 10 ≠ 1`.

**Fix**: Feed all tokens 1 at a time (including prefix), instead of the original approach of feeding all 10 prefix tokens in one shot then switching to 1-token autoregressive steps. With 1 input token, the mask is `[1,1,1,1]` which broadcasts to any K/V length. Architecturally correct because causal self-attention makes parallel and sequential processing equivalent — the hidden states accumulate through `decoder_mems`.

**Files changed**:
- `transcribe-rs/src/engines/parakeet/model_180m.rs`: Replaced the original `is_first` branching logic with a prefix `for` loop (feeds 10 prefix tokens 1-by-1) followed by an autoregressive `loop`. Mems are always `[6, 1, N, 1024]` where N grows cumulatively.
- `app/src/main/java/dev/notune/transcribe/TranscribeFileActivity.java`: Added `volatile boolean transcribing` flag to fix infinite `onStatusUpdate("Ready")` → `startDecodeAndTranscribe()` loop.
- `src/transcribe_file.rs`: Added `log::error!` for decoder.run() failures to capture errors in logcat.

**Verification**: Transcribed `dots.wav` (565K samples at 16kHz) successfully — produced 586 characters of accurate text (Steve Jobs "connecting the dots" speech). 0 Rust warnings.

**On-device re-verification (post-v0.8.3, Samsung A059 / Android 16)**:
- Engine 180M loads with `decoder_mems: [6, 1, 1, 1024]` derived from ONNX metadata.
- Full pipeline (manual WAV reader → encoder → decoder loop) reaches `EOS (3)` after **234 generation steps**, no `Invalid dimension` / `Broadcast` errors.
- Output text: `Of course, it was impossible to connect the dots looking forward when I was in college, but it was v...` — 586 chars, byte-for-byte match with the desktop test fixture.
- See "v0.8.4 manual WAV reader" entry below for the Android 16 audio-decode context.

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
12. **On-device v0.8.3 verification on Samsung A059 (Android 16)**: confirmed decoder loop runs to EOS at step 234 with no broadcast error, 586 chars transcribed.
13. **Manual WAV reader fallback in `TranscribeFileActivity`** (unreleased, debug build only): bypasses `MediaExtractor` / `ContentResolver` for `file://` URIs which both refuse on Android 16+ scoped storage (`EACCES` / "Failed to instantiate extractor"). `openAudioStream(Uri)` returns `FileInputStream(new File(uri.getPath()))` for readable `file://` URIs; `decodeAudioToSamples` falls back with `MediaExtractor.setDataSource(uri.getPath())` (raw path); supports PCM (format=1) only, 16-bit LE, mono or stereo. RIFF chunk padding applied to unknown chunks. Throws `IOException` for unsupported containers → routes to MediaCodec for MP3 / M4A / OGG / WAVE_FORMAT_EXTENSIBLE. See "Next steps (planned)" for stable-API decision.

### Verification
- Rust: **0 warnings** in both `android_transcribe_app` + `transcribe-rs`
- Only 3 pre-existing Java compiler warnings (source/target version deprecation)
- Manual WAV decoder: bytes-by-byte equivalent output to MediaCodec path on plain PCM WAV; smoke-tested end-to-end on device with `dots.wav`

### Next steps (planned)
- Commit + tag the manual WAV reader (unreleased yet); decide whether to ship as a fix (v0.8.4) or wait for a bigger feature
- Build release APK with the new pipeline: `bash build.sh` (no `debug`) and verify on the A059 release build
- Product website (VoxLocal.app) landing page: Hero → Features → How it Works → Model Comparison → Privacy → Open Source
- **Refactor**: `DEFAULT_PROMPT` in `SettingsManager.java` should read from `R.string.label_prompt` instead of duplicating the string, keeping a single source of truth (both currently identical; risk of drift)
- Test 0.6B model on the same device to confirm no regression from manual-reader work

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

## Session history (v0.8.5)

### What was done
14. **InitNative silent-error bug surface** (Jul 18): while smoke-testing the v0.8.4 release APK on Samsung A059 / Android 16 / SDK 36, `TranscribeFileActivity` launched into TFA but the UI hung for 110+ seconds on the last engine-status text (`"Reading vocabulary…"`). Investigation in `src/transcribe_file.rs` showed the background thread spawned in `initNative` did `let _ = engine::ensure_loaded_from_thread(...)`, silently dropping the `Err` from a failed engine load (e.g. `Model 0.6B not downloaded` on a fresh install where the model-variant default is `0.6b` but only `180m` was on disk). Fix: propagate the `Err` to the UI via `notify_status("Error: …")` inside the spawned thread. Verified in Test A (debug build, fresh install, default variant `0.6b`, no models): UI now shows `Error: Model 0.6B not downloaded: No such file or directory (os error 2)` instead of hanging. Same fix applies to release builds (Rust code-path is shared between debug and release); the v0.8.4 release APK on the same device was reproduced and confirmed before the fix.
15. **Release v0.8.5** (Jul 18): version bump 27 → 28 + `0.8.4` → `0.8.5`. Rebuild release APK with the fix, on-device verification on the A059 reproduces the fix path. New GitHub release **v0.8.5** at https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.5 with notes describing the initNative fix and explicit "supersedes v0.8.4" line.

### Known Issue (v0.8.4)
v0.8.4 release APK contains the silent-error bug above. Users who downloaded v0.8.4 may experience an indefinite UI hang on first launch. Upgrading to v0.8.5 immediately surfaces the failure with a clear error message — either the user sees an error and switches variants via MainActivity, or they see the prompt to download the missing model. Until v0.8.4's APK is replaced on the GitHub release (manual step), users should be redirected to v0.8.5.

### Verification
- Rust: **0 warnings** in both `android_transcribe_app` + `transcribe-rs`
- Only the 3 pre-existing Java compiler warnings (source/target version deprecation)
- v0.8.5 release APK signs cleanly with `release.keystore`
- Test A (fix verification) on debug build + A059 + default variant + missing model: UI shows `Error: Model 0.6B not downloaded…` (was hang)
- Pre-fix Test (warm path) was already covered by the v0.8.4 session history (`dots.wav` → 586 chars Steve Jobs + `jfk.wav` → 108 chars JFK) — the Rust engine code path is unchanged, so v0.8.5 inherits the same warm-path behaviour.

### Next steps (planned) for v0.8.6 cycle
- **(b) Build asserts** in `app/build.gradle.kts`: defensive `require(isMinifyEnabled == false && signingConfig != null)` on the `assembleRelease` task. Cheap, prevents accidental minify or unsigned builds.
- **(c) CI connectedAndroidTest** step before tag/release: instrumentation test starts TFA on an emulator, verifies engine load + audio decode path returns the expected text (or expected `notify_status(\"Error:...\")` when wrong variant). Catches future regressions similar to the v0.8.4 silent-error bug before the binary ships.
- Refactor: `DEFAULT_PROMPT` in `SettingsManager.java` → single source of truth from `R.string.label_prompt`.
- Product website (VoxLocal.app) landing page: Hero / Features / How / Model Comparison / Privacy / Open Source.
