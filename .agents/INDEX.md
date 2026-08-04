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

- [`memory/release-0.1.24-prep-2026-08-04.md`](./memory/release-0.1.24-prep-2026-08-04.md) —
  v0.1.24 release preparation: privacy logging, model-import hardening,
  CI gates (rustfmt/checkModels/APK verification), JVM harness closure
  (DNS fail + connect timeout, 34 tests), English publication metadata and
  the maintainer decisions behind them.
- [`../scripts/README.md`](../scripts/README.md) —
  secret-safe device smoke runner for real post-processing with an
  OpenAI-compatible provider; documents resource-ID UI automation, cleanup,
  and future-release usage.
- [`memory/p14-p15-feasibility-2026-08-04.md`](./memory/p14-p15-feasibility-2026-08-04.md) —
  clasificación de los checklists P1.4/P1.5 según qué pasos son ejecutables
  desde el entorno actual (sin dispositivo físico) y cuáles requieren
  hardware; plan para añadir 2 tests JVM pendientes (DNS fail, connect
  timeout) antes del smoke en dispositivo.
- [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md) —
  implementación de los bloqueadores P0.1–P0.4 y P1.1/P1.2 con tests JVM
  verdes; Guantelete sigue abierto pendiente de validación CI/dispositivo.
- [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md) —
  auditoría estática integral, deuda P0/P1/P2, criterios de aceptación y
  taxonomía de evidencia; estado del Guantelete abierto.
- [`memory/postprocess-final-only-2026-08-03.md`](./memory/postprocess-final-only-2026-08-03.md) —
  separación del streaming visual del ASR y el postprocesado AI final-only con
  commit atómico y fallback al transcript crudo.



- [`memory/polish-agents-2026-07-29.md`](./memory/polish-agents-2026-07-29.md) —
  limpieza y puesta al día de ficheros agénticos: `progress.md` al día,
  referencias `specs.md` → `INDEX.md` corregidas en `dedup-round`,
  preguntas resueltas consolidadas.
- [`memory/phonetic-corrector-2026-07-28.md`](./memory/phonetic-corrector-2026-07-28.md) —
  implementación del corrector fonético post-ASR + investigación de FUTO
  Voice Input + 4 rondas de code-review.
- [`memory/dedup-round-2026-07-27.md`](./memory/dedup-round-2026-07-27.md) —
  creación del `AGENTS.md` raíz + dedup con `README.md` + bootstrap de
  esta carpeta.

_(Nuevos ficheros de `memory/` se referencian aquí conforme se crean.)_
