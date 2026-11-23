package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTitlesTest {

    @Test
    fun namesAnEditAsEditingFiles() {
        assertEquals("Editing files", ThinkingTitles.titleFor("write_file: 12 lines"))
        assertEquals("Editing files", ThinkingTitles.titleFor("apply_patch(path/to/File.kt)"))
        assertEquals("Editing files", ThinkingTitles.titleFor("replace_string_in_file"))
    }

    @Test
    fun namesReadsAndSearchesAsReadingTheProject() {
        assertEquals("Reading the project", ThinkingTitles.titleFor("read_file: 42 lines"))
        assertEquals("Reading the project", ThinkingTitles.titleFor("grep_search: 7 matches"))
        assertEquals("Reading the project", ThinkingTitles.titleFor("list_files"))
    }

    @Test
    fun namesCommandsTestsAndWeb() {
        assertEquals("Running commands", ThinkingTitles.titleFor("run_command: gradle build"))
        assertEquals("Running tests", ThinkingTitles.titleFor("test_search: 3 found"))
        assertEquals("Searching the web", ThinkingTitles.titleFor("web_search: kotlin coroutines"))
    }

    @Test
    fun theHeaderNamesTheLatestStepNotTheFirst() {
        // The rule VS Code states: a section whose title names an older step looks stuck.
        assertEquals(
            "Editing files",
            ThinkingTitles.runningTitle(listOf("read_file: 42 lines", "write_file: 12 lines")),
        )
    }

    @Test
    fun emptyOrUnknownFallsBackToThinking() {
        assertEquals("Thinking", ThinkingTitles.runningTitle(emptyList()))
        assertEquals("Thinking", ThinkingTitles.titleFor(""))
        assertEquals("Thinking", ThinkingTitles.titleFor("something_unmapped"))
        assertEquals("Thinking", ThinkingTitles.runningTitle(listOf("", "  ")))
    }

    @Test
    fun blankStepsAreSkippedWhenChoosingTheTitle() {
        assertEquals(
            "Running commands",
            ThinkingTitles.runningTitle(listOf("write_file", "", "run_command")),
        )
    }
}
