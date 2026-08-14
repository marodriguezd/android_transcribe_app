# INDEX — Índice cruzado entre documentos de especificación

**Propósito:** un único punto de entrada para **toda** la documentación
de especificación de este proyecto. Útil cuando necesitas "¿dónde está
escrito X?" sin navegar a ciegas.

## Documentos canónicos (cada uno es single-source-of-truth en su dominio)

| Doc | Cubre | Editarlo cuando… |
|---|---|---|
| [`spec.md`](./spec.md) | **QUÉ** es el proyecto (objetivo, usuarios, restricciones, no-objetivos, superficies funcionales) | Cambia el **alcance del producto** (semanas/meses) |
| [`architecture.md`](./architecture.md) | **CÓMO** está construido (stack, módulos, flujos, invariantes, boundaries) | Refactors de módulos, nuevas dependencies, cambios de flujo (días/semanas) |
| [`memory/<topic>-YYYY-MM-DD.md`](./memory) | **DECISIONES** tomadas en sesiones de trabajo concretas, con fecha y rationale | Cada vez que se cierra una tarea no-trivial o se observa un trade-off |
| [`progress.md`](./progress.md) | **ESTADO** actual: en-curso, recién completado, bloqueos, próximos pasos | Tras cada commit relevante al trabajo IA |
| [`../AGENTS.md`](../AGENTS.md) | **REGLAS** para IAs: JNI contract, marker files, anti-patterns críticos, plantillas | Cambios de **convenciones para agentes** |
| [`../README.md`](../README.md) | **CARA** del proyecto para humanos: features, prerequisitos, screenshots, license | Cambios de cara/UI (semanas) |
| [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) | **NOTAS DE LA RELEASE ACTUAL** que consume GitHub Actions | Al preparar una release; el historial completo vive en `CHANGELOG.md` |

## Reglas de oro

1. **No dupliques contenido.** Si dos pueden decirlo, uno apunta al otro.
   Las versiones pinnadas viven en `app/build.gradle.kts`; `spec.md` y
   `architecture.md` enlazan, no reproducen.
2. **Una sola fuente de verdad por dato.** Si la versión del modelo cambia
   en el `build.gradle.kts`, ningún otro doc debe llevar ese dato: agregan
   un enlace a ese sitio.
3. **Edita el doc MÁS ESPECÍFICO.** Reglas de IA → `AGENTS.md`. Razones
   de scope → `spec.md`. Detalles de implementación → `architecture.md`.
   Estado actual → `progress.md`. Historia → `memory/`.
4. **No añadas docs sin editar `INDEX.md`.** Si entra un doc, este índice
   tiene que reflejarlo; si no, el índice se pudre.

## Cuándo crear un nuevo documento

- **Sí** cuando hay un dominio con info densa que no encaja en ninguno
  existente (e.g. un futuro modelo de seguridad de LLM post-procesador
  — sería un doc independiente, no un apéndice de architecture).
- **No** cuando es sólo un párrafo largo (mejor sección de un doc
  existente).
- **Casi siempre no** cuando es spec temporal para una decisión — va a
  `memory/<topic>-<fecha>.md`.## Memoria por tema

Se rellena conforme se acumulen sesiones de trabajo. Listado en orden cronológico inverso (lo más reciente primero):

> **Language note (2026-08-04):** new/updated documents in this folder are
> written in English (maintainer decision). Historical Spanish documents stay
> as the recorded history; do not silently translate them.

- [`memory/extreme-latency-simd-hardware-optimizations-2026-08-14.md`](./memory/extreme-latency-simd-hardware-optimizations-2026-08-14.md) —
  **Extreme Latency & Hardware Optimization Pass:** Slashed streaming tick delay from
  300ms to 80ms (-220ms lag / 3.75x cadence boost), ARM64 NEON SIMD vector math (`vfmaq_f32`,
  4 vector accumulators unrolled 16x), zero-alloc CPAL/JNI buffering, Fat LTO compiler profile,
  hardware backend selector UI & engine (`hardware_backend` marker: CPU NEON / NPU NNAPI / GPU Vulkan),
  2.56x faster phonetic corrector tiebreaking, and automated benchmark suite in CI (`scripts/bench_performance.py`,
  GitHub Actions run `31788198797` all gates PASS).
- [`memory/github-actions-outage-2026-08-06.md`](./memory/github-actions-outage-2026-08-06.md) —
  GitHub Actions major-outage incident: run cancelled, queued `gh run rerun`, full gate validation
  green for the IME cancel-button feature, history cleanup of empty retry commit, and lessons for CI debugging.
- [`memory/live-subtitle-translation-2026-08-04.md`](./memory/live-subtitle-translation-2026-08-04.md) —
  Live-subtitle on-device translation (cascade ASR → ML Kit; `Auto` = original language; 71 JVM tests; gates green).
- [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md) —
  v0.1.24 release prep: privacy logging (`BuildConfig.DEBUG` gating), model-import hardening,
  CI gates (rustfmt/checkModels/APK verification), JVM harness closure (34 tests), English publication metadata.
- [`../scripts/README.md`](../scripts/README.md) —
  Secret-safe device smoke runner for real post-processing with OpenAI-compatible provider.
- [`memory/p14-p15-feasibility-2026-08-04.md`](./memory/p14-p15-feasibility-2026-08-04.md) —
  Classification of P1.4/P1.5 checklists (runnable in JVM vs hardware-dependent).
- [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md) —
  Implementation of P0.1–P0.4 and P1.1/P1.2 blockers with JVM tests.
- [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md) —
  Comprehensive static audit, P0/P1/P2 debt tracking, and evidence taxonomy.
- [`memory/postprocess-final-only-2026-08-03.md`](./memory/postprocess-final-only-2026-08-03.md) —
  Separation of ASR visual streaming and final-only AI post-processing with atomic commit & fallback.
- [`memory/polish-agents-2026-07-29.md`](./memory/polish-agents-2026-07-29.md) —
  Agentic file cleanup and alignment.
- [`memory/phonetic-corrector-2026-07-28.md`](./memory/phonetic-corrector-2026-07-28.md) —
  Phonetic post-ASR corrector implementation and dictionary matching.
- [`memory/dedup-round-2026-07-27.md`](./memory/dedup-round-2026-07-27.md) —
  Creation of root `AGENTS.md` and spec directory deduplication.

_(Nuevos ficheros de `memory/` se referencian aquí conforme se crean.)_
