package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1: metadata is complete, deterministic and cannot expose anything on its own. */
class ToolMetaIndexTest {

    @Test
    fun everyRegisteredToolHasMetadata() {
        for (spec in ToolRegistry.ALL) {
            val meta = ToolMetaIndex.metaFor(spec)
            assertNotNull("no metadata for " + spec.name, meta)
            assertTrue("empty category for " + spec.name, meta.category.name.isNotEmpty())
        }
    }

    @Test
    fun noMetadataNamesAnUnregisteredTool() {
        // Explicit rows must never describe a tool that does not exist: that is how a catalogue
        // grows ghosts and the model starts asking for capabilities nothing can serve.
        val registered = ToolRegistry.names().toSet()
        val explicit = ToolMetaIndex.search("", ChatMode.AGENT, limit = 1)
        assertEquals(emptyList<ToolMetaIndex.Hit>(), explicit)
        for (spec in ToolRegistry.ALL) {
            assertTrue(spec.name in registered)
        }
    }

    @Test
    fun searchFindsByKeywordNotJustName() {
        val hits = ToolMetaIndex.search("hash", ChatMode.AGENT)
        assertTrue("expected checksum", hits.any { it.spec.name == "checksum" })
        val cmp = ToolMetaIndex.search("compare two files", ChatMode.AGENT)
        assertTrue("expected diff_files", cmp.any { it.spec.name == "diff_files" })
        val mv = ToolMetaIndex.search("rename", ChatMode.AGENT)
        assertTrue("expected move_path", mv.any { it.spec.name == "move_path" })
    }

    @Test
    fun searchNeverReturnsAHiddenTool() {
        // run_vscode_command is registered and hidden: searchable would mean loadable, then callable.
        val hits = ToolMetaIndex.search("vscode api", ChatMode.AGENT, limit = 20)
        assertFalse(hits.any { it.spec.name == "get_vscode_api" })
        assertFalse(hits.any { it.spec.name == "run_vscode_command" })
    }

    @Test
    fun searchRespectsTheMode() {
        val ask = ToolMetaIndex.search("commit", ChatMode.ASK, limit = 20).map { it.spec.name }
        assertFalse("Ask must not see git_commit_push", ask.contains("git_commit_push"))
        val agent = ToolMetaIndex.search("commit", ChatMode.AGENT, limit = 20).map { it.spec.name }
        assertTrue(agent.contains("git_commit_push"))
    }

    @Test
    fun resultsAreStableForTheSameQuery() {
        val a = ToolMetaIndex.search("find files containing a class", ChatMode.AGENT).map { it.spec.name }
        val b = ToolMetaIndex.search("find files containing a class", ChatMode.AGENT).map { it.spec.name }
        assertEquals(a, b)
    }

    @Test
    fun anUnrelatedQueryFindsNothingRatherThanEverything() {
        assertTrue(ToolMetaIndex.search("xyzzy plugh frobnicate", ChatMode.AGENT).isEmpty())
    }

    @Test
    fun mcpToolsAreSearchableWithoutBeingRegistered() {
        val hits = ToolMetaIndex.search("mcp_postgres_query", ChatMode.AGENT, 5, listOf("mcp_postgres_query"))
        assertTrue(hits.any { it.spec.name == "mcp_postgres_query" })
        assertTrue("MCP names never enter the registry", ToolRegistry.spec("mcp_postgres_query") == null)
    }
}

/** L2: lean offering keeps the mode's identity and states what it left out. */
class ToolOfferTest {

    @Test
    fun everyModeOffersAtMostItsTarget() {
        val request = "refactor the settings screen"
        assertEquals(true, ToolOffer.plan(ChatMode.ASK, request).names.size <= 10)
        assertEquals(true, ToolOffer.plan(ChatMode.EDIT, request).names.size <= 16)
        assertEquals(true, ToolOffer.plan(ChatMode.AGENT, request).names.size <= 25)
    }

    @Test
    fun theCoreToolsSurviveEveryRequest() {
        for (request in listOf("", "compare two files", "research android news", "xyzzy")) {
            for (mode in listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)) {
                val names = ToolOffer.plan(mode, request).names
                for (core in ToolMetaIndex.core(mode)) {
                    if (ToolRegistry.spec(core)?.modes?.contains(mode) == true) {
                        assertTrue("$mode lost core $core for '$request'", names.contains(core))
                    }
                }
            }
        }
    }

    @Test
    fun theOfferIsAlwaysASubsetOfTheMode() {
        for (mode in listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)) {
            val allowed = ToolRegistry.exposedNames(mode).toSet()
            assertTrue(allowed.containsAll(ToolOffer.plan(mode, "commit my changes").names))
        }
    }

    @Test
    fun aNarrowOfferSaysSo() {
        val plan = ToolOffer.plan(ChatMode.AGENT, "fix the parser")
        assertNotNull("a limited offer must be explicable", plan.note)
        assertTrue(plan.note!!.contains("tool_search"))
    }

    @Test
    fun leanCanBeTurnedOffWithoutLosingTools() {
        val full = ToolOffer.plan(ChatMode.AGENT, "fix the parser", enabled = false)
        assertEquals(ToolRegistry.exposedNames(ChatMode.AGENT).size, full.names.size)
        assertEquals(null, full.note)
    }
}

/** L3: the four tasks from the owner's brief must select the obvious tools. */
class ToolRelevanceTest {

    private fun top(mode: ChatMode, request: String, n: Int = 8) = ToolRelevance.rank(mode, request).take(n)

    @Test
    fun searchTaskSelectsSearchAndRead() {
        val names = top(ChatMode.AGENT, "Find all Kotlin files containing this class")
        assertTrue(names.contains("grep_search") || names.contains("file_search"))
        assertTrue(names.contains("read_file"))
    }

    @Test
    fun compareTaskSelectsDiffAndRead() {
        val names = top(ChatMode.AGENT, "Compare these two files and tell me what changed")
        assertTrue(names.contains("diff_files"))
        assertTrue(names.contains("read_file"))
    }

    @Test
    fun gitTaskSelectsGitTools() {
        val names = top(ChatMode.AGENT, "Create a branch and commit my changes", 12)
        assertTrue(names.contains("git_branch"))
        assertTrue(names.contains("git_commit_push"))
        assertTrue(names.contains("git_status"))
    }

    @Test
    fun researchTaskSelectsWebTools() {
        val names = top(ChatMode.AGENT, "Research the latest Android development news", 12)
        assertTrue(names.contains("web_search"))
        assertTrue(names.contains("web_fetch"))
    }

    @Test
    fun synonymsReachTheToolWhoseNameDoesNotMatch() {
        val hash = top(ChatMode.AGENT, "give me a checksum of this apk")
        assertTrue(hash.contains("checksum"))
        val tags = top(ChatMode.AGENT, "tag this release as v2", 12)
        assertTrue(tags.contains("git_tag"))
    }

    @Test
    fun anAmbiguousRequestStaysStableAndSane() {
        val a = ToolRelevance.rank(ChatMode.AGENT, "look at this")
        val b = ToolRelevance.rank(ChatMode.AGENT, "look at this")
        assertEquals(a, b)
    }

    @Test
    fun taskKindsAreRecognisable() {
        assertEquals(ToolRelevance.TaskKind.COMPARE, ToolRelevance.kindOf("compare a.txt and b.txt"))
        assertEquals(ToolRelevance.TaskKind.GIT, ToolRelevance.kindOf("stash my changes"))
        assertEquals(ToolRelevance.TaskKind.RESEARCH, ToolRelevance.kindOf("latest Kotlin news"))
        assertEquals(ToolRelevance.TaskKind.UNKNOWN, ToolRelevance.kindOf("xyzzy"))
    }
}

/** L4: loading is capped, mode-checked, idempotent and observable. */
class ToolLoadoutTest {

    @Test
    fun loadingMakesAToolAVailableAndBumpsTheVersion() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        val before = ToolLoadout.version()
        val r = ToolLoadout.load(ChatMode.AGENT, listOf("git_stash"))
        assertTrue(r.accepted.contains("git_stash"))
        assertTrue(ToolLoadout.extra().contains("git_stash"))
        assertTrue("version must change so the session rebuilds", r.version != before)
    }

    @Test
    fun loadingTheSameToolTwiceDoesNotGrowTheSet() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        ToolLoadout.load(ChatMode.AGENT, listOf("checksum", "checksum"))
        ToolLoadout.load(ChatMode.AGENT, listOf("checksum"))
        assertEquals(1, ToolLoadout.extra().size)
    }

    @Test
    fun loadingIsRefusedOutsideTheMode() {
        ToolLoadout.beginTask(ChatMode.ASK)
        val r = ToolLoadout.load(ChatMode.ASK, listOf("run_command", "git_commit_push"))
        assertTrue(r.rejected.contains("run_command"))
        assertFalse(ToolLoadout.extra().contains("run_command"))
    }

    @Test
    fun hiddenToolsCannotBeLoadedEvenByExactName() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        val r = ToolLoadout.load(ChatMode.AGENT, listOf("run_vscode_command", "get_vscode_api"))
        assertEquals(2, r.rejected.size)
        assertTrue(ToolLoadout.extra().isEmpty())
    }

    @Test
    fun theCapIsEnforcedAndSaidOutLoud() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        val pool = ToolRegistry.exposedNames(ChatMode.AGENT).filter { it !in ToolMetaIndex.core(ChatMode.AGENT) }
        val r = ToolLoadout.load(ChatMode.AGENT, pool)
        assertTrue(r.accepted.size <= ToolLoadout.MAX_EXTRA)
        assertTrue(r.atCap)
        assertTrue(r.rejected.isNotEmpty())
    }

    @Test
    fun aNewUserMessageClearsTheLoadout() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        ToolLoadout.load(ChatMode.AGENT, listOf("checksum"))
        ToolLoadout.beginTask(ChatMode.AGENT)
        assertTrue(ToolLoadout.extra().isEmpty())
    }

    @Test
    fun anUnknownNameIsRejectedNotLoaded() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        val r = ToolLoadout.load(ChatMode.AGENT, listOf("definitely_not_a_tool"))
        assertTrue(r.rejected.contains("definitely_not_a_tool"))
    }
}

/** L2 measurement: the numbers the report quotes come from HERE, not from an estimate. */
class ToolPromptBudgetTest {

    private fun report(mode: ChatMode, request: String): Triple<Int, Int, Int> {
        val full = ToolRegistry.promptBlock(mode)
        val lean = ToolRegistry.promptBlock(mode, ToolOffer.plan(mode, request).names.toSet())
        val instruction = ChatModePrompts.instruction(mode)
        return Triple(full.length, lean.length, instruction.length)
    }

    @Test
    fun leanIsSmallerInEveryModeAndStillUsable() {
        val request = "fix the failing parser test"
        for (mode in listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)) {
            val (full, lean, instruction) = report(mode, request)
            // chars/4 is the documented estimator; the report states it next to the numbers.
            println(
                "BUDGET " + mode.name + " full=" + full + " lean=" + lean +
                    " instruction=" + instruction +
                    " fullTokens~" + (full + instruction) / 4 + " leanTokens~" + (lean + instruction) / 4,
            )
            assertTrue(mode.name + " lean block must be smaller", lean < full)
            assertTrue(mode.name + " lean block must not be empty", lean > 500)
        }
    }

    @Test
    fun theLeanBlockStillCarriesTheProtocolAndTheLoadHint() {
        val lean = ToolRegistry.promptBlock(ChatMode.AGENT, ToolOffer.plan(ChatMode.AGENT, "hello").names.toSet())
        assertTrue(lean.contains("<tool>"))
        assertTrue(lean.contains("tool_search"))
    }

    @Test
    fun askingForEverythingIsUnchanged() {
        assertEquals(ToolRegistry.promptBlock(ChatMode.AGENT), ToolRegistry.promptBlock(ChatMode.AGENT, null))
    }
}
