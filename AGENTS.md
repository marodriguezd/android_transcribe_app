# Project: Offline Voice Input (android_transcribe_app)

## Architecture

Rust + JNI Android app for offline speech-to-text using NVIDIA Parakeet models.

- **Java UI**: Activities/Services in `app/src/main/java/dev/notune/transcribe/`
- **Rust JNI layer**: `src/` — bridges Java ↔ transcribe-rs
- **transcribe-rs**: ONNX Runtime based Parakeet inference

## Model variants

| Variant | Size | Files | Engine |
|---------|------|-------|--------|
| 0.6B (fast) | ~640 MB | `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `nemo128.onnx`, `vocab.txt` | `ParakeetEngine` (engine.rs in transcribe-rs) |
| 1.1B (precise) | ~3.8 GB | `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` | `Parakeet1_1bModel` (model_1_1b.rs) with Rust mel extraction |

## Model loading

Models are stored in `getFilesDir()/models/parakeet-tdt-{variant}-v3-int8/`. No longer bundled in APK assets — must be downloaded via `ModelDownloadManager` from Hugging Face.

## Key files

| File | Purpose |
|------|---------|
| `MainActivity.java` | Main UI, model selection RadioGroup |
| `SettingsManager.java` | SharedPreferences for model_variant |
| `App.java` | Application class, download manager singleton |
| `ModelDownloadManager.java` | Downloads model files |
| `ModelDownloadForegroundService.java` | Foreground service for download |
| `src/engine.rs` | Global engine singleton, model loading/switching |
| `src/main_activity.rs` | JNI bridge for initNative/switchModel |
| `transcribe-rs/src/engines/parakeet/model_1_1b.rs` | 1.1B model inference |

## Important patterns

- Engine singleton: `GLOBAL_ENGINE` (`Lazy<Mutex<Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)>>>`)
- Loading coordination: `LOAD_STATE` mutex + Condvar to serialize loads
- Model switching: `switch_model()` first acquires LOAD_STATE lock, then clears engine
- ORT providers on Android: NNAPI, XNNPACK, CPU (in priority order)
- JNI `ensure_loaded_from_thread` and `switch_model` both coordinate via LOAD_STATE

## Common pitfalls

- `rbModelFast` may be null if `setupModelSelection` hasn't run
- Download callbacks should use `WeakReference<MainActivity>` with lifecycle checks
- `onCheckedChangeListener` must guard against `checkedId == -1` (RadioGroup cleared state)
- Model files moved from APK assets to internal storage in v0.7.0
