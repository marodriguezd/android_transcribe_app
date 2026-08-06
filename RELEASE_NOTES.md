# v0.1.28 — security and robustness hardening (2026-08-06)

`versionCode 30` — hardens release signing, backup privacy and memory bounds; fixes a subtitle teardown crash; makes the Ollama local preset work again; and trims hot-path allocations.

- **Release signing:** local release builds now fail fast when the keystore exists but a signing env var is missing — the app never signs with default credentials, so an APK cannot be republished by someone who obtained it.
- **Backup privacy:** app data is excluded from Android Auto Backup (`allowBackup=false`), so the post-processing API key and the imported speech models (up to ~750 MB) no longer leave the device in a cloud backup.
- **Memory bounds:** voice recordings are hard-capped at 5 minutes (auto-stop commits the captured audio) and file transcription at 60 minutes, so a forgotten recording or an oversized shared file can no longer grow until OOM.
- **Local LLM preset:** the "Ollama (local)" provider works again on Android 9+ — cleartext is allowed for localhost only, everything else stays HTTPS-only.
- **Crash fix:** stopping live subtitles no longer crashes when audio capture failed to initialize (uninitialized `AudioRecord.stop()`).
- **Clipboard privacy:** file transcription no longer auto-copies the transcript to the shared system clipboard; use the copy button.
- **Robustness:** model loading is panic-safe (a failed load can always retry instead of freezing on "Waiting for model…"); bundled-asset extraction verifies file sizes and reports missing assets; custom-words sync uses atomic marker writes and drops stale words when the system dictionary is emptied.
- **Performance:** audio callbacks attach to the JVM once per stream instead of ~20 times per second; quietest split-point search is O(n) via a sliding window; the phonetic corrector precomputes lowercase terms and skips impossible candidates; the mic-level glow reuses a single animator; settings migration moved off the UI thread.

# v0.1.27 — live-subtitle translation and context (2026-08-05)

`versionCode 29` — improves live subtitles with optional on-device translation and a larger default caption context window.

Adds on-device translation of live subtitles, targeting a language you choose.

- **Subtitle translation target:** new picker in the Live Subtitles card. "Auto (original language)" keeps the spoken language — the ASR already detects it; picking a language (ES/EN/FR/DE/IT/PT/RU) translates the finalized captions on-device via ML Kit (language packs download through Google Play Services on first use; the translation itself runs locally and works offline afterwards).
- **Ordered, failure-safe captions:** segments show immediately in the original language and swap to the translation as it lands; translations are applied strictly in order, stale sessions are dropped by generation, and any failure (no Play Services, no pack, slow/failing translation) keeps the original text on screen.
- **ASR isolation:** live subtitles always run `Task::Transcribe` in the engine, so the global "translate to English" model switch can never translate captions behind the user's back.
- **Auto source detection:** with an automatic model language, the source is detected from the text itself (Chinese/Japanese/Korean/Russian scripts plus a conservative Latin heuristic); for reliable translation of Latin-script speech, set the language in Speech models.
- **Four-line default context:** live subtitles now show four lines by default, so the original-language phrase provides more context before the finalized caption is translated.

# v0.1.26 — release post-processing fix (2026-08-04)

`versionCode 28` — fixes post-processing after upgrading from releases that stored the API key in encrypted preferences.

- **API-key migration:** recovers legacy encrypted API keys into the cross-process marker store when possible.
- **Safe fallback:** if Android cannot recover the old key, refinement fails immediately with a clear instruction instead of sending an unauthenticated request; the raw transcript is preserved.
- **Settings diagnostics:** missing API keys are highlighted and `/models`/connection tests no longer run without authentication.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
