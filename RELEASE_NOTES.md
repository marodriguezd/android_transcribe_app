# ✨ Aura Transcribe v0.2.1 — Universal WhisperFlow Engine & Recommended Packs

`versionCode 40` — Quality, Audio & Intelligence Release: WhisperFlow-level universal prompt engine, curated model packs for lightweight and offline workflows, built-in mic default reliability, Bluetooth headset dynamic routing, and AI Fix guardrails.

### 🌟 Key Changes in v0.2.1

- **Universal WhisperFlow-Level Post-Processing Engine:** Engineered a state-of-the-art system prompt capable of transforming raw transcripts into publication-ready text even on smaller 7B–27B models. Features automated Markdown bullet/numbered list formatting for shopping or sequential instructions, strict atomic handling of programming tokens and casing (`camelCase`, `snake_case`, `--flags`, URLs, code snippets), seamless resolution of verbal disfluencies and mid-sentence self-corrections, and execution of spoken meta-voice commands (*"borra eso"*, *"entre comillas"*, *"en negrita"*).
- **Curated Recommended Model Packs UI:** Added an interactive "Packs Recomendados" dialog in `ModelsActivity` featuring 1-tap access to:
  - **⚡ Ultralight Offline Pack (<500 MB):** *Canary 180M Flash* (210 MB) / *Parakeet 110M* (135 MB) + *SuperWhisper S1-mini* (380 MB) for ultra-fast, low-memory, 100% offline transcription and text normalization.
  - **🏆 Pro Integrated Pack (Recommended):** *Nemotron 3.5 ASR 0.6B* (bundled) + *AI Fix Cloud* (Groq / OpenAI / Cerebras) for maximum accuracy and zero initial setup.
  - **🚀 Whisper Extended Pack:** *Whisper Large-v3-Turbo* (845 MB) for noisy audio and heavy multilingual translation.
- **Built-in Internal Microphone by Default:** Default microphone mode is now strictly configured to the phone's internal microphone (`MIC_MODE_BUILTIN_ONLY`), guaranteeing immediate, flawless audio capture out of the box. Users can opt into "Automático" or "Solo Bluetooth" from Settings whenever desired.
- **Wireless Bluetooth Headset Detection & Routing:** Overhauled `AudioDeviceManager` to seamlessly detect, connect, and route audio from Bluetooth wireless earbuds (AirPods, Galaxy Buds, Pixel Buds, Sony, etc.), Bluetooth SCO/A2DP headsets, Bluetooth LE Audio, Hearing Aids, USB microphones, and wired headsets. Added runtime `BLUETOOTH_CONNECT` permission checks on Android 12–15+.
- **AI Fix Configuration Guardrails:** `SettingsManager.isPostProcessEnabled()` strictly validates `isPostProcessConfigured()`. Tapping the "AI Fix" switch on the on-screen keyboard or floating overlay without a configured provider automatically snaps the toggle back to OFF and displays an informative prompt (`pp_not_configured_prompt`).
- **100% i18n Parity (257 Strings across 6 Locales):** Full translation coverage across English, German, Spanish, French, Italian, Portuguese, and Russian.

---

### 📦 Assets

- `Aura_Transcribe_v0.2.1.apk` (Release APK with bundled Nemotron 3.5 ASR Streaming 0.6B Q8_0 model)
