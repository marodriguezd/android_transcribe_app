# agent_prompt.md — Instrucciones para el siguiente agente

> Lee primero `AGENTS.md`, `GAUNTLETE_PLAN.md`, `.agents/progress.md`,
> `memory/gauntlet-p0-implemented-2026-08-03.md` y la auditoría
> `memory/static-audit-debt-2026-08-03.md`.
>
> El repositorio está en **Guantelete ABIERTO**: los bloqueadores P0 y las
> líneas P1.1–P1.3 están **implementados** y gateados por JVM (32 tests);
> `assembleDebug` y `lintDebug` quedaron **validados en CI** el 2026-08-03
> (runs `30859369221`/`30859370506` del fix `aa08a08`, APK v0.1.24 a Telegram).
> Pendientes: `checkModels`/release, Rust real y smoke en dispositivo. No
> confundas implementación con validación ejecutada en CI o hardware.

## Ya implementado (2026-08-03) — no rehacer

- P0.1: cancelación del postprocesado por propietario (`CallRegistry` +
  `cancelAllFor(owner)`; `cancelAll()` global sólo para shutdown real).
- P0.2: generación de sesión para el worker de subtítulos (`GENERATION` en
  `subtitle.rs`; reset de ventana y `mSubtitleText=null` en Java).
- P0.3: SHA-256 verificado antes de activar la descarga runtime debug
  (`FileSha256` + `MainActivity`).
- P0.4: toolchain unificada (NDK 28.0.13004108; `ndkPrebuiltDir()`).
- P1.1: operation-id en `transcribe_file.rs`/`TranscribeFileActivity`.
- P1.2: markers atómicos (temp único por escritura en `MarkerFileHelper`;
  `ModelsActivity`/`App`/`MainActivity` centralizados).
- P1.3: suite HTTP/JVM del postprocesado (`PostProcessorTest`, 8 tests con
  MockWebServer) vía seam `PostProcessorSettings` (SettingsManager lo
  implementa en producción) + timeout real de OkHttp por seam con client de
  valores escalados (`setSharedClientForTests`).
- Tests: `CallRegistryTest`, `MarkerAtomicityTest`, `FileSha256Test`,
  `PostProcessorTest` — 32 tests JVM verdes localmente (2026-08-03).

## Siguiente: validación y deuda restante

### P1.3 — resto pendiente

La suite HTTP/JVM está cerrada (payload, `stream:false`, `${output}`, JSON
inválido, HTTP error, toggle-off, fallback y timeout real de OkHttp por seam —
exactamente una entrega final por sesión). Los valores de producción
(30 s/60 s/60 s) se asertan en JVM; lo que queda fuera del harness es el
**transcurso wall-clock** de esos timeouts (esperar 60 s en CI no aporta) y el
comportamiento de red real (DNS, TLS, latencia del proveedor) → smoke de PP
con proveedor real en dispositivo (**P1.5** — checklist detallado en la
auditoría §P1.5).

### P2 / CI / dispositivo

- ✅ `assembleDebug` y `lintDebug` validados en CI (2026-08-03, runs
  `30859369221`/`30859370506` del fix `aa08a08`). Pendiente: `checkModels` en
  CI (workflow release; el guard de `cargoNdkBuild` sigue siendo obligatorio).
- `cargo test` real o bloqueo reproducible de `transcribe-cpp-sys` v0.1.3.
- Smoke test de las seis superficies; especial atención a subtítulos
  start/stop/start, revocación MediaProjection, descarga debug truncada y
  PP con proveedor real (P1.5: timeouts 30 s/60 s, DNS/TLS, latencia,
  toggle-off en vuelo y superficies concurrentes).
- ✅ P2.4 hecho (2026-08-03): strings visibles migradas a los 7 locales (44
  nuevas, gate PASS); excepción documentada: detalles de error de
  `PostProcessor` (sin Context) y strings de protocolo JNI.

## Reglas a respetar al tocar lo implementado

- Preservar `CallRegistry` (owner por identidad, `NO_OWNER` sentinel) y la
  separación global vs owner-scoped de `cancelAll`.
- Preservar la generación de subtítulos: no reintroducir entregas de workers
  viejos ni quitar los re-checks antes de transcribir/deliver.
- No activar un modelo sin verificar su hash; `active_model` siempre atómico.
- No cambiar firmas JNI sin búsqueda global (transcribeAudio ya lleva opId).
- No eliminar `catch_unwind` ni recuperación de Mutex poison.
- No cachear `model_language` dentro de `Engine`.
- No subir umbrales de subtítulos sin hardware lento.
- No declarar “BUILD SUCCESSFUL” sin salida real ni “Guantelete cerrado”
  mientras queden P0 sin validar en CI/dispositivo.

## Prioridad P2

- habilitar `cargo test` real o documentar bloqueo reproducible de
  `transcribe-cpp-sys` en CI;
- `cargo fmt --all -- --check` del Rust tocado;
- smoke/instrumentation matrix de las seis superficies;
- ✅ migrar strings hardcodeadas visibles a recursos (P2.4, 2026-08-03);
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
