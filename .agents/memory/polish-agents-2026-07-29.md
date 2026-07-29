# polish-agents — 2026-07-29

**Tema:** limpieza y puesta al día de los ficheros agénticos (`.agents/` +
referencias cruzadas). El usuario pidió pulir todo lo agénico hasta dejarlo
"rozando lo perfecto".

## Contexto inicial

El repo estaba en un estado casi impecable tras la feature del corrector
fonético (2026-07-28), pero los ficheros de `.agents/` tenían:

1. **`progress.md`** con una tarea en "🟡 En curso" que ya estaba completada
   (la actualización de los propios ficheros agénticos con el corrector).
2. **`dedup-round-2026-07-27.md`** con 3 referencias al nombre antiguo
   `specs.md` (el fichero se renombró a `INDEX.md`).
3. Una pregunta abierta sobre el naming `specs.md` marcada con
   strikethrough en `progress.md` pero no limpiada.

## Decisiones tomadas

- **Mover, no borrar:** la tarea completada de actualización de ficheros
  agénticos se movió de "🟡 En curso" a "🟢 Recién completado" con fecha
  2026-07-29, en lugar de borrarla. Así queda trazabilidad de cuándo se
  terminó.
- **Renombrar sección:** "🟡 En curso / siguiente" → "🟡 Pendiente humano"
  porque las dos tareas restantes (Gradle build + smoke test) son
  exclusivamente del mantenedor, no de agentes IA.
- **`specs.md` → `INDEX.md`:** 3 ocurrencias actualizadas en
  `dedup-round-2026-07-27.md`. La pregunta pendiente se marcó como
  "Resuelto (julio 2026)".
- **Pregunta resuelta en `progress.md`:** el strikethrough se reemplazó
  por una línea limpia "Resuelto. Renombrado para evitar ambigüedad con
  la spec singular."

## Cambios realizados

| Fichero | Cambio |
|---|---|
| `.agents/progress.md` | Fecha → 2026-07-29; nueva entrada completada; sección renombrada a "Pendiente humano"; pregunta specs.md limpiada |
| `.agents/memory/dedup-round-2026-07-27.md` | 3× `specs.md` → `INDEX.md`; pregunta naming marcada como resuelta |

## Verificación

- `code-searcher` para `specs\.md`: 2 hits restantes, ambos en frases
  que documentan el histórico del rename (correcto).
- `code-reviewer-deepseek`: aprobado sin issues.
- `INDEX.md` ya se actualizó en la entrada anterior (phonetic-corrector).

## Errores y callejones sin salida

_Ninguno._ Sesión directa, sin bloqueos.

## Lecciones aprendidas

1. **Los memory files envejecen:** un fichero escrito el 2026-07-27
   referenciaba `specs.md` que ya no existe. Las referencias a nombres de
   fichero en docs de memoria deberían verificarse si el fichero tiene
   más de unos días — los renombres son silenciosos para los docs
   estáticos.
2. **`progress.md` es el canary:** si la sección "En curso" contiene algo
   que ya está en los docs, es señal de que hay que moverlo a "Recién
   completado". Un agente que abre `progress.md` primero debería
   detectar esto.
3. **Las preguntas abiertas resueltas deben limpiarse:** el strikethrough
   es un estado transitorio (el humano acaba de decidir). Días después,
   debe consolidarse como "Resuelto" limpio o eliminarse. Si no, el doc
   acumula ruido.
