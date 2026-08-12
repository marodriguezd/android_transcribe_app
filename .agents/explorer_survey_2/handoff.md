# Architectural Handoff Report — Explorer Survey 2: Accessibility Service & Auto-Paste Architecture

## 1. Observation

### Existing Project Structure & Manifest
- **Manifest File**: `/root/GitHub/android_transcribe_app/app/src/main/AndroidManifest.xml`
  - Current services declared in manifest:
    - `LiveSubtitleService` (lines 125-128, `foregroundServiceType="mediaProjection"`)
    - `VoiceRecognitionService` (lines 134-145, `permission="android.permission.BIND_RECOGNITION_SERVICE"`)
    - `RustInputMethodService` (lines 148-160, `permission="android.permission.BIND_INPUT_METHOD"`, `android:process=":ime"`)
  - No AccessibilityService is currently declared in `AndroidManifest.xml`.
  - Permissions currently in manifest (lines 9-17): `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `READ_USER_DICTIONARY`.
- **Build & Test Configurations**: `/root/GitHub/android_transcribe_app/app/build.gradle.kts`
  - Target SDK: 34, Min SDK: 26, Compile SDK: 34.
  - JVM Unit Test harness (lines 123-134): `isIncludeAndroidResources = true`, `isReturnDefaultValues = true`.
  - Test framework: JUnit 4 (`junit:junit:4.13.2`), OkHttp MockWebServer (`4.12.0`), `org.json` (lines 173-182).
- **String Localizations**:
  - Locales supported: 7 locales (`values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`).
  - Validation script: `python3 scripts/check_translations.py` checks parity of all string keys across all 7 locales.

---

## 2. Logic Chain

### 2.1 AccessibilityService Implementation Strategy & Focused Node Tracking
- **Lifecycle & Base Class**:
  - `FloatingDictationAccessibilityService` must extend `android.accessibilityservice.AccessibilityService`.
  - Maintain a static volatile reference `sInstance` assigned in `onServiceConnected()` and cleared (`sInstance = null`) in `onDestroy()` and `onUnbind()`.
  - Expose static methods `getInstance()` and `isEnabled()` to query service status safely from any component in the same process.
- **Node Focus Tracking Mechanism**:
  - System Accessibility events to monitor:
    - `TYPE_VIEW_FOCUSED` (`0x00000008`): Fired when input focus shifts to an `EditText` or focusable view.
    - `TYPE_VIEW_CLICKED` (`0x00000001`): Fired when a user taps an input field.
    - `TYPE_VIEW_TEXT_SELECTION_CHANGED` (`0x00002000`): Fired on cursor/selection repositioning.
    - `TYPE_WINDOW_STATE_CHANGED` (`0x00000020`): Fired on window/app switches.
  - **Dynamic Focus Query Strategy**:
    - Rather than caching an `AccessibilityNodeInfo` handle across calls (which leads to IPC handle leaks or stale node crashes), the service should dynamically retrieve the focused node at insertion time via `getRootInActiveWindow().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`.
    - If `findFocus(FOCUS_INPUT)` returns `null`, fall back to checking the last event source node obtained from `onAccessibilityEvent(event)`.
  - **Editable Node Verification Filter**:
    - A node is valid for insertion if and only if:
      - `node != null`
      - `node.isEditable() == true`
      - `node.isEnabled() == true`
      - `node.isVisibleToUser() == true`
    - Node handles must always be recycled in a `finally` block via `node.recycle()` to avoid leaking system handles.

### 2.2 Text Insertion Methods & Fallback Strategy
- **Priority 1: `ACTION_PASTE` (`AccessibilityNodeInfo.ACTION_PASTE`)**:
  - How it works: Writes `transcribedText` to system clipboard (`ClipboardManager.setPrimaryClip()`) and triggers `node.performAction(AccessibilityNodeInfo.ACTION_PASTE)`.
  - Advantages: Inserts text directly at the current cursor position or replaces active text selection; respects application undo stack and input filters.
- **Priority 2: `ACTION_SET_TEXT` (`AccessibilityNodeInfo.ACTION_SET_TEXT`)**:
  - How it works: Constructs `Bundle` with key `AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` containing `transcribedText` and calls `node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)`.
  - Usage scenario: Used if `ACTION_PASTE` is not supported by the target node or returns `false`.
  - Caveat: Replaces the full text content of the target view.
- **Priority 3: Fallback Clipboard Copy**:
  - How it works: Triggered when `FloatingDictationAccessibilityService` is not enabled, no focused editable node is available, or both accessibility actions fail.
  - Copies `transcribedText` to `ClipboardManager` and issues user notification ("Text copied to clipboard. Paste manually.").

### 2.3 AndroidManifest.xml & Metadata Resource Specifications
- **Manifest Entry**:
  ```xml
  <service
      android:name=".FloatingDictationAccessibilityService"
      android:label="@string/accessibility_service_label"
      android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
      android:exported="true">
      <intent-filter>
          <action android:name="android.accessibilityservice.AccessibilityService" />
      </intent-filter>
      <meta-data
          android:name="android.accessibilityservice"
          android:resource="@xml/accessibility_service_config" />
  </service>
  ```
- **Configuration Resource File**: `app/src/main/res/xml/accessibility_service_config.xml`
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
      android:accessibilityEventTypes="typeViewFocused|typeViewClicked|typeViewTextSelectionChanged|typeWindowStateChanged"
      android:accessibilityFeedbackType="feedbackGeneric"
      android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
      android:canRetrieveWindowContent="true"
      android:description="@string/accessibility_service_description"
      android:notificationTimeout="100" />
  ```
- **Translation Parity Requirement**:
  - Declare `accessibility_service_label` and `accessibility_service_description` in `values/strings.xml` and mirror across all 6 alternative locale files (`values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`) to pass `scripts/check_translations.py`.

### 2.4 Inter-Service Communication (`FloatingDictationAccessibilityService` ↔ `FloatingOverlayService`)
- Both `FloatingOverlayService` and `FloatingDictationAccessibilityService` run in the main application process (`dev.notune.transcribe`).
- Communication mechanism: In-process static method call.
  ```java
  public static boolean pasteText(Context context, CharSequence text) {
      FloatingDictationAccessibilityService service = sInstance;
      if (service != null && service.performInsert(text)) {
          return true;
      }
      return copyToClipboardFallback(context, text);
  }
  ```
- `FloatingOverlayService` calls `FloatingDictationAccessibilityService.pasteText(this, text)` when the user taps "Insert" / "Pegar" or auto-paste triggers.

### 2.5 JVM Unit Testing Strategy without Live Framework
- Direct testing of `AccessibilityService` or `AccessibilityNodeInfo` methods in standard JVM unit tests can throw unmocked errors or return dummy default values.
- Strategy: Decouple node insertion decision logic into a clean helper class (`AccessibilityInsertionHelper` or `NodeActionAdapter`).
- Define interface `NodeActionAdapter`:
  - `boolean isEditable()`
  - `boolean supportsAction(int actionId)`
  - `boolean performAction(int actionId, Bundle args)`
- Test cases in `./gradlew testDebugUnitTest`:
  1. `testPasteActionAttemptedFirstWhenSupported`: Verifies `ACTION_PASTE` is performed first.
  2. `testFallbackToSetTextWhenPasteUnsupported`: Verifies `ACTION_SET_TEXT` is performed if `ACTION_PASTE` fails or is unsupported.
  3. `testFallbackToClipboardWhenNodeNullOrUneditable`: Verifies fallback clipboard write when node is missing or uneditable.
  4. `testNodeRecycling`: Asserts node handle recycling in `finally` blocks.

---

## 3. Caveats
- **Custom App Input Views**: Certain applications (e.g. WebViews, Flutter, custom canvas code editors) may not declare standard `AccessibilityNodeInfo` attributes (`isEditable() == true`) or respond to `ACTION_PASTE`/`ACTION_SET_TEXT`. In such cases, the fallback clipboard copy ensures text is never lost.
- **Android OS Security Policies**: Google Play Store policy requires explicit user disclosure regarding why an app requests `BIND_ACCESSIBILITY_SERVICE`. The `android:description` string must clearly explain that accessibility features are strictly used for voice text insertion into focused text fields.
- **Process Boundaries**: `RustInputMethodService` runs in process `:ime`. `FloatingOverlayService` and `FloatingDictationAccessibilityService` run in the default main process. Inter-service calls between overlay and accessibility services are in-process and fast.

---

## 4. Conclusion
The proposed architecture for `FloatingDictationAccessibilityService` is robust, lightweight, and fully integrated with existing project standards:
1. `FloatingDictationAccessibilityService` manages singleton reference and dynamic node focus querying.
2. Direct insertion prioritizes `ACTION_PASTE` (at cursor) with fallback to `ACTION_SET_TEXT` and final fallback to clipboard.
3. XML and Manifest configurations follow Android SDK standard specifications with full 7-locale string localization.
4. Inter-service integration uses direct static method access within the main application process.
5. Logic is fully testable under JVM `./gradlew testDebugUnitTest` via adapter abstraction.

---

## 5. Verification Method

### 1. Build & Test Verification
Run local JVM unit tests:
```bash
./gradlew testDebugUnitTest
```
*Expected Result*: All 34 existing unit tests pass cleanly, plus new accessibility unit tests pass with 0 failures.

### 2. Localization Verification
Run translation checker script:
```bash
python3 scripts/check_translations.py
```
*Expected Result*: Output reports all keys present across all 7 locales (`values`, `values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`).

### 3. File Verification
Inspect created/modified files:
- `/root/GitHub/android_transcribe_app/app/src/main/java/dev/notune/transcribe/FloatingDictationAccessibilityService.java`
- `/root/GitHub/android_transcribe_app/app/src/main/res/xml/accessibility_service_config.xml`
- `/root/GitHub/android_transcribe_app/app/src/main/AndroidManifest.xml`
- `/root/GitHub/android_transcribe_app/app/src/test/java/dev/notune/transcribe/AccessibilityInsertionTest.java`
