package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * STAGE 1 — the panel signal must follow the DISK, not the intent.
 *
 * These tests exist because the failure they prevent is invisible: a panel that refreshes on a
 * failed write shows a fact that is not stored, and the user believes it is remembered.
 */
class MemoryEventsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("mem")

    @Test
    fun `successful save publishes exactly one revision`() {
        val d = dir()
        val before = MemoryEvents.revision.value
        val r = FactsStore.saveTo(d, "Build with gradlew assembleDevDebug", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.Saved)
        assertEquals(before + 1, MemoryEvents.revision.value)
    }

    @Test
    fun `refused save publishes nothing`() {
        val d = dir()
        val before = MemoryEvents.revision.value
        val r = FactsStore.saveTo(d, "   ", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.Refused)
        assertEquals(before, MemoryEvents.revision.value)
    }

    @Test
    fun `project fact without a project publishes nothing`() {
        val d = dir()
        val before = MemoryEvents.revision.value
        val r = FactsStore.saveTo(d, "Uses MVVM", FactScope.PROJECT, null)
        assertTrue(r is FactsStore.SaveResult.Refused)
        assertEquals(before, MemoryEvents.revision.value)
    }

    @Test
    fun `failed delete publishes nothing and a real delete publishes once`() {
        val d = dir()
        val saved = FactsStore.saveTo(d, "Prefer Kotlin", FactScope.USER, null)
        val id = (saved as FactsStore.SaveResult.Saved).fact.id
        val before = MemoryEvents.revision.value
        assertFalse(FactsStore.deleteIn(d, "f_missing"))
        assertEquals(before, MemoryEvents.revision.value)
        assertTrue(FactsStore.deleteIn(d, id))
        assertEquals(before + 1, MemoryEvents.revision.value)
    }

    @Test
    fun `update publishes once on success`() {
        val d = dir()
        val saved = FactsStore.saveTo(d, "Prefer tabs", FactScope.USER, null)
        val id = (saved as FactsStore.SaveResult.Saved).fact.id
        val before = MemoryEvents.revision.value
        assertEquals(null, FactsStore.updateIn(d, id, content = "Prefer spaces"))
        assertEquals(before + 1, MemoryEvents.revision.value)
    }
}

/**
 * STAGE 1 — a date that is not known must read as unknown.
 *
 * The rule under test: no function here may substitute one timestamp for another, and no absent
 * value may be rendered as a date.
 */
class FactDatesTest {

    @Test
    fun `absent dates read Not recorded`() {
        assertEquals(FactDates.NOT_RECORDED, FactDates.local(null))
        assertEquals(FactDates.NOT_RECORDED, FactDates.local(0L))
        assertEquals(FactDates.NOT_RECORDED, FactDates.conversation(null))
    }

    @Test
    fun `a real date is rendered and is not the unknown label`() {
        val ms = 1_700_000_000_000L
        val text = FactDates.local(ms)
        assertTrue(text.isNotEmpty())
        assertTrue(text != FactDates.NOT_RECORDED)
        assertEquals(text, FactDates.conversation(ms))
    }

    @Test
    fun `known distinguishes an absent value from a real one`() {
        assertFalse(FactDates.known(null))
        assertFalse(FactDates.known(0L))
        assertTrue(FactDates.known(1L))
    }
}
