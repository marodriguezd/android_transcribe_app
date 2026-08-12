# DISPATCH — Spec Miner Survey 3

## Identity
- Role: Spec Miner Survey 3 (Floating Overlay UI, i18n & Test Suite Requirements)
- Working directory: /root/GitHub/android_transcribe_app/.agents/spec_miner_survey_3

## Task Description
Investigate Floating Overlay UI requirements (Whisperflow Style), WindowManager overlay management (`TYPE_APPLICATION_OVERLAY`, `SYSTEM_ALERT_WINDOW`), touch handling for dragging bubble, state expansion/collapse, UI component requirements (status indicator, cancel button, AI Fix toggle marker integration, live streaming transcription window, insert/paste action button).
Map existing translation strings catalog across all 7 locales (`values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`) and `scripts/check_translations.py`.
Map existing JVM unit test suites (`app/src/test/java/...`) and how to run `./gradlew testDebugUnitTest`.

## Reference Files
- `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md`
- `/root/GitHub/android_transcribe_app/AGENTS.md`

## Output Requirements
Write handoff report to `/root/GitHub/android_transcribe_app/.agents/spec_miner_survey_3/handoff.md` detailing:
1. Floating overlay UI layout design, WindowManager layout params, touch listener for drag & click.
2. AI Fix toggle marker file binding (`filesDir() / "post_process"`, `PostProcessor.java`).
3. Complete inventory of new string resources needed in 7 locales to satisfy `check_translations.py`.
4. Unit testing plan for JVM unit tests (`testDebugUnitTest`).
5. Acceptance criteria verification roadmap.
