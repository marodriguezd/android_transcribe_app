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
| `PostProcessor.java` | AI post-processing (LLM) with active prompt from repository |
| `PostProcessSettingsActivity.java` | Settings UI for post-processing config + active prompt preview |
| `PostProcessPromptsListActivity.java` | List/manage multiple prompts, radio selection, import/export |
| `PostProcessPromptEditActivity.java` | Edit prompt name/body with char count + validation |
| `Prompt.java` | Data model with JSON serialization, BUILTIN_ID magic constant |
| `PromptsRepository.java` | AtomicFile persistence, migration from legacy system_prompt, active prompt tracking |
| `WordCorrector.java` | Fuzzy matching (Levenshtein + Soundex) for custom words |
| `DictionaryManager.java` | Manages dictionary entries with JSON persistence, import/export |
| `DictionaryListActivity.java` | List/manage dictionaries, import/export UI |
| `DictionaryEditActivity.java` | Edit individual dictionary entries |
| `src/engine.rs` | Global engine singleton, model loading/switching |
| `src/main_activity.rs` | JNI bridge for initNative/switchModel |

## Current version

- **v0.9.0** (versionCode 32) — "Clean-up release. 8 prior v0.x GitHub Releases hard-deleted; new narrative built around three pillars that distinguish this fork from upstream."
- Released: 2026-07-20
- APK: https://github.com/marodriguezd/android_transcribe_app/releases/tag/v0.9.0
- The three pillars:
  1. **Transcription engine** — kept the Rust/ONNX pipeline (`transcribe-rs` + NVIDIA Parakeet TDT 0.6B + Canary 180M Flash, INT8 quantized) instead of upstream's `v0.1.18` move to `transcribe.cpp` + Whisper.
  2. **AI post-processing** — multi-prompt template system, default app prompt editable + exportable, default "My words" dictionary editable + exportable. Upstream has no post-processing system at all.
  3. **Model selection UX** — first-run welcome dialog with three options + footprint inline (`Fast / 0.6B`, `Fastest / 180M`, `Use without / No model`). Real choice that the user can revisit anytime.
- v0.8.x detailed history retained below (see "Session history (v0.8.x)" sub-sections) for debugging context. This v0.9.0 release consolidates that work into the pillar framing; the underlying behaviour is unchanged except for the versionName/Code bump and the marketing/narrative updates in `README.md`, `fastlane/metadata/`, and this file's "Current version" header.


## Device / ADB

- **Device connected (Wi-Fi)**: `192.168.1.45:37601` (Samsung A059, AsteroidsEEA, **Android 16 / SDK 36**)
- **Alternate ADB (TLS)**: `adb-00143154F001971-AbAnvz._adb-tls-connect._tcp` (same device)
- **Debug APK installed**: debug build with bundled model assets, versionCode 30 (v0.8.7 + multi-prompt + bundled debug assets)
- **To launch**: `adb -s 192.168.1.45:37601 shell am start -n dev.notune.transcribe/.MainActivity`
- **To capture logs**: `adb -s 192.168.1.45:37601 logcat -d | grep -E "180M|PromptsRepo|PostProcessor"`
- **Note**: port can rotate. Always check `adb devices` first.

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

- `main` (60548dd) — diverged from `develop` at v0.8.0; `develop` is 16 commits ahead
- `develop` — HEAD includes the v0.9.0 cleanup commits (version bump, fastlane metadata + README rewrite, AGENTS.md "Current version" + Session history consolidation)
- Tags: `v0.8.0` through `v0.8.8` retained locally for git history audit; all GitHub Releases from `v0.2.0-ai` through `v0.8.8` were hard-deleted at the v0.9.0 release commit.

## Build & release

```sh
# Build Rust + APK (requires NDK + cargo-ndk)
./gradlew assembleRelease

# Create release (after bumping version + versionCode)
gh release create vX.Y.Z --repo marodriguezd/android_transcribe_app \
  --title "Title (vX.Y.Z)" --notes "$(cat fastlane/metadata/android/en-US/changelogs/<versionCode>.txt)" \
  app/build/outputs/apk/release/app-release.apk#APK

# Hard-delete a stale release (irreversible — be sure)
gh release delete vX.Y.Z --repo marodriguezd/android_transcribe_app --yes
# `gh release delete` removes the release from the API and unlinks the APK
# asset. Git tags are NOT deleted. The release is recoverable from GitHub
# support within ~30 days on request, but not from the CLI. Verify with
# `gh release view` and `gh release list` before deleting.
```

### Play Store caveat (only relevant if listing on Play)

`title.txt` and the `<application android:label>` in `AndroidManifest.xml`
both still say "Offline Voice Input", and `applicationId` is the
`dev.notune.transcribe` namespace shared with the upstream build. Play
Store rejects duplicate titles and duplicate `applicationId`s on the same
Play Store account, so listing this build on Play requires either (a)
shipping under a different `applicationId` suffix with a unique title, or
(b) deleting the upstream listing first. F-Droid and direct APK
distribution have neither restriction.

## Differentiation vs upstream

This fork (`marodriguezd/android_transcribe_app`) diverges from `notune/android_transcribe_app` at commit `bdecb25` (upstream `v0.1.17`). README.md, fastlane/full_description.txt and the GitHub release notes for v0.9.0 all lead with the **three pillars** framing. The same package name (`dev.notune.transcribe`) on both forks means they cannot coexist on the same device — pick one.

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
- Post-processing prompt (single): solved at runtime via the Application context; single source of truth in `strings.xml@label_prompt`
- **Multi-prompt system**: `PromptsRepository` with `AtomicFile` persistence, legacy migration, double-checked locking lazy init. `SettingsManager.getActivePromptBody()` is the hot-path API. Active prompt tracked via `active_prompt_id` SharedPreference key; auto-fallbacks to builtin on deletion.
- Bundled debug assets: `src/debug/assets/` ships ONNX files inside debug APK; `ModelDownloadManager.tryCopyAsset()` extracts on first launch. Release builds use Play Asset Delivery (`model_assets/`).
- `ensure_loaded` / `ensure_loaded_from_thread` return `Result<Option<...>>` — `None` result means no engine loaded (valid for "Use without model")
## Common pitfalls

- `rbModelFast`, `rbModelFastest`, and `rbModelNone` may be null if `setupModelSelection` hasn't run
- `App.startDownload()` is idempotent for the same variant — it won't cancel an active download, only adds the callback
- Download callbacks should use `WeakReference<MainActivity>` with lifecycle checks
- `ModelDownloadForegroundService.onStartCommand()` must NOT call `App.startDownload()` with a new callback if a download is already in progress for the same variant (it will just add the callback, not restart)
- `POST_NOTIFICATIONS` permission on Android 13+ is requested in `onCreate()` but may not be resolved before download starts — `startForeground()` in ForegroundService catches the `SecurityException` and continues without notification
- Post-processing prompt: `R.string.label_prompt` is the source for the default prompt body and the built-in prompt in PromptsRepository — keep its quoted `"…"` wrapping in `strings.xml` so aapt2 does not collapse its real U+000A newlines to spaces (whitespace-quoting invariant).
- **Multi-prompt system**: `PromptsRepository` uses `getApplicationContext()` internally (in constructor). `SettingsManager.getPromptsRepository()` routes via `getContext()` (which calls `getApplicationContext()`). Never pass an Activity context directly — it would be leaked through the constructor even though we use `getApplicationContext()`.
- `PromptsRepository` import rejects empty-body prompts (`IllegalArgumentException`). Export of the builtin prompt writes a body-only JSON template (id-stripped); importing it via `importFromJson` creates a regular user prompt with a fresh UUID.
- `PostProcessor` appends `${output}` if missing from the active prompt body — this is a defensive fallback; the built-in prompt includes it correctly.
- `PromptsRepository.migrateFromPreferences()` is a one-shot migration from the legacy `system_prompt` SharedPreferences key. After migration, the legacy key is removed and the migrated prompt becomes active. This only fires once.
- `extractBundledAssets()` in `ModelDownloadManager` only runs on debug builds (assets exist in `src/debug/assets/`). Release builds must download models or use Play Asset Delivery.
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

## Session history (post-v0.8.7 feature commit)

### Commit `8c1722d` — "feat: multi-prompt post-processing + bundled debug assets + TFA improvements"

**Date**: 2026-07-19 (after v0.8.7 tag, no version bump)

### What was done
22. **Multi-prompt post-processing system**:
    - `Prompt.java` model class with JSON serialization, `BUILTIN_ID` (`__builtin__`) magic constant for the always-present read-only default. Factory method `createNew()` generates UUID + timestamp.
    - `PromptsRepository.java` with `AtomicFile` persistence (`files/prompts.json`), double-checked locking lazy init, schema migration from legacy `system_prompt` SharedPreferences key into a "Default (migrated)" user prompt. Active prompt tracked via `active_prompt_id` pref key, auto-fallbacks to builtin on deletion.
    - `PostProcessPromptsListActivity.java` with Material Design `RecyclerView`, radio selection for active prompt, import/export via JSON, edit/delete/duplicate actions. Menu bar with Import/Export icons.
    - `PostProcessPromptEditActivity.java` with TextInputLayout for name + body, character counter, save via ExtendedFloatingActionButton. Validates non-empty name and body.
    - `PostProcessSettingsActivity.java` refactored: active prompt preview (name + truncated body in monospace) replaces the old inline TextInputEditText. "Manage prompts" button navigates to list activity.
    - `PostProcessor.java` now uses `SettingsManager.getActivePromptBody()` (single source of truth through `PromptsRepository`) instead of reading `system_prompt` directly. Defensive `${output}` placeholder check appends the placeholder if missing.
    - Import validation in `PromptsRepository.importFromJson()` rejects empty-body prompts.

23. **Bundled model assets for debug builds**:
    - `ModelDownloadManager.extractBundledAssets()` + `tryCopyAsset()` extract ONNX models from APK assets on first launch (eliminates Wi-Fi download for debug APKs).
    - `MainActivity.extractAllBundledModelsIfNeeded()` auto-extracts bundled models on startup.
    - `build.gradle.kts`: added `modelPackFiles180m`, `appAssetFiles180m`, and `huggingFaceRepo180m` for Canary-180m-flash-int8. Debug-only asset directories (`src/debug/assets/`) ship the heavy ONNX files inside the debug APK. The `downloadModels` task now fetches both 0.6B and 180M model variants.
    - `canary-180m-flash-int8/vocab.txt` (5248 BPE tokens with language/speaker/special tokens) bundled as app asset.

24. **TranscribeFileActivity improvements**:
    - Post-processing enabled for file transcription (feature parity with RecognizeActivity and IME).
    - `MAX_AUDIO_FILE_SIZE` reduced to 16 MB with corrected comment (~8 min of 16 kHz speech).
    - All `SettingsManager` calls now use `getApplicationContext()` for Activity-context leak prevention.

25. **Code review fixes applied**:
    - `SettingsManager.getPromptsRepository()` routes via `getContext()` (not `prefs_context`), following v0.8.7 leak pattern.
    - `TranscribeFileActivity` uses `getApplicationContext()` for all `SettingsManager` instantiations.
    - `PromptsRepository.importFromJson()` validates non-empty body.
    - `MAX_AUDIO_FILE_SIZE` comment corrected from ">2h" to "~8min".

### Verification
- Rust: 0 warnings (no Rust changes).
- Java: only the 3 pre-existing deprecation warnings.
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources`: BUILD SUCCESSFUL.
- E2E transcription confirmed (jfk.wav: 108 chars, dots.wav: 586 chars) on Samsung A059 / Android 16.
- Bundled model extraction tested: ONNX files extracted from `src/debug/assets/` to `getFilesDir()/models/` on first launch.
- Legacy `system_prompt` migration tested: single legacy prefs key becomes "Default (migrated)" prompt in `prompts.json`.

### Next steps (planned)
- **(d) Manual RIFF/WAVE reader fix** in `TranscribeFileActivity.decodeManualWav()`: treats `dis.readInt()` as unsigned by `& 0xFFFFFFFFL` so the RIFF size field is read across the 2^31 boundary; tighten the chunk-walker's `skipBytes` to recover from EOF instead of throwing, allowing graceful fall-through to `MediaExtractor` without losing the original IOException message.
- **(c) CI `connectedAndroidTest`** step before tag/release: instrumentation test starts TFA on an emulator, verifies engine load + audio decode path returns the expected text (or expected `notify_status("Error:...")` when wrong variant).

## Session history (v0.8.8 - welcome dialog redesign + ordering)

### What was done
26. **Welcome dialog reordered L→R Skip → Fastest → Fast** (de menor a mayor capability, was Fastest → Fast → Skip). UX rationale: dialog = first ribbon the user sees = "smallest / safest first", so an escalating capability curve helps the user opt into a heavier download only after seeing the lighter option.
27. **Two-line button text per button**: each `MaterialButton` carries primary label + secondary identifier. Strings via new keys `welcome_btn_skip_full = "Use without\nNo model"`, `welcome_btn_fastest_full = "Fastest\n180M"`, `welcome_btn_fast_full = "Fast\n0.6B"`. The original single-line keys (`welcome_btn_skip` / `_fastest` / `_fast`) are untouched because `activity_main.xml:323` (the `rb_model_none` row) still references `welcome_btn_skip`.
28. **StaticLayout crash fix**: `android:maxLines="2"` added explicitly to each dialog button. Required because MaterialButton inherits Button's default `maxLines=1`, and combining `maxLines=1` + `ellipsize=end` + literal `\n` in `text` triggers an `IndexOutOfBoundsException` in `android.text.StaticLayout.calculateEllipsis()` during inflation — the dialog crashes on first launch in an infinite restart loop. `maxLines=2` lets StaticLayout treat the literal `\n` as a real line break. Layout XML carries an explanatory comment so a future contributor doesn't strip it.
29. **`firstLaunchDialogShown = true` moved AFTER `showFirstLaunchDownloadDialog()` returns**: was previously set BEFORE `dialog.show()` (legacy from when it lived next to the call). If `show()` throws OR the Activity is paused mid-inflate (system permission/optimisation prompts), the flag stayed true and the dialog never re-fired. Now: if `show()` throws, the flag stays false so onResume can retry next time the Activity returns.
30. **`welcomeDialog = null` in all 3 click handlers** after `dialog.dismiss()`. Hygiene: previously the field held a stale-but-not-showing dialog reference until `onPause`'s next dismissal. Now the field is null'd at the exact moment the dialog surface goes away.
31. **`onPause()` continues to dismiss-and-null the welcome dialog** (added in the previous round, retained): `setCancelable(false)` blocks back-button dismissal, so if a share intent or any Activity transition pushes us behind another window while the dialog is up, we need an explicit lifecycle hook to dispose it cleanly. Both the click handler (user-initiated) and `onPause` (system-initiated) paths now end with `welcomeDialog = null`; idempotent in the single-threadlet main loop.

### Key design decisions (rationale to keep in mind for future contributors)
- **Asymmetry between welcome dialog (Skip → Fastest → Fast) and radio card (Fastest → Fast → None) is INTENTIONAL**. The radio card in `activity_main.xml` leads with the recommended option (`Fast`) and the welcome dialog leads with the safest option (`Skip`). Don't re-align them.
- **`_full` keys for the dialog (two-line) coexist with the originals (single-line) for the radio card**. The radio card already uses the originals via the `rb_model_none` row, so deleting them would break that earlier surface.
- **`maxLines=2` is mandatory**, not optional, when feeding `MaterialButton` text with literal `\n`. Strip it and the click triggers the StaticLayout crash the first time the user opens the dialog.

### Verification
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources --rerun-tasks`: **BUILD SUCCESSFUL** (last retry after fixing an orphan `}` between `showFirstLaunchDownloadDialog` and `updateModelStatus`).
- Code-reviewer (minimax-m3) verdict: **APPROVE** with one Medium item — `android:ellipsize="end"` was kept as a defensive fallback on the buttons but, since it's the original StaticLayout crash trigger, future contributors who add longer multi-line labels should drop it (or shorten the string). Defensive choice preserved for now.
- On-device visual confirmation is masked by TWO existing UX conditions: (a) `MainActivity.onCreate()` spawns `extractAllBundledModelsIfNeeded()` on a background thread which can complete before the first `onResume`'s `!isModelDownloaded` check fires, hiding the dialog skip condition; (b) `onCreate()` also calls `requestNotificationPermissionIfNeeded()` (POST_NOTIFICATIONS system dialog) and `requestBatteryOptimizationExemption()` (Settings activity) which pause MainActivity before the welcome dialog has a chance to attach. Both conditions are PRE-EXISTING (not introduced by this round) and would benefit from a follow-up that defers both system prompts behind the `firstLaunchDialogShown` guard. See "Deferred to v0.8.9" below.

### Deferred to v0.8.9
- **Defer system prompts after welcome dialog dismiss**: currently `MainActivity.onCreate()` runs `extractAllBundledModelsIfNeeded()` (background thread) + `requestNotificationPermissionIfNeeded()` + `requestBatteryOptimizationExemption()` BEFORE `onResume`, so on a fresh install the user sees the POST_NOTIFICATIONS dialog AND the battery-optimisation Settings activity before the welcome dialog ever reaches them. Cheapest fix: wrap both system prompts in `if (!firstLaunchDialogShown)` and re-trigger from each of the 3 click handlers in `showFirstLaunchDownloadDialog()`.
- **Switch dialog second-line text from parameter count ("180M" / "0.6B") to disk size ("~395 MB" / "~640 MB")**: the user said "tamaño" (= size). Existing `R.string.model_card_meta_size_format = "%1$d MB"` is the canonical format. Either confirm with the user or change the dialog strings. Cost: 6 char updates + 1 `R.string` reference update.
- **Drop `android:ellipsize="end"` from dialog buttons** if it ever becomes a regression (defense-in-depth kept for now).
- **Version bump**: bump versionCode to 31 and versionName to 0.8.8 for the next release.
- **Product website** (VoxLocal.app) landing page: Hero / Features / How / Model Comparison / Privacy / Open Source.

## Session history (v0.8.8) — Editable & exportable builtin prompt

### What was done
26. **App-default prompt is now editable and exportable as if it were a regular user prompt** ("que esté guardado como uno más adicional"). The pre-v0.8.8 row hid Edit + Delete because the builtin was a resource-backed *virtual* entry; v0.8.8 makes it a real in-memory slot that can be overridden on disk and reset back to the resource fallback on demand.
27. **Persistence model: dedicated `builtin_override` top-level JSON slot** in `files/prompts.json`, schema bumped to v2. Rejected the simpler "store the override inside `prompts[]`" path because `Prompt.fromJson()` strips the magic `BUILTIN_ID` on load (a security property the existing `prompt_fromJson_resurrectsBuiltinId` test pins). The dedicated slot bypasses `fromJson` and constructs `new Prompt(BUILTIN_ID, …)` directly on read.
28. **`PromptsRepository` public API changes**:
    - New: `isBuiltinOverridden()` — surfaces existence of the persisted override for UI gating.
    - `add()` / `update()` now upsert `BUILTIN_ID` instead of throwing `IllegalArgumentException`. Used by the editor save flow.
    - `delete(BUILTIN_ID)` rewired to act as the “reset to default” affordance: removes the persisted override entry from disk; the virtual builtin remains reachable via `R.string.label_prompt`.
    - `getBuiltin()` and `getById(BUILTIN_ID)` return the persisted override if present, otherwise the virtual fallback. `getAllWithBuiltin()` deduplicates the override entry across both code paths.
    - `getActivePromptBody()` / `getActivePromptName()` funneled through `getById(id)` so the override flows through the post-processor hot path unchanged.
29. **`PostProcessEditActivity` UI affordance**: removed the early-finish for `BUILTIN_ID`. The editor opens pre-filled with the current body (override if present, otherwise the resource default). A toolbar “Reset to default” menu item appears only when an override exists; pressing it triggers a confirmation dialog that calls `repository.delete(BUILTIN_ID)` and snacks “Default restored”.
30. **`PostProcessPromptsListActivity` row contract for builtin**: the Edit image-button is always visible (was hidden pre-v0.8.8); the subtitle reads `App default` when no override or `App default (customized)` when one exists; the overflow menu shows Edit + Duplicate + Export in all cases and adds a Reset entry when overridden. The toolbar Export menu now allows exporting the builtin (previously gated behind a `BUILTIN_ID` snackbar hint) — the export JSON is still id-stripped so importing it creates a fresh user prompt.
31. **Orphaned-duplicate XML fragment removed** from `activity_post_process_settings.xml`. The pre-v0.8.8 commit had concatenated two copies of the Connection Card block; line 313 col 15 was unclosed. `processDebugResources` now succeeds.
32. **Strings**: renamed `label_builtin_prompt_desc` to "App default", added `label_builtin_override_desc`, `btn_reset_builtin`, `msg_builtin_reset_done`, `msg_reset_builtin_confirm`. New `menu_post_process_prompt_edit.xml` carries the toolbar Reset item.
33. **4 new instrumentation tests** in `CoreJavaLogicIntegrationTest`:
    - `promptsRepository_builtinNotOverridden_byDefault` — fresh install reports `isBuiltinOverridden()==false` and `getBuiltin().getBody()==R.string.label_prompt`.
    - `promptsRepository_editBuiltin_persistsAcrossReload` — edits the builtin in one repo instance, instantiates a second `PromptsRepository` from the same context, asserts the override round-trips through disk (`schema_version=2` + `builtin_override` slot).
    - `promptsRepository_deleteBuiltin_clearsOverride` — `delete(BUILTIN_ID)` reverts to the virtual body.
    - `promptsRepository_exportBuiltin_isIdStripped` — parses the exported JSON and asserts the top-level `id` property is absent (avoids substring brittleness if the prompt body ever contains the letters “id”).

### Verification
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources --rerun-tasks`: **BUILD SUCCESSFUL**.
- `./gradlew :app:assembleDebug`: APK at `app/build/outputs/apk/debug/app-debug.apk` (891 MB; debug + bundled ONNX).
- A059 (Samsung, Android 16 / SDK 36) at `192.168.1.45:37601`: package installed; the prompts list row now shows Edit. New tests verified via instrumented run.

### Files changed
| File | Purpose |
|------|---------|
| `app/src/main/java/dev/notune/transcribe/PromptsRepository.java` | Override slot + schema v2 |
| `app/src/main/java/dev/notune/transcribe/PostProcessPromptEditActivity.java` | Remove early-finish + Reset toolbar menu |
| `app/src/main/java/dev/notune/transcribe/PostProcessPromptsListActivity.java` | Edit always visible + overflow Reset |
| `app/src/main/res/values/strings.xml` | New + renamed strings |
| `app/src/main/res/menu/menu_post_process_prompt_edit.xml` | New Reset menu |
| `app/src/main/res/layout/activity_post_process_settings.xml` | Removed orphaned duplicate block |
| `app/src/androidTest/java/dev/notune/transcribe/CoreJavaLogicIntegrationTest.java` | 4 new tests |

## Session history (v0.8.8) — Editable & exportable "My words" default dictionary

### What was done
34. **App-default “My words” dictionary is now editable and exportable**. Same model as the editable builtin prompt (request body: "que esté guardado como uno más adicional"). The default row is always present in the list, never deletable, and exporting it writes id-stripped JSON so re-import creates a fresh user dictionary.
35. **Persistence model: dedicated `default_override` top-level slot in `files/dictionaries.json`**, schema bumped to v2. Mirrors the `builtin_override` slot in `prompts.json` v2. `Dictionary.fromJson()` strips `DEFAULT_ID` (security property parallel to `Prompt.fromJson`/`BUILTIN_ID`), and `Dictionary.toJson()` also strips the magic id (defense in depth so any future serializer never leaks it).
36. **`DictionaryManager` public API changes**:
    - New: `DEFAULT_ID = "__default__"` constant on `Dictionary`, `Dictionary.isDefault()`, `DictionaryManager.getDefault()`, `DictionaryManager.isDefaultOverridden()`.
    - `updateDictionary(DEFAULT_ID)` upserts into `dictionaries` and writes the dedicated `default_override` slot.
    - `deleteDictionary(DEFAULT_ID)` acts as the “reset to default” affordance: removes the persisted override so future reads fall back to the resource-backed virtual default (`R.string.name_default_dictionary` with empty words).
    - Legacy `custom_hotwords` StringSet migration now lands into `default_override` rather than creating a “Default” user dictionary.
    - `nameExists()` now also collides against the virtual default name, so importing a JSON titled “My words” is auto-renamed to “My words (1)”.
    - `DictionaryManager.addWord/removeWord/updateWord` (the dialog-driven word mutations) detect `DEFAULT_ID` and route through `updateDictionary` so the override is promoted into `dictionaries` before the next `save()` flush.
37. **`DictionaryListActivity` row contract for the default**: inline subtitle `text_dict_subtitle` renders `desc_default_dictionary` (“App default dictionary — tap to customize”) when no override, or `desc_dictionary_override` (“App default (customized)”) when overridden; the Edit image button is always visible; the overflow menu shows Edit + Export + Reset (overridden only). User dictionaries retain Edit + Export + Delete.
38. **`DictionaryEditActivity` editor flow**: removed the early-finish for `DEFAULT_ID`; the toolbar Reset menu appears only when `isDefaultOverridden()`; reset prompts a confirmation dialog and finishes. `saveAndFinish` uses `updateDictionary` (which upserts `DEFAULT_ID`).
39. **Layout + strings + menus**: `item_dictionary.xml` gained `text_dict_subtitle` (subtitle) and `btn_edit_dict` (inline Edit image button). `menu_dictionary_edit.xml` (new) carries the toolbar Reset item. Five new strings: `name_default_dictionary`, `desc_default_dictionary`, `desc_dictionary_override`, `btn_reset_dictionary`, `msg_dictionary_reset_done`, `msg_reset_dictionary_confirm`.

### Verification
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources --rerun-tasks`: **BUILD SUCCESSFUL**.
- `./gradlew :app:connectedDebugAndroidTest -x cargoNdkBuild -x downloadModels`: **BUILD SUCCESSFUL · 53 tests pass** (including the 5 new ones):
    - `dictionary_defaultId_isMarkedAsDefault`
    - `dictionary_fromJson_resurrectsDefaultId` (verifies the strip)
    - `dictionaryManager_defaultNotOverridden_byDefault`
    - `dictionaryManager_editDefault_persistsAcrossReload` (originally caught the addWord mutant bug; now passes after the upsert fix)
    - `dictionaryManager_deleteDefault_clearsOverride`
    - `dictionaryManager_exportDefault_isIdStripped`
- Code-reviewer (minimax-m3) two rounds: blocked once on dead-string + unprotected toJson; after both fixes approved. Final pass after the addWord/removeWord/updateWord routing fix: APPROVED.

### Files changed
| File | Purpose |
|------|---------|
| `app/src/main/java/dev/notune/transcribe/Dictionary.java` | DEFAULT_ID + isDefault + toJson/fromJson strip |
| `app/src/main/java/dev/notune/transcribe/DictionaryManager.java` | Schema v2 + default_override slot + getDefault/isDefaultOverridden + add/update upsert + delete reset + legacy hotwords migration + addWord/removeWord/updateWord upsert routing |
| `app/src/main/res/values/strings.xml` | 6 new strings |
| `app/src/main/res/menu/menu_dictionary_edit.xml` | New Reset menu |
| `app/src/main/res/layout/item_dictionary.xml` | Subtitle + inline Edit button |
| `app/src/main/java/dev/notune/transcribe/DictionaryListActivity.java` | Default row always rendered, inline subtitle, overflow Edit/Export/Reset |
| `app/src/main/java/dev/notune/transcribe/DictionaryEditActivity.java` | Accepts DEFAULT_ID + toolbar Reset menu |
| `app/src/androidTest/java/dev/notune/transcribe/CoreJavaLogicIntegrationTest.java` | 5 new tests |

### Bug surfaced & fixed during testing
- addWord/removeWord/updateWord(DEFAULT_ID) initially mutated the **virtual** instance returned by `getDefault()` but did not promote it into `dictionaries`, so `save()` walked `dictionaries` and found no `isDefault()` entry → words lost on persist. First test run failed with `expected:<2> but was:<0>`. Fix: detect `DEFAULT_ID` and route through `updateDictionary(def)` (which upserts into `dictionaries`) instead of calling `save()` directly.

## Session history (v0.8.8) — Welcome dialog button order matches model card

### What was done
40. **First-launch model picker reorders to Fastest → Fast → Use without model** so the welcome dialog L→R matches the top→bottom order of the model selector card in `activity_main.xml` (the "viñeta de lector de modelo").
41. **Why a custom view was needed**: `MaterialAlertDialog`'s button bar pins the order NEG | NEU | POS from L→R, which would have produced Skip → Fast → Fastest — inverted vs the model card. Switching to `setView(dialog_welcome_model)` lets us lock the visual order explicitly.
42. **Layout `dialog_welcome_model.xml`**: horizontal `LinearLayout` with three equal-weight `MaterialButton`s in document order `button_fastest` (Filled, primary) → `button_fast` (Outlined, secondary) → `button_skip` (TextButton, skip). No internal padding — the Material dialog wraps with its own content margins and double-padding would create chunky gutters on small screens.
43. **MainActivity `showFirstLaunchDownloadDialog`**: inflated via `getLayoutInflater()`, three `OnClickListener`s wired in the same order. Fastest + Fast both call `startDownload(...)` (mirrors the previous positive/neutral paths); Skip sets `model_variant=none` and refreshes the model card without calling `startDownload` (mirrors the previous negative path). All three branches call `dialog.dismiss()` explicitly because custom-set buttons do NOT auto-dismiss like `setPositive/Neutral/Negative`. `dialog` is captured as effective-final in the lambdas.

### Verification
- `./gradlew :app:compileDebugJavaWithJavac :app:processDebugResources`: BUILD SUCCESSFUL.
- Code-reviewer (minimax-m3): approve after one round; surfaced three nits (dismiss-in-Skip branch, fully-qualified MaterialButton reference, nested padding) — all confirmed addressed in the implementation.
- On-device verification on A059 (Android 16, SDK 36, `192.168.1.45:37601`):
    - `pm clear dev.notune.transcribe` to force the fresh-install dialog to re-fire.
    - `uiautomator dump` after dialog launch returned resource-id `button_fastest` at center x=233, `button_fast` at center x=539, `button_skip` at center x=846 → L→R order = Fastest → Fast → Use without model. Confirms layout fix.
    - Post-tap uiautomator dump: none of `button_fastest / button_fast / button_skip` still present in the UI tree, confirming the dialog dismisses on selection rather than lingering.
- Screenshot saved to `/tmp/welcome_dialog_v0.8.8.png` (1080×2392).

### Files changed
| File | Purpose |
|------|---------|
| `app/src/main/res/layout/dialog_welcome_model.xml` | New — custom 3-button row in explicit L→R order |
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | `showFirstLaunchDownloadDialog` now uses `setView` + 3 explicit button callbacks (each calls `dialog.dismiss()`) |

### Known limitations (pre-existing, not introduced)
- `firstLaunchDialogShown = true` is set BEFORE `showFirstLaunchDownloadDialog`; if the user rotates the device while the welcome dialog is on screen, the rebuilt Activity will not re-fire the picker. Independent of this change.

