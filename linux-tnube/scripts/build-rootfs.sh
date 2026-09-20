#!/usr/bin/env bash
# build-rootfs.sh — Genera un rootfs Debian arm64 (bookworm) listo para empaquetar en la app Android.
# Estrategia: exportar la imagen oficial de Docker (evita debootstrap+qemu en host x86).
# Salida: dist/debian-bookworm-arm64.tar.xz  (+ dist/rootfs-meta.json)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist work

IMAGE="${IMAGE:-arm64v8/debian:bookworm}"
PLATFORM="${PLATFORM:-linux/arm64}"
OUT="dist/debian-bookworm-arm64.tar"

echo "==> Pull $IMAGE ($PLATFORM)"
docker pull --platform "$PLATFORM" "$IMAGE"

echo "==> Añadiendo paquetes base (opcional, requiere emulación arm64)"
# Nota: instalar paquetes extra aquí requiere binfmt/qemu; por defecto solo exportamos la base.
cid="$(docker create --platform "$PLATFORM" "$IMAGE" /bin/true)"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo "==> Exportando rootfs -> $OUT"
docker export "$cid" -o "$OUT"

echo "==> Comprimiendo"
xz -T0 -6 -f -k "$OUT"

sz=$(du -h "$OUT.xz" | cut -f1)
sha=$(sha256sum "$OUT.xz" | cut -d' ' -f1)
cat > dist/rootfs-meta.json <<EOF
{
  "image": "$IMAGE",
  "platform": "$PLATFORM",
  "arch": "aarch64",
  "file": "$(basename "$OUT").xz",
  "size": "$sz",
  "sha256": "$sha",
  "builtAt": "$(date -Iseconds)"
}
EOF
echo "==> OK: dist/$(basename "$OUT").xz ($sz)"
cat dist/rootfs-meta.json
