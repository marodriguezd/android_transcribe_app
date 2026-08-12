# Project: Floating Bubble Dictation Overlay & Accessibility Auto-Paste
# Branch: feature/floating-bubble-dictation

## Architecture
- **Native Rust**: `src/floating.rs` JNI bridge registered in `src/lib.rs`, delegating session actions to `src/voice_session.rs`.
- **Java Services (Main Process)**:
  - `FloatingDictationAccessibilityService` extending `AccessibilityService`: tracks active input field focus (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_CLICKED`) via `getRootInActiveWindow().findFocus(FOCUS_INPUT)`, performs direct text insertion (`ACTION_PASTE`, `ACTION_SET_TEXT`), with fallback to ClipboardManager.
  - `FloatingOverlayService` extending `Service`: foreground service managing WindowManager overlay (`TYPE_APPLICATION_OVERLAY`), touch drag vs click gesture handling, collapsed bubble & expanded panel UI views, AI Fix toggle marker integration (`pp_enabled`), and native JNI voice callbacks.
- **Resources & Manifest**:
  - `app/src/main/res/xml/accessibility_service_config.xml` metadata resource file.
  - Service declarations in `AndroidManifest.xml` with `BIND_ACCESSIBILITY_SERVICE` and `SYSTEM_ALERT_WINDOW` permissions.
  - 14 string resources localized across 7 locales (`values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`).
- **Testing & Verification**:
  - JVM unit test classes in `app/src/test/java/dev/notune/transcribe/` (`FloatingOverlayStateTest`, `FloatingSettingsMarkerTest`, `AccessibilityNodeHelperTest`).
  - Validation commands: `./gradlew testDebugUnitTest` and `python3 scripts/check_translations.py`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Git Branch Setup | Ensure working branch is `feature/floating-bubble-dictation` | M1 | ORIGINAL_REQUEST §Acceptance |
| 2 | Rust JNI Floating Bridge | Add `src/floating.rs` & JNI exports for `FloatingOverlayService` in `src/lib.rs` | M2 | Explorer Survey 1 |
| 3 | Accessibility Service & Auto-Paste | Implement `FloatingDictationAccessibilityService`, node focus tracking, `ACTION_PASTE`/`ACTION_SET_TEXT` insertion, clipboard fallback | M3 | Explorer Survey 2 |
| 4 | Manifest & XML Config | Register AccessibilityService and overlay service, create `accessibility_service_config.xml` | M3 | Explorer Survey 2 |
| 5 | Floating Overlay UI Service | Implement `FloatingOverlayService`, WindowManager overlay (`TYPE_APPLICATION_OVERLAY`), drag vs tap touch handling, collapsed bubble & expanded panel views | M4 | Spec Miner Survey 3 |
| 6 | JNI & ASR Overlay Integration | Wire native voice callbacks (`onStatusUpdate`, `onAudioLevel`, `onPartialText`, `onTextTranscribed`, `onAutoStop`), session token validation, AI Fix toggle marker | M4 | Spec Miner Survey 3 |
| 7 | String Localizations (7 Locales) | Add all 14 new UI & Accessibility strings across 7 locales (`values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`) | M5 | Spec Miner Survey 3 |
| 8 | JVM Unit Test Suite | Implement unit tests for Accessibility insertion helper, overlay state, and marker settings; run `./gradlew testDebugUnitTest` and `check_translations.py` | M5 | Spec Miner Survey 3 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Branch Setup | Verify/checkout branch `feature/floating-bubble-dictation` | None | DONE |
| M2 | Native JNI Floating Bridge | Implement `src/floating.rs` & update `src/lib.rs` | M1 | IN_PROGRESS |
| M3 | Accessibility Service & Manifest Config | `FloatingDictationAccessibilityService`, `accessibility_service_config.xml`, `AndroidManifest.xml` | M1 | IN_PROGRESS |
| M4 | Floating Overlay UI & JNI Wiring | `FloatingOverlayService`, WindowManager overlay UI, drag/tap gestures, JNI callbacks, AI Fix marker | M2, M3 | PLANNED |
| M5 | i18n Translations & JVM Unit Tests | 14 strings across 7 locales, unit tests in `app/src/test/java/...`, `./gradlew testDebugUnitTest`, `check_translations.py` | M4 | PLANNED |

## Interface Contracts
### `FloatingOverlayService` ↔ `FloatingDictationAccessibilityService`
- `FloatingDictationAccessibilityService.pasteText(Context context, CharSequence text)`: returns `boolean` (true if pasted/copied successfully)
- Priority: `node.performAction(ACTION_PASTE)` -> `node.performAction(ACTION_SET_TEXT, args)` -> Clipboard fallback.
### `FloatingOverlayService` ↔ `src/floating.rs` (JNI)
- Methods: `initNative(service)`, `cleanupNative()`, `startRecording(autoStop, sessionId)`, `stopRecording()`, `cancelRecording()`
- Callbacks from JNI: `onStatusUpdate(String, int)`, `onAudioLevel(float, int)`, `onPartialText(String, int)`, `onTextTranscribed(String, int)`, `onAutoStop(int)`
### AI Fix Toggle Marker
- Marker file: `"pp_enabled"` in `context.getFilesDir()`
- Controlled via: `SettingsManager.isPostProcessEnabled(ctx)` and `SettingsManager.setPostProcessEnabled(ctx, val)`
