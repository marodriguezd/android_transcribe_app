# DISPATCH — Worker Milestone 3 (Accessibility Service & Manifest Config)

## Identity
- Role: Worker Milestone 3
- Working directory: /root/GitHub/android_transcribe_app/.agents/worker_m3

## Exclusive File Boundaries
You exclusively own and may modify ONLY these files:
- `/root/GitHub/android_transcribe_app/app/src/main/java/dev/notune/transcribe/FloatingDictationAccessibilityService.java`
- `/root/GitHub/android_transcribe_app/app/src/main/res/xml/accessibility_service_config.xml`
- `/root/GitHub/android_transcribe_app/app/src/main/AndroidManifest.xml`

Do NOT touch any other files.

## Task Description
Implement Android Accessibility Service auto-paste integration & AndroidManifest configuration:
1. Create `FloatingDictationAccessibilityService.java` extending `AccessibilityService`:
   - Maintain static `sInstance` singleton set on `onServiceConnected()` and cleared on `onDestroy()` / `onUnbind()`.
   - Expose static methods `getInstance()`, `isEnabled()`, and `pasteText(Context context, CharSequence text)`.
   - Implement `onAccessibilityEvent` tracking input focus events (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_CLICKED`).
   - Implement tiered text insertion (`performInsert(CharSequence text)`):
     - Priority 1: `ACTION_PASTE` via clipboard to insert at current cursor position or replace active selection.
     - Priority 2: `ACTION_SET_TEXT` via `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` for direct text replacement.
     - Priority 3: Fallback clipboard write + Toast notification ("Text copied to clipboard").
     - Recycles `AccessibilityNodeInfo` handles safely in `finally` blocks.
2. Create `app/src/main/res/xml/accessibility_service_config.xml` with `canRetrieveWindowContent="true"`, `accessibilityEventTypes="typeViewFocused|typeViewClicked|typeViewTextSelectionChanged|typeWindowStateChanged"`, `accessibilityFeedbackType="feedbackGeneric"`, `accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"`, and `description="@string/floating_accessibility_service_desc"`.
3. Register `FloatingDictationAccessibilityService` in `AndroidManifest.xml` with `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, intent filter for `android.accessibilityservice.AccessibilityService`, and meta-data pointing to `@xml/accessibility_service_config`.

## Mandated Integrity Prompt
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Reference Files
- `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md`
- `/root/GitHub/android_transcribe_app/PROJECT.md`
- `/root/GitHub/android_transcribe_app/AGENTS.md`
- `/root/GitHub/android_transcribe_app/.agents/explorer_survey_2/handoff.md`
- `/root/GitHub/android_transcribe_app/app/src/main/AndroidManifest.xml`

## Output Requirements

## 2026-08-12T11:14:13Z
Task received for Worker Milestone 3: Accessibility Service & Manifest Config.

