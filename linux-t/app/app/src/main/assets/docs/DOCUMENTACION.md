# Linux-T — Documentación técnica

**Debian Linux completo dentro de Android, sin root.**

- **Creador:** Andres MAG
- **Paquete:** `io.debi` · **namespace:** `io.debi`
- **Versión actual:** `0.4.2` (versionCode `16`)
- **Plataforma:** Android 9+ (API 28) · **arm64-v8a** (aarch64) · mínimo ~4 GB de RAM

> Linux-T es la **edición base** (terminal, sin OpenClaw). Para la edición con OpenClaw
> integrado ver **Linux-TC**; para la edición Pro ver **Linux-TPro**.

---

## Índice

1. [Qué es](#1-qué-es)
2. [Características](#2-características)
3. [Requisitos](#3-requisitos)
4. [Arquitectura](#4-arquitectura)
5. [Estructura del proyecto](#5-estructura-del-proyecto)
6. [Cómo funciona internamente](#6-cómo-funciona-internamente)
7. [Interfaz de usuario](#7-interfaz-de-usuario)
8. [Compilación](#8-compilación)
9. [Generación del rootfs](#9-generación-del-rootfs)
10. [Versionado y releases](#10-versionado-y-releases)
11. [Solución de problemas](#11-solución-de-problemas)
12. [Distribución](#12-distribución)

---

## 1. Qué es

**Linux-T** es una app Android que ejecuta un **sistema Debian completo** (filesystem +
terminal interactivo) empaquetado dentro del propio APK. No necesita root, ni conexión a
internet para funcionar (todo viene incluido), ni pasos manuales de instalación.

### Por qué proot y no chroot

Android **sin root bloquea `chroot` y los namespaces** (SELinux + restricciones del kernel).
La solución estándar del sector (Termux → `proot-distro`) es **PRoot**:

- PRoot intercepta las syscalls (`ptrace` + `seccomp`) y **emula un rootfs**.
- Los binarios se ejecutan **nativos ARM64** (no hay emulación tipo QEMU) → velocidad real.
- No requiere root.

---

## 2. Características

- 🐧 **Debian bookworm arm64** completo empaquetado en el APK (una sola extracción la primera vez).
- 🖥️ **Terminal interactivo** con xterm.js + PTY nativo (terminal **real**, no un wrapper).
- 🗂️ **Pestañas (secciones)** independientes: cada una es una sesión bash viva. Se pueden
  **anclar** como favoritas.
- 🎨 **6 temas** de terminal: Clásico, Dracula, Solarized, Gruvbox, Nord y Papel.
- 🧩 **Perfiles de paquetes**: Básico y Dev (se instalan/verifican desde Ajustes).
- 📊 **Monitor** de memoria, almacenamiento, carga y núcleos.
- 📱 **Segundo plano real**: servicio en primer plano + wake/wifi lock (las sesiones siguen
  vivas al salir de la app).
- 🔌 Acceso a **/sdcard** y **/root/storage** desde el terminal.

---

## 3. Requisitos

### Del dispositivo

| Requisito | Valor |
|---|---|
| Android | 9 (API 28) o superior |
| Arquitectura | **arm64-v8a (aarch64)** obligatorio |
| RAM | 4 GB recomendado mínimo |
| Almacenamiento | ~500 MB – 1 GB libres (extracción del rootfs) |

> Los equipos **arm32-only** quedan fuera: Debian moderno no tiene usuario arm32 viable.

### Del host de compilación

- JDK 17
- Android SDK (build-tools, platform 35, NDK `26.3.11579264`, CMake 3.22.1)
- Gradle 8.13 (wrapper en `~/.gradle/wrapper/dists`), AGP 8.12.0, Kotlin 2.1.21
- `local.properties` con `sdk.dir=<ruta Android SDK>`

Para **regenerar el rootfs** además: `bash`, `curl`, `tar`, `xz`, `docker`.

---

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│                       Android (app io.debi)                   │
│                                                              │
│  MainActivity (Activity)                                     │
│   └── WebView  ── file:///android_asset/web/index.html       │
│         · xterm.js (render terminal)                         │
│         · puente JS ↔ Kotlin:  window.Android.*              │
│                                                              │
│  Bridge (JavascriptInterface)                                │
│   · ready / newTab / selectTab / closeTab                    │
│   · send / resize / runLine                                  │
│   · sysStats / bgStatus / bgRequestBattery                   │
│                                                              │
│  Term (singleton)  ── gestor de sesiones                     │
│   · hasta 10 sesiones, buffer circular 512 KB                │
│   · procshim: bind de /proc/stat,/uptime,/loadavg            │
│                                                              │
│  RootfsInstaller  ── extrae proot + rootfs la 1ª vez         │
│  TermProcessService ── foreground service + wake/wifi lock   │
│  Pty (JNI libptyexec.so) ── PTY nativo fork/exec             │
└───────────────────────────┬──────────────────────────────────┘
                            │ proot (ptrace/seccomp)
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  filesDir/rootfs  =  Debian bookworm arm64                   │
│  (/bin/bash, apt, python3, …)                                │
│  binds: /dev /proc /sys  ·  /sdcard  ·  /root/storage        │
└──────────────────────────────────────────────────────────────┘
```

### Por qué targetSdk = 28

Android 10+ **bloquea el `exec` de binarios** alojados en el directorio privado de la app
cuando `targetSdk >= 29`. PRoot necesita ejecutar binarios desde `/data/data/io.debi/files/…`.
Por eso `targetSdk = 28` (mismo truco que Termux). Se mantiene `compileSdk = 35`.

---

## 5. Estructura del proyecto

```
debian-android/            # (proyecto Linux-T)
├── README.md
├── DOCUMENTACION.md       # este documento
├── scripts/
│   ├── build-rootfs.sh        # rootfs Debian base (desde imagen Docker oficial)
│   ├── make-icon.py           # genera iconos
│   └── patch-paths.py         # reescribe rutas en binarios proot (Termux→io.debi)
├── dist/                  # artefactos del rootfs (.tar, .tar.xz, meta)
└── app/                   # proyecto Android (Gradle/Kotlin)
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── cpp/              # ptyexec.c + CMakeLists.txt (PTY nativo)
        ├── jniLibs/arm64-v8a/ # libtalloc.so, libandroid-shmem.so
        ├── java/io/debi/
        │   ├── MainActivity.kt        # WebView + puente Android
        │   ├── Term.kt                # gestor de sesiones proot
        │   ├── RootfsInstaller.kt     # extracción/instalación del runtime
        │   ├── TermProcessService.kt  # servicio en primer plano
        │   └── Pty.kt                 # puente JNI
        └── assets/
            ├── web/          # index.html, xterm.js, addon-fit.js, xterm.css
            ├── proot/        # bin/proot, libexec/proot/loader(32), lib/*.so
            └── rootfs/       # debian-bookworm-arm64.tar.xz
```

---

## 6. Cómo funciona internamente

### 6.1 Instalación del runtime (una sola vez)

`RootfsInstaller.install()` se ejecuta en el primer arranque:

1. **Siempre** copia/refresca `assets/proot/` → `filesDir/usr/` (proot + loaders + libs) y les
   da permisos de ejecución.
2. Genera `filesDir/etc/resolv.conf` con los **DNS reales del sistema Android** (fallback
   `1.1.1.1` / `8.8.8.8`).
3. **Si el esquema cambió** (`ROOTFS_SCHEMA`), borra el rootfs anterior y extrae
   `assets/rootfs/debian-bookworm-arm64.tar.xz` (con `commons-compress` + `xz`), resolviendo
   **symlinks y hardlinks** (con fallback a copia).
4. Escribe el marcador `filesDir/.installed` con el número de esquema.

> **Importante:** al cambiar el rootfs o el extractor hay que **subir `ROOTFS_SCHEMA`** en
> `RootfsInstaller.kt` para forzar la re-extracción en dispositivos que ya lo tenían.

### 6.2 Sesiones (Term.kt)

- Cada **pestaña = una sesión** con su propio proceso `bash` dentro de proot.
- Máximo **10 sesiones simultáneas** (`MAX_SESSIONS`).
- La sesión **activa** entrega su salida en directo a la UI; las **inactivas** acumulan en un
  **buffer circular de 512 KB** que se vuelca al volver a esa pestaña.
- Cambiar de pestaña **nunca** cierra las demás.
- El singleton vive fuera de la Activity → las sesiones **sobreviven** a que la app pase a 2º plano.

### 6.3 Puente /proc (htop/top)

Android niega `/proc/stat`, `/proc/uptime` y `/proc/loadavg` a las apps, así que `htop`/`top` no
podrían leerlos dentro del guest. Si la propia app **sí** puede leerlos, se sirve una copia
refrescada cada segundo y se **bindean** como `/proc/stat` etc. dentro de proot.

### 6.4 PTY nativo (ptyexec.c)

- `Pty.create()` abre `/dev/ptmx`, hace `fork()`, y en el hijo: `setsid`, `TIOCSCTTY`, `dup2` de
  stdin/stdout/stderr al pts y `TIOCSWINSZ` (tamaño de terminal).
- Expone `read`/`write`/`resize`/`close`/`waitpid`/`kill` a Kotlin.
- Resultado: un **terminal real** (colores, control de job, `Ctrl+C`, etc.).

### 6.5 Argumentos de proot (`prootArgv`)

```
proot --kill-on-exit --link2symlink -0 -r <filesDir/rootfs>
      -b /dev -b /proc [-b <shim /proc…>] -b /sys
      -b <filesDir/etc/resolv.conf>:/etc/resolv.conf
      -b <sdcard>:/sdcard -b <sdcard>:/root/storage
      -w /root  /usr/bin/env -i HOME=/root PATH=… TERM=xterm-256color LANG=C.UTF-8
      /bin/bash -l
```

### 6.6 Servicio en primer plano

`TermProcessService` mantiene vivo el proceso Debian en segundo plano:

- **Notificación persistente** "Debian en ejecución" con botón **Detener**.
- **Wake lock** + **wifi lock** (se cogen solo cuando hay sesiones activas).
- `onTaskRemoved`: no se para al quitar la app de recientes.

---

## 7. Interfaz de usuario

La UI es una **WebView** que carga `assets/web/index.html` (xterm.js + `addon-fit`).

- **Barra superior:** selector de secciones + ☆ (anclar), ＋ (nueva), ✕ (cerrar); herramientas
  **📊 Monitor** y **⚙ Ajustes**.
- **Barra de teclas:** `Esc` · `Tab` · `Ctrl` · flechas · `|` · `/` · `-` · `⌫ línea`.
- **Ajustes (⚙):**
  - **Tema del terminal** (6 temas).
  - **Perfiles de instalación:** Básico y Dev.
  - **Segundo plano:** petición de exención de optimización de batería.
  - **Documentación:** resumen del proyecto desplegable.
  - **Acerca de:** `Linux-T` — Debian Linux en Android (proot) · **Creador: Andres MAG**.
- **Monitor (📊):** memoria, almacenamiento, carga (1/5/15) y núcleos.

### Puente JS ↔ Android (`window.Android`)

| Método | Función |
|---|---|
| `ready()` | La UI está lista → abre/activa la sección |
| `newTab()` / `selectTab(id)` / `closeTab(id)` | Gestión de pestañas |
| `send(b64)` / `resize(c,r)` / `runLine(line)` | E/S del terminal |
| `sysStats()` | JSON con memoria/disco/carga/núcleos |
| `bgStatus()` / `bgRequestBattery()` | Estado/petición del 2º plano |

---

## 8. Compilación

### Comando de build (debug)

```bash
cd app
export ANDROID_HOME=~/Android/Sdk
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle)
$GRADLE :app:assembleDebug --console=plain
```

Salida (se renombra automáticamente):

```
app/app/build/outputs/apk/debug/Linux-T-<versionName>-debug.apk
```

### Configuración clave (`app/app/build.gradle.kts`)

| Campo | Valor |
|---|---|
| `applicationId` | `io.debi` |
| `namespace` | `io.debi` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 28 / 35 |
| `abiFilters` | `arm64-v8a` |
| `versionCode` / `versionName` | 16 / `0.4.2` |
| Dependencias | `commons-compress:1.21`, `xz:1.9` |
| Firma (release) | config `debug` (⚠️ pendiente keystore propio) |

---

## 9. Generación del rootfs

### Rootfs base (`scripts/build-rootfs.sh`)

Exporta la imagen oficial `arm64v8/debian:bookworm` con Docker (evita `debootstrap`+`qemu`):

```bash
scripts/build-rootfs.sh
# → dist/debian-bookworm-arm64.tar(.xz) + dist/rootfs-meta.json (sha256)
```

### Iconos (`scripts/make-icon.py`)

Genera los mipmaps (legacy, foreground adaptativo y roundIcon).

### `patch-paths.py`

Reescribe strings de rutas en binarios de Termux para adaptarlos a `io.debi`
(`/data/data/com.termux/files/usr` → `/data/data/io.debi/files/usr`), sin desplazar secciones ELF.

> Tras regenerar el rootfs: **sube `ROOTFS_SCHEMA`** en `RootfsInstaller.kt`.

---

## 10. Versionado y releases

- Se incrementa **`versionCode`** (entero, +1) y **`versionName`** (semántico) en
  `app/app/build.gradle.kts`.
- Como el `applicationId` no cambia (`io.debi`), cada build **se instala encima** de la anterior
  **sin desinstalar** (misma firma).
- El APK final se renombra a `Linux-T-<versionName>-<buildType>.apk`.

### Reparto de builds

Si el APK supera el límite de Telegram (50 MB), se envía por **gofile.io**
(ver `~/.openclaw/workspace/gofile.sh`).

---

## 11. Solución de problemas

| Síntoma | Causa / solución |
|---|---|
| `Temporary failure resolving …` (apt/red) | Falta permiso `INTERNET` o el `resolv.conf`. |
| Las sesiones se cierran al salir | Activa **Permitir 2º plano** (Ajustes). |
| Faltan binarios tras actualizar | Sube **`ROOTFS_SCHEMA`** y reinstala para forzar re-extracción limpia. |
| `htop`/`top` sin datos de CPU | Android bloquea `/proc/stat`; se activa el **puente /proc** si la app puede leerlo. |
| La app se cierra en 2º plano (Xiaomi/Samsung/Huawei) | Concede la exención de batería y protege la app en recientes. |
| APK no instala (arquitectura) | El dispositivo no es **arm64-v8a** → no soportado. |

---

## 12. Distribución

Google Play **restringe apps que descargan y ejecutan código** (Device & Network Abuse) →
Termux fue retirada. Rutas recomendadas:

- **F-Droid** (ideal si el proyecto es open source).
- **APK directo / GitHub Releases**.
- **Amazon Appstore**.
- Play Store: posible, pero con riesgo de rechazo/retirada.

> ⚠️ Licencia: no se recomienda forkear Termux/UserLAnd tal cual (GPLv3 obliga a liberar todo el
> código). Linux-T usa su **propio** PTY nativo (`ptyexec.c`, Apache-2.0) y el binario proot del
> upstream.

---

*Documento generado para el proyecto Linux-T — Debian Linux en Android (proot). Creador: Andres MAG.*
