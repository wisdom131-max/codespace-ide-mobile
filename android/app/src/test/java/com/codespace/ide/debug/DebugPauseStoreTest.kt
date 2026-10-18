package com.codespace.ide.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH7-P3 (advisor 6d, 2026-10-18): JVM tests for DebugPauseStore — the durable
 * last-paused snapshot holder. The panel no longer owns paused state in section
 * composition; it mirrors THIS store, so these cases pin the semantics the owner
 * demanded:
 *  - pause -> read with NO listener registered (a pause before compose shows);
 *  - collapse and re-read (composition state loss can no longer lose values —
 *    the store returns the same snapshot);
 *  - continue -> cleared;
 *  - step -> replaced with the new values;
 *  - new session -> the new session sees NOTHING (no stale carry-over), and the
 *    old session's own clear never touches a different session's snapshot;
 *  - Python-shaped and Java-shaped snapshots both round-trip.
 */
class DebugPauseStoreTest {

    private fun pyVars() = listOf(
        DebugVariable(name = "x", type = "int", value = "30"),
        DebugVariable(name = "msg", type = "str", value = "'hello'", expandable = false),
        DebugVariable(name = "items", type = "list", value = "[1, 2]", expandable = true),
    )

    private fun javaVars() = listOf(
        DebugVariable(name = "args", type = "String[]", value = "[ZzDbg.java]", expandable = true),
        DebugVariable(name = "result", type = "int", value = "30"),
    )

    private fun pyStack(sessionFile: String) = listOf(
        DebugStackFrame(function = "<module>", file = sessionFile, line = 2, active = true),
    )

    private fun javaStack(sessionFile: String) = listOf(
        DebugStackFrame(function = "main", file = sessionFile, line = 5, active = true),
        DebugStackFrame(function = "sumAll", file = sessionFile, line = 9, active = false),
    )

    @Test fun pause_readWithoutAnyListener() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/zz_dbg_py.py"), pyVars())
        // No listener was ever registered — the snapshot still reads back.
        val snap = DebugPauseStore.currentFor("s1")
        assertNotNull(snap)
        assertEquals(1, snap!!.stack.size)
        assertEquals(3, snap.vars.size)
        assertEquals("x", snap.vars[0].name)
        DebugPauseStore.clearAll()
    }

    @Test fun collapse_andReRead_sameSnapshot() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/zz_dbg_py.py"), pyVars())
        val first = DebugPauseStore.currentFor("s1")
        // "Collapse and re-expand" is modeled as: read, then read again — the
        // holder lives outside section composition, so nothing changed between.
        val second = DebugPauseStore.currentFor("s1")
        assertEquals(first, second)
        assertEquals(3, second!!.vars.size)
        DebugPauseStore.clearAll()
    }

    @Test fun continue_clearsSnapshot() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/a.py"), pyVars())
        // UDM.transitionState clears on any non-PAUSED transition (continue).
        DebugPauseStore.clear("s1")
        assertNull(DebugPauseStore.currentFor("s1"))
        assertNull(DebugPauseStore.current())
        DebugPauseStore.clearAll()
    }

    @Test fun step_replacesWithNewValues() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/a.py"), pyVars())
        // A step fires a NEW pause with the new values — record replaces.
        DebugPauseStore.record("s1", pyStack("/proj/a.py"), listOf(DebugVariable(name = "x", type = "int", value = "31")))
        val snap = DebugPauseStore.currentFor("s1")
        assertEquals(1, snap!!.vars.size)
        assertEquals("31", snap.vars[0].value)
        DebugPauseStore.clearAll()
    }

    @Test fun newSession_neverSeesPreviousSessionValues() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/old.py"), pyVars())
        // A NEW session starts: currentFor(newId) must show nothing.
        assertNull(DebugPauseStore.currentFor("s2"))
        // And the new session's own stop transition cannot clear s1's snapshot
        // (clear is session-scoped) — but the panel asks currentFor(s2) only,
        // so stale values never render.
        DebugPauseStore.clear("s2")
        assertNotNull(DebugPauseStore.currentFor("s1"))
        DebugPauseStore.clearAll()
    }

    @Test fun newSession_replacedByItsOwnPause() {
        DebugPauseStore.clearAll()
        DebugPauseStore.record("s1", pyStack("/proj/old.py"), pyVars())
        // New run pauses: record overwrites the store (single holder), keyed to s2.
        DebugPauseStore.record("s2", javaStack("/proj/ZzDbg.java"), javaVars())
        val snap = DebugPauseStore.currentFor("s2")
        assertNotNull(snap)
        assertEquals(2, snap!!.vars.size)
        assertEquals("result", snap.vars[1].name)
        assertNull(DebugPauseStore.currentFor("s1")) // s1's old values are gone for good
        DebugPauseStore.clearAll()
    }

    @Test fun pythonShaped_snapshotRoundTrips() {
        DebugPauseStore.clearAll()
        val stack = pyStack("/proj/zz_dbg_py.py")
        val vars = pyVars()
        DebugPauseStore.record("py", stack, vars)
        val snap = DebugPauseStore.currentFor("py")
        assertEquals(stack, snap!!.stack)
        assertEquals(vars, snap.vars)
        assertTrue(snap.stack[0].active)
        assertEquals("list", snap.vars[2].type)
        DebugPauseStore.clearAll()
    }

    @Test fun javaShaped_snapshotRoundTrips() {
        DebugPauseStore.clearAll()
        val stack = javaStack("/proj/ZzDbg.java")
        val vars = javaVars()
        DebugPauseStore.record("jvm", stack, vars)
        val snap = DebugPauseStore.currentFor("jvm")
        assertEquals(2, snap!!.stack.size)
        assertEquals("sumAll", snap.stack[1].function)
        assertEquals("String[]", snap.vars[0].type)
        DebugPauseStore.clearAll()
    }

    @Test fun unknownSession_nullSnapshot() {
        DebugPauseStore.clearAll()
        assertNull(DebugPauseStore.currentFor(null))
        assertNull(DebugPauseStore.currentFor("missing"))
        DebugPauseStore.clearAll()
    }
}
