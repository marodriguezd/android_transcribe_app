# DISPATCH — Explorer Survey 2

## 2026-08-12T09:09:53Z

## Identity
- Role: Explorer Survey 2 (Accessibility Service & Auto-Paste Architecture)
- Working directory: /root/GitHub/android_transcribe_app/.agents/explorer_survey_2

## Task Description
Investigate Android Accessibility Service architecture for `FloatingDictationAccessibilityService`.
Examine how to track focused text fields (`AccessibilityNodeInfo`, `onAccessibilityEvent`, `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_CLICKED`, `AccessibilityNodeInfoCompat`).
Examine direct text insertion options (`ACTION_PASTE`, `ACTION_SET_TEXT`, `PERFORM_ACTION`, `EXTRA_SET_TEXT`, bundle args) and fallback clipboard insertion.
Examine AndroidManifest.xml permissions (`BIND_ACCESSIBILITY_SERVICE`), metadata XML configuration in `res/xml/accessibility_service_config.xml`, and security/lifecycle considerations.
Examine how `FloatingDictationAccessibilityService` can expose static or singleton access / callback / intent mechanism to `FloatingOverlayService`.

## Reference Files
- `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md`
- `/root/GitHub/android_transcribe_app/AGENTS.md`

## Output Requirements
Write handoff report to `/root/GitHub/android_transcribe_app/.agents/explorer_survey_2/handoff.md` detailing:
1. AccessibilityService implementation strategy and node focus tracking
2. Text insertion methods (SET_TEXT vs PASTE vs Clipboard fallback)
3. Manifest and XML resource metadata specifications
4. Inter-service communication with `FloatingOverlayService`
5. Unit testing strategy for Accessibility logic without live system framework

