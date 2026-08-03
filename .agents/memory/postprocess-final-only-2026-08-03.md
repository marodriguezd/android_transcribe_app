# Postprocesado final-only — 2026-08-03

**Tema:** separar la previsualización streaming del ASR del postprocesado AI.

## Problema observado

La previsualización del modelo transcriptor era fluida y correcta, pero el flujo
anterior del postprocesador usaba SSE (`stream: true`) y pegaba cada delta del
LLM directamente en el editor del IME. Una respuesta incompleta, una revisión
del proveedor o un corte de red podía dejar texto parcial, duplicado o forzar
la limpieza mediante `deleteSurroundingText`. El usuario confirmó que, tras
muchas pruebas, el postprocesado no parecía funcionar de forma fiable.

## Decisión

- El streaming del transcriptor se conserva intacto y sus parciales son
  exclusivamente visuales.
- El resultado final del ASR llega por `onTextTranscribed`.
- Si PP está desactivado, el texto ASR se comete directamente.
- Si PP está activado, `PostProcessor.process()` hace una sola petición
  OpenAI-compatible no-streaming y espera la respuesta JSON completa.
- El IME y el popup cometen una sola vez el resultado refinado.
- Ante cancelación, error de red, HTTP no exitoso, JSON inválido, `choices`
  vacío o contenido vacío, se comete el transcript crudo.
- Si el usuario desactiva PP mientras la petición está en vuelo, el transcript
  crudo gana; `cancelAll()` y el broadcast main → `:ime` permanecen activos.
- Los prompts con `${output}` siguen inyectando el transcript una sola vez en
  el system message; sin marcador, el transcript viaja como user message.

## Cambios

- `PostProcessor.java`: eliminado el camino SSE, `StreamCallback`, reintentos
  de streaming y fallback HTTP 400; conservados cliente compartido, cierre de
  `Response`, cancelación, validadores, payload explícito `stream: false` y `/models`.
- `RustInputMethodService.java`: reemplazado `processStreaming` y el commit de
  tokens por `process` + un commit final único.
- `RecognizeActivity.java`: reemplazado el renderizado de tokens del LLM por
  `process` + entrega de un resultado final único.
- `AGENTS.md`, `.agents/architecture.md` y `.agents/progress.md`: actualizados
  con el contrato activo final-only.
- La memoria de optimizaciones de 2026-07-29 queda marcada como histórica,
  no eliminada, para conservar la trazabilidad.

## Validación pendiente/completada

Validación local completada: `./gradlew testDebugUnitTest lintDebug` pasó con
`BUILD SUCCESSFUL` y `python3 scripts/check_translations.py` pasó para los 6
locales. El build Android completo depende del NDK/Cargo del runner y el
workflow oficial `.github/workflows/debug_telegram.yml` es el encargado de
generar y enviar el APK de depuración a Telegram.
