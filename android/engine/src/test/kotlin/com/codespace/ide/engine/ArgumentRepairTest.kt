package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgumentRepairTest {

    @Test
    fun alreadyValidIsUnchanged() {
        val r = ArgumentRepair.repair("""{"path":"a.kt"}""")
        assertTrue(r is ArgumentRepair.Result.Ok)
        assertEquals("""{"path":"a.kt"}""", (r as ArgumentRepair.Result.Ok).json)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun aMissingFinalBraceIsClosed() {
        val r = ArgumentRepair.repair("""{"path":"a.kt"""")
        assertTrue(r is ArgumentRepair.Result.Ok)
        assertEquals("""{"path":"a.kt"}""", (r as ArgumentRepair.Result.Ok).json)
    }

    @Test
    fun aTrailingCommaIsRemoved() {
        val r = ArgumentRepair.repair("""{"path":"a.kt",}""")
        assertTrue(r is ArgumentRepair.Result.Ok)
        assertEquals("""{"path":"a.kt"}""", (r as ArgumentRepair.Result.Ok).json)
    }

    @Test
    fun smartQuotesAreNormalised() {
        val r = ArgumentRepair.repair("{\u201cpath\u201d:\u201ca.kt\u201d}")
        assertTrue(r is ArgumentRepair.Result.Ok)
        assertEquals("""{"path":"a.kt"}""", (r as ArgumentRepair.Result.Ok).json)
    }

    @Test
    fun anUnquotedKeyIsRefusedRatherThanGuessed() {
        val r = ArgumentRepair.repair("""{path:"a.kt"}""")
        assertTrue("must refuse, not invent", r is ArgumentRepair.Result.Failed)
    }

    @Test
    fun emptyIsRefused() {
        assertTrue(ArgumentRepair.repair("  ") is ArgumentRepair.Result.Failed)
    }
}
