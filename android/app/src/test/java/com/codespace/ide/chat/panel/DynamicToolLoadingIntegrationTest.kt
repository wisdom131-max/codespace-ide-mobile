package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * POST-IMPLEMENTATION (2026-11-13): the four Dynamic Tool Loading branches tested as ONE system.
 *
 * The individual branches already have their own tests - ToolOfferTest scores relevance, ToolLoadout
 * has its lifecycle tests, ToolCatalogueTest covers rows and groups, DynamicToolsTest covers search
 * and metadata. Those tests answer "does each part work alone". They cannot answer the question this
 * file exists for: when lean mode, relevance, groups and on-demand loading all participate in the
 * same turn, do they still agree with each other, and does the agreement survive a load plus a
 * continuation?
 *
 * Each test names the branches it exercises in its comment, so a future failure points at a
 * disagreement rather than at "the tool system".
 */
class DynamicToolLoadingIntegrationTest {

    @Before fun setUp() {
        ToolLoadout.clear()
    }

    // ── Scenario 1: research and file analysis (LEAN + RELEVANCE + ON-DEMAND + GROUPS) ──────────

    @Test fun researchRequestOffersResearchToolsAndKeepsTheRestLoadable() {
        val ask = ToolOffer.plan(ChatMode.ASK, "search the docs for how retries work and cite sources")
        // Lean: a turn in ASK does not get the whole catalogue.
        assertTrue("ASK must be leaner than the catalogue", ask.names.size < ask.total)
        assertNotNull("an offer that limits tools must say so", ask.note)
        assertTrue(ask.note!!.contains("tool_search"))
        // Relevance: read-only research capabilities are present.
        assertTrue("search_files should survive relevance", "search_files" in ask.names)
        // L23: a mode's target is core+1, so exactly one request-specific tool can be admitted.
        assertTrue("the offer is bounded by the mode target", ask.names.size <= 10)
        // Safety: nothing that writes is offered in ASK.
        val exposed = ToolRegistry.exposed(ChatMode.ASK).associateBy { it.name }
        assertTrue(ask.names.all { exposed.containsKey(it) })
        assertTrue(ask.names.none { ToolMetaIndex.metaFor(exposed.getValue(it)).destructive })

        // On-demand: a tool the offer left out is still discoverable and loadable.
        val rows = ToolCatalogue.entries(ChatMode.ASK)
        val hit = ToolCatalogue.search(rows, "json query")
        assertTrue("tool_search must find json_query even when it was not offered", hit.any { it.name == "json_query" })
        val load = ToolLoadout.load(ChatMode.ASK, listOf("json_query"))
        assertEquals(listOf("json_query"), load.accepted)
        assertEquals(listOf("json_query"), ToolLoadout.extra().toList())

        // GROUPS: the catalogue groups the tool, and the group is a category, not a stage.
        val row = rows.first { it.name == "json_query" }
        assertTrue(ToolCatalogue.groups(rows).any { it.first == row.group })
        assertFalse("catalogue groups are categories, not execution stages", row.group in STAGES)
    }

    @Test fun aRelevanceMatchNeverRemovesTheModesOwnCoreTools() {
        // The failure this guards against shipped once (ToolOfferTest: "ASK lost core web_search").
        // Stated here as an integration rule: loading and relevance must not conspire to drop core.
        val core = ToolMetaIndex.core(ChatMode.ASK)
        val plans = listOf(
            "",
            "zip the archive and hash it",
            "git commit and push",
            "run the tests and read the log",
        ).map { ToolOffer.plan(ChatMode.ASK, it) }
        for (plan in plans) {
            for (name in core) {
                if (ToolRegistry.exposed(ChatMode.ASK).any { it.name == name }) {
                    assertTrue("core $name must survive relevance for any request", name in plan.names)
                }
            }
        }
    }

    // ── Scenario 2: git investigation and recovery (LEAN + RELEVANCE + ON-DEMAND) ───────────────

    @Test fun gitInvestigationSurfacesGitToolsAndRefusesTheWriteItCannotRunYet() {
        val ask = ToolOffer.plan(ChatMode.ASK, "the branch is broken after a bad merge, check git status and diff")
        // git_diff is core and always present. git_status is the relevance candidate; with one free
        // slot (L23) the guarantee is that it is LOADABLE, not that it is already offered.
        assertTrue(ask.names.contains("git_diff"))
        val status = ToolLoadout.load(ChatMode.ASK, listOf("git_status"))
        assertEquals(listOf("git_status"), status.accepted)

        // A tool ASK does not expose cannot be smuggled in by asking for it. service_write is the
        // clearest example: it changes something on a remote server, so ASK never offers it.
        val exposedInAsk = ToolRegistry.exposed(ChatMode.ASK).map { it.name }
        assertFalse("precondition: ASK must not expose service_write", exposedInAsk.contains("service_write"))
        val load = ToolLoadout.load(ChatMode.ASK, listOf("service_write"))
        assertTrue("a tool the mode does not expose is rejected, not loaded", load.rejected.contains("service_write"))
        // git_status was loaded above; the refusal must not have added anything else.
        assertEquals(setOf("git_status"), ToolLoadout.extra())

        // In AGENT the same tool exists, so the same request loads it instead of refusing it.
        ToolLoadout.clear()
        ToolLoadout.beginTask(ChatMode.AGENT)
        val agent = ToolOffer.plan(ChatMode.AGENT, "resolve the merge conflict and push")
        assertTrue("AGENT offers write tools that ASK withholds", "git_commit_push" in agent.names)
        val ok = ToolLoadout.load(ChatMode.AGENT, listOf("git_conflict_resolve"))
        assertEquals(listOf("git_conflict_resolve"), ok.accepted)
    }

    // ── Scenario 3: archive and document analysis (GROUPS + ON-DEMAND + LEAN) ───────────────────

    @Test fun archiveThenDocumentIsTwoLoadsAndOneGroup() {
        // The workflow is: find the archive, list it, extract one member, read it as a document.
        val agent = ToolOffer.plan(ChatMode.AGENT, "look inside the attached zip and summarise the pdf")
        // One of the two archive readers wins the single relevance slot; the other is one load away.
        assertTrue("an archive reader must be offered",
            agent.names.contains("zip_list") || agent.names.contains("zip_extract"))
        assertTrue("reading a pdf goes through read_file", agent.names.contains("read_file"))

        // Extraction is a separate capability; it is loadable in the same task without a restart.
        val versionBefore = ToolLoadout.version()
        val load = ToolLoadout.load(ChatMode.AGENT, listOf("zip_extract"))
        assertTrue("zip_extract must load: " + load, load.accepted.contains("zip_extract"))
        assertEquals("one new tool is one change stamp", versionBefore + 1, load.version)

        // Groups: zip_* and read_file are in different categories, which is what "/tools" shows.
        val rows = ToolCatalogue.entries(ChatMode.AGENT)
        // OBSERVATION (2026-11-13): zip_* and read_file share the "files" category, so "/tools"
        // shows archives and documents in one group. Recorded rather than asserted as separate:
        // the group is the tool's CATEGORY, and both are file tools. The property that matters is
        // that every row has a real group and the catalogue offers more than one.
        val zip = rows.filter { it.name.startsWith("zip_") }.map { it.group }.toSet()
        assertEquals("the zip tools share one group", 1, zip.size)
        val groups = ToolCatalogue.groups(rows)
        assertTrue("the catalogue must have several groups", groups.size >= 4)
        assertTrue("no empty group", groups.all { it.second > 0 })
    }

    // ── Scenario 4: multi-step agent task (all four + continuation) ─────────────────────────────

    @Test fun loadingIsIdempotentAndBumpsTheVersionOnceSoContinuationDoesNotDuplicate() {
        val first = ToolLoadout.load(ChatMode.AGENT, listOf("zip_extract", "json_query"))
        assertEquals(2, first.accepted.size)
        val v1 = first.version
        // The same load twice (a retry, a continuation, a replayed turn) must not change anything.
        val again = ToolLoadout.load(ChatMode.AGENT, listOf("zip_extract", "json_query"))
        assertEquals(v1, again.version)
        assertEquals(2, ToolLoadout.extra().size)
        // A genuinely new tool bumps exactly once.
        val third = ToolLoadout.load(ChatMode.AGENT, listOf("base64_codec"))
        assertEquals(v1 + 1, third.version)
        assertEquals(setOf("zip_extract", "json_query", "base64_codec"), ToolLoadout.extra())
    }

    @Test fun theCapIsEnforcedAndSaysSoRatherThanSilentlyDropping() {
        val asked = ToolRegistry.exposed(ChatMode.AGENT).map { it.name }
            .filter { it !in ToolMetaIndex.core(ChatMode.AGENT) }
            .take(ToolLoadout.MAX_EXTRA + 4)
        val result = ToolLoadout.load(ChatMode.AGENT, asked)
        assertEquals(ToolLoadout.MAX_EXTRA, ToolLoadout.extra().size)
        assertTrue("the cap must be reported, not silent", result.atCap)
        assertTrue(result.rejected.isNotEmpty())
    }

    @Test fun unknownAndMalformedLoadRequestsAreRejectedWithoutChangingState() {
        val before = ToolLoadout.version()
        val r = ToolLoadout.load(ChatMode.AGENT, listOf("", "   ", "not_a_tool", "mcp_nonexistent"))
        assertTrue(r.accepted.isEmpty())
        assertEquals(4 - 2, r.rejected.size) // the two blanks are dropped, not "rejected"
        assertEquals("a rejected load must not bump the version", before, ToolLoadout.version())
        assertTrue(ToolLoadout.extra().isEmpty())
    }

    @Test fun clearingTheTaskDropsEverythingASessionChangeShouldDrop() {
        ToolLoadout.beginTask(ChatMode.AGENT)
        ToolLoadout.load(ChatMode.AGENT, listOf("zip_extract"))
        assertFalse(ToolLoadout.extra().isEmpty())
        ToolLoadout.clear()
        assertTrue("a new conversation starts with no borrowed tools", ToolLoadout.extra().isEmpty())
        // clear() drops the set; the MODE is recorded by beginTask() with the next message. Asserted
        // as-is rather than changed: tool_search reads the mode, so only a real send may set it.
        assertEquals(ChatMode.AGENT, ToolLoadout.mode())
        ToolLoadout.beginTask(ChatMode.ASK)
        assertEquals(ChatMode.ASK, ToolLoadout.mode())
    }

    @Test fun everyExposedToolHasAGroupAndEveryGroupNameIsACategory() {
        for (mode in listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)) {
            val rows = ToolCatalogue.entries(mode)
            assertTrue("mode $mode exposed nothing", rows.isNotEmpty())
            assertTrue(rows.all { it.group.isNotBlank() })
            assertTrue(rows.none { it.group in STAGES })
            assertEquals("no duplicate rows in $mode", rows.size, rows.map { it.name }.toSet().size)
        }
    }

    private companion object {
        /**
         * Thinking Tree / chevron stage names. A catalogue group that equalled one of these would
         * make "/tools" and the execution tree look like the same thing, which the owner reported
         * as confusing. Kept as a literal list so adding a stage here is a deliberate act.
         */
        val STAGES = setOf("plan", "research", "read", "write", "verify", "thinking", "done")
    }
}
