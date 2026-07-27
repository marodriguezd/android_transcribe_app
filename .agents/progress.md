# Progreso — estado actual del trabajo IA-asistido

**Última actualización:** 2026-07-27

## 🟢 Recién completado

- **2026-07-27** — `AGENTS.md` raíz (commit `fa36345`).
  - 348 líneas, estructura `§1 Resumen / §2 Stack / §3 Comandos+y+wiring /
    §4 Convenciones (1-11) / §5 Reglas / §6 Commits / §7 TL;DR`.

- **2026-07-27** — Dedup AGENTS ↔ README (commit `c0073b4`).
  - Trim de §3 Comandos Frecuentes (→ apunta a README + solo wiring AGENT-ONLY).
  - Reemplazo de §4.6 árbol de carpetas por tabla de mapping Rust↔Java.
  - Colapso de 3 filas de toolchain en §2 a una sola fila "Toolchain humano".
  - Cross-link explícito en el README hacia AGENTS.md.

## 🟡 En curso / siguiente

- **Hoy** — Bootstrap de la carpeta `.agents/` con jerarquía cross-session
  (este commit). Ficheros: `README.md`, `progress.md`, `architecture.md`,
  `spec.md`, `INDEX.md`, `memory/dedup-round-2026-07-27.md`.

## 🔴 Bloqueos / esperando humano

_Ninguno en este momento._

## ❓ Preguntas abiertas para humanos

- **¿Tracked o gitignored?** Esta primera versión va tracked en git (ver
  `.agents/README.md` §"Editar con commit-tracked"). Si el mantenimiento
  prefiere local-only, mover `.agents/` a `.gitignore` es trivial; cambia
  el flujo de "entre sesiones" a "sólo en esta máquina". Decisión del
  mantenedor.
- **Idioma del contenido:** español en prosa principal (consistente con
  AGENTS.md), inglés en términos clave (commit hashes, file names,
  AGENTS.md root). Suficiente o ¿todo inglés para IAs internacionalizadas?
- ~~**Naming `specs.md` (plural)** es ambiguo…~~ — **Resuelto**:
  renombrado a `INDEX.md` (mantiene el rol de índice sin confundirse
  con la spec singular).

## 🔗 Enlaces activos

- Spec canónica: [`spec.md`](./spec.md)
- Arquitectura: [`architecture.md`](./architecture.md)
- Índice cruzado: [`INDEX.md`](./INDEX.md)
- AGENTS.md raíz: [`../AGENTS.md`](../AGENTS.md)
- README: [`../README.md`](../README.md)
- RELEASE_NOTES: [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md)
- Memoria de hoy: [`memory/dedup-round-2026-07-27.md`](./memory/dedup-round-2026-07-27.md)
