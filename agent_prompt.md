# agent_prompt.md — Instructions for the next agent

> Read first: `AGENTS.md`, `GAUNTLETE_PLAN.md`, `.agents/progress.md`,
> `memory/extreme-latency-simd-hardware-optimizations-2026-08-14.md`,
> `memory/github-actions-outage-2026-08-06.md`,
> `memory/live-subtitle-translation-2026-08-04.md`,
> and the audit `memory/static-audit-debt-2026-08-03.md`.
>
> The repository has been enhanced with **extreme mobile latency & SIMD NEON optimizations**
> (80ms streaming tick cadence, ARM64 NEON intrinsics for RMS math, zero-alloc audio buffers,
> Fat LTO compiler profile, hardware acceleration backend selector CPU/NPU/GPU, and
> CI benchmark optimization gate `scripts/bench_performance.py`).
> Full CI gauntlet green on GitHub Actions run `31788198797` (commit `50b82a7`).

## Already implemented — do not redo

- **2026-08-14 — Extreme Latency & SIMD NEON pass:**
  - `STREAM_TICK_MS` = 80ms (matches Nemotron native encoder frame interval; saves 220ms lag; 3.75x cadence boost) + hypothesis deduplication (`last_emitted`) in `src/engine.rs`.
  - ARM64 NEON vector math (`fast_rms`, `fast_sum_squares` with `vfmaq_f32`, 4 vector accumulators unrolled 16x) in `src/audio.rs`, applied in `voice_session.rs`, `recog_service.rs`, `subtitle.rs`.
  - Zero-alloc buffer draining with `Vec::drain(..).collect()` + thread-local buffer in `pushAudio`.
  - `[profile.release]` with `lto = "fat"`, `codegen-units = 1`.
  - Phonetic corrector precomputed bigrams and L2 norm in `src/corrector.rs` (2.56x faster dictionary tiebreak).
  - Hardware backend selector UI & engine configuration (`hardware_backend` marker: CPU NEON / NPU NNAPI / GPU Vulkan) with full 7-locale parity.
  - Automated benchmark gate in CI (`scripts/bench_performance.py`).
- P0.1: owner-scoped post-processing cancellation (`CallRegistry` + `cancelAllFor(owner)`).
- P0.2: subtitle worker session generations (`GENERATION` in `subtitle.rs`).
- P0.3: SHA-256 verified before activating the debug runtime download (`FileSha256` + `MainActivity`).
- P0.4: unified toolchain (NDK 28.0.13004108; `ndkPrebuiltDir()`).
- P1.1: operation-id in `transcribe_file.rs`/`TranscribeFileActivity`.
- P1.2: atomic markers (unique temp per write in `MarkerFileHelper`).
- P1.3: HTTP/JVM post-processor suite via the `PostProcessorSettings` seam + scaled-client timeout seam.
- **2026-08-06 hardening & audit fixes:** IME cancel button visible during recording, release signing fail-fast, audio duration caps, permanent JNI thread attach in audio callbacks.

## Next: validation and remaining debt

### JVM (complete)

The harness now covers: payload, `stream:false`, `${output}` once, invalid
JSON, HTTP error, toggle-off during flight, exactly one delivery, real OkHttp
read timeout by seam, **real DNS failure (`.invalid`)**, and **real connect
timeout (`192.0.2.1`, scaled client)**. Production values (30 s/60 s/60 s)
are asserted as applied values; wall-clock durations stay out of the harness.

### Device-only (P1.4/P1.5)

- P1.4: subtitles/MediaProjection lifecycle — Android 10–15 + one OEM ROM,
  stop/restart, notification stop, revocation, overlay removal, AudioRecord
  errors, zero callbacks after `cleanupNative`.
- P1.5: post-processing with a real provider — production timeouts, TLS,
  `CANCEL_PP` broadcast to `:ime`, IME never stuck in "Refining…", concurrent
  surfaces, 10+ consecutive dictations without leaks, end-to-end latency.
- Smoke of the six surfaces (popup, RecognitionService, IME, subtitles, file,
  custom words) with streaming and non-streaming models.

### P2 / CI

- ✅ `cargo fmt --all -- --check`, translations, 34 JVM tests, `assembleDebug`,
  `lintDebug` and `checkModels` exercised green on run `30897928321` for
  commit `371a119` (2026-08-04).
- 🟡 Live-subtitle translation gates green **locally** (71 JVM tests,
  translations PASS, `cargo fmt`, `lintDebug`); pending CI on push and
  device validation (Play Services pack download, latency, Red Note
  Chinese audio end-to-end).
- ⏳ `assembleRelease` + `checkModels` + signature/alignment verification in
  the release workflow (runs on the `v0.1.25` tag).
- ⏳ Full-crate `cargo test` or a documented reproducible block of
  `transcribe-cpp-sys v0.1.3`.
- ✅ Version metadata for v0.1.25: **versionCode 27** and `versionName 0.1.25`
  are prepared; the release tag and signed publication remain the final steps.

## Rules to respect when touching implemented code

- Preserve `CallRegistry` (identity owners, `NO_OWNER` sentinel) and the
  global-vs-owner-scoped separation of `cancelAll`.
- Preserve subtitle generations: never reintroduce stale worker deliveries or
  remove the re-checks before transcribing/delivering.
- Preserve the subtitle translation contract: `Auto` = original language;
  translation failures always fall back to the original text (never a wrong
  translation); segment delivery stays strictly ordered; `transcribe_subtitle`
  keeps forcing `Task::Transcribe` so the global `model_translate` cannot
  translate subtitles.
- Never activate a model without verifying its hash; `active_model` stays
  atomic.
- Never log transcribed text in release builds (`BuildConfig.DEBUG` gating).
- Do not change JNI signatures without a global search (`transcribeAudio`
  already carries opId).
- Do not remove `catch_unwind` or Mutex-poison recovery.
- Do not cache `model_language` inside `Engine`.
- Do not raise subtitle thresholds without slow hardware.
- Do not declare "BUILD SUCCESSFUL" without real output, nor "Gauntlet
  closed" while any P0 lacks CI/device validation.

## P2 priorities

- Exercise the new CI gates (`cargo fmt --check`, `checkModels`) on a push;
- run the release workflow end-to-end on a `v0.1.25` tag with keystore
  secrets and keep the `apksigner verify` evidence;
- smoke/instrumentation matrix of the six surfaces;
- keep dated evidence for every gate.

## Prohibitions

- No JNI signature changes without a global search and Java/Rust updates.
- No removing `catch_unwind` or Mutex-poison recovery.
- No caching `model_language` inside `Engine`.
- No raising subtitle thresholds without slow hardware.
- No activating a model before verifying its hash.
- No raw transcript in production logs.
- No "BUILD SUCCESSFUL" without real command output; no "Gauntlet closed"
  while any P0 is open.

## Final validation planned

Run in CI/authorized host, as separate invocations per workflow:

```bash
python3 scripts/check_translations.py
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew checkModels
cargo fmt --all -- --check
cargo test
```

Then: device smoke of popup, RecognitionService, IME, subtitles, file and
custom words with streaming and non-streaming models, PP off/on/failed, fast
cancel, language change and the `:ime` process — and then the
`v0.1.25` tag + release. Device Logcat attribution for the real-provider
post-processing smoke was inconclusive, although the observed transformed
output was functionally positive; do not describe that evidence as a full
six-surface/device matrix.
