# Unreleased

## 🔒 Robustez del postprocesado, subtítulos, modelos y toolchain

### 🤖 Postprocesado aislado por superficie
- La cancelación del postprocesado ya es **por superficie y sesión**: cerrar la
  pantalla del popup, cancelar un reconocimiento o destruir una Activity ya no
  interrumpe una petición legítima de otra superficie (IME, servicio de
  reconocimiento, transcripción de archivo, ajustes).
- El `cancelAll` global queda reservado para los eventos realmente globales:
  apagar el postprocesado en ajustes (con broadcast al proceso del teclado) y
  la destrucción del servicio del IME.
- El contrato HTTP queda blindado por tests JVM con servidor controlado
  (MockWebServer): payload `/chat/completions` con `stream:false`, transcript
  inyectado una sola vez, errores HTTP/JSON con fallback al texto crudo, una
  sola entrega final por petición y el **timeout real de OkHttp** con valores
  escalados (los 30 s/60 s de producción se verifican como valores aplicados;
  el transcurso real depende de la red y se valida en dispositivo).

### 🎬 Subtítulos: sin callbacks de sesiones anteriores
- Cada sesión de subtítulos tiene una generación: al detener y reiniciar, los
  trabajos pendientes de la sesión anterior se descartan sin transcribir y sin
  pintar sobre el overlay nuevo; el texto acumulado se reinicia en cada
  sesión.

### 📦 Descarga del modelo en debug: verificada antes de activar
- El modelo descargado en builds debug se verifica con SHA-256 antes de
  activarse: un fichero truncado o alterado nunca se marca como modelo activo
  y se puede reintentar, igual que la garantía del build de release
  (`checkModels`).

### 🛠️ Toolchain y markers
- NDK unificado a `28.0.13004108` en Gradle, CI y documentación; las rutas del
  toolchain se resuelven según el host (Linux x86_64/aarch64, macOS
  Intel/ARM, Windows).
- Todas las escrituras de ajustes (`model_language`, `active_model`, etc.) son
  atómicas (temp + rename): lectores concurrentes de los procesos principal y
  del teclado nunca ven un valor parcial.

### 🗂️ Transcripción de archivos con operation-id
- Los callbacks de transcripción de archivos llevan un id de operación: rotar,
  cerrar o recrear la pantalla durante el decode/ASR no actualiza la instancia
  equivocada.

### 🌐 Traducciones: strings visibles migradas a recursos
- Estados, errores y toasts antes hardcodeados en Java/layouts (teclado,
  popup, transcripción de archivos, subtítulos y descarga del modelo debug)
  ahora son recursos traducidos en los 7 idiomas.
- Excepción documentada: los detalles de error del postprocesado (capa sin
  contexto Android) y los nombres de los modelos se mantienen en inglés a
  propósito.

---

## 🤖 Postprocesado AI final-only
- Se mantiene la previsualización en streaming del transcriptor para conservar la fluidez durante la grabación.
- Al terminar, el transcript final se envía una sola vez al modelo de postprocesado y se espera la respuesta completa antes de pegarla.
- El IME y el popup ya no pegan tokens parciales del LLM. Si el postprocesado está desactivado, falla, se cancela o devuelve una respuesta vacía, se pega la transcripción original.

---

# v0.1.24

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app).

## What's new in v0.1.24

### ⌨️ IME keyboard keeps its shape while streaming partials
- **Mic area no longer compacts:** when live partial hypotheses appear (the keyboard's live window shows up to 3 lines, always scrolled to the latest words), the record area used to shrink from 200dp to 148dp to hold the keyboard's total height — clipping the mic glow and overlapping the "Tap to Stop" hint with the record button. Now the record area always keeps its full shape: the keyboard grows slightly while live text is shown and returns to normal when the recording ends.

### 🛠️ AI Post-Processing: readable prompt & smarter LLM payload
- **Prompt formatting restored:** the default system prompt (`pp_default_prompt`) now compiles with real line breaks (`\n` escapes). It shows its full paragraph structure in the post-processing settings and reaches the LLM properly structured — Android's `aapt2` was collapsing every newline into a single run-on line, which garbled the settings screen and flattened the instructions sent to the model.
- **Transcript de-duplication:** when the active prompt embeds the transcript via the `${output}` marker, the raw text is no longer sent a second time as a separate user message. The LLM now refines the text instead of mirroring the input back unchanged.
- **Streaming robustness:** mid-stream failures no longer trigger retries (which could duplicate text already committed into the editor); if the editor loses focus during streaming, the refined text is committed once available instead of being dropped; and defensive guards prevent rare IME force-closes when the input connection dies mid-stream.

---

# v0.1.23

Fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app). This release brings real-time **SSE streaming AI post-processing**, fixes **silence auto-stop for the IME keyboard**, and introduces seamless **Android System User Dictionary integration (FUTO Keyboard style)**.

## What's new in v0.1.23

### 🚀 Real-time Streaming AI Post-Processing (SSE)
- **Token-by-token streaming:** AI post-processing now uses Server-Sent Events (`stream: true`). Text starts refining in real-time with Time-To-First-Token down to **~300 ms** (previously ~2,000 ms block wait).
- **Live insertion in IME & Voice Popup:** Refined tokens stream directly into the focused text input field in `RustInputMethodService` and display live in `RecognizeActivity`.
- **Resilient & No "Frankenstein" text:** Automatic 3-attempt reconnect retry on mid-stream drops. On persistent connection loss, partial deltas are cleaned up (`deleteSurroundingText`) and the raw transcript is delivered clean without text corruption.
- **Provider Fallback:** Automatic fallback to standard block requests if a provider returns HTTP 400 (`stream` not supported).

### ⏱️ IME Silence Auto-Stop & Adaptive VAD Tuning
- **IME Keyboard Auto-Stop:** Fixed silence auto-stop (`auto_stop` marker) being hardcoded to `false` in the `:ime` keyboard process. Now the keyboard automatically stops and transcribes after 2 seconds of trailing silence.
- **Adaptive VAD Heuristics:** Lowered minimum speech level threshold (`MIN_SPEECH_LEVEL = 0.05` and `SPEECH_MARGIN = 0.04`) in `src/voice_session.rs` with dynamic noise floor tracking, reliably capturing soft and quiet speech.

### 📖 Android System User Dictionary Integration (FUTO Keyboard Style)
- **Native Android System Menu:** Tapping **Custom Words** in `MainActivity` opens Android's native Personal User Dictionary settings (`Settings.ACTION_USER_DICTIONARY_SETTINGS`) with string action and general settings fallbacks for custom OEM ROMs (Samsung OneUI, Xiaomi MIUI, etc.).
- **Automatic Sync:** Reads Android's `UserDictionary.Words` ContentProvider (`READ_USER_DICTIONARY` permission) on app start and right before every voice recording session, syncing system words into Rust's phonetic corrector (`src/corrector.rs`) across all surfaces.

---

*For full historical release notes (v0.1.22 and older), see [CHANGELOG.md](CHANGELOG.md).*
