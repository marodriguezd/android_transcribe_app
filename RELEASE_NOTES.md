# v0.1.25 — usability and diagnostics (2026-08-04)

`versionCode 27` — a focused follow-up to v0.1.24 that makes transcription easier to control, model downloads easier to follow, and post-processing failures easier to understand.

- **Transcription controls:** cancel an in-progress voice, keyboard, or file transcription without waiting for it to finish.
- **Model downloads:** debug builds now show download progress instead of appearing idle while fetching the speech model.
- **AI post-processing:** clearer diagnostics and error details make provider or configuration problems easier to troubleshoot, while preserving the safe raw-transcript fallback.
- **Stability and usability:** localized UI updates and small lifecycle improvements across the affected surfaces.

Full historical release details are preserved in [`CHANGELOG.md`](CHANGELOG.md).

---

# v0.1.24 — release hardening (2026-08-04)

`versionCode 26` — a focused hardening release for privacy, reliability and publication quality.

- **Privacy:** production builds no longer log transcripts, provider endpoints or post-processing error details.
- **AI post-processing:** final-only atomic delivery, owner-scoped cancellation, exact `${output}` handling and safe raw-text fallback.
- **Reliability:** generation-scoped subtitles, file-transcription operation IDs, atomic cross-process markers and safer IME/model-import lifecycles.
- **Models:** debug Nemotron downloads are SHA-256 verified before activation.
- **CI and release:** unified NDK 28.0.13004108, rustfmt/translations/JVM/checkModels gates, and a release workflow that verifies APK alignment and signatures before publishing.
- **Usability:** corrected prompt formatting, transcript de-duplication, localized strings and IME streaming layout.

Full historical release details are preserved in [`CHANGELOG.md`](CHANGELOG.md).
