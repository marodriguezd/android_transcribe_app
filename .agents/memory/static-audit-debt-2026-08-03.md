# Auditoría estática y registro de deuda — 2026-08-03

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

El payload final-only es correcto por lectura, pero faltan pruebas de proveedor,
timeout, cancelación, toggle durante request, respuesta JSON incompleta,
modelo inválido y dos superficies concurrentes.

**Aceptación:** suite JVM con servidor HTTP controlado o seam equivalente,
verificando payload, `stream:false`, una sola petición, cierre de response,
fallback y aislamiento por sesión.

#### P1.4 Revisar lifecycle del worker de subtítulos y MediaProjection en hardware

Validar stop/restart, revocación desde la notificación, overlay eliminado,
`AudioRecord` liberado y ausencia de callbacks después de `cleanupNative` en
Android 10–15 y al menos una ROM OEM.

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

La paridad XML pasa, pero quedan estados, errores y toasts visibles escritos
directamente en Java. Migrarlos a recursos y repetir el gate de traducciones.

## Checklist futuro de cierre

- [ ] Deuda P0 resuelta y revisada.
- [ ] Tests de concurrencia/cancelación por sesión verdes.
- [ ] Runtime debug verifica SHA-256 antes de activar el modelo.
- [ ] Toolchain Gradle/CI/docs unificada; host ARM64/x86_64 probado o límite
      declarado.
- [ ] `cargo test` real o bloqueo reproducible documentado en CI.
- [ ] `rustfmt` y lint del alcance completo verdes.
- [ ] Smoke test de las seis superficies en dispositivo/emulador.
- [ ] Postprocesado verificado con servidor HTTP controlado y fallback.
- [ ] Markers verificados frente a lecturas concurrentes main/`:ime`.
- [ ] Strings visibles migradas o excepción documentada.
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
