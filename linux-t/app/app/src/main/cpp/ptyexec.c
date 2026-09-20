/*
 * ptyexec.c — PTY + fork/exec nativo para Android (io.debi).
 * Licencia: Apache-2.0 (proyecto propio; inspirado en técnicas conocidas de PTY en Android).
 *
 * Expone a Kotlin (io.debi.Pty) la creación de un proceso con terminal (pty maestro)
 * y el I/O + resize + waitpid sobre él.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <android/log.h>

#define TAG "ptyexec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static char *jstr_dup(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (!c) return NULL;
    char *d = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return d;
}

static char **jarr_dup(JNIEnv *env, jobjectArray arr, int *outCount) {
    *outCount = 0;
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc(n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = jstr_dup(env, js);
        (*env)->DeleteLocalRef(env, js);
    }
    out[n] = NULL;
    *outCount = n;
    return out;
}

static void free_arr(char **a, int n) {
    if (!a) return;
    for (int i = 0; i < n; i++) free(a[i]);
    free(a);
}

/*
 * Crea el pty, hace fork y en el hijo execve(cmd, argv, envp).
 * Devuelve el fd maestro (>=0) y escribe el pid en pidOut[0]. -1 en error.
 */
JNIEXPORT jint JNICALL
Java_io_debi_Pty_create(JNIEnv *env, jclass clazz,
                        jstring jcmd, jobjectArray jargs, jobjectArray jenv,
                        jstring jcwd, jint rows, jint cols, jintArray jpidOut) {
    char *cmd = jstr_dup(env, jcmd);
    char *cwd = jstr_dup(env, jcwd);
    int argc = 0, envc = 0;
    char **args = jarr_dup(env, jargs, &argc);
    char **envp = jarr_dup(env, jenv, &envc);

    if (!cmd) { free_arr(args, argc); free_arr(envp, envc); return -1; }

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) { LOGE("open /dev/ptmx: %s", strerror(errno)); goto fail; }
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno)); close(ptm); goto fail;
    }
    char devname[128];
    if (ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        LOGE("ptsname_r: %s", strerror(errno)); close(ptm); goto fail;
    }

    pid_t pid = fork();
    if (pid < 0) { LOGE("fork: %s", strerror(errno)); close(ptm); goto fail; }

    if (pid == 0) {
        /* Hijo */
        close(ptm);
        setsid();
        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0); dup2(pts, 1); dup2(pts, 2);
        if (pts > 2) close(pts);
        struct winsize ws;
        ws.ws_row = (unsigned short) rows;
        ws.ws_col = (unsigned short) cols;
        ws.ws_xpixel = 0; ws.ws_ypixel = 0;
        ioctl(0, TIOCSWINSZ, &ws);

        if (cwd) { if (chdir(cwd) != 0) { /* seguimos */ } }

        /* argv: argv[0] = cmd por defecto (las args vienen ya completas desde Kotlin) */
        if (args && argc > 0) {
            execve(cmd, args, envp ? envp : environ);
        } else {
            char *a[2]; a[0] = cmd; a[1] = NULL;
            execve(cmd, a, envp ? envp : environ);
        }
        _exit(127);
    }

    /* Padre */
    if (jpidOut) {
        jint p = (jint) pid;
        (*env)->SetIntArrayRegion(env, jpidOut, 0, 1, &p);
    }
    free(cmd); free(cwd); free_arr(args, argc); free_arr(envp, envc);
    return ptm;

fail:
    free(cmd); free(cwd); free_arr(args, argc); free_arr(envp, envc);
    return -1;
}

JNIEXPORT jint JNICALL
Java_io_debi_Pty_read(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    jbyte *tmp = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t r;
    do { r = read(fd, tmp, (size_t) len); } while (r < 0 && errno == EINTR);
    if (r > 0) (*env)->SetByteArrayRegion(env, buf, 0, (jsize) r, tmp);
    (*env)->ReleaseByteArrayElements(env, buf, tmp, 0);
    if (r < 0) return -1;
    return (jint) r;
}

JNIEXPORT jint JNICALL
Java_io_debi_Pty_write(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    jbyte *tmp = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t w;
    do { w = write(fd, tmp, (size_t) len); } while (w < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buf, tmp, JNI_ABORT);
    if (w < 0) return -1;
    return (jint) w;
}

JNIEXPORT void JNICALL
Java_io_debi_Pty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0; ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_io_debi_Pty_close(JNIEnv *env, jclass clazz, jint fd) {
    return (jint) close(fd);
}

JNIEXPORT jint JNICALL
Java_io_debi_Pty_waitpid(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    waitpid((pid_t) pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_io_debi_Pty_kill(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    return (jint) kill((pid_t) pid, sig);
}
