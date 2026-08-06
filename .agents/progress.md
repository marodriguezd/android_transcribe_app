# Progress — current AI-assisted work state

**Last update:** 2026-08-06 (full audit fixes implemented, awaiting CI validation via push)

## ⚠️ Working mode (2026-08-06, fire rule)

- This dev host is a mobile-device-like environment: **heavy local builds crash it**.
- ALL build/test validation now happens via GitHub Actions: `git push` to `main`
  triggers the debug workflow (fmt check, translations, JVM tests, `assembleDebug`,
  `lintDebug`, `checkModels` → APK to Telegram). Read results with `gh run list` /
  `gh run view`; iterate fix → push → read CI.
- Local is allowed on the maintainer's physical machine (laptop/desktop).
- Canonical rule: root `AGENTS.md` §3 "Regla de validación por entorno".

## 🟢 Completed — 2026-08-06 audit fix round (V1–V10, R1–R5, O1–O9) — CI green

Full-codebase security/optimization audit done (2026-08-06). All fixes
implemented and **CI-validated in run `31095555098` (commit `92e9da2`, push
to `main`)**: every gate passed — `cargo fmt --check`, translations,
`testDebugUnitTest`, `assembleDebug` (compiles the Rust via cargo-ndk),
`lintDebug`, `checkModels` — and the debug APK was sent to Telegram.

- **Security:** release signing fail-fast when env vars missing (V1);
  `allowBackup=false` (V2); audio-buffer session cap in `voice_session.rs`
  (V3); file-transcription duration cap (V4); `UserDictionaryHelper`→
  `MarkerFileHelper` atomic writes (V5); `stopSubtitleSession` guards (V6);
  `network_security_config.xml` for localhost/cleartext loopback (V7);
  benchmark transcript not logged in release (V8); `glEsVersion required=false`
  (V9); no auto-copy to clipboard (V10).
- **Robustness:** `catch_unwind` in `do_load` (R1); poisoned-mutex recovery in
  audio callbacks (R2); asset size checks + open-error propagation (R3/R4);
  stream-context telemetry after retry (R5).
- **Performance:** permanent thread attach in audio callbacks (O1); corrector
  precomputed lowercase/length filter/`Arc<Dictionary>` (O2); sliding-sum
  `find_quietest_split` (O3); single `ValueAnimator` in `MicLevelView` (O4);
  debug-gated periodic audioLoop log (O5); settings migration off UI thread
  (O6); 10 Hz rms throttle (O9).
- **Housekeeping:** version bump to 0.1.28 (`versionCode 30`), `RELEASE_NOTES.md`
  block, AGENTS.md rules (incl. §3 "Regla de validación por entorno"),
  7-locale strings.

**Remaining for release:** the v0.1.28 signed release build is not yet run — it
needs a `v0.1.28` tag (or manual dispatch), which also requires the
`KEYSTORE_BASE64`/`KEY_ALIAS`/`KEY_PASS`/`STORE_PASS` secrets in the repo.

## 🟢 Completed — 2026-08-06 review follow-up (LOW fixes) — CI green

Code-review of commit `92e9da2` (code-reviewer) found: a MEDIUM signing-fail-fast gap
(`assemble`/`build` not covered), a MEDIUM file-transcription cap that may OOM before
60 min on small heaps, a MEDIUM/LOW async-migration race in `App.java`, and two LOWs
that the user chose to fix now:
- `src/voice_session.rs`: session monitor now uses `attach_current_thread_permanently`
  (was attach/detach every 100 ms — consistent with O1).
- `src/audio.rs`: sliding-window energy accumulator switched from `f32` to `f64` to
  eliminate rounding drift/catastrophic cancellation on long quiet recordings.

Pushed as `d8078b9`; CI run `31097691815` — **all gates green** (fmt, translations, JVM
tests, `assembleDebug` incl. Rust, `lintDebug`, `checkModels`), APK to Telegram.

## 🟢 Completed — 2026-08-06 review follow-up (MEDIUM fixes) — CI green

Closed the two MEDIUM findings from the code review of `92e9da2` (commit `9a53841`,
CI run `31098576970` — all gates green):
- **Signing fail-fast now covers aggregate tasks:** `taskTargetsRelease` matches
  `assemble`/`build` (with or without `:project:` prefix) plus any task containing
  `Release`/`bundle`, so `./gradlew assemble` or `build` with `release.keystore`
  present but env vars missing fails fast instead of signing with default
  credentials. `assembleDebug` stays exempt (never touches the release signing
  config).
- **File-transcription cap lowered to 30 min** (`MAX_DECODE_SAMPLES` = 28.8 M
  samples ≈ 115 MB float[]) so the cap fires before OOM on ~192–256 MB default
  heaps; `file_error_too_long` string updated to 30 minutes in all 7 locales
  (translations PASS) + RELEASE_NOTES updated.

**Still open from the review (only MEDIUM/LOW left):** `App.java` async-migration
race (tiny window, upgrade-only). Candidates for the v0.1.28 release push.

## 🟢 Recently completed

- **2026-08-04 — Live-subtitle on-device translation (feature):** optional translation for live subtitles with `Auto = original language` (user decision) and explicit targets EN/ES/FR/DE/IT/PT/RU. Research proved the bundled Nemotron 3.5 ASR cannot translate (`supports_translate = false`; Whisper-family only translates to English), so the design is a **cascade**: existing chunked ASR → Google ML Kit text translation (`com.google.mlkit:translate:17.0.2`). New `SubtitleTranslationTargets`, `SourceLanguageResolver`, `SubtitleTranslator` + `OnDeviceSubtitleTranslator`; ordered segment pipeline + FIFO queue + session generation in `LiveSubtitleService`; `subtitle_translation_target` marker; Rust `transcribe_subtitle` forces `Task::Transcribe` so `model_translate` never leaks into subtitles; the target selector lives on the `LiveSubtitleActivity` start screen (MainActivity untouched) + 7-locale strings; JVM tests (71 total, 0 failures), translations PASS, `cargo fmt` clean, `lintDebug` BUILD SUCCESSFUL. Details: [`memory/live-subtitle-translation-2026-08-04.md`](./memory/live-subtitle-translation-2026-08-04.md).
- **2026-08-04 — v0.1.25 release preparation:** bumped Gradle to `versionName 0.1.25` / `versionCode 27`, added concise release notes and store metadata, and documented the three follow-up improvements since v0.1.24: cancellation controls, debug model-download progress, and clearer post-processing diagnostics.
- **2026-08-04 — CI green on the privacy work:** run `30897928321` (commit `371a119`) passed every gate — `cargo fmt --check`, translations, **34 JVM tests**, `assembleDebug`, `lintDebug`, `checkModels` — and sent the APK to Telegram. The earlier run `30895862658` failed because AGP 8.x disables `BuildConfig` generation by default; fixed with `buildFeatures { buildConfig = true }` and the network tests were made deterministic (injected DNS + `NO_PROXY`), so they pass even under a runner proxy.
- **2026-08-04 — v0.1.24 release hardening:** privacy logging (`BuildConfig.DEBUG` gating for transcripts/PP errors), model-import hardening (`sanitizeModelFileName`, weak-ref UI dispatch), defensive IME cleanup, signing warnings, `cargo fmt --check` + `checkModels` CI gates, release APK verification (`zipalign`/`apksigner`), JVM harness closure (DNS fail + connect timeout, 34 tests total), English `RELEASE_NOTES`/`CHANGELOG`/store changelog, README language fix, agentic docs in English. Details: [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md).
- **2026-08-04 — Reusable device smoke automation:** added `scripts/smoke_postprocess_device.py` + `scripts/README.md`. It drives the real MainActivity → private post-processing settings → file-transcription path by resource ID, selects AutoComplete rows semantically, accepts credentials only from an environment variable, asserts final-only refinement, and uninstalls in `finally`.
- **2026-08-04 — P1.4/P1.5 feasibility:** checklists classified by what is runnable without hardware; 2 JVM tests added to close the harness (see above); remaining scenarios require a device.
- **2026-08-03 — CI green after the JObject fix:** runs `30859369221`/`30859370506` passed translations, unit tests, `assembleDebug` and `lintDebug`; APK `app-debug-apk-v0.1.24` sent to Telegram.
- **2026-08-03 — Gauntlet plan executed (P0 + P1.1/P1.2):** owner-scoped PP cancellation, subtitle generations, SHA-256 on debug downloads, unified toolchain, file-transcription operation-ids, atomic markers; JVM harness green (32 tests). Details: [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md).
- **2026-08-03 — Static audit & debt register:** [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md).

## 🟢 P0 blockers — implemented and CI-validated

1. ✅ Owner-scoped PP cancellation (`cancelAllFor(owner)`; global `cancelAll()` only for real shutdown / PP toggle-off).
2. ✅ Subtitle worker generations: stale jobs never transcribe nor deliver.
3. ✅ SHA-256 verified before activating the debug runtime download.
4. ✅ Unified toolchain: NDK 28.0.13004108 (Gradle = CI = docs), per-host `ndkPrebuiltDir()`.

Implemented 2026-08-03, gated by JVM; `assembleDebug`, `lintDebug` and `checkModels` confirmed in CI. **Pending:** full release build/signature evidence, remaining device matrix, and full-crate Rust test block.

## 🟡 P1/P2 debt

- ✅ PP verified with controlled HTTP: payload, `stream:false`, `${output}` once, JSON/HTTP errors, toggle-off during flight, single delivery, real OkHttp timeout by seam (**+ DNS fail and connect timeout on 2026-08-04** — `PostProcessorTest` 10 tests; 34 JVM total).
- ✅ File transcription operation-ids (P1.1); atomic markers (P1.2); strings migrated to 7 locales (P2.4).
- ⏳ **Device-only (P1.4/P1.5):** subtitle/MediaProjection lifecycle on Android 10–15 + one OEM ROM; post-processing with a real provider (production 30 s/60 s wall-clock, TLS, `CANCEL_PP` broadcast to `:ime`, concurrent surfaces, leaks, end-to-end latency); smoke of the six surfaces.
- ⏳ Full-crate `cargo test` or a documented reproducible block of `transcribe-cpp-sys v0.1.3`.
- ⏳ `rustfmt` full-scope check in CI (added as a hard gate 2026-08-04).
- ✅ Version/tag sequence for the v0.1.25 release: **versionCode 27** and `versionName 0.1.25` are prepared; the debug APK and release tag/workflow remain to be validated for this release.

## 🟡 Validation pending

- ✅ translations, JVM tests (34), `assembleDebug`, `lintDebug`, `checkModels` and `cargo fmt --check` in CI on run `30897928321` (commit `371a119`, 2026-08-04).
- ⏳ `assembleRelease` + `checkModels` + signature/alignment verification in the release workflow (runs on the `v0.1.25` tag).
- 🟡 Device smoke: the CI APK was installed cleanly, Nemotron downloaded and the IME/provider post-processing path produced functionally transformed output with Groq; Logcat attribution was inconclusive. The full popup/RecognitionService/IME/subtitles/file/custom-words matrix, cancellation and lifecycle scenarios remain pending. The reusable runner is available at `scripts/smoke_postprocess_device.py`.

## 🔴 Known environment blocks

- Full crate `cargo test` may be blocked by the `transcribe-cpp-sys v0.1.3` packaging; the mirror crate only covers pure logic.
- Local host is an ARM64 Android userspace without KVM/emulator; compile/lint gates are owned by CI; device smoke requires physical hardware.

## State rule

`progress.md` never marks a task "closed" based on design or static reading alone. Every closure must cite the command/workflow/device and the result (evidence taxonomy: design / static audit / validated locally / validated in CI / validated on device).

## Canonical links

- Live-subtitle translation: [`memory/live-subtitle-translation-2026-08-04.md`](./memory/live-subtitle-translation-2026-08-04.md)
- v0.1.24 prep: [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md)
- P1.4/P1.5 feasibility: [`memory/p14-p15-feasibility-2026-08-04.md`](./memory/p14-p15-feasibility-2026-08-04.md)
- Implementation P0/P1: [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md)
- Audit/debt: [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md)
- QA plan: [`../GAUNTLETE_PLAN.md`](../GAUNTLETE_PLAN.md)
- Architecture: [`architecture.md`](./architecture.md)
- Spec: [`spec.md`](./spec.md)
- Agent rules: [`../AGENTS.md`](../AGENTS.md)
- Index: [`INDEX.md`](./INDEX.md)
