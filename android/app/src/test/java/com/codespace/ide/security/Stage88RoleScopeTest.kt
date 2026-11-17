package com.codespace.ide.security

import com.codespace.ide.chat.session.HandoffGuard
import com.codespace.ide.agent.subagents.SubagentRoles
import com.codespace.ide.chat.panel.ToolCategory
import com.codespace.ide.chat.panel.ToolMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.8 - a role cannot be handed more than it is allowed to hold.
 *
 * These tests use the real catalogue and the real rule. The rule is deliberately a separate
 * function so it can be exercised with a destructive tool even though no read-only role lists one
 * today: the hole was in the mechanism, not in today's data, and a data-only test would not catch
 * the next catalogue edit.
 */
class Stage88RoleScopeTest {

    private fun meta(destructive: Boolean) =
        ToolMeta(ToolCategory.FILES, listOf("x"), destructive = destructive)

    @Test
    fun aReadOnlyRoleMayNeverHoldADestructiveTool() {
        val readOnly = SubagentRoles.Role.byId("planner")!!
        assertFalse(readOnly.mayWrite)
        assertFalse(SubagentRoles.mayInclude(readOnly, meta(destructive = true)))
        assertTrue(SubagentRoles.mayInclude(readOnly, meta(destructive = false)))
    }

    @Test
    fun aWritingRoleMayHoldBoth() {
        val coder = SubagentRoles.Role.byId("coder")!!
        assertTrue(coder.mayWrite)
        assertTrue(SubagentRoles.mayInclude(coder, meta(destructive = true)))
        assertTrue(SubagentRoles.mayInclude(coder, meta(destructive = false)))
    }

    @Test
    fun anUnclassifiableToolIsWithheldFromAReadOnlyRole() {
        val readOnly = SubagentRoles.Role.byId("review")!!
        assertFalse(SubagentRoles.mayInclude(readOnly, null))
        val coder = SubagentRoles.Role.byId("coder")!!
        assertTrue(SubagentRoles.mayInclude(coder, null))
    }

    @Test
    fun everyReadOnlyRoleInTheCatalogueListsOnlyReadOnlyTools() {
        // A catalogue edit that put write_file in a read-only role's explicit list would break the
        // invariant even though the runtime now also refuses it. Catch it where it is written.
        for (role in SubagentRoles.Role.entries) {
            if (role.mayWrite) continue
            for (name in role.explicit) {
                assertFalse(
                    role.id + " is read-only but lists " + name,
                    name.startsWith("write") || name.startsWith("delete") || name.startsWith("git_push"),
                )
            }
        }
    }

    @Test
    fun anUnknownRoleIsRefusedByName() {
        val result = SubagentRoles.scope("no_such_role", setOf("read_file")) { null }
        assertTrue(result is SubagentRoles.Result.Refused)
        assertTrue((result as SubagentRoles.Result.Refused).reason.contains("Unknown role"))
    }

    @Test
    fun aRoleWithNothingItMayUseIsRefusedRatherThanSilentlyEmptied() {
        val result = SubagentRoles.scope("planner", setOf("write_file")) { meta(destructive = true) }
        assertTrue(result is SubagentRoles.Result.Refused)
    }

    @Test
    fun aReadOnlyRoleWithAReadableToolGetsAScopeThatCannotWrite() {
        val result = SubagentRoles.scope("review", setOf("read_file")) { meta(destructive = false) }
        assertTrue(result is SubagentRoles.Result.Ok)
        val scope = (result as SubagentRoles.Result.Ok).scope
        assertFalse(scope.canWrite)
        assertEquals(setOf("read_file"), scope.tools)
    }

    @Test
    fun aDestructiveToolIsWithheldAndReportedRatherThanDropped() {
        val result = SubagentRoles.scope("review", setOf("read_file", "write_file")) { name ->
            meta(destructive = name == "write_file")
        }
        val scope = (result as SubagentRoles.Result.Ok).scope
        assertEquals(setOf("read_file"), scope.tools)
        assertEquals(setOf("write_file"), scope.withheld)
    }

    /**
     * 8.7: a successor session is reused when it is still empty, and a fresh one is created once it
     * holds a turn. Repeated taps must not manufacture a chain of empty successors.
     */
    @Test
    fun aRepeatedHandoffReusesTheEmptySuccessorInsteadOfCreatingAnother() {
        val first = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = listOf("c1" to "Child"),
            rowsInChild = { 0 },
            labelFor = { it },
        )
        val second = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = listOf("c1" to "Child"),
            rowsInChild = { 0 },
            labelFor = { it },
        )
        assertEquals(first, second)
    }

    @Test
    fun aSuccessorThatAlreadyHoldsATurnIsNotReused() {
        val decision = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = listOf("c1" to "Child"),
            rowsInChild = { 3 },
            labelFor = { it },
        )
        val fresh = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = emptyList(),
            rowsInChild = { 0 },
            labelFor = { it },
        )
        assertEquals(fresh, decision)
    }
}
