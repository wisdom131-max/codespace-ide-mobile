package com.codespace.ide.terminal

/**
 * E17-b / E17-c choice-dialog state machine (advisor ruling 2026-10-08).
 *
 * Pure Kotlin, no Android dependencies, JVM-testable: TerminalPane's install
 * thread DRIVES this machine, but every decision rule lives here —
 *
 *   - kind 0 (E17-b, storage permission): GRANT_OR_RETRY means "Grant access now".
 *   - kind 1 (E17-c, restore failed):      GRANT_OR_RETRY means "Try restore again".
 *   - DOWNLOAD_FRESH means the ~58 MB fresh-download path (always an explicit
 *     user tap; the backup stays in shared storage either way).
 *   - No choice within the timeout, or a dialog dismissal (outside-tap/back),
 *     resolves to SKIPPED_TIMEOUT / SKIPPED_DISMISSED — the install is SKIPPED,
 *     never a default download (reopen the tab to retry).
 *
 * The machine models ONE dialog at a time; a retry re-enters begin() with the
 * same or a new kind. All choices are user taps; timeout is evaluated by the
 * driver feeding tick() (the clock is injectable so JVM tests need no sleeps).
 */
enum class InstallChoice {
    GRANT_OR_RETRY,     // dialog button 1 (grant access / try restore again)
    DOWNLOAD_FRESH,     // dialog button 2
    SKIPPED_TIMEOUT,    // no choice before the deadline
    SKIPPED_DISMISSED,  // outside-tap / back dismissed the dialog
}

class InstallChoiceMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { IDLE, AWAITING_CHOICE, RESOLVED }

    var state: State = State.IDLE
        private set
    /** 0 = storage permission (E17-b), 1 = restore failed (E17-c). */
    var kind: Int = 0
        private set
    /** The failure reason shown for kind 1 (empty for kind 0). */
    var reason: String = ""
        private set

    private var deadline: Long = 0L
    private var outcome: InstallChoice? = null

    val resolved: Boolean get() = state == State.RESOLVED
    val awaiting: Boolean get() = state == State.AWAITING_CHOICE
    fun outcomeOrNull(): InstallChoice? = outcome

    /** Open the dialog for [kind]; resets any previous outcome (retry cycle). */
    fun begin(kind: Int, reason: String = "", timeoutMs: Long = 600_000) {
        this.kind = kind
        this.reason = reason
        this.outcome = null
        this.deadline = clock() + timeoutMs
        this.state = State.AWAITING_CHOICE
    }

    /** Dialog button tap: 1 = grant/retry, 2 = download fresh. Ignored unless awaiting. */
    fun choose(value: Int) {
        if (state != State.AWAITING_CHOICE) return
        outcome = when (value) {
            1 -> InstallChoice.GRANT_OR_RETRY
            2 -> InstallChoice.DOWNLOAD_FRESH
            else -> InstallChoice.SKIPPED_DISMISSED
        }
        state = State.RESOLVED
    }

    /** Dialog dismissed (outside-tap / back). Ignored unless awaiting. */
    fun dismiss() {
        if (state != State.AWAITING_CHOICE) return
        outcome = InstallChoice.SKIPPED_DISMISSED
        state = State.RESOLVED
    }

    /**
     * Driver poll: resolves SKIPPED_TIMEOUT once the deadline passes and reports
     * whether the machine is resolved. Call from the install-thread poll loop.
     */
    fun tick(): Boolean {
        if (state == State.AWAITING_CHOICE && clock() >= deadline) {
            outcome = InstallChoice.SKIPPED_TIMEOUT
            state = State.RESOLVED
        }
        return state == State.RESOLVED
    }
}
