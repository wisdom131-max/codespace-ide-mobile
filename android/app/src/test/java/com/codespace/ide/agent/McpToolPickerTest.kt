package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 4.5: relevance, routing, staleness, and an approval gate nobody can rank away. */
class McpToolPickerTest {

    private val github = McpToolPicker.Candidate("github", "create_issue", "Open a new issue in a repository")
    private val slack = McpToolPicker.Candidate("slack", "post_message", "Post a message to a channel")
    private val noise = McpToolPicker.Candidate("jira", "create_board", "Create a board")

    @Test fun theRelevantServerWinsForTheTask() {
        val pick = McpToolPicker.choose("create issue for this bug", listOf(slack, github, noise))
        assertEquals(github.tool, pick?.candidate?.tool)
    }

    @Test fun routingKeepsTheOwningServer() {
        val pick = McpToolPicker.choose("create issue for this bug", listOf(github))
        assertEquals("github", pick?.candidate?.server)
    }

    @Test fun everyPickCarriesTheApprovalRequirement() {
        val picks = McpToolPicker.rank("post a message", listOf(slack, github))
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.all { it.requiresApproval })
        assertTrue(McpToolPicker.choose("post a message", listOf(slack))!!.requiresApproval)
    }

    @Test fun aDisconnectedServerIsNotOffered() {
        val down = github.copy(connected = false)
        assertTrue(McpToolPicker.rank("create issue", listOf(down)).isEmpty())
        assertTrue(McpToolPicker.exclusions(listOf(down)).first().contains("not connected"))
    }

    @Test fun aStaleDefinitionIsNotOffered() {
        val old = slack.copy(stale = true)
        assertTrue(McpToolPicker.rank("post a message", listOf(old)).isEmpty())
        assertTrue(McpToolPicker.exclusions(listOf(old)).first().contains("stale"))
    }

    @Test fun unrelatedToolsAreNotLoadedIntoThePrompt() {
        // A shared generic verb is not relevance: "create a board" does not answer an issue request.
        val unrelated = McpToolPicker.Candidate("jira", "create_board", "Create a board")
        assertTrue(McpToolPicker.rank("issue tracker", listOf(unrelated)).isEmpty())
    }

    @Test fun rankingIsDeterministicAndBounded() {
        val many = (1..20).map { McpToolPicker.Candidate("s$it", "create_issue", "create issue") }
        val picks = McpToolPicker.rank("create issue", many, limit = 5)
        assertEquals(5, picks.size)
        assertEquals(picks.map { it.candidate.server }, picks.map { it.candidate.server }.sorted())
    }

    @Test fun anEmptyRequestChoosesNothingRatherThanGuessing() {
        assertTrue(McpToolPicker.rank("", listOf(github, slack)).isEmpty())
        assertEquals(null, McpToolPicker.choose("   ", listOf(github)))
    }

    @Test fun aPickIsNeverMarkedAutoRunnable() {
        val pick = McpToolPicker.rank("post a message", listOf(slack)).first()
        assertTrue(pick.requiresApproval)
        assertFalse(pick.requiresApproval.not())
        assertNotNull(pick.candidate.server)
    }
}
