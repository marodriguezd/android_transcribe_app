# Progreso — estado actual del trabajo IA-asistido

**Última actualización:** 2026-08-03

## 🟢 Recién completado

- **2026-08-03 — Plan del Guantelete ejecutado (P0 + P1.1/P1.2):**
  - P0.1 cancelación del postprocesado por propietario (`CallRegistry`,
    `cancelAllFor(owner)` en las 5 superficies) con `CallRegistryTest`;
  - P0.2 generación de sesión para el worker de subtítulos (Rust + Java);
  - P0.3 SHA-256 en la descarga runtime debug antes de activar el modelo;
  - P0.4 toolchain unificada (NDK 28.0.13004108, `ndkPrebuiltDir()`);
  - P1.1 operation-id en transcripción de archivos;
  - P1.2 markers atómicos (temp único por escritura) + `MarkerAtomicityTest`;
  - tests JVM nuevos: `CallRegistryTest` (4), `MarkerAtomicityTest` (1),
    `FileSha256Test` (3) y `PostProcessorTest` (8, P1.3: payload,
    `stream:false`, `${output}` una vez, errores, fallback, una sola entrega y
    timeout real de OkHttp por seam con valores escalados).
    Validación local: `./gradlew testDebugUnitTest` BUILD SUCCESSFUL (32 tests,
    0 fallos), translations PASS, `rustfmt --check` de los ficheros Rust
    tocados, `git diff --check`. Detalle en
    [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md).
- **2026-08-03 — Postprocesado AI final-only:** los parciales del ASR siguen
  siendo previsualización visual; el transcript final se manda una sola vez a
  `PostProcessor.process()` con respuesta completa y fallback al texto crudo.
- **2026-08-03 — Auditoría estática integral:** revisados arquitectura
  Java/Rust, JNI, callbacks, lifecycle, concurrencia, streaming ASR,
  postprocesado, subtítulos, modelos, markers, recursos, manifest, i18n y CI.
  Resultado detallado en
  [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md).
- **2026-07-29 — Diccionario del sistema Android:** sincronización hacia
  `filesDir/custom_words`, con editor y fallbacks de Settings.
- **2026-07-29 — Auto-stop IME/VAD:** marcador `auto_stop`, callback de
  auto-stop y umbrales adaptativos.
- **2026-07-28 — Corrector fonético:** post-filtro ES+EN en Rust con
  safe-fallback, cubriendo todas las superficies.

La historia detallada de las sesiones anteriores se conserva en las memorias
indexadas: `phonetic-corrector-2026-07-28.md`, `optimizations-2026-07-29.md`,
`polish-agents-2026-07-29.md`, `postprocess-final-only-2026-08-03.md` y
`gauntlet-p0-implemented-2026-08-03.md`.

## 🟢 Bloqueadores P0 — implementados, pendientes de validación

1. ✅ Postprocesado aislado por propietario (`cancelAllFor(owner)`);
   `cancelAll()` global sólo para shutdown real / toggle PP-off.
2. ✅ Worker de subtítulos con generación: jobs viejos ni se transcriben ni se
   entregan; termina al drenar el canal.
3. ✅ SHA-256 verificado en la descarga runtime debug antes de `active_model`.
4. ✅ NDK/SDK unificados (34/34/26, NDK 28.0.13004108); rutas de host resueltas
   con `ndkPrebuiltDir()` y límite declarado.

Implementados el 2026-08-03 y gateados por JVM; la validación de CI
(`assembleDebug`/`lintDebug`/`checkModels`) y de dispositivo sigue pendiente.

## 🟡 Deuda P1/P2

- ✅ Operation-id en transcripción de archivos (P1.1).
- ✅ Escrituras atómicas de markers cross-process (P1.2, temp único).
- ✅ PP verificado con HTTP controlado: payload, `stream:false`, `${output}`,
  JSON inválido, HTTP error, toggle-off durante vuelo, fallback y timeout real
  de OkHttp por seam (`PostProcessorTest`, 8 tests; los valores de producción
  30 s/60 s/60 s se asertan con `buildProductionClient()`). El transcurso
  wall-clock y la red real (DNS/TLS/latencia) quedan para smoke en dispositivo.
- Probar lifecycle de subtítulos/MediaProjection en Android 10–15 y ROM OEM
  (P1.4, dispositivo).
- Smoke del postprocesado con proveedor real en dispositivo (P1.5): timeouts
  de producción (30 s/60 s), DNS/TLS, latencia, toggle-off en vuelo y
  superficies concurrentes (checklist en la auditoría §P1.5).
- Habilitar `cargo test` real o documentar bloqueo reproducible en CI.
- Ejecutar `lintDebug`/rustfmt de todo el alcance en CI.
- Añadir smoke/instrumentation matrix para las seis superficies.
- ✅ Strings visibles hardcodeadas migradas a recursos en los 7 locales
  (P2.4, 2026-08-03): 44 strings nuevas (IME, popup, archivos, subtítulos,
  descarga debug) con gate de traducciones PASS (174 translatables).
  Excepción documentada: detalles de error de PostProcessor (sin Context,
  seam JVM) y strings de protocolo JNI (comparadores de la máquina de
  estados).

## 🟡 Validación pendiente

- `./gradlew assembleDebug` y `assembleRelease` en el pipeline oficial
  (el NDK local no está instalado en este entorno; local.properties apunta a
  una versión antigua y está gitignored).
- `checkModels`, `lintDebug` y translations en CI actual (`testDebugUnitTest`
  ya pasó localmente: 32 tests).
- Tests Rust del crate real cuando se resuelva `transcribe-cpp-sys`.
- Smoke test en dispositivo arm64 con modelo streaming y no streaming
  (incluidos subtítulos start/stop/start y revocación MediaProjection).
- Pruebas de cancelación rápida, cambio de modelo/idioma y PP concurrente
  entre superficies.

## 🔴 Bloqueos de entorno conocidos

- El crate completo puede quedar bloqueado por el empaquetado de
  `transcribe-cpp-sys v0.1.3`; el crate espejo sólo cubre lógica pura.
- La auditoría no ejecutó comandos de build/test por la restricción de esta
  sesión. No registrar esta sesión como `BUILD SUCCESSFUL`.

## Regla de estado

`progress.md` no declara una tarea “cerrada” sólo porque exista diseño o una
lectura estática. Cada cierre debe indicar comando/workflow/dispositivo y
resultado. Ver la taxonomía en la memoria de auditoría y el plan del Guantelete.

## Enlaces canónicos

- Implementación P0/P1: [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md)
- Auditoría/deuda: [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md)
- Memoria histórica de optimizaciones: [`memory/optimizations-2026-07-29.md`](./memory/optimizations-2026-07-29.md)
- Memoria de limpieza agéntica: [`memory/polish-agents-2026-07-29.md`](./memory/polish-agents-2026-07-29.md)
- Memoria del corrector: [`memory/phonetic-corrector-2026-07-28.md`](./memory/phonetic-corrector-2026-07-28.md)
- Postprocesado final-only: [`memory/postprocess-final-only-2026-08-03.md`](./memory/postprocess-final-only-2026-08-03.md)
- Plan QA: [`../GAUNTLETE_PLAN.md`](../GAUNTLETE_PLAN.md)
- Arquitectura: [`architecture.md`](./architecture.md)
- Spec: [`spec.md`](./spec.md)
- Reglas IA: [`../AGENTS.md`](../AGENTS.md)
- Índice: [`INDEX.md`](./INDEX.md)
