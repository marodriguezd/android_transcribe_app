# v0.1.29 — cancel-anytime keyboard dictation (2026-08-06)

`versionCode 31` — the IME Cancel action is now available from the very start of a voice dictation, so a capture can be discarded before it is transcribed and before any post-processing request is sent.

- **Cancel during recording:** the Cancel button appears as soon as recording starts, not only after it finishes. Cancelling mid-capture discards the audio buffer natively and cancels any in-flight post-processing call, so no transcription is produced and **no LLM request is ever made (or billed)** for text you cancel.
- **No flicker:** the button stays continuously visible from mic release through transcription and refinement, until the text is committed or cancelled — the previous stop→transcribe→refine transitions no longer make it blink in and out.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
