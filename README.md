# Linux-T · Linux-TC · Linux-TPro · Linux-TNube Pro

**Debian Linux completo dentro de Android — sin root.**

[![Platform](https://img.shields.io/badge/platform-Android%209%2B-3ddc84?logo=android&logoColor=white)](#requisitos)
[![Arch](https://img.shields.io/badge/arch-arm64--v8a-blue)](#requisitos)
[![Root](https://img.shields.io/badge/root-not%20required-brightgreen)](#por-qué-proot-y-no-chroot)
[![Engine](https://img.shields.io/badge/engine-PRoot-orange)](#por-qué-proot-y-no-chroot)
[![OpenClaw](https://img.shields.io/badge/OpenClaw-incluido%20(TC%2FPro)-red)](#ediciones)

App Android que ejecuta un **sistema Debian (bookworm, arm64) completo** —filesystem +
terminal interactivo— **empaquetado dentro del propio APK**. No necesita root, ni conexión a
internet para funcionar, ni pasos manuales: abres la app y tienes una shell Debian lista.

Proyecto **open source** pensado para distribuir Linux en el móvil de forma sencilla.

> **Creador:** Andres MAG

---

## 📦 Ediciones

Hay cuatro ediciones del mismo motor, pensadas para distintos usos:

| Edición | Versión | Tamaño APK | Incluye | Para quién |
|---|---|---|---|---|
| **Linux-T** | `0.4.4` | ~19 MB | Debian + terminal | Base, ligera |
| **Linux-TC** | `0.4.9` | ~135 MB | Linux-T + **OpenClaw** + bot de Telegram | Agente IA en el móvil |
| **Linux-TPro** | `1.0.4` | ~267 MB | Linux-TC + perfiles de paquetes, copiar logs, visor de docs | Todo empaquetado |
| **Linux-TNube Pro** | `1.1.0` | ~267 MB | Linux-TPro + **APIs de IA** conmutables y **router de modelos** | Agente IA multi-proveedor |

Las cuatro comparten el mismo terminal (xterm.js + PTY nativo), el motor **PRoot** y el
sistema de **pestañas**. Se pueden instalar a la vez (cada una tiene su propio `applicationId`).

---

## ⬇️ Descargas

Los APK se distribuyen desde carpetas de **MEGA** (enlaces permanentes):

- **Linux-T** → <https://mega.nz/folder/8f51QKxI#dOvUA8vteT5b_OHyz9hZtw>
- **Linux-TC** → <https://mega.nz/folder/UXYCUS4A#xIhcfw3blovIvmhjB5Xv8g>
- **Linux-TPro** → <https://mega.nz/folder/gDp1USDI#I-N4ByWtnb__IEg46nEQvQ>
- **Linux-TNube Pro** → [GitHub Releases](https://github.com/aurenox-global/Linux-T-Android/releases) *(subida a GitHub)*

También se publican las cuatro ediciones en **GitHub Releases** del repositorio.
Página de descargas (web autocontenida): [`index.html`](index.html)

> Los APK son builds **debug** (firmadas con la clave de depuración) con fines de prueba.

---

## ✨ Características

- 🐧 **Debian bookworm arm64** completo, empaquetado en el APK (una sola extracción la primera vez).
- 🖥️ **Terminal real** con xterm.js + PTY nativo (`ptyexec.c`) —no es un wrapper de comandos—:
  colores, control de job, `Ctrl+C`, `htop`, `vim`…
- 🗂️ **Pestañas (secciones)** independientes; cada una es una sesión `bash` viva y se puede
  **anclar** como favorita.
- 🎨 **6 temas** de terminal: Clásico, Dracula, Solarized, Gruvbox, Nord y Papel.
- 🧩 **Perfiles de paquetes** (Básico / Dev) desde Ajustes.
- 📊 **Monitor** de memoria, almacenamiento, carga y núcleos.
- 📱 **Segundo plano real**: servicio en primer plano + wake/wifi lock (las sesiones y el
  gateway siguen vivos al salir de la app).
- 📋 **Copiar logs del terminal** al portapapeles *(Pro)*.
- 📖 **Visor de documentación** integrado (lee el `DOCUMENTACION.md` de cada proyecto) *(Pro/TC)*.
- 🦞 **OpenClaw integrado** *(TC/Pro)*: Node.js v24 + agente IA, panel de API key (DeepSeek),
  bot de Telegram opcional y gateway en segundo plano con **watchdog** (se relanza solo).
- 🔌 Acceso a **/sdcard** y **/root/storage** desde el terminal.
- ☁️ **APIs de IA conmutables + router de modelos** *(TNube Pro)*: conecta varios proveedores
  (con plan gratis), elige el principal y deja que un **router estilo OpenRouter** decida el
  modelo y salte de proveedor si falla; incluye **medidor de uso**.

---

## 🧩 Requisitos

| Requisito | Valor |
|---|---|
| Sistema | **Android 9 (API 28)** o superior |
| Arquitectura | **arm64-v8a (aarch64)** — obligatorio |
| RAM | **4 GB** recomendado mínimo |
| Almacenamiento | ~1–1.5 GB libres (extracción del rootfs) |
| Root | **No** necesario (proot) |

> Los dispositivos **arm32-only** no están soportados (Debian moderno no tiene usuario arm32 viable).

---

## 🚀 Instalación y uso

1. Descarga el APK de la edición que quieras (enlaces [arriba](#️-descargas)).
2. Permite *instalar apps de fuentes desconocidas* para tu navegador/gestor de archivos.
3. Abre el APK e instálalo. La primera vez extrae Debian (puede tardar un poco).
4. ¡Listo! Ya tienes el terminal.

Dentro de la app:

- **Ajustes (⚙):** temas, perfiles de paquetes, segundo plano y (en TC/Pro) → **📖 Documentación**.
- **Monitor (📊):** memoria, disco, carga y núcleos.
- **OpenClaw (🦞)** *(TC/Pro):* pega tu API key de DeepSeek y activa el gateway; opcionalmente
  configura un bot de Telegram.

### Por qué proot y no chroot

Android **sin root bloquea `chroot` y los namespaces** (SELinux + restricciones del kernel).
La solución estándar del sector (Termux → `proot-distro`) es **PRoot**: intercepta syscalls
(`ptrace` + `seccomp`) y emula un rootfs, ejecutando los binarios **nativos ARM64** (sin
emulación tipo QEMU → velocidad real).

---

## 🏗️ Arquitectura (resumen)

```
Android (app) ── WebView (xterm.js) ── puente JS↔Kotlin (window.Android)
   │
   ├── Term          gestor de sesiones (hasta 10, buffer circular 512 KB)
   ├── RootfsInstaller  extrae proot + rootfs la 1ª vez (ROOTFS_SCHEMA)
   ├── TermProcessService  foreground service + wake/wifi lock
   └── Pty (JNI)     PTY nativo (fork/exec)
        │
        ▼  proot (ptrace/seccomp)
   Debian bookworm arm64  (/bin/bash, apt, python3, node, openclaw…)
   binds: /dev /proc /sys · /sdcard · /root/storage
```

> `targetSdk = 28` a propósito: Android 10+ bloquea el `exec` de binarios del directorio
> privado de la app (proot los necesita ejecutar desde ahí). Mismo truco que Termux.

Detalle completo en el `DOCUMENTACION.md` de cada proyecto.

---

## 🗂️ Estructura del repositorio

Cada edición es un proyecto Android independiente (Gradle/Kotlin) con la misma base:

```
.
├── README.md                 # este documento
├── index.html                # página de descargas (web autocontenida)
│
├── linux-t/                  # Linux-T  (applicationId: io.debi)
├── linux-tc/                 # Linux-TC (applicationId: io.debi.tc)
├── linux-tpro/               # Linux-TPro (applicationId: io.debipro)
└── linux-tnube/              # Linux-TNube Pro (applicationId: io.tnube)
    ├── README.md
    ├── DOCUMENTACION.md      # documentación técnica del proyecto
    ├── scripts/              # build-rootfs.sh, build-rootfs-openclaw.sh, make-icon.py, patch-paths.py
    ├── dist/                 # artefactos del rootfs
    └── app/                  # proyecto Android (Gradle/Kotlin)
        └── app/src/main/
            ├── cpp/          # PTY nativo (ptyexec.c)
            ├── java/io/debi/ # MainActivity, Term, RootfsInstaller, TermProcessService, Pty
            └── assets/       # web/ (xterm.js), proot/, rootfs/, docs/, oc-*/
```

---

## 🔧 Compilación desde el código

**Requisitos del host:** JDK 17 · Android SDK (platform 35, NDK `26.3.11579264`, CMake 3.22.1) ·
Gradle 8.13 (AGP 8.12.0, Kotlin 2.1.21).

```bash
cd <proyecto>/app
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug      # o el gradle del wrapper
# → app/app/build/outputs/apk/debug/Linux-T*-<versionName>-debug.apk
```

La tarea Gradle **`syncDocs`** copia el `DOCUMENTACION.md` del proyecto a los assets en cada
build, así el visor in-app siempre muestra la última versión del documento.

### Regenerar el rootfs

```bash
scripts/build-rootfs.sh            # Debian base (desde la imagen oficial de Docker)
scripts/build-rootfs-openclaw.sh   # + Node.js v24 + OpenClaw (TC/Pro)
```

> Tras regenerar el rootfs hay que **subir `ROOTFS_SCHEMA`** en `RootfsInstaller.kt`.

---

## 📚 Documentación

Cada proyecto incluye su documentación técnica en **`DOCUMENTACION.md`** (también legible
dentro de la app, en Ajustes → 📖 Documentación, y desde la web de descargas):

- `linux-t/DOCUMENTACION.md`
- `linux-tc/DOCUMENTACION.md`
- `linux-tpro/DOCUMENTACION.md`
- `linux-tnube/DOCUMENTACION.md`

---

## 📄 Licencia

El código propio de la app (incluido el PTY nativo `ptyexec.c`) se distribuye bajo
**Apache-2.0**. El binario **PRoot** proviene de su proyecto upstream y conserva su licencia.

> ⚠️ No se recomienda forkear Termux/UserLAnd tal cual (GPLv3 obliga a liberar todo el código).
> Este proyecto usa su **propio** PTY nativo y el binario PRoot upstream.

---

## 🗺️ Estado / Roadmap

- [x] Terminal real (xterm.js + PTY nativo) + proot
- [x] Pestañas, temas, perfiles, monitor, segundo plano
- [x] OpenClaw + Node.js preinstalados *(TC/Pro)*
- [x] Visor de documentación in-app y en la web
- [ ] Firmar releases con keystore propio
- [ ] (Fase 2) Escritorio gráfico (VNC / Termux:X11)
- [ ] Publicación (F-Droid / GitHub Releases / Amazon Appstore)

---

**Creador:** Andres MAG · Debian Linux en Android (proot).
