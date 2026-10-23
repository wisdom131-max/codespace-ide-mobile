package com.codespace.ide.terminal

import android.app.PendingIntent
import android.content.Intent

/**
 * C12-U6-b item 2 (advisor 2026-10-23): the notification CONTENT intent.
 * READ before writing: TerminalService.buildNotification had NO
 * setContentIntent at all — tapping the "VN Code" notification did
 * nothing. This is the launch contract for the fix, kept as pure
 * constants so a JVM unit test can pin it (framework Intent and
 * PendingIntent objects cannot be built in a plain JVM test).
 *
 * FLAG CHOICES (verified against MainActivity + manifest READs):
 * - ACTION_MAIN + CATEGORY_LAUNCHER + explicit MainActivity component =
 *   LAUNCHER-STYLE intent. The system resolves it like a home-screen
 *   launch: an EXISTING task is brought to the front AS-IS, no second
 *   instance of MainActivity is stacked on top, and the live activity
 *   keeps its state. MainActivity is exported=true with NO launchMode
 *   override in the manifest (standard) — a plain component intent with
 *   only NEW_TASK would stack a SECOND instance on the live task,
 *   so the launcher-style shape is load-bearing here, not decoration.
 * - FLAG_ACTIVITY_NEW_TASK: REQUIRED — the PendingIntent fires from a
 *   service context outside any activity task.
 * - FLAG_ACTIVITY_SINGLE_TOP: with the launcher-style intent, a tap
 *   while the activity is alive and on top delivers onNewIntent()
 *   instead of recreating; MainActivity does not override onNewIntent
 *   (READ-verified), so the default no-op runs and the activity simply
 *   resumes with state intact.
 * - Deliberately NOT set: FLAG_ACTIVITY_CLEAR_TOP (would DESTROY and
 *   recreate the live activity), FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
 *   (no clearTaskOnLaunch in the manifest, nothing to reset),
 *   FLAG_ACTIVITY_CLEAR_TASK (would wipe the back stack).
 * - PendingIntent FLAG_IMMUTABLE + FLAG_UPDATE_CURRENT: immutable is
 *   the modern requirement (a mutable app-launch intent is a security
 *   smell); UPDATE_CURRENT reuses the single PendingIntent across
 *   notification rebuilds instead of stacking duplicates.
 * - Swiped-from-Recents with the process alive: no task exists, so
 *   NEW_TASK starts it and the activity is CREATED (then restored from
 *   persistence — editor tabs, per-tab CWD); recreation after a swipe is
 *   unavoidable by design, this only guarantees the tap always opens
 *   the app, never no-ops, never stacks.
 */
object NotificationLaunch {
    const val INTENT_ACTION = Intent.ACTION_MAIN
    val INTENT_CATEGORIES = listOf(Intent.CATEGORY_LAUNCHER)
    const val INTENT_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    const val PI_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private const val REQUEST_CODE = 3 // 0/1 are the WakeLock toggle's

    fun contentPendingIntent(service: TerminalService): PendingIntent {
        val intent = Intent(service, com.codespace.ide.MainActivity::class.java).apply {
            action = INTENT_ACTION
            INTENT_CATEGORIES.forEach { addCategory(it) }
            flags = INTENT_FLAGS
        }
        return PendingIntent.getActivity(service, REQUEST_CODE, intent, PI_FLAGS)
    }
}
