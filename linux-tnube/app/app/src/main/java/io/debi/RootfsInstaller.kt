package io.debi

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Instala (una sola vez por versión de esquema) el runtime en el almacenamiento
 * privado de la app:
 *
 *   filesDir/usr/bin/proot
 *   filesDir/usr/lib/libtalloc.so, libandroid-shmem.so
 *   filesDir/usr/libexec/proot/loader{,32}
 *   filesDir/rootfs/...            <- Debian bookworm arm64
 *   filesDir/etc/resolv.conf
 *   filesDir/.installed            <- marca (contiene el nº de esquema)
 */
object RootfsInstaller {

    private const val ASSET_PROOT = "proot"
    private const val ASSET_ROOTFS = "rootfs/debian-bookworm-arm64.tar.xz"

    /** Súbela cuando cambie el rootfs o el extractor → fuerza re-extracción. */
    private const val ROOTFS_SCHEMA = 5

    fun isInstalled(ctx: Context): Boolean {
        val f = File(ctx.filesDir, ".installed")
        return f.exists() && f.readText().trim() == ROOTFS_SCHEMA.toString()
    }

    fun install(ctx: Context, log: (String) -> Unit) {
        val files = ctx.filesDir
        val usr = File(files, "usr")

        // El runtime (proot + libs) se refresca SIEMPRE: así las actualizaciones de la
        // app sustituyen binarios viejos sin re-extraer todo el rootfs.
        log("Preparando runtime (proot)...\r\n")
        copyAssetTree(ctx, ASSET_PROOT, usr)
        execPerms(File(usr, "bin/proot"))
        execPerms(File(usr, "libexec/proot/loader"))
        execPerms(File(usr, "libexec/proot/loader32"))

        log("Configurando DNS...\r\n")
        val resolv = buildResolvConf(ctx)
        File(files, "etc").mkdirs()
        File(files, "etc/resolv.conf").writeText(resolv)

        if (!isInstalled(ctx)) {
            val rootfs = File(files, "rootfs")
            // Reinstalación limpia si había un rootfs de un esquema anterior (podía
            // estar incompleto: los hardlinks fallaban → faltaba p.ej. /usr/bin/perl).
            if (rootfs.exists()) {
                log("Actualizando sistema de archivos...\r\n")
                deleteRecursively(rootfs)
            }
            rootfs.mkdirs()
            log("Extrayendo Debian (una vez, puede tardar)...\r\n")
            ctx.assets.open(ASSET_ROOTFS).use { raw ->
                XZCompressorInputStream(raw, true).use { xz ->
                    TarArchiveInputStream(xz).use { tar -> extractTar(tar, rootfs) }
                }
            }
            File(files, ".installed").writeText(ROOTFS_SCHEMA.toString())
            log("Instalación completada.\r\n")
        } else {
            log("Sistema ya extraído.\r\n")
        }

        // Carpetas para el almacenamiento compartido (se bindean a /sdcard y /root/storage).
        File(files, "rootfs/sdcard").mkdirs()
        File(files, "rootfs/root/storage").mkdirs()

        // Refresca también el resolv.conf DENTRO del rootfs: así hay DNS aunque el
        // bind de proot no llegue a aplicarse (y refleja los DNS reales del sistema).
        try {
            val guestResolv = File(files, "rootfs/etc/resolv.conf")
            if (guestResolv.parentFile?.exists() == true) guestResolv.writeText(resolv)
        } catch (_: Exception) { }

        // Plantillas del workspace de OpenClaw (AGENTS.md, SOUL.md, USER.md, …).
        // El paquete las busca en <pkg>/docs/reference/templates y el rootfs las lleva
        // recortadas por tamaño, así que las copiamos desde assets en CADA arranque
        // (son ~30 KB). Sin esto el agente falla con:
        //   "Missing workspace template: AGENTS.md (). Ensure workspace templates are packaged."
        try {
            val ocDir = File(files, "rootfs/usr/local/lib/node_modules/openclaw")
            if (ocDir.exists()) {
                copyAssetTreeRaw(ctx, "oc-templates", File(ocDir, "docs/reference/templates"))
            }
        } catch (_: Exception) { }

        // Workspace por defecto del agente ("~/.openclaw/workspace" en el guest).
        // OpenClaw NO lo crea en el camino de reply ("This agent's workspace is missing on
        // the gateway host") y si existe a medias lanza WorkspaceVanishedError
        // ("Refusing to reseed BOOTSTRAP.md over a recently attested workspace").
        // Solución: garantizar el directorio + ficheros base. BOOTSTRAP.md solo si el
        // workspace parece SIN inicializar (no hay AGENTS.md) → no re-dispararlo en uno ya montado.
        try {
            val ws = File(files, "rootfs/root/.openclaw/workspace")
            val tplDir = File(files, "rootfs/usr/local/lib/node_modules/openclaw/docs/reference/templates")
            if (tplDir.exists()) {
                ws.mkdirs()
                for (name in listOf("AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md", "IDENTITY.md", "HEARTBEAT.md", "BOOT.md")) {
                    val dst = File(ws, name)
                    if (!dst.exists()) {
                        val src = File(tplDir, name)
                        if (src.exists()) { try { src.copyTo(dst, overwrite = false) } catch (_: Exception) { } }
                    }
                }
                // BOOTSTRAP.md = "evidencia de supervivencia" que OpenClaw exige si hay una
                // atestación reciente (si no → WorkspaceVanishedError). Lo sembramos solo si
                // el workspace está SIN configurar (AGENTS.md ausente o idéntico al template),
                // para no re-disparar el onboarding en uno ya montado.
                val tplAgents = File(tplDir, "AGENTS.md")
                val curAgents = File(ws, "AGENTS.md")
                val unconfigured = !curAgents.exists() ||
                    (tplAgents.exists() && (try { curAgents.readText() == tplAgents.readText() } catch (_: Exception) { false }))
                val boot = File(ws, "BOOTSTRAP.md")
                if (unconfigured && !boot.exists()) {
                    val src = File(tplDir, "BOOTSTRAP.md")
                    if (src.exists()) { try { src.copyTo(boot, overwrite = false) } catch (_: Exception) { } }
                }
            }
        } catch (_: Exception) { }

        // Config lista de OpenClaw (como la del docker, provider DeepSeek). Si no hay
        // config, la copiamos → el usuario solo tiene que meter su API key y activar.
        try {
            val cfgFile = File(files, "rootfs/root/.openclaw/openclaw.json")
            if (!cfgFile.exists()) {
                File(files, "rootfs/root/.openclaw").mkdirs()
                try {
                    ctx.assets.open("oc-config/openclaw.json").use { ins ->
                        cfgFile.outputStream().use { ins.copyTo(it) }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Script de preparación (asegura el workspace real de la config + doctor --fix).
        // Se refresca siempre para que las actualizaciones de la app lo actualicen.
        try {
            val bin = File(files, "rootfs/usr/local/bin")
            bin.mkdirs()
            val prep = File(bin, "oc-prepare")
            ctx.assets.open("oc-bin/oc-prepare.sh").use { ins -> prep.outputStream().use { ins.copyTo(it) } }
            try { prep.setExecutable(true, false) } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    /** Copia recursiva de un asset respetando la estructura (sin recortar prefijos). */
    private fun copyAssetTreeRaw(ctx: Context, assetPath: String, destDir: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            val out = File(destDir, File(assetPath).name)
            out.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            return
        }
        destDir.mkdirs()
        for (c in children) {
            val childAsset = "$assetPath/$c"
            val sub = ctx.assets.list(childAsset) ?: emptyArray()
            if (sub.isEmpty()) {
                val out = File(destDir, c)
                out.parentFile?.mkdirs()
                ctx.assets.open(childAsset).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            } else {
                copyAssetTreeRaw(ctx, childAsset, File(destDir, c))
            }
        }
    }

    /* ------------------------- DNS ------------------------- */

    /**
     * Construye el resolv.conf con los DNS que usa el sistema Android (los del
     * operador/router). Fallback a resolvers públicos si no se pueden leer.
     */
    private fun buildResolvConf(ctx: Context): String {
        val servers = systemDnsServers(ctx).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        return servers.joinToString("\n") { "nameserver $it" } + "\n"
    }

    private fun systemDnsServers(ctx: Context): List<String> {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return emptyList()
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
            lp.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() && !it.contains(':') }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /* ------------------------- helpers ------------------------- */

    private fun deleteRecursively(f: File) {
        try {
            if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
            f.delete()
        } catch (_: Exception) { }
    }

    private fun copyAssetTree(ctx: Context, assetPath: String, destRoot: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            val out = File(destRoot, File(assetPath).name)
            out.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            return
        }
        for (c in children) {
            val childAsset = "$assetPath/$c"
            val sub = ctx.assets.list(childAsset) ?: emptyArray()
            if (sub.isEmpty()) {
                val out = File(destRoot, childAsset.removePrefix("$ASSET_PROOT/"))
                out.parentFile?.mkdirs()
                ctx.assets.open(childAsset).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            } else {
                copyAssetTree(ctx, childAsset, destRoot)
            }
        }
    }

    private fun execPerms(f: File) {
        try {
            val p = Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS).toMutableSet()
            p.add(PosixFilePermission.OWNER_EXECUTE)
            p.add(PosixFilePermission.OWNER_READ)
            p.add(PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(f.toPath(), p)
        } catch (e: Exception) {
            f.setExecutable(true, false)
            f.setReadable(true, false)
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, root: File) {
        val rootPath = root.canonicalPath
        // Hardlinks cuyo destino aún no existía: se resuelven al final.
        val deferred = ArrayList<Pair<File, String>>()
        var e: TarArchiveEntry? = tar.nextTarEntry
        while (e != null) {
            val name = e.name.trimStart('/').trimEnd('/')
            if (name.isEmpty() || name == ".") { e = tar.nextTarEntry; continue }
            val out = File(root, name)
            if (!out.canonicalPath.startsWith(rootPath)) { e = tar.nextTarEntry; continue } // zip-slip guard

            when {
                e.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    val target = e.linkName
                    try {
                        Files.deleteIfExists(out.toPath())
                        Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(target))
                    } catch (_: Exception) { }
                }
                e.isLink -> {
                    // Hardlink: si falla (o el destino no existe todavía) copiamos el
                    // contenido del fichero enlazado. Sin esto faltaban binarios como
                    // /usr/bin/perl (rompía debconf/dpkg).
                    out.parentFile?.mkdirs()
                    val target = File(root, e.linkName)
                    var ok = false
                    try {
                        Files.deleteIfExists(out.toPath())
                        Files.createLink(out.toPath(), target.toPath())
                        ok = true
                    } catch (_: Exception) { }
                    if (!ok) {
                        if (target.exists() && !target.isDirectory) {
                            try { copyFile(target, out); applyPerms(out, e.mode, false) } catch (_: Exception) { }
                        } else {
                            deferred.add(out to e.linkName)
                        }
                    }
                }
                e.isDirectory -> {
                    out.mkdirs()
                    applyPerms(out, e.mode, true)
                }
                else -> {
                    out.parentFile?.mkdirs()
                    val bos = out.outputStream()
                    copyLimited(tar, bos, e.size)
                    bos.close()
                    applyPerms(out, e.mode, false)
                }
            }
            e = tar.nextTarEntry
        }

        for ((out, linkName) in deferred) {
            val target = File(root, linkName)
            if (target.exists() && !target.isDirectory) {
                try { copyFile(target, out) } catch (_: Exception) { }
            }
        }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
    }

    private fun copyLimited(ins: InputStream, outs: OutputStream, len: Long) {
        val buf = ByteArray(1 shl 16)
        var remaining = len
        while (remaining > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) break
            outs.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun applyPerms(f: File, mode: Int, isDir: Boolean) {
        try {
            val perms = mutableSetOf<PosixFilePermission>()
            val m = if (mode == 0) (if (isDir) 0b111101101 else 0b110100100) else mode
            for (bit in 0 until 9) {
                if ((m shr bit) and 1 == 1) {
                    val p = when (bit) {
                        0 -> PosixFilePermission.OTHERS_EXECUTE
                        1 -> PosixFilePermission.OTHERS_WRITE
                        2 -> PosixFilePermission.OTHERS_READ
                        3 -> PosixFilePermission.GROUP_EXECUTE
                        4 -> PosixFilePermission.GROUP_WRITE
                        5 -> PosixFilePermission.GROUP_READ
                        6 -> PosixFilePermission.OWNER_EXECUTE
                        7 -> PosixFilePermission.OWNER_WRITE
                        else -> PosixFilePermission.OWNER_READ
                    }
                    perms.add(p)
                }
            }
            Files.setPosixFilePermissions(f.toPath(), perms)
        } catch (_: Exception) { }
    }
}
