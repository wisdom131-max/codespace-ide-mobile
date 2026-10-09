package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codespace.ide.terminal.TerminalSessionStore.DecodedTabs
import com.codespace.ide.terminal.TerminalSessionStore.SavedTab

/**
 * F3 (2026-10-09, owner-approved): the store codec round-trip — count, ORDER,
 * names (incl. unicode), the ACTIVE tab id, and each tab's persisted cwd — plus
 * the corrupt-store and closed-tab contracts.
 */
class TerminalSessionStoreF3Test {

    private fun tab(i: Int, name: String, cwd: String) =
        SavedTab(id = "id$i", name = name, workingDir = cwd, lockedRoot = null, crashCount = 0)

    @Test
    fun `round-trip keeps count order names active id and cwds`() {
        val tabs = listOf(
            tab(1, "build", "/host-files/projects/p1"),
            tab(2, "Ünïcode nãme", "/sdcard/My Co de"),
            tab(3, "third", "/root"),
        )
        val raw = TerminalSessionStore.encodeTabs("id2", tabs)
        val decoded = TerminalSessionStore.decodeTabs(raw)
        assertNotNull(decoded)
        assertEquals("id2", decoded!!.activeId)
        assertEquals(3, decoded.tabs.size)
        // ORDER preserved
        assertEquals(listOf("id1", "id2", "id3"), decoded.tabs.map { it.id })
        // names preserved verbatim (unicode included)
        assertEquals("build", decoded.tabs[0].name)
        assertEquals("Ünïcode nãme", decoded.tabs[1].name)
        assertEquals("third", decoded.tabs[2].name)
        // per-tab cwd preserved
        assertEquals("/host-files/projects/p1", decoded.tabs[0].workingDir)
        assertEquals("/sdcard/My Co de", decoded.tabs[1].workingDir)
    }

    @Test
    fun `null active id round-trips as null`() {
        val raw = TerminalSessionStore.encodeTabs(null, listOf(tab(1, "solo", "/root")))
        val decoded = TerminalSessionStore.decodeTabs(raw)
        assertNotNull(decoded)
        assertNull(decoded!!.activeId)
    }

    @Test
    fun `legacy bare-array store decodes with null active id`() {
        val legacy = """[
            {"id":"a","name":"old1","workingDir":"/root","crashCount":0},
            {"id":"b","name":"old2","workingDir":"/host-files/projects/p9","crashCount":0}
        ]"""
        val decoded = TerminalSessionStore.decodeTabs(legacy)
        assertNotNull("legacy pre-F3 stores must keep restoring", decoded)
        assertNull(decoded!!.activeId)
        assertEquals(listOf("a", "b"), decoded.tabs.map { it.id })
        assertEquals("/host-files/projects/p9", decoded.tabs[1].workingDir)
    }

    @Test
    fun `corrupt store decodes to null — caller starts with one default tab`() {
        assertNull(TerminalSessionStore.decodeTabs("not json at all"))
        assertNull(TerminalSessionStore.decodeTabs("""{"tabs": [}"""))
        assertNull(TerminalSessionStore.decodeTabs("""{"noTabsField": 1}"""))
    }

    @Test
    fun `closed tab stays closed — the tab dropped from the save never returns`() {
        val open = listOf(tab(1, "a", "/root"), tab(2, "b", "/tmp"), tab(3, "c", "/etc"))
        // User closes tab 2 ON PURPOSE: the save-after-close drops it.
        val afterClose = open.filter { it.id != "id2" }
        val raw = TerminalSessionStore.encodeTabs("id3", afterClose)
        val decoded = TerminalSessionStore.decodeTabs(raw)!!
        assertEquals(listOf("id1", "id3"), decoded.tabs.map { it.id })
        assertTrue("the deliberately closed tab must never resurrect", decoded.tabs.none { it.id == "id2" })
        assertEquals("id3", decoded.activeId)
    }

    @Test
    fun `crashed-out tabs decode but are eligibility-filtered by the caller`() {
        val raw = TerminalSessionStore.encodeTabs("id1", listOf(
            tab(1, "ok", "/root"),
            SavedTab("id2", "crashed", "/root", null, 2),
        ))
        val decoded = TerminalSessionStore.decodeTabs(raw)!!
        assertEquals(2, decoded.tabs.size) // codec is faithful; load() filters crashCount
        assertEquals(2, decoded.tabs[1].crashCount)
    }
}
