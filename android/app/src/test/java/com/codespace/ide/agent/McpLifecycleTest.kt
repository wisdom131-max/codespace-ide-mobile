package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP lifecycle rules. Pure, because every one of these decided whether a call reached the right
 * server, whether a bad config was accepted, or how much of a server's answer reached the model.
 */
class McpLifecycleTest {

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    fun aPlainNameIsAccepted() {
        assertNull(McpLifecycle.validateName("filesystem"))
        assertNull(McpLifecycle.validateName("my-server_2"))
    }

    @Test
    fun surroundingSpaceIsTrimmedNotRejected() {
        assertNull(McpLifecycle.validateName("  github  "))
    }

    @Test
    fun blankNameIsRefusedWithAnActionableMessage() {
        val msg = McpLifecycle.validateName("   ")
        assertNotNull(msg)
        assertTrue(msg!!.contains("name"))
    }

    @Test
    fun spacesAndSlashesAreRefusedBecauseTheyBreakTheToolKey() {
        assertNotNull(McpLifecycle.validateName("my server"))
        assertNotNull(McpLifecycle.validateName("a/b"))
        assertNotNull(McpLifecycle.validateName("dot.name"))
    }

    @Test
    fun theReservedMcpPrefixIsRefused() {
        val msg = McpLifecycle.validateName("mcp_files")
        assertNotNull(msg)
        assertTrue(msg!!.contains("mcp_"))
    }

    @Test
    fun anOverlongNameIsRefused() {
        assertNotNull(McpLifecycle.validateName("a".repeat(McpLifecycle.MAX_NAME_CHARS + 1)))
    }

    @Test
    fun aBlankCommandIsRefused() {
        assertNotNull(McpLifecycle.validateCommand("  "))
        assertNull(McpLifecycle.validateCommand("npx -y @modelcontextprotocol/server-filesystem /root"))
    }

    @Test
    fun duplicatesAreRefusedCaseInsensitively() {
        val existing = listOf("github", "filesystem")
        assertNotNull(McpLifecycle.validateNewServer("GitHub", "npx x", existing))
        assertNull(McpLifecycle.validateNewServer("gitlab", "npx x", existing))
    }

    // ── tool identity / routing ─────────────────────────────────────────────

    @Test
    fun keyAndResolveRoundTrip() {
        assertEquals("mcp_gh_create_issue", McpLifecycle.key("gh", "create_issue"))
        val route = McpLifecycle.resolve("mcp_gh_create_issue", listOf("gh"))
        assertEquals(McpLifecycle.Route("gh", "create_issue"), route)
    }

    @Test
    fun aToolNameContainingUnderscoresKeepsThem() {
        val route = McpLifecycle.resolve("mcp_gh_repo_list_branches", listOf("gh"))
        assertEquals("gh", route!!.server)
        assertEquals("repo_list_branches", route.tool)
    }

    /**
     * The ambiguity this file exists for: server "files" with tool "b_x" and server "files_b" with
     * tool "x" both spell mcp_files_b_x (the separator is "_", so a name that ends where the next
     * one continues collides).
     */
    @Test
    fun theCachedServerWinsOverALookalikeName() {
        val route = McpLifecycle.resolve(
            "mcp_files_b_x", listOf("files", "files_b"), cachedServer = "files",
        )
        assertEquals("files", route!!.server)
        assertEquals("b_x", route.tool)
    }

    @Test
    fun withoutACacheEntryTheLongestNameWins() {
        // No cached owner (a continuation after a process restart): the longest configured name is
        // the best guess, and it is the one the user is most likely to have meant.
        val route = McpLifecycle.resolve("mcp_files_b_x", listOf("files", "files_b"))
        assertEquals("files_b", route!!.server)
        assertEquals("x", route.tool)
    }

    @Test
    fun aNameThatIsNotAnMcpToolIsNotRouted() {
        assertNull(McpLifecycle.resolve("read_file", listOf("files")))
        assertNull(McpLifecycle.resolve("mcp_unknown_tool", listOf("files")))
    }

    // ── discovery results ───────────────────────────────────────────────────

    @Test
    fun aMissingSchemaBecomesAnEmptyObjectSchema() {
        val s = McpLifecycle.sanitizeSchema(null)
        assertEquals("object", s.optString("type"))
        assertEquals(0, s.optJSONObject("properties")!!.length())
    }

    @Test
    fun aSchemaWithoutPropertiesStillDescribesAnObject() {
        val s = McpLifecycle.sanitizeSchema(JSONObject().put("type", "object"))
        assertEquals("object", s.optString("type"))
        assertEquals(0, s.optJSONObject("properties")!!.length())
    }

    @Test
    fun propertiesAndRequiredSurviveSanitising() {
        val raw = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("path").put(""))
        val s = McpLifecycle.sanitizeSchema(raw)
        assertEquals("string", s.optJSONObject("properties")!!.optJSONObject("path")!!.optString("type"))
        assertEquals(1, s.optJSONArray("required")!!.length())
        assertEquals("path", s.optJSONArray("required")!!.getString(0))
    }

    @Test
    fun toolsOfADeadServerAreTheOnesPruned() {
        val cached = mapOf("mcp_a_one" to "a", "mcp_b_two" to "b")
        assertEquals(listOf("mcp_a_one"), McpLifecycle.pruneStale(cached, setOf("a")))
        assertTrue(McpLifecycle.pruneStale(cached, emptySet()).isEmpty())
    }

    // ── result handling ─────────────────────────────────────────────────────

    @Test
    fun aResultIsWrappedInItsServerBoundary() {
        val out = McpLifecycle.wrapResult("gh", "create_issue", "done")
        assertTrue(out.contains("server=\"gh\""))
        assertTrue(out.contains("tool=\"create_issue\""))
        assertTrue(out.contains("done"))
        assertTrue(out.trimEnd().endsWith("</mcp_result>"))
    }

    @Test
    fun aHugeResultIsCutAndSaysHowMuchWasLost() {
        val huge = "x".repeat(McpLifecycle.MAX_RESULT_CHARS + 500)
        val out = McpLifecycle.wrapResult("gh", "dump", huge)
        assertTrue(out.contains("[truncated: 500 more characters from gh/dump]"))
        assertTrue(out.length < huge.length)
    }

    @Test
    fun aSmallResultIsNotTruncated() {
        val out = McpLifecycle.wrapResult("gh", "ping", "ok")
        assertTrue(!out.contains("truncated"))
    }

    @Test
    fun aFailureNamesTheServerAndTheTool() {
        val msg = McpLifecycle.describeFailure("gh", "create_issue", "timeout")
        assertTrue(msg.contains("gh/create_issue"))
        assertTrue(msg.contains("timeout"))
        assertTrue(McpLifecycle.describeFailure("gh", "x", "").contains("no reason given"))
    }
}
