package com.codespace.ide.diagnostics

import androidx.compose.runtime.mutableStateListOf
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
        lines.add("[$ts] [$channel]  $message")
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun clear() {
        lines.clear()
        lines.add("[info]  Output cleared")
    }
}
