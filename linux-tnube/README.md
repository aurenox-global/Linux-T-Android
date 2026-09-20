# Linux-TNube Pro — Debian Linux en Android (edición Pro, todo empaquetado)

App Android que ejecuta un sistema **Debian** completo (filesystem + terminal ± escritorio),
para distribuir al público.

> 📘 **Documentación completa:** [`DOCUMENTACION.md`](DOCUMENTACION.md) (arquitectura, funcionamiento
> interno, compilación, rootfs, solución de problemas y changelog).
> **Creador:** Andres MAG

## Requisitos objetivo

- **Android 9 (API 28) en adelante**
- **Mínimo 4 GB de RAM**
- **arm64-v8a** (aarch64). Los equipos arm32-only quedan fuera (Debian moderno no tiene usuario arm32 viable).
- Sin root (proot). Opcionalmente mejor con root, pero la app debe funcionar sin él.

## Por qué proot y no chroot

Android sin root bloquea `chroot`/namespaces (SELinux + restricciones del kernel).
**PRoot** intercepta syscalls (`ptrace`+`seccomp`) y emula un root fs; los binarios se ejecutan
**nativos ARM64** (no es emulación tipo QEMU → velocidad real). Es el estándar del sector
(Termux → proot-distro).

## Camino elegido

**A) APK propio** con:
- Terminal emulator embebido (UI)
- Motor PRoot (arm64) + rootfs Debian arm64 empaquetado o descargado
- Opcional Fase 2: escritorio (XFCE) vía VNC / Termux:X11

Se descarta forkear Termux/UserLAnd tal cual por licencia **GPLv3** (obliga a liberar todo el
código si el objetivo es una app pública; válido si aceptamos open source — decisión pendiente).

## ⚠️ Distribución (crítico para "pública")

Google Play **restringe apps que descargan y ejecutan código** (Device & Network Abuse) — Termux
fue retirado. Rutas reales:
- **F-Droid** (ideal si open source)
- **APK directo / GitHub Releases**
- **Amazon Appstore**
- Play Store: posible pero con riesgo de rechazo/retirada; requiere cuidado extra.

## Estructura

```
linux-tpro/
├── README.md
├── scripts/
│   └── build-rootfs.sh      # genera rootfs Debian arm64 desde imagen Docker oficial
├── dist/                    # artefactos (rootfs .tar.xz + meta)
└── app/                     # (pendiente) proyecto Android Gradle/Kotlin
```

## Rootfs

`scripts/build-rootfs.sh` exporta `arm64v8/debian:bookworm` (imagen oficial Docker) a
`dist/debian-bookworm-arm64.tar.xz` + `rootfs-meta.json` (sha256).
Ventaja: no requiere debootstrap ni qemu en host x86.

## OpenClaw preinstalado (por defecto) — 2026-09-13

El rootfs viene con **Node.js v24 (arm64)** y **OpenClaw** ya instalados, para poder
usarlo desde el terminal de la app sin pasos extra:

- Node.js en `/opt/node` (symlinks en `/usr/local/bin`: `node`, `npm`, `npx`, `corepack`).
- OpenClaw global: `/usr/local/bin/openclaw` → `/usr/local/lib/node_modules/openclaw/openclaw.mjs`.

Uso dentro del terminal:

```sh
openclaw --version
openclaw            # TUI / onboarding
openclaw gateway    # arranca el gateway
```

Detalles de construcción:
- `scripts/build-rootfs-openclaw.sh` parte del rootfs base, añade Node arm64 (binario oficial
  precompilado) e instala OpenClaw con `npm install -g --os=linux --cpu=arm64` (sin emulación:
  se bajan los prebuilds arm64 correctos desde el host x86). Genera
  `app/app/src/main/assets/rootfs/debian-bookworm-arm64.tar.xz`.
- Al cambiar el rootfs hay que **subir `ROOTFS_SCHEMA`** en `RootfsInstaller.kt` para forzar
  la re-extracción en dispositivos que ya tenían una versión instalada.
- El rootfs crece: base ~28 MB → **~137 MB** (xz). El APK resultante pesa ~165 MB.
- ⚠️ Pendiente: probar OpenClaw en dispositivo real (proot). Riesgos conocidos: FFI nativa
  (`koffi`), `node-pty` y Playwright (navegadores no incluidos) pueden requerir ajustes.

## 🔌 APIs de IA y router de modelos (v1.1.0)

En **Ajustes → APIs de IA** puedes conectar varias APIs de modelos y elegir con qué trabaja el
agente. La mayoría tienen **plan gratuito** (Groq, Cerebras, Gemini, SambaNova, OpenRouter,
Mistral, Z.ai, Qwen, GitHub Models, Hugging Face, Together, NVIDIA NIM, Cohere, Chutes).

- **Interruptor por proveedor** + campo de **API key** (y enlace para conseguirla).
- **Principal**: el que se usa con el router apagado.
- **Router inteligente (estilo OpenRouter)**: decide el modelo según la petición
  (visión → multimodal, código → coder, contexto largo → 1M, resto → rápido) y salta de
  proveedor si uno falla o se agota. Es un servidor Node local compatible con OpenAI
  (`assets/oc-bin/tnube-router.mjs`) al que OpenClaw apunta como proveedor `tnube-router`.
- **Medidor de uso**: peticiones/tokens por proveedor y día (lo lleva el router) + consulta de la
  cuota oficial vía `openclaw status --usage`.

Catálogo editable: `app/app/src/main/assets/oc-config/providers.json`.
Detalles en [`DOCUMENTACION.md`](DOCUMENTACION.md) §15.

## Estado

- [x] Definición de requisitos y arquitectura
- [x] Script de generación de rootfs
- [x] UI terminal (xterm.js + PTY nativo) + proot
- [x] APK propio (package `io.debi`, label «Linux-TNube Pro»)
- [x] **APIs de IA conmutables + router estilo OpenRouter + medidor de uso** (v1.1.0)
- [x] OpenClaw + Node.js preinstalados en el rootfs (por defecto)
- [ ] Probar OpenClaw en dispositivo real (proot)
- [ ] (Fase 2) Escritorio VNC/X11
- [ ] Empaquetado + canal de distribución
