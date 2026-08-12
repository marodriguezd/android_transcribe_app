# DISPATCH — Explorer Survey 1

## Identity
- Role: Explorer Survey 1 (JNI & Audio Session Architecture)
- Working directory: /root/GitHub/android_transcribe_app/.agents/explorer_survey_1

## Task Description
Investigate existing JNI & Audio Session surfaces in `android_transcribe_app` (`RecognizeActivity`, `RustInputMethodService`, `VoiceRecognitionService`, `LiveSubtitleService`, `src/voice_session.rs`, `src/recognize.rs`, `src/ime.rs`, `src/lib.rs`, `src/engine.rs`).
Map how native Rust audio capture and engine callbacks (`onStatusUpdate`, `onTextTranscribed`, `onPartialText`, `onAudioLevel`, `onAutoStop`) interact with Java Services/Activities.
Determine the exact JNI bridge and Java Service pattern required for the new floating overlay service to connect to native Rust ASR cleanly.

## Reference Files
- `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md`
- `/root/GitHub/android_transcribe_app/AGENTS.md`

## Output Requirements
Write handoff report to `/root/GitHub/android_transcribe_app/.agents/explorer_survey_1/handoff.md` detailing:
1. Existing JNI patterns & callback signatures
2. How `voice_session.rs` and `cpal::Stream` work across services
3. Recommended JNI bridge for `FloatingOverlayService`
4. Code layout and dependencies

## 2026-08-12T11:09:52Z
You are Explorer Survey 1.
Your working directory is `/root/GitHub/android_transcribe_app/.agents/explorer_survey_1`.

Read the instructions and dispatch details in `/root/GitHub/android_transcribe_app/.agents/explorer_survey_1/DISPATCH.md`.
Read `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md` and `/root/GitHub/android_transcribe_app/AGENTS.md`.

Your task:
Investigate existing JNI & Audio Session surfaces in `android_transcribe_app` (`RecognizeActivity`, `RustInputMethodService`, `VoiceRecognitionService`, `LiveSubtitleService`, `src/voice_session.rs`, `src/recognize.rs`, `src/ime.rs`, `src/lib.rs`, `src/engine.rs`).
Map how native Rust audio capture and engine callbacks (`onStatusUpdate`, `onTextTranscribed`, `onPartialText`, `onAudioLevel`, `onAutoStop`) interact with Java Services/Activities.
Determine the exact JNI bridge and Java Service pattern required for the new floating overlay service to connect to native Rust ASR cleanly.

Write your report to `/root/GitHub/android_transcribe_app/.agents/explorer_survey_1/handoff.md`.
Update your `progress.md` as you work. Send a message to parent when finished.
