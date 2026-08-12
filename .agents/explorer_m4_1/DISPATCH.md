## 2026-08-12T09:26:46Z
<USER_REQUEST>
You are Explorer 1 (FloatingOverlayService UI & WindowManager Specialist).
Your working directory is `/root/GitHub/android_transcribe_app/.agents/explorer_m4_1`.

MANDATORY INPUT FILES TO READ VERBATIM BEFORE STARTING:
1. `/root/GitHub/android_transcribe_app/ORIGINAL_REQUEST.md`
2. `/root/GitHub/android_transcribe_app/PROJECT.md`
3. `/root/GitHub/android_transcribe_app/.agents/auditor_m2_m3/handoff.md`

FULL FORENSIC AUDITOR EVIDENCE REPORT FROM PREVIOUS ITERATION:
```
# Handoff Report — Forensic Auditor Milestones M2 & M3

## Forensic Audit Report

**Work Product**: Milestones M2 & M3 Implementation (Floating Dictation Overlay & Accessibility Auto-Paste)  
**Profile**: General Project  
**Integrity Mode**: Development (from `ORIGINAL_REQUEST.md`)  
**Verdict**: **INTEGRITY VIOLATION**

### Phase Results
- **Hardcoded test result check**: PASS — No hardcoded test results or mock return values found in source code.
- **Facade & Missing implementation detection**: **FAIL** — `src/floating.rs` contains JNI C functions declared for `Java_dev_notune_transcribe_FloatingOverlayService_*`, but `FloatingOverlayService.java` does NOT exist anywhere in `app/src/main/java/` or in `AndroidManifest.xml`. Requirement R2 / Milestone M2 (foreground overlay service, WindowManager `SYSTEM_ALERT_WINDOW`, draggable bubble UI, streaming text view, status panel, AI Fix toggle, cancel/insert actions) is completely unimplemented in Java.
- **Pre-populated artifact detection**: PASS — No pre-populated log files, fake results, or pre-built attestations exist in workspace.
- **Build and test execution**: **FAIL** — `./gradlew testDebugUnitTest` failed with 1 failing unit test (`PostProcessorTest > stalledResponseHitsReadTimeoutAndReportsError`).
- **Translation verification**: PASS — `python3 scripts/check_translations.py` executed cleanly with 192 translatable strings verified across all 6 locales.
- **Accessibility Service implementation (M3)**: PASS — `FloatingDictationAccessibilityService.java` and `accessibility_service_config.xml` exist, are registered in `AndroidManifest.xml` with `BIND_ACCESSIBILITY_SERVICE`, and genuinely implement focused node auto-pasting with `ACTION_PASTE` / `ACTION_SET_TEXT` / `ClipboardManager` fallback.

## 1. Observation
1. **Missing `FloatingOverlayService.java` & Orphaned JNI Bindings**:
   - File inspected: `src/floating.rs` (lines 12, 22, 30, 43, 54):
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_initNative(...)
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative(...)
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_startRecording(...)
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_stopRecording(...)
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording(...)
   - Command run: `grep -rn "FloatingOverlayService" /root/GitHub/android_transcribe_app`
     Result: Found ONLY in `src/floating.rs` and `.agents/` metadata directories. No Java class named `FloatingOverlayService` exists in `app/src/main/java/`.
   - File inspected: `app/src/main/AndroidManifest.xml` (lines 1-177):
     No service or component named `FloatingOverlayService` or managing `SYSTEM_ALERT_WINDOW` is declared.
2. **Failing Unit Test in `./gradlew testDebugUnitTest`**:
   - Command run: `./gradlew testDebugUnitTest`
   - Test log output (`TEST-dev.notune.transcribe.PostProcessorTest.xml` line 14):
     PostProcessorTest > stalledResponseHitsReadTimeoutAndReportsError FAILED
         java.lang.AssertionError: expected socket timeout message, got err:Network error: Read timed out
             at org.junit.Assert.fail(Assert.java:89)
             at org.junit.Assert.assertTrue(Assert.java:42)
             at dev.notune.transcribe.PostProcessorTest.stalledResponseHitsReadTimeoutAndReportsError(PostProcessorTest.java:321)
   - Test Summary: `71 tests completed, 1 failed. BUILD FAILED in 3m 6s`.
```

YOUR TASK:
Investigate and design the full Java UI & foreground service strategy for `FloatingOverlayService.java`:
1. WindowManager setup with `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `PixelFormat.TRANSLUCENT`.
2. Floating bubble icon design (draggable via `OnTouchListener` with slop threshold distinguishing drag vs tap).
3. Expanded overlay box UI (status view, cancel button, AI Fix toggle checkbox linked to `SettingsManager` marker `pp_enabled`, live streaming transcription text view, paste button invoking `FloatingDictationAccessibilityService.pasteText`).
4. Foreground Service notification setup for Android O+ compatibility (`startForeground`, NotificationChannel).
5. Manifest declaration in `app/src/main/AndroidManifest.xml` with `SYSTEM_ALERT_WINDOW` permission and service entry.

DO NOT write source code or run build tools. Write your comprehensive findings and implementation strategy to `.agents/explorer_m4_1/handoff.md`. Send a message when done.
</USER_REQUEST>
