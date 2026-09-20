#!/bin/sh
# oc-prepare — prepara OpenClaw dentro de Linux-TNube Pro.
# SOLO actúa si hace falta: si el workspace ya está bien, no ejecuta `doctor`
# (así no molesta ni tarda en cada arranque).
set +e
TPL=/usr/local/lib/node_modules/openclaw/docs/reference/templates
CFG="$HOME/.openclaw/openclaw.json"

# Workspace configurado (lectura directa del JSON, sin arrancar node).
WS=""
if [ -f "$CFG" ]; then
  WS=$(grep -oE '"workspace"[[:space:]]*:[[:space:]]*"[^"]+"' "$CFG" | head -n1 | sed -E 's/.*"([^"]+)"$/\1/')
fi
[ -z "$WS" ] && WS="$HOME/.openclaw/workspace"
case "$WS" in
  "~/"*) WS="$HOME/${WS#\~/}" ;;
  "~")   WS="$HOME/.openclaw/workspace" ;;
esac

changed=0
[ -d "$WS" ] || { mkdir -p "$WS"; changed=1; }
mkdir -p "$HOME/.openclaw" 2>/dev/null

for f in AGENTS.md SOUL.md USER.md TOOLS.md IDENTITY.md HEARTBEAT.md BOOT.md; do
  if [ ! -e "$WS/$f" ] && [ -e "$TPL/$f" ]; then
    cp -f "$TPL/$f" "$WS/$f" 2>/dev/null && changed=1
  fi
done
if [ ! -e "$WS/BOOTSTRAP.md" ] && { [ ! -e "$WS/AGENTS.md" ] || cmp -s "$WS/AGENTS.md" "$TPL/AGENTS.md"; }; then
  cp -f "$TPL/BOOTSTRAP.md" "$WS/" 2>/dev/null && changed=1
fi

if [ "$changed" = "1" ]; then
  echo "== oc-prepare: workspace reparado ($WS) → doctor --fix =="
  openclaw doctor --non-interactive --fix 2>&1 | tail -20
else
  echo "== oc-prepare: workspace OK ($WS) =="
fi
