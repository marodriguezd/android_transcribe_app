# v0.1.31 — Whisperflow floating bubble dictation & Accessibility auto-paste (2026-08-12)

`versionCode 33` — introduces floating bubble dictation overlay and Android Accessibility Service auto-paste integration.

- **Floating Bubble Dictation (Whisperflow style):** Draggable floating bubble icon (`WindowManager` `TYPE_APPLICATION_OVERLAY`) that expands into a dictation control box. Displays real-time streaming hypotheses, status, Cancel button, AI Fix toggle, and Insert action.
- **Accessibility Auto-Paste:** `FloatingDictationAccessibilityService` tracks active input field focus across apps and performs direct text insertion (`ACTION_PASTE` / `ACTION_SET_TEXT`) with clipboard fallback.
- **Native JNI Floating Bridge:** Direct native audio capture and Rust engine callbacks connected to the floating overlay service.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
