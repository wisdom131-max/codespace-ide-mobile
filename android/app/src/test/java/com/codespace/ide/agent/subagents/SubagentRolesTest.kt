package com.codespace.ide.agent.subagents

import com.codespace.ide.chat.panel.ToolCategory
import com.codespace.ide.chat.panel.ToolRegistry
import com.codespace.ide.chat.panel.ToolCost
import com.codespace.ide.chat.panel.ToolMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 3.3: the role system must be a narrowing, never a widening.
 *
 * The dangerous version of this feature is a "subagent" that holds tools the user's own session
 * refuses. Every test below is about that boundary, or about a role silently gaining a capability
 * nobody granted it.
 */
class SubagentRolesTest {

    private val meta = mapOf(
        "read_file" to ToolMeta(ToolCategory.FILES),
        "list_files" to ToolMeta(ToolCategory.FILES),
        "write_file" to ToolMeta(ToolCategory.FILES, destructive = true),
        "delete_path" to ToolMeta(ToolCategory.FILES, destructive = true),
        "grep_search" to ToolMeta(ToolCategory.SEARCH),
        "edit_file" to ToolMeta(ToolCategory.EDIT, destructive = true),
        "git_status" to ToolMeta(ToolCategory.GIT),
        "git_commit" to ToolMeta(ToolCategory.GIT, destructive = true),
        "run_command" to ToolMeta(ToolCategory.TERMINAL, destructive = true),
        "web_search" to ToolMeta(ToolCategory.WEB, cost = ToolCost.HEAVY),
        "detect_secrets" to ToolMeta(ToolCategory.CONNECTORS),
        "read_entities" to ToolMeta(ToolCategory.DATA),
        "mcp_call" to ToolMeta(ToolCategory.MCP, destructive = true),
        "obscure_tool_without_meta" to null,
    )

    private fun lookup(name: String): ToolMeta? = meta[name]

    private fun scope(role: String, permitted: Set<String>): SubagentRoles.Scope {
        val result = SubagentRoles.scope(role, permitted, ::lookup)
        assertTrue("expected Ok for " + role + " but got " + result, result is SubagentRoles.Result.Ok)
        return (result as SubagentRoles.Result.Ok).scope
    }

    private fun refusal(role: String, permitted: Set<String>): String {
        val result = SubagentRoles.scope(role, permitted, ::lookup)
        assertTrue("expected Refused but got " + result, result is SubagentRoles.Result.Refused)
        return (result as SubagentRoles.Result.Refused).reason
    }

    @Test
    fun anUnknownRoleIsRefusedAndTheValidOnesAreListed() {
        val reason = refusal("wizard", setOf("read_file"))
        assertTrue(reason, reason.contains("wizard"))
        SubagentRoles.Role.entries.forEach { role ->
            assertTrue("the refusal should name " + role.id, reason.contains(role.id))
        }
    }

    @Test
    fun aReadOnlyRoleNeverReceivesADestructiveTool() {
        val readOnly = SubagentRoles.Role.entries.filter { !it.mayWrite }
        assertTrue("there must be read-only roles", readOnly.isNotEmpty())
        readOnly.forEach { role ->
            val granted = scope(role.id, meta.keys).tools
            val destructive = granted.filter { meta[it]?.destructive == true }
            assertTrue(role.id + " received destructive tools: " + destructive, destructive.isEmpty())
        }
    }

    @Test
    fun aRoleCannotContainAToolOutsideWhatTheSessionPermits() {
        val permitted = setOf("read_file", "grep_search")
        SubagentRoles.Role.entries.forEach { role ->
            val result = SubagentRoles.scope(role.id, permitted, ::lookup)
            if (result is SubagentRoles.Result.Ok) {
                assertTrue(
                    role.id + " granted something not permitted: " + result.scope.tools,
                    permitted.containsAll(result.scope.tools),
                )
            }
        }
    }

    @Test
    fun aToolWithNoMetadataIsNotGivenToAnyRole() {
        SubagentRoles.Role.entries.forEach { role ->
            val result = SubagentRoles.scope(role.id, setOf("obscure_tool_without_meta", "read_file"), ::lookup)
            if (result is SubagentRoles.Result.Ok) {
                assertFalse(
                    role.id + " received an unclassified tool",
                    result.scope.tools.contains("obscure_tool_without_meta"),
                )
            }
        }
    }

    @Test
    fun theResearcherCannotEditOrRunAnything() {
        val granted = scope("researcher", meta.keys).tools
        assertFalse(granted.toString(), granted.contains("edit_file"))
        assertFalse(granted.toString(), granted.contains("run_command"))
        assertFalse(granted.toString(), granted.contains("git_commit"))
        assertTrue(granted.toString(), granted.contains("web_search"))
    }

    @Test
    fun theSecurityReviewerIsReadOnlyEvenThoughItSeesGitAndConnectors() {
        val granted = scope("security", meta.keys).tools
        assertTrue(granted.toString(), granted.contains("git_status"))
        assertTrue(granted.toString(), granted.contains("detect_secrets"))
        assertFalse("a reviewer must not commit", granted.contains("git_commit"))
        assertFalse("a reviewer must not run commands", granted.contains("run_command"))
        assertFalse(granted.contains("write_file"))
    }

    @Test
    fun theCoderGetsTheWritesItNeedsAndSaysItCanWrite() {
        val coder = scope("coder", meta.keys)
        assertTrue(coder.tools.toString(), coder.tools.contains("write_file"))
        assertTrue(coder.tools.toString(), coder.tools.contains("edit_file"))
        assertTrue("the coder must be reported as able to write", coder.canWrite)
        assertFalse("the coder has no terminal in its categories", coder.tools.contains("run_command"))
    }

    @Test
    fun aReadOnlyScopeReportsThatItCannotWrite() {
        assertFalse(scope("researcher", meta.keys).canWrite)
        assertFalse(scope("planner", meta.keys).canWrite)
        assertFalse(scope("review", meta.keys).canWrite)
    }

    @Test
    fun anEmptyIntersectionIsRefusedWithTheRoleAndTheReason() {
        // No web tools are permitted here, so the researcher has nothing it may use.
        val reason = refusal("researcher", setOf("edit_file", "run_command"))
        assertTrue(reason, reason.contains("Research agent"))
        assertTrue(reason, reason.contains("read-only") || reason.contains("nothing"))
    }

    @Test
    fun withheldToolsAreReportedRatherThanSilentlyDropped() {
        val reviewer = scope("review", setOf("read_file", "write_file", "git_commit"))
        assertTrue(reviewer.withheld.toString(), reviewer.withheld.contains("write_file"))
        assertTrue(reviewer.withheld.toString(), reviewer.withheld.contains("git_commit"))
        assertFalse(reviewer.tools.toString(), reviewer.tools.contains("write_file"))
    }

    @Test
    fun askingForARoleIsCaseInsensitiveAndTrimmed() {
        assertTrue(SubagentRoles.isReadOnly("  RESEARCHER "))
        assertFalse(SubagentRoles.isReadOnly("coder"))
        assertFalse("an unknown role is not read-only, it is refused", SubagentRoles.isReadOnly("wizard"))
    }

    /**
     * The boundary that matters most: whatever the registry holds, and whatever the session permits,
     * a role's scope is always inside the permitted set. This asserts it against the real 129-tool
     * catalogue rather than the small fixture, so a future tool cannot slip through.
     */
    @Test
    fun everyRoleStaysInsideTheRealRegistryAndTheRealPermissions() {
        val real = ToolRegistry.names().toSet()
        assertTrue("the registry should be populated", real.size > 100)
        SubagentRoles.Role.entries.forEach { role ->
            val result = SubagentRoles.scope(role.id, real) { name ->
                ToolMeta(ToolCategory.FILES)
            }
            if (result is SubagentRoles.Result.Ok) {
                assertTrue(
                    role.id + " escaped the permitted set",
                    real.containsAll(result.scope.tools),
                )
            }
        }
    }

    @Test
    fun theRoleIdsAreUniqueAndLowerCase() {
        val ids = SubagentRoles.Role.ids()
        assertEquals("role ids must be unique", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertEquals("role ids are lower_snake", id.lowercase(), id)
            assertFalse(id.contains(' '))
        }
    }
}
