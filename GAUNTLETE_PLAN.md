# GAUNTLETE_PLAN.md — Plan de cierre QA de `android_transcribe_app`

> Este documento es el plan operativo del Guantelete. No declara que el
> repositorio esté cerrado: separa evidencia estática, gates ejecutables y
> validación que aún requiere código, CI o dispositivo.
>
> Auditoría de referencia: [`.agents/memory/static-audit-debt-2026-08-03.md`](.agents/memory/static-audit-debt-2026-08-03.md).

## 1. Estado actual

**Estado: ABIERTO — deuda P0 pendiente.**

La auditoría del 2026-08-03 fue sólo lectura. No se deben atribuir a esta sesión
logs `BUILD SUCCESSFUL`, tests o smoke tests. El diseño final-only es coherente,
pero no basta para cerrar concurrencia, workers, descarga runtime ni toolchain.

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
| `testDebugUnitTest` | 14 tests JVM de markers/subtitle prefs según historial | No cubre JNI, OkHttp real, engine ni lifecycle Android |
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

### P0.2 Subtítulos: worker generacional y cleanup determinista

- **Problema:** el worker conserva receiver/GlobalRef tras `cleanupNative`; no
  existe invalidación generacional explícita.
- **Objetivo:** token de sesión, cierre de canal, no entrega tras invalidación y
  espera/confirmación de terminación.
- **Pruebas:** start → stop → start repetido, revocación MediaProjection,
  destrucción de servicio y overlay recreado.
- **Criterio de cierre:** cero callbacks de una generación anterior y cero
  actualizaciones sobre overlay eliminado.

### P0.3 Modelo debug: verificar antes de activar

- **Problema:** la descarga runtime renombra y activa sin comparar SHA-256.
- **Objetivo:** hash correcto antes de `active_model`, limpieza de temporales y
  rechazo de fichero truncado/alterado.
- **Pruebas:** hash correcto, mismatch, descarga truncada, rename fallido,
  reintento y falta de espacio.
- **Criterio de cierre:** debug y release exponen la misma garantía observable.

### P0.4 Toolchain: una sola verdad reproducible

- **Problema actual:** Gradle usa compile/target 34 y NDK `28.0.12916984`, pero
  docs/workflows mencionan 35 y `28.0.13004108`; hay rutas `linux-x86_64` fijas.
- **Objetivo:** versión elegida sincronizada en Gradle, CI, README y AGENTS;
  detección de host o límite documentado.
- **Pruebas:** build en runner x86_64 y host ARM64 soportado, o declaración
  explícita de que sólo uno es oficial.
- **Criterio de cierre:** cero referencias contradictorias y APK reproducible.

## 4. Deuda P1 — robustez de superficies

1. **Transcripción de archivos:** añadir operation-id para que workers nativos no
   actualicen Activity destruida/recreada.
2. **Markers:** centralizar toda escritura de `model_language`, `device_language`,
   `active_model`, `model_translate`, `model_threads` y `stream_context_right`
   en escritura temporal + rename; probar lecturas main/`:ime` concurrentes.
3. **Postprocesado:** probar URL/payload, `stream:false`, `${output}`, respuesta
   inválida, HTTP error, timeout, cancelación, toggle y proveedor concurrente.
4. **Subtítulos/MediaProjection:** probar Android 10–15, stop desde notificación,
   revocación, `AudioRecord` error, overlay permission y restart.

## 5. Deuda P2 — cobertura del Guantelete

1. Resolver/pinear `transcribe-cpp-sys v0.1.3` o aislar oficialmente un crate de
   lógica pura; ejecutar `cargo test` real en workflow x86_64.
2. Ejecutar `rustfmt --check` sobre todo Rust tocado y documentar la salida.
3. Añadir smoke/instrumentation matrix para popup, RecognitionService, IME,
   subtítulos, archivo y custom words.
4. Cubrir modelo streaming y no streaming, PP off/on/failure, cambio de idioma,
   cambio de modelo, cancelación rápida y proceso `:ime`.
5. Migrar cadenas hardcodeadas visibles a recursos o registrar excepciones.

## 6. Secuencia futura recomendada

1. Resolver P0.4 primero, para que los siguientes resultados sean reproducibles.
2. Implementar P0.3 y añadir tests de integridad/activación del modelo.
3. Implementar P0.1 y P0.2 con tokens de operación/generación.
4. Añadir tests HTTP/JVM y lifecycle de las seis superficies.
5. Ejecutar gates separados: translations → JVM → assemble → lint → hash.
6. Ejecutar Rust real/rustfmt cuando el entorno lo permita.
7. Ejecutar smoke test en dispositivo y conservar modelo, API, ABI, Android y
   logs.
8. Revisar docs y sólo entonces cambiar este estado a **CERRADO**.

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
