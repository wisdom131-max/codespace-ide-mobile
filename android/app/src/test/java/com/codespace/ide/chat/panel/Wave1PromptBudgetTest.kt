package com.codespace.ide.chat.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wave 1 Group A: the real prompt, per mode, with progressive loading in play.
 *
 * Every case here calls [ChatInstructions.build] - the same function ChatSession calls to produce
 * the provider's system instruction - so "the prompt fits" means the bytes that are actually sent,
 * not a helper's output and not the registry catalogue.
 */
class Wave1PromptBudgetTest {

    private val groupA = listOf("json_query", "csv_parse", "base64_codec")
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

    private fun prompt(mode: ChatMode, request: String): String =
        ChatInstructions.build(mode, true, ToolOffer.plan(mode, request).names, "", "")

    private fun definitionsOf(prompt: String, name: String): Int =
        Regex("(?m)^- " + Regex.escape(name) + ":").findAll(prompt).count()

    @Test
    fun askWithoutLoadingAnythingFitsTheAskBudget() {
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val p = prompt(mode, "what does this file do?")
        println("[wave1] ask baseline=" + p.length)
        assertTrue("ask prompt is " + p.length, p.length < askCap)
        groupA.forEach { assertFalse("'" + it + "' must not be advertised before it is loaded",
            definitionsOf(p, it) > 0) }
    }

    @Test
    fun askFitsTheBudgetWithEachGroupAToolLoadedOnItsOwn() {
        for (name in groupA) {
            val mode = ChatMode.ASK
            ToolLoadout.beginTask(mode)
            val plan = ToolOffer.plan(mode, "look at this file")
            val loaded = ToolLoadout.load(mode, listOf(name))
            assertTrue(name + " must load in Ask", loaded.accepted.contains(name))
            val p = ChatInstructions.build(mode, true, plan.names, "", "")
            println("[wave1] ask + " + name + " = " + p.length)
            assertTrue(name + " pushed Ask past its budget: " + p.length, p.length < askCap)
            assertEquals(name + " must appear exactly once", 1, definitionsOf(p, name))
        }
    }

    @Test
    fun askFitsTheBudgetWithAllThreeLoadedTogether() {
        val mode = ChatMode.ASK
        ToolLoadout.beginTask(mode)
        val plan = ToolOffer.plan(mode, "clean up this data")
        val loaded = ToolLoadout.load(mode, groupA)
        assertEquals(groupA, loaded.accepted)
        val p = ChatInstructions.build(mode, true, plan.names, "", "")
        println("[wave1] ask + all three = " + p.length)
        assertTrue("three loaded tools pushed Ask past its budget: " + p.length, p.length < askCap)
        groupA.forEach { assertEquals(it + " must appear exactly once", 1, definitionsOf(p, it)) }
    }

    @Test
    fun agentFitsTheBudgetWithTheGroupALoaded() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val plan = ToolOffer.plan(mode, "fix the failing import")
        ToolLoadout.load(mode, groupA)
        val p = ChatInstructions.build(mode, true, plan.names, "", "")
        println("[wave1] agent + all three = " + p.length)
        assertTrue("agent prompt is " + p.length, p.length < agentCap)
        groupA.forEach { assertEquals(it + " must appear exactly once", 1, definitionsOf(p, it)) }
    }

    @Test
    fun editFitsItsBudgetWithTheGroupALoaded() {
        val mode = ChatMode.EDIT
        ToolLoadout.beginTask(mode)
        val plan = ToolOffer.plan(mode, "update the config")
        ToolLoadout.load(mode, groupA)
        val p = ChatInstructions.build(mode, true, plan.names, "", "")
        println("[wave1] edit + all three = " + p.length)
        assertTrue("edit prompt is " + p.length, p.length < agentCap)
    }

    @Test
    fun discoveryInOneStepMakesTheDefinitionAvailableInTheNextAndOnlyThen() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val plan = ToolOffer.plan(mode, "rename the foo function everywhere")
        assertTrue("precondition: none of the three is offered yet",
            plan.names.none { it in groupA })
        val first = ChatInstructions.build(mode, true, plan.names, "", "")
        assertFalse("json_query must not be described before discovery", definitionsOf(first, "json_query") > 0)
        val versionBefore = ToolLoadout.version()

        val loaded = ToolLoadout.load(mode, listOf("json_query"))
        assertTrue(loaded.accepted.contains("json_query"))
        assertTrue("loading must bump the version so the session rebuilds", ToolLoadout.version() > versionBefore)

        val second = ChatInstructions.build(mode, true, plan.names, "", "")
        assertEquals("the next continuation must carry it exactly once", 1, definitionsOf(second, "json_query"))
        assertTrue("and the prompt must still fit: " + second.length, second.length < agentCap)
        assertTrue("the added definition must be the delta", second.length > first.length)
    }

    /**
     * A request that names all three. Relevance must find all three; the OFFER is capped at the
     * mode's target, so with a 23-name core the two free slots go to the best two - that is the
     * documented trade-off, not a miss. What must hold either way is the budget, with all three
     * described.
     */
    @Test
    fun aTaskThatNamesAllThreeRanksThemAllAndStillFitsWithThemLoaded() {
        val mode = ChatMode.AGENT
        val request = "parse this csv, query the json and base64 decode the blob"
        val ranked = ToolRelevance.rank(mode, request)
        println("[wave1] ranked head=" + ranked.take(6))
        for (name in groupA) assertTrue(name + " must be ranked for a request that names it",
            ranked.contains(name))

        val plan = ToolOffer.plan(mode, request)
        assertTrue("the offer must stay bounded", plan.names.size <= ToolMetaIndex.target(mode))
        assertTrue("at least one named capability must win a free slot",
            groupA.any { it in plan.names })

        ToolLoadout.beginTask(mode)
        ToolLoadout.load(mode, groupA)
        val p = ChatInstructions.build(mode, true, plan.names, "", "")
        println("[wave1] all-named + loaded = " + p.length)
        assertTrue("prompt with all three loaded is " + p.length, p.length < agentCap)
        groupA.forEach { assertEquals(it + " exactly once", 1, definitionsOf(p, it)) }
    }

    @Test
    fun anUnrelatedTaskOffersNoneOfThem() {
        val plan = ToolOffer.plan(ChatMode.AGENT, "rename the foo function everywhere")
        for (name in groupA) assertFalse(name + " must not be offered for an unrelated task",
            plan.names.contains(name))
        val p = ChatInstructions.build(ChatMode.AGENT, true, plan.names, "", "")
        groupA.forEach { assertEquals(it + " must not be described", 0, definitionsOf(p, it)) }
    }

    @Test
    fun noPathSendsTheWholeCatalogue() {
        val catalogue = ToolRegistry.promptBlock(ChatMode.AGENT).length
        ChatMode.entries.forEach { mode ->
            ToolLoadout.clear()
            ToolLoadout.beginTask(mode)
            val p = ChatInstructions.build(mode, true, ToolOffer.plan(mode, "test the parser").names, "", "")
            assertTrue(mode.label + " prompt (" + p.length + ") must be smaller than the catalogue (" +
                catalogue + ")", p.length < catalogue)
        }
    }

    @Test
    fun everyToolNamedInThePromptWasActuallyOfferedOrLoaded() {
        val mode = ChatMode.AGENT
        ToolLoadout.beginTask(mode)
        val plan = ToolOffer.plan(mode, "summarise the repository")
        ToolLoadout.load(mode, listOf("json_query"))
        val p = ChatInstructions.build(mode, true, plan.names, "", "")
        val visible = plan.names.toSet() + ToolLoadout.extra()
        ToolRegistry.exposedNames(mode).forEach { name ->
            val described = definitionsOf(p, name) > 0
            assertEquals("'" + name + "' described in the prompt but not offered or loaded",
                name in visible, described)
        }
    }

    @Test
    fun theCatalogueIsNotConfusedWithThePrompt() {
        val catalogue = ToolRegistry.promptBlock(ChatMode.AGENT)
        ChatMode.entries.forEach { mode ->
            ToolLoadout.clear()
            ToolLoadout.beginTask(mode)
            val p = ChatInstructions.build(mode, true, ToolOffer.plan(mode, "hi").names, "", "")
            assertTrue(mode.label + " prompt must be much smaller than the full catalogue",
                p.length < catalogue.length)
        }
    }
}
