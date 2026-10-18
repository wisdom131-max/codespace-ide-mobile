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

    /** BATCH10-P00: protected build/lifecycle history cap (never cleared by Clear). */
    private const val MAX_PROTECTED_LINES = 200
    val protectedLines = mutableStateListOf<String>()

    private fun addProtected(entry: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (protectedLines.size >= MAX_PROTECTED_LINES) protectedLines.removeAt(0)
            protectedLines.add(entry)
        } else {
            mainHandler.post {
                if (protectedLines.size >= MAX_PROTECTED_LINES) protectedLines.removeAt(0)
                protectedLines.add(entry)
            }
        }
    }

    // LOG-POST-CAP (2026-09-11): every log() call ends in a main-thread mutation (direct
    // or Handler.post). A background flood (LSP stderr, build tools, proot noise) turned
    // into a post storm that janked the UI even with zero editor tabs open. Cap total
    // entries at 80/sec; surplus is dropped and reported once per window.
    private const val MAX_ENTRIES_PER_SEC = 80
    private val capWindowStartMs = java.util.concurrent.atomic.AtomicLong(0)
    private val capWindowCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val capDropped = java.util.concurrent.atomic.AtomicLong(0)

    /** Admits this log entry; on window rollover reports the prior window's drop count. */
    private fun admit(): Boolean {
        val now = System.currentTimeMillis()
        if (now - capWindowStartMs.get() >= 1000L) {
            synchronized(this) {
                if (now - capWindowStartMs.get() >= 1000L) {
                    val dropped = capDropped.getAndSet(0)
                    if (dropped > 0) {
                        addEntry("[" + timeFmt.format(Date()) + "] [info]  [AppOutputLog] $dropped log lines suppressed in last window (cap $MAX_ENTRIES_PER_SEC/sec)")
                    }
                    capWindowCount.set(0)
                    capWindowStartMs.set(now)
                }
            }
        }
        if (capWindowCount.incrementAndGet() <= MAX_ENTRIES_PER_SEC) return true
        capDropped.incrementAndGet()
        return false
    }
    val availableChannels = listOf("info", "build", "git", "debug", "terminal", "lsp")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    val lines = mutableStateListOf(
        "[info]  Visual Node Code started",
    )

    fun log(message: String, channel: String = "info") {
        if (!admit()) return
        val ts = timeFmt.format(Date())
        // BATCH10-P00: PROTECTED build/lifecycle history — the startup build stamp
        // and every [LSP-LIFECYCLE] line are ALSO kept here, and clear() NEVER
        // touches this buffer. Answer to the advisor question: YES, Output "Clear"
        // erased these before (they lived only in the shared 500-line list); the
        // owner's later saved logs lost the build identity and the lifecycle
        // history. The protected buffer keeps the last 200 of them, and clear()
        // re-prints the build stamp so every saved log still names its build.
        if (isProtectedMessage(message)) {
            addProtected("[$ts] [$channel]  $message")
        }
        addEntry("[$ts] [$channel]  $message")
    }

    /** BATCH10-P00: messages that survive Output "Clear" (kept in [protectedLines]). */
    internal fun isProtectedMessage(message: String): Boolean {
        val m = message.trim()
        return m.startsWith("[start]") || m.contains("[LSP-LIFECYCLE]")
    }

    /** BATCH10-P00: the startup stamp line — "[start] build <hash> run <n> <time>". */
    fun buildStampLine(): String =
        "[start] build " + com.codespace.ide.BuildConfig.GIT_HASH +
            " run " + com.codespace.ide.BuildConfig.CI_RUN_NUMBER +
            " " + com.codespace.ide.BuildConfig.BUILD_TIME

    /** Shared mutation path: direct on main thread, Handler.post from background. */
    private fun addEntry(entry: String) {
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
        val doClear = {
            lines.clear()
            // BATCH10-P00: re-print the build stamp after a clear — the owner clears
            // the Output mid-session, and every SAVED log after that point must
            // still identify which APK produced it. The protected buffer holds the
            // full startup + [LSP-LIFECYCLE] history Clear erased from the panel.
            val ts = timeFmt.format(Date())
            addEntry("[$ts] [info]  Output cleared — build stamp re-printed below; " +
                protectedLines.size + " protected build/lifecycle lines kept (Settings → About)")
            addEntry("[$ts] [info]  " + buildStampLine())
        }
        if (Looper.myLooper() == Looper.getMainLooper()) doClear()
        else mainHandler.post { doClear() }
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
        if (!admit()) return
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
