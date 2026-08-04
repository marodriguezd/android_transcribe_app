# release-0.1.24-prep — 2026-08-04

**Topic:** preparing the v0.1.24 release: privacy logging, release hardening, CI gates, model-import lifecycle, JVM harness closure, agentic docs refresh and publication metadata.

## Context

Full static audit of the repository completed earlier on 2026-08-04 (source, tests, CI, agentic files, the 41 commits of 28/07–04/08). Veredict: the Gauntlet remains **OPEN** — solid architecture and 32 JVM tests, but no closed release: no `v0.1.24` tag, no release-signing evidence, no device smoke of the six surfaces.

User decisions (2026-08-04):

1. **Distribution channel:** GitHub Releases — signed APK via tags + `android_release.yml`. No Play Store/AAB in scope.
2. **Log privacy:** strict — transcribed text must never appear in production logs; debug-only diagnostics allowed via `BuildConfig.DEBUG`.
3. **Signing:** keep the historical defaulting behaviour (`password` defaults) for local dev, but warn loudly; CI keeps decoding `KEYSTORE_BASE64` and now verifies the APK signature before publishing.
4. **Documentation language:** everything in English.

## Changes implemented

### Code

- `RecognizeActivity.java`: raw transcript + provider endpoint logs gated behind `BuildConfig.DEBUG` (removed `PP-RAW`/length logs from production).
- `VoiceRecognitionService.java`, `TranscribeFileActivity.java`, `RustInputMethodService.java`: post-processor error logs (which may carry provider details) gated behind `BuildConfig.DEBUG`.
- `ModelsActivity.java`:
  - `sanitizeModelFileName()` rejects path separators, `..`, control chars (P1.3 model-import hardening).
  - Import thread uses application context + weak-ref UI dispatch guarded by `isFinishing()/isDestroyed()` — a rotation or task switch mid-import can no longer touch destroyed views.
- `RustInputMethodService.java`: `cleanupNative()` wrapped in try/catch (defensive, matches existing teardown pattern).
- `app/build.gradle.kts`: signing warning when `release.keystore` exists but `STORE_PASS`/`KEY_ALIAS`/`KEY_PASS` are missing; warning when the keystore is absent. Behaviour unchanged (user decision #3).

### Tests

- `PostProcessorTest.java` +2 tests (now 10 in the class, 34 JVM total):
  - `dnsFailureReportsError`: real DNS failure against RFC 6761 `.invalid` host → `UnknownHostException` → `onError` → caller falls back to raw text.
  - `connectTimeoutReportsError`: real OkHttp connect timeout against TEST-NET `192.0.2.1` with a 500 ms scaled client via `setSharedClientForTests`.

### CI

- `debug_telegram.yml`: added `cargo fmt --all -- --check` (hard gate) and `checkModels` gate.
- `android_release.yml`: added `cargo fmt --all -- --check`; keystore decode now fails fast when `KEYSTORE_BASE64` is empty; after `assembleRelease` the APK is verified with `zipalign -c 4` and `apksigner verify --print-certs` before the release is created.

### Publication metadata

- `RELEASE_NOTES.md` fully in English: new **v0.1.24** section (privacy, final-only PP, isolation, CI hardening) plus historical v0.1.19–v0.1.23 notes (re-headed from `# Unreleased` to `# v0.1.24 — release hardening (2026-08-04)` in the release commit).
- `CHANGELOG.md`: v0.1.24 section in English; historical sections left as-is.
- `fastlane/metadata/android/en-US/changelogs/26.txt`: new store changelog for `versionCode 26`.
- `README.md`: corrected the built-in-model language claim (40 locales, not 4) and clarified the debug-model download.

### Agentic docs

- `.agents/progress.md`: rewritten in English, dated 2026-08-04.
- `.agents/INDEX.md`: memory index updated in English.
- New memory file `.agents/memory/release-0.1.24-prep-2026-08-04.md` (this document).
- `agent_prompt.md` refresh: current state (34 JVM tests, gates, privacy rule) — see updated instructions.

## Verification

- Local validation pending on the run: `./gradlew testDebugUnitTest` (expect 34 tests green), `python3 scripts/check_translations.py`, `cargo fmt --all -- --check`, plus code review.
- `assembleDebug`/`lintDebug`/`checkModels`/release gates run in CI (workflows changed).

## Remaining work for a closed v0.1.24

- Device smoke of the six surfaces (P1.4 subtitles/MediaProjection lifecycle, P1.5 post-processing with a real provider).
- Tag `v0.1.24` + signed release: `versionCode 26` is committed in the release commit (2026-08-04); the tag is created **only after the user validates the debug APK** (run `30897003634`), and the release workflow then produces the signed APK with `apksigner`/`zipalign` evidence.
- `cargo test` of the full crate or a documented reproducible block of `transcribe-cpp-sys v0.1.3`.
- Final README/AGENTS consistency pass in English.

## Lessons learned

1. **Privacy gating is per-message, not per-file:** error strings can carry provider response details, so even "delivering raw text" warnings get gated; non-sensitive errors (permissions, lifecycle) stay always-on for support.
2. **CI verification of the artifact beats trusting the build:** `apksigner verify` + `zipalign -c` after `assembleRelease` makes an unsigned/unaligned APK impossible to publish silently.
3. **Device-only validation must be spelled out in the docs as "pending"**, never implied done — the audit's evidence taxonomy applies to every gate.
4. **AGP 8.x disables `BuildConfig` generation by default** (https://developer.android.com/build/releases/gradle-plugin#buildconfig-fields). The first CI run of the privacy work failed exactly here: `compileDebugJavaWithJavac` could not resolve `dev.notune.transcribe.BuildConfig`. Fix: `buildFeatures { buildConfig = true }` in `app/build.gradle.kts`. Any future use of `BuildConfig.DEBUG` (or any new `BuildConfig` field) depends on this flag staying on.
5. **Network tests must not depend on the runner's resolver/proxy.** The original `.invalid`-host DNS test and the plain `192.0.2.1` connect test are sensitive to CI proxies (GitHub-hosted runners can route them through a proxy, changing the failure mode and making the test flaky or wrong). Deterministic fix applied 2026-08-04:
   - DNS: inject an OkHttp `Dns` that always throws `UnknownHostException` (real `onFailure` path, no resolver dependency).
   - Connect: `proxy(NO_PROXY)` + TEST-NET `192.0.2.1` + 500 ms scaled connect timeout via `setSharedClientForTests`.
   - Verified: `testDebugUnitTest` passes (34 tests) even with `HTTP(S)_PROXY` pointed at an unreachable proxy.

## CI incident log (2026-08-04, run 30895862658)

- Run `30895862658` (push `625ad68`, "Debug APK → Telegram") **failed at step 14 `testDebugUnitTest`**.
- Root cause: `BuildConfig.DEBUG` used in 4 production files (`RecognizeActivity`, `VoiceRecognitionService`, `TranscribeFileActivity`, `RustInputMethodService`) but AGP 8.x does not generate `BuildConfig` unless `buildFeatures.buildConfig = true`. Clean CI build → unresolved symbol.
- Secondary latent issue caught while fixing: the two new network tests could be made flaky by runner proxies; made deterministic (lesson 5).
- Fix commits: `7b62942` — `buildConfig = true` + deterministic network tests (injected DNS + `NO_PROXY`; the `InetAddress` import was also missing in the first attempt).
- Local re-validation: `testDebugUnitTest` → BUILD SUCCESSFUL, 34/34 tests green with `HTTP_PROXY`/`HTTPS_PROXY` set to an unreachable proxy.
- Green re-run: push `7b62942` triggered run `30897003634` → all 21 steps success (incl. `cargo fmt --check`, translations, 34 JVM tests, `assembleDebug`, `lintDebug`, `checkModels`), APK sent to Telegram.
- Release commit (2026-08-04): `versionCode 26`, `RELEASE_NOTES.md` re-headed to `# v0.1.24`; tag `v0.1.24` deliberately **not** created (pending device validation).
