# Release Notes: High-Performance Offline Transcription (v0.3.0)

## 🚀 Speed & Hardware Acceleration

This release focuses on a complete overhaul of the inference engine to unlock the full potential of your Android device's hardware.

- **Hardware Acceleration (NNAPI & XNNPACK):** Now leveraging Android's Neural Networks API (NNAPI) and XNNPACK for significantly faster on-device transcription.
- **ONNX Runtime Upgrade (v1.25.0):** Updated to the latest industry-standard inference engine for improved stability and performance.
- **Optimized I/O Binding:** Implemented efficient memory management in the Rust engine, reducing data copying between CPU and GPU/NPU during recurrent decoding.
- **Enhanced Accuracy:** Refined Parakeet TDT token-splitting and timestamp logic for more precise word-level timing.

## 🛠 Improvements & Bug Fixes

- **Hybrid Type System:** Resolved "unexpected input data type" errors by implementing a tailored type system for the multi-stage inference pipeline (Preprocessor -> Encoder -> Decoder).
- **Large Audio Handling:** Improved stability when transcribing long recordings (over 1 minute) through better chunking management.
- **Build System Overhaul:** New `build.sh` script for easier environment setup and one-command APK generation.
- **Privacy Hardening:** Better exclusion of local agentic state from source control.

## 📦 Assets
- `android_transcribe_app_v0.3.0.apk`
- Source code (zip/tar.gz)

---
*Note: This update requires re-extraction of model assets on the first run to ensure compatibility with the new engine.*
