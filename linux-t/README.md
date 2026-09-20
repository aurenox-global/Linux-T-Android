# debian-android — Debian Linux en Android

App Android que ejecuta un sistema **Debian** completo (filesystem + terminal ± escritorio),
para distribuir al público.

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
debian-android/
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

## Estado

- [x] Definición de requisitos y arquitectura
- [x] Script de generación de rootfs
- [ ] Decidir UI: terminal-only vs escritorio gráfico
- [ ] Scaffold app Android (Gradle + Kotlin)
- [ ] Integración PRoot + arranque de Debian
- [ ] Terminal UI
- [ ] (Fase 2) Escritorio VNC/X11
- [ ] Empaquetado + canal de distribución
