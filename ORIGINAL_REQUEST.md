# Original User Request

## Initial Request — 2026-08-12T11:09:17Z

Implement a Whisperflow-style floating bubble dictation overlay with Android Accessibility Service auto-paste integration on branch feature/floating-bubble-dictation.

Working directory: /root/GitHub/android_transcribe_app
Integrity mode: development

## Requirements

### R1. Android Accessibility Service & Auto-Paste
Implement an AccessibilityService (`FloatingDictationAccessibilityService`) to track the currently focused text field across any application and perform direct text insertion (`ACTION_PASTE` / `ACTION_SET_TEXT`).

### R2. Floating Dictation Overlay (Whisperflow Style)
Implement a foreground service with WindowManager (`SYSTEM_ALERT_WINDOW`) displaying a draggable floating bubble icon. Tapping the bubble expands a compact overlay box with status ("Escuchando..."), Cancel button, AI Fix toggle, live streaming transcription window, and Insert/Paste action.

### R3. JNI & ASR Integration
Wire native Rust audio capture and engine callbacks (`voice_session` / `onTextTranscribed` / `onPartialText`) into the floating overlay service.

## Acceptance Criteria

### Functionality & Build
- [ ] `./gradlew testDebugUnitTest` passes cleanly with 0 failures.
- [ ] `check_translations.py` passes with all translatable strings present across 7 locales.
- [ ] `FloatingDictationAccessibilityService` registered in `AndroidManifest.xml` with `BIND_ACCESSIBILITY_SERVICE`.
- [ ] Floating overlay service handles `SYSTEM_ALERT_WINDOW`, dragging, state toggle, and auto-paste into focused node.
