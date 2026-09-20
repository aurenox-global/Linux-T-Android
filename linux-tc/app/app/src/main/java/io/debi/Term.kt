package io.debi

import android.content.Context
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gestor de sesiones Debian (proot). Cada pestaña = una sesión con su propio
 * proceso bash. Vive en un singleton para sobrevivir a la Activity: si la app
 * pasa a segundo plano, las sesiones siguen corriendo (TermProcessService).
 *
 * La sesión activa entrega su salida en directo a la UI; las inactivas guardan
 * su salida en un buffer circular y la vuelcan al volver a esa pestaña.
 *
 * IMPORTANTE: cambiar de pestaña NUNCA cierra las demás; solo cambia a quién se
 * entrega la salida. Las sesiones solo se cierran explícitamente (× / stopAll).
 */
object Term {

    class Session(val id: Int) {
        @Volatile var fd = -1
        @Volatile var pid = -1
        @Volatile var alive = true
        var reader: Thread? = null
        val ring = ArrayDeque<ByteArray>()
        var ringBytes = 0
        @Volatile var listener: ((ByteArray) -> Unit)? = null
    }

    private const val MAX_RING = 512 * 1024
    const val MAX_SESSIONS = 10

    private val lock = Any()
    private val initLock = Any()
    private var installedOnce = false

    @Volatile private var shimBinds: List<String> = emptyList()
    private const val SHIM_SOURCES = "/proc/stat,/proc/uptime,/proc/loadavg"

    private val sessions = ArrayList<Session>()
    private val idGen = AtomicInteger(1)

    @Volatile private var activeId = -1

    /** Sesión dedicada al gateway de OpenClaw (en segundo plano). -1 = ninguna. */
    @Volatile var gatewaySessionId: Int = -1

    /** El usuario quiere el gateway activo (para relanzarlo si se cae). */
    @Volatile var gatewayEnabled = false

    /** Contexto de aplicación (para poder relanzar el gateway desde el watchdog). */
    @Volatile private var appCtx: Context? = null

    /** Avisa al servicio (wake lock) cuando cambia el nº de sesiones activas. */
    @Volatile var onSessionCountChanged: ((Int) -> Unit)? = null

    private fun notifySessionCount() {
        val cb = onSessionCountChanged ?: return
        val n = synchronized(lock) { sessions.size }
        try { cb(n) } catch (_: Throwable) { }
    }

    /* ------------------------- consultas ------------------------- */

    fun sessionIds(): List<Int> = synchronized(lock) { sessions.map { it.id } }
    fun activeSessionId(): Int = activeId
    val running: Boolean get() = synchronized(lock) { sessions.isNotEmpty() }

    private fun session(id: Int): Session? = synchronized(lock) { sessions.find { it.id == id } }

    /** ¿Sigue viva la sesión del gateway? */
    fun gatewayAlive(): Boolean {
        val id = gatewaySessionId
        if (id <= 0) return false
        val s = session(id) ?: return false
        return s.alive && s.fd >= 0
    }

    /* ------------------------- ciclo de vida ------------------------- */

    private fun ensureInstalled(ctx: Context, log: (String) -> Unit) {
        synchronized(initLock) {
            if (!installedOnce) {
                RootfsInstaller.install(ctx) { log(it) }
                shimBinds = buildProcShim(ctx) { log(it) }
                installedOnce = true
            }
        }
    }

    /**
     * Android niega /proc/stat (y /proc/uptime, /proc/loadavg) a las apps, así que htop/top
     * no pueden leerlo dentro del guest. Si la PROPIA app sí puede leerlo, servimos una
     * copia refrescada cada segundo y la bindeamos como /proc/stat (etc.) dentro de proot.
     * Devuelve las especificaciones de bind ("origen:destino").
     */
    private fun buildProcShim(ctx: Context, log: (String) -> Unit): List<String> {
        val dir = File(ctx.filesDir, "procshim")
        val sources = SHIM_SOURCES.split(",")
        val binds = ArrayList<String>()
        val readable = ArrayList<String>()
        for (src in sources) {
            val ok = try { File(src).inputStream().use { it.read() }; true } catch (_: Throwable) { false }
            if (ok) {
                readable.add(src)
                binds.add("${File(dir, File(src).name).absolutePath}:$src")
            } else {
                log("[diag] la app NO puede leer $src\r\n")
            }
        }
        if (readable.isEmpty()) return binds
        dir.mkdirs()
        log("[diag] puente /proc activo (${readable.size} fichero/s) para htop\r\n")
        Thread {
            while (true) {
                for (src in readable) {
                    try {
                        val bytes = File(src).readBytes()
                        val name = File(src).name
                        val tmp = File(dir, "$name.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(File(dir, name))) File(dir, name).writeBytes(bytes)
                    } catch (_: Throwable) { }
                }
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
        return binds
    }

    /** Argumentos de proot para ejecutar `guest` (lista de argv dentro del guest). */
    private fun prootArgv(ctx: Context, guest: List<String>): ArrayList<String> {
        val files = ctx.filesDir
        val proot = File(files, "usr/bin/proot").absolutePath
        val rootfs = File(files, "rootfs").absolutePath

        val args = ArrayList<String>()
        args += proot
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "-0"
        args += "-r"; args += rootfs
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        for (b in shimBinds) { args += "-b"; args += b }
        args += "-b"; args += "/sys"
        args += "-b"; args += "$files/etc/resolv.conf:/etc/resolv.conf"
        val storage = try { Environment.getExternalStorageDirectory()?.absolutePath } catch (_: Throwable) { null }
        if (storage != null && File(storage).exists()) {
            args += "-b"; args += "$storage:/sdcard"
            args += "-b"; args += "$storage:/root/storage"
        }
        args += "-w"; args += "/root"
        args += "/usr/bin/env"; args += "-i"
        args += "HOME=/root"
        args += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        args += "TERM=xterm-256color"
        args += "LANG=C.UTF-8"
        args += "LC_ALL=C.UTF-8"
        args += guest
        return args
    }

    private fun prootEnv(ctx: Context): Array<String> {
        val files = ctx.filesDir
        val loader = File(files, "usr/libexec/proot/loader").absolutePath
        File(files, "tmp").mkdirs()
        return arrayOf(
            "PROOT_LOADER=$loader",
            "PROOT_TMP_DIR=$files/tmp",
            "PROOT_NO_SECCOMP=1",
            "HOME=$files",
            "PATH=/system/bin:/system/xbin:/vendor/bin:/usr/bin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "TMPDIR=$files/tmp"
        )
    }

    /** Abre una nueva sesión Debian. Devuelve su id (>=1) o -1 si falló. */
    /**
     * Abre una sesión. Si `command` no es null, en vez de un shell interactivo la
     * sesión ejecuta ese comando (`bash -lc command`) — se usa para el gateway.
     */
    fun openSession(ctx: Context, rows: Int, cols: Int,
                    command: String? = null, log: (String) -> Unit): Int {
        synchronized(lock) { if (sessions.size >= MAX_SESSIONS) return -1 }
        appCtx = ctx.applicationContext
        ensureInstalled(ctx, log)

        val files = ctx.filesDir
        val proot = File(files, "usr/bin/proot").absolutePath
        val guest = if (command.isNullOrBlank()) listOf("/bin/bash", "-l")
                    else listOf("/bin/bash", "-lc", command)
        val args = prootArgv(ctx, guest)
        val env = prootEnv(ctx)

        val pidOut = IntArray(1)
        val f = try {
            Pty.create(proot, args.toTypedArray(), env, files.absolutePath, rows, cols, pidOut)
        } catch (t: Throwable) {
            log("\r\n[error] ${t.javaClass.simpleName}: ${t.message}\r\n")
            return -1
        }
        if (f < 0) {
            log("\r\n[error] no se pudo iniciar proot (fd<0)\r\n")
            return -1
        }

        val id = synchronized(lock) {
            val s = Session(idGen.getAndIncrement())
            s.fd = f
            s.pid = pidOut[0]
            sessions.add(s)
            s.reader = Thread { pump(s) }.also { it.isDaemon = true; it.start() }
            s.id
        }
        notifySessionCount()
        return id
    }

    /**
     * Ejecuta un comando puntual dentro del guest y devuelve su salida (stdout+stderr).
     * No crea pestaña. Para chequeos rápidos (versiones, estado...).
     */
    fun runCommand(ctx: Context, command: String, timeoutMs: Long = 180_000): String {
        try { ensureInstalled(ctx) {} } catch (_: Throwable) { }
        val files = ctx.filesDir
        val argv = prootArgv(ctx, listOf("/bin/bash", "-lc", command))
        val env = prootEnv(ctx)
        val out = StringBuilder()
        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(files)
            val e = pb.environment()
            e.clear()
            for (kv in env) {
                val i = kv.indexOf('=')
                if (i > 0) e[kv.substring(0, i)] = kv.substring(i + 1)
            }
            val p = pb.start()
            val slurp = { ins: java.io.InputStream ->
                try { ins.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } }
                catch (_: Throwable) { }
            }
            val t1 = Thread { slurp(p.inputStream) }.also { it.isDaemon = true; it.start() }
            val t2 = Thread { slurp(p.errorStream) }.also { it.isDaemon = true; it.start() }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) { try { p.destroyForcibly() } catch (_: Throwable) { } }
            try { t1.join(500) } catch (_: Throwable) { }
            try { t2.join(500) } catch (_: Throwable) { }
            out.toString()
        } catch (t: Throwable) {
            "[error] ${t.javaClass.simpleName}: ${t.message}\n"
        }
    }

    /* ------------------------- gateway OpenClaw ------------------------- */

    /** Arranca el gateway en una sesión propia (sobrevive en segundo plano). */
    fun startGateway(ctx: Context, rows: Int, cols: Int, log: (String) -> Unit): Boolean {
        if (gatewayAlive()) return true
        // Antes de arrancar: ASEGURA el workspace del agente y pasa `doctor --fix`
        // (cur" WorkspaceVanishedError" / "workspace is missing" sin depender de la app).
        val cmd =
            "/usr/local/bin/oc-prepare 2>&1; " +
            "echo '== arrancando gateway =='; exec openclaw gateway 2>&1"
        val id = openSession(ctx, rows, cols, command = cmd, log = log)
        if (id <= 0) return false
        gatewaySessionId = id
        gatewayEnabled = true
        // No robamos el foco: dejamos la pestaña activa donde estaba.
        return true
    }

    /** Detiene el gateway (cierra su sesión). */
    fun stopGateway() {
        gatewayEnabled = false
        val id = gatewaySessionId
        gatewaySessionId = -1
        if (id > 0) closeSession(id)
    }

    private fun pump(s: Session) {
        val buf = ByteArray(8192)
        while (true) {
            val f = s.fd
            if (f < 0) break
            val n = try { Pty.read(f, buf, buf.size) } catch (_: Throwable) { -1 }
            if (n <= 0) break
            push(s, buf.copyOf(n))
        }
        push(s, "\r\n[proceso terminado]\r\n".toByteArray())
        synchronized(lock) {
            try { Pty.close(s.fd) } catch (_: Throwable) { }
            s.fd = -1
            s.pid = -1
            s.alive = false
            if (s.id == gatewaySessionId) {
                gatewaySessionId = -1
                // Watchdog: si el gateway estaba activado y su proceso murió, relánzalo.
                val ctx = appCtx
                if (gatewayEnabled && ctx != null) {
                    Thread {
                        try { Thread.sleep(5000) } catch (_: Throwable) { }
                        if (gatewayEnabled && !gatewayAlive()) {
                            try { startGateway(ctx, 24, 80) { } } catch (_: Throwable) { }
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }
    }

    private fun push(s: Session, b: ByteArray) {
        val cb: ((ByteArray) -> Unit)?
        synchronized(lock) {
            val l = s.listener
            if (l == null) {
                s.ring.addLast(b)
                s.ringBytes += b.size
                while (s.ringBytes > MAX_RING && s.ring.isNotEmpty()) s.ringBytes -= s.ring.removeFirst().size
                cb = null
            } else {
                cb = l
            }
        }
        cb?.invoke(b)
    }

    /**
     * Activa una sesión, engancha la UI a ella y devuelve su salida acumulada.
     * Las demás sesiones pasan a acumular en su buffer.
     */
    fun attach(id: Int, cb: (ByteArray) -> Unit): ByteArray {
        synchronized(lock) {
            val s = sessions.find { it.id == id } ?: return ByteArray(0)
            for (x in sessions) if (x.id != id) x.listener = null
            val out = ByteArrayOutputStream()
            for (b in s.ring) out.write(b)
            s.ring.clear()
            s.ringBytes = 0
            s.listener = cb
            activeId = id
            return out.toByteArray()
        }
    }

    fun detach() {
        synchronized(lock) { for (x in sessions) x.listener = null }
    }

    fun write(bytes: ByteArray) {
        val s = session(activeId) ?: return
        val f = s.fd
        if (f >= 0) { try { Pty.write(f, bytes, bytes.size) } catch (_: Throwable) { } }
    }

    fun resize(rows: Int, cols: Int) {
        val s = session(activeId) ?: return
        val f = s.fd
        if (f >= 0) { try { Pty.resize(f, rows, cols) } catch (_: Throwable) { } }
    }

    /** Cierra una sesión. Devuelve el id de la nueva sesión activa (-1 si ya no queda). */
    fun closeSession(id: Int): Int {
        val newActive = synchronized(lock) {
            val s = sessions.find { it.id == id } ?: return activeId
            s.listener = null
            try { if (s.pid > 0) Pty.kill(s.pid, 15) } catch (_: Throwable) { }
            try { if (s.fd >= 0) Pty.close(s.fd) } catch (_: Throwable) { }
            sessions.remove(s)
            if (id == gatewaySessionId) gatewaySessionId = -1
            if (activeId == id) activeId = sessions.firstOrNull()?.id ?: -1
            activeId
        }
        notifySessionCount()
        return newActive
    }

    /** Termina todas las sesiones (botón "Detener" de la notificación). */
    fun stopAll() {
        synchronized(lock) {
            for (s in sessions) {
                s.listener = null
                try { if (s.pid > 0) Pty.kill(s.pid, 15) } catch (_: Throwable) { }
                try { if (s.fd >= 0) Pty.close(s.fd) } catch (_: Throwable) { }
            }
            sessions.clear()
            gatewaySessionId = -1
            activeId = -1
        }
        notifySessionCount()
    }
}
