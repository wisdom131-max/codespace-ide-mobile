package com.codespace.ide.data

import android.content.Context
import java.io.File

/**
 * The restore crash-loop guard.
 *
 * THE PROBLEM IT SOLVES (owner report 2026-11-06): a file that crashed the app was reopened by the
 * restore path on the next launch, so every start crashed again. The owner had to delete the file
 * from the phone's Files app to get the editor back. A competitor that behaves the same way was
 * reported as never recovering.
 *
 * THE RULE: if the previous launch ended badly (crash, ANR, native crash, low-memory kill) or a
 * crash happened WHILE restoring, the files that were open are not reopened automatically. The
 * project and the other tabs still come back, and the user gets one line and a Reopen action.
 * Two consecutive bad starts skip every restored file until a clean start happens.
 */
object RestoreGuard {

    /** What to do with one saved tab path. */
    data class Decision(val restore: Boolean, val note: String?)

    /** Why a path was skipped, for the one-line note. */
    enum class SkipReason { FILE_GONE, PREVIOUS_EXIT_BAD, CRASH_DURING_RESTORE, REPEATED_BAD_STARTS }

    /**
     * The whole decision table, pure so it can be pinned by tests.
     *
     * @param fileExists the saved path still exists on disk
     * @param previousExitBad the last process ended in crash / ANR / native crash / low memory
     * @param crashDuringRestore the persisted marker says a restore started and never finished
     * @param consecutiveBadStarts how many launches in a row ended badly (>=2 means stop trying)
     */
    fun decide(
        path: String,
        fileExists: Boolean,
        previousExitBad: Boolean,
        crashDuringRestore: Boolean,
        consecutiveBadStarts: Int,
    ): Decision {
        if (!fileExists) return Decision(false, null) // nothing to say: the file is simply gone
        val name = path.substringAfterLast('/')
        return when {
            consecutiveBadStarts >= 2 -> Decision(
                false,
                "VN Code keeps closing unexpectedly; $name was not reopened. Open it when you are ready.",
            )
            crashDuringRestore -> Decision(
                false,
                "VN Code closed while restoring $name; it was not reopened.",
            )
            previousExitBad -> Decision(
                false,
                "VN Code closed unexpectedly while $name was open; it was not reopened.",
            )
            else -> Decision(true, null)
        }
    }

    /** True when the exit reason means "the app did not finish properly". */
    fun isBadExitReason(reasonRecognised: Boolean, recognisedBad: Boolean): Boolean {
        // Only the caller knows the platform constant mapping; this keeps the set explicit and
        // testable: an unrecognised reason is NOT treated as bad (a normal user swipe-away is not
        // an accident we should punish by hiding their files).
        return reasonRecognised && recognisedBad
    }
}

/**
 * Persisted state for [RestoreGuard]: the restore-in-progress marker, the last clean start, and the
 * consecutive bad start counter.
 *
 * The marker is written BEFORE restoring and cleared only after the first successful frame. If the
 * process dies during restore, the marker is still there on the next launch, which is exactly how
 * a crash during restore is detected even when the crash happens inside layout.
 */
class RestoreGuardStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("restore_guard", Context.MODE_PRIVATE)

    fun beginRestore() {
        prefs.edit()
            .putBoolean(KEY_IN_PROGRESS, true)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Called after the first successful frame: the restore survived, so nothing was risky. */
    fun endRestore(cleanStart: Boolean = true) {
        val bad = if (cleanStart) 0 else prefs.getInt(KEY_BAD_STARTS, 0) + 1
        prefs.edit()
            .putBoolean(KEY_IN_PROGRESS, false)
            .putInt(KEY_BAD_STARTS, bad)
            .putLong(KEY_LAST_CLEAN_START, System.currentTimeMillis())
            .apply()
    }

    /** The marker was never cleared: the previous launch died during restore. */
    fun restoreWasInProgress(): Boolean = prefs.getBoolean(KEY_IN_PROGRESS, false)

    fun consecutiveBadStarts(): Int = prefs.getInt(KEY_BAD_STARTS, 0)

    /** A crash log written after the last clean start means the previous launch crashed. */
    fun crashedSinceLastCleanStart(filesDir: File): Boolean {
        val since = prefs.getLong(KEY_LAST_CLEAN_START, 0L)
        val dir = File(filesDir, "crash_logs")
        val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: return false
        return newest > since
    }

    /** Counts one bad launch (crash/ANR/native/low-memory) for the repeated-crash rule. */
    fun recordBadStart() {
        prefs.edit().putInt(KEY_BAD_STARTS, prefs.getInt(KEY_BAD_STARTS, 0) + 1).apply()
    }

    private companion object {
        const val KEY_IN_PROGRESS = "restore_in_progress"
        const val KEY_STARTED_AT = "restore_started_at"
        const val KEY_BAD_STARTS = "consecutive_bad_starts"
        const val KEY_LAST_CLEAN_START = "last_clean_start"
    }
}
