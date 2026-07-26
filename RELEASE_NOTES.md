# v0.1.22

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app), built on top of upstream **v0.1.18** (inherits the v0.1.19/v0.1.20/v0.1.21 layers). This is the stable build that bundles the v0.1.21 fixes (device-language default + per-run language re-read so the IME honors the chosen language) with the AI post-processing layer working end to end.

## What's in v0.1.22 (vs v0.1.21)
- Same code as v0.1.21 (no functional changes). This tag republishes a clean, signed release build so the GitHub release asset matches the working debug many users already tested — language selection (Auto/English/French/…) applies in both the voice panel and the keyboard IME, and the AI post-processing prompt refines transcripts when configured with an OpenAI-compatible provider (e.g. Groq).
- Reminder: AI post-processing needs its provider URL, API key, and model configured in the app (settings are stored encrypted and are wiped on uninstall, so re-enter them after a fresh install).

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
