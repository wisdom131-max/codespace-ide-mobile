package com.codespace.ide.diagnostics

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared, app-wide "Output" channel — mirrors VS Code's Output panel.
 *
 * P32-THREAD-SAFETY-FIX (v2): All mutations to [lines] and [internalLines] are
 * dispatched to the main thread via Handler.post. This is the ONLY reliable way
 * to mutate Compose state from background threads — the previous approach
 * (Snapshot.withMutableSnapshot) was racy: snapshot.apply() from a background
 * thread can conflict with the composition's snapshot read on the UI thread,
 * causing "Unsupported concurrent change during composition" even though the
 * snapshot system was "supposed" to handle it.
 *
 * The Handler.post approach is what LogcatPanel uses and it has never crashed.
 * It works because:
 * 1. All mutations execute on the main thread (serialized, can't overlap composition)
 * 2. If composition is in progress, the posted Runnable waits in the message queue
 *    until composition finishes — no race possible
 * 3. The remove-before-add pattern is preserved to avoid the P1 snapshot size race
 */
object AppOutputLog {

    private const val TAG = "AppOutputLog"
    private const val MAX_LINES = 500
    val availableChannels = listOf("info", "build", "git", "debug", "terminal", "lsp")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    val lines = mutableStateListOf(
        "[info]  Visual Node Code started",
    )

    fun log(message: String, channel: String = "info") {
        val ts = timeFmt.format(Date())
        val entry = "[$ts] [$channel]  $message"
        // If already on main thread, mutate directly (e.g. from LaunchedEffect)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines.add(entry)
        } else {
            mainHandler.post {
                if (lines.size >= MAX_LINES) lines.removeAt(0)
                lines.add(entry)
            }
        }
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lines.clear()
            lines.add("[info]  Output cleared")
        } else {
            mainHandler.post {
                lines.clear()
                lines.add("[info]  Output cleared")
            }
        }
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

    fun logInternal(message: String, channel: String = "internal") {
        val ts = timeFmt.format(Date())
        val entry = "[$ts] [$channel]  $message"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (internalLines.size >= MAX_INTERNAL_LINES) internalLines.removeAt(0)
            internalLines.add(entry)
        } else {
            mainHandler.post {
                if (internalLines.size >= MAX_INTERNAL_LINES) internalLines.removeAt(0)
                internalLines.add(entry)
            }
        }
    }

    /**
     * P44-OUTPUT: Get lines filtered by channel. Used by the Output panel's channel selector.
     * If channel is null or "all", returns all lines.
     */
    fun getLines(channel: String? = null): List<String> {
        if (channel == null || channel == "all") return lines.toList()
        return lines.filter { it.contains("[$channel]") }
    }
}
