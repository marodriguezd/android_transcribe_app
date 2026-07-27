# dedup-round — 2026-07-27

**Tema:** creación de infraestructura de documentación IA-asistida
(`AGENTS.md` raíz + dedup con `README.md` + bootstrap de la carpeta
`.agents/`).

## Contexto inicial

El proyecto es `android_transcribe_app` (fork de `notune/...`), app
Android de STT offline en Rust + Java con post-procesado IA opt-in. El
usuario pidió, en orden:

1. Generar un documento de instrucciones para IAs (yo entregué
   `OPENCODE.md`; el usuario pidió rename explícito a `AGENTS.md`).
2. Subirlo al repo (`fa36345`).
3. Recordar que `README.md` = humanos y `AGENTS.md` = IAs, sin mezcla.
4. Auditar solapamiento entre ambos y deduplicar (`c0073b4`).
5. Construir la carpeta `.agents/` con jerarquía persistente cross-
   session (este commit).

## Decisiones tomadas

- **Naming raíz:** `AGENTS.md` (estándar widely-known, mejor que
  `OPENCODE.md` o nombres ad-hoc).
- **Dedup scope:** §3 Comandos y §4.6 Estructura de AGENTS.md movidas a
  README o reemplazadas por tablas específicas del agente; §2 Stack
  colapsó 3 filas (JDK / NDK / cargo-ndk) en una sola fila "Toolchain
  humano" con anchor-link al README. Cross-link en el README desde el
  top hacia AGENTS.md.
- **Scope blockquote** añadido al top de AGENTS.md — pequeño scope
  creep respecto a la petición textual, pero mejora descubrabilidad para
  IAs futuras; el code-reviewer lo dejó pasar.
- **`.agents/` layout:** `README.md` (cómo usar el directorio) +
  `progress.md` (estado actual) + `spec.md` (QUÉ) + `architecture.md`
  (CÓMO) + `specs.md` (índice cruzado) +
  `memory/<topic>-YYYY-MM-DD.md`. Pluralidad:
    - singular para spec / architecture / progress / README (cada uno es
      single-source-of-truth en su dominio).
    - plural para `specs.md` (índice) y `memory/` (varios por sesión).
- **Tracked, no gitignored.** Razón: la mecánica "entre sesiones, entre
  agentes" exige persistencia + sincronización entre clones/máquinas.
  git es la persistencia. Si el mantenedor prefiere local-only, hay que
  editar `.gitignore`, no es trivial y cambia el modelo.

## Errores y callejones sin salida

- **`str_replace` falló dos veces** al trimear §2 con un bloque multi-
  línea byte-idéntico al del file. Probable causa: el `·` (U+00B7) o `→`
  (U+2192) en el `newString` cambió la codificación del JSON en el
  boundary. Resuelto con Python heredoc vía basher + `grep -n` para
  números de línea + `del`/`insert` por índice.
  - **Lección:** para edits multilínea con chars especiales, fallback a
    herramientas con numeración de línea explícita (sed, Python) es más
    fiable que `str_replace` por bloque.
- **El code-reviewer-minimax-m3** leyó `git status` antes de que el
  commit aterrizara (corrí `commit` y `reviewer` en paralelo, no se-
  cuencial). Marcó un "🔴 no se ha commiteado" que era stale. Decisión:
  ahora sé que cuando spawneo code-reviewer para commits, debo darle
  `git show <sha>` específico o esperar al commit antes de spawnarlo.

## Bloqueos encontrados

_Ninguno._

## Commits / PRs

- `fa36345 docs: add AGENTS.md with stack, conventions, and rules for AI assistants`
- `c0073b4 docs: trim duplication between AGENTS.md and README`
- `9f5026e` (este commit) — `docs: agents: bootstrap .agents/ registry hierarchy`

## Pendientes / seguimiento

- **Tracking vs. local-only:** decisión del mantenedor (ver
  [`../progress.md` §Preguntas abiertas](../progress.md)). Si prefiere
  gitignored, añadir `.agents/` a `.gitignore` y mover bootstrap a un
  script de instalación.
- **Naming `specs.md` (plural):** algunos lectores lo leen como "otra
  spec". Alternativas que se ofrecieron como followup: `INDEX.md` o
  `docs.md`.
- **Idioma contenido:** español en prosa principal (consistente con
  AGENTS.md), inglés en términos clave (commit hashes, file names).
  Mantener consistencia o migrar a EN-only para IAs internacionalizadas.

## Lessons learned (para futuras sesiones)

1. **AI conversation state vs. git history.** La conversación es la
   verdad del **POR QUÉ**; git es la verdad del **QUÉ**. Los dos se
   necesitan. `.agents/` busca unir ambas en un sitio navegable
   cross-session.
2. **Spanish + English mezclados en docs** funciona si los términos
   clave (commit hashes, file names, nombres JNI) van siempre en inglés
   para grep-abilidad. Mantener esa disciplina es valioso.
3. **Cross-doc anchors** (`#building`, `#prerequisites`) son fragmente
   por GitHub auto-generation: si renombrar una H2 rompe anchors, los
   links AGENTS → README se rompen sin aviso.
4. **AI agents deben abrir `AGENTS.md` y `.agents/progress.md` SIEMPRE
   primero**, antes de tocar código o leer README/spec. Esto minimiza
   decisiones redundantes en cada sesión.
5. **El re-read de `model_language` por `Engine::run`** está documentado
   en muchas partes a propósito — es la trampa histórica v0.1.20→21
   que más cuesta no recaer en ella.
