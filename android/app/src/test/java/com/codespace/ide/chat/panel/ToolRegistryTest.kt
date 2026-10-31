package com.codespace.ide.chat.panel

import com.codespace.ide.agent.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 parity suite (owner approval 2026-10-30).
 *
 * The defect being locked down: 32 tools were registered and executable while the chat
 * advertised 5, and nothing failed when those two facts diverged. Every test here is a
 * tripwire on that divergence, not a description of a happy path.
 */
class ToolRegistryTest {

    private val readOnlyWave = setOf(
        "read_file", "list_files", "search_files",
        "git_status", "git_diff", "detect_secrets",
        "read_memory", "list_tasks", "read_entities", "list_connectors",
        "get_errors", "get_changed_files", "request_connector",
    )

    // --- registration parity -------------------------------------------------------

    @Test
    fun theRegistryCoversEveryRegisteredToolExactlyOnce() {
        val registered = AgentTools.toolNames()
        assertEquals("registry and executor must agree on the registered set", registered.toSet(), ToolRegistry.names().toSet())
        assertEquals(36, ToolRegistry.names().size)
        assertEquals("no duplicate ids", 36, ToolRegistry.names().toSet().size)
    }

    @Test
    fun everyRegisteredToolIsEitherExposedOrHasAWrittenReason() {
        for (spec in ToolRegistry.ALL) {
            if (spec.modes.isEmpty()) {
                assertNotNull("hidden tool needs a reason: " + spec.name, spec.hiddenReason)
                assertTrue("hidden reason must say something: " + spec.name, spec.hiddenReason!!.isNotBlank())
            } else {
                assertTrue("exposed tool needs a description: " + spec.name, spec.description.isNotBlank())
            }
        }
    }

    @Test
    fun theHiddenSetIsExactlyTheToolsheldBackFromTheModel() {
        val hidden = ToolRegistry.hidden().map { it.name }
        assertEquals(16, hidden.size)
        assertTrue(hidden.containsAll(listOf("connect_service", "use_connector", "git_branch", "plan", "delete_entity", "delete_memory")))
        assertEquals("no name may sit outside exposed + hidden", 36, hidden.size + exposedInAnyMode().size)
    }

    // --- exposure per mode ---------------------------------------------------------

    private fun exposedInAnyMode(): Set<String> =
        ChatMode.values().flatMap { ToolRegistry.exposedNames(it) }.toSet()

    @Test
    fun askExposesTheReadOnlyWaveAndNothingElse() {
        assertEquals(readOnlyWave, ToolRegistry.exposedNames(ChatMode.ASK).toSet())
    }

    @Test
    fun editAddsWritesButNeverExecution() {
        val edit = ToolRegistry.exposedNames(ChatMode.EDIT).toSet()
        assertEquals(readOnlyWave + setOf("write_file", "replace_string_in_file", "create_directory"), edit)
        assertTrue("Edit mode needs a targeted edit tool, not only whole-file writes", edit.contains("replace_string_in_file"))
        assertFalse("Edit must never be offered a command", edit.contains("run_command"))
    }

    @Test
    fun agentAddsExecutionAndStillRefusesSecrets() {
        val agent = ToolRegistry.exposedNames(ChatMode.AGENT).toSet()
        assertEquals(
            readOnlyWave + setOf(
                "write_file", "replace_string_in_file", "create_directory",
                "run_command", "web_fetch", "save_memory", "install_package",
            ),
            agent,
        )
        assertFalse(agent.contains("save_secret"))
        assertFalse(agent.contains("get_secret"))
    }

    @Test
    fun allowsMatchesExposureForEveryToolInEveryMode() {
        for (spec in ToolRegistry.ALL) {
            for (mode in ChatMode.values()) {
                assertEquals(
                    "allows and exposure disagree for " + spec.name + " in " + mode,
                    mode in spec.modes,
                    ToolRegistry.allows(mode, spec.name),
                )
            }
        }
    }

    // --- schemas -------------------------------------------------------------------

    @Test
    fun everyExposedToolHasAValidJsonSchema() {
        for (mode in ChatMode.values()) {
            for (spec in ToolRegistry.exposed(mode)) {
                val s = spec.schema
                assertTrue("schema must be an object: " + spec.name, s.startsWith("{\"type\":\"object\""))
                assertTrue("schema must declare properties: " + spec.name, s.contains("\"properties\""))
                assertEquals(
                    "unbalanced braces in schema: " + spec.name,
                    s.count { it == '{' },
                    s.count { it == '}' },
                )
                assertTrue("schema must be closed: " + spec.name, s.endsWith("}"))
            }
        }
    }

    @Test
    fun everySchemaDeclaresTheArgumentsTheExecutorActuallyReads() {
        // Mirrors the argument reads at AgentTools.kt:172-215. If a branch starts reading
        // a new key and the schema is not updated, this fails: the model would be guessing.
        // required arguments first, optional second: a tool with only optional inputs
        // legitimately has no "required" list, and that is not a schema defect.
        val expected = mapOf(
            "run_command" to (listOf("command") to listOf("workdir")),
            "read_file" to (listOf("path") to emptyList<String>()),
            "write_file" to (listOf("path", "content") to emptyList<String>()),
            "list_files" to (listOf("path") to emptyList<String>()),
            "search_files" to (listOf("path", "pattern") to emptyList<String>()),
            "git_status" to (emptyList<String>() to listOf("repo_dir")),
            "git_diff" to (emptyList<String>() to listOf("staged", "repo_dir")),
            "detect_secrets" to (emptyList<String>() to listOf("text", "path")),
            "read_memory" to (emptyList<String>() to emptyList<String>()),
            "list_tasks" to (emptyList<String>() to emptyList<String>()),
            "read_entities" to (listOf("entity") to listOf("filter")),
            "web_fetch" to (listOf("url") to listOf("headers")),
            "save_memory" to (listOf("key", "value") to emptyList<String>()),
            "install_package" to (listOf("manager", "package") to listOf("project_dir")),
            "request_connector" to (listOf("service") to emptyList<String>()),
            "replace_string_in_file" to (listOf("path", "old_string", "new_string") to listOf("replace_all")),
            "get_errors" to (emptyList<String>() to listOf("path")),
            "create_directory" to (listOf("path") to emptyList<String>()),
            "get_changed_files" to (emptyList<String>() to listOf("repo_dir")),
            "list_connectors" to (emptyList<String>() to emptyList<String>()),
        )
        // Two directions, each with its own meaning: an expectation for a tool that is not
        // registered is a stale lie, and an exposed tool with no expectation means the model is
        // guessing at argument names. Hidden tools need neither, because their schema is never
        // shown to the model.
        val stale = expected.keys - ToolRegistry.names().toSet()
        assertTrue("expectations for unregistered tools: " + stale, stale.isEmpty())
        val undocumented = exposedInAnyMode() - expected.keys
        assertTrue("exposed tool without an argument expectation: " + undocumented, undocumented.isEmpty())
        for ((name, pair) in expected) {
            val (required, optional) = pair
            val spec = ToolRegistry.spec(name)
            assertNotNull("missing spec: " + name, spec)
            val schema = spec!!.schema
            if (required.isEmpty()) {
                assertFalse("$name declares no required arguments but the schema says otherwise",
                    schema.contains("\"required\""))
            } else {
                assertTrue("$name must declare its required arguments", schema.contains("\"required\""))
                for (a in required) {
                    assertTrue("schema for $name omits required argument '$a'", schema.contains("\"" + a + "\""))
                }
            }
            for (a in optional) {
                assertTrue("schema for $name omits optional argument '$a'", schema.contains("\"" + a + "\""))
            }
        }
    }

    // --- forbidden tools -----------------------------------------------------------

    @Test
    fun secretsAreNeverExposedInAnyMode() {
        for (mode in ChatMode.values()) {
            for (secret in listOf("save_secret", "get_secret")) {
                assertFalse(
                    secret + " must never be advertised in " + mode,
                    ToolRegistry.exposedNames(mode).contains(secret),
                )
                assertFalse(secret + " must never be allowed in " + mode, ToolRegistry.allows(mode, secret))
            }
        }
    }

    @Test
    fun destructiveToolsAreNeverExposedInAnyMode() {
        for (mode in ChatMode.values()) {
            // install_package moved to Agent-only on 2026-10-31 (VS Code parity: it exposes
            // InstallExtension). It is still refused in Ask and Edit, so the guard below checks
            // those two modes for it explicitly rather than dropping it from all checking.
            for (dangerous in listOf("git_pull_rebase", "delete_entity", "upload_file", "delete_memory")) {
                assertFalse(
                    dangerous + " must never be advertised in " + mode,
                    ToolRegistry.allows(mode, dangerous),
                )
            }
        }
    }

    @Test
    fun stateChangingMemoryIsOnlyOfferedInAgentMode() {
        // 2026-10-31: VS Code exposes a Memory tool, so saving is offered where its approval
        // gate exists. Reading stays available everywhere; deleting is never offered.
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "save_memory"))
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "save_memory"))
        assertFalse(ToolRegistry.allows(ChatMode.EDIT, "save_memory"))
        for (mode in ChatMode.values()) {
            assertFalse("deleting a memory has no undo", ToolRegistry.allows(mode, "delete_memory"))
        }
    }

    @Test
    fun installIsRefusedOutsideAgentMode() {
        // High blast radius, so the weaker modes must not be able to offer it even though
        // Agent mode now can.
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "install_package"))
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "install_package"))
        assertFalse(ToolRegistry.allows(ChatMode.EDIT, "install_package"))
    }

    @Test
    fun theVSCodeParityToolsAreWiredIntoTheRightModes() {
        // replace_string_in_file / create_directory replace a whole-file write, so they follow
        // the write modes; get_errors and get_changed_files are reads, so every mode has them.
        for (mode in listOf(ChatMode.EDIT, ChatMode.AGENT)) {
            assertTrue(ToolRegistry.allows(mode, "replace_string_in_file"))
            assertTrue(ToolRegistry.allows(mode, "create_directory"))
        }
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "replace_string_in_file"))
        for (mode in ChatMode.values()) {
            assertTrue(ToolRegistry.allows(mode, "get_errors"))
            assertTrue(ToolRegistry.allows(mode, "get_changed_files"))
        }
    }

    @Test
    fun theConnectorCardToolIsReachableNowThatItRaisesConsent() {
        // It only raises a consent card; the external step still needs the user's tap.
        for (mode in ChatMode.values()) {
            assertTrue(ToolRegistry.allows(mode, "request_connector"))
        }
        assertFalse("starting OAuth without a card stays blocked", ToolRegistry.allows(ChatMode.AGENT, "connect_service"))
    }

    @Test
    fun webEgressIsAgentOnly() {
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "web_fetch"))
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "web_fetch"))
        assertFalse(ToolRegistry.allows(ChatMode.EDIT, "web_fetch"))
    }

    @Test
    fun thePlanStubStaysHiddenAndIsRefused() {
        assertFalse(ToolRegistry.allows(ChatMode.AGENT, "plan"))
        assertTrue(ToolRegistry.spec("plan")!!.hiddenReason!!.contains("Stub"))
        val call = """<tool>{"name":"plan","arguments":{"steps":[]}}</tool>"""
        assertTrue(AgentToolWire.decide(ChatMode.AGENT, call, 1) is ToolDecision.Refused)
    }

    // --- prompt --------------------------------------------------------------------

    @Test
    fun theProtocolCarriesDescriptionsAndSchemasForTheModeOnly() {
        val ask = AgentToolWire.protocol(ChatMode.ASK)
        assertTrue(ask.contains("read_file"))
        assertTrue("schemas must reach the model", ask.contains("\"properties\""))
        assertFalse(ask.contains("write_file"))
        assertFalse(ask.contains("run_command"))

        val edit = AgentToolWire.protocol(ChatMode.EDIT)
        assertTrue(edit.contains("write_file"))
        assertFalse("offering a command tool to Edit then refusing it is a trap", edit.contains("run_command"))

        val agent = AgentToolWire.protocol(ChatMode.AGENT)
        assertTrue(agent.contains("run_command"))
        for (hidden in listOf("save_secret", "get_secret", "use_connector", "plan", "install_extension")) {
            assertFalse("hidden tool leaked into the prompt: " + hidden, agent.contains(hidden))
        }
    }
}
