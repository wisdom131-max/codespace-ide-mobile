#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

// ── Native crash handler ──────────────────────────────────────────────────
// Added 2026-07-03: a JVM-level Thread.UncaughtExceptionHandler (see
// CodeSpaceApplication.installCrashLogger()) does NOT catch native signal
// crashes (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) -- those are handled by the
// kernel/Bionic's own debuggerd path and never reach Java at all. This is
// exactly the class of crash this app has hit before ("signal 11" in the
// Ubuntu terminal) and was suspected again when a JVM crash log never showed
// up for a real device crash. This installs our own sigaction for the common
// crash signals, writes minimal info to a fixed file path using ONLY
// async-signal-safe calls (write/open/close -- no malloc, no snprintf-heavy
// locking), then re-raises with the default handler restored so Android's
// normal tombstone/debuggerd path still runs unmodified.
#include <sys/syscall.h>
#include <time.h>

static char g_crash_path[512] = {0};

static void write_uint(int fd, unsigned long v) {
    char buf[24];
    int i = sizeof(buf);
    buf[--i] = '\0';
    if (v == 0) { buf[--i] = '0'; }
    while (v > 0 && i > 0) { buf[--i] = (char)('0' + (v % 10)); v /= 10; }
    write(fd, buf + i, strlen(buf + i));
}

static void write_hex(int fd, unsigned long v) {
    static const char hex[] = "0123456789abcdef";
    char buf[20];
    int i = sizeof(buf);
    buf[--i] = '\0';
    if (v == 0) { buf[--i] = '0'; }
    while (v > 0 && i > 0) { buf[--i] = hex[v & 0xf]; v >>= 4; }
    write(fd, "0x", 2);
    write(fd, buf + i, strlen(buf + i));
}

#define WRITE_LIT(fd, lit) write((fd), (lit), sizeof(lit) - 1)

static void native_crash_handler(int sig, siginfo_t* info, void* ucontext) {
    (void) ucontext;
    if (g_crash_path[0] != '\0') {
        int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            WRITE_LIT(fd, "=== NATIVE CRASH (signal, no Java stack trace available) ===\n");
            WRITE_LIT(fd, "signal=");
            write_uint(fd, (unsigned long) sig);
            WRITE_LIT(fd, " code=");
            write_uint(fd, (unsigned long) (info ? info->si_code : -1));
            WRITE_LIT(fd, " pid=");
            write_uint(fd, (unsigned long) getpid());
            WRITE_LIT(fd, " tid=");
            write_uint(fd, (unsigned long) syscall(SYS_gettid));
            WRITE_LIT(fd, " fault_addr=");
            write_hex(fd, (unsigned long) (info ? (unsigned long) info->si_addr : 0));
            WRITE_LIT(fd, "\n");
            close(fd);
        }
    }
    // Restore default handler and re-raise -- lets debuggerd still produce a
    // real tombstone exactly as before; we're only adding a side-channel record.
    signal(sig, SIG_DFL);
    raise(sig);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_installCrashHandler(JNIEnv* env, jclass clazz, jstring path)
{
    if (!path) return;  // NULL GUARD: null jstring crashes CheckJNI
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        strncpy(g_crash_path, cpath, sizeof(g_crash_path) - 1);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}
// ── End native crash handler ──────────────────────────────────────────────

// Copied verbatim from Termux terminal-emulator/src/main/jni/termux.c
// Replaces the old forkpty()-based implementation which had Android signal
// handling bugs, no setsid(), no FD cleanup, and no IUTF8/flow-control fix.

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control (prevents Ctrl+S from locking display)
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    struct winsize sz = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Child process:
        // Unblock all signals — Android's Java process blocks many of them
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid(); // New session — child becomes process group leader with controlling terminal

        int pts = open(devname, O_RDWR);
        if (pts < 0) exit(-1);

        dup2(pts, 0); // stdin
        dup2(pts, 1); // stdout
        dup2(pts, 2); // stderr

        // Close all extra file descriptors
        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        // Clear inherited environment and set only what we pass in
        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        if (chdir(cwd) != 0) {
            char* error_message;
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }

        execvp(cmd, argv);

        // execvp failed — print error to terminal so user sees it
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

// ── NativePty JNI (used by bash tab) ─────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_codespace_ide_terminal_NativePty_createSubprocess(
    JNIEnv* env, jclass clazz,
    jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
    jintArray processIdArray, jint rows, jint cols)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) malloc((size + 1) * sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "malloc() for argv failed");
        for (int i = 0; i < size; ++i) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            if (!s) { argv[i] = strdup(""); continue; }  // null guard
            char const* u = (*env)->GetStringUTFChars(env, s, NULL);
            if (!u) { argv[i] = strdup(""); continue; }
            argv[i] = strdup(u);
            (*env)->ReleaseStringUTFChars(env, s, u);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) malloc((size + 1) * sizeof(char*));
        if (!envp) return throw_runtime_exception(env, "malloc() for envp failed");
        for (int i = 0; i < size; ++i) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            if (!s) { envp[i] = strdup(""); continue; }  // null guard: skip null env entries
            char const* u = (*env)->GetStringUTFChars(env, s, NULL);
            if (!u) { envp[i] = strdup(""); continue; }
            envp[i] = strdup(u);
            (*env)->ReleaseStringUTFChars(env, s, u);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    // NULL GUARD (2026-07-03): same fix as Termux JNI — null jstring crashes CheckJNI
    const char* cmd_utf8 = NULL;
    if (cmd) {
        cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
        if (!cmd_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for cmd");
    }
    const char* cwd_utf8 = NULL;
    if (cwd) {
        cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (!cwd_utf8) { if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8); return throw_runtime_exception(env, "GetStringUTFChars() failed for cwd"); }
    }
    const char* effective_cmd = cmd_utf8 ? cmd_utf8 : "/system/bin/sh";
    const char* effective_cwd = cwd_utf8 ? cwd_utf8 : "/";
    int ptm = create_subprocess(env, effective_cmd, effective_cwd, argv, envp, &procId, rows, cols, 0, 0);
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);

    if (argv) { for (char** t = argv; *t; ++t) free(*t); free(argv); }
    if (envp) { for (char** t = envp; *t; ++t) free(*t); free(envp); }

    if (ptm < 0) return ptm;  // exception already thrown by create_subprocess
    int* pProcId = (int*)(*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "GetPrimitiveArrayCritical failed");
    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);
    return ptm;
}

JNIEXPORT void JNICALL
Java_com_codespace_ide_terminal_NativePty_setWindowSize(
    JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols)
{
    struct winsize sz = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_codespace_ide_terminal_NativePty_waitFor(JNIEnv* env, jclass clazz, jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

// ── Termux JNI (used by vendored TerminalSession.java) ───────────────────────

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass clazz,
        jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
        jintArray processIdArray, jint rows, jint columns, jint cell_width, jint cell_height)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) malloc((size + 1) * sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            if (!arg_java_string) { argv[i] = strdup(""); continue; }  // null guard: prevents SIGSEGV crash
            char const* arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) malloc((size + 1) * sizeof(char*));
        if (!envp) return throw_runtime_exception(env, "malloc() for envp array failed");
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            if (!env_java_string) { envp[i] = strdup(""); continue; }  // null guard: skip null env entries
            char const* env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, NULL);
            if (!env_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for env");
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    // NULL GUARD (2026-07-03): If cwd or cmd jstring is null, GetStringUTFChars()
    // crashes inside CheckJNI with SIGSEGV (fault addr 0x3c) BEFORE returning NULL.
    // Root cause of the crash loop: 16 SIGKILLs in 90s, process uptime 1s.
    const char* cwd_utf8 = NULL;
    if (cwd) {
        cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (!cwd_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for cwd");
    }
    const char* cmd_utf8 = NULL;
    if (cmd) {
        cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
        if (!cmd_utf8) { if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8); return throw_runtime_exception(env, "GetStringUTFChars() failed for cmd"); }
    }
    const char* effective_cmd = cmd_utf8 ? cmd_utf8 : "/system/bin/sh";
    const char* effective_cwd = cwd_utf8 ? cwd_utf8 : "/";
    int ptm = create_subprocess(env, effective_cmd, effective_cwd, argv, envp, &procId, rows, columns, cell_width, cell_height);
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);

    if (argv) { for (char** tmp = argv; *tmp; ++tmp) free(*tmp); free(argv); }
    if (envp) { for (char** tmp = envp; *tmp; ++tmp) free(*tmp); free(envp); }

    if (ptm < 0) return ptm;  // exception already thrown by create_subprocess
    int* pProcId = (int*)(*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical failed");
    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);
    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = {
        .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols,
        .ws_xpixel = (unsigned short)(cols * cell_width), .ws_ypixel = (unsigned short)(rows * cell_height)
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* env, jclass clazz, jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* env, jclass clazz, jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* env, jclass clazz, jint fileDescriptor)
{
    close(fileDescriptor);
}

