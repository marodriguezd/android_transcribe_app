# Changelog

Full change history of **android_transcribe_app** (fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app)).

# v0.1.32

Floating bubble position memory & panel placement:

- Bubble position persisted in a marker file (`floating_bubble_pos`) and restored on service start, clamped to the current screen so rotation/screen-size changes can never push it off-screen.
- Expanded dictation panel opens vertically centered on the bubble instead of always under the status bar; clamped between status bar and nav bar using the panel's measured height.
- Position also saved when a drag is interrupted (touch cancel) and on every overlay stop path, so the bubble reliably returns to its spot.

---

# v0.1.31

Whisperflow floating bubble dictation & Accessibility auto-paste:

- Added `FloatingOverlayService` with draggable floating bubble overlay (`SYSTEM_ALERT_WINDOW`) and expanded dictation control panel.
- Added `FloatingDictationAccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`) to track focused input fields across applications and insert transcribed text via `ACTION_PASTE`/`ACTION_SET_TEXT`, with clipboard fallback so no transcript is ever lost.
- Linked native Rust audio capture JNI bridge (`src/floating.rs`) to floating overlay callbacks.
- Integrated AI Fix toggle, streaming hypothesis window, and Insert action button into floating overlay panel.
- Full-width IME-style panel: expands to near-screen width below the status bar and returns to the exact bubble position on collapse.
- Crash-proof stack: permission-gated startup (mic + overlay) with graceful stopSelf instead of restart crash-loops; exception-safe accessibility event handling; poison-tolerant native session locks.
- Stop without opening the app: notification Stop action and long-press on the bubble (with fade-out + toast feedback on Android < 12).
- Listening pulse animation on the mic circles while recording.

---

# v0.1.30

Integrated AI Fix toggle in IME keyboard:

- Integrated AI post-processing toggle switch directly in the central voice keyboard container (`ime_pp_toggle`).
- Supports enabling/disabling post-processing on the fly at any point before, during, or after speech recognition.
- Immediate raw text delivery if toggled OFF at end of transcription or mid-refinement.
- Clean header layout with status on top-left ("Escuchando...") and Cancel button on top-right.

---

# v0.1.29

Cancel-anytime keyboard dictation and resultPending window improvement:

- The IME Cancel button appears as soon as recording starts, allowing instant cancellation before ASR/LLM post-processing.
- Mid-capture cancellation discards the native audio buffer and cancels in-flight LLM calls.
- Stays continuously visible through mic release, transcription, and refinement without UI flickering.

---

# v0.1.28

Security and robustness hardening:

- Hardened release signing: local release builds fail fast if signing env vars are missing.
- Backup privacy: excluded app data from Android Auto Backup (`allowBackup=false`).
- Memory bounds: capped voice recording at 5 minutes and file transcription at 30 minutes.
- Local LLM preset: restored cleartext support for Ollama localhost on Android 9+.
- Subtitle crash fix: safe teardown when `AudioRecord` fails to initialize.
- File transcription privacy: removed auto-copying to system clipboard.

---

# v0.1.27

Live-subtitle translation and caption-context improvements:

- Added optional on-device translation of finalized live-subtitle captions to a selected language, with ordered results and safe fallback to the original text.
- Live subtitles now show four lines by default, preserving more original-language context before translation.
- Stale translation callbacks are ignored so a restarted subtitle session cannot be overwritten by an older one.

---

# v0.1.26

Release post-processing fix for upgrades from versions that stored the API key in encrypted preferences:

- Legacy encrypted API keys are migrated into the cross-process marker store when recoverable.
- Missing/unrecoverable keys fail fast with a clear settings prompt; raw transcript fallback remains intact.
- `/models` and connection diagnostics no longer send unauthenticated requests.

---

# v0.1.25

Follow-up to v0.1.24 focused on usability and diagnostics:

### 🎙️ Transcription cancellation
- Added cancellation controls for voice-popup, IME keyboard, and file transcription flows.
- Cancellation is routed through the existing generation/operation lifecycle so stale results are not delivered after a user stops an operation.

### 📥 Model download visibility
- Debug model downloads now report progress, giving users clear feedback while the speech model is fetched.
- Download status text is localized across the supported app languages.

### 🤖 Post-processing diagnostics
- Provider and configuration failures now expose clearer diagnostics and error details in the post-processing settings flow.
- The existing safe fallback remains in place: when refinement fails, the raw transcript is preserved.

---

# v0.1.24

Release hardening for the 0.1.24 launch:

### 🔒 Transcript privacy
- Raw transcripts and provider endpoints are no longer logged in release builds (`BuildConfig.DEBUG` gating); debug APKs keep the diagnostics.

### 🤖 Post-processing final-only (replacing the historical SSE streaming)
- The ASR streaming preview remains visual-only; the final transcript is sent exactly once to the post-processor and the complete response is committed.
- The IME and popup no longer paste partial LLM tokens; any failure, cancellation or invalid response falls back to the raw transcript (no "Frankenstein" text).

### 🛡️ Hardening
- Owner-scoped post-processing cancellation (`CallRegistry`), generation-scoped subtitle workers, SHA-256 verified debug model downloads, atomic marker writes, operation-ids for file transcription, sanitized model import names, import-safe lifecycle.
- JVM harness closed: real DNS failure and connect-timeout tests added to `PostProcessorTest` (10 tests; 34 JVM tests total).
- CI: `cargo fmt --all -- --check` gate in both workflows, `checkModels` on the debug workflow, keystore fail-fast, `zipalign`/`apksigner` verification of the release APK.

---

# v0.1.23

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app). This release brings real-time **SSE streaming AI post-processing**, fixes **silence auto-stop for the IME keyboard**, and introduces seamless **Android System User Dictionary integration (FUTO Keyboard style)**.

## What's new in v0.1.23

### 🚀 Real-time Streaming AI Post-Processing (SSE)
- **Token-by-token streaming:** AI post-processing now uses Server-Sent Events (`stream: true`). Text starts refining in real-time with Time-To-First-Token down to **~300 ms** (previously ~2,000 ms block wait).
- **Live insertion in IME & Voice Popup:** Refined tokens stream directly into the focused text input field in `RustInputMethodService` and display live in `RecognizeActivity`.
- **Resilient & No "Frankenstein" text:** Automatic 3-attempt reconnect retry on mid-stream drops. On persistent connection loss, partial deltas are cleaned up (`deleteSurroundingText`) and the raw transcript is delivered clean without text corruption.
- **Provider Fallback:** Automatic fallback to standard block requests if a provider returns HTTP 400 (`stream` not supported).

### ⏱️ IME Silence Auto-Stop & Adaptive VAD Tuning
- **IME Keyboard Auto-Stop:** Fixed silence auto-stop (`auto_stop` marker) being hardcoded to `false` in the `:ime` keyboard process. Now the keyboard automatically stops and transcribes after 2 seconds of trailing silence.
- **Adaptive VAD Heuristics:** Lowered minimum speech level threshold (`MIN_SPEECH_LEVEL = 0.05` and `SPEECH_MARGIN = 0.04`) in `src/voice_session.rs` with dynamic noise floor tracking, reliably capturing soft and quiet speech.

### 📖 Android System User Dictionary Integration (FUTO Keyboard Style)
- **Native Android System Menu:** Tapping **Custom Words** in `MainActivity` opens Android's native Personal User Dictionary settings (`Settings.ACTION_USER_DICTIONARY_SETTINGS`) with string action and general settings fallbacks for custom OEM ROMs (Samsung OneUI, Xiaomi MIUI, etc.).
- **Automatic Sync:** Reads Android's `UserDictionary.Words` ContentProvider (`READ_USER_DICTIONARY` permission) on app start and right before every voice recording session, syncing system words into Rust's phonetic corrector (`src/corrector.rs`) across all surfaces.

---

# v0.1.22

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app). This release adds an on-device **custom-words dictionary** that corrects misrecognized terms phonetically, and — most importantly — makes the optional AI **post-processing activate and deactivate reliably** between the main app and the `:ime` keyboard process.

## What's new in v0.1.22

### 📖 Custom words (phonetic dictionary, on-device)
- New **Custom Words** editor. List any term the speech model mishears — proper nouns, technical jargon, names; one per line, `#` for comments.
- Spanish+English phonetic encoder + Levenshtein distance ≤ 2 on phonetic keys; character-bigram cosine similarity breaks ties. Speaker's capitalization is preserved.
- **Multi-word terms** supported (e.g. "New York", "Buenos Aires") via sliding windows, longest-first.
- **Covers every surface at once** — runs inside the engine's `transcribe_shared`, so the voice popup, IME keyboard, live subtitles, SpeechRecognizer, and file transcription all benefit without per-surface wiring.
- **Safe by design:** wrapped in its own `catch_unwind`, so a corrector bug can never freeze the IME — the raw transcript is delivered instead.
- Works with **any speech model** (Whisper, Canary, Parakeet, …) and requires **no network**. The file is mtime-cached so live-subtitle partials don't re-read it.
- UI fully localized in all 7 app languages (EN, ES, DE, FR, IT, PT, RU).

### ⚙️ Post-processing: activation & deactivation now behave correctly
- All 5 PP settings (`pp_enabled`, `pp_provider`, `pp_url`, `pp_model`, `pp_prompt`) **and the API key** now live as **marker files in `filesDir()`**. Both the main app process and the `:ime` keyboard see them instantly — fixing the *"does not stop working after disabling"* symptom reported in earlier releases.
- **`cancelAll()` interrupts in-flight LLM calls the instant you toggle off**, and `PostProcessor.cancelAll()` is also triggered by a cancel `Broadcast` from main → IME so the keyboard's in-flight calls go down too — **no more ghost `"Refining…"`** waiting for the OkHttp timeout.
- **Activity/Session guards** stop callbacks and late results from landing on torn-down components: lifecycle validators on the 3 Activities (`RecognizeActivity`, `PostProcessSettingsActivity`, `TranscribeFileActivity`) and on the IME service drop callbacks when `!isFinishing() && !isDestroyed()`; `VoiceRecognitionService` discards results from stale sessions via session IDs.
- **First activation is instant:** the API key is no longer in `EncryptedSharedPreferences`, so no cold Android Keystore boot on the UI thread.
- **Singleton `OkHttpClient` with `ConnectionPool`** reuses TLS sessions across calls; **atomic marker writes** (temp file + rename) prevent torn reads on concurrent saves for every PP setting, including the API key.
- Legacy `SharedPreferences` → marker files migration is silent and cross-process safe for the 5 settings (see 🔄 below).

### 🛠️ Debug build model download is reliable
- Debug builds no longer ship the bundled Canary 180M Flash model **at build time**; it's downloaded by the app from Hugging Face on first run, keeping the test APK under Telegram's 50 MB limit.
- The runtime download in `MainActivity` is hardened against early-return edge cases and `Activity`-recreation stalls so the download no longer gets stuck on configuration changes.
- The downloaded GGUF is SHA-256 verified; checksum mismatch triggers re-download, same code path as the release build.

### 🔄 Silent migration on upgrade
- Users coming from any earlier fork release get their existing post-processing settings (toggle, provider, URL, model, prompt) migrated to marker files **automatically** — no toggle reset, nothing to configure.
- The migration uses an **OS-level file lock** (`FileChannel.tryLock`) on a sentinel marker in `filesDir()` so the main process and the `:ime` keyboard never race. Whichever acquires the lock first does the work; the other sees the sentinel on its next `App.onCreate` and skips.
- Legacy `SharedPreferences` is cleared **synchronously** under the lock with `commit()` so a stale `enabled=true` can never leak back into either process after migration.
- ⚠️ **API key on upgrade:** the previous `EncryptedSharedPreferences` API-key store is **not** migrated (cross-process consistency comes from re-authenticating from the new marker). If you set an API key in any earlier release and you want the LLM cleanup to keep working after upgrading, you only need to re-enter the key once — it goes straight into the new `pp_api_key` marker file and is then cross-process consistent.

---

# v0.1.21

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18** (inherits the v0.1.19/v0.1.20 layers). This release fixes a language regression introduced in v0.1.20.

## What's fixed vs v0.1.20

### 🐛 Language selection now applies everywhere (IME included)
- In v0.1.20 the engine cached the transcription language in memory and only re-read it on an explicit model reload from `ModelsActivity` (the main process). The voice-keyboard process (`:ime`) loaded the language once at startup and never re-read it — so picking "English" (or any non-default language) in the dropdown wrote `model_language=en-US` and reloaded the main engine, but **speaking through the keyboard still transcribed in the cached default language** (Spanish on a Spanish phone). Symptom: "I set English but it always writes Spanish."
- Fix: the engine now re-reads `model_language` from disk on **every** transcription run (`Engine::run` in `src/engine.rs`), so a language change applies in any process — main or `:ime` — with no manual reload.
- Behavior is unchanged by design: "Auto (device language)" transcribes in the phone's system language; any explicit language (English, French, …) is honored even when the device locale differs. Verified on-device speaking EN/ES/FR.

---

# v0.1.20

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18** (inherits everything from the v0.1.19 layer). This release fixes the language-handling behavior and ships a new default post-processing prompt.

## What's new vs v0.1.19

### 🌍 Default transcription language = device language
- On first run (and whenever no language has been chosen) the app now defaults the transcription language to the **device's current language** (e.g. `es-ES`, `en-US`, `fr-FR`) instead of leaving it empty.
- Previously an empty language left the bundled Canary 180M Flash model with no hint, so it defaulted to **English for every input language** regardless of what was spoken. Now speech is transcribed in the phone's system language by default.
- The language is resolved at app start (`App.onCreate`, in every process including the `:ime` keyboard) and written to `model_language`; the engine's existing per-run locale degradation (`es-ES` → `es`) still applies.
- The language dropdown's "Automatic" option is now labeled **"Auto (device language)"** and, when picked, writes the device language as the hint. Users can still override it explicitly with any supported language.

### 🤖 New default AI post-processing prompt
- Replaced the bundled default system prompt with a Wispr-Flow-style dictation engine prompt: zero-loss cleanup, thematic blocking into paragraphs, stutter/filler removal (including `o sea`, `bueno`, `vaya`…), spoken-correction handling, smart numbers/currency/percent formatting, and enumeration lists.
- The prompt uses a `${output}` marker where the raw transcript is injected before the LLM call, so the cleaned text is returned in the same language as the input.

---

# v0.1.19

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18**. Everything from upstream still works the same — this release only adds a layer on top and changes the bundled model. Below is what differs from the original.

## What's new vs upstream v0.1.18

### 🤖 Optional AI post-processing
- New **AI post-processing** layer: after on-device transcription, the text can be cleaned up by any OpenAI-compatible LLM (punctuation, capitalization, inverse text normalization "uno dos tres" → "1, 2, 3", disfluency removal, etc.).
- **Provider presets**: Groq, OpenAI, Cerebras, OpenRouter, Mistral, Together, Ollama (local), or a fully custom base URL.
- **Model picker with live fetch**: a refresh button queries the provider's `/models` endpoint with your key and fills a dropdown.
- **API key stored encrypted** (EncryptedSharedPreferences).
- **Safe fallback**: if the API call fails, the raw transcription is delivered instead — you never lose text.
- Fully **opt-in and off by default**; the app stays 100% offline unless you enable it.
- Wired into both the recognition panel and the voice keyboard (IME).

### 🎙️ New default speech model: Canary 180M Flash (Q8)
- Bundled model switched from Parakeet TDT 0.6B v3 (Q4_K_M, ~485 MB) to **NVIDIA Canary 180M Flash (Q8_0, ~209 MB)** — smaller download, faster on-device.
- Added a bundled-model integrity check so an app update that ships a different model actually re-extracts it (upstream would keep the stale one).

### 🌍 Localization
- The AI post-processing UI is fully translatable and shipped in all 7 app languages (EN, ES, DE, FR, IT, PT, RU).
- Fixed the hardcoded "Parakeet" label in **Manage speech models** — the built-in model name and description now reflect Canary and are localized.

### 📦 Fork housekeeping
- Requires the `INTERNET` permission (only used by the optional post-processing; upstream had none).
- README rebranded as a fork: Play Store badge removed, GitHub links point to this repo's releases, credits the upstream project.
- **APK-only** releases (AAB output dropped from CI and docs).

---

*Base engine, transcription, live subtitles and voice keyboard are unchanged from upstream v0.1.18. Credit for the original app goes to [notune](https://github.com/notune/android_transcribe_app).*
