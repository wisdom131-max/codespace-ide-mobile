package com.codespace.ide.agent

import com.codespace.ide.agent.runtime.EvidenceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Stage 4.7 workstream A: observations that survive the process, and refuse to lie after it dies. */
class Stage47EvidenceTest {

    private fun tempFile(): File = File(Files.createTempDirectory("vncode-evidence").toFile(), "evidence.json")

    @After
    fun tearDown() = EvidenceReconciler.clear()

    @Test
    fun anObservationSurvivesAProcessRestart() {
        val file = tempFile()
        EvidenceReconciler.attach(EvidenceStore.forTest(file))
        EvidenceReconciler.compare("src/Main.kt", "version one", "s1", 1, "read_file", false)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun aContradictionIsDetectedAfterARestart() {
        val file = tempFile()
        val store = EvidenceStore.forTest(file)
        EvidenceReconciler.attach(store)
        EvidenceReconciler.compare("src/Main.kt", "version one", "s1", 1, "read_file", false)

        // A new process reads the same file, with nothing in memory.
        EvidenceReconciler.clearMemoryForTest()
        EvidenceReconciler.attachFreshForTest(EvidenceStore.forTest(file))

        val clash = EvidenceReconciler.compare("src/Main.kt", "version two", "s1", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.CONTRADICTION, clash.verdict)
        assertTrue(clash.blocksSatisfaction)
    }

    @Test
    fun restoredEvidenceFromAnotherConversationIsStale() {
        val file = tempFile()
        EvidenceReconciler.attach(EvidenceStore.forTest(file))
        EvidenceReconciler.compare("src/A.kt", "content", "s1", 1, "read_file", false)
        EvidenceReconciler.clearMemoryForTest()
        EvidenceReconciler.attachFreshForTest(EvidenceStore.forTest(file))
        val stale = EvidenceReconciler.compare("src/A.kt", "other", "s2", 1, "read_file", false)
        assertEquals(EvidenceReconciler.Verdict.STALE, stale.verdict)
        assertFalse(stale.blocksSatisfaction)
    }

    @Test
    fun restoredEvidenceFromAnOldPlanRevisionIsStale() {
        val file = tempFile()
        EvidenceReconciler.attach(EvidenceStore.forTest(file))
        EvidenceReconciler.compare("src/A.kt", "content", "s1", 1, "read_file", false)
        EvidenceReconciler.clearMemoryForTest()
        EvidenceReconciler.attachFreshForTest(EvidenceStore.forTest(file))
        assertEquals(
            EvidenceReconciler.Verdict.STALE,
            EvidenceReconciler.compare("src/A.kt", "other", "s1", 2, "read_file", false).verdict,
        )
    }

    @Test
    fun aChangeAfterAVerifiedWriteIsNotCalledAContradictionAfterARestart() {
        val file = tempFile()
        EvidenceReconciler.attach(EvidenceStore.forTest(file))
        EvidenceReconciler.compare("src/A.kt", "before", "s1", 1, "read_file", false)
        EvidenceReconciler.clearMemoryForTest()
        EvidenceReconciler.attachFreshForTest(EvidenceStore.forTest(file))
        assertEquals(
            EvidenceReconciler.Verdict.LEGITIMATE_CHANGE,
            EvidenceReconciler.compare("src/A.kt", "after", "s1", 1, "read_file", true).verdict,
        )
    }

    @Test
    fun aCorruptEvidenceFileYieldsNothingRatherThanThrowing() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("{not json at all")
        val loaded = EvidenceStore.forTest(file).load()
        assertTrue(loaded.observations.isEmpty())
        assertTrue(loaded.note.isNotBlank())
    }

    @Test
    fun aMissingEvidenceFileIsSimplyEmpty() {
        val loaded = EvidenceStore.forTest(tempFile()).load()
        assertTrue(loaded.observations.isEmpty())
        assertEquals("", loaded.note)
    }

    @Test
    fun aHalfWrittenRowWithoutADigestIsIgnored() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("""[{"resource":"src/A.kt"},{"resource":"src/B.kt","digest":"abc123","session":"s1"}]""")
        val loaded = EvidenceStore.forTest(file).load()
        assertEquals(1, loaded.observations.size)
        assertTrue(loaded.observations.containsKey("src/B.kt"))
    }

    @Test
    fun storageIsBounded() {
        val file = tempFile()
        val store = EvidenceStore.forTest(file)
        val big = (1..260).associate { "src/File$it.kt" to EvidenceReconciler.Observation("src/File$it.kt", "d$it", it.toLong(), "s1", 1, "read_file") }
        store.save(big)
        val loaded = store.load()
        assertTrue("bounded to the cap", loaded.observations.size <= EvidenceStore.CAP)
        assertTrue("the newest survive", loaded.observations.containsKey("src/File260.kt"))
        assertFalse("the oldest are dropped", loaded.observations.containsKey("src/File1.kt"))
    }

    @Test
    fun savingLeavesNoTemporaryFileBehind() {
        val file = tempFile()
        val store = EvidenceStore.forTest(file)
        store.save(mapOf("a" to EvidenceReconciler.Observation("a", "d", 1L, "s1", 1, "read_file")))
        val tmp = File(file.parentFile, file.name + ".tmp")
        assertFalse(tmp.exists())
        assertTrue(file.exists())
    }

    @Test
    fun aFailedSaveNeverTurnsUnknownEvidenceIntoSuccess() {
        val file = tempFile()
        val store = EvidenceStore.forTest(file)
        // Make the parent a file so the store cannot create its directory.
        val blocked = File(file.parentFile, "blocked")
        blocked.writeText("x")
        val broken = EvidenceStore.forTest(File(blocked, "evidence.json"))
        store.save(emptyMap())
        broken.save(mapOf("a" to EvidenceReconciler.Observation("a", "d", 1L, "s1", 1, "read_file")))
        assertTrue(broken.load().observations.isEmpty())
    }
}
