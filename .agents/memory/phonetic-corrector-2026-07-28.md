# phonetic-corrector — 2026-07-28

**Tema:** implementación del corrector fonético post-ASR de palabras
personalizadas (fork addition) + investigación de FUTO Voice Input.

## Contexto inicial

El usuario pidió (en orden, a lo largo de la sesión):

1. Analizar si existe algún mecanismo de "palabras calientes" o reemplazo
   por proximidad a un diccionario. → **No existe**; el post-procesado LLM
   tiene una regla genérica de corrección de typos (regla #7 del prompt),
   pero no hay diccionario del usuario ni matching fonético.
2. Investigar la mejor manera de implementarlo, mirando cómo lo hacen
   FUTO Keyboard y Handy Computer (transcribe.cpp).
3. Investigar específicamente cómo hace FUTO Voice Input su custom
   vocabulary antes de implementar.
4. Implementar el diseño completo ("de manera correcta, profunda y
   perfecta").

## Decisiones tomadas

### Diseño del corrector (4 decisiones del usuario)

| Decisión | Elegida | Razón |
|---|---|---|
| Ubicación | **Rust (`transcribe_shared`)** | Cubre TODAS las superficies (IME, popup, subtítulos, SpeechRecognizer, archivo, benchmark) de golpe sin cablear cada callback Java |
| Formato diccionario | **Lista de términos correctos** | Una palabra por línea en `filesDir/custom_words`; más simple que pares alias→término |
| Alcance | **Todas las superficies, incluidos subtítulos** | El corrector vive en `transcribe_shared` antes del callback, así los parciales de subtítulos también se corrigen |
| Semántica | **Fonética + n-gram coseno (ligero)** | Núcleo fonético (Levenshtein sobre claves fonéticas ES+EN) + tiebreak ortográfico; sin modelo neural (~0 KB extra) |

### Por qué post-filtro fonético y no `initial_prompt` de Whisper

Se investigó el código fuente de FUTO Voice Input (clonado de
`github.com/futo-org/voice-input`):

- **Mecanismo de FUTO:** `PERSONAL_DICTIONARY` → string libre en DataStore →
  `"(Glossary: ${glossaryCleaned})"` → `wparams.initial_prompt` de
  whisper.cpp (`WhisperModel.kt:147-151`, `voiceinput.cpp:157-158`).
- **FUTO admite en un TODO** que "sólo funciona bien para inglés" y "puede
  causar comportamiento raro con otros idiomas".
- **No hay post-filtro fonético** en FUTO (grep exhaustivo: 0 hits de
  Levenshtein/Soundex/Metaphone/corrección post-ASR).

El post-filtro fonético propuesto es **superior para el caso multilingüe
(ES+EN)** porque:
1. Funciona con las 16 familias de transcribe.cpp, no sólo Whisper.
2. Es determinista (reemplazo garantizado) vs probabilístico (sesgo
   ignorable).
3. No contamina cada chunk de 30s del ASR.
4. Cubre el caso multilingüe donde FUTO admite que falla.

### Implementación

- **`src/corrector.rs`** (NUEVO, ~300 líneas): codificador fonético ES+EN
  (seseo/yeísmo, h muda, v→b, qu→k, g/j→h, ñ→ny, ph→f, th→d, etc.),
  `strsim::levenshtein` con `MAX_PHONETIC_DISTANCE=2`, bigram coseno de
  tiebreak, tokenizer que preserva puntuación/espacios y trata
  `transcribe.cpp`/`state-of-the-art` como tokens únicos, matching
  multi-palabra por ventanas (longest-first, agrupado por word count),
  diccionario mtime-cached, safe-fallback en cualquier fallo.
- **`strsim = "0.11"`** añadida a `Cargo.toml` (pure Rust, MIT, <20 KB).
- **`engine.rs`:** `set_files_dir` en `do_load` (antes de ambos code paths
  para que funcione con modelos importados Y bundled); `correct_if_enabled`
  en `transcribe_shared` con su **propio `catch_unwind`** (complementa el
  del engine — dos capas independientes para dos componentes
  independientes).
- **Java:** `CustomWordsActivity` (editor del marker file), card en
  `activity_main.xml`, wiring en `MainActivity.java`, registro en
  `AndroidManifest.xml`.
- **i18n:** 10 strings `cw_*` en los 7 locales.

## Errores y callejones sin salida

- **`set_files_dir` mal colocado (ronda 1 de review):** originalmente se
  puso después del early-return del modelo importado, lo que hacía que el
  corrector **nunca funcionara con modelos importados**. Arreglado moviendo
  la llamada antes del bloque `if let Some(name)`.
- **Corrector fuera de `catch_unwind` (ronda 1):** el comentario decía
  "safe-fallback en panic" pero la llamada estaba fuera del `catch_unwind`
  del engine. Un panic en el corrector habría escapado a JNI y congelado el
  IME. Arreglado con su propio `catch_unwind` que devuelve el texto crudo.
- **Mutex poison (ronda 1):** los 4 `Mutex::lock().unwrap()` del corrector
  propagarían poison en vez de recuperar. Cambiados a
  `unwrap_or_else(|p| p.into_inner())` (patrón del engine, AGENTS.md §5.1).
- **Multi-word word-count mismatch (ronda 3):** `dict.multi` era un
  `Vec<Term>` sin agrupar por word count, lo que podía causar out-of-bounds
  si un término de 4 palabras matcheaba una ventana de 2. Arreglado
  cambiando a `HashMap<usize, Vec<Term>>` (key = word count) y filtrando
  candidatos por window size.
- **Build Gradle no ejecutado:** el entorno no tiene NDK 28 instalado
  (`/opt/android-sdk` sin directorio `ndk/`). El `cargo check` del módulo
  corrector standalone sí pasó limpio (9/9 tests).

## Commits / PRs

- Sin commit todavía (cambios en working tree, pendiente de validación
  con NDK en máquina del mantenedor).

## Lessons learned (para futuras sesiones)

1. **`cargo check` standalone para módulos Rust sin NDK:** cuando el
   entorno no tiene NDK, se puede validar un módulo Rust nuevo creando un
   `Cargo.toml` temporal en `/tmp` con sólo las deps del módulo
   (`strsim`, `once_cell`, `log`) y copiando el `.rs` como `lib.rs`. Esto
   confirma compilación + tests sin necesidad del toolchain Android
   completo.
2. **Code-reviewer-glm en rondas iterativas:** para features complejas,
   lanzar el reviewer tras cada ronda de fixes (no sólo al final) catching
   issues que se introducen al arreglar otros. En esta sesión: 4 rondas,
   7 issues total, todas arregladas.
3. **FUTO Voice Input usa `initial_prompt`, no post-filtro:** si una IA
   futura quiere replicar el "Personal Dictionary" de FUTO, debe saber que
   FUTO admite (en un TODO) que su enfoque "sólo funciona bien para inglés".
   El post-filtro fonético de este fork es deliberadamente diferente.
4. **`set_files_dir` debe llamarse antes de AMBOS code paths** (importado y
   bundled) en `do_load`. El early-return del modelo importado es fácil de
   pasar por alto al insertar código nuevo en `do_load`.
5. **Multi-word matching requiere agrupar por word count.** Sin esto, un
   `best_term` sobre todos los términos multi-palabra puede devolver un
   término con word count ≠ window, causando out-of-bounds o reemplazo
   parcial incorrecto.
