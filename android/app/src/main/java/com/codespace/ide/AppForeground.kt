package com.codespace.ide

/**
 * Advisor follow-up (2026-10-15): process-wide foreground flag, set by
 * MainActivity.onStart/onStop. The auto-restore countdown (and every install
 * choice timeout) PAUSES while the app is backgrounded and only resumes when
 * the owner is looking — a restore can never start behind his back, and
 * background time never counts toward the 5 seconds.
 */
object AppForeground {
    @Volatile
    @JvmField
    var resumed: Boolean = false
}
