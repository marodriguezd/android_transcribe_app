# Soft Handoff — Orchestrator Generation 1

## Milestone State
- **M1 (Branch Setup)**: DONE (Verified branch `feature/floating-bubble-dictation`).
- **M2 (Native JNI Floating Bridge)**: IMPLEMENTED (`src/floating.rs` & `src/lib.rs`), Gate check pending M4 integration.
- **M3 (Accessibility Service & Manifest Config)**: IMPLEMENTED (`FloatingDictationAccessibilityService.java`, `accessibility_service_config.xml`, `AndroidManifest.xml`), Gate check pending M4 integration.
- **M4 (Floating Overlay UI & JNI Wiring)**: PLANNED (Next focus for Successor).
- **M5 (i18n Translations & JVM Unit Tests)**: PLANNED.

## Gate Audit Findings (Iteration 2)
The Forensic Auditor reported INTEGRITY VIOLATION on M2/M3 gate check due to:
1. `FloatingOverlayService.java` not existing yet in Java (scheduled for M4), leaving `src/floating.rs` JNI symbols unlinked in Java.
2. `./gradlew testDebugUnitTest` failed 1 test (`PostProcessorTest > stalledResponseHitsReadTimeoutAndReportsError`) due to expected socket timeout message string mismatch.

## Remaining Work for Successor
1. **Execute Milestone M4**:
   - Dispatch Worker to create `app/src/main/java/dev/notune/transcribe/FloatingOverlayService.java` (foreground service with WindowManager `TYPE_APPLICATION_OVERLAY`, draggable bubble UI, expanded panel, cancel button, AI Fix toggle, live streaming text window, paste action calling `FloatingDictationAccessibilityService.pasteText`, JNI callbacks).
   - Register `FloatingOverlayService` in `AndroidManifest.xml` with `SYSTEM_ALERT_WINDOW` permission.
2. **Fix `PostProcessorTest`**:
   - Worker must update string match in `PostProcessorTest.java` so `./gradlew testDebugUnitTest` passes 100% (34/34 or 35/35 tests green).
3. **Re-run Gate Verification**:
   - Dispatch Reviewers, Challengers, and Forensic Auditor to verify M2, M3, M4.
4. **Execute Milestone M5**:
   - Dispatch Worker to add all 14 string resources across 7 locales (`values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`).
   - Create JVM unit test classes (`FloatingOverlayStateTest`, `FloatingSettingsMarkerTest`, `AccessibilityNodeHelperTest`).
   - Run `python3 scripts/check_translations.py` and `./gradlew testDebugUnitTest`.
5. **Final Gate & Completion Claim**:
   - Perform final audit and claim task completion to parent `ab5b97e6-a57a-4cd7-98ef-7c60ad0e057b`.

## Key Artifacts
- `/root/GitHub/android_transcribe_app/PROJECT.md` — Scope & milestones
- `/root/GitHub/android_transcribe_app/.agents/orchestrator/progress.md` — Progress log
- `/root/GitHub/android_transcribe_app/.agents/orchestrator/BRIEFING.md` — State briefing
- `/root/GitHub/android_transcribe_app/.agents/auditor_m2_m3/handoff.md` — Audit evidence
