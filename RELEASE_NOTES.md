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
