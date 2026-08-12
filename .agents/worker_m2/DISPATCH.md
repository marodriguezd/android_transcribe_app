# DISPATCH — Worker Milestone 2 (Native JNI Floating Bridge)

## Identity
- Role: Worker Milestone 2
- Working directory: /root/GitHub/android_transcribe_app/.agents/worker_m2

## Exclusive File Boundaries
You exclusively own and may modify ONLY these files:
- `/root/GitHub/android_transcribe_app/src/floating.rs`
- `/root/GitHub/android_transcribe_app/src/lib.rs`

Do NOT touch any other files.

## Task Description
Implement the native Rust JNI bridge for `FloatingOverlayService`:
1. Create `/root/GitHub/android_transcribe_app/src/floating.rs` containing static `FLOATING_STATE: Lazy<Mutex<Option<VoiceSessionState>>>` delegating session lifecycle calls to `voice_session.rs`.
2. Export the C-ABI JNI symbols targeting `dev.notune.transcribe.FloatingOverlayService`:
   - `Java_dev_notune_transcribe_FloatingOverlayService_initNative`
   - `Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative`
   - `Java_dev_notune_transcribe_FloatingOverlayService_startRecording`
   - `Java_dev_notune_transcribe_FloatingOverlayService_stopRecording`
   - `Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording`
3. Declare `pub mod floating;` in `/root/GitHub/android_transcribe_app/src/lib.rs`.

Follow existing JNI surface patterns established in `src/recognize.rs` and `src/ime.rs`.

## Mandated Integrity Prompt
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Reference Files
- `/root/GitHub/android_transcribe_app/.agents/ORIGINAL_REQUEST.md`
- `/root/GitHub/android_transcribe_app/PROJECT.md`
- `/root/GitHub/android_transcribe_app/AGENTS.md`
- `/root/GitHub/android_transcribe_app/.agents/explorer_survey_1/handoff.md`
- `/root/GitHub/android_transcribe_app/src/recognize.rs`
- `/root/GitHub/android_transcribe_app/src/ime.rs`

## Output Requirements
Write handoff report to `/root/GitHub/android_transcribe_app/.agents/worker_m2/handoff.md`.
