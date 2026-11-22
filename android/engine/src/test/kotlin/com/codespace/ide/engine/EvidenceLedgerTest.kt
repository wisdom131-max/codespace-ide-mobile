package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EvidenceLedgerTest {
    @Test fun remembersWhatWasSeen() {
        val l = EvidenceLedger()
        l.record("tests", "gradle assemble")
        assertTrue(l.has("tests"))
        assertFalse(l.has("build"))
        assertEquals(listOf("tests"), l.kinds())
    }
}
