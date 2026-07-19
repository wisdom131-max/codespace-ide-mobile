package com.codespace.ide.diagnostics

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.withMutableSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared, app-wide "Output" channel — mirrors VS Code's Output panel, which
 * shows real log lines from whatever the editor/extensions are doing (build,
 * git, language server, etc.) rather than a static placeholder.
 *
 * Any part of the app can call AppOutputLog.log(...) and the Output panel
 * (OutputPanel in ProjectShellScreen.kt) reflects it live, since it's backed
 * by a Compose SnapshotStateList.
 *
 * Capped at 500 lines to avoid unbounded memory growth on long sessions —
 * important on the 3GB target device.
 */
object AppOutputLog {

    private const val MAX_LINES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val lines = mutableStateListOf(
        "[info]  Visual Node Code started",
    )

    @Synchronized
    fun log(message: String, channel: String = "info") {
        val ts = timeFmt.format(Date())
        // P31-CRASH-FIX: Wrap add+trim in a single snapshot so Compose never sees
        // an intermediate state (size 501 before removeAt(0) brings it back to 500).
        // Without this, LazyColumn's prefetcher reads size=501, tries to access
        // index 500, but by then removeAt(0) already ran — IndexOutOfBoundsException.
        withMutableSnapshot {
            lines.add("[$ts] [$channel]  $message")
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun clear() {
        lines.clear()
        lines.add("[info]  Output cleared")
    }

    /**
     * P25-1: Internal-only log channel.
     * Lines written here are stored in [internalLines] but NOT shown in the Output panel UI.
     * Used for proot startup noise, which is noise in end-user panels but valuable for
     * debugging sessions that need to inspect the raw proot environment output.
     * Capped at 200 lines (noise is high-volume; we don't need a full history).
     */
    private const val MAX_INTERNAL_LINES = 200
    val internalLines = mutableStateListOf<String>()

    @Synchronized
    fun logInternal(message: String, channel: String = "internal") {
        val ts = timeFmt.format(Date())
        withMutableSnapshot {
            internalLines.add("[$ts] [$channel]  $message")
            while (internalLines.size > MAX_INTERNAL_LINES) internalLines.removeAt(0)
        }
    }
}
