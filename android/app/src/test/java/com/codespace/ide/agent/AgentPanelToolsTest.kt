package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search view snapshot, the image hand-off queue, and the GitHub repo helpers. */
class AgentPanelToolsTest {

    @Test
    fun searchSnapshotCarriesQueryAndHits() {
        SearchViewState.clear()
        SearchViewState.publish("fun ", listOf(SearchViewState.Hit("a.kt", 12, "fun main()")))
        val snap = SearchViewState.current()
        assertNotNull(snap)
        assertEquals("fun ", snap!!.query)
        assertEquals(1, snap.hits.size)
        assertEquals(12, snap.hits[0].line)
        assertFalse(snap.truncated)
    }

    @Test
    fun searchSnapshotIsCappedAndSaysItIsTruncated() {
        SearchViewState.clear()
        val many = (1..250).map { SearchViewState.Hit("f.kt", it, "line") }
        SearchViewState.publish("x", many)
        val snap = SearchViewState.current()!!
        assertEquals(200, snap.hits.size)
        assertTrue("truncation must be visible to the model", snap.truncated)
    }

    @Test
    fun aClearedSnapshotReadsAsNoSearch() {
        SearchViewState.publish("q", emptyList())
        SearchViewState.clear()
        assertNull(SearchViewState.current())
    }

    @Test
    fun imageQueueHandsImagesToExactlyOneSend() {
        ToolImageQueue.clear()
        assertTrue(ToolImageQueue.enqueue(ToolImageQueue.Pending("a.png", "image/png", "AAA", "a.png")))
        assertEquals(1, ToolImageQueue.size())
        val drained = ToolImageQueue.drain()
        assertEquals(1, drained.size)
        assertEquals("a.png", drained[0].name)
        assertEquals("the next send must not see them twice", 0, ToolImageQueue.size())
    }

    @Test
    fun imageQueueRefusesInsteadOfSilentlyDropping() {
        ToolImageQueue.clear()
        repeat(ToolImageQueue.MAX_PENDING) {
            assertTrue(ToolImageQueue.enqueue(ToolImageQueue.Pending("p$it.png", "image/png", "X", "p$it.png")))
        }
        assertFalse("over the cap must be refused, not evicted", ToolImageQueue.enqueue(
            ToolImageQueue.Pending("extra.png", "image/png", "X", "extra.png"),
        ))
        assertEquals(ToolImageQueue.MAX_PENDING, ToolImageQueue.size())
        ToolImageQueue.clear()
    }

    @Test
    fun repoRefParsesEveryRemoteShape() {
        assertEquals("o/r", GitHubRepoSupport.parseRepoRef("https://github.com/o/r.git"))
        assertEquals("o/r", GitHubRepoSupport.parseRepoRef("https://github.com/o/r"))
        assertEquals("o/r", GitHubRepoSupport.parseRepoRef("git@github.com:o/r.git"))
        assertNull("a non-GitHub remote must not be coerced", GitHubRepoSupport.parseRepoRef("https://gitlab.com/o/r.git"))
        assertNull(GitHubRepoSupport.parseRepoRef(""))
    }

    @Test
    fun repoRefComesFromTheOriginRemoteOnly() {
        val config = """
            [core]
                bare = false
            [remote "upstream"]
                url = https://github.com/wrong/repo.git
            [remote "origin"]
                url = git@github.com:right/repo.git
        """.trimIndent()
        assertEquals("right/repo", GitHubRepoSupport.repoRefFromGitConfig(config))
        assertNull(GitHubRepoSupport.repoRefFromGitConfig("[core]\n\tbare = false"))
    }

    @Test
    fun overviewRendersRealFields() {
        val json = """{"full_name":"o/r","description":"thing","default_branch":"main","private":false,"open_issues_count":3,"stargazers_count":7,"language":"Kotlin","html_url":"https://github.com/o/r"}"""
        val text = GitHubRepoSupport.renderOverview(json)
        assertTrue(text.contains("o/r"))
        assertTrue(text.contains("open issues: 3"))
        assertTrue(text.contains("language: Kotlin"))
    }

    @Test
    fun anEmptyItemListSaysSoAndNonJsonIsRefused() {
        assertEquals("No issues found.", GitHubRepoSupport.renderItems("[]", "issues"))
        assertTrue(GitHubRepoSupport.renderOverview("not json").contains("not JSON"))
    }

    @Test
    fun itemsCarryNumberTitleStateAndDraftFlag() {
        val json = """[{"number":7,"title":"Fix it","state":"open","user":{"login":"ada"},"draft":true}]"""
        val text = GitHubRepoSupport.renderItems(json, "pull requests")
        assertTrue(text.contains("#7 Fix it"))
        assertTrue(text.contains("(draft)"))
        assertTrue(text.contains("by ada"))
    }
}
