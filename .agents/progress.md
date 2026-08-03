# Progreso — estado actual del trabajo IA-asistido

**Última actualización:** 2026-08-03

## 🟢 Recién completado

- **2026-08-03 — Postprocesado AI final-only:** los parciales del ASR siguen
  siendo previsualización visual; el transcript final se manda una sola vez a
  `PostProcessor.process()` con respuesta completa y fallback al texto crudo.
- **2026-08-03 — Auditoría estática integral:** revisados arquitectura
  Java/Rust, JNI, callbacks, lifecycle, concurrencia, streaming ASR,
  postprocesado, subtítulos, modelos, markers, recursos, manifest, i18n y CI.
  Resultado detallado en
  [`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md).
- **2026-08-03 — Registro de deuda futuro:** documentados cuatro bloqueadores
  P0, cuatro líneas P1 y cuatro líneas P2. Esta sesión sólo actualizó docs; no
  implementó los arreglos ni ejecutó builds/tests.
- **2026-07-29 — Diccionario del sistema Android:** sincronización hacia
  `filesDir/custom_words`, con editor y fallbacks de Settings.
- **2026-07-29 — Auto-stop IME/VAD:** marcador `auto_stop`, callback de
  auto-stop y umbrales adaptativos.
- **2026-07-28 — Corrector fonético:** post-filtro ES+EN en Rust con
  safe-fallback, cubriendo todas las superficies.

La historia detallada de las sesiones anteriores se conserva en las memorias
indexadas: `phonetic-corrector-2026-07-28.md`, `optimizations-2026-07-29.md`,
`polish-agents-2026-07-29.md` y `postprocess-final-only-2026-08-03.md`.

## 🔴 Bloqueadores P0 abiertos

1. Aislar `PostProcessor` por sesión/superficie; `cancelAll()` es global.
2. Invalidar y esperar workers antiguos de subtítulos mediante generación/token.
3. Verificar SHA-256 en la descarga runtime debug antes de escribir
   `active_model`.
4. Unificar NDK/SDK entre Gradle, workflows y docs; resolver las rutas
   `linux-x86_64` o declarar el límite real.

Ninguno de estos cuatro puntos está resuelto por documentación: requieren
cambios de código/configuración y validación posterior.

## 🟡 Deuda P1/P2

- Añadir operation-id a transcripción de archivos.
- Hacer atómicas todas las escrituras de markers cross-process.
- Probar PP con HTTP controlado: payload, cancelación, timeout, JSON inválido,
  fallback y concurrencia.
- Probar lifecycle de subtítulos/MediaProjection en Android 10–15 y ROM OEM.
- Habilitar `cargo test` real o documentar bloqueo reproducible en CI.
- Ejecutar `rustfmt`/lint de todo el alcance, no sólo archivos puntuales.
- Añadir smoke/instrumentation matrix para las seis superficies.
- Migrar strings visibles hardcodeadas a recursos.

## 🟡 Validación pendiente

- `./gradlew assembleDebug` y `assembleRelease` en el pipeline oficial.
- `testDebugUnitTest`, `checkModels`, `lintDebug` y translations en CI actual.
- Tests Rust del crate real cuando se resuelva `transcribe-cpp-sys`.
- Smoke test en dispositivo arm64 con modelo streaming y no streaming.
- Pruebas de cancelación rápida, cambio de modelo/idioma y PP concurrente.

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
