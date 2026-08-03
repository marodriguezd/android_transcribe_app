# agent_prompt.md — Instrucciones para el siguiente agente

> Lee primero `AGENTS.md`, `GAUNTLETE_PLAN.md`, `.agents/progress.md` y la
> auditoría `.agents/memory/static-audit-debt-2026-08-03.md`.
>
> El repositorio está en **Guantelete ABIERTO**. No confundas diseño o auditoría
> estática con validación ejecutada. Esta guía prioriza la implementación futura.

## Regla de alcance

La auditoría pidió documentar deuda, no arreglarla automáticamente. El siguiente
agente puede implementar los puntos de abajo sólo después de leer el código
completo y actualizar referencias JNI/tests/documentación afectadas.

## Prioridad P0 — bloqueadores

### 1. Aislar postprocesado por sesión

`PostProcessor.cancelAll()` es global. Introduce operation-id/owner por `Call` y
cancela únicamente la operación afectada. Mantén el fallback al texto ASR,
`stream:false`, cierre de `Response`, validadores y broadcast main → `:ime`.

Tests mínimos:

- dos llamadas simultáneas de superficies distintas;
- cancelación de una sin afectar a la otra;
- toggle off durante request;
- HTTP error, timeout, JSON inválido y contenido vacío;
- exactamente una entrega final por sesión.

### 2. Generación y cleanup del worker de subtítulos

`cleanupNative()` no debe dejar workers antiguos capaces de llamar a Java.
Añade generación/token de sesión, cierre de canal y terminación determinista.
Preserva la semántica `isFinal=true` append / `false` replace, merging y lag
policies calibradas.

Tests/smoke mínimos:

- start/stop/start rápido;
- revocación MediaProjection;
- destrucción del servicio durante un job;
- ningún callback posterior a cleanup o sobre overlay eliminado.

### 3. Hash runtime del modelo debug

La descarga runtime debe verificar el mismo SHA-256 declarado antes de activar
`active_model`. Usa temporal, hash, rename y activación atómica. Prueba mismatch,
truncado, rename fallido, reintento y falta de espacio.

### 4. Unificar toolchain

La fuente efectiva actual es `app/build.gradle.kts`; antes de editar, decidir la
combinación soportada y sincronizar Gradle, workflows, README, AGENTS y docs. Las
rutas `linux-x86_64` no pueden presentarse como compatibilidad ARM64 sin resolver
la arquitectura o declarar el límite.

## Prioridad P1

- operation-id para `TranscribeFileActivity`/`transcribe_file.rs`;
- escritura atómica de todos los marker files consumidos por main y `:ime`;
- tests HTTP/JVM del payload y fallback final-only;
- lifecycle de subtítulos/MediaProjection Android 10–15 y OEM.

## Prioridad P2

- habilitar `cargo test` real o documentar bloqueo reproducible de
  `transcribe-cpp-sys` en CI;
- `cargo fmt --all -- --check` del Rust tocado;
- smoke/instrumentation matrix de las seis superficies;
- migrar strings hardcodeadas visibles a recursos;
- conservar evidencia fechada de cada gate.

## Prohibiciones

- No cambiar firmas JNI sin búsqueda global y actualización de Java/Rust.
- No eliminar `catch_unwind` ni recuperación de Mutex poison.
- No cachear `model_language` dentro de `Engine`.
- No subir umbrales de subtítulos sin hardware lento.
- No activar un modelo antes de verificar su hash.
- No declarar “BUILD SUCCESSFUL” sin salida real del comando.
- No declarar “Guantelete cerrado” mientras exista cualquier P0 abierto.

## Validación final prevista

Las invocaciones deben ejecutarse en CI/host autorizado, separadas según el
workflow:

```bash
python3 scripts/check_translations.py
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew checkModels
cargo test
cargo fmt --all -- --check
```

Después: smoke test de popup, RecognitionService, IME, subtítulos, archivo y
custom words con modelo streaming y no streaming, PP desactivado/activado/
fallido, cancelación rápida, cambio de idioma y proceso `:ime`.
