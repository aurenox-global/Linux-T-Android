#!/usr/bin/env bash
# build-rootfs-openclaw.sh
# Parte del rootfs Debian arm64 base y le añade Node.js v24 (arm64) + OpenClaw,
# generando el asset que se empaqueta en la app:
#   app/app/src/main/assets/rootfs/debian-bookworm-arm64.tar.xz
#
# No necesita qemu: el binario de Node arm64 se descarga precompilado y npm
# resuelve los prebuilds arm64 con --os=linux --cpu=arm64 desde el host x86.
#
# Requisitos en el host: bash, curl, tar, xz, npm (cualquier versión moderna).
#
# Uso:  scripts/build-rootfs-openclaw.sh [version_openclaw]
#       scripts/build-rootfs-openclaw.sh 2026.9.4
set -euo pipefail
cd "$(dirname "$0")/.."

NODE_VERSION="${NODE_VERSION:-v24.21.0}"
OPENCLAW_VERSION="${1:-latest}"
BASE_TAR="${BASE_TAR:-dist/debian-bookworm-arm64.tar}"   # rootfs base sin comprimir
ASSET="app/app/src/main/assets/rootfs/debian-bookworm-arm64.tar.xz"

WORK="work/rootfs-oc"
[ -f "$BASE_TAR" ] || { echo "Falta $BASE_TAR (genera el rootfs base con scripts/build-rootfs.sh)"; exit 1; }

echo "==> Limpiando $WORK"
rm -rf "$WORK"; mkdir -p "$WORK"

echo "==> Extrayendo rootfs base"
tar -xf "$BASE_TAR" -C "$WORK"

echo "==> Descargando Node $NODE_VERSION (linux-arm64)"
tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/node.tar.xz" \
  "https://nodejs.org/dist/latest-v24.x/node-${NODE_VERSION}-linux-arm64.tar.xz"
tar -xf "$tmp/node.tar.xz" -C "$tmp"
mkdir -p "$WORK/opt"
mv "$tmp/node-${NODE_VERSION}-linux-arm64" "$WORK/opt/node"
rm -rf "$tmp"

echo "==> Symlinks de Node en /usr/local/bin"
mkdir -p "$WORK/usr/local/bin" "$WORK/usr/local/lib"
for b in node npm npx corepack; do ln -sf "/opt/node/bin/$b" "$WORK/usr/local/bin/$b"; done

echo "==> Instalando OpenClaw ($OPENCLAW_VERSION) para linux/arm64"
npm install -g "openclaw@${OPENCLAW_VERSION}" \
  --prefix "$WORK/usr/local" \
  --os=linux --cpu=arm64 \
  --ignore-scripts --no-audit --no-fund

echo "==> Recortando (source maps, docs, tests) — conservando docs/reference/templates"
OC="$WORK/usr/local/lib/node_modules/openclaw"
find "$OC" -name '*.map' -delete 2>/dev/null || true
rm -rf "$OC/test" "$OC/tests" 2>/dev/null || true
# docs/: conservamos SOLO reference/templates (plantillas del workspace AGENTS.md, SOUL.md…
# que OpenClaw necesita; sin ellas el agente falla con "Missing workspace template").
if [ -d "$OC/docs" ]; then
  find "$OC/docs" -mindepth 1 -maxdepth 1 -not -name reference -exec rm -rf {} + 2>/dev/null || true
  find "$OC/docs/reference" -mindepth 1 -maxdepth 1 -not -name templates -exec rm -rf {} + 2>/dev/null || true
fi
find "$WORK/usr/local/lib/node_modules" -type d \
  \( -name test -o -name tests -o -name __tests__ \) -prune -exec rm -rf {} + 2>/dev/null || true

echo "==> Marcador de versión"
mkdir -p "$WORK/etc"
printf 'Linux-TPro · OpenClaw preinstalado (node %s, openclaw %s)\n' \
  "$NODE_VERSION" "$OPENCLAW_VERSION" > "$WORK/etc/linux-tc-openclaw.txt"

echo "==> Empaquetando + comprimiendo -> $ASSET"
mkdir -p "$(dirname "$ASSET")"
tar -cf dist/debian-bookworm-arm64-oc.tar -C "$WORK" .
xz -T0 -6 -f -k dist/debian-bookworm-arm64-oc.tar
cp -f dist/debian-bookworm-arm64-oc.tar.xz "$ASSET"

sz=$(du -h "$ASSET" | cut -f1)
echo "==> OK: $ASSET ($sz)"
echo "   ⚠️  Sube ROOTFS_SCHEMA en RootfsInstaller.kt para forzar re-extracción en dispositivos."
