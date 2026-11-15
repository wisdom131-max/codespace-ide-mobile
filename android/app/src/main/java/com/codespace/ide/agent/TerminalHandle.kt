package com.codespace.ide.agent

/**
 * PHASE 8a: the wording and the rule for a command that outlives its inline window.
 *
 * Kept pure and separate from the coroutine machinery so the promise the model reads is testable:
 * it must say the command is STILL RUNNING, name how to poll, and must never suggest running it
 * again.
 */
internal object TerminalHandle {

    fun stillRunning(handle: String): String =
        "Still running after " + (BackgroundCommands.INLINE_WAIT_MS / 1000) + "s. Nothing has failed " +
            "and the command was NOT stopped. Poll it with terminal_list (it is the newest run) or " +
            "stop it with terminal_kill using handle " + handle + ". Do not start it again."

    /** True when a caller should hand back a handle rather than wait any longer. */
    fun shouldHandOff(waitedMs: Long): Boolean = waitedMs >= BackgroundCommands.INLINE_WAIT_MS
}
