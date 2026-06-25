## 2026-06-25T16:55:06Z
Investigate the Offline Voice Input Android codebase. Analyze where and how requirements R1 to R5 apply. Specifically, identify:
1. The location of the ONNX model files and where/how they are currently loaded, copied, or stored.
2. The AndroidManifest.xml and details about the process configurations (e.g., `:ime` process) and the `GLOBAL_ENGINE` initialization.
3. The JNI interface, the real-time CPAL audio thread callback, and the JNI calls made from it.
4. The CPAL microphone recording setup and format query code in Rust/C++, and the resampler implementation in `TranscribeFileActivity` (downsampling to 16kHz).
5. The UI components: `MicLevelView` (ValueAnimator usages), settings flags usage (auto_record, select_transcription, pause_audio) and their file storage, and the Live Subtitles service speech detection/timeout mechanism.
6. The exact build and test commands that should be used to build and verify the application.

Write your findings to `/home/marodriguezd/Github/android_transcribe_app/.agents/teamwork_preview_explorer_init/analysis.md`.
Your working directory is `/home/marodriguezd/Github/android_transcribe_app/.agents/teamwork_preview_explorer_init/`.
Your parent is 9fe6abb1-b74e-46e9-9657-b431507526a2. Use this ID for communication.
