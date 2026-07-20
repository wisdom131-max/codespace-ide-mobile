package com.codespace.ide.diagnostics

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
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
 *
 * P32-THREAD-SAFETY-FIX: All mutations to [lines] and [internalLines] are
 * wrapped in Snapshot.withMutableSnapshot { } to properly integrate with
 * Compose's snapshot system. Without this, background threads (LSP reader,
 * DAP reader, execOnce output capture, terminal startup) calling log()
 * would mutate the SnapshotStateList outside a snapshot, causing:
 *   IllegalStateException: Unsupported concurrent change during composition.
 * The @Synchronized alone only provides Java-level mutual exclusion — it
 * does NOT notify Compose's snapshot tracker that a state object is being
 * modified. withMutableSnapshot does both: serializes the mutation AND
 * records it as a snapshot state change so recomposition sees a consistent
 * state.
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
        // P32-THREAD-SAFETY-FIX: Wrap mutations in withMutableSnapshot so Compose's
        // snapshot system tracks the change. Without this, background threads
        // modifying the SnapshotStateList cause "Unsupported concurrent change
        // during composition" crashes.
        // P31-CRASH-FIX: Remove BEFORE add so the list never exceeds MAX_LINES.
        // The original bug was: add (size 501) then removeAt(0) (size 500) — between
        // those two ops, Compose's LazyColumn prefetcher could take a snapshot seeing
        // size=501 and try to access index 500, which doesn't exist after removeAt.
        // By removing first, the size goes 500→499→500 — never exceeding MAX_LINES.
        Snapshot.withMutableSnapshot {
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines.add("[$ts] [$channel]  $message")
        }
    }

    @Synchronized
    fun clear() {
        Snapshot.withMutableSnapshot {
            lines.clear()
            lines.add("[info]  Output cleared")
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

    @Synchronized
    fun logInternal(message: String, channel: String = "internal") {
        val ts = timeFmt.format(Date())
        // Same remove-before-add pattern as log() to avoid snapshot race.
        // Same withMutableSnapshot fix as log() — internalLines is also a
        // SnapshotStateList read during composition.
        Snapshot.withMutableSnapshot {
            if (internalLines.size >= MAX_INTERNAL_LINES) internalLines.removeAt(0)
            internalLines.add("[$ts] [$channel]  $message")
        }
    }
}
