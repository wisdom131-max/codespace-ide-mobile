package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.codespace.ide.agent.ChatFlowLevel
import org.junit.Test

/**
 * PHASE 4a regression: the review card must not depend on a tool being one of three remembered
 * names, and Ask must refuse writes without having to filter the offer first.
 */
class WritePolicyTest {

    @Test
    fun everyFileWriterIsStagedInEditMode() {
        val writers = listOf(
            "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
            "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path",
        )
        for (w in writers) {
            assertEquals("Edit must stage $w", WritePolicy.Decision.STAGE, WritePolicy.decide(ChatMode.EDIT, w))
        }
    }

    @Test
    fun theThreeOldNamesAreNotSpecial() {
        // The bug: only these three were staged, the rest wrote through.
        assertEquals(WritePolicy.Decision.STAGE, WritePolicy.decide(ChatMode.EDIT, "write_file"))
        assertEquals(WritePolicy.Decision.STAGE, WritePolicy.decide(ChatMode.EDIT, "edit_file"))
        assertEquals(WritePolicy.Decision.STAGE, WritePolicy.decide(ChatMode.EDIT, "apply_patch"))
    }

    @Test
    fun askRefusesEveryWrite() {
        val writes = listOf("write_file", "edit_file", "apply_patch", "delete_path", "run_command", "git_commit")
        for (w in writes) {
            assertEquals("Ask must refuse $w", WritePolicy.Decision.REFUSE, WritePolicy.decide(ChatMode.ASK, w))
        }
    }

    @Test
    fun askStillReads() {
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.ASK, "read_file"))
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.ASK, "grep_search"))
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.ASK, "checksum"))
    }

    @Test
    fun anUnknownToolIsDangerousEverywhere() {
        // ToolEffectIndex treats an unlisted name as SIDE_EFFECTING; the gate inherits that.
        assertEquals(WritePolicy.Decision.REFUSE, WritePolicy.decide(ChatMode.ASK, "some_future_tool"))
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.AGENT, "some_future_tool"))
    }

    @Test
    fun agentKeepsDirectWrites() {
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.AGENT, "write_file"))
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.AGENT, "edit_file"))
    }

    @Test
    fun aShellCommandIsNotABlanketProposal() {
        // Staging a command as a diff would be a lie: it keeps its approval gate instead.
        assertEquals(WritePolicy.Decision.ALLOW, WritePolicy.decide(ChatMode.EDIT, "run_command"))
        assertTrue(!WritePolicy.isFileWriter("run_command"))
    }

    @Test
    fun theRefusalSaysWhatDidNotHappen() {
        val msg = WritePolicy.refusalMessage("edit_file")
        assertTrue(msg.contains("read-only"))
        assertTrue(msg.contains("nothing on disk changed"))
        assertTrue(msg.contains("edit_file"))
    }

    // PHASE 4b: the approval level decides the diff, not the mode alone.

    @Test
    fun autoAllInEditWritesDirectlyWithNoDiff() {
        assertEquals(
            WritePolicy.Decision.ALLOW,
            WritePolicy.decide(ChatMode.EDIT, "write_file", ChatFlowLevel.AUTO_ALL),
        )
        assertEquals(
            WritePolicy.Decision.ALLOW,
            WritePolicy.decide(ChatMode.EDIT, "apply_patch", ChatFlowLevel.AUTO_ALL),
        )
    }

    @Test
    fun autoSafeInEditStillShowsTheProposal() {
        assertEquals(
            WritePolicy.Decision.STAGE,
            WritePolicy.decide(ChatMode.EDIT, "edit_file", ChatFlowLevel.AUTO_SAFE),
        )
    }

    @Test
    fun alwaysAskInEditStages() {
        assertEquals(
            WritePolicy.Decision.STAGE,
            WritePolicy.decide(ChatMode.EDIT, "delete_path", ChatFlowLevel.MANUAL),
        )
    }

    @Test
    fun autoAllDoesNotMakeAskWritable() {
        assertEquals(
            WritePolicy.Decision.REFUSE,
            WritePolicy.decide(ChatMode.ASK, "write_file", ChatFlowLevel.AUTO_ALL),
        )
    }

    @Test
    fun autoAllDoesNotTurnAShellCommandIntoADiff() {
        assertEquals(
            WritePolicy.Decision.ALLOW,
            WritePolicy.decide(ChatMode.EDIT, "run_command", ChatFlowLevel.AUTO_SAFE),
        )
    }
}
