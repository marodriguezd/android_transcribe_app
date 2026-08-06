# GitHub Actions outage — cancelled run, queued rerun, green validation, history cleanup (2026-08-06)

Operational incident during CI validation of the IME cancel-button feature. Written
for future agents so they (a) don't misread an outage-cancelled run as a code
failure and (b) know the working retry sequence.

## Timeline (UTC)

| Time | Event |
|---|---|
| 13:04 | Last pre-incident debug run for `679b73b` — success. |
| ~18:46 | GitHub **Partial System Outage** begins; status indicator `major`; the **Actions** component shows `major_outage` (Git/Webhooks/API recovered hours earlier). |
| 19:44 | Push of `9c65f61` (the feature commit) → run `31127092655` created, but the `debug` job was **cancelled by the outage**: conclusion `failure`, job `debug cancelled`, `gh run view --log-failed` empty. |
| ~20:10 | Empty retry commit `e1243c0` (`ci: retry debug workflow trigger`) pushed to re-fire the push webhook — **no run was created** (the push event itself was affected). |
| 20:34 | Status-page incident "Incident with Actions" still `investigating`, no ETA. |
| 20:44 | `gh run rerun 31127092655` accepted → run **queued**. |
| 21:14 | Actions started draining the queue → run `in_progress`. |
| 21:18:45 | Run completed **success** — all 21 steps green, APK sent to Telegram. |
| ~21:24 | History cleanup: `e1243c0` removed (`git reset --hard 9c65f61` + `git push --force-with-lease origin main`); `main` = `9c65f61` → `679b73b`. |

## What the run validated (context)

Commit `9c65f61` — "feat: keep IME cancel button visible while recording and pending":

- The IME Cancel button (`ime_cancel`) is now visible **during recording**, so a
  capture can be discarded before ASR/post-processing fires (zero wasted LLM
  API/tokens), and stays visible through the whole **`resultPending`** window (mic
  release → commit/cancel) via a single-source-of-truth flag that eliminated the
  GONE→VISIBLE flicker across stop→transcribe→refine (including the "Ready"
  status landing between ASR and refining on the streaming path).
- Also touched: `RELEASE_NOTES.md` (Unreleased entry) and `AGENTS.md` §4.9 (UI
  convention documented so future agents don't reintroduce the flicker).

## Gate evidence (run `31127092655`, job `debug`, 21:14:24 → 21:18:45, conclusion success)

All 21 steps green, including every hard gate:

- Rust format check: `cargo fmt --all -- --check` ✅
- Translations parity: `python3 scripts/check_translations.py` ✅
- JVM unit tests: `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL in 32s** ✅
- Debug build: `./gradlew assembleDebug` (Rust via cargo-ndk) → **BUILD SUCCESSFUL in 1m 18s** ✅
- Lint: `./gradlew lintDebug` → **BUILD SUCCESSFUL in 19s** ✅
- `checkModels` (bundled-model SHA-256) ✅
- APK renamed + artifact uploaded + sent to Telegram ✅

## Lessons for future agents

1. **An outage "failure" is usually a cancellation, not a bug.** Check the job
   conclusion first (`gh run view <id> --json jobs -q '.jobs[]|.conclusion'`).
   A `cancelled` job with no `--log-failed` output = outage artifact. Do **not**
   start debugging the code until that's ruled out.
2. **`gh run rerun <id>` is the winning retry move** — it was accepted even while
   Actions was `major_outage`, queued the run, and GitHub drained the queue when
   the incident resolved.
3. **Do NOT push an empty retry commit (`git commit --allow-empty`) to re-fire the
   push webhook during an outage** — the push event is also affected, no run is
   created, and it only pollutes history (it had to be cleaned up afterwards).
   Prefer `gh run rerun` once the API responds.
4. **Watch the status page, not just the run list:**
   `https://www.githubstatus.com/api/v2/status.json` (indicator/description) and
   `/api/v2/components.json` (per-component status — `Actions` stayed
   `major_outage` while Git/Webhooks/API were already operational).
5. **History cleanup after a retry commit:** `e1243c0` was 100 % empty (`git diff
   e1243c0 9c65f61` empty). Removed with `git reset --hard 9c65f61 && git push
   --force-with-lease origin main`. Safe here (single-maintainer repo, empty
   commit, validated SHA preserved). Use `--force-with-lease`, never bare
   `--force`.
6. **CI evidence stays valid after cleanup:** the rerun validated SHA `9c65f61`,
   which is unchanged; the cleanup force-push did not create a new run (Actions
   still degraded) — fine, since it's the same commit with the same green result.
7. **The debug workflow only triggers on `push: main`** (no `workflow_dispatch`),
   so during an outage the only manual retry paths are `gh run rerun` or waiting
   for the queued run.
8. `gh` CLI calls themselves may time out while the API is degraded — retry with
   a longer `timeout_seconds`; the API recovered intermittently before Actions did.

## Status

- Feature `9c65f61` fully CI-validated (all gates green), APK sent to Telegram.
- History clean: `main` = `9c65f61` → `679b73b`; empty retry commit gone.
- This doc + INDEX.md/progress.md updates are uncommitted (docs only, no code).

## Addendum — v0.1.29 tag pushed during the same outage (21:26 UTC)

Release **v0.1.29** (`e150c98`, versionCode 31) was pushed to `main` and tagged
`v0.1.29` while Actions was still `major_outage`. As of 21:37 UTC no run had
been created for `e150c98` — push/tag events are queued and should be processed
when Actions recovers (the release workflow triggers on `push: tags v*` and
fails fast if `KEYSTORE_BASE64` is missing; secrets were verified present).
Resume steps live in `progress.md` (section "🟡 Pending — v0.1.29 release CI").
