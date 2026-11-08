package com.codespace.ide.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why the PREVIOUS process died, read from the platform instead of inferred from a crash log.
 *
 * THE GAP THIS CLOSES (owner/advisor order 2026-11-08, item 1): the restore guard could only learn
 * about a bad exit from a crash log written after the last clean start, or from the persisted
 * restore marker. A death AFTER the first successful frame that never wrote a log left no evidence:
 * an ANR killed by the system, a low-memory kill, a native crash in a JNI library, and a crash
 * during initialization all looked exactly like a clean exit, so the next launch cheerfully reopened
 * the file that was on screen when the app died - the loop this whole guard exists to break.
 *
 * ExitInfo is available from API 30; on older devices this reader returns null and the guard falls
 * back to the crash-log path, which is unchanged.
 *
 * Not a service, not an observer: one read at the moment the guard asks, during startup, of a list
 * the system already keeps.
 */
internal object ExitReasonClassifier {

    /** The exit kinds this app distinguishes, in its own vocabulary so tests need no Android types. */
    enum class ExitKind {
        /** Killed for not responding. */
        ANR,

        /** Java/Kotlin exception crash. */
        CRASH,

        /** Native crash in a JNI library. */
        CRASH_NATIVE,

        /** The system reclaimed the process for memory. */
        LOW_MEMORY,

        /** Killed by a signal (often the watchdog or the kernel). */
        SIGNALED,

        /** The process died before it finished initializing. */
        INITIALIZATION_FAILURE,

        /** The system killed it for excessive resource use. */
        EXCESSIVE_RESOURCE_USAGE,

        /** The user swiped the app away or the task was removed: NOT an accident. */
        USER_REQUESTED,

        /** The app exited itself (the normal "back out of the app" path): NOT an accident. */
        EXIT_SELF,

        /** Anything else, or unknown. */
        OTHER,
    }

    /**
     * Which kinds mean "the app did not finish properly, so do not reopen the file that was open".
     *
     * Deliberately NOT bad:
     *  - USER_REQUESTED (force-stop / swipe-away): the user chose to end it. Treating a deliberate
     *    exit as a crash would hide their files after every normal swipe-away, which is worse than
     *    the bug this guard fixes.
     *  - EXIT_SELF: the app closed itself on purpose.
     *  - OTHER / unknown: an unrecognised reason is not evidence of an accident. The crash-log path
     *    still catches real crashes that happen to be labelled OTHER.
     */
    fun isBad(kind: ExitKind): Boolean = when (kind) {
        ExitKind.ANR,
        ExitKind.CRASH,
        ExitKind.CRASH_NATIVE,
        ExitKind.LOW_MEMORY,
        ExitKind.SIGNALED,
        ExitKind.INITIALIZATION_FAILURE,
        ExitKind.EXCESSIVE_RESOURCE_USAGE -> true

        ExitKind.USER_REQUESTED,
        ExitKind.EXIT_SELF,
        ExitKind.OTHER -> false
    }

    /** Maps a platform reason constant to our vocabulary. Pure, so the mapping is testable. */
    fun fromPlatformReason(reason: Int): ExitKind = when (reason) {
        ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
        ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.CRASH_NATIVE
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
        ApplicationExitInfo.REASON_SIGNALED -> ExitKind.SIGNALED
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitKind.INITIALIZATION_FAILURE
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitKind.EXCESSIVE_RESOURCE_USAGE
        ApplicationExitInfo.REASON_USER_REQUESTED -> ExitKind.USER_REQUESTED
        ApplicationExitInfo.REASON_EXIT_SELF -> ExitKind.EXIT_SELF
        else -> ExitKind.OTHER
    }

    /** The sentence shown next to a skipped file, so the note names the real cause. */
    fun describe(kind: ExitKind): String = when (kind) {
        ExitKind.ANR -> "stopped responding"
        ExitKind.CRASH -> "crashed"
        ExitKind.CRASH_NATIVE -> "crashed in a native library"
        ExitKind.LOW_MEMORY -> "was closed because the phone ran out of memory"
        ExitKind.SIGNALED -> "was killed by the system"
        ExitKind.INITIALIZATION_FAILURE -> "failed to finish starting"
        ExitKind.EXCESSIVE_RESOURCE_USAGE -> "was closed for using too much memory"
        ExitKind.USER_REQUESTED -> "was closed by you"
        ExitKind.EXIT_SELF -> "exited"
        ExitKind.OTHER -> "closed"
    }
}

/** Reads the previous process exit reason once. Returns null when the platform cannot say. */
internal class ExitReasonReader(private val context: Context) {

    /**
     * The most recent PREVIOUS exit of this app, or null.
     *
     * The list the system keeps does not contain the running process, only finished ones, so the
     * newest entry is the launch that ended just before this one.
     */
    fun lastExitKind(): ExitReasonClassifier.ExitKind? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return null
            ExitReasonClassifier.fromPlatformReason(info.reason)
        } catch (_: Throwable) {
            // Reading exit history can be refused by the platform; the crash-log path still works.
            null
        }
    }

    /** True when the previous launch ended in a way that means "do not reopen that file". */
    fun previousExitWasBad(): Boolean = lastExitKind()?.let { ExitReasonClassifier.isBad(it) } ?: false
}
