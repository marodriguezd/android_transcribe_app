# Changelog

Historial completo de cambios del proyecto **android_transcribe_app** (fork de [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app)).

---

# Unreleased

### 🤖 Postprocesado AI final-only
- Los parciales del transcriptor continúan mostrándose en streaming como previsualización visual.
- El transcript final se envía una única vez al postprocesador con una respuesta JSON completa; el IME y el popup hacen un único commit del resultado.
- Si el postprocesado está apagado, se cancela, falla o devuelve contenido inválido, se entrega la transcripción cruda sin perder texto.

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
