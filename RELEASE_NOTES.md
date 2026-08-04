# Unreleased

## v0.1.24 — release hardening

> These notes are the ones the `v0.1.24` tag will publish (the release workflow
> reads `RELEASE_NOTES.md` via `body_path`). Fork of
> [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app).

### 🔒 Transcript privacy in production logs
- The raw transcript and provider endpoint are no longer logged in release builds (`BuildConfig.DEBUG` gating). Debug APKs keep the diagnostics; production logcat never contains user speech or the post-processing error payload.

### 🤖 Post-processor isolation and final-only contract
- Post-processing cancellation is **per surface and session**: closing the popup, cancelling a recognition or destroying an Activity no longer interrupts a legitimate request from another surface (IME, recognition service, file transcription, settings). The global `cancelAll()` stays reserved for truly global events (PP toggle-off with a broadcast to the keyboard process, IME destruction).
- The HTTP contract is pinned by JVM tests with a controlled server (MockWebServer): `/chat/completions` payload with `stream:false`, transcript injected exactly once, HTTP/JSON errors fall back to the raw text, exactly one final delivery per request, and the real OkHttp timeout path with scaled values (the 30 s/60 s production values are asserted as applied values; wall-clock behaviour is validated on device).
- **JVM harness closed for P1.5 (2026-08-04):** two new tests exercise real DNS failure (RFC 6761 `.invalid` host) and the real OkHttp connect-timeout path (TEST-NET `192.0.2.1` with a scaled client). The remaining P1.5 scenarios (production 30 s/60 s wall-clock, real TLS, `CANCEL_PP` broadcast to `:ime`, concurrent surfaces, leaks, end-to-end latency) require a device.

### 🎬 Subtitles: no callbacks from previous sessions
- Every subtitle session carries a generation: stopping and restarting discards the previous session's queued jobs without transcribing or drawing on the new overlay; the accumulated text resets per session.

### 📦 Debug model download verified before activation
- The debug-runtime downloaded model is verified with SHA-256 before activation: a truncated or altered file is never marked as the active model and can be retried, matching the release build's `checkModels` guarantee.

### 🛠️ Toolchain, markers and CI
- NDK unified to `28.0.13004108` in Gradle, CI and documentation; toolchain paths are resolved per host (Linux x86_64/aarch64, macOS Intel/ARM, Windows).
- All settings writes (`model_language`, `active_model`, …) are atomic (temp + rename): concurrent readers from the main and keyboard processes never observe a partial value.
- **CI hardening (2026-08-04):** `cargo fmt --all -- --check` is a hard gate in both workflows; `checkModels` now also runs on the debug workflow; the release workflow fails fast when `KEYSTORE_BASE64` is missing and verifies the release APK with `zipalign -c` and `apksigner verify` before publishing.
- A warning is printed when `release.keystore` exists locally but one of `STORE_PASS`/`KEY_ALIAS`/`KEY_PASS` is missing (historical defaulting behaviour kept for local development; never publish such an APK).

### 🗂️ File transcription with operation-id
- File-transcription callbacks carry an operation id: rotating, closing or recreating the screen during decode/ASR never updates the wrong instance.

### 📦 Model import hardening
- Model file names from the system file picker are sanitized (path separators, `..`, control characters are rejected), and a long import can no longer touch a destroyed Activity's views (rotation/back during a multi-hundred-MB copy).

### ⌨️ IME keyboard keeps its shape while streaming partials
- **Mic area no longer compacts:** when live partial hypotheses appear (the keyboard's live window shows up to 3 lines, always scrolled to the latest words), the record area used to shrink from 200dp to 148dp to hold the keyboard's total height — clipping the mic glow and overlapping the "Tap to Stop" hint with the record button. Now the record area always keeps its full shape: the keyboard grows slightly while live text is shown and returns to normal when the recording ends.

### 🛠️ AI Post-Processing: readable prompt & smarter LLM payload
- **Prompt formatting restored:** the default system prompt (`pp_default_prompt`) now compiles with real line breaks (`\n` escapes). It shows its full paragraph structure in the post-processing settings and reaches the LLM properly structured — Android's `aapt2` was collapsing every newline into a single run-on line.
- **Transcript de-duplication:** when the active prompt embeds the transcript via the `${output}` marker, the raw text is no longer sent a second time as a separate user message. The LLM now refines the text instead of mirroring the input back unchanged.
- **Streaming robustness:** mid-stream failures no longer trigger retries (which could duplicate text already committed into the editor); if the editor loses focus during streaming, the refined text is committed once available instead of being dropped; defensive guards prevent rare IME force-closes when the input connection dies mid-stream.

### 🌐 Translations
- User-visible strings live in resources across the 7 languages; documented exception: post-processor error details (layer without an Android context) and JNI protocol strings stay in English on purpose.

---

# v0.1.23

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app). This release brought **SSE streaming AI post-processing** (later replaced by the final-only contract), **silence auto-stop for the IME keyboard**, and **Android System User Dictionary integration (FUTO Keyboard style)**.

## What's new in v0.1.23

### 🚀 Real-time Streaming AI Post-Processing (SSE)
- **Token-by-token streaming:** AI post-processing uses Server-Sent Events (`stream: true`). Text starts refining in real-time with Time-To-First-Token down to **~300 ms** (previously ~2,000 ms block wait).
- **Live insertion in IME & Voice Popup:** Refined tokens stream directly into the focused text input field in `RustInputMethodService` and display live in `RecognizeActivity`.
- **Resilient & No "Frankenstein" text:** Automatic 3-attempt reconnect retry on mid-stream drops. On persistent connection loss, partial deltas are cleaned up (`deleteSurroundingText`) and the raw transcript is delivered clean.
- **Provider Fallback:** Automatic fallback to standard block requests if a provider returns HTTP 400 (`stream` not supported).
- *Historical note: since 2026-08-03 the active contract is final-only — the LLM response is no longer streamed into the editor (see v0.1.24).*

### ⏱️ IME Silence Auto-Stop & Adaptive VAD Tuning
- **IME Keyboard Auto-Stop:** fixed silence auto-stop (`auto_stop` marker) being hardcoded to `false` in the `:ime` keyboard process; the keyboard now stops and transcribes after 2 seconds of trailing silence.
- **Adaptive VAD Heuristics:** lowered minimum speech level (`MIN_SPEECH_LEVEL = 0.05`, `SPEECH_MARGIN = 0.04`) with dynamic noise floor tracking for soft and quiet speech.

### 📖 Android System User Dictionary Integration (FUTO Keyboard Style)
- **Native Android System Menu:** **Custom Words** in `MainActivity` opens Android's Personal User Dictionary settings (`Settings.ACTION_USER_DICTIONARY_SETTINGS`) with fallbacks for OEM ROMs.
- **Automatic Sync:** reads the `UserDictionary.Words` ContentProvider (`READ_USER_DICTIONARY`) on app start and before every recording session, syncing system words into the Rust phonetic corrector across all surfaces.

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
- Legacy `SharedPreferences` → marker files migration is silent and cross-process safe for the 5 settings.

### 🛠️ Debug build model download is reliable
- Debug builds no longer ship the bundled model **at build time**; it's downloaded by the app from Hugging Face on first run, keeping the test APK under Telegram's 50 MB limit.

### ⚠️ API key on upgrade
- The previous `EncryptedSharedPreferences` API-key store is **not** migrated (cross-process consistency comes from re-authenticating from the new marker). If you set an API key in any earlier release and you want the LLM cleanup to keep working after upgrading, re-enter the key once — it goes straight into the new `pp_api_key` marker file and is then cross-process consistent.

---

# v0.1.21

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18** (inherits the v0.1.19/v0.1.20 layers). This release fixes a language regression introduced in v0.1.20.

## What's fixed vs v0.1.20
- In v0.1.20 the engine cached the transcription language in memory and only re-read it on an explicit model reload from `ModelsActivity` (the main process). The voice-keyboard process (`:ime`) loaded the language once at startup and never re-read it — so picking "English" (or any non-default language) in the dropdown wrote `model_language=en-US` and reloaded the main engine, but **speaking through the keyboard still transcribed in the cached default language** (Spanish on a Spanish phone). Symptom: "I set English but it always writes Spanish."

---

# v0.1.20

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18** (inherits everything from the v0.1.19 layer). This release fixes the language-handling behavior and ships a new default post-processing prompt.

## What's new vs v0.1.19
- **Language handling fixed:** the engine now re-reads `model_language` on every transcription and streams (see v0.1.21 notes for the regression this introduced and its fix).
- **New default post-processing prompt** (the multilingual ASR-editor prompt).

---

# v0.1.19

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18**. Everything from upstream still works the same — this release only adds a layer on top and changes the bundled model. Below is what differs from the original.

## What's new vs upstream v0.1.18
- **AI post-processing layer (fork addition):** optional, off-by-default, refines transcriptions with any OpenAI-compatible LLM.
- **Bundled model:** Nemotron 3.5 ASR Streaming 0.6B (Q8_0), multilingual (40 language-locales) with automatic language detection and live partial hypotheses.
- **Custom model import:** any transcribe.cpp GGUF (Whisper, Canary, Parakeet, …) via the system file picker.
- **Streaming latency selector** for cache-aware chunk sizes.
- Live subtitles, voice popup, RecognitionService and IME inherited from upstream.

---

*For full historical release notes (v0.1.18 and older), see [CHANGELOG.md](CHANGELOG.md).*
