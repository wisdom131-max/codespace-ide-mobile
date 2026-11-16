package com.codespace.ide.agent

import com.codespace.ide.agent.research.ClaimReconciliation
import com.codespace.ide.agent.research.ContradictionScan
import com.codespace.ide.agent.research.SourceLedger
import com.codespace.ide.chat.panel.ChatMode
import com.codespace.ide.chat.panel.ToolCost
import com.codespace.ide.chat.panel.ToolSuitability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4.7 workstreams D and E: the remaining signals, and claims judged against their sources. */
class Stage47SelectionAndResearchTest {

    @After
    fun tearDown() = com.codespace.ide.agent.runtime.PrereqLedger.clear()

    private fun rank(
        candidates: List<String>,
        cost: (String) -> ToolCost? = { ToolCost.NORMAL },
        network: (String) -> Boolean? = { false },
        available: Boolean? = true,
        provider: (String) -> Boolean? = { true },
        offered: (String) -> Boolean = { true },
        request: String = "search the files",
        mode: ChatMode = ChatMode.AGENT,
    ) = ToolSuitability.rank(
        request = request,
        candidates = candidates,
        mode = mode,
        isOffered = offered,
        exists = { true },
        healthPenalty = { 0 },
        demoted = emptySet(),
        costOf = cost,
        needsNetwork = network,
        networkAvailable = available,
        providerConnected = provider,
    )

    @Test
    fun aCheaperToolOfEqualFitWins() {
        val choice = rank(listOf("read_file", "grep_search"), cost = { if (it == "grep_search") ToolCost.CHEAP else ToolCost.HEAVY })
        assertEquals("grep_search", choice.best?.tool)
        val heavy = choice.ranked.first { it.tool == "read_file" }
        assertTrue(heavy.signals.any { it.name == "cost" && it.earned == 0 })
    }

    @Test
    fun unknownCostEarnsNothingRatherThanAGuess() {
        val choice = rank(listOf("grep_search"), cost = { null })
        val cost = choice.best!!.signals.first { it.name == "cost" }
        assertEquals(0, cost.earned)
        assertTrue(cost.why.contains("unknown"))
    }

    @Test
    fun anUnknownNetworkRequirementIsSaidToBeUnknown() {
        val choice = rank(listOf("grep_search"), network = { null })
        val signal = choice.best!!.signals.first { it.name == "network" }
        assertEquals(0, signal.earned)
        assertTrue(signal.why.contains("unknown"))
    }

    @Test
    fun aToolThatNeedsAnUnavailableNetworkScoresLower() {
        val choice = rank(
            listOf("web_search", "grep_search"),
            network = { it == "web_search" },
            available = false,
            request = "search",
        )
        assertEquals("grep_search", choice.best?.tool)
        val web = choice.ranked.first { it.tool == "web_search" }
        assertTrue(web.eligible)
        assertTrue(web.signals.any { it.name == "network" && it.earned == 0 && it.why.contains("not available") })
    }

    @Test
    fun aProviderThatIsNotConnectedDemotesWithoutDisabling() {
        val choice = rank(listOf("web_search", "grep_search"), provider = { it != "web_search" }, network = { false })
        val unconnected = choice.ranked.first { it.tool == "web_search" }
        assertTrue(unconnected.eligible)
        assertTrue(unconnected.signals.any { it.name == "provider" && it.why.contains("not connected") })
    }

    @Test
    fun permissionsAndModeRemainAbsolute() {
        val choice = rank(listOf("write_file"), offered = { false }, mode = ChatMode.ASK)
        assertEquals(null, choice.best)
        assertTrue(choice.refusal!!.contains("ask mode does not offer it"))
    }

    @Test
    fun aHeavyToolIsDescribedAsHeavyOnAPhone() {
        val choice = rank(listOf("runTests"), cost = { ToolCost.HEAVY })
        val resources = choice.best!!.signals.first { it.name == "resources" }
        assertEquals(0, resources.earned)
        assertTrue(resources.why.contains("heavy"))
    }

    @Test
    fun theExplanationNamesTheSignalsThatDecidedIt() {
        val choice = rank(listOf("grep_search"))
        val text = choice.best!!.explanation
        assertTrue(text.contains("capability") || text.contains("task words") || text.contains("cost"))
        assertTrue(text.contains("scores"))
    }

    // ---------- research ----------

    private fun source(id: String, url: String, excerpt: String, domain: String = "example.com") =
        SourceLedger.Source(
            id = id, title = "Article $id", url = url, domain = domain,
            retrievedAt = "2026-11-16", publishedAt = "2026-11-15", excerpt = excerpt,
        )

    private fun claim(subject: String, value: String, sourceId: String, quote: String) =
        ContradictionScan.Claim(subject = subject, value = value, sourceId = sourceId, quote = quote)

    @Test
    fun oneSourceSupportingAClaimIsNotCorroboration() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the release date", "March 2027", "s1", "the release is planned for March 2027")),
            listOf(source("s1", "https://example.com/a", "the release is planned for March 2027, says the team")),
        )
        assertEquals(ClaimReconciliation.Support.SUPPORTED, report.verdicts.single().support)
        assertFalse(report.verdicts.single().note.contains("corroborated") && report.verdicts.single().note.isBlank())
    }

    @Test
    fun twoIndependentSourcesCorroborate() {
        val report = ClaimReconciliation.reconcile(
            listOf(
                claim("the release date", "March 2027", "s1", "the release is planned for March 2027"),
                claim("the release date", "March 2027", "s2", "the release is planned for March 2027"),
            ),
            listOf(
                source("s1", "https://example.com/a", "the release is planned for March 2027"),
                source("s2", "https://other.org/b", "the release is planned for March 2027"),
            ),
        )
        assertTrue(report.verdicts.all { it.support == ClaimReconciliation.Support.INDEPENDENTLY_SUPPORTED })
    }

    @Test
    fun twoSourcesThatDisagreeAreConflictingAndNoWinnerIsChosen() {
        val report = ClaimReconciliation.reconcile(
            listOf(
                claim("the release date", "March 2027", "s1", "the release is planned for March 2027"),
                claim("the release date", "September 2027", "s2", "the release is planned for September 2027"),
            ),
            listOf(
                source("s1", "https://example.com/a", "the release is planned for March 2027"),
                source("s2", "https://other.org/b", "the release is planned for September 2027"),
            ),
        )
        assertTrue(report.verdicts.isNotEmpty())
        assertTrue(report.disputed.isNotEmpty())
        assertTrue(report.verdicts.all { !it.confirmed })
        assertTrue(report.verdicts.first().note.contains("none is being chosen"))
    }

    @Test
    fun aQuoteTheSourceDoesNotContainIsUnsupported() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the price", "99 dollars", "s1", "it costs ninety nine dollars for every user")),
            listOf(source("s1", "https://example.com/a", "pricing has not been announced yet")),
        )
        assertEquals(ClaimReconciliation.Support.UNSUPPORTED, report.verdicts.single().support)
        assertFalse(report.verdicts.single().confirmed)
    }

    @Test
    fun syndicatedCopiesOfOneArticleAreNotIndependent() {
        val report = ClaimReconciliation.reconcile(
            listOf(
                claim("the release date", "March 2027", "s1", "the release is planned for March 2027"),
                claim("the release date", "March 2027", "s2", "the release is planned for March 2027"),
            ),
            listOf(
                source("s1", "https://example.com/story", "the release is planned for March 2027"),
                source("s2", "https://example.com/story", "the release is planned for March 2027"),
            ),
        )
        assertEquals(ClaimReconciliation.Support.SUPPORTED, report.verdicts.first().support)
        assertTrue(report.notes.any { it.contains("share an identity") })
    }

    @Test
    fun aClaimWithNoSourceIsUnverified() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the release date", "March 2027", "", "the release is planned for March 2027")),
            listOf(source("s1", "https://example.com/a", "something else entirely")),
        )
        assertEquals(ClaimReconciliation.Support.UNVERIFIED, report.verdicts.single().support)
        assertTrue(report.verdicts.single().note.contains("cites no source"))
    }

    @Test
    fun aClaimCitingASourceThatWasNeverFetchedIsUnverified() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the release date", "March 2027", "s9", "the release is planned for March 2027")),
            listOf(source("s1", "https://example.com/a", "the release is planned for March 2027")),
        )
        assertEquals(ClaimReconciliation.Support.UNVERIFIED, report.verdicts.single().support)
    }

    @Test
    fun withNoSourcesAtAllNothingIsConfirmed() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the release date", "March 2027", "s1", "the release is planned for March 2027")),
            emptyList(),
        )
        assertEquals(ClaimReconciliation.Support.INSUFFICIENT, report.verdicts.single().support)
        assertTrue(report.confirmed.isEmpty())
    }

    @Test
    fun everyVerdictKeepsItsSourceLink() {
        val report = ClaimReconciliation.reconcile(
            listOf(claim("the release date", "March 2027", "s1", "the release is planned for March 2027")),
            listOf(source("s1", "https://example.com/a", "the release is planned for March 2027")),
        )
        assertEquals("s1", report.verdicts.single().sourceId)
        assertTrue(report.verdicts.single().independentSources.contains("s1"))
    }
}
