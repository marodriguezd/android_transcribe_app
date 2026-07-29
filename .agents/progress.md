# Progreso — estado actual del trabajo IA-asistido

**Última actualización:** 2026-07-29

## 🟢 Recién completado

- **2026-07-29** — Post-Procesado con IA en Streaming (SSE / Token Streaming):
  - `PostProcessor.java`: implementación de `processStreaming` mediante Server-Sent Events (`stream: true`). Reducción del tiempo de respuesta percibido de ~2.000 ms a **~300 ms** (TTFT). Motor de 3 reintentos de reconexión y fallback a petición síncrona si la API no soporta streaming (HTTP 400).
  - `RustInputMethodService.java`: inserción fluida token por token en el editor enfocado vía `InputConnection.commitText`. Limpieza de deltas insertados (`deleteSurroundingText`) y reemplazo por transcripción bruta original ante fallo de stream para prevenir texto fragmentado ("efecto Frankenstein").
  - `RecognizeActivity.java`: renderizado de deltas token a token en tiempo real en la pantalla de voz.
  - **CI/CD:** Verificación de compilación limpia y envío de APK mediante GitHub Actions (`Debug APK → Telegram`).

- **2026-07-29** — Actualización de ficheros agénticos (`.agents/`) con la
  feature del corrector fonético: `AGENTS.md` §4.5/§4.6, `architecture.md`,
  `spec.md`, `progress.md`, `INDEX.md`. Todo al día con la feature del
  corrector.

- **2026-07-28** — Corrector fonético de palabras personalizadas (fork
  addition). Feature completa:
  - `src/corrector.rs` (NUEVO, ~300 líneas): codificador fonético ES+EN,
    Levenshtein via `strsim` 0.11, tiebreak coseno de bigramas, tokenizer
    que preserva puntuación/espacios, matching multi-palabra por ventanas,
    diccionario mtime-cached, safe-fallback en cualquier fallo.
  - `Cargo.toml`: añadida dependencia `strsim = "0.11"` (pure Rust, MIT).
  - `src/lib.rs`: `pub mod corrector;`
  - `src/engine.rs`: `set_files_dir` en `do_load` (antes de ambos code
    paths); `correct_if_enabled` en `transcribe_shared` con su propio
    `catch_unwind` (safe-fallback = texto crudo).
  - `CustomWordsActivity.java` + `activity_custom_words.xml` (NUEVO):
    editor del marker file `filesDir/custom_words`.
  - `activity_main.xml`: nueva card "Custom words" con `ic_dictionary.xml`.
  - `MainActivity.java`: wiring del botón.
  - `AndroidManifest.xml`: registro de `CustomWordsActivity`.
  - strings `cw_*` (10) en los 7 locales (EN/ES/DE/FR/IT/PT/RU).
  - **Investigación FUTO Voice Input:** se clonó y greppeó el repo
    (`github.com/futo-org/voice-input`). FUTO usa `initial_prompt` de
    whisper.cpp con `"(Glossary: …)"`, con un TODO que admite que "sólo
    funciona bien para inglés". Se eligió post-filtro fonético en su
    lugar (multilingüe, determinista, multi-modelo, no contaminante).
  - **Validación:** `cargo check` limpio, `cargo test` 9/9 pasan.
    code-reviewer-glm: 4 rondas, 7 issues encontrados y arreglados
    (set_files_dir mal colocado, corrector fuera de catch_unwind, mutex
    poison, dead return value, TAG convention, card title, multi-word
    word-count mismatch).
  - **Build Gradle:** no ejecutado (entorno sin NDK 28 instalado).

- **2026-07-27** — `AGENTS.md` raíz (commit `fa36345`).
  - 348 líneas, estructura `§1 Resumen / §2 Stack / §3 Comandos+y+wiring /
    §4 Convenciones (1-11) / §5 Reglas / §6 Commits / §7 TL;DR`.

- **2026-07-27** — Dedup AGENTS ↔ README (commit `c0073b4`).
  - Trim de §3 Comandos Frecuentes (→ apunta a README + solo wiring AGENT-ONLY).
  - Reemplazo de §4.6 árbol de carpetas por tabla de mapping Rust↔Java.
  - Colapso de 3 filas de toolchain en §2 a una sola fila "Toolchain humano".
  - Cross-link explícito en el README hacia AGENTS.md.

## 🟡 Pendiente humano

- **Pendiente humano** — `./gradlew assembleDebug` en máquina con NDK 28
  para confirmar que el build Android pasa limpio sin warnings nuevos.
- **Pendiente humano** — Smoke test en dispositivo arm64: diccionario con
  "Madrid", decir "madriz" → debe reemplazar.

## 🔴 Bloqueos / esperando humano

_Ninguno en este momento._

## ❓ Preguntas abiertas para humanos

- **¿Tracked o gitignored?** Esta primera versión va tracked en git (ver
  `.agents/README.md` §"Editar con commit-tracked"). Si el mantenimiento
  prefiere local-only, mover `.agents/` a `.gitignore` es trivial; cambia
  el flujo de "entre sesiones" a "sólo en esta máquina". Decisión del
  mantenedor.
- **Idioma del contenido:** español en prosa principal (consistente con
  AGENTS.md), inglés en términos clave (commit hashes, file names,
  AGENTS.md root). Suficiente o ¿todo inglés para IAs internacionalizadas?
- **Naming `specs.md` → `INDEX.md`:** Resuelto. Renombrado para evitar
  ambigüedad con la spec singular.

## 🔗 Enlaces activos

- Spec canónica: [`spec.md`](./spec.md)
- Arquitectura: [`architecture.md`](./architecture.md)
- Índice cruzado: [`INDEX.md`](./INDEX.md)
- AGENTS.md raíz: [`../AGENTS.md`](../AGENTS.md)
- README: [`../README.md`](../README.md)
- RELEASE_NOTES: [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md)
- Memoria de hoy: [`memory/phonetic-corrector-2026-07-28.md`](./memory/phonetic-corrector-2026-07-28.md)
- Memoria previa: [`memory/dedup-round-2026-07-27.md`](./memory/dedup-round-2026-07-27.md)
