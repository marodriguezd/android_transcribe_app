# ✨ Aura Transcribe v0.2.2 — Bluetooth AudioRecord Bridge, Zero-Latency Pre-Warming & Diagnostics

`versionCode 41` — Major Audio & Hardware Release: Native `AudioRecordBridge` communication pipeline, zero-latency Bluetooth pre-warming handshake, dynamic headset visual indicators, real-time microphone diagnostics & sound test meter, poison-tolerant concurrency hardening, and 100% i18n parity.

### 🌟 Key Changes in v0.2.2

- **Dedicated Native `AudioRecordBridge` Communication Pipeline:** Direct high-performance Java `AudioRecord` bridge operating in `VOICE_COMMUNICATION` mode (16 kHz, 16-bit mono PCM). Leverages direct `ByteBuffer` allocation and native JNI streaming (`pushAudioDirect`) directly into the Rust ASR engine, forcing active microphone capture on wireless Bluetooth earbuds (AirPods, Galaxy Buds, Pixel Buds, Sony, etc.) and headset SCO/BLE devices.
- **Zero-Latency Bluetooth Pre-Warming Handshake:** Automatically pre-warms and establishes the audio communication pipe in the background the moment the on-screen keyboard (`RustInputMethodService`) or floating overlay bubble (`FloatingOverlayService`) appears on screen. Eliminates the initial 300–500 ms Bluetooth connection handshake latency when the user taps record.
- **Dynamic Device Indicators (🎧 / 🎙️):** Visual real-time indicator on both the keyboard record button and the floating dictation bubble. Dynamically switches to 🎧 (`ic_headset`) when Bluetooth headset input is active, and 🎙️ (`ic_mic`) when recording via the internal microphone.
- **Interactive Microphone Diagnostics & Real-Time Sound Test:** Added a comprehensive sound test section in `MainActivity` Settings. Features a live RMS decibel audio level meter (`MicLevelView`), active recording device label, and one-tap test start/stop to verify microphone levels and active routing before dictating.
- **Multi-Agent Robustness & Concurrency Hardening (Victory Audit):**
  - Eliminated memory leaks and ensured bounded direct `ByteBuffer` reuse.
  - Poison-tolerant mutex guards in Rust and atomic session generation validation to prevent race conditions during rapid start/stop cycles.
  - Safe, idempotent JNI pointer cleanup (`cleanupNative`) and lifecycle teardowns across all services and activities.
  - Comprehensive unit test suite `AudioRecordBridgeTest.java` (109/109 JVM tests passed).
- **100% i18n Parity (266 Strings across 6 Locales):** Complete translation coverage across English, German, Spanish, French, Italian, Portuguese, and Russian.

---

### 📦 Assets

- `Aura_Transcribe_v0.2.2.apk` (Release APK with bundled Nemotron 3.5 ASR Streaming 0.6B Q8_0 model)
