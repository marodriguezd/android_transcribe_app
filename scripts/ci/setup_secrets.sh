#!/usr/bin/env bash
# =============================================================================
# setup_secrets.sh
# -----------------------------------------------------------------------------
# Configura TELEGRAM_BOT_TOKEN y TELEGRAM_CHAT_ID como secretos del repositorio
# `marodriguezd/android_transcribe_app` usando la CLI oficial `gh`.
#
# SAFETY MODEL (no negociable):
#   1. Auth-guard: aborta inmediatamente si GH_TOKEN no es valido. Ningun token
#      se imprime en pantalla en ningun momento.
#   2. Inventario ANTES y DESPUES: solo se imprimen NAME / UPDATED / VISIBILITY.
#      Nunca el valor. Si el secret ya existe se exige escribir "yes" explicito
#      antes de sobrescribir (sin force-update silencioso).
#   3. Silent read: `read -rs` no muestra el valor en pantalla ni aparece en
#      ~/.bash_history.
#   4. Stdin pipe a `gh secret set`: sin --body, sin argv, invisible para `ps`.
#   5. Scope de funcion: `v` y `CONFIRM` son `local` dentro de `assign_secret`
#      y se destruyen cuando la funcion retorna (o cuando Ctrl-C / SIGINT
#      provoca la salida del script). El trap EXIT se omite a proposito: en
#      bash, `unset` desde fuera de la funcion no alcanza nombres locales
#      fuera de scope, asi que el trap seria simbolico y solo añadiria ruido.
#
# REQUISITOS:
#   - gh CLI v2.0+
#   - GH_TOKEN exportado en el entorno (scope "repo").
#
# USO:
#   chmod +x scripts/ci/setup_secrets.sh
#   scripts/ci/setup_secrets.sh
# =============================================================================
set -u

REPO="marodriguezd/android_transcribe_app"

# ---------- 0) Auth-guard ----------------------------------------------------
if ! gh auth status 2>&1 | grep -q "Logged in to"; then
  printf '\nERROR: GH_TOKEN invalido o ausente.\n' >&2
  printf 'Exporta GH_TOKEN=ghp_... (scope \"repo\") y vuelve a ejecutar.\n' >&2
  exit 1
fi

# ---------- 1) Inventario ANTES (solo lectura) --------------------------------
printf '\n=== Inventario ANTES ===\n'
gh secret list --repo "$REPO"

# ---------- 2) Helpers --------------------------------------------------------
# Requiere gh CLI >= 2.20 para `--json name`; cae a awk si no esta disponible.
has_secret() {
  local name="$1"
  if gh secret list --repo "$REPO" --json name --jq '.[].name' >/dev/null 2>&1; then
    gh secret list --repo "$REPO" --json name --jq '.[].name' | grep -qx "$name"
  else
    gh secret list --repo "$REPO" | awk 'NR>1{print $1}' | grep -qx "$name"
  fi
}

assign_secret() {
  local name="$1"

  if has_secret "$name"; then
    local CONFIRM=""
    read -rs -p "  '$name' YA EXISTE - sobreescribir? (yes/no): " CONFIRM
    echo
    if [ "$CONFIRM" != "yes" ]; then
      echo "  omitido"
      return
    fi
  fi

  local v=""
  read -rs -p "  $name: " v
  echo
  if [ -z "$v" ]; then
    echo "  ERROR: valor vacio, omitido"
    return
  fi

  # Stdin pipe: nada en argv, nada en shell history, nada en `ps aux`.
  # El lado derecho muestra OK en exito o FAIL en cualquier error de gh
  # (red, auth, repo no encontrado, scope insuficiente). Nunca se imprime $v.
  if printf '%s' "$v" | gh secret set "$name" --repo "$REPO"; then
    echo "  OK"
  else
    local rc=$?
    echo "  FAIL (gh salio con codigo $rc - revisa gh auth status, red, o scope del token)"
    return $rc
  fi
  # Limpieza local: destruye $v dentro del frame antes de retornar para
  # que la promesa del SAFETY MODEL item 5 sea exacta.
  unset v
  return 0
}

# ---------- 3) Asignacion ----------------------------------------------------
printf '\n=== Asignacion silenciosa ===\n'
assign_secret TELEGRAM_BOT_TOKEN
assign_secret TELEGRAM_CHAT_ID

# ---------- 4) Inventario DESPUES --------------------------------------------
printf '\n=== Inventario DESPUES ===\n'
gh secret list --repo "$REPO"

printf '\nListo. Verifica que ambas filas aparecen con Updated reciente.\n'
