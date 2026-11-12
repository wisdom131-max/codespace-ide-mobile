package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Classification comes from the registry table, never from a description. */
class ToolEffectIndexTest {

    @Test fun writersAreSideEffecting() {
        listOf("write_file", "create_file", "run_command", "git_commit", "zip_extract", "manage_todo_list")
            .forEach { assertEquals(it, ToolEffect.SIDE_EFFECTING, ToolEffectIndex.effectOf(it)) }
    }

    @Test fun readersAreReadOnly() {
        listOf("read_file", "grep_search", "list_dir", "checksum", "zip_list")
            .forEach { assertEquals(it, ToolEffect.READ_ONLY, ToolEffectIndex.effectOf(it)) }
    }

    @Test fun anUnknownToolDefaultsToSideEffecting() {
        assertEquals(ToolEffect.SIDE_EFFECTING, ToolEffectIndex.effectOf("some_new_tool"))
    }
}
