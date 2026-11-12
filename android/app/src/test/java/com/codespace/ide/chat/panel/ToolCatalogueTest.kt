package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "/tools" catalogue: grouping, ownership, search and honest rendering.
 *
 * These assertions are about the CATALOGUE, not the Thinking Tree: catalogue grouping answers
 * "what can I call and what is it for", and has nothing to do with task grouping in the transcript.
 */
class ToolCatalogueTest {

    private fun all() = ToolCatalogue.entries(ChatMode.AGENT, emptyList())

    @Test
    fun everyAdvertisedToolAppearsExactlyOnce() {
        val names = all().map { it.name }
        assertEquals("no duplicate catalogue entries", names.size, names.toSet().size)
        val exposed = ToolRegistry.exposedNames(ChatMode.AGENT)
        val missing = exposed.toSet() - names.toSet()
        assertTrue("catalogue must cover every exposed tool: $missing", missing.isEmpty())
    }

    @Test
    fun everyEntryHasAGroupAndATitleAndADescription() {
        all().forEach { item ->
            assertTrue("${item.name} has no group", item.group.isNotBlank())
            assertTrue("${item.name} has no title", item.title.isNotBlank())
            assertTrue("${item.name} has no description", item.description.isNotBlank())
        }
    }

    @Test
    fun groupsAreTheMetadataCategoriesAndAreNotOneBucket() {
        val groups = all().map { it.group }.toSet()
        assertTrue(groups.size >= 8)
        assertTrue(groups.contains("files"))
    }

    @Test
    fun aToolIsListedUnderItsOwningCategoryNotACompromise() {
        val byName = all().associateBy { it.name }
        assertEquals("files", byName.getValue("read_file").group)
        assertEquals("search", byName.getValue("grep_search").group)
        assertEquals("git", byName.getValue("git_status").group)
        assertEquals("terminal", byName.getValue("run_command").group)
    }

    @Test
    fun builtinToolsAreOwnedByTheAppAndMcpToolsByTheirServer() {
        val withMcp = ToolCatalogue.entries(ChatMode.AGENT, listOf("mcp_filesystem_read_file"))
        val builtin = withMcp.first { it.name == "read_file" }
        assertEquals("builtin", builtin.origin)
        val mcp = withMcp.first { it.name == "mcp_filesystem_read_file" }
        assertTrue(mcp.origin, mcp.origin.contains("filesystem"))
        assertEquals("mcp", mcp.group)
    }

    @Test
    fun mcpToolsAreNotInventedWhenNoneAreCached() {
        assertTrue(all().none { it.origin != "builtin" })
    }

    @Test
    fun destructiveToolsSaySo() {
        val byName = all().associateBy { it.name }
        assertTrue(byName.getValue("write_file").destructive)
        assertFalse(byName.getValue("read_file").destructive)
    }

    @Test
    fun searchMatchesNames() {
        val hits = ToolCatalogue.search(all(), "grep")
        assertTrue(hits.any { it.name == "grep_search" })
    }

    @Test
    fun searchMatchesKeywordsNotJustNames() {
        // "hash" is a keyword of checksum, which does not contain the word itself.
        val hits = ToolCatalogue.search(all(), "hash")
        assertTrue(hits.any { it.name == "checksum" })
    }

    @Test
    fun searchMatchesDescriptionText() {
        val hits = ToolCatalogue.search(all(), "archive")
        assertTrue(hits.any { it.name.startsWith("zip_") })
    }

    @Test
    fun searchIsBoundedAndReportsWhatItWithheld() {
        val hits = ToolCatalogue.search(all(), "e", limit = 5)
        assertEquals(5, hits.size)
    }

    @Test
    fun aGroupFilterNarrowsToThatGroup() {
        val hits = ToolCatalogue.entries(ChatMode.AGENT).filter { it.group == "git" }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.group == "git" })
    }

    @Test
    fun describeShowsGroupsAndABoundedList() {
        val out = ToolCatalogue.describe(ChatMode.AGENT, listOf("mcp_filesystem_read_file"))
        assertTrue(out, out.contains("group"))
        assertTrue(out, out.length < 20000)
    }

    @Test
    fun describeInAskModeDoesNotAdvertiseWriteTools() {
        val out = ToolCatalogue.describe(ChatMode.ASK, emptyList())
        assertFalse(out, out.contains("write_file"))
    }

    @Test
    fun theSchemaIsAvailableOnRequestAndOnlyOnRequest() {
        val schema = ToolCatalogue.schema("read_file")
        assertTrue(schema != null && schema.contains("properties"))
        assertEquals(null, ToolCatalogue.schema("not_a_tool"))
    }

    @Test
    fun leanModeStillWorksWithTheCatalogue() {
        // The catalogue is a VIEW: building it must not change what Lean Mode offers.
        val before = ToolOffer.plan(ChatMode.AGENT, "read the file").names
        all()
        val after = ToolOffer.plan(ChatMode.AGENT, "read the file").names
        assertEquals(before, after)
    }
}
