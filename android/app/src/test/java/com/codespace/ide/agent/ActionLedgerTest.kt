package com.codespace.ide.agent

import com.codespace.ide.agent.ActionLedger.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PROJECT 4: the six-state action ledger.
 *
 * Each test names the rule it holds. The security tests (approval-gated begin, terminal-state
 * integrity, "planned is not executed") are the reason this ledger exists, so they are first.
 */
class ActionLedgerTest {

    private fun ledger(): ActionLedger = ActionLedger(null)
    private fun file(): File = File(Files.createTempDirectory("vncode-actions").toFile(), "a.ledger")

    // ── security ────────────────────────────────────────────────────────────

    @Test fun `an approval-gated action cannot begin before it is approved`() {
        val l = ledger()
        l.plan("c1", "mcp__github__create_issue", "s1", requiresApproval = true, now = 1)
        assertFalse("no approval, no execution", l.begin("c1", now = 2))
        assertFalse(l.mayExecute("c1"))
        assertEquals(State.PLANNED, l.action("c1")!!.state)
    }

    @Test fun `approval then execution is the allowed path`() {
        val l = ledger()
        l.plan("c1", "mcp__github__create_issue", "s1", requiresApproval = true, now = 1)
        assertTrue(l.approve("c1", now = 2))
        assertTrue(l.mayExecute("c1"))
        assertTrue(l.begin("c1", now = 3))
        assertEquals(State.EXECUTING, l.action("c1")!!.state)
    }

    @Test fun `planned and approved are never evidence of execution`() {
        val l = ledger()
        l.plan("a", "write_file", "s1", requiresApproval = false, now = 1)
        assertFalse("planned did not run", l.action("a")!!.ran)
        l.plan("b", "mcp__x", "s1", requiresApproval = true, now = 1)
        l.approve("b", now = 2)
        assertFalse("approved did not run", l.action("b")!!.ran)
        l.begin("b", now = 3)
        assertTrue("executing ran", l.action("b")!!.ran)
    }

    @Test fun `a cancelled action can never begin later`() {
        val l = ledger()
        l.plan("a", "delete_file", "s1", requiresApproval = false, now = 1)
        assertTrue(l.cancel("a", now = 2, detail = "user cancelled"))
        assertFalse("stale callback is refused", l.begin("a", now = 3))
        assertFalse(l.mayExecute("a"))
        assertEquals(State.CANCELLED, l.action("a")!!.state)
    }

    @Test fun `an action needing no approval may begin straight from planned`() {
        val l = ledger()
        l.plan("a", "read_file", "s1", requiresApproval = false, now = 1)
        assertTrue(l.mayExecute("a"))
        assertTrue(l.begin("a", now = 2))
    }

    // ── the six states and their transitions ────────────────────────────────

    @Test fun `all six states are reachable and recorded`() {
        val l = ledger()
        l.plan("p", "t", "s", false, 1)
        l.plan("ap", "t", "s", true, 1); l.approve("ap", 2)
        l.plan("ex", "t", "s", false, 1); l.begin("ex", 2)
        l.plan("co", "t", "s", false, 1); l.begin("co", 2); l.complete("co", 3)
        l.plan("fa", "t", "s", false, 1); l.begin("fa", 2); l.fail("fa", 3, "boom")
        l.plan("ca", "t", "s", false, 1); l.cancel("ca", 2)
        assertEquals(State.PLANNED, l.action("p")!!.state)
        assertEquals(State.APPROVED, l.action("ap")!!.state)
        assertEquals(State.EXECUTING, l.action("ex")!!.state)
        assertEquals(State.COMPLETED, l.action("co")!!.state)
        assertEquals(State.FAILED, l.action("fa")!!.state)
        assertEquals(State.CANCELLED, l.action("ca")!!.state)
        assertEquals("boom", l.action("fa")!!.detail)
        assertEquals(1, l.inState(State.FAILED).size)
    }

    @Test fun `invalid transitions are refused rather than coerced`() {
        val l = ledger()
        l.plan("a", "t", "s", false, 1)
        l.begin("a", 2)
        assertFalse("cannot approve something already running", l.approve("a", 3))
        assertFalse("cannot go back to planned", l.plan("a", "t", "s", false, 4) != null)
        assertEquals(State.EXECUTING, l.action("a")!!.state)
        assertFalse(ActionLedger.allowed(State.COMPLETED, State.EXECUTING))
        assertFalse(ActionLedger.allowed(State.CANCELLED, State.COMPLETED))
        assertFalse(ActionLedger.allowed(State.FAILED, State.FAILED))
    }

    @Test fun `a terminal state is final against late and duplicate events`() {
        val l = ledger()
        l.plan("a", "t", "s", false, 1); l.begin("a", 2); l.complete("a", 3)
        assertFalse("late fail is refused", l.fail("a", 4, "late"))
        assertFalse("late cancel is refused", l.cancel("a", 5))
        assertFalse("duplicate complete is refused", l.complete("a", 6))
        assertEquals(State.COMPLETED, l.action("a")!!.state)
        assertEquals(3L, l.action("a")!!.updatedAt)
    }

    @Test fun `execution failure is recorded with its reason`() {
        val l = ledger()
        l.plan("a", "run_command", "s", false, 1); l.begin("a", 2)
        assertTrue(l.fail("a", 3, "exit 1"))
        val a = l.action("a")!!
        assertEquals(State.FAILED, a.state)
        assertEquals("exit 1", a.detail)
        assertTrue(a.terminal)
    }

    // ── identity and concurrency ────────────────────────────────────────────

    @Test fun `a duplicate plan does not create or overwrite an action`() {
        val l = ledger()
        val first = l.plan("a", "read_file", "s", false, 1)
        assertNotNull(first)
        assertNull("second plan of the same id is ignored", l.plan("a", "write_file", "s", false, 2))
        assertEquals(1, l.all().size)
        assertEquals("read_file", l.action("a")!!.tool)
    }

    @Test fun `a blank id has no identity so nothing is recorded`() {
        val l = ledger()
        assertNull(l.plan("  ", "t", "s", false, 1))
        assertTrue(l.all().isEmpty())
    }

    @Test fun `concurrent callers cannot overwrite one another`() {
        val l = ledger()
        val n = 40
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(n) { i ->
            pool.submit {
                start.await()
                l.plan("id" + i, "tool" + i, "s", false, 1)
                l.begin("id" + i, 2)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals(n, l.all().size)
        assertEquals(n, l.inState(State.EXECUTING).size)
    }

    @Test fun `the same id raced from two threads produces exactly one action`() {
        val l = ledger()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        repeat(8) {
            pool.submit { start.await(); l.plan("same", "t", "s", false, 1) }
        }
        start.countDown(); pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals(1, l.all().size)
    }

    // ── persistence, restoration, continuation ──────────────────────────────

    @Test fun `state survives a restart and history is preserved`() {
        val f = file()
        val l = ActionLedger(f)
        l.plan("done", "read_file", "s1", false, 10); l.begin("done", 11); l.complete("done", 12)
        l.plan("open", "write_file", "s1", false, 13); l.begin("open", 14)
        l.plan("asked", "mcp__x", "s1", true, 15)

        val restored = ActionLedger(f)
        assertEquals(State.COMPLETED, restored.action("done")!!.state)
        assertEquals(State.EXECUTING, restored.action("open")!!.state)
        assertEquals(State.PLANNED, restored.action("asked")!!.state)
        assertTrue("an approval-gated plan is still gated after restart", !restored.mayExecute("asked"))
    }

    @Test fun `a corrupt ledger file loads empty instead of throwing`() {
        val f = file()
        f.writeText("not|a|ledger\n\u0000garbage\n")
        val l = ActionLedger(f)
        assertTrue(l.all().isEmpty())
        assertNotNull("still usable", l.plan("a", "t", "s", false, 1))
    }

    @Test fun `the ledger stays bounded`() {
        val f = file()
        val l = ActionLedger(f)
        repeat(ActionLedger.MAX_ACTIONS + 30) { i ->
            l.plan("id" + i, "t", "s", false, i.toLong()); l.begin("id" + i, i.toLong()); l.complete("id" + i, i.toLong())
        }
        assertTrue(l.all().size <= ActionLedger.MAX_ACTIONS)
        val restored = ActionLedger(f)
        assertTrue(restored.all().size <= ActionLedger.MAX_ACTIONS)
    }
}
