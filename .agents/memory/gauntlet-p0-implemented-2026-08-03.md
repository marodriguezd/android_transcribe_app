# Implementación de deuda P0/P1 — 2026-08-03

**Tema:** ejecución del plan del Guantelete (GAUNTLETE_PLAN.md §3–§4): los cuatro
bloqueadores P0 y las dos primeras líneas P1 quedan implementados con tests JVM.

**Alcance de esta sesión:** cambios de código + tests + docs. Validación
ejecutada localmente: `testDebugUnitTest` (32 tests, BUILD SUCCESSFUL),
`python3 scripts/check_translations.py` (PASS 6 locales), `rustfmt --check` de
`src/subtitle.rs` y `src/transcribe_file.rs`, `git diff --check`. **No** se
ejecutaron `assembleDebug`/`lintDebug`/`checkModels` (sin NDK local) ni smoke
tests en dispositivo: eso sigue pendiente de CI/hardware.

## Qué se implementó

### P0.1 — Cancelación del postprocesado por propietario

- `PostProcessor` extrae el registro de llamadas en vuelo a
  `PostProcessor.CallRegistry` (mapa `Call → owner`, identidad).
  - `cancelAll()` queda reservado para shutdown global real (IME muerto,
    toggle PP-off + broadcast a `:ime`).
  - `cancelAllFor(owner)` cancela sólo las llamadas de esa superficie.
  - Registro con owner `null` usa un centinela `NO_OWNER` (ConcurrentHashMap
    prohíbe valores null; las llamadas sin owner siguen siendo cancelables
    globalmente).
- Superficies actualizadas para pasar `this` como owner y cancelar sólo lo
  suyo: `RustInputMethodService`, `RecognizeActivity`, `VoiceRecognitionService`,
  `TranscribeFileActivity`, `PostProcessSettingsActivity` (onDestroy/onCancel).
- `PostProcessSettingsActivity.save()` y el receiver `CANCEL_PP` del IME
  conservan `cancelAll()` a propósito (evento global "PP desactivado").

### P0.2 — Worker de subtítulos con generación

- `src/subtitle.rs`: contador `GENERATION` (AtomicU32) bumpado en cada
  `initNative` y `cleanupNative`; `Job` y `LiveSubtitleState` llevan la
  generación. El worker:
  - descarta jobs de generación vieja sin transcribir (no quema CPU);
  - no fusiona finals de otra generación;
  - re-verifica generación antes de transcribir y antes de cada `deliver`
    (nunca entrega a un overlay retirado o a la sesión nueva).
  - Al drenar el canal, el worker termina y suelta su GlobalRef.
- `LiveSubtitleService.java`: `setupOverlay()` resetea la ventana de
  transcripción (mCommittedText/base/window/displayed); `removeOverlay()`
  anula `mSubtitleText` para que callbacks tardíos salgan por el guard.

### P0.3 — SHA-256 en la descarga runtime debug

- Nueva utilidad `FileSha256` (pure-JVM, testeada) con `sha256Hex(File/InputStream)`.
- `MainActivity.startDebugModelDownload()`: constantes `DEBUG_MODEL_NAME` y
  `DEBUG_MODEL_SHA256` (mismo hash que `modelPackFiles` en `app/build.gradle.kts`);
  borra `.tmp` residual antes de descargar; verifica el hash **antes** del
  rename/activación; mismatch → borra temporal, error con retry. `active_model`
  se escribe vía `MarkerFileHelper.writeString` (atómico).

### P1.1 — Operation-id para transcripción de archivos

- `src/transcribe_file.rs`: `transcribeAudio` gana `op_id: jint`; usa
  `jni_util::notify_status_with_session`/`notify_text_with_session` (se eliminan
  los helpers locales duplicados).
- `TranscribeFileActivity.java`: `AtomicInteger NEXT_OP` estático (único entre
  instancias), `currentOpId` por Activity, incrementado al iniciar decode y en
  `onDestroy`; `onTextTranscribed(String, int)` y `onStatusUpdate(String, int)`
  ignoran opIds obsoletos y `isFinishing()/isDestroyed()`.

### P1.2 — Atomicidad de markers cross-process

- `MarkerFileHelper`: temp **único por escritura** (`fileName + ".tmp" + threadId
  + nanoTime`) en `writeString` y `writeStringToFile`. Bug real cazado por el
  test: writers concurrentes compartían `*.tmp`, y el rename de uno movía el
  fichero que otro seguía escribiendo → lecturas parciales.
- `ModelsActivity.readConfig/writeConfig` y `App.writeConfig` ahora pasan por
  `MarkerFileHelper` (temp+fsync+rename; delete en vacío) en lugar de
  `FileOutputStream` directo.

### P0.4 — Toolchain unificada

- `app/build.gradle.kts`: `ndkVersion = "28.0.13004108"` (la que CI instala en
  ambos workflows y documentan README/AGENTS). Sin cambios de SDK: queda 34/34/26
  como fuente efectiva (Android 15/SDK 35 sigue pendiente de decisión).
- Detección de host `ndkPrebuiltDir()`: `linux-x86_64`, `linux-aarch64` (si el
  NDK la trae), `darwin-x86_64`, `darwin-arm64`, `windows`; usado en
  `CMAKE_SYSROOT` y en la copia de `libc++_shared.so`. Host sin prebuilt →
  GradleException con mensaje claro (límite declarado).

## Tests nuevos (JVM, sin Android framework)

- `CallRegistryTest` (4): aislamiento por owner, cancelAll global, unregister,
  owner null. Usa MockWebServer con respuestas retardadas para que las llamadas
  estén determinísticamente en vuelo.
- `MarkerAtomicityTest` (1): lectores concurrentes nunca observan un valor
  parcial bajo 3 writers con payloads de 20 KB.
- `FileSha256Test` (3): vectores conocidos, truncado ≠ completo, file ≡ stream.
- `PostProcessorTest` (8, P1.3): payload `/chat/completions` con `stream:false`,
  `${output}` inyectado exactamente una vez, transcript como user message sin
  marcador, respuesta vacía → error, HTTP 500 → error, JSON inválido → error,
  toggle-off durante vuelo → fallback al texto crudo, y exactamente una entrega
  final por petición. El **timeout real de OkHttp** se cubre con un client de
  valores escalados vía seam `PostProcessor.setSharedClientForTests` (read
  timeout 100 ms contra un body retrasado 5 s → `onFailure` → onError) y los
  valores de producción se asertan con `PostProcessor.buildProductionClient()`
  (connect 30 s, read/write 60 s). Se usa el seam
  `PostProcessor.PostProcessorSettings` (SettingsManager lo implementa en
  producción; fake en tests).

Dependencias de test añadidas: `mockwebserver:4.12.0` y `org.json:json:20240303`
(el org.json del android.jar está stubbeado y devolvería null en JVM pura; el
android.jar va al final del classpath de test, así que el jar real lo ensombrece).
`unitTests.isReturnDefaultValues = true` para que `android.util.Log` en los
caminos de error no lance "not mocked".

### P2.4 — Strings visibles migradas a recursos

- 44 strings nuevas en los 7 locales (gate `check_translations.py` PASS,
  174 translatables): IME, popup, transcripción de archivos, subtítulos
  (toasts/overlay/notificación), `action_close` y errores de la descarga debug.
- Layouts sin texto hardcodeado (`android:text`/`contentDescription` → `@string`).
- Excepción documentada: detalles de error de `PostProcessor` (sin Context,
  seam JVM de P1.3) y strings de protocolo JNI (comparadores de la máquina de
  estados) se mantienen como literales; los nombres de modelo son nombres
  propios técnicos.

## Estado del Guantelete

Sigue **ABIERTO**: la implementación P0 y P1.1–P1.3 está hecha y gateada por
JVM (32 tests, BUILD SUCCESSFUL), pero la regla de cierre exige además CI
(`assembleDebug`, `lintDebug`, `checkModels`), Rust real/`cargo test` (bloqueado
por `transcribe-cpp-sys` v0.1.3), P1.4 y smoke test en dispositivo (subtítulos
start/stop/start, revocación MediaProjection, PP concurrente entre superficies,
descarga debug truncada). Ver checklist en `static-audit-debt-2026-08-03.md`.
