# Linux-TNube Pro — Documentación técnica

**Debian Linux completo dentro de Android, sin root.**

- **Creador:** Andres MAG
- **Paquete:** `io.tnube` · **namespace:** `io.debi`
- **Versión actual:** `1.0.3` (versionCode `4`)
- **Plataforma:** Android 9+ (API 28) · **arm64-v8a** (aarch64) · mínimo ~4 GB de RAM

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
14. [Changelog](#14-changelog)
15. [APIs de IA y router de modelos](#15-apis-de-ia-y-router-de-modelos)

---

## 1. Qué es

**Linux-TNube Pro** es una app Android que ejecuta un **sistema Debian completo** (filesystem +
terminal interactivo) empaquetado dentro del propio APK. No necesita root, ni conexión a
internet para funcionar (todo viene incluido), ni pasos manuales de instalación.

El objetivo es **distribuir al público** un Linux real en el móvil de forma sencilla:
abres la app y tienes una shell Debian lista, con herramientas preinstaladas y **OpenClaw +
Node.js** ya dentro.

### Por qué proot y no chroot

Android **sin root bloquea `chroot` y los namespaces** (SELinux + restricciones del kernel).
La solución estándar del sector (Termux → `proot-distro`) es **PRoot**:

- PRoot intercepta las syscalls (`ptrace` + `seccomp`) y **emula un rootfs**.
- Los binarios se ejecutan **nativos ARM64** (no hay emulación tipo QEMU) → velocidad real.
- No requiere root.

---

## 2. Características

- 🐧 **Debian bookworm arm64** completo empaquetado en el APK (una sola extracción la primera vez).
- 🖥️ **Terminal interactivo** con xterm.js + PTY nativo (no es un "wrapper" de comandos).
- 🗂️ **Pestañas (secciones)** independientes: cada una es una sesión bash viva. Se pueden
  **anclar** como favoritas (no se cierran por error).
- 🎨 **6 temas** de terminal: Clásico, Dracula, Solarized, Gruvbox, Nord y Papel.
- 🧩 **Perfiles de paquetes**: Básico y Dev (se **verifican/actualizan** desde Ajustes).
- 📊 **Monitor** de memoria, almacenamiento, carga y núcleos.
- 🦞 **OpenClaw integrado** (agente IA) con Node.js v24 preinstalado y arranque del gateway
  en segundo plano con watchdog (se relanza solo si se cae).
- 📱 **Segundo plano real**: servicio en primer plano + wake/wifi lock (las sesiones y el
  gateway siguen vivos al salir de la app).
- 📋 **Copiar logs del terminal** al portapapeles con un botón.
- 🔌 Acceso a **/sdcard** y **/root/storage** desde el terminal (almacenamiento compartido).

---

## 3. Requisitos

### Del dispositivo

| Requisito | Valor |
|---|---|
| Android | 9 (API 28) o superior |
| Arquitectura | **arm64-v8a (aarch64)** obligatorio |
| RAM | 4 GB recomendado mínimo |
| Almacenamiento | ~1 – 1.5 GB libres (extracción del rootfs) |

> Los equipos **arm32-only** quedan fuera: Debian moderno no tiene usuario arm32 viable.

### Del host de compilación

- JDK 17
- Android SDK (build-tools, platform 35, NDK `26.3.11579264`, CMake 3.22.1)
- Gradle 8.13 (wrapper en `~/.gradle/wrapper/dists`), AGP 8.12.0, Kotlin 2.1.21
- `local.properties` con `sdk.dir=<ruta Android SDK>`

Para **regenerar el rootfs** además: `bash`, `curl`, `tar`, `xz`, `docker` y `npm` moderno.

---

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│                       Android (app io.tnube)                │
│                                                              │
│  MainActivity (Activity)                                     │
│   └── WebView  ── file:///android_asset/web/index.html       │
│         · xterm.js (render terminal)                         │
│         · puente JS ↔ Kotlin:  window.Android.*              │
│                                                              │
│  Bridge (JavascriptInterface)                                │
│   · ready / newTab / selectTab / closeTab                    │
│   · send / resize / runLine / copyToClipboard                │
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

Android 10+ **bloquea el `exec` de binarios** alojados en el directorio privado de la app
cuando `targetSdk >= 29`. PRoot necesita ejecutar binarios desde
`/data/data/io.tnube/files/…`. Por eso `targetSdk = 28` (mismo truco que Termux).
Se mantiene `compileSdk = 35` para compilar contra SDKs modernos.

---

## 5. Estructura del proyecto

```
Linux-TNube Pro/
├── README.md                 # resumen del proyecto
├── DOCUMENTACION.md          # este documento
├── scripts/
│   ├── build-rootfs.sh           # rootfs Debian base (desde imagen Docker oficial)
│   ├── build-rootfs-openclaw.sh  # rootfs base + Node v24 + OpenClaw
│   ├── make-icon.py              # genera iconos (squircle rojo OpenClaw)
│   └── patch-paths.py            # reescribe rutas en binarios proot (Termux→io.tnube)
├── dist/                     # artefactos del rootfs (.tar, .tar.xz, meta)
└── app/                      # proyecto Android (Gradle/Kotlin)
    ├── build.gradle.kts          # AGP + Kotlin
    ├── settings.gradle.kts       # módulo :app
    ├── gradle.properties         # JVM, paralelismo, AndroidX
    ├── local.properties          # sdk.dir
    └── app/
        ├── build.gradle.kts      # config Android + versionCode/versionName
        └── src/main/
            ├── AndroidManifest.xml
            ├── cpp/              # ptyexec.c + CMakeLists.txt (PTY nativo)
            ├── jniLibs/arm64-v8a/ # libtalloc.so, libandroid-shmem.so
            ├── java/io/debi/
            │   ├── MainActivity.kt        # WebView + puente Android
            │   ├── Term.kt                # gestor de sesiones proot
            │   ├── RootfsInstaller.kt     # extracción/instalación del runtime
            │   ├── TermProcessService.kt  # servicio en primer plano
            │   └── Pty.kt                 # puente JNI
            ├── res/              # iconos mipmap, drawables
            └── assets/
                ├── web/          # index.html, xterm.js, addon-fit.js, xterm.css
                ├── proot/        # bin/proot, libexec/proot/loader(32), lib/*.so
                ├── rootfs/       # debian-bookworm-arm64.tar.xz
                ├── oc-templates/ # plantillas del workspace de OpenClaw
                ├── oc-config/    # openclaw.json por defecto
                └── oc-bin/       # oc-prepare.sh
```

---

## 6. Cómo funciona internamente

### 6.1 Instalación del runtime (una sola vez)

`RootfsInstaller.install()` se ejecuta en el primer arranque:

1. **Siempre** copia/refresca `assets/proot/` → `filesDir/usr/` (proot + loaders + libs)
   y les da permisos de ejecución. Así las actualizaciones de la app sustituyen binarios
   viejos de proot sin re-extraer todo el rootfs.
2. Genera `filesDir/etc/resolv.conf` con los **DNS reales del sistema Android** (fallback
   `1.1.1.1` / `8.8.8.8`).
3. **Si el esquema cambió** (`ROOTFS_SCHEMA`), borra el rootfs anterior y extrae
   `assets/rootfs/debian-bookworm-arm64.tar.xz` (con `commons-compress` + `xz`).
   La extracción resuelve **symlinks y hardlinks** (con fallback a copia, porque algunos
   hardlinks fallan y rompían binarios como `/usr/bin/perl`).
4. Escribe el marcador `filesDir/.installed` con el número de esquema.
5. Copia las **plantillas del workspace** de OpenClaw y la **config por defecto**, y
   refresca el script `oc-prepare`.

> **Importante:** al cambiar el rootfs o el extractor hay que **subir `ROOTFS_SCHEMA`** en
> `RootfsInstaller.kt` para forzar la re-extracción en dispositivos que ya lo tenían.

### 6.2 Sesiones (Term.kt)

- Cada **pestaña = una sesión** con su propio proceso `bash` dentro de proot.
- Máximo **10 sesiones simultáneas** (`MAX_SESSIONS`).
- La sesión **activa** entrega su salida en directo a la UI. Las **inactivas** acumulan en
  un **buffer circular de 512 KB** que se vuelca al volver a esa pestaña.
- Cambiar de pestaña **nunca** cierra las demás (solo cambia a quién se entrega la salida).
- El singleton vive fuera de la Activity → las sesiones **sobreviven** a que la app pase a
  segundo plano.

### 6.3 Puente /proc (htop/top)

Android niega `/proc/stat`, `/proc/uptime` y `/proc/loadavg` a las apps, así que `htop`/`top`
no podrían leerlos dentro del guest. Si la propia app **sí** puede leerlos, se sirve una copia
refrescada cada segundo y se **bindean** como `/proc/stat` etc. dentro de proot.

### 6.4 PTY nativo (ptyexec.c)

- `Pty.create()` abre `/dev/ptmx`, hace `fork()`, y en el hijo: `setsid`, `TIOCSCTTY`,
  `dup2` de stdin/stdout/stderr al pts y `TIOCSWINSZ` (tamaño de terminal).
- Expone `read`/`write`/`resize`/`close`/`waitpid`/`kill` a Kotlin.
- Resultado: un **terminal real** (no un pipe), con colores, control de job, `Ctrl+C`, etc.

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

`TermProcessService` mantiene vivo el proceso Debian en segundo plano

- **Notificación persistente** "Debian en ejecución" con botón **Detener** (mata todas las
  sesiones y para el servicio).
- **Wake lock** (CPU despierta) + **wifi lock** (red activa) → el gateway de OpenClaw puede
  responder aunque la pantalla esté apagada.
- Los locks se cogen **solo cuando hay sesiones activas** (ahorro de batería).
- `onTaskRemoved`: no se para al quitar la app de recientes; refuerza el foreground.

---

## 7. Interfaz de usuario

La UI es una **WebView** que carga `assets/web/index.html` (xterm.js + `addon-fit`).

### Barra superior

- **Selector de secciones** + botones: ☆ (anclar favorita), ＋ (nueva), ✕ (cerrar).
- Herramientas: **🦞 OpenClaw**, **📊 Monitor**, **⚙ Ajustes**.

### Barra de teclas extra

`Esc` · `Tab` · `Ctrl` · flechas ↑↓←→ · `|` · `/` · `-` · `⌫ línea` (borra la línea actual).

### Ajustes (⚙)

- **Tema del terminal:** 6 temas (Clásico, Dracula, Solarized, Gruvbox, Nord, Papel).
- **Perfiles de instalación:** **Básico** y **Dev**. En Linux-TNube Pro estos paquetes ya vienen
  instalados, así que los botones dicen **«Actualizar Básico»** / **«Actualizar Dev»** y solo
  verifican/actualizan los paquetes en la pestaña actual.
  - *Básico:* git, curl, wget, nano, less, python3, python3-pip, python3-venv, unzip, zip, tree, ca-certificates.
  - *Dev:* git, curl, wget, vim, tmux, python3, python3-pip, python3-venv, build-essential, cmake, pkg-config, gdb, nodejs, npm, sqlite3.
- **Terminal:** botón **📋 Copiar logs del terminal** → vuelca todo el contenido del terminal
  (incluido el scrollback) al portapapeles del sistema.
- **Acerca de:** `Linux-TNube Pro` — Debian Linux en Android (proot) · **Creador: Andres MAG**.

### Monitor (📊)

Memoria usada/total, almacenamiento, carga (1/5/15) y núcleos. (Android bloquea `/proc/stat`
a las apps, así que no hay % de CPU ni uptime.)

### Puente JS ↔ Android

Se expone como `window.Android`:

| Método | Función |
|---|---|
| `ready()` | La UI está lista → abre/activa la sección |
| `newTab()` / `selectTab(id)` / `closeTab(id)` | Gestión de pestañas |
| `send(b64)` / `resize(c,r)` / `runLine(line)` | E/S del terminal |
| `copyToClipboard(text)` | Copia texto al portapapeles del sistema |
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

- **Dependencias:** muestra versiones de Node.js, npm y OpenClaw (✅/❌).
- **API key del proveedor (DeepSeek):** se guarda y arranca el gateway.
- **Bot de Telegram (opcional):** guarda el token y reinicia el gateway.
- **Segundo plano:** petición de exención de optimización de batería (sin esto Android suele
  matar el gateway al salir de la app).
- **Gateway:** interruptor para arrancar/parar. Se ejecuta en **su propia pestaña** (anclada
  automáticamente) y sigue vivo en segundo plano.
- **Reparar (doctor):** ejecuta `oc-prepare` + `openclaw doctor --fix`.

### `oc-prepare`

Script en `/usr/local/bin/oc-prepare` (assets/oc-bin). Garantiza el workspace real de la
config y, solo si hace falta, ejecuta `openclaw doctor --fix`. Evita errores como
`Missing workspace template` o `WorkspaceVanishedError`.

### Watchdog del gateway

Si el gateway estaba activado y su proceso muere, `Term.pump()` lo **relanza solo** tras 5 s
(mientras el usuario lo tenga activo).

---

## 9. Compilación

### Requisitos en el host

- Android SDK en `~/Android/Sdk` (ajusta `app/local.properties`).
- JDK 17.
- NDK `26.3.11579264`, CMake `3.22.1` (se bajan vía SDK Manager si faltan).

### Comando de build (debug)

```bash
cd app
export ANDROID_HOME=~/Android/Sdk
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle)
$GRADLE :app:assembleDebug --console=plain
```

Salida (se renombra automáticamente):

```
app/app/build/outputs/apk/debug/Linux-TNube Pro-<versionName>-debug.apk
```

### Configuración clave (`app/app/build.gradle.kts`)

| Campo | Valor |
|---|---|
| `applicationId` | `io.tnube` |
| `namespace` | `io.debi` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 28 / 35 |
| `abiFilters` | `arm64-v8a` |
| `versionCode` / `versionName` | 4 / `1.0.3` |
| Dependencias | `commons-compress:1.21`, `xz:1.9` |
| Firma (release) | config `debug` (⚠️ pendiente keystore propio) |

> El APK **release** está firmado con la clave *debug* a propósito (para pruebas).
> Para publicar hay que crear un **keystore propio** y configurar `signingConfigs.release`.

---

## 10. Generación del rootfs

### Rootfs base (`scripts/build-rootfs.sh`)

Exporta la imagen oficial `arm64v8/debian:bookworm` con Docker (evita `debootstrap`+`qemu`):

```bash
scripts/build-rootfs.sh
# → dist/debian-bookworm-arm64.tar(.xz) + dist/rootfs-meta.json (sha256)
```

### Rootfs con OpenClaw (`scripts/build-rootfs-openclaw.sh`)

Parte del rootfs base, añade **Node v24 (arm64)** y **OpenClaw** (`--os=linux --cpu=arm64`,
sin emulación) y genera el asset que se empaqueta:

```bash
scripts/build-rootfs-openclaw.sh            # última versión
scripts/build-rootfs-openclaw.sh 2026.9.4   # versión concreta
# → app/app/src/main/assets/rootfs/debian-bookworm-arm64.tar.xz
```

Recorta *source maps*, `test/` y docs no esenciales — **conservando** `docs/reference/templates`
(sin ellas el agente falla con *"Missing workspace template"*).

> Tras regenerar el rootfs: **sube `ROOTFS_SCHEMA`** en `RootfsInstaller.kt`.

### Iconos (`scripts/make-icon.py`)

Genera los mipmaps (legacy, foreground adaptativo y roundIcon) en rojo OpenClaw
(degradado `#DE3C3C → #C83232 → #B92A2A`).

### `patch-paths.py`

Reescribe strings de rutas en binarios de Termux para adaptarlos a `io.tnube`
(`/data/data/com.termux/files/usr` → `/data/data/io.tnube/files/usr`), sin desplazar
secciones ELF.

---

## 11. Versionado y releases

- Se incrementa **`versionCode`** (entero, siempre +1) y **`versionName`** (semántico) en
  `app/app/build.gradle.kts`.
- Como el `applicationId` no cambia (`io.tnube`), cada build **se instala encima** de la
  anterior **sin desinstalar** (misma firma).
- El APK final se renombra a `Linux-TNube Pro-<versionName>-<buildType>.apk` automáticamente.

### Reparto de builds

Los APK pesan ~260–280 MB → **exceden el límite de Telegram (50 MB)**. Para enviarlos se usa
**gofile.io** (ver `~/.openclaw/workspace/gofile.sh`), que devuelve un enlace de descarga.

---

## 12. Solución de problemas

| Síntoma | Causa / solución |
|---|---|
| `Temporary failure resolving …` (apt/red) | Falta permiso `INTERNET` o el `resolv.conf`. Comprueba DNS en Ajustes/terminal. |
| El gateway se apaga al salir | Activa **Permitir 2º plano** (Ajustes → OpenClaw). |
| Faltan binarios tras actualizar (p.ej. `perl`) | Sube **`ROOTFS_SCHEMA`** y reinstala para forzar re-extracción limpia. |
| `Missing workspace template: AGENTS.md` | Las plantillas se copian desde assets en cada arranque; si persiste, usa **Reparar (doctor)**. |
| `WorkspaceVanishedError` | `oc-prepare` reconstruye el workspace; ejecuta **Reparar (doctor)**. |
| `htop`/`top` sin datos de CPU | Android bloquea `/proc/stat`; se activa el **puente /proc** si la app puede leerlo. |
| La app se cierra en 2º plano (Xiaomi/Samsung/Huawei) | Concede la exención de batería y protege la app en recientes. |
| APK no instala (arquitectura) | El dispositivo no es **arm64-v8a** → no soportado. |

---

## 13. Distribución

Google Play **restringe apps que descargan y ejecutan código** (Device & Network Abuse) →
Termux fue retirada. Rutas recomendadas:

- **F-Droid** (ideal si el proyecto es open source).
- **APK directo / GitHub Releases**.
- **Amazon Appstore**.
- Play Store: posible, pero con riesgo de rechazo/retirada.

> ⚠️ Licencia: no se recomienda forkear Termux/UserLAnd tal cual (GPLv3 obliga a liberar todo
> el código). Linux-TNube Pro usa su **propio** PTY nativo (`ptyexec.c`, Apache-2.0) y el binario
> proot del upstream.

---

## 14. Changelog

### 1.1.0 — versionCode 6
- 🆕 **Ajustes → APIs de IA**: interruptores por proveedor (activar/desactivar), pega de API key
  y elección del proveedor **principal**. Incluye ~14 proveedores, la mayoría con plan gratuito
  (Groq, Cerebras, Gemini, SambaNova, OpenRouter, Mistral, Z.ai, Qwen, GitHub Models, Hugging Face,
  Together, NVIDIA NIM, Cohere, Chutes).
- 🆕 **Router inteligente (estilo OpenRouter)**: un interruptor que decide el modelo según la
  petición (visión / código / contexto largo / rápido) y salta de proveedor si uno falla o se agota.
- 🆕 **Medidor de uso**: peticiones y tokens por proveedor y día, más consulta de cuota oficial
  del proveedor.
- Renombrado a **Linux-TNube Pro** (label y nombre del proyecto/APK).

### 1.0.3 — versionCode 4
- Los botones de perfiles pasan de «Instalar …» a **«Actualizar Básico»** / **«Actualizar Dev»**
  (todas las dependencias ya vienen instaladas).

### 1.0.2 — versionCode 3
- **Botón 📋 Copiar logs del terminal** en Ajustes (portapapeles del sistema).
- Sección **Acerca de** con el creador: **Andres MAG**.
- Nueva numeración de versión.

### 1.0.1 — versionCode 2
- Primera versión con **OpenClaw + Node.js** preinstalados en el rootfs.
- UI de terminal (xterm.js + PTY), pestañas, temas, perfiles, monitor y panel OpenClaw.

---

*Documento generado para el proyecto Linux-TNube Pro — Debian Linux en Android (proot).*

---

## 15. APIs de IA y router de modelos

### 15.1 Qué es

En **Ajustes → APIs de IA** puedes conectar la app a varias APIs de modelos (la mayoría con plan
**gratuito**) y elegir con qué proveedor trabaja el agente OpenClaw.

- **Interruptor por proveedor**: activa solo los que quieras. Cada uno guarda su **API key**
  (se escribe en `openclaw.json` y en la config del router).
- **Principal**: marca cuál se usa cuando el router está apagado.
- **Router inteligente** (estilo OpenRouter): cuando está encendido, un pequeño router local
  decide el modelo en cada petición y salta al siguiente proveedor si el elegido falla o se agota.
- **Medidor de uso**: peticiones y tokens por proveedor y día + consulta de cuota oficial.

### 15.2 Arquitectura

```
Ajustes (WebView, index.html)
   │  Android.apiCatalog() / apiState() / apiApply() / apiQuota()
   ▼
MainActivity.Bridge (Kotlin)
   ├─ escribe  rootfs/root/.openclaw/openclaw.json      (proveedores + modelo principal/fallbacks)
   ├─ escribe  rootfs/root/.tnube-router.json           (config del router)
   ├─ copia    rootfs/usr/local/bin/tnube-router(.mjs)  (script del router)
   └─ arranca/para el router y reinicia el gateway
                                    │
OpenClaw ──(provider tnube-router, http://127.0.0.1:8790/v1)──▶ tnube-router.mjs
                                                                   │ clasifica la petición
                                                                   ▼
                                          Groq / Cerebras / Gemini / OpenRouter / …
```

- El router es un servidor **Node sin dependencias** (`assets/oc-bin/tnube-router.mjs`) que expone
  una API **compatible con OpenAI** en `http://127.0.0.1:8790/v1`. OpenClaw lo ve como un proveedor
  más (`tnube-router`, modelo `auto`).
- Al **clasificar** la petición asigna un rol:
  - **vision** — el mensaje lleva imagen.
  - **code** — hay bloques de código o palabras de programación.
  - **long** — el texto pasa de ~12 000 caracteres (contextos enormes).
  - **fast** — el resto (charla, dudas, resúmenes).
  Cada rol tiene una lista ordenada de proveedores (`roles` en `providers.json`).
- Si el proveedor elegido devuelve error (429/5xx/red), prueba el siguiente candidato de la lista.
- El router va en su **propia sección** (como el gateway) y se relanza solo si se cae.

### 15.3 Medidor

- **Local (siempre)**: el router anota en `~/.tnube-router-usage.json` peticiones, errores y tokens
  por proveedor y día (14 días de histórico). Ajustes lo muestra en «Medidor de uso (hoy)».
- **Oficial (a demanda)**: el botón «Consultar cuotas del proveedor» ejecuta
  `openclaw status --usage --json` y muestra la ventana de cuota que reporte cada proveedor
  (p. ej. OpenRouter, DeepSeek). Los planes gratis que no exponen cuota no aparecerán.

### 15.4 Catálogo de proveedores

El catálogo vive en `app/app/src/main/assets/oc-config/providers.json`:

- `providers[]`: `id`, `name`, `baseUrl`, `api`, `keyHint`, `signup`, `free` (nota del plan),
  `contextWindow`, `maxTokens` y `models` con el modelo por **rol** (`fast/default/code/long/vision`).
- `roles`: orden de proveedores por rol.
- `priority`: orden general de respaldo.

Para añadir un proveedor nuevo basta con una entrada en `providers[]` y, si quieres, meterlo en
`roles`/`priority`. Los IDs de modelo son valores por defecto razonables: si un proveedor cambia el
nombre, edítalo ahí.

### 15.5 Notas

- El `applicationId` sigue siendo `io.tnube` (el parcheo de rutas del binario proot está
  calculado para esa longitud); el **nombre visible** pasa a ser **Linux-TNube Pro**.
- Las claves se guardan en claro dentro del rootfs (como ya hacía DeepSeek). Es un entorno local.

