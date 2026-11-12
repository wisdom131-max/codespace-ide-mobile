package com.codespace.ide.chat.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P6.C: "continue in another session" created a conversation and forgot where it came from. The
 * chain, its numbering and its root label are what make hop five readable.
 */
class SessionLineageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun lineage() = SessionLineage(File(tmp.root, SessionLineage.FILE_NAME))

    @Test fun anUntouchedProjectHasNoLineage() {
        assertTrue(lineage().read().isEmpty())
        assertNull(lineage().linkOf("nothing"))
    }

    @Test fun aRootSessionIsSessionOne() {
        val link = lineage().record("s1", null, "explain git")
        assertEquals(1, link.seq)
        assertEquals("explain git", link.origin)
        assertNull(link.parentId)
        assertEquals("Session 1 from explain git", SessionLineage.label(link))
    }

    @Test fun aSuccessorIncrementsAndKeepsTheRootLabel() {
        val l = lineage()
        l.record("s1", null, "explain git")
        val second = l.record("s2", "s1", "s1's own title")
        assertEquals(2, second.seq)
        assertEquals("explain git", second.origin)
        assertEquals("s1", second.parentId)
        assertEquals("Session 2 from explain git", SessionLineage.label(second))
    }

    @Test fun aThirdHopStillCountsFromTheRoot() {
        val l = lineage()
        l.record("s1", null, "explain git")
        l.record("s2", "s1", "ignored")
        val third = l.record("s3", "s2", "ignored")
        assertEquals(3, third.seq)
        assertEquals("Session 3 from explain git", SessionLineage.label(third))
    }

    @Test fun continuingFromAPreLineageSessionStartsAtTwo() {
        val second = lineage().record("s2", "legacy-unlinked", "git notes")
        assertEquals(2, second.seq)
        assertEquals("git notes", second.origin)
    }

    @Test fun lineageSurvivesAReload() {
        lineage().record("s1", null, "fix the parser")
        val again = lineage()
        assertEquals("fix the parser", again.linkOf("s1")!!.origin)
    }

    @Test fun reRecordingASessionDoesNotDuplicateIt() {
        val l = lineage()
        l.record("s1", null, "one")
        l.record("s1", null, "one")
        assertEquals(1, l.read().count { it.sessionId == "s1" })
    }

    @Test fun aLabelWithTabsAndNewlinesSurvivesTheRoundTrip() {
        val l = lineage()
        l.record("s1", null, "tab\there\nand a newline")
        val back = l.read().first { it.sessionId == "s1" }
        assertTrue("tabs and newlines must be escaped, not split the record", back.origin.contains("tab"))
        assertEquals(1, l.read().size)
    }

    @Test fun aBlankTitleFallsBackToTheStandardLabel() {
        val link = lineage().record("s1", null, "   ")
        assertEquals(SessionMeta.UNTITLED, link.origin)
    }

    @Test fun damagedLinesAreSkippedNotFatal() {
        val f = File(tmp.root, SessionLineage.FILE_NAME)
        f.writeText("s1\ts0\t2\tsomething\nnot-a-record\ts0\n\n")
        val read = SessionLineage(f).read()
        assertEquals(1, read.size)
        assertEquals("s1", read[0].sessionId)
    }

    @Test fun aVeryLongOriginIsBounded() {
        val link = lineage().record("s1", null, "x".repeat(500))
        assertTrue(link.origin.length <= SessionLineage.MAX_ORIGIN + 1)
    }
}
