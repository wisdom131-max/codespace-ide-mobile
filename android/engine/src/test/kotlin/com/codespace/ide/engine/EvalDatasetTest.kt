package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalDatasetTest {
    @Test fun roundTrips() {
        val c = EvalDataset.EvalCase("e1", "do a thing", "a result", listOf("smoke"))
        val back = EvalDataset.parse(EvalDataset.render(listOf(c)))
        assertEquals(1, back.size)
        assertEquals("do a thing", back[0].request)
        assertEquals("a result", back[0].expected)
        assertEquals(listOf("smoke"), back[0].tags)
    }
    @Test fun skipsMalformedLines() {
        assertEquals(0, EvalDataset.parse("garbage line without tabs").size)
    }
}
