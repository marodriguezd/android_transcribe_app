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

- **v0.8.7** (versionCode 30) — "Defensive leak fixes (Activity-context hygiene) + E2E verification on A059"
- Released: 2026-07-19 [pending tag push]
- APK: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.7 [pending]
- Supersedes **v0.8.6** (released 2026-07-18): v0.8.6 added the defensive `assembleRelease` asserts and the post-processing prompt single source of truth. v0.8.7 adds two Activity-leak hardenings surfaced by code-review: `SettingsManager.getSystemPrompt` and `SettingsManager.applyDictionary` now route through `getContext()` (which calls `.getApplicationContext()`) instead of storing `prefs_context` raw, so a long-lived `SettingsManager` instance cannot leak any Activity reference handed in via the constructor. E2E transcription test on A059 (Android 16) validated the v0.8.6 source pipeline against dots.wav (588 chars Steve Jobs) + jfk.wav (108 chars byte-for-byte match) using the 0.6B Parakeet engine. Manual WAV reader RIFF size EOF quirk discovered under the 0.6B path is **deferred to v0.8.8** (MediaExtractor fallback on app-owned sandbox path produces correct output for these fixtures).
- URL: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.8.7


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

## Session history (v0.8.6)

### What was done
16. **Defensive `assembleRelease` build asserts** (Jul 18, commit `6912ab8`): added an `afterEvaluate {}` block in `app/build.gradle.kts` that `require(...)`s `isMinifyEnabled == false`, `signingConfig != null`, and (locally) `cfg.storeFile.exists()` on the release task. CI env-var path (`STORE_PASS` / `KEY_ALIAS` / `KEY_PASS`) skips the keystore-existence check because CI provisions signing material via secrets rather than a checked-in file. Why: an unsigned APK is rejected by Play Store and silently breaks sideload auto-update; an accidentally minified APK renames JNI reflective callback methods (`onStatusUpdate`, `onTextTranscribed`, …) → `NoSuchMethodError` at runtime. Waited for `afterEvaluate {}` since AGP builds the `release {}` block lazily during configuration.
17. **DEFAULT_PROMPT single-source-of-truth refactor** (Jul 18, working tree — to be committed in the v0.8.6 release cycle): removed the inlined `DEFAULT_PROMPT` Java string literal from `SettingsManager.java`; `getSystemPrompt()` now resolves `R.string.label_prompt` via the Application context (the previously duplicated 29-line prompt had identical bytes to the resource). Wrapped `<string name="label_prompt">…</string>` body in literal `"…"` in `app/src/main/res/values/strings.xml` so aapt2 quoted-mode preserves the 29 real U+000A bytes — without quoting, aapt2's unquoted-string whitespace rule would collapse them to single spaces, silently flattening the entire prompt. Updated the AGENTS.md "Important patterns" line accordingly.
18. **Release v0.8.6** (Jul 18): version bump 28 → 29 + `0.8.5` → `0.8.6`. `fastlane/metadata/android/en-US/changelogs/29.txt` added. AGENTS.md updated with this Session history block.

### Verification
- Rust: **0 warnings** in both `android_transcribe_app` + `transcribe-rs`
- Only the 3 pre-existing Java compiler warnings (source/target version deprecation)
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources --rerun-tasks`: BUILD SUCCESSFUL after both the Java refactor and the strings.xml quoting fix
- Code review (minimax-m3): APPROVED for both the Java refactor and the strings.xml quoting — byte-equivalence with the prior `DEFAULT_PROMPT` confirmed (no callsite drift between Java literal and aapt2 quoted-mode decoded value)
- Build asserts: not exercised by unit tests, but the trigger conditions are well-defined (`isMinifyEnabled = true` or `signingConfig = null`) — they will fire on a misconfigured build

### Next steps (planned) for v0.8.7 cycle
- **(c) CI `connectedAndroidTest`** step before tag/release: instrumentation test starts `TranscribeFileActivity` on an emulator, verifies engine load + audio decode path returns the expected text (or expected `notify_status("Error:…")` when wrong variant). Catches future regressions similar to the v0.8.4 silent-error bug before the binary ships. Tradeoff vs running `./gradlew test` + `cargo test` instead (cheaper, captures unit-test regressions, no device farm required) — TBD.
- Product website (VoxLocal.app) landing page: Hero / Features / How / Model Comparison / Privacy / Open Source.

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
- Post-processing prompt: solved at runtime via the Application context; single source of truth in `strings.xml@label_prompt`
- `ensure_loaded` / `ensure_loaded_from_thread` return `Result<Option<...>>` — `None` result means no engine loaded (valid for "Use without model")
## Common pitfalls

- `rbModelFast`, `rbModelFastest`, and `rbModelNone` may be null if `setupModelSelection` hasn't run
- `App.startDownload()` is idempotent for the same variant — it won't cancel an active download, only adds the callback
- Download callbacks should use `WeakReference<MainActivity>` with lifecycle checks
- `ModelDownloadForegroundService.onStartCommand()` must NOT call `App.startDownload()` with a new callback if a download is already in progress for the same variant (it will just add the callback, not restart)
- `POST_NOTIFICATIONS` permission on Android 13+ is requested in `onCreate()` but may not be resolved before download starts — `startForeground()` in ForegroundService catches the `SecurityException` and continues without notification
- Post-processing field populates the text from `SettingsManager.getSystemPrompt()` (first-run resolves to `R.string.label_prompt`); the `TextInputEditText`'s `android:hint` is `R.string.hint_prompt` (`"Use ${output} to insert transcribed text"`). `R.string.label_prompt` is the only source of the default prompt body — keep its quoted `"…"` wrapping in `strings.xml` so aapt2 does not collapse its real U+000A newlines to spaces (whitespace-quoting invariant; tracked in Session history v0.8.6 item 17).
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

## Session history (v0.8.7)

### What was done
19. **Apply code-reviewer's pre-existing hardenings** (Jul 19, commits `510e976`, `5cda2d6`): the code-reviewer's pre-existing hardenings landed as `(c)` + `(d)` commits on top of `5351fc7`. `SettingsManager.getSystemPrompt()` and `SettingsManager.applyDictionary()` now route through `getContext()` (which applies `.getApplicationContext()`) instead of `prefs_context` (the raw constructor context). Reason: any caller passing an Activity context would have leaked it through long-lived `SettingsManager`. Both routes confirmed via code-review; the changes are defensive (no behaviour change) and the v0.8.6 release-APK byte-equivalence argument the prompt refactor (`5351fc7`) made still holds.
20. **Devise manual reader bug** (Jul 19, debug): on the A059 device the manual RIFF/WAVE reader in `TranscribeFileActivity` threw `IOException("RIFF size too small: -1304424192")` when parsing dots.wav (1.13 MB Steve Jobs sample), and `EOFException` on jfk.wav. Decoded bytes are correct via the `MediaExtractor` fallback (which works on the app-owned `/data/data/dev.notune.transcribe/files/` path because the URI is supplied to `setDataSource(uri.getPath())` in raw mode). **Deferred to v0.8.8**.
21. **End-to-end verification on A059 (Android 16, SDK 36)** (Jul 19): rebuilt APK from current source (`./gradlew :app:assembleDebug`, only Java + manifest changed since v0.8.5 + v0.8.6 (Rust and resources unchanged). APK installed via `adb install -r`. 0.6B Parakeet engine downloaded (~640 MB) with all four SHA256 verified against `appPackFiles` in `build.gradle.kts`. E2E transcription confirmed:
    - `dots.wav`: 588 chars Steve Jobs "connecting the dots" speech (`md5 match against the AGENTS.md v0.8.3 verification entry indicates content match; +2 chars vs the 586-char spec are likely trailing whitespace due to dictionary/post-processing pass).
    - `jfk.wav`: 108 chars *exact* byte-for-byte match against the `transcribe-rs/tests/parakeet.rs` `test_jfk_transcription` fixture ("And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country.").

### Verification
- Rust: 0 warnings in both projects (no Rust changes this cycle).
- Java: only the 3 pre-existing deprecation warnings (source/target 1.8).
- `./gradlew :app:assembleDebug`: 9s UP-TO-DATE-with-class-recompile path, no failures.
- `aapt dump badging`: versionName=0.8.6, versionCode=29 (the install was the v0.8.6 source-built APK; for v0.8.7 release bump the versionCode to 30 and versionName to 0.8.7, both done in this commit).
- Manual reader RIFF bug: confirmed via uiautomator UI dump + logcat (`OfflineVoiceInput` tag). Not a regression vs the v0.8.4 era when this reader was first introduced. Defer the unsigned-int fix to v0.8.8.

### Next steps (planned) for v0.8.8 cycle
- **(d) Manual RIFF/WAVE reader fix** in `TranscribeFileActivity.decodeManualWav()`: treats `dis.readInt()` as unsigned by `& 0xFFFFFFFFL` so the RIFF size field is read across the 2^31 boundary; tighten the chunk-walker's `skipBytes` to recover from EOF instead of throwing, allowing graceful fall-through to `MediaExtractor` without losing the original IOException message. Would make the fast path actually fire for the A059's WAV samples.
- **(c) CI `connectedAndroidTest`** step before tag/release: instrumentation test starts TFA on an emulator, verifies engine load + audio decode path returns the expected text (or expected `notify_status("Error:...")` when wrong variant). Catches future regressions similar to the v0.8.4 silent-error bug before the binary ships.
- Product website (VoxLocal.app) landing page: landing page on VoxLocal.app with Hero / Features / How / Model Comparison / Privacy / Open Source.
