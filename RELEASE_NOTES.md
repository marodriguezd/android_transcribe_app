# v0.1.24 — release hardening (2026-08-04)

`versionCode 26` — a focused hardening release for privacy, reliability and publication quality.

- **Privacy:** production builds no longer log transcripts, provider endpoints or post-processing error details.
- **AI post-processing:** final-only atomic delivery, owner-scoped cancellation, exact `${output}` handling and safe raw-text fallback.
- **Reliability:** generation-scoped subtitles, file-transcription operation IDs, atomic cross-process markers and safer IME/model-import lifecycles.
- **Models:** debug Nemotron downloads are SHA-256 verified before activation.
- **CI and release:** unified NDK 28.0.13004108, rustfmt/translations/JVM/checkModels gates, and a release workflow that verifies APK alignment and signatures before publishing.
- **Usability:** corrected prompt formatting, transcript de-duplication, localized strings and IME streaming layout.

Full historical release details are preserved in [`CHANGELOG.md`](CHANGELOG.md).
