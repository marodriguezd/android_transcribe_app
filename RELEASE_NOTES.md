# v0.1.26 — release post-processing fix (2026-08-04)

`versionCode 28` — fixes post-processing after upgrading from releases that stored the API key in encrypted preferences.

- **API-key migration:** recovers legacy encrypted API keys into the cross-process marker store when possible.
- **Safe fallback:** if Android cannot recover the old key, refinement fails immediately with a clear instruction instead of sending an unauthenticated request; the raw transcript is preserved.
- **Settings diagnostics:** missing API keys are highlighted and `/models`/connection tests no longer run without authentication.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
