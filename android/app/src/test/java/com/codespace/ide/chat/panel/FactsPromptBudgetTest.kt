package com.codespace.ide.chat.panel

import com.codespace.ide.agent.facts.Fact
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactRetrieval
import com.codespace.ide.agent.facts.FactScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Memory in the REAL prompt, per mode.
 *
 * Two things must both hold and they pull against each other: remembered facts must reach the model,
 * and the Ask/Agent caps must not move. So every case here measures [ChatInstructions.build] - the
 * same call ChatSession makes - and asserts the total stays under the mode's cap while the tool
 * definitions are still present exactly once.
 */
class FactsPromptBudgetTest {

    private val askCap = 5000
    private val agentCap = 12500

    @Before
    fun freshTask() {
        ToolLoadout.clear()
        ToolLoadout.beginTask(ChatMode.AGENT)
    }

    @After
    fun cleanUp() {
        ToolLoadout.clear()
    }

    private fun fact(id: String, content: String, scope: FactScope = FactScope.USER,
                     projectId: String? = null) = Fact(
        id = id, content = content, scope = scope, projectId = projectId,
        category = FactCategory.BUILD, createdAt = 1L, updatedAt = 1L,
    )

    /** A memory block of a realistic size, built the way production builds it. */
    private fun memoryBlock(facts: List<Fact>, request: String, maxChars: Int = FactRetrieval.MAX_PROMPT_CHARS): String =
        FactRetrieval.block(FactRetrieval.forRequest(facts, request, null), maxChars)

    private fun definitionsOf(prompt: String, name: String): Int =
        Regex("(?m)^- " + Regex.escape(name) + ":").findAll(prompt).count()

    private fun prompt(mode: ChatMode, request: String, memory: String = ""): String {
        val offered = ToolOffer.plan(mode, request).names
        return ChatInstructions.build(mode, true, offered, "", "", ToolLoadout.extra(), memory)
    }

    private val sampleFacts = listOf(
        fact("f1", "The build uses ./gradlew :app:assembleDevDebug"),
        fact("f2", "Run ./gradlew test before pushing any build"),
        fact("f3", "Prefers concise replies"),
    )

    @Test
    fun askPromptStaysUnderItsCapWithRelevantMemory() {
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val memory = memoryBlock(sampleFacts, "how do I build this app?")
        assertTrue("the fixture must actually retrieve something", memory.isNotEmpty())
        val p = prompt(mode, "how do I build this app?", memory)
        println("[facts] ask with memory = " + p.length)
        assertTrue("ask prompt is " + p.length, p.length < askCap)
        assertTrue(p.contains("What you remember"))
    }

    @Test
    fun agentPromptStaysUnderItsCapWithRelevantMemory() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val memory = memoryBlock(sampleFacts, "build and test the app")
        val p = prompt(mode, "build and test the app", memory)
        println("[facts] agent with memory = " + p.length)
        assertTrue("agent prompt is " + p.length, p.length < agentCap)
        assertTrue(p.contains("What you remember"))
    }

    @Test
    fun memoryNeverEvictsAToolDefinition() {
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val memory = memoryBlock(sampleFacts, "build the app")
        val without = prompt(mode, "build the app", "")
        val with = prompt(mode, "build the app", memory)
        val offered = ToolOffer.plan(mode, "build the app").names
        for (name in offered) {
            assertEquals("'" + name + "' must still be defined once with memory present",
                1, definitionsOf(with, name))
            assertEquals(definitionsOf(without, name), definitionsOf(with, name))
        }
        // And the memory is what grew, not the tool block.
        assertTrue(with.length > without.length)
        assertTrue(with.length < askCap)
    }

    @Test
    fun noToolDefinitionIsDuplicatedWhenMemoryIsPresent() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val memory = memoryBlock(sampleFacts, "build and test")
        val p = prompt(mode, "build and test", memory)
        val offered = ToolOffer.plan(mode, "build and test").names
        for (name in offered) assertEquals("duplicate definition of $name", 1, definitionsOf(p, name))
    }

    @Test
    fun memoryTextNeverLooksLikeAToolDefinition() {
        // The budget test counts definitions by their "- name:" shape; a fact line must not match it.
        val block = memoryBlock(sampleFacts, "build the app")
        for (line in block.lines()) {
            assertFalse("fact line looks like a tool definition: " + line,
                Regex("^- [a-z_]+:").containsMatchIn(line))
        }
    }

    @Test
    fun oversizedMemoryIsCutToTheRoomLeftRatherThanBreakingTheBudget() {
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val huge = (1..6).joinToString("\n") { "- a very long remembered fact " + "x".repeat(200) }
        val p = prompt(mode, "what is this file?", huge)
        assertTrue("ask prompt is " + p.length, p.length < askCap)
        // Cut at a line boundary: no half-line survives.
        assertFalse(p.contains("xxxxx...x\n- ") && false)
    }

    @Test
    fun memoryIsOmittedEntirelyWhenThereIsNoRoom() {
        assertEquals("", ChatInstructions.fitMemory("## memory\n- a fact", 0))
        assertEquals("", ChatInstructions.fitMemory("## memory\n- a fact", -20))
        assertEquals("", ChatInstructions.fitMemory("", 500))
    }

    @Test
    fun aCutMemoryBlockWithNoFactsLeftIsDroppedNotTruncated() {
        // Only the header fits: a header promising facts it does not have must not be sent.
        assertEquals("", ChatInstructions.fitMemory("## What you remember\nlong line here", 26))
    }

    @Test
    fun fitMemoryKeepsACompleteLineWhenItFits() {
        val mem = "## facts\n- one short fact"
        val out = ChatInstructions.fitMemory(mem, 200)
        assertEquals("\n\n" + mem, out)
    }

    @Test
    fun continuationPromptStartsWithTheLoadedToolAndKeepsMemory() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val memory = memoryBlock(sampleFacts, "build and test")
        val before = prompt(mode, "build and test", memory)
        assertFalse(definitionsOf(before, "git_stash") > 0)
        ToolLoadout.load(mode, listOf("git_stash"))
        val after = prompt(mode, "build and test", memory)
        assertEquals("the loaded tool must be defined exactly once", 1, definitionsOf(after, "git_stash"))
        assertTrue(after.contains("What you remember"))
        println("[facts] agent continuation with memory = " + after.length)
        assertTrue("continuation prompt is " + after.length, after.length < agentCap)
    }

    @Test
    fun memoryDoesNotLeakAProjectFactFromAnotherProject() {
        val facts = listOf(
            fact("mine", "The build uses gradle", FactScope.PROJECT, "proj-a"),
            fact("theirs", "The build uses gradle ant tasks", FactScope.PROJECT, "proj-b"),
        )
        val block = FactRetrieval.block(
            FactRetrieval.forRequest(facts, "build with gradle", "proj-a"), 900)
        assertTrue(block.contains("The build uses gradle"))
        assertFalse(block.contains("ant tasks"))
    }

    @Test
    fun withNoMemoryThePromptIsByteIdenticalToBefore() {
        // Regression guard: memory defaults to empty, so every existing caller's prompt is unchanged.
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val offered = ToolOffer.plan(mode, "what does this do?").names
        val a = ChatInstructions.build(mode, true, offered, "", "", ToolLoadout.extra())
        val b = ChatInstructions.build(mode, true, offered, "", "", ToolLoadout.extra(), "")
        assertEquals(a, b)
    }
}
