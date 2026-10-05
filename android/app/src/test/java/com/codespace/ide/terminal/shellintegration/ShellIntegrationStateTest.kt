package com.codespace.ide.terminal.shellintegration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D15-b: JVM test matrix for the shell-integration STATE layer (B0) and the
 * shell-run capture model (B2).
 *
 * D15-a's parser suite deliberately excluded this class ("state is Android-bound").
 * B0 removes the blocker three ways: the constructor is internal (no live
 * TerminalSession needed), the log sink is injectable (AppOutputLog's Android
 * Handler/Looper never loads host-side), and the change listeners are pure
 * Kotlin. Session-registry lifetime (attach/forSession/detach) needs a real
 * TerminalSession and stays covered by device gate B; the behaviors that MATTER
 * here are the state machine and its observable surface.
 */
class ShellIntegrationStateTest {

    private val logLines = mutableListOf<String>()

    @Before fun setUp() {
        ShellIntegrationState.logSink = { logLines.add(it) }
    }

    @After fun tearDown() {
        ShellIntegrationState.logSink = null
    }

    private val goodNonce = "a".repeat(32)

    private fun state(nonce: String? = goodNonce) = ShellIntegrationState(1, nonce)

    private fun runCommand(st: ShellIntegrationState, cmd: String, exit: String?, nonce: String? = goodNonce) {
        st.onPayload("E;$cmd" + (if (nonce != null) ";$nonce" else ""))
        st.onPayload("C")
        st.onPayload("D" + (if (exit != null) ";$exit" else ""))
    }

    // ── B0: state machine drives the read accessors ────────────────────────

    @Test
    fun `full command lifecycle lands in the record ring with exit code`() {
        val st = state()
        runCommand(st, "ls -la", "0")
        val records = st.recentCommands(10)
        assertEquals(1, records.size)
        assertEquals("ls -la", records[0].command)
        assertEquals(0, records[0].exitCode)
        assertTrue(records[0].nonceValidated)
        assertEquals(0, st.lastCommandExitCode)
    }

    @Test
    fun `failed command carries nonzero exit and shows in the accessors`() {
        val st = state()
        runCommand(st, "false", "1")
        assertEquals(1, st.lastCommandExitCode)
        assertEquals(1, st.recentCommands(1).last().exitCode)
    }

    @Test
    fun `wrong nonce marks the record UNVALIDATED`() {
        val st = state()
        runCommand(st, "evil", "0", nonce = "b".repeat(32))
        assertFalse(st.recentCommands(1).last().nonceValidated)
    }

    @Test
    fun `absent nonce is also unvalidated`() {
        val st = state()
        st.onPayload("E;whoami")
        st.onPayload("C")
        st.onPayload("D;0")
        assertFalse(st.recentCommands(1).last().nonceValidated)
    }

    @Test
    fun `P Cwd updates currentCwd and the record captures it`() {
        val st = state()
        st.onPayload("P;Cwd=/root")
        assertEquals("/root", st.currentCwd)
        runCommand(st, "cd /tmp", "0")
        st.onPayload("P;Cwd=/tmp")
        assertEquals("/tmp", st.currentCwd)
        assertEquals("/root", st.recentCommands(1).last().cwd)
    }

    // ── B0: change listeners (reader-thread contract) ──────────────────────

    @Test
    fun `listener fires CWD then OUTPUT_START then COMMAND_COMPLETE in order`() {
        val st = state()
        val kinds = mutableListOf<ShellIntegrationState.ChangeKind>()
        st.addListener { kinds.add(it) }
        st.onPayload("P;Cwd=/root")
        runCommand(st, "true", "0")
        assertEquals(
            listOf(
                ShellIntegrationState.ChangeKind.CWD,
                ShellIntegrationState.ChangeKind.OUTPUT_START,
                ShellIntegrationState.ChangeKind.COMMAND_COMPLETE,
            ),
            kinds,
        )
    }

    @Test
    fun `unchanged Cwd does not fire CWD`() {
        val st = state()
        st.onPayload("P;Cwd=/root")
        var fired = 0
        st.addListener { if (it == ShellIntegrationState.ChangeKind.CWD) fired++ }
        st.onPayload("P;Cwd=/root") // emitter re-sends; state must dedupe
        assertEquals(0, fired)
    }

    @Test
    fun `removal handle stops the listener`() {
        val st = state()
        var fired = 0
        val remove = st.addListener { fired++ }
        remove()
        runCommand(st, "true", "0")
        assertEquals(0, fired)
    }

    @Test
    fun `a throwing listener cannot kill the others`() {
        val st = state()
        var survived = 0
        st.addListener { throw IllegalStateException("boom") }
        st.addListener { survived++ }
        runCommand(st, "true", "0")
        assertTrue(survived > 0)
    }

    // ── B2: transcript-window slicing (ShellRunCapture) ────────────────────

    @Test
    fun `slice returns the window after the start offset`() {
        val transcript = "prompt$ cmd\nline1\nline2\n"
        val start = transcript.indexOf("line1")
        val win = ShellRunCapture.sliceOutputWindow(transcript, start)
        assertEquals("line1\nline2\n", win)
    }

    @Test
    fun `null transcript and negative offset yield null`() {
        assertNull(ShellRunCapture.sliceOutputWindow(null, 0))
        assertNull(ShellRunCapture.sliceOutputWindow("abc", -1))
    }

    @Test
    fun `evicted window clamps to the surviving transcript`() {
        // Circular buffer shrank below the captured offset: return what survived.
        assertEquals("tail", ShellRunCapture.sliceOutputWindow("tail", 4000))
    }

    @Test
    fun `window is capped to the newest MAX_OUTPUT_CHARS`() {
        val big = "x".repeat(ShellRunCapture.MAX_OUTPUT_CHARS + 500)
        val win = ShellRunCapture.sliceOutputWindow(big, 0)
        assertEquals(ShellRunCapture.MAX_OUTPUT_CHARS, win!!.length)
        assertTrue(win.endsWith("x".repeat(100)))
    }

    @Test
    fun `empty window is null not empty string`() {
        assertNull(ShellRunCapture.sliceOutputWindow("", 0))
        assertNull(ShellRunCapture.sliceOutputWindow("abc", 3))
    }
}
