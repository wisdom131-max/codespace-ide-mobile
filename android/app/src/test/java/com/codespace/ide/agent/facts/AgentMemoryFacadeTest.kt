package com.codespace.ide.agent

import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactStatus
import com.codespace.ide.agent.facts.FactsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The facade: the old key/value contract, unchanged for every existing caller.
 *
 * The storage underneath is new; none of the OLD behaviour may move. Each test here is a behaviour
 * that existed before FACTS and must not regress because the bytes are now structured.
 */
class AgentMemoryFacadeTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("mem", "dir").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun saveThenGetReturnsTheValue() {
        val msg = AgentMemory.saveIn(dir, "build_command", "Build with ./gradlew")
        assertTrue(msg.startsWith("Memory saved"))
        assertEquals("Build with ./gradlew", AgentMemory.getIn(dir, "build_command"))
    }

    @Test
    fun savingTheSameKeyTwiceCorrectsItInsteadOfDuplicating() {
        AgentMemory.saveIn(dir, "theme", "Prefers light")
        val msg = AgentMemory.saveIn(dir, "theme", "Prefers dark")
        assertTrue(msg.startsWith("Memory updated"))
        assertEquals("Prefers dark", AgentMemory.getIn(dir, "theme"))
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun readAllListsKeysWithProvenance() {
        AgentMemory.saveIn(dir, "build_command", "Build with ./gradlew")
        val out = AgentMemory.readAllIn(dir)
        assertTrue(out.contains("build_command"))
        assertTrue(out.contains("Build with ./gradlew"))
        assertTrue(out.contains("you said"))
    }

    @Test
    fun readAllOnAnEmptyStoreSaysSo() {
        assertEquals("No memories saved yet.", AgentMemory.readAllIn(dir))
    }

    @Test
    fun deleteRemovesTheKeyAndReportsIt() {
        AgentMemory.saveIn(dir, "k", "v")
        assertTrue(AgentMemory.deleteIn(dir, "k").startsWith("Deleted memory"))
        assertNull(AgentMemory.getIn(dir, "k"))
        assertTrue(AgentMemory.deleteIn(dir, "k").startsWith("No memory found"))
    }

    @Test
    fun aBlankKeyIsRefused() {
        assertTrue(AgentMemory.saveIn(dir, "  ", "value").startsWith("A memory needs a key"))
    }

    @Test
    fun aSecretIsRefusedAndNotStored() {
        val msg = AgentMemory.saveIn(dir, "token", "ghp_" + "a".repeat(36))
        assertTrue(msg.startsWith("Memory not saved"))
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    // ── M5 session scope, unchanged ─────────────────────────────────────────────

    @Test
    fun aCurrentSessionMemoryIsInvisibleToAnotherSession() {
        AgentMemory.saveIn(dir, "mine", "only here", MemoryScope.CURRENT_SESSION, "sess-a")
        assertEquals("only here", AgentMemory.getIn(dir, "mine", MemoryScope.CURRENT_SESSION, "sess-a"))
        assertNull(AgentMemory.getIn(dir, "mine", MemoryScope.CURRENT_SESSION, "sess-b"))
    }

    @Test
    fun anAllSessionMemoryIsVisibleEverywhere() {
        AgentMemory.saveIn(dir, "shared", "everywhere", MemoryScope.ALL_SESSIONS, "sess-a")
        assertEquals("everywhere", AgentMemory.getIn(dir, "shared", MemoryScope.CURRENT_SESSION, "sess-b"))
        assertEquals("everywhere", AgentMemory.getIn(dir, "shared", MemoryScope.ALL_SESSIONS, "sess-b"))
    }

    @Test
    fun allSessionsScopeSeesSessionOwnedMemories() {
        AgentMemory.saveIn(dir, "mine", "only here", MemoryScope.CURRENT_SESSION, "sess-a")
        assertEquals("only here", AgentMemory.getIn(dir, "mine", MemoryScope.ALL_SESSIONS, "sess-b"))
    }

    @Test
    fun aScopeThatCannotSeeAMemoryCannotDeleteIt() {
        AgentMemory.saveIn(dir, "mine", "only here", MemoryScope.CURRENT_SESSION, "sess-a")
        val msg = AgentMemory.deleteIn(dir, "mine", MemoryScope.CURRENT_SESSION, "sess-b")
        assertTrue(msg.contains("in this scope"))
        assertNotNull(AgentMemory.getIn(dir, "mine", MemoryScope.CURRENT_SESSION, "sess-a"))
    }

    @Test
    fun readAllUnderCurrentSessionHidesOtherSessions() {
        AgentMemory.saveIn(dir, "a", "mine", MemoryScope.CURRENT_SESSION, "sess-a")
        AgentMemory.saveIn(dir, "b", "theirs", MemoryScope.CURRENT_SESSION, "sess-b")
        val out = AgentMemory.readAllIn(dir, MemoryScope.CURRENT_SESSION, "sess-a")
        assertTrue(out.contains("a:"))
        assertFalse(out.contains("b:"))
    }

    @Test
    fun visibleFactsHonoursProjectIsolation() {
        FactsStore.saveTo(dir, "Uses gradle", FactScope.PROJECT, "proj-a")
        FactsStore.saveTo(dir, "Uses maven", FactScope.PROJECT, "proj-b")
        FactsStore.saveTo(dir, "Prefers dark", FactScope.USER, null)
        val inA = AgentMemory.visibleFactsIn(dir, projectId = "proj-a").map { it.content }
        assertEquals(2, inA.size)
        assertTrue(inA.contains("Uses gradle"))
        assertTrue(inA.contains("Prefers dark"))
        assertFalse(inA.contains("Uses maven"))
    }

    @Test
    fun supersededMemoryIsNotListed() {
        val a = (FactsStore.saveTo(dir, "Old rule about builds", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val b = (FactsStore.saveTo(dir, "New rule about build steps", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        FactsStore.supersedeIn(dir, a.id, b.id)
        val contents = AgentMemory.visibleFactsIn(dir).map { it.content }
        assertFalse(contents.contains("Old rule about builds"))
    }

    @Test
    fun memoriesSurviveARestartBecauseTheyAreOnDisk() {
        AgentMemory.saveIn(dir, "build_command", "Build with ./gradlew")
        // A "restart" is a new read of the same directory: no in-memory cache is involved.
        assertEquals("Build with ./gradlew", AgentMemory.getIn(dir, "build_command"))
        assertEquals(1, FactsStore.loadFrom(dir).size)
        assertEquals(FactStatus.ACTIVE, FactsStore.loadFrom(dir).single().status)
    }

    @Test
    fun theLegacyKeyIsAddressableAfterCorrection() {
        AgentMemory.saveIn(dir, "k", "first value about builds")
        AgentMemory.saveIn(dir, "k", "second value about builds")
        assertNotNull(FactsStore.loadFrom(dir).firstOrNull { it.legacyKey == "k" })
    }
}
