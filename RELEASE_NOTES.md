# v0.1.27 — live-subtitle translation (next)

Adds on-device translation of live subtitles, targeting a language you choose.

- **Subtitle translation target:** new picker in the Live Subtitles card. "Auto (original language)" keeps the spoken language — the ASR already detects it; picking a language (ES/EN/FR/DE/IT/PT/RU) translates the finalized captions on-device via ML Kit (language packs download through Google Play Services on first use; the translation itself runs locally and works offline afterwards).
- **Ordered, failure-safe captions:** segments show immediately in the original language and swap to the translation as it lands; translations are applied strictly in order, stale sessions are dropped by generation, and any failure (no Play Services, no pack, slow/failing translation) keeps the original text on screen.
- **ASR isolation:** live subtitles always run `Task::Transcribe` in the engine, so the global "translate to English" model switch can never translate captions behind the user's back.
- **Auto source detection:** with an automatic model language, the source is detected from the text itself (Chinese/Japanese/Korean/Russian scripts plus a conservative Latin heuristic); for reliable translation of Latin-script speech, set the language in Speech models.

# v0.1.26 — release post-processing fix (2026-08-04)

`versionCode 28` — fixes post-processing after upgrading from releases that stored the API key in encrypted preferences.

- **API-key migration:** recovers legacy encrypted API keys into the cross-process marker store when possible.
- **Safe fallback:** if Android cannot recover the old key, refinement fails immediately with a clear instruction instead of sending an unauthenticated request; the raw transcript is preserved.
- **Settings diagnostics:** missing API keys are highlighted and `/models`/connection tests no longer run without authentication.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
