package com.codespace.ide.agent

import com.codespace.ide.data.ConnectorsApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inventory the agent reads. The failure this pins: GitLab printed as a bare "[CONNECTED]"
 * line left the model hunting for a `gitlab_*` tool, finding none, and telling the user it could
 * not create a repository. A connected service has to state what it answers to.
 */
class ConnectorInventoryTest {

    private fun status(id: String, connected: Boolean, configured: Boolean = true) =
        ConnectorsApiClient.ConnectorStatus(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            connected = connected,
            configured = configured,
            scope = null,
        )

    @Test
    fun `a connected gitlab prints the create-repo call`() {
        val text = AgentConnectorManager.formatStatuses(listOf(status("gitlab", connected = true)))
        assertTrue(text.contains("[CONNECTED] Gitlab (gitlab)"))
        assertTrue(text, text.contains("POST /projects"))
        assertTrue(text, text.contains("CREATE A REPOSITORY"))
        assertTrue(text, text.contains("use_connector"))
    }

    @Test
    fun `a service that is not connected gets no recipes`() {
        val text = AgentConnectorManager.formatStatuses(
            listOf(status("gitlab", connected = false), status("gmail", connected = false, configured = false))
        )
        // The recipes are the answer to "what can I do"; showing them for a service nobody signed
        // in to would invite a call that can only fail.
        assertFalse(text, text.contains("POST /projects"))
        assertFalse(text, text.contains("GET /messages"))
        assertTrue(text, text.contains("[not set up by owner yet]"))
    }

    @Test
    fun `the footer says there are no per-provider tools`() {
        val text = AgentConnectorManager.formatStatuses(emptyList())
        assertTrue(text, text.contains("no per-provider"))
        assertTrue(text, text.contains("not a gitlab_* tool"))
    }

    @Test
    fun `an empty inventory tells the agent how to ask for a connection`() {
        val text = AgentConnectorManager.formatStatuses(emptyList())
        assertTrue(text, text.contains("Nothing is connected yet"))
        assertTrue(text, text.contains("request_connector"))
    }

    @Test
    fun `recipe lookup is case and whitespace tolerant`() {
        assertTrue(ConnectorRecipes.has(" GitLab "))
        assertEquals(ConnectorRecipes.forService("gitlab"), ConnectorRecipes.forService("GITLAB"))
    }

    @Test
    fun `an unknown service returns nothing rather than a guess`() {
        assertFalse(ConnectorRecipes.has("myspace"))
        assertEquals("", ConnectorRecipes.usageLines("myspace"))
    }

    @Test
    fun `every listed service has at least one recipe`() {
        // A service the hub can show but the agent has no recipe for is the same bug again.
        val services = AgentConnectorManager.SERVICES
        val missing = services.filter { !ConnectorRecipes.has(it) }
        assertEquals("services without a recipe: " + missing, emptyList<String>(), missing)
    }
}
