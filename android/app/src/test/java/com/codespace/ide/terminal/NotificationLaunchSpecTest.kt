package com.codespace.ide.terminal

import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C12-U6-b item 2: the notification content-intent contract, pinned as a
 * pure-constants JVM test (framework Intent/PendingIntent objects cannot
 * be built in a plain JVM test — the service maps these constants to the
 * real PendingIntent in NotificationLaunch.contentPendingIntent, whose
 * shape this locks down). Every assertion here is load-bearing for the
 * "tap opens the app WITHOUT recreating or stacking" behavior:
 * launcher-style action+category and the single-top new-task flag set.
 */
class NotificationLaunchSpecTest {

    @Test fun launcherStyleIntentBringsTheTaskForward() {
        assertEquals(Intent.ACTION_MAIN, NotificationLaunch.INTENT_ACTION)
        assertEquals(listOf(Intent.CATEGORY_LAUNCHER), NotificationLaunch.INTENT_CATEGORIES)
    }

    @Test fun intentFlagsAreNewTaskPlusSingleTop() {
        val f = NotificationLaunch.INTENT_FLAGS
        assertTrue("must start a new task (service context)",
            f and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("single-top: alive activity gets onNewIntent, never recreation",
            f and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun intentNeverDestroysTheLiveActivityOrTask() {
        val f = NotificationLaunch.INTENT_FLAGS
        assertFalse("CLEAR_TOP would destroy + recreate the live activity",
            f and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertFalse("CLEAR_TASK would wipe the back stack",
            f and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertFalse("RESET_TASK_IF_NEEDED has nothing to reset (no clearTaskOnLaunch)",
            f and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertFalse("SINGLE_INSTANCE is an anachronism for a single-activity app",
            f and Intent.FLAG_ACTIVITY_SINGLE_INSTANCE != 0)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP, f)
    }

    @Test fun pendingIntentIsImmutableAndUpdateCurrent() {
        val p = NotificationLaunch.PI_FLAGS
        assertTrue("immutable: modern requirement for an app-launch intent",
            p and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue("update-current: rebuilds reuse one PendingIntent, no duplicates",
            p and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertFalse("no cancel-current: notification rebuilds must not kill the grant",
            p and PendingIntent.FLAG_CANCEL_CURRENT != 0)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, p)
    }
}
