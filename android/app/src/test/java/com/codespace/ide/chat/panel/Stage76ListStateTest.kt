package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN 7.6 A/B — the model list's own state, which must be true rather than decorative.
 *
 * This picker does not fetch over the network; the list is read from disk. So there is no loading state
 * to show, and the note says what the data actually is.
 */
class Stage76ListStateTest {

    @Test
    fun `a normal list with a known live set needs no note at all`() {
        assertNull(ModelPickerPresentation.listNote(liveIdsKnown = true, unreadable = false, shown = 5))
    }

    @Test
    fun `an endpoint that never reported its models says so instead of implying a fetch`() {
        val note = ModelPickerPresentation.listNote(liveIdsKnown = false, unreadable = false, shown = 5)!!
        assertTrue(note.contains("not reported its model list"))
        assertTrue(note.contains("catalogue"))
        assertTrue("no loading claim", !note.contains("Loading", ignoreCase = true))
    }

    @Test
    fun `an unreadable store is left to the notice that already handles it`() {
        assertNull(ModelPickerPresentation.listNote(liveIdsKnown = false, unreadable = true, shown = 3))
    }

    @Test
    fun `an empty list has its own sentence`() {
        assertTrue(ModelPickerPresentation.listNote(true, false, 0)!!.contains("No models are stored"))
    }

    @Test
    fun `every list error is explained without raw transport detail`() {
        val messages = ModelPickerPresentation.ErrorKind.values().map { ModelPickerPresentation.listError(it) }
        assertEquals("one message per kind", messages.size, messages.toSet().size)
        messages.forEach { m ->
            assertTrue("no raw status codes: $m", !m.contains("401") && !m.contains("429"))
            assertTrue("no headers or keys: $m", !m.contains("Authorization") && !m.contains("Bearer"))
            assertTrue("no silent switch promised: $m", !m.contains("switched to", ignoreCase = true))
        }
    }

    @Test
    fun `auth and rate-limit errors say the selection is untouched`() {
        val auth = ModelPickerPresentation.listError(ModelPickerPresentation.ErrorKind.AUTH)
        val rate = ModelPickerPresentation.listError(ModelPickerPresentation.ErrorKind.RATE_LIMIT)
        assertTrue(auth.contains("selection is unchanged"))
        assertTrue(rate.contains("Nothing was switched"))
    }

    @Test
    fun `an unknown custom endpoint keeps its capabilities unknown`() {
        val m = ModelPickerPresentation.listError(ModelPickerPresentation.ErrorKind.CUSTOM_ENDPOINT)
        assertTrue(m.contains("capabilities stay unknown"))
    }
}
