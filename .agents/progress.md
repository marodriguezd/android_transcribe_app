# Progress — current AI-assisted work state

**Last update:** 2026-08-04 (v0.1.24 release preparation)

## 🟢 Recently completed

- **2026-08-04 — CI green on the privacy work:** run `30897003634` passed every gate — `cargo fmt --check`, translations, **34 JVM tests**, `assembleDebug`, `lintDebug`, `checkModels` — and sent the APK to Telegram. The earlier run `30895862658` failed because AGP 8.x disables `BuildConfig` generation by default; fixed with `buildFeatures { buildConfig = true }` and the network tests were made deterministic (injected DNS + `NO_PROXY`), so they pass even under a runner proxy.
- **2026-08-04 — v0.1.24 release hardening:** privacy logging (`BuildConfig.DEBUG` gating for transcripts/PP errors), model-import hardening (`sanitizeModelFileName`, weak-ref UI dispatch), defensive IME cleanup, signing warnings, `cargo fmt --check` + `checkModels` CI gates, release APK verification (`zipalign`/`apksigner`), JVM harness closure (DNS fail + connect timeout, 34 tests total), English `RELEASE_NOTES`/`CHANGELOG`/store changelog, README language fix, agentic docs in English. Details: [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md).
- **2026-08-04 — P1.4/P1.5 feasibility:** checklists classified by what is runnable without hardware; 2 JVM tests added to close the harness (see above); remaining scenarios require a device.
- **2026-08-03 — CI green after the JObject fix:** runs `30859369221`/`30859370506` passed translations, unit tests, `assembleDebug` and `lintDebug`; APK `app-debug-apk-v0.1.24` sent to Telegram.
- **2026-08-03 — Gauntlet plan executed (P0 + P1.1/P1.2):** owner-scoped PP cancellation, subtitle generations, SHA-256 on debug downloads, unified toolchain, file-transcription operation-ids, atomic markers; JVM harness green (32 tests). Details: [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md).
- **2026-08-03 — Static audit & debt register:** [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md).

## 🟢 P0 blockers — implemented, pending validation

1. ✅ Owner-scoped PP cancellation (`cancelAllFor(owner)`; global `cancelAll()` only for real shutdown / PP toggle-off).
2. ✅ Subtitle worker generations: stale jobs never transcribe nor deliver.
3. ✅ SHA-256 verified before activating the debug runtime download.
4. ✅ Unified toolchain: NDK 28.0.13004108 (Gradle = CI = docs), per-host `ndkPrebuiltDir()`.

Implemented 2026-08-03, gated by JVM; `assembleDebug`/`lintDebug` confirmed in CI. **Pending:** `checkModels` in the release workflow (added to debug workflow 2026-08-04), full release build, device validation.

## 🟡 P1/P2 debt

- ✅ PP verified with controlled HTTP: payload, `stream:false`, `${output}` once, JSON/HTTP errors, toggle-off during flight, single delivery, real OkHttp timeout by seam (**+ DNS fail and connect timeout on 2026-08-04** — `PostProcessorTest` 10 tests; 34 JVM total).
- ✅ File transcription operation-ids (P1.1); atomic markers (P1.2); strings migrated to 7 locales (P2.4).
- ⏳ **Device-only (P1.4/P1.5):** subtitle/MediaProjection lifecycle on Android 10–15 + one OEM ROM; post-processing with a real provider (production 30 s/60 s wall-clock, TLS, `CANCEL_PP` broadcast to `:ime`, concurrent surfaces, leaks, end-to-end latency); smoke of the six surfaces.
- ⏳ Full-crate `cargo test` or a documented reproducible block of `transcribe-cpp-sys v0.1.3`.
- ⏳ `rustfmt` full-scope check in CI (added as a hard gate 2026-08-04).
- ⏳ Version/tag sequence for the v0.1.24 release: **versionCode 26 committed** (2026-08-04, release commit); tag `v0.1.24` pending user validation of the debug APK (run `30897003634`).

## 🟡 Validation pending

- ✅ translations, JVM tests (34), `assembleDebug`, `lintDebug`, `checkModels` and `cargo fmt --check` in CI on run `30897003634` (2026-08-04).
- ⏳ `assembleRelease` + `checkModels` + signature/alignment verification in the release workflow (runs on the `v0.1.24` tag).
- ⏳ Device smoke: popup, RecognitionService, IME, subtitles, file, custom words — streaming and non-streaming models, PP off/on/failed, fast cancel, language change, `:ime` process.

## 🔴 Known environment blocks

- Full crate `cargo test` may be blocked by the `transcribe-cpp-sys v0.1.3` packaging; the mirror crate only covers pure logic.
- Local host is an ARM64 Android userspace without KVM/emulator; compile/lint gates are owned by CI; device smoke requires physical hardware.

## State rule

`progress.md` never marks a task "closed" based on design or static reading alone. Every closure must cite the command/workflow/device and the result (evidence taxonomy: design / static audit / validated locally / validated in CI / validated on device).

## Canonical links

- v0.1.24 prep: [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md)
- P1.4/P1.5 feasibility: [`memory/p14-p15-feasibility-2026-08-04.md`](./memory/p14-p15-feasibility-2026-08-04.md)
- Implementation P0/P1: [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md)
- Audit/debt: [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md)
- QA plan: [`../GAUNTLETE_PLAN.md`](../GAUNTLETE_PLAN.md)
- Architecture: [`architecture.md`](./architecture.md)
- Spec: [`spec.md`](./spec.md)
- Agent rules: [`../AGENTS.md`](../AGENTS.md)
- Index: [`INDEX.md`](./INDEX.md)
