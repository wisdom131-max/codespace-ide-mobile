package com.codespace.ide.chat.panel

import com.codespace.ide.agent.ChatFlowLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 9 (2026-10-31): the approvals entry states the current level and offers exactly the three
 * levels the gate honours. A level the gate does not implement must not be offered, and every
 * row must say what it lets through, because the label alone hides the consequence.
 */
class ApprovalLevelSpecTest {

    @Test
    fun `every gate level has exactly one row`() {
        assertEquals(ChatFlowLevel.entries.toSet(), ApprovalLevelSpec.rows.map { it.level }.toSet())
        assertEquals(ChatFlowLevel.entries.size, ApprovalLevelSpec.rows.size)
    }

    @Test
    fun `the menu label carries the current level, not a generic word`() {
        assertEquals("Approvals: Ask every time", ApprovalLevelSpec.menuLabel(ChatFlowLevel.MANUAL))
        assertEquals("Approvals: Auto for safe tools", ApprovalLevelSpec.menuLabel(ChatFlowLevel.AUTO_SAFE))
        assertEquals("Approvals: Auto for everything", ApprovalLevelSpec.menuLabel(ChatFlowLevel.AUTO_ALL))
    }

    @Test
    fun `each row explains what it actually allows`() {
        for (row in ApprovalLevelSpec.rows) {
            assertTrue(row.label.isNotBlank())
            assertTrue(row.detail.length > 20)
        }
        assertTrue(ApprovalLevelSpec.row(ChatFlowLevel.MANUAL).detail.contains("wait"))
        assertTrue(ApprovalLevelSpec.row(ChatFlowLevel.AUTO_SAFE).detail.contains("still asks"))
        assertTrue(ApprovalLevelSpec.row(ChatFlowLevel.AUTO_ALL).detail.contains("No approval"))
    }

    @Test
    fun `the overflow menu shows the live level once the panel passes one`() {
        val items = ChatOverflowSpec.items(
            canUndo = false,
            canRedo = false,
            viewByDefault = false,
            canMoveToEditor = true,
            approvalsLabel = ApprovalLevelSpec.menuLabel(ChatFlowLevel.AUTO_SAFE),
        )
        val approvals = items.single { it.action == ChatOverflowAction.MANAGE_APPROVALS }
        assertTrue(approvals.enabled)
        assertEquals("Approvals: Auto for safe tools", approvals.label)
    }
}
