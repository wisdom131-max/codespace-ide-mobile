package com.termux.terminal;

import android.util.Log;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {
    public static void logError(TerminalSessionClient client, String tag, String message) {
        if (client != null) client.logError(tag, message); else Log.e(tag, message);
    }
    public static void logWarn(TerminalSessionClient client, String tag, String message) {
        if (client != null) client.logWarn(tag, message); else Log.w(tag, message);
    }
    public static void logInfo(TerminalSessionClient client, String tag, String message) {
        if (client != null) client.logInfo(tag, message); else Log.i(tag, message);
    }
    public static void logDebug(TerminalSessionClient client, String tag, String message) {
        if (client != null) client.logDebug(tag, message); else Log.d(tag, message);
    }
    public static void logVerbose(TerminalSessionClient client, String tag, String message) {
        if (client != null) client.logVerbose(tag, message); else Log.v(tag, message);
    }
    public static void logStackTraceWithMessage(TerminalSessionClient client, String tag, String message, Throwable t) {
        logError(client, tag, message + ":\n" + getStackTraceString(t));
    }
    public static String getStackTraceString(Throwable t) {
        if (t == null) return null;
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        } catch (Exception e) { return null; }
    }
}
