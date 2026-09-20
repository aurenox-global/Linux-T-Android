package io.debi

/**
 * Puente JNI con libptyexec.so: crea un proceso con PTY y expone I/O.
 */
object Pty {
    init {
        System.loadLibrary("ptyexec")
    }

    /** Devuelve el fd maestro del pty (>=0) o -1. Escribe el pid en pidOut[0]. */
    external fun create(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        pidOut: IntArray
    ): Int

    external fun read(fd: Int, buf: ByteArray, len: Int): Int
    external fun write(fd: Int, buf: ByteArray, len: Int): Int
    external fun resize(fd: Int, rows: Int, cols: Int)
    external fun close(fd: Int): Int
    external fun waitpid(pid: Int): Int
    external fun kill(pid: Int, sig: Int): Int
}
