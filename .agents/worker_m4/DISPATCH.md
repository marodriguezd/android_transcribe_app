## 2026-08-12T11:30:27Z

You are Worker M4 (FloatingOverlayService & Test Fix Implementer).
Your working directory is `/root/GitHub/android_transcribe_app/.agents/worker_m4`.

MANDATORY INPUT FILES TO READ VERBATIM BEFORE IMPLEMENTING:
1. `/root/GitHub/android_transcribe_app/ORIGINAL_REQUEST.md`
2. `/root/GitHub/android_transcribe_app/PROJECT.md`
3. `/root/GitHub/android_transcribe_app/.agents/explorer_m4_2/handoff.md`
4. `/root/GitHub/android_transcribe_app/.agents/explorer_m4_3/handoff.md`
5. `/root/GitHub/android_transcribe_app/.agents/auditor_m2_m3/handoff.md`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

YOUR SPECIFIC TASKS:
1. **Implement `app/src/main/java/dev/notune/transcribe/FloatingOverlayService.java`**:
   - Foreground service extending `Service` managing WindowManager `TYPE_APPLICATION_OVERLAY` view layout.
   - Floating bubble icon layout with drag gesture vs tap touch listener using touch slop threshold.
   - Expanded status panel view (status label, cancel button, AI Fix toggle switch linked to `SettingsManager` marker `pp_enabled`, live streaming partial text view, paste button invoking `FloatingDictationAccessibilityService.pasteText(getApplicationContext(), text)`).
   - Declare native JNI methods matching `src/floating.rs`: `initNative(service)`, `cleanupNative()`, `startRecording(autoStop, sessionId)`, `stopRecording()`, `cancelRecording()`.
   - Implement all 7 public JNI callback methods: `onStatusUpdate(String, int)`, `onStatusUpdate(String)`, `onAudioLevel(float, int)`, `onAudioLevel(float)`, `onPartialText(String, int)`, `onTextTranscribed(String, int)`, `onAutoStop(int)`.
   - Dispatch all JNI callbacks to the main thread via `Handler(Looper.getMainLooper())` and validate `sessionId == currentSessionId`.
   - Implement AI post-processing integration when `SettingsManager.isPostProcessEnabled()` is true via `PostProcessor`.
2. **Register Service in `app/src/main/AndroidManifest.xml`**:
   - Add `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`.
   - Declare `<service android:name=".FloatingOverlayService" android:enabled="true" android:exported="false" />`.
3. **Fix Unit Test Assertion in `app/src/test/java/dev/notune/transcribe/PostProcessorTest.java`**:
   - At lines 321-322, update assertion check so that `"err:Network error: Read timed out"` passes:
     `assertTrue("expected socket timeout message, got " + outcome.get(), outcome.get().toLowerCase().contains("timed out") || outcome.get().toLowerCase().contains("timeout"));`
4. **Create JVM Unit Test Suite `app/src/test/java/dev/notune/transcribe/FloatingOverlayStateTest.java`**:
   - Test session ID stale callback filtering, state transitions, and `"pp_enabled"` marker toggle persistence.
5. **Add Localized Strings across 7 Locales**:
   - Add all 14 floating overlay & accessibility string resources in `app/src/main/res/values/strings.xml` AND all 6 non-base locale directories (`values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`).
6. **Run Verification Commands & Report**:
   - Run `python3 scripts/check_translations.py`
   - Run `./gradlew testDebugUnitTest`
   - Document exact output and write handoff report to `.agents/worker_m4/handoff.md`. Send a message when complete.
