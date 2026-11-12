package com.codespace.ide.agent

import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactStatus
import com.codespace.ide.agent.facts.FactsStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `save_memory` with its new arguments: scope, category and citation.
 *
 * The tool NAME and its dispatch are unchanged on purpose. A second save tool would have split the
 * approval gate in two and given the model two ways to say the same thing.
 */
class MemoryToolsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("tools", "dir").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun args(json: String) = JSONObject(json)

    @Test
    fun userScopeSaveIsTheDefault() {
        val msg = MemoryTools.saveIn(
            dir, args("""{"key":"build_command","value":"Build with ./gradlew"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        assertTrue(msg.startsWith("Memory saved"))
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactScope.USER, f.scope)
        assertEquals("build_command", f.legacyKey)
    }

    @Test
    fun projectScopeSaveUsesTheOpenProject() {
        val msg = MemoryTools.saveIn(
            dir,
            args("""{"key":"build","value":"Build with ./gradlew assembleDevDebug","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        assertTrue(msg.contains("this project"))
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactScope.PROJECT, f.scope)
        assertEquals("proj-a", f.projectId)
    }

    @Test
    fun projectScopeWithoutAProjectIsExplainedNotSilentlyDowngraded() {
        val msg = MemoryTools.saveIn(
            dir, args("""{"value":"Build with gradle","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        assertTrue(msg.contains("no project open"))
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun categoryAndCitationAreStored() {
        MemoryTools.saveIn(
            dir,
            args("""{"key":"tests","value":"Run ./gradlew test before pushing","category":"test","citation":"build.gradle.kts"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactCategory.TEST, f.category)
        assertEquals("build.gradle.kts", f.citation)
    }

    @Test
    fun anUnknownCategoryFallsBackToGeneralRatherThanFailing() {
        MemoryTools.saveIn(
            dir, args("""{"key":"k","value":"Something worth remembering","category":"galaxy"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        assertEquals(FactCategory.GENERAL, FactsStore.loadFrom(dir).single().category)
    }

    @Test
    fun missingValueIsRefusedWithAnExample() {
        val msg = MemoryTools.saveIn(dir, args("""{"key":"k"}"""), MemoryScope.ALL_SESSIONS, "", null)
        assertTrue(msg.contains("value"))
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun aMissingKeyIsDerivedFromTheContentSoTheFactIsFindable() {
        MemoryTools.saveIn(
            dir, args("""{"value":"Prefers two-space indentation"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        val f = FactsStore.loadFrom(dir).single()
        assertTrue(f.legacyKey != null && f.legacyKey!!.isNotBlank())
    }

    @Test
    fun aSecretIsRefused() {
        val msg = MemoryTools.saveIn(
            dir, args("""{"key":"token","value":"token ghp_""" + "a".repeat(36) + """"}"""),
            MemoryScope.ALL_SESSIONS, "", null,
        )
        assertTrue(msg.startsWith("Memory not saved"))
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun aDuplicateSaveReportsThatNothingWasDuplicated() {
        MemoryTools.saveIn(
            dir, args("""{"value":"Build with ./gradlew","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        val msg = MemoryTools.saveIn(
            dir, args("""{"value":"Build with ./gradlew","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        assertTrue(msg.contains("already saved"))
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun aCorrectionIsReportedAsACorrection() {
        MemoryTools.saveIn(
            dir, args("""{"value":"The build uses Gradle","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        val msg = MemoryTools.saveIn(
            dir, args("""{"value":"The build uses Gradle 8 with the wrapper","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        assertTrue(msg.contains("corrected"))
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun aContradictionIsReportedAsAConflict() {
        MemoryTools.saveIn(
            dir, args("""{"value":"use tabs for indentation","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        val msg = MemoryTools.saveIn(
            dir, args("""{"value":"never use tabs for indentation","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        assertTrue(msg.contains("CONTRADICTS"))
        assertTrue(FactsStore.loadFrom(dir).all { it.status == FactStatus.CONFLICT })
    }

    @Test
    fun currentSessionScopeIsRecordedOnTheFact() {
        MemoryTools.saveIn(
            dir, args("""{"value":"Only for this session","scope":"project"}"""),
            MemoryScope.CURRENT_SESSION, "sess-9", "proj-a",
        )
        assertEquals("sess-9", FactsStore.loadFrom(dir).single().sessionOwner)
    }

    @Test
    fun aSecretCannotReachTheStoreThroughEitherScope() {
        MemoryTools.saveIn(
            dir, args("""{"value":"password=hunter2hunter2","scope":"project"}"""),
            MemoryScope.ALL_SESSIONS, "", "proj-a",
        )
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun theToolIsStillGatedAndNeverReadOnly() {
        // Permission classification lives in one place; this pins the classification itself.
        assertFalse(com.codespace.ide.agent.ChatPermissionStore.SAFE_TOOLS.contains("save_memory"))
    }
}
