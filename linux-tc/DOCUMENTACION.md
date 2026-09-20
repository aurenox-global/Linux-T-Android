# Linux-TC — Documentación técnica

**Debian Linux completo dentro de Android, sin root — con OpenClaw integrado.**

- **Creador:** Andres MAG
- **Paquete:** `io.debi.tc` · **namespace:** `io.debi`
- **Versión actual:** `0.4.9` (versionCode `23`)
- **Plataforma:** Android 9+ (API 28) · **arm64-v8a** (aarch64) · mínimo ~4 GB de RAM

> Linux-TC = **Linux-T + OpenClaw** (agente IA con Node.js y bot de Telegram) preinstalado en el
> rootfs. Para la edición base sin OpenClaw ver **Linux-T**; para la edición Pro ver **Linux-TPro**.

---

## Índice

1. [Qué es](#1-qué-es)
2. [Características](#2-características)
3. [Requisitos](#3-requisitos)
4. [Arquitectura](#4-arquitectura)
5. [Estructura del proyecto](#5-estructura-del-proyecto)
6. [Cómo funciona internamente](#6-cómo-funciona-internamente)
7. [Interfaz de usuario](#7-interfaz-de-usuario)
8. [OpenClaw integrado](#8-openclaw-integrado)
9. [Compilación](#9-compilación)
10. [Generación del rootfs](#10-generación-del-rootfs)
11. [Versionado y releases](#11-versionado-y-releases)
12. [Solución de problemas](#12-solución-de-problemas)
13. [Distribución](#13-distribución)

---

## 1. Qué es

**Linux-TC** es una app Android que ejecuta un **sistema Debian completo** (filesystem +
terminal interactivo) empaquetado dentro del propio APK, **sin root** y **sin conexión a
internet** para funcionar. Además incluye **OpenClaw** (agente IA) con **Node.js v24** ya
instalados en el rootfs, listos para usar desde el terminal o desde el panel de la app.

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
- 🦞 **OpenClaw integrado** (agente IA) con Node.js v24 preinstalado, arranque del gateway en
  segundo plano con watchdog (se relanza solo si se cae) y bot de Telegram opcional.
- 📱 **Segundo plano real**: servicio en primer plano + wake/wifi lock.
- 🔌 Acceso a **/sdcard** y **/root/storage** desde el terminal.

---

## 3. Requisitos

### Del dispositivo

| Requisito | Valor |
|---|---|
| Android | 9 (API 28) o superior |
| Arquitectura | **arm64-v8a (aarch64)** obligatorio |
| RAM | 4 GB recomendado mínimo |
| Almacenamiento | ~1 – 1.5 GB libres (extracción del rootfs con Node+OpenClaw) |

> Los equipos **arm32-only** quedan fuera: Debian moderno no tiene usuario arm32 viable.

### Del host de compilación

- JDK 17
- Android SDK (build-tools, platform 35, NDK `26.3.11579264`, CMake 3.22.1)
- Gradle 8.13, AGP 8.12.0, Kotlin 2.1.21
- `local.properties` con `sdk.dir=<ruta Android SDK>`

Para **regenerar el rootfs** además: `bash`, `curl`, `tar`, `xz`, `npm` moderno.

---

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│                      Android (app io.debi.tc)                 │
│                                                              │
│  MainActivity (Activity)                                     │
│   └── WebView  ── file:///android_asset/web/index.html       │
│         · xterm.js (render terminal)                         │
│         · puente JS ↔ Kotlin:  window.Android.*              │
│                                                              │
│  Bridge (JavascriptInterface)                                │
│   · ready / newTab / selectTab / closeTab                    │
│   · send / resize / runLine                                  │
│   · ocStatus / ocInstall / ocSetKey / ocSetTgToken /         │
│     ocGateway / ocDoctor / ocBatteryStatus / sysStats        │
│                                                              │
│  Term (singleton)  ── gestor de sesiones                     │
│   · hasta 10 sesiones, buffer circular 512 KB                │
│   · procshim: bind de /proc/stat,/uptime,/loadavg            │
│   · watchdog del gateway OpenClaw                            │
│                                                              │
│  RootfsInstaller  ── extrae proot + rootfs la 1ª vez         │
│  TermProcessService ── foreground service + wake/wifi lock   │
│  Pty (JNI libptyexec.so) ── PTY nativo fork/exec             │
└───────────────────────────┬──────────────────────────────────┘
                            │ proot (ptrace/seccomp)
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  filesDir/rootfs  =  Debian bookworm arm64                   │
│  (/bin/bash, apt, python3, node, openclaw, …)                │
│  binds: /dev /proc /sys  ·  /sdcard  ·  /root/storage        │
└──────────────────────────────────────────────────────────────┘
```

### Por qué targetSdk = 28

Android 10+ **bloquea el `exec` de binarios** alojados en el directorio privado de la app cuando
`targetSdk >= 29`. PRoot necesita ejecutar binarios desde `/data/data/io.debi.tc/files/…`. Por eso
`targetSdk = 28`. Se mantiene `compileSdk = 35`.

---

## 5. Estructura del proyecto

```
Linux-TC/
├── README.md
├── DOCUMENTACION.md       # este documento
├── scripts/
│   ├── build-rootfs.sh           # rootfs Debian base (desde imagen Docker oficial)
│   ├── build-rootfs-openclaw.sh  # rootfs base + Node v24 + OpenClaw
│   ├── make-icon.py              # genera iconos
│   └── patch-paths.py            # reescribe rutas en binarios proot
├── dist/                  # artefactos del rootfs (.tar, .tar.xz, meta)
└── app/
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── cpp/              # ptyexec.c + CMakeLists.txt (PTY nativo)
        ├── jniLibs/arm64-v8a/ # libtalloc.so, libandroid-shmem.so
        ├── java/io/debi/
        │   ├── MainActivity.kt        # WebView + puente Android
        │   ├── Term.kt                # gestor de sesiones proot + watchdog gateway
        │   ├── RootfsInstaller.kt     # extracción/instalación del runtime
        │   ├── TermProcessService.kt  # servicio en primer plano
        │   └── Pty.kt                 # puente JNI
        └── assets/
            ├── web/          # index.html, xterm.js, addon-fit.js, xterm.css
            ├── proot/        # bin/proot, libexec/proot/loader(32), lib/*.so
            ├── rootfs/       # debian-bookworm-arm64.tar.xz (con Node+OpenClaw)
            ├── oc-templates/ # plantillas del workspace de OpenClaw
            ├── oc-config/    # openclaw.json por defecto
            └── oc-bin/       # oc-prepare.sh
```

---

## 6. Cómo funciona internamente

### 6.1 Instalación del runtime (una sola vez)

`RootfsInstaller.install()` se ejecuta en el primer arranque:

1. **Siempre** copia/refresca `assets/proot/` → `filesDir/usr/` y les da permisos de ejecución.
2. Genera `filesDir/etc/resolv.conf` con los **DNS reales del sistema Android** (fallback
   `1.1.1.1` / `8.8.8.8`).
3. **Si el esquema cambió** (`ROOTFS_SCHEMA`), borra el rootfs anterior y extrae
   `assets/rootfs/debian-bookworm-arm64.tar.xz`, resolviendo **symlinks y hardlinks**.
4. Escribe el marcador `filesDir/.installed`.
5. Copia las **plantillas del workspace** de OpenClaw y la **config por defecto**, y refresca
   el script `oc-prepare`.

> **Importante:** al cambiar el rootfs o el extractor hay que **subir `ROOTFS_SCHEMA`** en
> `RootfsInstaller.kt`.

### 6.2 Sesiones (Term.kt)

- Cada **pestaña = una sesión** con su propio proceso `bash` dentro de proot.
- Máximo **10 sesiones simultáneas** (`MAX_SESSIONS`).
- Sesión **activa** → salida en directo; **inactivas** → buffer circular de 512 KB que se vuelca
  al volver.
- El singleton vive fuera de la Activity → las sesiones **sobreviven** al paso a 2º plano.

### 6.3 Puente /proc (htop/top)

Android niega `/proc/stat`, `/proc/uptime` y `/proc/loadavg` a las apps. Si la propia app **sí**
puede leerlos, se sirve una copia refrescada cada segundo y se **bindean** dentro de proot.

### 6.4 PTY nativo (ptyexec.c)

`Pty.create()` abre `/dev/ptmx`, hace `fork()`, y en el hijo: `setsid`, `TIOCSCTTY`, `dup2` de
stdin/stdout/stderr al pts y `TIOCSWINSZ`. Expone `read`/`write`/`resize`/`close`/`waitpid`/`kill`
a Kotlin.

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

`TermProcessService` mantiene vivo el proceso Debian en segundo plano: notificación persistente
con botón **Detener**, **wake + wifi lock** (solo con sesiones activas) y `onTaskRemoved` que no
para al quitar la app de recientes.

---

## 7. Interfaz de usuario

La UI es una **WebView** que carga `assets/web/index.html` (xterm.js + `addon-fit`).

- **Barra superior:** selector de secciones + ☆ (anclar), ＋ (nueva), ✕ (cerrar); herramientas
  **🦞 OpenClaw**, **📊 Monitor** y **⚙ Ajustes**.
- **Barra de teclas:** `Esc` · `Tab` · `Ctrl` · flechas · `|` · `/` · `-` · `⌫ línea`.
- **Ajustes (⚙):**
  - **Tema del terminal** (6 temas).
  - **Perfiles de instalación:** Básico y Dev.
  - **Documentación:** resumen del proyecto desplegable.
  - **Acerca de:** `Linux-TC` — Debian Linux en Android (proot) + OpenClaw · **Creador: Andres MAG**.
- **Monitor (📊):** memoria, almacenamiento, carga (1/5/15) y núcleos.

### Puente JS ↔ Android (`window.Android`)

| Método | Función |
|---|---|
| `ready()` | La UI está lista → abre/activa la sección |
| `newTab()` / `selectTab(id)` / `closeTab(id)` | Gestión de pestañas |
| `send(b64)` / `resize(c,r)` / `runLine(line)` | E/S del terminal |
| `sysStats()` | JSON con memoria/disco/carga/núcleos |
| `ocStatus()` / `ocInstall()` / `ocDoctor()` | Estado/runtime de OpenClaw |
| `ocSetKey(key,start)` / `ocSetTgToken(tok,start)` | API key / token de bot |
| `ocGateway(on)` | Arranca/para el gateway |
| `ocBatteryStatus()` / `ocRequestBattery()` | Estado/petición del 2º plano |

---

## 8. OpenClaw integrado

El rootfs incluye **Node.js v24 (arm64)** y **OpenClaw** ya instalados:

- Node en `/opt/node` (symlinks en `/usr/local/bin`: `node`, `npm`, `npx`, `corepack`).
- OpenClaw global: `/usr/local/bin/openclaw` → `/usr/local/lib/node_modules/openclaw/openclaw.mjs`.
- Config por defecto (`assets/oc-config/openclaw.json`): gateway local en el puerto **18789**,
  proveedor **DeepSeek** (el usuario solo mete su API key desde Ajustes → OpenClaw).

### Panel OpenClaw en la app

- **Dependencias:** versiones de Node.js, npm y OpenClaw (✅/❌).
- **API key del proveedor (DeepSeek):** se guarda y arranca el gateway.
- **Bot de Telegram (opcional):** guarda el token y reinicia el gateway.
- **Segundo plano:** petición de exención de optimización de batería.
- **Gateway:** interruptor para arrancar/parar. Se ejecuta en **su propia pestaña** (anclada) y
  sigue vivo en segundo plano.
- **Reparar (doctor):** ejecuta `oc-prepare` + `openclaw doctor --fix`.

### Watchdog del gateway

Si el gateway estaba activado y su proceso muere, `Term.pump()` lo **relanza solo** tras 5 s.

---

## 9. Compilación

```bash
cd app
export ANDROID_HOME=~/Android/Sdk
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle)
$GRADLE :app:assembleDebug --console=plain
# → app/app/build/outputs/apk/debug/Linux-TC-<versionName>-debug.apk
```

### Configuración clave (`app/app/build.gradle.kts`)

| Campo | Valor |
|---|---|
| `applicationId` | `io.debi.tc` |
| `namespace` | `io.debi` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 28 / 35 |
| `abiFilters` | `arm64-v8a` |
| `versionCode` / `versionName` | 23 / `0.4.9` |
| Dependencias | `commons-compress:1.21`, `xz:1.9` |
| Firma (release) | config `debug` (⚠️ pendiente keystore propio) |

---

## 10. Generación del rootfs

### Rootfs base (`scripts/build-rootfs.sh`)

Exporta la imagen oficial `arm64v8/debian:bookworm` con Docker:

```bash
scripts/build-rootfs.sh
# → dist/debian-bookworm-arm64.tar(.xz) + dist/rootfs-meta.json (sha256)
```

### Rootfs con OpenClaw (`scripts/build-rootfs-openclaw.sh`)

Parte del rootfs base, añade **Node v24 (arm64)** y **OpenClaw** (`--os=linux --cpu=arm64`, sin
emulación) y genera el asset empaquetado:

```bash
scripts/build-rootfs-openclaw.sh            # última versión
scripts/build-rootfs-openclaw.sh 2026.9.4   # versión concreta
# → app/app/src/main/assets/rootfs/debian-bookworm-arm64.tar.xz
```

Recorta *source maps*, `test/` y docs no esenciales — **conservando** `docs/reference/templates`
(sin ellas el agente falla con *"Missing workspace template"*).

> Tras regenerar el rootfs: **sube `ROOTFS_SCHEMA`** en `RootfsInstaller.kt`.

---

## 11. Versionado y releases

- Se incrementa **`versionCode`** (+1) y **`versionName`** (semántico) en `app/app/build.gradle.kts`.
- Como el `applicationId` no cambia (`io.debi.tc`), cada build **se instala encima** de la anterior
  **sin desinstalar** (misma firma).
- El APK final se renombra a `Linux-TC-<versionName>-<buildType>.apk`.
- Si supera el límite de Telegram (50 MB), se envía por **gofile.io**.

---

## 12. Solución de problemas

| Síntoma | Causa / solución |
|---|---|
| `Temporary failure resolving …` (apt/red) | Falta permiso `INTERNET` o el `resolv.conf`. |
| El gateway se apaga al salir | Activa **Permitir 2º plano** (Ajustes → OpenClaw). |
| Faltan binarios tras actualizar | Sube **`ROOTFS_SCHEMA`** y reinstala. |
| `Missing workspace template: AGENTS.md` | Las plantillas se copian en cada arranque; usa **Reparar (doctor)**. |
| `WorkspaceVanishedError` | `oc-prepare` reconstruye el workspace; ejecuta **Reparar (doctor)**. |
| `htop`/`top` sin datos de CPU | Android bloquea `/proc/stat`; se activa el **puente /proc**. |
| La app se cierra en 2º plano (Xiaomi/Samsung/Huawei) | Concede la exención de batería y protege la app. |
| APK no instala (arquitectura) | El dispositivo no es **arm64-v8a** → no soportado. |

---

## 13. Distribución

Google Play **restringe apps que descargan y ejecutan código** (Device & Network Abuse) → Termux
fue retirada. Rutas recomendadas: **F-Droid**, **APK directo / GitHub Releases**, **Amazon
Appstore**; Play Store con riesgo de rechazo/retirada.

> ⚠️ Licencia: no forkear Termux/UserLAnd tal cual (GPLv3). Linux-TC usa su **propio** PTY nativo
> (`ptyexec.c`, Apache-2.0) y el binario proot del upstream.

---

*Documento generado para el proyecto Linux-TC — Debian Linux en Android (proot) + OpenClaw. Creador: Andres MAG.*
