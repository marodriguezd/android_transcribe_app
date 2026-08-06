# `.agents/` — Registro persistente entre sesiones y entre agentes

Esta carpeta es el **registro compartido** que múltiples sesiones de asistentes
IA (y por extensión, distintos modelos/herramientas) consultan y actualizan
al trabajar en este repo. Funciona como una **conversación durable** entre
sesiones: lo que se consigue, lo que se está haciendo y lo que se ha hecho no
se pierde al cerrar la conversación porque vive en `git`.

## Language (2026-08-04)

Maintainer decision: new and updated documents in this folder are written in
**English**. Historical documents in Spanish remain as recorded history — keep
them unchanged; only add English sections or new English files.

## Workflow rule: where to build (2026-08-06, fire rule)

Agents must choose where to validate builds based on the host they run on:

- 📱 **Mobile-device-like / embedded host (e.g. an ARM64 Android userspace
  without KVM — like the current dev host): NEVER run heavy local builds.**
  `./gradlew assembleDebug`, `cargo build`, `cargo ndk ...`, NDK-based
  `cargo check`, `assembleRelease`, etc. overload the system and can leave it
  unresponsive (verified 2026-08-06). All build validation is done
  **dynamically through GitHub**: `git commit` + `git push` to `main` triggers
  the debug workflow (`debug_telegram.yml`), which runs every gate — `cargo
  fmt --check`, `check_translations.py`, `testDebugUnitTest`, `assembleDebug`
  (compiles the Rust via cargo-ndk), `lintDebug`, `checkModels` — and sends
  the APK to Telegram. Read the result with `gh run list` / `gh run view` and
  **iterate: fix → push → read CI → repeat**, pushing tests that pass or fail.
- 💻 **Physical machine (maintainer's laptop/desktop):** local builds are fine
  (`./gradlew assembleDebug`, `cargo ndk ...`, JVM tests, etc.) while
  respecting thermal protection (JVM tests do not trigger `cargoNdkBuild`).
- ✅ **Light gates allowed on the embedded host:** static code reading,
  `git diff`, `cargo fmt --check` (does not compile), `python3
  scripts/check_translations.py`, editing XML strings. Nothing that invokes
  Gradle/Cargo/CMake in build mode.
- 🛠️ `gh` is authenticated as the maintainer on the embedded host; use it to
  inspect CI runs instead of compiling locally.

See root `AGENTS.md` §3 "Regla de validación por entorno" for the Spanish
canonical version.

## Cómo usar estos ficheros (orden de lectura al abrir el repo)

1. **`progress.md`** — primero. Refleja el estado actual, lo que está en
   curso, lo que se acaba de terminar y los bloqueos pendientes.
2. **`spec.md`** — segundo. Define el **QUÉ**: objetivo, usuarios,
   restricciones, no-objetivos, superficies funcionales.
3. **`architecture.md`** — tercero. Define el **CÓMO**: stack, módulos,
   flujos clave, invariantes, boundaries.
4. **`INDEX.md`** — cuando necesites el índice cruzado entre todos los docs
   de spec (estos + `AGENTS.md` raíz + `README.md` + `RELEASE_NOTES.md`).
5. **`memory/<topic>-YYYY-MM-DD.md`** — cuando tu tarea se solapa con un
   trabajo previo documentado.
6. **`AGENTS.md` raíz** — siempre que tengas dudas sobre convenciones,
   contrato JNI, marker files, anti-patterns críticos.

## Convenciones de nombres y roles

| Fichero | Pluralidad | Rol | Frecuencia de edición |
|---|---|---|---|
| `progress.md` | **singular**, canónico | Estado actual / en-curso / next | Cada commit relevante |
| `spec.md` | **singular**, canónico | Especificación del proyecto (QUÉ) | Semanas/meses (cambios de alcance) |
| `architecture.md` | **singular**, canónico | Arquitectura del sistema (CÓMO) | Días/semanas (refactors, nuevo módulo) |
| `INDEX.md` | **singular**, índice | Cross-link entre todos los docs de spec | Cuando se añade/quita un doc |
| `memory/<topic>-YYYY-MM-DD.md` | **varios** | Notas de sesiones de trabajo concretas | Cada vez que una tarea termina |
| `README.md` (este fichero) | **singular** | Cómo usar la carpeta `.agents/` | Cuando evolucionan las convenciones |

## Convenciones específicas de `memory/`

- **Naming:** `memory/<topic>-YYYY-MM-DD.md` (e.g.
  `memory/dedup-round-2026-07-27.md`). El `<topic>` agrupa cohorts de commits
  relacionados; la fecha al final ordena cronológicamente sin que `git log`
  o `ls` mezclen sesiones distintas.
- **Coalesce:** si dos ficheros comparten `<topic>`, fusiónalos en el más
  reciente. Mismo `<topic>` no es concurrente; es una misma cohorte que se
  extiende en el tiempo.
- **Mover, no copiar:** al cerrar una tarea, mueve el contenido relevante
  desde `progress.md` a `memory/<topic>-<fecha>.md`. `progress.md` queda
  siempre resumido; `memory/` guarda el detalle.

## Workflow recomendado para una sesión IA

```
   ┌────────────────────────────────────────┐
   │ 1. git pull / fetch                    │
   │ 2. Lee progress.md → ¿hay algo mío?    │
   │ 3. Lee memory/<mi-topic>-<fecha>.md    │
   │       si existe → reanuda              │
   │       si no → arranca fresca           │
   │ 4. Lee spec.md + architecture.md       │
   │       para contexto si dudas sobre QUÉ │
   │       o CÓMO                            │
   │ 5. Trabaja, y al cerrar:               │
   │    • update progress.md                │
   │    • crea o actualiza memory/<…>.md    │
   │    • respeta AGENTS.md raíz si tocas   │
   │      código, contratos, marker files   │
   └────────────────────────────────────────┘
```

## Qué NO poner aquí

- **Datos sensibles** (claves API, PII). Esto va a git y se commitea.
  Excepción: puede haber nombres de proveedores sin la clave.
- **Artefactos binarios** (logs, capturas, modelos). Va a `assets/`,
  `target/`, CI artifacts, etc.
- **Documentación user-facing.** Va a `README.md` raíz.
- **Reglas para IAs.** Va a `AGENTS.md` raíz — esta carpeta registra
  estado del proyecto; las reglas viven en `AGENTS.md`.
- **Prompts extensos o privados.** Las plantillas viven aquí, el contenido
  real generado va al producto.

## Editar con commit-tracked

Estos ficheros van **tracked**, no en `.gitignore`. La razón: la mecánica
"entre sesiones, entre agentes" exige persistencia + sincronización con
otros clones/máquinas del mantenedor. Mantenerlos en git es lo que da
esa garantía.

Si tu rama local tiene `.agents/` en `.gitignore`, **quítalo** antes de
contribuir estos ficheros.
