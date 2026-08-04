# GAUNTLETE_PLAN.md — Plan de cierre QA de `android_transcribe_app`

> Este documento es el plan operativo del Guantelete. No declara que el
> repositorio esté cerrado: separa evidencia estática, gates ejecutables y
> validación que aún requiere código, CI o dispositivo.
>
> Auditoría de referencia: [`.agents/memory/static-audit-debt-2026-08-03.md`](.agents/memory/static-audit-debt-2026-08-03.md).

## 1. Estado actual

**Estado: ABIERTO — deuda P0 implementada; pendiente de validación CI/dispositivo.**

El 2026-08-03 se implementaron los cuatro bloqueadores P0 y las líneas P1.1–P1.3
(ver [`memory/gauntlet-p0-implemented-2026-08-03.md`](.agents/memory/gauntlet-p0-implemented-2026-08-03.md)).
`testDebugUnitTest` (34 tests), translations y rustfmt de los ficheros tocados
pasaron localmente; `assembleDebug` y `lintDebug` quedaron **validados en CI**
el 2026-08-03 (runs `30859369221`/`30859370506` del fix `aa08a08`, APK
`app-debug-apk-v0.1.24` enviado a Telegram). Para la preparación de 0.1.25,
las mejoras posteriores a 0.1.24 ya están implementadas; siguen pendientes de
CI/hardware:
`checkModels` (workflow release), `assembleRelease`, Rust real y los smoke
tests en dispositivo — sin ellos no se declara el Guantelete cerrado.

### Taxonomía obligatoria de evidencia

| Etiqueta | Significado |
|---|---|
| Diseño | contrato o intención documentada; no prueba ejecución |
| Auditoría estática | código/docs/XML comparados sin ejecutar builds |
| Validado localmente | comando concreto ejecutado, salida conservada |
| Validado en CI | workflow y run identificables |
| Validado en dispositivo | API, modelo, hardware y pasos identificados |

## 2. Gates actualmente disponibles

Estos gates existen en el repositorio o en CI, pero deben ejecutarse y conservar
su evidencia para cada cambio relevante:

| Gate | Qué cubre | Limitación conocida |
|---|---|---|
| `testDebugUnitTest` | 34 tests JVM (markers/subtitle prefs + cancelación por owner + atomicidad de markers + SHA-256 + suite HTTP del postprocesado con timeout real por seam + DNS fail real con host `.invalid` y connect timeout por seam contra `192.0.2.1` desde 2026-08-04) | No cubre JNI, engine ni lifecycle Android; los timeouts de producción (30 s/60 s) se asertan pero no se esperan en wall-clock |
| `checkModels` | SHA-256 de assets bundled presentes | No cubre la descarga runtime debug si no se añade su hash |
| `scripts/check_translations.py` | Paridad de nombres en seis locales alternativos | No detecta todas las cadenas hardcodeadas en Java |
| `lintDebug` | Lint del variant debug | No sustituye pruebas de dispositivo ni garantiza release |
| `assembleDebug`/`assembleRelease` | Integración Gradle, recursos, JNI y APK | Necesita toolchain coherente; no es smoke test funcional |
| tests Rust espejo | Lógica pura aislada (`audio`/`corrector`) | No son `cargo test` del crate cdylib real |
| `rustfmt` parcial | Formato de archivos Rust concretos | Debe ampliarse al alcance real modificado |

## 3. Bloqueadores P0 — resolver antes de “Guantelete cerrado”

### P0.1 Postprocesado: cancelación por operación

- **Problema:** `PostProcessor.cancelAll()` usa un registro global de `Call`.
- **Objetivo:** cada dictado/fetch de modelos tiene operation-id y propietario.
- **Pruebas:** dos superficies concurrentes; cancelar A no cancela B; toggle off
  sólo produce fallback en la sesión afectada.
- **Criterio de cierre:** tests JVM/HTTP controlados verdes + revisión de
  lifecycle en IME, popup, RecognitionService, archivo y settings.
- **Estado: implementado 2026-08-03.** `CallRegistry` con owner por identidad;
  `cancelAllFor(owner)` en las cinco superficies; `cancelAll()` global sólo para
  shutdown real / toggle PP-off. Tests: `CallRegistryTest` (4, MockWebServer)
  + `PostProcessorTest` (10, P1.3) verdes. Pendiente: smoke de PP concurrente en
  dispositivo.

### P0.2 Subtítulos: worker generacional y cleanup determinista

- **Problema:** el worker conserva receiver/GlobalRef tras `cleanupNative`; no
  existe invalidación generacional explícita.
- **Objetivo:** token de sesión, cierre de canal, no entrega tras invalidación y
  espera/confirmación de terminación.
- **Pruebas:** start → stop → start repetido, revocación MediaProjection,
  destrucción de servicio y overlay recreado.
- **Criterio de cierre:** cero callbacks de una generación anterior y cero
  actualizaciones sobre overlay eliminado.
- **Estado: implementado 2026-08-03.** Generación `AtomicU32` bumpada en
  `initNative`/`cleanupNative`; jobs portan generación; el worker descarta jobs
  viejos (sin transcribir ni entregar) y termina al drenar su canal. Java:
  reset de la ventana en `setupOverlay` y `mSubtitleText = null` en
  `removeOverlay`. Pendiente: smoke start/stop/start + revocación
  MediaProjection en dispositivo.

### P0.3 Modelo debug: verificar antes de activar

- **Problema:** la descarga runtime renombra y activa sin comparar SHA-256.
- **Objetivo:** hash correcto antes de `active_model`, limpieza de temporales y
  rechazo de fichero truncado/alterado.
- **Pruebas:** hash correcto, mismatch, descarga truncada, rename fallido,
  reintento y falta de espacio.
- **Criterio de cierre:** debug y release exponen la misma garantía observable.
- **Estado: implementado 2026-08-03.** `FileSha256` (testeado con vectores
  conocidos) + verificación previa al rename en `startDebugModelDownload`;
  `active_model` atómico vía `MarkerFileHelper`. Pendiente: smoke de mismatch /
  truncado en dispositivo.

### P0.4 Toolchain: una sola verdad reproducible

- **Problema actual:** Gradle usa compile/target 34 y NDK `28.0.12916984`, pero
  docs/workflows mencionan 35 y `28.0.13004108`; hay rutas `linux-x86_64` fijas.
- **Objetivo:** versión elegida sincronizada en Gradle, CI, README y AGENTS;
  detección de host o límite documentado.
- **Pruebas:** build en runner x86_64 y host ARM64 soportado, o declaración
  explícita de que sólo uno es oficial.
- **Criterio de cierre:** cero referencias contradictorias y APK reproducible.
- **Estado: implementado 2026-08-03.** `ndkVersion = "28.0.13004108"`
  (Gradle = CI = README/AGENTS); SDK efectivo 34/34/26 con Android 15/SDK 35
  pendiente de decisión; `ndkPrebuiltDir()` resuelve sysroot/libc++ por hosty declara el límite con GradleException claro. `assembleDebug` confirmado en CI
x86_64 el 2026-08-03 (runs `30859369221`/`30859370506` del fix `aa08a08`).
Pendiente: verificación de host ARM64 o su límite documentado en README.

## 4. Deuda P1 — robustez de superficies

1. ✅ **Transcripción de archivos** (2026-08-03): operation-id en
   `transcribeAudio`/callbacks; callbacks obsoletos descartados por opId +
   `isFinishing/isDestroyed`.
2. ✅ **Markers** (2026-08-03): toda escritura pasa por `MarkerFileHelper` con
   temp único por escritura + fsync + rename; lecturas concurrentes testeadas
   (MarkerAtomicityTest).
3. ✅ **Postprocesado** (2026-08-03, P1.3): `PostProcessorTest` (10 tests JVM
   con MockWebServer) cubre URL/payload `/chat/completions` con `stream:false`,
   `${output}` inyectado una sola vez, transcript como user message sin marcador,
   respuesta vacía/JSON inválida/HTTP error → error + fallback, toggle-off en
   vuelo → texto crudo y exactamente una entrega final. El **timeout real de
   OkHttp** se cubre con client de valores escalados vía seam
   (`setSharedClientForTests`: read timeout 100 ms contra body retrasado →
   onError) y los valores de producción (30 s/60 s/60 s) se asertan con
   `buildProductionClient()`. Queda fuera del harness el transcurso wall-clock
   de esos timeouts y la red real → smoke en dispositivo.
4. **Subtítulos/MediaProjection (P1.4):** probar Android 10–15, stop desde
   notificación, revocación, `AudioRecord` error, overlay permission y restart
   (dispositivo). Viabilidad 2026-08-04: 0 pasos ejecutables sin hardware —
   mitigación = auditoría estática dirigida y/o andamiaje androidTest
   (ver `memory/p14-p15-feasibility-2026-08-04.md`).
5. **Postprocesado en dispositivo (P1.5):** smoke con proveedor
   OpenAI-compatible real: timeouts de producción (30 s connect / 60 s
   read/write), DNS, TLS, latencia, toggle-off en vuelo (broadcast
   `CANCEL_PP` al `:ime`), superficies concurrentes y fallback a texto crudo —
   checklist completo en la auditoría (§P1.5). Viabilidad 2026-08-04: 9 de 12
   escenarios ya cubiertos en JVM (**DNS fail y connect timeout añadidos el
   2026-08-04**, cerrando el harness); 5 quedan
   solo-dispositivo.

## 5. Deuda P2 — cobertura del Guantelete

1. Resolver/pinear `transcribe-cpp-sys v0.1.3` o aislar oficialmente un crate de
   lógica pura; ejecutar `cargo test` real en workflow x86_64.
2. Ejecutar `rustfmt --check` sobre todo Rust tocado y documentar la salida.
3. Añadir smoke/instrumentation matrix para popup, RecognitionService, IME,
   subtítulos, archivo y custom words.
4. Cubrir modelo streaming y no streaming, PP off/on/failure, cambio de idioma,
   cambio de modelo, cancelación rápida y proceso `:ime`.
5. ✅ Migrar cadenas hardcodeadas visibles a recursos o registrar excepciones
   (2026-08-03, P2.4): 44 strings nuevas en 7 locales (gate PASS, 174
   translatables); excepción documentada para los detalles de error de
   `PostProcessor` (sin Context, seam JVM) y las strings de protocolo JNI.

## 6. Secuencia futura recomendada

1. ✅ P0.4 resuelto (2026-08-03): NDK unificado + host detection.
2. ✅ P0.3 implementado con tests JVM (FileSha256).
3. ✅ P0.1 y P0.2 implementados con tokens de operación/generación.
4. ✅ P1.3 resuelto (2026-08-03): suite HTTP/JVM completa (payload,
   `stream:false`, `${output}`, JSON inválido, HTTP error, fallback, una sola
   entrega y timeout real de OkHttp por seam con valores escalados).
   Pendiente: smoke de red real en dispositivo.
5. ✅ Ejecutar gates separados en CI: translations → JVM → assemble → lint
   (2026-08-03, runs `30859369221`/`30859370506` del fix `aa08a08`). Pendiente:
   hash (`checkModels`, workflow release).
6. Ejecutar Rust real/rustfmt cuando el entorno lo permita (`cargo test`
   bloqueado por `transcribe-cpp-sys` v0.1.3).
7. Ejecutar smoke test en dispositivo y conservar modelo, API, ABI, Android y
   logs (especialmente subtítulos start/stop/start, revocación MediaProjection,
   PP concurrente, descarga debug truncada y cancelación rápida de transcripción).
8. Revisar docs y sólo entonces cambiar este estado a **CERRADO**; la publicación
   de 0.1.25 requiere además crear el tag `v0.1.25` para activar el workflow firmado.

## 7. Comandos/gates de cierre previstos

No ejecutar automáticamente en esta sesión. En CI o host autorizado:

```bash
python3 scripts/check_translations.py
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew checkModels
cargo test
cargo fmt --all -- --check
```

Las invocaciones Gradle deben respetar la separación documentada en el workflow:
no combinar unit tests/lint con `assembleDebug` si el guard de `cargoNdkBuild`
puede omitir la compilación nativa.

## 8. Regla de cierre

No escribir “perfecto”, “100 % verificado”, “sin fallos” ni “Guantelete
operativo” basándose sólo en lectura estática. Cada deuda debe tener:

- cambio implementado;
- test automatizado o justificación explícita;
- gate ejecutado;
- smoke test cuando afecte Android/runtime;
- evidencia fechada y reproducible.
