# ✨ Aura Transcribe v0.2.1 — Bluetooth Headset Routing & AI Fix Guardrails

`versionCode 40` — Quality, Audio & Stability Release: Comprehensive wireless Bluetooth headset detection and routing, AI post-processing configuration guardrails, and smart keyboard feedback.

### 🌟 Key Changes in v0.2.1

- **Wireless Bluetooth Headset Detection & Routing:** Overhauled `AudioDeviceManager` to seamlessly detect, connect, and route audio from Bluetooth wireless earbuds (AirPods, Galaxy Buds, Pixel Buds, Sony, etc.), Bluetooth SCO/A2DP headsets, Bluetooth LE Audio, Hearing Aids, USB microphones, and wired headsets. Added runtime `BLUETOOTH_CONNECT` permission checks on Android 12–15+.
- **AI Fix Configuration Guardrails:** `SettingsManager.isPostProcessEnabled()` now strictly validates `isPostProcessConfigured()`. If no functional API key (Cloud providers) or local S1-mini model (Local provider) is present, post-processing safely evaluates to `false` and bypasses gracefully without false timeouts or broken states.
- **Smart Toggle Feedback on Keyboard & Bubble:** Tapping the "AI Fix" switch on the on-screen keyboard (`RustInputMethodService`) or floating overlay (`FloatingOverlayService`) without a configured provider automatically snaps the toggle back to OFF and displays an informative prompt (`pp_not_configured_prompt`).
- **Settings Screen Validation:** Saving post-processing in `PostProcessSettingsActivity` validates API key presence and local model installation, preventing saving into an unconfigured enabled state.
- **100% i18n Parity (248 Strings):** Localized `pp_not_configured_prompt` across all 6 supported languages (`de`, `es`, `fr`, `it`, `pt`, `ru`).
- **Comprehensive Guardrail & Audio Test Suite:** Added plain-JVM unit test suites verifying unconfigured post-processing bypass and audio device routing contracts.

---

### 📦 Assets

- `Aura_Transcribe_v0.2.1.apk` (Release APK with bundled Nemotron 3.5 ASR Streaming 0.6B Q8_0 model)
