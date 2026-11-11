package com.codespace.ide.chat.panel

import com.codespace.ide.agent.AgentTools
import com.codespace.ide.agent.ChatFlowLevel
import com.codespace.ide.agent.ChatPermissionStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 1 Group A, at the seams: the registry, the executor list, the discovery index and the
 * permission rule. The executors themselves are covered by DataToolsTest; this file proves the three
 * tools are reachable and correctly classified rather than merely present.
 */
class Wave1DataToolsRegistryTest {

    private val names = listOf("json_query", "csv_parse", "base64_codec")

    @Test
    fun eachToolIsRegisteredAndExecutable() {
        for (name in names) {
            assertTrue(name + " must be executable", AgentTools.toolNames().contains(name))
            val spec = ToolRegistry.spec(name) ?: error(name + " is not in the registry")
            assertTrue(name + " must be offered in Ask", spec.modes.contains(ChatMode.ASK))
            assertTrue(name + " must be offered in Edit", spec.modes.contains(ChatMode.EDIT))
            assertTrue(name + " must be offered in Agent", spec.modes.contains(ChatMode.AGENT))
            assertTrue(name + " is read-only and must not be hidden", ToolRegistry.hidden().none { it.name == name })
        }
    }

    @Test
    fun schemasDeclareEveryArgumentTheExecutorReads() {
        val expected = mapOf(
            "json_query" to listOf("expr", "path", "json"),
            "csv_parse" to listOf("path", "text", "delimiter", "header", "offset", "limit", "columns"),
            "base64_codec" to listOf("op", "text", "path"),
        )
        for ((name, props) in expected) {
            val spec = ToolRegistry.spec(name)!!
            val obj = JSONObject(spec.schema)
            assertEquals(name + " schema must be an object", "object", obj.getString("type"))
            val declared = obj.getJSONObject("properties").keys().asSequence().toList()
            for (p in props) assertTrue(name + " schema must declare '" + p + "'", declared.contains(p))
        }
    }

    @Test
    fun noneOfThemIsSilentlyAutoApproved() {
        // Deliberate: Wave 1 does not widen SAFE_TOOLS, so AUTO_SAFE keeps asking for these exactly
        // as it does today. Additions belong in a decision about AUTO_SAFE, not in a tool commit.
        for (name in names) {
            assertFalse(name + " must not be auto-approved under AUTO_SAFE",
                ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, name, emptySet()))
            assertFalse(name + " is not an MCP tool", name.startsWith("mcp_"))
        }
    }

    @Test
    fun discoveryFindsThemByTheirOwnNameAndByTheirSubject() {
        for (name in names) {
            val spec = ToolRegistry.spec(name)!!
            assertTrue(name + " has no metadata", ToolMetaIndex.metaFor(spec).keywords.isNotEmpty())
            assertTrue(name + " must be findable by its own name",
                ToolMetaIndex.search(name, ChatMode.AGENT).any { it.spec.name == name })
        }
        assertTrue(ToolMetaIndex.search("json", ChatMode.AGENT).any { it.spec.name == "json_query" })
        assertTrue(ToolMetaIndex.search("csv", ChatMode.AGENT).any { it.spec.name == "csv_parse" })
        assertTrue(ToolMetaIndex.search("base64", ChatMode.AGENT).any { it.spec.name == "base64_codec" })
    }

}
