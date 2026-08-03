# Auditoría estática y registro de deuda — 2026-08-03

> **Estado (2026-08-03, implementación):** los bloqueadores P0.1–P0.4 y las
> líneas P1.1–P1.3 quedaron **implementados** con tests JVM verdes
> (`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 32 tests). Detalle en
> [`gauntlet-p0-implemented-2026-08-03.md`](./gauntlet-p0-implemented-2026-08-03.md).
> Este documento conserva la auditoría original intacta; la validación de CI
> (`assembleDebug`/`lintDebug`/`checkModels`) y de dispositivo sigue pendiente,
> por lo que el Guantelete permanece **ABIERTO**.

**Tema:** auditoría integral de arquitectura, streaming ASR, postprocesado AI,
JNI, lifecycle, recursos, modelos, CI y Guantelete.

**Alcance de esta sesión:** sólo lectura y documentación. No se modificó código
funcional y no se ejecutaron Gradle, Cargo, tests, lint ni builds locales.

## Veredicto

La arquitectura **streaming ASR + postprocesado AI final-only** es coherente en
su intención y evita el texto duplicado o "Frankenstein": los parciales del ASR
son visuales, el transcript final se manda una vez al LLM y el editor recibe un
único resultado completo, con fallback al texto ASR.

No obstante, no es válido declarar el repositorio "perfecto", "100 % verificado"
o "libre de fallos". La auditoría encontró deuda técnica concreta que debe
resolverse y validarse antes de cerrar el Guantelete.

## Evidencia positiva obtenida en lectura estática

- Los 24 métodos `native` Java encontrados tienen un export JNI Rust
  correspondiente.
- Las firmas principales de callbacks JNI tienen correspondencia: estados,
  texto final, parciales, nivel de audio, auto-stop, subtítulos,
  `RecognitionService` y benchmark.
- Los XML parsean estáticamente y el recurso `@drawable/icon` del manifest
  existe como `drawable/icon.png`.
- Los seis locales alternativos tienen paridad estática de nombres de strings
  respecto a `values/strings.xml`.
- El flujo final-only usa `stream: false`, cierra las respuestas OkHttp y
  conserva fallback al texto crudo ante error, cancelación o respuesta inválida.
- El IME y el popup usan generaciones (`sessionId`) para descartar muchos
  callbacks tardíos.
- Las políticas de lag de subtítulos y la relectura de `model_language` en cada
  run están documentadas como invariantes de diseño.

Esta evidencia **no equivale** a compilación, ejecución ni prueba en dispositivo.

## Deuda confirmada y orden de prioridad

### P0 — bloquear la declaración de cierre del Guantelete

#### P0.1 Cancelación de postprocesado por sesión, no global

**Evidencia:** `PostProcessor.cancelAll()` mantiene un conjunto global de
`Call` y se invoca desde IME, popup, `VoiceRecognitionService`, transcripción
de archivos, settings y lifecycle.

**Riesgo:** destruir una superficie o cambiar settings puede cancelar una
petición legítima de otra superficie que esté procesando simultáneamente.

**Futuro arreglo:** introducir propietario/operation-id por llamada; cancelar
sólo las llamadas de la superficie y sesión correspondientes. Mantener una
cancelación global únicamente para un shutdown real del proceso.

**Aceptación:** dos superficies con peticiones simultáneas; cancelar una no
afecta a la otra; desactivar PP sigue entregando texto crudo sólo en la sesión
correspondiente.

#### P0.2 Worker de subtítulos con generación y parada determinista

**Evidencia:** `cleanupNative()` sustituye `LIVE_STATE`, pero el worker conserva
su `Receiver` y `GlobalRef` Java. No hay token de generación que invalide un
worker antiguo al iniciar una sesión nueva.

**Riesgo:** trabajos pendientes del worker anterior pueden emitir callbacks
sobre un servicio/vista de una sesión anterior o interferir con una sesión
nueva.

**Futuro arreglo:** añadir generación/cancellation token, cerrar el canal,
impedir nuevas entregas y esperar la terminación del worker antes de publicar
el siguiente estado.

**Aceptación:** iniciar/detener/iniciar subtítulos repetidamente no produce
texto de una sesión anterior, crash de overlay ni callback posterior a cleanup.

#### P0.3 SHA-256 también en la descarga runtime debug

**Evidencia:** Gradle y `checkModels` verifican assets de build; la descarga
runtime de `MainActivity` renombra el GGUF y escribe `active_model` sin calcular
el hash declarado.

**Riesgo:** un fichero truncado o alterado queda marcado como modelo activo.

**Futuro arreglo:** hash incremental mientras se descarga o segundo recorrido
antes de activar; borrar `.tmp` y destino inválido; escribir `active_model`
atomically sólo después de verificar el hash.

**Aceptación:** fichero correcto activa; fichero truncado, alterado o con hash
incorrecto nunca activa y permite reintentar; la ruta de release y debug tienen
la misma garantía observable.

#### P0.4 Unificar toolchain real y documentación

**Evidencia:** `app/build.gradle.kts` fija `compileSdk/targetSdk 34` y NDK
`28.0.12916984`, mientras `AGENTS.md`, README y workflows mencionan SDK 35 y
NDK `28.0.13004108`. Además, el wiring Gradle contiene rutas hardcodeadas
`linux-x86_64` aunque se afirma validación en hosts ARM64 y x86_64.

**Futuro arreglo:** escoger una combinación soportada, hacer que Gradle/CI/docs
compartan una única fuente de verdad y resolver sysroot/libc++ según la
arquitectura del host (o declarar explícitamente el límite).

**Aceptación:** CI instala exactamente la versión que Gradle usa; el build
reproduce el entorno documentado en x86_64 y el soporte ARM64 queda demostrado
o explícitamente descartado; no quedan referencias contradictorias.

### P1 — robustez de superficies

#### P1.1 Generación también para transcripción de archivos

`TranscribeFileActivity` conserva callbacks nativos sin `sessionId`/operation-id.
Debe impedir que un worker nativo finalice sobre una Activity destruida,
recreada o sustituida.

**Aceptación:** rotación/cierre/reapertura durante decode o ASR no actualiza la
instancia equivocada ni deja callbacks tardíos sin dueño.

#### P1.2 Revisar atomicidad de todos los marker files cross-process

`MarkerFileHelper` y `SettingsManager` tienen rutas atómicas, pero
`App`/`ModelsActivity` escriben directamente varios marcadores (`model_language`,
`device_language`, `active_model`, `model_translate`, `model_threads`,
`stream_context_right`).

**Futuro arreglo:** centralizar toda escritura en un helper atómico con temp,
`fsync` cuando proceda, rename y limpieza; revisar también deletes y carreras
entre main y `:ime`.

**Aceptación:** pruebas de lectura concurrente nunca observan contenido vacío o
parcial; el marker activo sólo apunta a un modelo completo.

#### P1.3 Probar postprocesado bajo latencia, cancelación y concurrencia

El payload final-only es correcto por lectura, pero faltaban pruebas de
proveedor, timeout, cancelación, toggle durante request, respuesta JSON
incompleta, modelo inválido y dos superficies concurrentes.

**Estado (2026-08-03): implementado.** `PostProcessorTest` (8 tests JVM con
MockWebServer) vía seam `PostProcessor.PostProcessorSettings` (SettingsManager
lo implementa en producción): payload `/chat/completions` con `stream:false`,
`${output}` inyectado una sola vez, transcript como user message sin marcador,
respuesta vacía/JSON inválida/HTTP 500 → error con fallback al texto crudo,
toggle-off durante vuelo → fallback, exactamente una entrega final, y el
timeout real de OkHttp por seam (`setSharedClientForTests` con read timeout de
100 ms contra un body retrasado → onError; los valores de producción
30 s/60 s/60 s se asertan con `buildProductionClient()`). La cancelación por
sesión queda cubierta por `CallRegistryTest`.

**Aceptación (pendiente):** transcurso wall-clock de los timeouts de producción
y comportamiento de red real (DNS, TLS, latencia del proveedor) — sólo
verificables en smoke de dispositivo; no se esperan 60 s en el harness JVM.

#### P1.4 Revisar lifecycle del worker de subtítulos y MediaProjection en hardware

Validar stop/restart, revocación desde la notificación, overlay eliminado,
`AudioRecord` liberado y ausencia de callbacks después de `cleanupNative` en
Android 10–15 y al menos una ROM OEM.

#### P1.5 Smoke del postprocesado con proveedor real en dispositivo

El harness JVM (P1.3) cubre payload, errores, fallback y timeout por seam con
valores escalados; el transcurso wall-clock (30 s connect, 60 s read/write) y
el comportamiento de red real (DNS, TLS, latencia, cortes) sólo son
verificables en dispositivo.

**Entorno:** dispositivo arm64 físico (API 26–35; idealmente uno stock + una
ROM OEM), modelo ASR streaming (Nemotron) y no streaming (Whisper/Parakeet),
proveedor OpenAI-compatible con clave de test (o endpoint local en la misma
WiFi). Redes: WiFi normal, DNS lento y corte de red (modo avión + WiFi).

**Checklist por superficie (IME, popup, RecognitionService, transcripción de
archivo):**

- [ ] Éxito: dictado corto y largo → el editor recibe el texto refinado **una
      sola vez** (sin parciales del LLM, sin duplicados ni texto
      "Frankenstein").
- [ ] Fallback a texto crudo ante DNS fail (URL inalcanzable) → inmediato.
- [ ] Fallback ante HTTP 4xx/5xx del proveedor → inmediato, sin texto parcial.
- [ ] Fallback ante respuesta JSON inválida o vacía.
- [ ] Fallback ante **timeout real de read**: proveedor que cuelga > 60 s →
      texto crudo; registrar latencia start→fallback.
- [ ] Connect timeout observable: IP no enrutable con DNS que responde →
      fallback ~30 s.
- [ ] Toggle-off durante vuelo: desactivar PP en ajustes mientras la petición
      vuela → broadcast `CANCEL_PP` al `:ime`, gana el texto crudo, cancelación
      inmediata (sin esperar al timeout) y el IME nunca queda en
      "Refining..."/"Processing...".
- [ ] Superficies concurrentes: dictado en IME + fetch de modelos en ajustes a
      la vez; destruir una superficie no cancela la otra (P0.1).
- [ ] Cancelación por superficie: cerrar popup / cancelar reconocimiento /
      destruir Activity en vuelo → sólo se cancela esa llamada.
- [ ] 10+ dictados consecutivos con PP: sin fugas visibles (logcat GC), sin
      respuestas OkHttp sin cerrar, sin toasts duplicados.
- [ ] Rotación/cierre de pantalla durante el vuelo → cero callbacks tardíos
      (P1.1/P0.1).
- [ ] Latencia end-to-end registrada: tap-stop → texto final (ASR + 1
      round-trip del LLM).

**Evidencia a conservar:** versión de app (`versionCode`/`versionName`), ABI,
API level, ROM; logcat con los TAG `PostProcessor` y `OfflineVoiceInput`;
capturas antes/después del editor; tiempos medidos por escenario; proveedor y
modelo LLM usados.

**Aceptación:** las 4 superficies entregan texto (refinado o crudo)
exactamente una vez en todos los escenarios; fallback a crudo inmediato en
DNS/HTTP y ≤ 60 s + margen en timeouts; cero callbacks tardíos; IME nunca
bloqueado tras fallo o toggle-off.

**Viabilidad (2026-08-04):** clasificación sin dispositivo físico — 7 de 12
escenarios ya cubiertos en JVM; 2 ampliables en el harness (DNS fail con host
`.invalid`, connect timeout por seam contra `192.0.2.1` — pendientes); 5
solo-dispositivo (wall-clock 30 s/60 s, TLS real, broadcast `:ime` + IME no
bloqueado, superficies concurrentes, fugas, latencia end-to-end). Detalle en
`memory/p14-p15-feasibility-2026-08-04.md`.

### P2 — cobertura y calidad del Guantelete

#### P2.1 Rust real, no sólo crate espejo

El crate completo está bloqueado por el problema conocido de empaquetado de
`transcribe-cpp-sys v0.1.3`. Los tests de `audio` y `corrector` verificados en un
crate espejo prueban lógica pura, no integración JNI/engine/transcribe.cpp.

**Futuro arreglo:** corregir/pinear la dependencia o aislar un crate de lógica
pura oficial; añadir workflow Rust que ejecute `cargo test` real cuando sea
posible y documente el bloqueo si no.

#### P2.2 `rustfmt` y lint de todo el alcance

La evidencia histórica cubre archivos concretos y `lintDebug` en debug, no
necesariamente todo Rust ni el conjunto release. Ejecutar gates explícitos y
conservar logs fechados.

#### P2.3 Smoke tests instrumentados de las seis superficies

Crear o recuperar una matriz reproducible para popup, RecognitionService, IME,
subtítulos, archivo y custom words. Debe incluir modelos streaming y no
streaming, PP apagado/encendido/fallido y cambios de idioma cross-process.

#### P2.4 Reducir strings hardcodeadas

La paridad XML pasa, pero quedaban estados, errores y toasts visibles escritos
directamente en Java.

**Estado (2026-08-03): implementado.** 44 strings nuevas en los 7 locales
(174 translatables en total, gate PASS): IME (estados, permisos, error de
carga, contentDescriptions), popup, transcripción de archivos, subtítulos
(toasts, overlay, notificación), `action_close` y errores de la descarga debug
(usando formatos `%1$s`/`%1$d`). Se reutilizan `status_ready` y `section_subs`.

**Excepción documentada:** (a) las strings de protocolo JNI ("Ready",
"Listening...", "Transcribing", "Error") se conservan como literales porque
la máquina de estados Java las compara con `.equals()`/`.contains()`/
`.startsWith()`; (b) los detalles de error de `PostProcessor` ("API Error N",
"Parse error: ...", "Request failed", "Empty response ...") se mantienen en
inglés a propósito: `PostProcessor` no tiene `Context` (el seam JVM de P1.3 lo
requiere) y las superficies muestran el fallback localizado al texto crudo;
(c) los nombres de modelo en `ModelsActivity` son nombres propios técnicos y
no se traducen.

## Checklist futuro de cierre

Actualizado tras la implementación del 2026-08-03:

- [x] Deuda P0 resuelta y revisada (2026-08-03).
- [x] Tests de concurrencia/cancelación por sesión verdes (CallRegistryTest,
      MarkerAtomicityTest — JVM).
- [x] Runtime debug verifica SHA-256 antes de activar el modelo (FileSha256 +
      MainActivity; tests JVM).
- [x] Toolchain Gradle/CI/docs unificada (NDK 28.0.13004108); límite de host
      ARM64 declarado vía `ndkPrebuiltDir()`.
- [ ] `cargo test` real o bloqueo reproducible documentado en CI
      (`transcribe-cpp-sys` v0.1.3 — pendiente).
- [ ] `rustfmt` y lint del alcance completo verdes (rustfmt de los ficheros
      tocados ✅; `lintDebug` pendiente de CI).
- [ ] Smoke test de las seis superficies en dispositivo/emulador.
- [x] Postprocesado verificado con servidor HTTP controlado: payload,
      `stream:false`, JSON inválido, HTTP error, toggle-off, fallback y
      timeout real de OkHttp por seam (P1.3 — `PostProcessorTest`, 8 tests;
      los 30 s/60 s de producción se asertan; el wall-clock y la red real
      quedan para smoke en dispositivo).
- [ ] Smoke del postprocesado con proveedor real en dispositivo (P1.5):
      timeouts de producción, DNS/TLS, latencia, toggle-off en vuelo y
      superficies concurrentes; checklist §P1.5.
- [x] Markers verificados frente a lecturas concurrentes main/`:ime`
      (MarkerAtomicityTest; temp único por escritura).
- [x] Strings visibles migradas o excepción documentada (P2.4, 2026-08-03:
      44 strings en 7 locales; excepción: PostProcessor sin Context + strings
      de protocolo JNI + nombres de modelo).
- [ ] Sólo entonces actualizar el estado a “Guantelete cerrado”.

## Regla de lenguaje para documentos futuros

Usar siempre estas etiquetas:

- **Diseño:** está especificado, no probado.
- **Auditoría estática:** leído/comparado sin ejecutar.
- **Validado localmente:** comando concreto ejecutado y resultado conservado.
- **Validado en CI:** workflow concreto y run identificable.
- **Validado en dispositivo:** modelo, API, hardware y pasos identificados.

No usar “perfecto”, “100 % verificado”, “operativo” o “sin fallos” si sólo existe
lectura estática o evidencia parcial.
