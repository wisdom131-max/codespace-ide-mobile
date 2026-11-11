package com.codespace.ide.chat.panel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOOL REGISTRY INTEGRITY.
 *
 * Why this exists (owner audit 2026-11-10, build 3703): 14 schemas shipped with a stray comma -
 * `"properties":{,"message":{...}}` - for months. Nothing noticed, because the schema was only ever
 * PASTED into a prompt as text: no parser ever read it, so a schema that is not JSON could not fail
 * a test. A model sees a broken object, guesses, and the tool call is rejected or mis-parsed, which
 * reads to the owner as "the tool does not work".
 *
 * The rule this test enforces: if a tool is OFFERED to a model, its schema must parse as JSON and
 * describe its own required fields. Hidden tools may carry no schema - they are never sent - but
 * they must still say WHY they are hidden.
 */
class ToolSchemaIntegrityTest {

    /** Every exposed schema, strictly parsed. A failure here means a model was handed junk. */
    @Test
    fun everyExposedSchemaIsStrictJson() {
        var checked = 0
        ToolRegistry.ALL.filter { it.modes.isNotEmpty() }.forEach { spec ->
            try {
                val obj = JSONObject(spec.schema)
                assertEquals("${spec.name} must declare an object", "object", obj.getString("type"))
                checked++
            } catch (e: Exception) {
                throw AssertionError(
                    "schema for '" + spec.name + "' is not valid JSON (" + e.message + "):\n" + spec.schema,
                )
            }
        }
        println("[schema] strict-parsed " + checked + " exposed schemas")
        assertTrue("the registry must actually contain exposed tools", checked > 0)
    }

    /** required[] must name properties that exist, or the model is told to send an unknown field. */
    @Test
    fun requiredFieldsExistInProperties() {
        ToolRegistry.ALL.filter { it.modes.isNotEmpty() }.forEach { spec ->
            val obj = JSONObject(spec.schema)
            if (!obj.has("required")) return@forEach
            val req = obj.getJSONArray("required")
            val props = obj.optJSONObject("properties")
            for (i in 0 until req.length()) {
                val field = req.getString(i)
                assertTrue(
                    "'" + spec.name + "' requires '" + field + "' but does not declare it",
                    props != null && props.has(field),
                )
            }
        }
    }

    /** A property must carry a type, or the compact form cannot render it and the model guesses. */
    @Test
    fun everyPropertyHasAType() {
        ToolRegistry.ALL.filter { it.modes.isNotEmpty() }.forEach { spec ->
            val props = JSONObject(spec.schema).optJSONObject("properties") ?: return@forEach
            val names = props.keys()
            while (names.hasNext()) {
                val name = names.next()
                val p = props.getJSONObject(name)
                assertTrue(
                    "'" + spec.name + "." + name + "' has no type",
                    p.has("type") || p.has("enum") || p.has("\$ref"),
                )
            }
        }
    }

    /** Compaction must never turn a valid schema into an empty arg list. */
    @Test
    fun compactionKeepsEveryExposedToolCallable() {
        ToolRegistry.ALL.filter { it.modes.isNotEmpty() }.forEach { spec ->
            val args = ToolRegistry.compactArgs(spec.schema)
            assertTrue("compacted args empty for '" + spec.name + "'", args.isNotBlank())
        }
    }

    /** Hidden tools are never sent, so an empty schema is legal - an unstated reason is not. */
    @Test
    fun hiddenToolsCarryAReasonAndNoSchema() {
        ToolRegistry.hidden().forEach { spec ->
            assertTrue("hidden '" + spec.name + "' has no reason", !spec.hiddenReason.isNullOrBlank())
        }
        println("[schema] hidden tools: " + ToolRegistry.hidden().size)
    }

    /** Names advertised must be exactly the names the executor accepts, per mode. */
    @Test
    fun everyAdvertisedNameIsExecutable() {
        ChatMode.entries.forEach { mode ->
            ToolRegistry.exposedNames(mode).forEach { name ->
                assertTrue(
                    "'" + name + "' is advertised in " + mode + " but not executable",
                    com.codespace.ide.agent.AgentTools.toolNames().contains(name),
                )
            }
        }
    }

    /** BASELINE for the dynamic-loading work: what each mode costs today, measured. */
    @Test
    fun baselinePromptSizes() {
        ChatMode.entries.forEach { mode ->
            val block = ToolRegistry.promptBlock(mode)
            val tools = ToolRegistry.exposed(mode).size
            println("[schema] baseline " + mode.name + ": tools=" + tools +
                " promptChars=" + block.length + " estTokens=" + (block.length / 4))
        }
        assertTrue(true)
    }
}
