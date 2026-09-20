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
    private const val ROOTFS_SCHEMA = 3

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
