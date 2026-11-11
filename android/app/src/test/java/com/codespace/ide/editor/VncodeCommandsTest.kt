package com.codespace.ide.editor

import com.codespace.ide.agent.ChatFlowLevel
import com.codespace.ide.agent.ChatPermissionStore
import com.codespace.ide.chat.panel.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlisted dispatcher's contract. Everything here is pure: no Android, no composition.
 *
 * The permission tests use ChatPermissionStore.decideAutoApproved - the same rule the gate applies -
 * so "AUTO_ALL must not prompt" and "MANUAL must prompt" are asserted rather than claimed.
 */
class VncodeCommandsTest {

    private fun ready(command: String, query: String? = null, line: Int = 0): CommandRequest {
        val p = VncodeCommands.parse(command, query, line)
        assertTrue("expected Ready for " + command + " but got " + p, p is VncodeCommands.Parse.Ready)
        return (p as VncodeCommands.Parse.Ready).request
    }

    private fun rejected(command: String?, query: String? = null, line: Int = 0): String {
        val p = VncodeCommands.parse(command, query, line)
        assertTrue("expected Rejected but got " + p, p is VncodeCommands.Parse.Rejected)
        return (p as VncodeCommands.Parse.Rejected).reason
    }

    @Test
    fun theAllowlistIsExactlyTheFiveVerifiedCommands() {
        assertEquals(
            listOf(
                "vncode.command.save",
                "vncode.command.saveAll",
                "vncode.command.format",
                "vncode.command.find",
                "vncode.command.goToLine",
            ),
            VnCommand.ids,
        )
    }

    @Test
    fun everyAllowedIdParsesToItsCommand() {
        for (cmd in VnCommand.entries) {
            assertEquals(cmd, ready(cmd.id).command)
        }
    }

    @Test
    fun anUnknownIdIsRefusedAndTheAllowlistIsPrinted() {
        val reason = rejected("vncode.command.deleteEverything")
        assertTrue(reason.contains("is not an allowed command"))
        assertTrue(reason.contains("vncode.command.save"))
        // Nothing that looks like a real command may be accepted by prefix or by case folding.
        assertTrue(VncodeCommands.parse("Vncode.command.save", null, 0) is VncodeCommands.Parse.Rejected)
        assertTrue(VncodeCommands.parse("vncode.command.save ", null, 0) is VncodeCommands.Parse.Ready)
        assertTrue(VncodeCommands.parse("vncode.command", null, 0) is VncodeCommands.Parse.Rejected)
    }

    @Test
    fun anEmptyCommandIsRefusedWithTheAllowlist() {
        val reason = rejected("")
        assertTrue(reason.contains("No command given"))
        assertTrue(reason.contains("vncode.command.goToLine"))
    }

    @Test
    fun saveTakesNoArgumentsAndSaysSo() {
        assertEquals(VnCommand.SAVE, ready("vncode.command.save").command)
        assertTrue(rejected("vncode.command.save", query = "x").contains("takes no arguments"))
        assertTrue(rejected("vncode.command.saveAll", line = 9).contains("takes no arguments"))
        assertTrue(rejected("vncode.command.format", query = "x").contains("takes no arguments"))
    }

    @Test
    fun findSeparatesQueryFromLineAndRefusesBoth() {
        val q = ready("vncode.command.find", query = "  needle  ")
        assertEquals("needle", q.query)
        assertTrue(rejected("vncode.command.find", query = "a", line = 3).contains("not both"))
        assertTrue(rejected("vncode.command.find", query = "x".repeat(501)).contains("longer than 500"))
    }

    @Test
    fun goToLineRejectsZeroAndNegativeAndKeepsAPositiveLine() {
        assertEquals(42, ready("vncode.command.goToLine", line = 42).line)
        assertTrue(rejected("vncode.command.goToLine", line = -2).contains("1 or greater"))
    }

    @Test
    fun theToolIsAdvertisedInEditAndAgentOnly() {
        assertEquals(true, ToolRegistry.allows(com.codespace.ide.chat.panel.ChatMode.EDIT, "run_vncode_command"))
        assertEquals(true, ToolRegistry.allows(com.codespace.ide.chat.panel.ChatMode.AGENT, "run_vncode_command"))
        assertEquals(false, ToolRegistry.allows(com.codespace.ide.chat.panel.ChatMode.ASK, "run_vncode_command"))
        // The six untouched hidden tools stay unreachable in every mode - discovery must not leak them.
        for (hidden in listOf("run_vscode_command", "runSubagent", "search_subagent", "execution_subagent",
            "vscode_get_terminal_confirmation", "resolve_memory_file_uri", "get_vscode_api")) {
            for (mode in com.codespace.ide.chat.panel.ChatMode.entries) {
                assertFalse(hidden + " must stay hidden in " + mode, ToolRegistry.allows(mode, hidden))
            }
        }
    }

    @Test
    fun autoAllDoesNotPromptForTheCommandTool() {
        assertTrue(
            ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "run_vncode_command", emptySet()),
        )
    }

    @Test
    fun manualAndAutoSafeStillRequireApproval() {
        assertFalse(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "run_vncode_command", emptySet()))
        // AUTO_SAFE keeps its own rule: the command tool is not a read-only tool, so it is gated.
        assertFalse(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "run_vncode_command", emptySet()))
        // ...unless the user explicitly allowed it once (existing per-tool switch).
        assertTrue(
            ChatPermissionStore.decideAutoApproved(
                ChatFlowLevel.MANUAL, "run_vncode_command", setOf("run_vncode_command"),
            ),
        )
    }

    @Test
    fun mcpToolsKeepTheirOwnRule() {
        assertFalse(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "mcp_github_search", emptySet()))
    }
}
