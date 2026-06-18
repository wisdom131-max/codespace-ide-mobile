#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdio.h>

JNIEXPORT jint JNICALL
Java_com_codespace_ide_terminal_NativePty_createSubprocess(
    JNIEnv *env, jclass clazz,
    jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
    jintArray processIdArray, jint rows, jint cols)
{
    int procFd;
    pid_t pid = forkpty(&procFd, NULL, NULL, NULL);
    if (pid == 0) {
        const char *cmdStr = (*env)->GetStringUTFChars(env, cmd, NULL);
        const char *cwdStr = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (chdir(cwdStr) != 0) chdir("/data/local/tmp");
        int argc = (*env)->GetArrayLength(env, args);
        char **argv = malloc((argc + 1) * sizeof(char *));
        for (int i = 0; i < argc; i++) {
            jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            argv[i] = strdup((*env)->GetStringUTFChars(env, arg, NULL));
        }
        argv[argc] = NULL;
        int envc = (*env)->GetArrayLength(env, envVars);
        for (int i = 0; i < envc; i++) {
            jstring envVar = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            putenv(strdup((*env)->GetStringUTFChars(env, envVar, NULL)));
        }
        execvp(cmdStr, argv);
        _exit(1);
    } else if (pid > 0) {
        struct winsize sz = { .ws_row = rows, .ws_col = cols };
        ioctl(procFd, TIOCSWINSZ, &sz);
        jint *pidArr = (*env)->GetIntArrayElements(env, processIdArray, NULL);
        pidArr[0] = pid;
        (*env)->ReleaseIntArrayElements(env, processIdArray, pidArr, 0);
        return procFd;
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_codespace_ide_terminal_NativePty_setWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols)
{
    struct winsize sz = { .ws_row = rows, .ws_col = cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_codespace_ide_terminal_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    return WEXITSTATUS(status);
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
    JNIEnv *env, jclass clazz,
    jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
    jintArray processIdArray, jint rows, jint cols, jint cellWidthPixels, jint cellHeightPixels)
{
    int procFd;
    pid_t pid = forkpty(&procFd, NULL, NULL, NULL);
    if (pid == 0) {
        const char *cmdStr = (*env)->GetStringUTFChars(env, cmd, NULL);
        const char *cwdStr = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (chdir(cwdStr) != 0) chdir("/data/local/tmp");
        int argc = (*env)->GetArrayLength(env, args);
        char **argv = malloc((argc + 1) * sizeof(char *));
        for (int i = 0; i < argc; i++) {
            jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            argv[i] = strdup((*env)->GetStringUTFChars(env, arg, NULL));
        }
        argv[argc] = NULL;
        int envc = (*env)->GetArrayLength(env, envVars);
        for (int i = 0; i < envc; i++) {
            jstring envVar = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            putenv(strdup((*env)->GetStringUTFChars(env, envVar, NULL)));
        }
        execvp(cmdStr, argv);
        char *fallback[] = { "/system/bin/sh", "-i", NULL };
        execvp("/system/bin/sh", fallback);
        _exit(1);
    } else if (pid > 0) {
        struct winsize sz = { .ws_row = rows, .ws_col = cols };
        ioctl(procFd, TIOCSWINSZ, &sz);
        jint *pidArr = (*env)->GetIntArrayElements(env, processIdArray, NULL);
        pidArr[0] = pid;
        (*env)->ReleaseIntArrayElements(env, processIdArray, pidArr, 0);
        return procFd;
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols, jint cellWidthPixels, jint cellHeightPixels)
{
    struct winsize sz = { .ws_row = rows, .ws_col = cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv *env, jclass clazz, jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    return WEXITSTATUS(status);
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv *env, jclass clazz, jint fd)
{
    close(fd);
}
