package com.termux.terminal;

public final class JNI {
    static { System.loadLibrary("ptynative"); }
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int cols, int cellWidthPixels, int cellHeightPixels);
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidthPixels, int cellHeightPixels);
    public static native int waitFor(int pid);
    public static native void close(int fd);
    public static native void installCrashHandler(String crashFilePath);
}
