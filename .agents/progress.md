# Progreso — estado actual del trabajo IA-asistido

**Última actualización:** 2026-08-03

## 🟢 Recién completado

- **2026-08-03** — Post-procesado AI final-only: se mantiene el streaming del
  transcriptor como previsualización visual y se elimina el streaming SSE del
  postprocesador. Tras el texto final de ASR, `PostProcessor.process()` envía
  una única petición completa; el IME y el popup entregan una sola vez el
  refinado, o la transcripción cruda si PP está apagado, falla, se cancela o
  devuelve una respuesta vacía/no válida. La integración ya no pega tokens
  intermedios ni necesita borrar texto parcialmente insertado.


## 🟢 Recién completado

- **2026-07-29** — Integración Simplificada del Diccionario del Sistema Android (Estilo FUTO Keyboard):
  - `UserDictionaryHelper.java`: helper que abre la pantalla nativa de Ajustes del Diccionario de Usuario de Android (`Settings.ACTION_USER_DICTIONARY_SETTINGS`) con fallbacks a cadenas de acción e `Intent` de ajustes generales.
  - Sincronización automática de palabras del sistema desde `UserDictionary.Words.CONTENT_URI` hacia el marcador `custom_words` al volver a la app y antes de iniciar cada sesión de voz en el teclado IME o ventana emergente.
  - `AndroidManifest.xml`: añadido permiso `android.permission.READ_USER_DICTIONARY`.
  - **CI/CD:** Compilación verificada e integrada en GitHub Actions (commit `3b837c5`, workflow `30475726536`).

- **2026-07-29** — Habilitación de Auto-Parada tras 2s de Silencio en Teclado IME y Calibración Adaptativa VAD:
  - `RustInputMethodService.java` & `src/ime.rs`: eliminación de la restricción hardcodeada `false`. Transmisión del marcador `auto_stop` vía JNI y adición del callback `onAutoStop()` para auto-parar y procesar texto automáticamente en el teclado.
  - `src/voice_session.rs`: calibración adaptativa de VAD (`MIN_SPEECH_LEVEL = 0.05` y `SPEECH_MARGIN = 0.04`) sobre el suelo de ruido en tiempo real para detectar voz suave/susurrada.
  - **CI/CD & Verificación:** Compilación verificada en GitHub Actions (commit `8e587d9`, workflow `30474594163`) enviada por Telegram. **Confirmado y validado como funcionando correctamente por el usuario.**

- **2026-07-29** — Post-Procesado AI en streaming (histórico, reemplazado el 2026-08-03):
  - Se conserva como antecedente porque la implementación SSE/token-streaming
    fue deliberadamente sustituida por el flujo final-only de arriba tras
    observar que los tokens intermedios no daban un resultado fiable en el
    editor.

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
