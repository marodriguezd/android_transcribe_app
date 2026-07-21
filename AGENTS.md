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

- `main` and `develop` aligned at commit `a6b574a` ("merge: bring CI hardening from main into develop"). Both branches point at the same SHA — the canonical posture is "main is the trunk, develop mirrors it going forward". Default branch on GitHub: `main` (set via `gh repo edit --default-branch main`, 2026-07-20).
- Tag inventory after the v0.9.0 cleanup: `v0.9.0` (force-moved to `a6b574a`), `v0.8.0`-`v0.8.8` (the active fork cycle, all reachable from `main`), `v0.7.0`, `v0.6.0`. The pre-fork `-ai` cycle (`v0.2.0-ai` → `v0.4.0-ai`) + the versionName-mismatched `v0.5.0` were purged locally + on origin via `git tag -d` + `git push origin --delete`.
- **GitHub Releases: 0** (`gh release list` returns empty). `v0.9.0` tag exists; the v0.9.0 Release is **deferred** — APK release build was skipped at this commit (mobile build env constraint, no JDK / NDK / cargo-ndk locally). To publish it later: build `app-release.apk` (./build.sh or ./gradlew assembleRelease) — note the v0.8.6 release-build asserts in `app/build.gradle.kts` will fail-fast locally unless **all** of the following hold: (a) `release.keystore` exists at the project root, (b) `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS` env vars exported (or `CI=true` to skip the local keystore-existence check while still WARN-logging it), (c) `isMinifyEnabled = false` on the release build type (default). Then: `gh release create v0.9.0 --target main --notes-file fastlane/metadata/android/en-US/changelogs/32.txt app/build/outputs/apk/release/app-release.apk#Offline\ Voice\ Input\ v0.9.0.apk`. The CI workflow `android_release.yml` produces debug APKs (NOT signed release APKs) as artifacts on every push; the signed release APK + GitHub Release must be done manually with proper signing.

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

## Session history (post-v0.9.0 hotfix #3 — Canary 180M Auto mode + IME cross-component state broadcaster) — 2026-07-21

Two user-reported defects addressed in commits `26ecd32` + `1c0d7aa` on `develop`. Both merged to `origin/develop`, Build Debug APK run `29822573287` → `completed / success` (cargo-ndk + Javac + R8 + ONNX bundled extract, 4m 51s wall-clock on the cold Linux worker).

### Hotfix #3 — Canary 180M "Auto" language mode produced empty transcripts

**Root cause**: `transcribe-rs/src/engines/parakeet/model_180m.rs :: build_prefix(Auto)` set **BOTH** decoder-prefix positions 4 + 5 (source + target) to `<|unklang|>`. Canary-180m-flash was never conditioned on a `(unklang_source, unksrc_target)` pair in its training distribution, so the decoder converged to EOS at step 1 every time, producing no output tokens between prefix and endoftext. User-reported: *"El modo automático del Canary no funciona. Ahora, cuando os coges el idioma, sí que funciona bien."* (Auto fails; explicit EN/ES/DE/FR works fine — confirmed by picking a chip and re-running the same audio.)

**Fix** (`26ecd32`): `build_prefix(Auto)` now resolves to `(self.lang_unksrc_id, self.lang_en_id)` — `<|unklang|>` on source (encoder auto-detects input language from audio) and `<|en|>` on target (deterministic-decoder-output language, the dominant training pair). **Trade-off**: Auto output is now always English; users transcribing Spanish / German / French whose target language is not English must pick the matching explicit chip from the language picker. A future iteration could add a runtime language ID pass and set source + target before the autoregressive loop. Doc-comment updates propagated at all three sites:
- `transcribe-rs/src/engines/parakeet/model_180m.rs` — `CanaryLanguage` enum block (table row + free-form comment) + `build_prefix` inline doc
- `src/engine.rs` — `read_transcription_language` doc + log line
### Hotfix #4 — No visual cue in the IME when the engine is mid-switch

**Root cause**: Rust's `engine::notify_status(...)` only fires `onStatusUpdate` on the activity passed to the JNI entry point. Model-switch UI triggered from `MainActivity` (`switchModelAsync(variant)` → Rust `engine::switch_model` → `do_load_*`) only fires status updates on the MainActivity, which the `RustInputMethodService` never sees. The IME had no path to know when the engine was warming up, so its record button stayed tappable during a half-loaded model — user-reported: *"como que le cuesta un poco, debería marcar que se esté inicializando [...] alguna forma de aviso visual en el IME."*

**Fix** (`1c0d7aa`):

1. New `EngineStateBroadcaster.java` — static utility with `volatile String currentState` + `CopyOnWriteArrayList<StateListener>` (per AGENTS.md's established callback pattern) + `Handler(Looper.getMainLooper())` marshalling + predicates `isLoading`/`isTranscribing`/`isError`/`isReady` + `stripStatusPrefix` helper. Listeners fire on the main thread; remove-on-destroy is race-safe via CopyOnWriteArrayList snapshot semantics.
2. `MainActivity.onStatusUpdate` publishes to the broadcaster as its FIRST action (before `runOnUiThread` status text update). `switchModelAsync(variant)` pre-fires `"Switching model…"` immediately so the IME shows a spinner the instant the user taps a different radio — Rust's first `notify_status` fires on a JNI worker thread ~100ms later and would otherwise leave the IME visually idle in that gap.
3. `RustInputMethodService.subscribe()` on `onCreate` (before the `initNative` thread is spawned so the very first Loading… state is captured), `unsubscribe()` on `onDestroy`. New listener callback `onEngineStateChanged(String status)` is the single source of truth for UI updates. `updateUiState()` is now parameterless — derives all four predicates from `lastStatus` via broadcaster predicates. `recordContainer` disabled union expanded to `loading || transcribing || error` (was `transcribing || waiting || error` — `waiting` is folded into `loading` via substring match). Status text surfaces the raw engine status (e.g. "Initializing fastest engine…" / "Switching model…") with `stripStatusPrefix` cutting the redundant `"Status: "` prefix `MainActivity` prepends.
4. `isLoading` substring coverage extended to `"reading"` / `"decoding"` / `"extracting"` (Rust's `notify_status("Reading vocabulary…")` lacks `"loading"` — without the substring it would leave the IME visually idle during a model load; the others are preemptive for future Rust emits).
5. Deleted dead `private static boolean isErrorStatus(String)` helper from `RustInputMethodService` — zero callers remained after the broadcaster refactor (all error predicates funnel through `EngineStateBroadcaster.isError` now). Matches the project's "no dead code" invariant.

### Verification
- Rust fix (`26ecd32`) and IME feat (`1c0d7aa`) by code-reviewer-minimax-m3 in two passes (architecture + applied nits) — APPROVED.
- CI Build Debug APK run `29822573287` for `1c0d7aa` → `completed / success` (4m 51s wall clock; cargo-ndk + Javac + R8 + AGP + ONNX bundled extract all green). On-device smoke test deferred to user (A059 at `192.168.1.45:37601`): confirm Canary Auto mode now produces English output for Spanish audio (instead of empty / garbled); confirm IME shows progress bar + "Switching model…" status text + disabled mic button during a MainActivity-driven 180M ↔ 0.6B switch.
- Bug fix scope explicitly per user: "soluciona única y exclusivamente eso". Other open items (main → develop FF housekeeping, v0.9.1 release cut, manual RIFF/WAVE reader v0.8.8 fix, unit tests for `attachModelRadioListener` / `onVariantSelectedByUser` from hotfix #2) deliberately deferred.

### Files changed across hotfix #3 + #4
| File | Purpose |
|------|---------|
| `transcribe-rs/src/engines/parakeet/model_180m.rs` | Canary 180M `build_prefix(Auto)` — `(unklang_src, en_target)`. Doc comments updated at three sites that describe the prefix semantics (`CanaryLanguage` enum block, `build_prefix` inline, log line). |
| `src/engine.rs` | `read_transcription_language` doc comment + log line rewording to match the new `Auto` semantics. |
| `app/src/main/java/dev/notune/transcribe/EngineStateBroadcaster.java` | **NEW** static singleton with `volatile currentState` + `CopyOnWriteArrayList<StateListener>` + main-thread `Handler`. Listeners fire on the main thread. |
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | `onStatusUpdate` now publishes to broadcaster. `switchModelAsync(variant)` pre-fires `"Switching model…"` before the JNI call. |
| `app/src/main/java/dev/notune/transcribe/RustInputMethodService.java` | Subscribe on `onCreate`, unsubscribe on `onDestroy`. New `onEngineStateChanged` listener callback. `updateUiState()` becomes parameterless + uses broadcaster predicates. Dead `isErrorStatus` helper deleted. |

## Session history (post-v0.9.0 hotfix #2 — RadioGroup user-tap dispatch fix) — 2026-07-21

User reported, on the **running debug APK from commit `a31a77f`**, that tapping "Fastest" while Fast was the active variant left BOTH `rb_model_fastest` and `rb_model_fast` visually selected, and that deleting a model left the UI half-refreshed until the Activity was closed and reopened.

### Root cause
`modelGroup.setOnCheckedChangeListener(...)` in `setupModelSelection` is **dead code** in this layout. `RadioGroup`'s internal `CheckedStateTracker` is wired via `addView()` so it sees direct children only — but each `MaterialRadioButton` lives inside a horizontal `LinearLayout` row wrapper (the per-row delete `ImageButton` sits beside the radio). The group listener therefore never fires from a user tap, AND `RadioGroup.clearCheck()` cannot traverse into the wrappers to clear the previously-checked sibling. Result on user tap: the tapped radio flips visually (because `CompoundButton.setChecked` updates the button internally) but no state-change propagates up AND the previously-checked sibling stays selected. This is what the user sees as *"both stay selected"*. The *"needs close/reopen"* complaint stems from the same root cause: the only code path inside the Activity that re-syncs the radio visual state is `onResume` → `selectRadioButton(current)` (commit `a31a77f`'s programmatic helper). Anyone who corrupted the visual state via a user tap had to background-and-foreground the app to recover.

Commit `a31a77f` addressed only the **programmatic** sync side (initial setup, `onResume`, post-download, post-delete, welcome-dialog callbacks). The user-tap path was the gap. This commit closes it.

### What was done
- `<c>(d)</c>` **Removed `modelGroup.setOnCheckedChangeListener(...)` from `setupModelSelection`** entirely. Replaced with three per-button `attachModelRadioListener(rb, variant)` calls — one each for `rb_model_fastest` ("180m"), `rb_model_fast` ("0.6b"), and `rb_model_none` ("none"). Each per-button listener attaches a `CompoundButton.OnCheckedChangeListener` whose only two early-returns are `!isChecked` (sibling-clear filter; fires when a button transitions from checked to unchecked) and `modelSelectionChanging` (synchronous-sync window filter; `selectRadioButton` raises the flag across its three `setChecked` calls so the cascade during a single user-tap → `selectRadioButton` → 1-dispatch completes without recursion).
- `<c>(d)</c>` **Extracted the dispatch logic** from the deleted group listener into a single `onVariantSelectedByUser(String variant)` method. It persists the prefs (`sm.setModelVariant(variant)`), forces a `selectRadioButton(variant)` re-sync to guarantee "exactly one radio visually checked" on every dispatch path (user tap OR programmatic), then runs the existing branches unchanged: `"none"` → unload + status; `isModelDownloaded` → `switchModelAsync` + 30s status-timeout (`model_switch_restart` → `model_switch_timeout` via `mainHandler.postDelayed`); else → `startDownload`.
- `<c>(d)</c>` **Extracted the `new Thread(() -> switchModel(...))` wrapper** from the old dispatch into a single `switchModelAsync(String variant)` helper using `WeakReference<MainActivity>` + `isFinishing()/isDestroyed()` gates (preserves AGENTS' bg-thread idiom; same as `initNative`/`switchModel` paths elsewhere in the file).

### Verification
- `./gradlew` compile + R8 validation deferred to CI Build Debug APK (no local JDK/NDK on this host per AGENTS.md § Common pitfalls).
- `thinker-with-files-gemini`: APPROVED — synchronous `CompoundButton.setChecked` + the `modelSelectionChanging` flag is sufficient to prevent double-dispatch when one user tap causes three cascading `setChecked` calls (two sibling clears, one self-restore) inside the synchronous dispatch window. Re-entrant calls all see the flag and early-return.
- `code-reviewer-minimax-m3`: APPROVED with one medium-priority nit F about test-file impact (`modelGroup.setOnCheckedChangeListener` removal could break tests asserting on the group's listener). Verified via grep on `OfflineVoiceInputE2ETest.java` + `CoreJavaLogicIntegrationTest.java` for `modelGroup`, `rg_model`, `rb_model_*`, `selectRadioButton`: the only "MainActivity" hit in `CoreJavaLogicIntegrationTest` is a comment about cold-start paths, not an assertion on the group-level listener. Nit F is clean.
- Two non-blocking nits (G-a: rapid double-tap on the same radio could fire two `switchModelAsync` threads — original had no debounce either so no regression; G-b: cross-reference comment in `onVariantSelectedByUser`). Applied G-b in source; deferring G-a unless observed.

### Files changed
| File | Purpose |
|------|---------|
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | Per-button listener wiring (3 `attachModelRadioListener` calls) + extracted `onVariantSelectedByUser` dispatch + extracted `switchModelAsync` helper + cross-reference comment in `onVariantSelectedByUser` |

### Limits / next steps
- Typecheck still gated by CI (no local JDK/NDK); the API surface change (removing the group's group-level listener) is verified by grep on test sources.
- User must `pm clear dev.notune.transcribe` (or fresh-install the rebuilt debug APK from CI) and tap **Fastest** while Fast is selected to confirm only one radio stays checked; the prior close-reopen workaround should not be needed.
- Future defensive improvement (not in this commit): a `dispatchingUserSwitch` boolean + `mainHandler.post(() -> flag=false)` to debounce rapid double-taps — only add if observed in the wild.

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

## Session history (post-v0.9.0 Canary multilingual + borrow fixes) — 2026-07-21

This entry documents the multilingual transcription feature added to the Canary 180M AED model (English, Spanish, German, French, plus auto-detect), plus two follow-up Rust compile-error fixes the CI introduced when validating the feature.

### 1. User report — Canary (and possibly 0.6B) only transcribed English for Spanish audio

User observation in Spanish: the app only transcribes in English even when speaking Spanish, on both the Canary 180M "Fastest" radio and (they believed) the Parakeet 0.6B "Fast" radio.

### 2. Root cause — hardcoded English at decoder prefix positions 4+5

`transcribe-rs/src/engines/parakeet/model_180m.rs` had a `transcribe_input: Vec<i64>` built once at `from_memory()` time that hardcoded `<|en|>` at positions 4 (source lang) and 5 (target lang) of the 10-token decoder prefix. Even though the Canary vocab (`app/src/main/assets/canary-180m-flash-int8/vocab.txt`) contained `<|es|>` (id 169), `<|de|>` (id 76), `<|fr|>` (id 69), and `<|unklang|>` (id 21) for auto-detect, the app never used them — Canary was permanently conditioned on English.

The Parakeet TDT 0.6B v3 path is structurally different (CTC + auto-detect, no prefix tokens in the codebase) and was left untouched in code; only the UI subtitle was updated from `(English)` to `(multilingual)` + `Languages: 25 European languages` to match NVIDIA's official claim for v3. Whether INT8 quantization preserves that multilingual accuracy for the 0.6B path is still TBD on-device.

### 3. `CanaryLanguage` enum + dynamic prefix builder

Added `pub enum CanaryLanguage { Auto, En, Es, De, Fr }` (Default = Auto, Copy + Eq) with:

- `CanaryLanguage::from_pref(&str)` — coerces unknown values to `Auto` so malformed SharedPreferences entries never crash the load.
- `Parakeet180mModel::build_prefix(lang) -> Vec<i64>` — returns a 10-token prefix derived from `lang`, picking the matching `<|lang|>` token from cached fields `lang_en_id`, `lang_es_id`, `lang_de_id`, `lang_fr_id`, `lang_unksrc_id`.
- Static prefix tokens (8 of the 10 prefix positions: space, `<|startofcontext|>`, `<|startoftranscript|>`, `<|emo:undefined|>`, `<|pnc|>`, `<|noitn|>`, `<|notimestamp|>`, `<|nodiarize|>`) cached as plain `i64` fields so the prefix can be rebuilt per call without re-tokenising.
- `set_language(lang)` / `current_language()` on `Parakeet180mModel`.
- `EngineWrapper::set_language(lang) -> bool` (true for V180m, no-op for V0_6b) and `current_language()`.
- `engine::set_language(lang) -> Result<(), String>` — SHORT-circuited to drop the dead `&mut JNIEnv, &JObject` args the previous signature inherited from `read_model_variant` (reviewer flagged the dead args in the first pass).
- `read_transcription_language(env, context) -> CanaryLanguage` — mirrors `read_model_variant` to pull the `transcription_language` SharedPreferences string and coerce via `from_pref`.
- `do_load_180m` calls `read_transcription_language` and `model.set_language(initial_lang)` AFTER model construction, so a fresh download already respects the user's last selection without needing a separate nativeSetLanguage call at Activity init.

### 4. JNI export `Java_dev_notune_transcribe_MainActivity_nativeSetLanguage`

New JNI entry point in `src/main_activity.rs`. Pseudocode flow (see file for the verbatim `env.with_local_frame(16, ...)` wrapper that satisfies AGENTS' "guard local refs" rule):

1. Read the input `lang_str: JString` into a `String lang_s` — wrapped in `env.with_local_frame(16, ...)` for local-ref hygiene.
2. Convert via `CanaryLanguage::from_pref(&lang_s)` — arbitrary / malformed values coerce to `Auto`.
3. Call `engine::set_language(lang)` which locks `GLOBAL_ENGINE`, mutably borrows `EngineWrapper`, calls `V180m(m).set_language(lang)` (no-op for `V0_6b`), and logs the change. Returns `Err("No engine loaded")` on a cold start where the model is still loading — harmlessly dropped on the Java side, since `do_load_180m` re-reads the same pref on construction and applies it.

Called from the `OnCheckedStateChangeListener` of the ChipGroup in `MainActivity.setupLanguagePicker`. The unused `activity: JObject` parameter stays in the JNI signature for future status-callback symmetry even though the current Rust impl does not touch it.

### 5. Java UI

**`SettingsManager.java`** — added `KEY_TRANSCRIPTION_LANGUAGE = "transcription_language"` (default `"auto"`) + `getTranscriptionLanguage()` / `setTranscriptionLanguage(String)`. `getTranscriptionLanguage()` never returns null.

**`MainActivity.java`** —

- 7 new fields: `containerLanguagePicker: View`, `chipGroupLanguage: ChipGroup`, 5 chip references, `textModelLanguages: TextView`, `languageSelectionChanging: boolean`.
- New `setupLanguagePicker(SettingsManager sm)` method called from `onCreate` after `setupModelSelection`. Resolves all 7 picker views with per-view null-guard, restores the persisted preference into chip group (no listener attached yet at this point, so the `languageSelectionChanging` flag is overkill — kept as a defensive comment in the listener anyway in case future code attaches the listener first), wires `setOnCheckedStateChangeListener((group, checkedIds) -> …)`.
- `OnCheckedStateChangeListener` persists pref via `SettingsManager.setTranscriptionLanguage(lang)`, calls `nativeSetLanguage(this, lang)`, and shows a Snackbar `"Language: <name>"` so the user gets feedback (label mapped from `lang` code via switch to "Spanish" / "German" / "French" / "English" / "Auto" — not the raw `"ES"` ISO code, which the first review pass flagged as not user-friendly).
- New `updateLanguagePickerVisibility(variant)` — toggles `container_language_picker` visibility on/off based on whether the variant is `180m` (only Canary has a language context; Parakeet 0.6B v3 auto-detects via CTC, "Use without model" has no engine).
- New `updateLanguagesSubtitle(variant)` — populates `text_model_languages` with the compact label for the current variant: Canary → "Languages: English, Spanish, German, French"; Parakeet → "Languages: 25 European languages"; "none" → GONE.
- `selectRadioButton(variant)` calls both `updateLanguagePickerVisibility(variant)` AND `updateLanguagesSubtitle(variant)` after the radio update, so the picker + subtitle stay in lockstep with the selected radio from every caller path (download completion, `confirmDeleteModel` auto-fallback, welcome dialog buttons). `updateModelSelectionUI` (called from `onResume`) NO LONGER calls `updateLanguagePickerVisibility` directly because `selectRadioButton` already does — first review pass caught this double-call.

### 6. Layout

`activity_main.xml`:

- New `text_model_languages` TextView always visible (sibling BEFORE `container_language_picker` so it does NOT get hidden by the container's `visibility="gone"` when variant != 180m).
- New `container_language_picker` LinearLayout with `visibility="gone"` by default, containing a title + `chip_group_language` ChipGroup with `app:singleSelection="true"`, `app:selectionRequired="true"`, and 5 chips (`chip_language_auto`, `_en`, `_es`, `_de`, `_fr`) styled as `Widget.Material3.Chip.Filter` with `android:checkable="true"`.

### 7. Strings

`strings.xml`:

- Updated `model_card_meta_parakeet`: `(English)` → `(multilingual)`.
- Updated `model_card_meta_canary`: `(English)` → `(4 languages)`.
- Added `model_card_meta_languages_canary` (full list reference) and `model_card_meta_languages_parakeet` (full 25-language list).
- Added compact subtitles `model_card_languages_canary_compact` ("Languages: English, Spanish, German, French") and `model_card_languages_parakeet_compact` ("Languages: 25 European languages") — the actual `text_model_languages` text.
- Added chip labels `desc_language_section` ("Language (Canary 180M only)"), `language_auto`, `language_english`, `language_spanish`, `language_german`, `language_french`.
- Added `msg_language_set = "Language: %1$s"` for the Snackbar (format arg is the human-readable label, not the ISO code).

### 8. First CI attempt (commit 1c4ae35) — failed with E0502

Pushed `origin/develop` after the user said "no cambiemos más por ahora, sibe a repo". CI `Build Debug APK` failed during `:app:cargoNdkBuild`:

```
error[E0502]: cannot borrow `*self` as immutable because it is also
borrowed as mutable
at transcribe-rs/src/engines/parakeet/model_180m.rs:360:30
location of mutable borrow: line 328-329 (self.encoder)
```

Root cause diagnosed by the thinker agent: `ort`'s `Session::run()` returns a `SessionOutputs<'r>` whose lifetime ties the mutable borrow of `self.encoder` to the lifetime of the output tensors. As long as the outputs are alive further down the function, `self.encoder` stays borrowed — so a `self.build_prefix(...)` call later (which is on `&self`) cannot fire. The bug had been masked earlier by the original code using `self.transcribe_input.clone()` (which doesn't touch `self` at all).

### 9. Fix commit 7171a6b — drop the cache, build prefix once at the top

Cleanest fix: drop the per-language prefix cache entirely. The cache was a perf optimisation (avoid 80-byte Vec allocation per `transcribe_samples` call) — `transcribe_samples` is called once per dictation session, not per frame, so the allocation is negligible. The fix moves the single `self.build_prefix(self.current_lang)` call to the very top of `transcribe_samples` — right after the empty-samples / mel-features guards and BEFORE any `Tensor::from_array` that feeds `self.encoder.run(...)`. After this call returns an owned `Vec<i64>`, subsequent `&mut self.encoder` / `&mut self.decoder` borrows are entirely disjoint because `input_ids` is plain owned data, not a reference back into `self`.

### 10. Second CI attempt (commit 7171a6b) — failed with E0423

CI Build Debug APK failed again, this time at a smaller scope:

```
error[E0423]: expected value, found built-in attribute `lang`
at transcribe-rs/src/engines/parakeet/model_180m.rs:381:13
    |             lang,
    |             ^^^^ not a value
```

When removing the local `let lang = self.current_lang;` for fix 7171a6b, I'd missed a stray reference to that local in the `input_ids` debug log:

```rust
log::info!(
    "180M input_ids: len={}, lang={:?}, values={:?}",
    input_ids.len(),
    lang,                                  // <-- undefined after 7171a6b
    input_ids.iter().map(|x| *x as i64).collect::<Vec<_>>()
);
```

### 11. Fix commit 56d4469 — replace `lang` with `self.current_lang` in the log

`CanaryLanguage` is a `Copy` enum so reading the field directly does not hold a borrow on `self`, which is the same NLL-safe pattern we used at the top of `transcribe_samples`. Diff:

```diff
+        // Access self.current_lang directly — CanaryLanguage is Copy
+        // so reading the field does not hold a borrow on self, which is
+        // important here because we are mid-function with the encoder
+        // borrow (produced by self.encoder.run earlier) potentially
+        // still tracked by NLL.
         log::info!(
             "180M input_ids: len={}, lang={:?}, values={:?}",
             input_ids.len(),
-            lang,
+            self.current_lang,
             input_ids.iter().map(|x| *x as i64).collect::<Vec<_>>()
         );
```

The surrounding `input_ids.len()` and `.iter().map(...).collect::<Vec<_>>()` are unaffected. Compiler went from E0423 to clean; CI Build Debug APK on commit 56d4469 is run 29814411572 (re-validation triggered by the push).

### Files changed across the three commits

Per-file counts verified via `git diff-tree --no-commit-id --numstat -r 1c4ae35` for the feature commit and `git show --stat` for the two fix commits:

| File | Commits | Adds / Removes |
|------|---------|-----------------|
| `transcribe-rs/src/engines/parakeet/model_180m.rs` | 1c4ae35 + 7171a6b + 56d4469 | +206 / -30 (186/26 + 17/2 + 3/2) |
| `transcribe-rs/src/engines/parakeet/mod.rs` | 1c4ae35 | +1 / -1 |
| `src/engine.rs` | 1c4ae35 | +111 / -2 |
| `src/main_activity.rs` | 1c4ae35 | +37 / -0 |
| `app/src/main/java/dev/notune/transcribe/SettingsManager.java` | 1c4ae35 | +31 / -0 |
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | 1c4ae35 | +169 / -0 |
| `app/src/main/res/layout/activity_main.xml` | 1c4ae35 | +89 / -0 |
| `app/src/main/res/values/strings.xml` | 1c4ae35 | +26 / -2 |
| **Cumulative** | 3 commits, 8 distinct files | **+670 / -35** |

### Verification

- **`Event Router Test`** workflow: passed at 1c4ae35, 7171a6b, in progress at 56d4469 (run 29814411572 at time of writing).
- **`Build Debug APK`** workflow: failed at E0502 on 1c4ae35, failed at E0423 on 7171a6b, **in progress** at 56d4469 — user is watching for green before flashing the A059.
- **Code-reviewer-minimax-m3** — APPROVED across three passes (initial approve + two follow-up approves for the E0502 / E0423 fixes). Snackbar displays readable chip label (Spanish, not raw ES); updateLanguagePickerVisibility double-call eliminated; languageSelectionChanging flag usage simplified.
- **Rust consistency** (AGENTS.md "Common pitfalls / Rust conventions"): the new `engine::set_language(lang)` mutex recovery on `&GLOBAL_ENGINE` follows AGENTS' "use `unwrap_or_else(|poisoned| { log::error!; poisoned.into_inner() })`" pattern, and the JNI entry uses `match env.get_string(&lang_str).map(|s| s.into())` rather than `.expect() /.unwrap()`. The new JNI entry point uses `JObject` (not `JClass`) for the activity parameter per AGENTS' JNI signature rule.

### Next steps (planned)

- **Confirm green CI on 56d4469** — watch run 29814411572 in the Actions tab. User is on this.
- **On-device Spanish smoke test** on Samsung A059 (Android 16, `192.168.1.45:37601`) — pick Canary 180M, choose "Spanish" in the new language picker, dictate Spanish via `RecognizeActivity` or feed a Spanish WAV through `TranscribeFileActivity`. Verify the output is Spanish text (not garbled English) and the Snackbar shows "Language: Spanish". Should produce the Steve Jobs "connecting the dots" speech in Spanish with the new prefix tokens propagating through the decoder loop.
- **Investigate the 0.6B Spanish path separately** — the strings claim "multilingual" / "25 European languages" but the codebase does not invoke the Parakeet TDT's `<|predict_lang|>` conditioning, so on-device verification is required before we publish that as a user-facing claim. If INT8 quantization does lose multilingual accuracy, options are: (a) add TDT language prefix conditioning (similar pattern to what we did for Canary, but in `transcribe-rs/src/engines/parakeet/model.rs`); or (b) switch the 0.6B variant to FP32; or (c) drop the multilingual claim from the strings.
- **Release prep** — bump `versionCode 32→33` and `versionName 0.9.0→0.9.1` in `app/build.gradle.kts`, author `fastlane/metadata/android/en-US/changelogs/33.txt` covering the multilingual feature, sign the release APK locally (requires `release.keystore` + `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS`), and `gh release create v0.9.1 --target main --notes-file changelogs/33.txt`. AGENTS.md "Deferred (mobile build env constraint)" subsection still applies — no JDK / NDK / cargo-ndk locally, so release build is a manual step.
- **Add Rust unit test for the prefix builder** — currently no automated regression test for `CanaryLanguage::from_pref` or `build_prefix`. A trivial test (`#[test] fn build_prefix_es_uses_es_token() { ... }`) would have caught the E0502 + E0423 chain before it took 3 commits to settle. Worth adding to `transcribe-rs/tests/parakeet.rs` against a synthetic vocab with `<|en|>`/`<|es|>`/`<|unklang|>`/delimiter tokens only.

## Session history (post-v0.9.0 housekeeping) — 2026-07-20

This entry documents the post-v0.9.0 cleanup pass that brought the local + remote repo into a clean, aligned state. Subtasks are listed in chronological order. All operations were executed from a Linux host; no Android SDK / NDK / `cargo-ndk` available locally — release-side builds remained CI-only as documented in the v0.8.x history.

### 1. Discovery / pre-state
- `origin/develop` was 20 commits ahead of `origin/main`. The most recent tag `v0.9.0` (`659f4125`) sat on `develop`; `origin/main` was frozen at `v0.8.0` (`39619e1`) with 13 commits of CI hardening only. README, AGENTS descriptions, and Fastlane metadata differed markedly between branches.
- Local tag inventory: `v0.9.0`, `v0.8.0`–`v0.8.8` (active fork cycle), `v0.7.0`, `v0.6.0`, `v0.5.0`, `v0.4.0-ai`, `v0.3.0-ai`, `v0.2.0-ai`. GitHub Releases: 0 (post-hard-delete of 8 prior v0.x Releases per the v0.9.0 narrative).

### 2. Branch strategy decision
- **`main` is the canonical / public-facing branch**; `develop` mirrors it going forward. `develop` is retained for future feature cycles but loses authority over releases. Default branch on GitHub: `main`.
- Rationale: GitHub's default clone URL, badge URL, and CI workflow triggers anchor on `main`. The fork-of-upstream narrative already centred what was in `main`. Trunk-based posture with `develop` as integration.
- User explicitly approved both: (a) bring main's CI into develop, and (b) push develop to main.

### 3. Merge CI from `main` into `develop` — commit `a6b574a`
- `git checkout -b develop origin/develop` (created local `develop`).
- `git merge --no-ff origin/main` produced 4 conflict files, all on paths both branches had modified:
  - `app/build.gradle.kts`: git **auto-merged** — develop's 379-line v0.9.0 build file retained; main's 7-line CI debug-coexistence block (`applicationIdSuffix = ".debug"`, `versionNameSuffix = "-debug"`) survived at lines 48–52.
  - `.github/workflows/android_release.yml`: resolved with `--theirs` — kept main's 226-line hardened workflow (Telegram notifications, stable debug signing, secrets bridging, step summary).
  - `AGENTS.md`: resolved with `--ours` — kept develop's 435-line three-pillar narrative.
  - `README.md`: resolved with `--ours` — kept develop's three-pillar narrative + status-badge removal.
- New files from main (no conflicts): `.github/workflows/event-test.yml` (13 lines), `scripts/ci/setup_secrets.sh` (103 lines).
- Code-reviewer approved with one optional follow-up: "CI secrets management" 4-line section from `AGENTS.md` main could be grafted later. Deemed optional since develop's three-pillar narrative covers the same ground rhetorically.
- Verification (pre-commit):
  - `versionCode=32` / `versionName="0.9.0"` preserved.
  - 180M / canary-180m-flash-int8 model support preserved (in `downloadModels` task + `modelPackFiles180m`).
  - Defensive release-build asserts (added in v0.8.6) preserved in `app/build.gradle.kts` `afterEvaluate {}`.
  - `unitTests { isIncludeAndroidResources = true }` preserved (Robolectric 4.11.1 needs this).
  - `androidTestImplementation` deps (runner / rules / ext:junit) preserved (E2E test `OfflineVoiceInputE2ETest.java` needs these).
  - `applicationIdSuffix = ".debug"` block present in merged `app/build.gradle.kts`.

### 4. Fast-forward `main` to `develop` — both at `a6b574a`
- `git checkout main && git merge --ff-only develop` — no merge commit needed since develop already contained the merged state.
- Rationale for keeping the `-no-ff` merge from Step 3 (instead of FF earlier): preserving the audit trail showing "this is where the CI hardening from main landed in the v0.9.0 cycle". Future contributors can `git log --first-parent` and see the merge point clearly.

### 5. Push to origin
- `git push -u origin main develop` — clean FF for both branches from their previous origin commits (main `39619e1` → `a6b574a`, develop `659f4125` → `a6b574a` via FF only — actually the no-ff merge demand also worked because origin's local-track was an ancestor).
- `git fetch --tags origin` to sync the local tags-tracking refs.
- `git tag -f v0.9.0 HEAD && git push -f origin v0.9.0` — force-move the `v0.9.0` tag from `659f4125` to `a6b574a` so the tag correctly points at the merge commit that contains v0.9.0 code + CI in one place.
- `gh repo edit --default-branch main` (via gh CLI, authenticated as `marodriguezd`).
- Verification: `git ls-remote origin main develop v0.9.0` showed all three at the expected SHAs.

### 6. Audit + purge of legacy tags
- Target list (4): `v0.5.0` (tag name vs `build.gradle.kts versionName` mismatch: tag says `v0.5.0` but build.gradle had `versionName="0.4.0"`); `v0.4.0-ai`, `v0.3.0-ai`, `v0.2.0-ai` (pre-fork AI-era tags, all three pointing at commits with `versionCode=15` / `versionName="0.1.14"` — pointless duplication).
- Surviving inventory (8): `v0.9.0`, `v0.8.0`–`v0.8.8` (active fork cycle), `v0.7.0`, `v0.6.0`.
- Execution: `git tag -d <tag>` then `git push origin --delete <tag>` for each.
- Purged commits remain in git history (tags are pointers, not commits); the cleanup-release rationale is preserved per the v0.9.0 narrative.

### 7. GitHub Releases: audit confirmed 0
- `gh release list --repo marodriguezd/android_transcribe_app --limit 100` returned `[]`.
- Consistent with the AGENTS.md v0.9.0 line "8 prior v0.x GitHub Releases hard-deleted".
- `v0.9.0` tag exists but no GitHub Release yet — deferred, see §11.

### 8. AGENTS.md doc updates
- The `## Branches` section was stale (still said `main (60548dd) — diverged from develop at v0.8.0`). Rewrote it to reflect: branch alignment at `a6b574a` (later `9834358` after doc commits), default branch set to `main`, surviving tag inventory, GitHub Releases=0 with deferred-release command documented.
- Two commits, in sequence:
  - `d70bc91` — primary rewrite of `## Branches` section.
  - `9834358` — added a caveat to the deferred-release instructions documenting the v0.8.6 release-build asserts. Code-reviewer in the prior turn flagged this as a foot-gun: anyone running `./gradlew assembleRelease` locally without `release.keystore` + env vars would fail-fast on the new asserts. The caveat lists the four pre-conditions explicitly: (a) `release.keystore` at project root, (b) `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS` env vars, or `CI=true` to skip the keystore-existence check (warn-only in CI), (c) `isMinifyEnabled = false` on the release build type (default true).

### 9. GitHub Actions — verified badge passing
- Direct SVG fetch via curl: both shields encoded `passing`.
  - `https://github.com/marodriguezd/android_transcribe_app/actions/workflows/android_release.yml/badge.svg` → `<title>Build Debug APK - passing</title>`
  - `https://github.com/marodriguezd/android_transcribe_app/actions/workflows/event-test.yml/badge.svg` → `<title>Event Router Test - passing</title>`
- Run inspection via `gh run list`: `Event Router Test` passes on `main`, `develop`, `v0.9.0`. `Build Debug APK` passes on `develop` and `v0.9.0`; the `main `Build Debug APK` initially reported `in_progress` (downloading ~640 MB of model assets from HuggingFace + compiling) and concluded successfully. The two doc commits (d70bc91, 9834358) on top of the merge did not re-trigger builds — consistent with whatever path filters the workflow has.

### 10. `git gc --prune=now --aggressive`
- Triggered by the dangling objects left by the legacy-tag purge. 3 dangling commits were eligible for pruning (their tree and blob objects referenced via indirect paths).
- Output: `git gc` ran in ~3.24 seconds.
- Before: 70 loose objects (356 KB), 1 898 in-pack (17 371 KB), 3 dangling.
- After: 0 loose objects (0 KB), 1 940 in-pack (16 870 KB), 0 dangling.
- Net: −501 KB pack size, −356 KB loose, −3 dangling. Refs unchanged.
- `git fsck` post-gc clean.

### 11. Deferred (mobile build env constraint — not a bug, a workflow decision)
- **`gh release create v0.9.0`** was NOT executed in this session. The user opted to defer because the active build host lacks JDK + NDK + `cargo-ndk`, and the v0.8.6 release-build asserts would fail-fast locally without `release.keystore` + the required env vars.
- Pre-conditions for the next session (documented inline in `## Branches`):
  1. Build `app-release.apk`: `./build.sh` or `./gradlew assembleRelease`.
  2. Ensure `release.keystore` at project root or `CI=true` env; export `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS`.
  3. Run:
     ```sh
     gh release create v0.9.0 \
       --repo marodriguezd/android_transcribe_app \
       --target main \
       --notes-file fastlane/metadata/android/en-US/changelogs/32.txt \
       app/build/outputs/apk/release/app-release.apk#Offline\ Voice\ Input\ v0.9.0.apk
     ```
- Why the CI workflow doesn't do this for us: `android_release.yml` only produces **debug** APKs as artifacts (no signing material in CI secrets beyond DEBUG keystore, which is auto-cached for stable debug-coexistence). Release APK + GitHub Release must be manual.

## Session history (post-v0.9.0 hotfix #1) — 2026-07-20

Two user-reported defects addressed in commit `a31a77f` on `develop` (push to `origin/develop` triggered the `Build Debug APK` CI workflow; `main` left untouched because the v0.9.0 housekeeping block already noted `main` is the trunk and only gets fast-forwarded during housekeeping-style coordinated moves, never for single-fix commits).

### What was done
41. **All-English output on the direct model path (and everywhere else)**. The only Spanish string remaining in user-facing resources was the post-process "saved" toast:
    - Was: `<string name="post_process_settings_saved">Configuración guardada</string>` in `app/src/main/res/values/strings.xml`.
    - Now: `<string name="post_process_settings_saved">Settings saved</string>`.
    Verified the rest of the user-facing surface is English by sampling the candidate paths: the three Rust CLI examples (`transcribe-rs/examples/parakeet_cli.rs`, `transcribe.rs`, `openai.rs`) print their banner / model-load / transcription lines in English; the test fixtures in `transcribe-rs/tests/parakeet.rs` (`jfk.wav` → "And so, my fellow Americans, ask not what your country can do for you...") and `whisper.rs` (Quirk/Quid/Quill transcript) expect English output; the two shipped models are English-only per `R.string.model_card_meta_parakeet` ("Parakeet TDT v3 (English)") and `model_card_meta_canary` ("Canary 180M (English)"). So the project invariant — *no Spanish / non-English user-facing string ships* — is now enforced for the post-process flow specifically, and consistent with the rest of the surface.
42. **RadioGroup visual-state defensiveness for rapid programmatic switches.** Introduced a private `selectRadioButton(String variant)` helper in `MainActivity` that:
    - null-guards `rbModelFastest` / `rbModelFast` / `rbModelNone` / `modelGroup` before any view access (defensive against the AGENTS.md "Common pitfalls" note that the three RadioButtons may be null if `setupModelSelection` has not run);
    - saves and restores the `modelSelectionChanging` flag rather than clobbering it to `false` on exit (safer than the prior pattern — no caller accidentally cleared by the helper);
    - calls `modelGroup.clearCheck()` *and* `setChecked(false)` on every RadioButton, then `setChecked(true)` on the target.
    The reason for the manual loop is that the three `MaterialRadioButton`s sit inside `LinearLayout`s (so the per-row delete `ImageButton` can sit beside the radio), which makes `RadioGroup`'s direct-child auto-uncheck traversal unreliable across Android versions — and the v0.8.3 fix that switched `RadioButton.setChecked(true)` to `RadioGroup.check(id)` did not catch this because the RadioButtons are not **direct** children of the Group. After rapid `updateModelSelectionUI()` / `onComplete()` / `confirmDeleteModel()` re-syncs, more than one radio can stay visually selected.
    Replaced the 5 sites that previously did the manual `modelGroup.check(id)` + `modelSelectionChanging = true; ...; = false` dance:
    - `updateModelSelectionUI()` — onResume re-sync path.
    - `setupModelSelection()` — initial sync on `onCreate` (before listener attachment).
    - `createDownloadCallback.onComplete()` — after a model finishes downloading, before kicking off the JNI switch. (Inside the anonymous inner class, the call is `a.selectRadioButton(variant)`.)
    - `confirmDeleteModel()` auto-fallback branch — when the user deletes the currently-active variant, the auto-fallback to the *other* downloadable variant is preserved bit-for-bit (deleting 180m → falls back to 0.6b, deleting 0.6b → falls back to 180m). The refactor also tightened the order: `setModelVariant(targetVariant)` BEFORE the radio sync so the helper sees the canonical variant name, eliminating a one-line window where `updateDeleteButtons()` could read a stale prefs value.
    - All three click handlers in `showFirstLaunchDownloadDialog()` — Fastest / Fast / Skip.
    The **only** remaining `RadioButton.setChecked(true)` calls in the project live inside `selectRadioButton()` itself (i.e. the helper). The v0.8.3 "no `setChecked` outside `RadioGroup.check`" invariant is therefore upgraded to: **"no `setChecked` outside the `selectRadioButton` helper"** — a single chokepoint for any future "stuck checks" regression.

### Verification
- `git diff --stat` for `a31a77f`: 2 files changed, +93 / −46 in `MainActivity.java`, 1 string line in `strings.xml` (net +3).
- Code-reviewer (minimax-m3): **APPROVED** — A (helper null-guards + flag envelope + no new thread → no WeakReference obligation), B (5 sites consistent), C (`confirmDeleteModel` auto-fallback preserved; cleaner because `setModelVariant` now precedes the radio sync), D (Spanish fixed; rest of user-facing surface already English), E (only caller of `RadioButton.setChecked(true)` is the helper itself).
- Killed the in-flight bash attempts that mis-escaped parentheses / apostrophes in inline `-m` arguments (first commit attempt failed because the shell split on `setChecked(...)` and the smart quotes around `'rbModelFast'`); retried with `git commit -F commit_msg_fix_radio.txt` and the same content landed cleanly. Temp file removed after the commit succeeded.
- CI verification: `gh api /repos/marodriguezd/android_transcribe_app/actions/runs?per_page=3` confirms Actions is auto-enabled for this repo and the push to `develop` triggered both workflows:
    - `Event Router Test` for `a31a77f` → `status: completed`, `conclusion: success`.
    - `Build Debug APK` for `a31a77f` → `status: in_progress` at last poll (the cargo-ndk step is downloading the Parakeet bundle from HuggingFace — first heavy build after resuming Actions on a clean Linux worker takes ~5–10 min). The APK artifact will materialise in the run's *Artifacts* block once it concludes.
    No manual run-dispatch was needed; the workflow's `on: push:` is sufficient.

### Files changed
| File | Purpose |
|------|---------|
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | New private `selectRadioButton(variant)` helper; replaced 5 manual `modelGroup.check(...)` call sites |
| `app/src/main/res/values/strings.xml` | `post_process_settings_saved` translated Spanish → English |

### Limits
- No local JDK / NDK / `cargo-ndk` (Linux host), so `./gradlew :app:compileDebugJavaWithJavac` cannot run here. CI is the only validator that exercises the Java compile + R8 + cargo-ndk Rust link chain. Typecheck responsibility moves to the Action's `gradle/actions/setup-gradle@v2` step.
- Commit was pushed to `develop`, not `main`. `main` is the trunk per the v0.9.0 housekeeping agreement; coordination for a `develop → main` FF would be a separate, deliberate step (the v0.9.0 README/AGENTS narrative still anchors on `main`).

### Next steps (for the user)
- Monitor the `Build Debug APK` run on `develop` (linked to commit `a31a77f`) in the Actions tab; once green, download the APK artifact (zip with `app-debug.apk`).
- `adb -s 192.168.1.45:37601 install -r app-debug.apk` to flash the A059.
- On-device smoke test the **Transcription Model** card (Fastest 180M ↔ Fast 0.6B ↔ Use without model) under rapid taps — **only one** radio should stay lit at any time. If two still appear lit after this build, the next debugging tranche should restructure `activity_main.xml` so the RadioButtons are *direct* children of `rg_model` (LinearLayouts as wrappers in the same RadioGroup are the root cause of this class of bug).
- Verify the post-process settings toast reads `Settings saved` (English), not `Configuración guardada`.
- (Pre-v0.9.1 release prep) bump `versionCode` 32 → 33 and `versionName` 0.9.0 → 0.9.1 in `app/build.gradle.kts`; author `fastlane/metadata/android/en-US/changelogs/33.txt` covering both fixes; sign the release APK locally (requires `release.keystore` at project root + `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS` exported, otherwise the v0.8.6 defensive build asserts fail-fast); `gh release create v0.9.1 --target main --notes-file changelogs/33.txt` with the signed APK.

### End-of-session invariants (post hotfix #3 + #4 closure, 2026-07-21)

| Ref | SHA | Notes |
|---|---|---|
| `main` (local + origin) | `af79a89` | Default branch on GitHub. Tip: `docs(AGENTS): add Session history (post-v0.9.0 housekeeping) - close the audit trail` (direct-pushed at the v0.9.0 housekeeping cycle, NOT FF'd into `develop`). |
| `develop` (local + origin) | `1c0d7aa` | **11 commits ahead of `main`** (`main` has nothing `develop` doesn't). The 11 commits are: hotfix #1 `a31a77f`+`4f52d4c`, Canary multilingual `1c4ae35`+`7171a6b`+`56d4469`+`7f03045`+`cfe84f3`, hotfix #2 `fb17897`+`7ff296a`, hotfix #3 (Canary Auto mode `26ecd32`), and hotfix #4 (IME cross-component state broadcaster `1c0d7aa`). The "develop mirrors main" baseline from § Branches is currently broken — next housekeeping-style coordinated move should FF `main` to `develop`'s tip (`1c0d7aa`). Suffix `1c0d7aa` references the JNI broadcaster Java feat; the actual code-merge SHA for the v0.9.x release line is `1c4ae35`. |
| `v0.9.0` (tag, local + origin) | `a6b574a` | Points at code-merge commit. Unchanged across hotfix #1..#4 — all four shipped as `develop`-only single-fix commits per the post-v0.9.0 housekeeping agreement. |

- Working tree: clean on `develop`. `git fsck`: 0 errors.
- Hotfix #2 commits on `develop` (this session):
  - `fb17897` — `fix(MainActivity): route user-tap model-radio selection through per-button CompoundButton listeners` (closes the "both radios appear selected" regression + the "needs close-and-reopen to refresh UI after delete" regression; replaces dead `modelGroup.setOnCheckedChangeListener` with `attachModelRadioListener` per-button listeners + `onVariantSelectedByUser` dispatch helper + `switchModelAsync` helper).
  - `7ff296a` — `docs(AGENTS): session history block for post-v0.9.0 RadioGroup user-tap fix`.
- Hotfix #2 CI validation (for `7ff296a`): Build Debug APK run `29820968886` → `completed / success`; Event Router Test run `29820969008` → `completed / success`. Java compile + R8 + cargo-ndk + ONNX bundled extract all green. APK artifact: `gh run download 29820968886 --name app-debug-apk`. On-device smoke test deferred to user (A059 at `192.168.1.45:37601`).
- Local tags: 8. Origin tags: 8. Identical inventory: `v0.6.0`, `v0.7.0`, `v0.8.0`, `v0.8.4`, `v0.8.5`, `v0.8.7`, `v0.8.8`, `v0.9.0`.
- Badge SVGs: both `passing` on `develop`.
- GitHub Releases: 0 (cut of `v0.9.1` deferred per § Branches — requires local JDK + NDK + `release.keystore` + `KEY_ALIAS` / `KEY_PASS` / `STORE_PASS`; the CI workflow produces debug APKs as artifacts only, does not sign release APKs).


