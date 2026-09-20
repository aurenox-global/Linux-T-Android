package io.debi

import android.content.Context
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gestor de sesiones Debian (proot). Cada pestaña = una sesión con su propio
 * proceso bash. Vive en un singleton para sobrevivir a la Activity: si la app
 * pasa a segundo plano, las sesiones siguen corriendo (TermProcessService).
 *
 * La sesión activa entrega su salida en directo a la UI; las inactivas guardan
 * su salida en un buffer circular y la vuelcan al volver a esa pestaña.
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

    private const val MAX_RING = 256 * 1024
    const val MAX_SESSIONS = 6

    private val lock = Any()
    private val initLock = Any()
    private var installedOnce = false

    @Volatile private var shimBinds: List<String> = emptyList()
    private const val SHIM_SOURCES = "/proc/stat,/proc/uptime,/proc/loadavg"

    private val sessions = ArrayList<Session>()
    private val idGen = AtomicInteger(1)

    @Volatile private var activeId = -1

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

    /** Abre una nueva sesión Debian. Devuelve su id (>=1) o -1 si falló. */
    fun openSession(ctx: Context, rows: Int, cols: Int, log: (String) -> Unit): Int {
        synchronized(lock) { if (sessions.size >= MAX_SESSIONS) return -1 }
        ensureInstalled(ctx, log)

        val files = ctx.filesDir
        val proot = File(files, "usr/bin/proot").absolutePath
        val rootfs = File(files, "rootfs").absolutePath
        val loader = File(files, "usr/libexec/proot/loader").absolutePath

        val args = ArrayList<String>()
        args += proot
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "-0"
        args += "-r"; args += rootfs
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        // Puente /proc (si la app puede leer /proc/stat, etc.): va DESPUÉS de -b /proc
        // para que su prioridad (prefijo más largo) gane.
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
        args += "/bin/bash"; args += "-l"

        File(files, "tmp").mkdirs()
        val env = arrayOf(
            "PROOT_LOADER=$loader",
            "PROOT_TMP_DIR=$files/tmp",
            // En algunos móviles la "aceleración" seccomp de proot provoca EACCES en
            // ficheros de /proc (p.ej. htop: /proc/stat). Desactivarla es más compatible.
            "PROOT_NO_SECCOMP=1",
            "HOME=$files",
            "PATH=/system/bin:/system/xbin:/vendor/bin:/usr/bin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "TMPDIR=$files/tmp"
        )

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
            activeId = -1
        }
        notifySessionCount()
    }
}
