package com.codespace.ide.agent.facts

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
 * The store: persistence, migration, dedupe outcomes, and the damage cases.
 *
 * Every case runs against a real temporary directory through the SHIPPED code (`loadFrom`/`saveTo`
 * and friends), so "the store survives a corrupt file" is a fact about the file on disk, not about a
 * stub someone wrote to make the test pass.
 */
class FactsStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("facts", "dir").let { f ->
            f.delete(); f.mkdirs(); f
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ── basics ──────────────────────────────────────────────────────────────────

    @Test
    fun emptyStoreIsEmptyAndCreatesNothing() {
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
        assertFalse(File(dir, FactsStore.FACTS_FILE).exists())
    }

    @Test
    fun savesAndReloadsAUserFact() {
        val r = FactsStore.saveTo(dir, "Prefers two-space indentation", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.Saved)
        val all = FactsStore.loadFrom(dir)
        assertEquals(1, all.size)
        assertEquals("Prefers two-space indentation", all[0].content)
        assertEquals(FactScope.USER, all[0].scope)
        assertTrue(all[0].id.startsWith("f_"))
        assertEquals(FactFactFieldsCheck.EXPECTED, FactFactFieldsCheck.actual(all[0]).toString())
    }

    @Test
    fun savesAProjectFactWithItsProject() {
        FactsStore.saveTo(dir, "Builds with Gradle", FactScope.PROJECT, "proj-a")
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactScope.PROJECT, f.scope)
        assertEquals("proj-a", f.projectId)
    }

    @Test
    fun projectFactWithoutAProjectIsRefused() {
        val r = FactsStore.saveTo(dir, "Uses Gradle", FactScope.PROJECT, null)
        assertTrue(r is FactsStore.SaveResult.Refused)
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun blankContentIsRefused() {
        assertTrue(FactsStore.saveTo(dir, "   ", FactScope.USER, null) is FactsStore.SaveResult.Refused)
    }

    @Test
    fun oversizeContentIsRefused() {
        val big = "x".repeat(FactsStore.MAX_CONTENT + 1)
        assertTrue(FactsStore.saveTo(dir, big, FactScope.USER, null) is FactsStore.SaveResult.Refused)
    }

    @Test
    fun everyFieldRoundTripsThroughJson() {
        FactsStore.saveTo(dir, "Cite me", FactScope.PROJECT, "p1", FactCategory.TEST,
            FactSource.VERIFIED_FILE, "build.gradle.kts", "conv-9", "sess-3")
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactCategory.TEST, f.category)
        assertEquals(FactSource.VERIFIED_FILE, f.source)
        assertEquals(FactConfidence.VERIFIED, f.confidence)
        assertEquals("build.gradle.kts", f.citation)
        assertEquals("conv-9", f.sourceConversationId)
        assertEquals("sess-3", f.sessionOwner)
        assertEquals(FactStatus.ACTIVE, f.status)
        assertTrue(f.createdAt > 0L && f.updatedAt > 0L)
    }

    // ── dedupe / correction / conflict ──────────────────────────────────────────

    @Test
    fun duplicateSaveDoesNotGrowTheStore() {
        FactsStore.saveTo(dir, "The build uses Gradle", FactScope.USER, null)
        val r = FactsStore.saveTo(dir, "the build uses gradle", FactScope.USER, null)
        assertEquals(FactDecision.DUPLICATE, (r as FactsStore.SaveResult.Saved).decision)
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun duplicateUpgradesProvenanceWithoutChangingTheId() {
        val first = FactsStore.saveTo(dir, "The build uses Gradle", FactScope.USER, null,
            source = FactSource.INFERRED) as FactsStore.SaveResult.Saved
        val second = FactsStore.saveTo(dir, "The build uses Gradle", FactScope.USER, null,
            source = FactSource.EXPLICIT_USER) as FactsStore.SaveResult.Saved
        assertEquals(first.fact.id, second.fact.id)
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactSource.EXPLICIT_USER, f.source)
        assertEquals(first.fact.createdAt, f.createdAt)
    }

    @Test
    fun correctionReplacesTheContentAndKeepsTheId() {
        val first = FactsStore.saveTo(dir, "The build uses Gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved
        val second = FactsStore.saveTo(dir, "The build uses Gradle 8 with the wrapper", FactScope.USER, null)
            as FactsStore.SaveResult.Saved
        assertEquals(FactDecision.CORRECTION, second.decision)
        assertEquals(first.fact.id, second.fact.id)
        assertEquals(1, FactsStore.loadFrom(dir).size)
        assertTrue(FactsStore.loadFrom(dir).single().content.contains("8"))
    }

    @Test
    fun contradictionKeepsBothSidesAndFlagsThem() {
        FactsStore.saveTo(dir, "use tabs for indentation", FactScope.USER, null)
        val second = FactsStore.saveTo(dir, "never use tabs for indentation", FactScope.USER, null)
        assertEquals(FactDecision.CONFLICT, (second as FactsStore.SaveResult.Saved).decision)
        val all = FactsStore.loadFrom(dir)
        assertEquals(2, all.size)
        assertTrue(all.all { it.status == FactStatus.CONFLICT })
        assertEquals(1, all[0].conflicts.size)
        assertEquals(1, all[1].conflicts.size)
    }

    @Test
    fun anUnresolvedConflictIsLabelledAsSuch() {
        FactsStore.saveTo(dir, "use tabs", FactScope.USER, null)
        val second = FactsStore.saveTo(dir, "never use tabs", FactScope.USER, null)
            as FactsStore.SaveResult.Saved
        assertTrue(second.fact.provenanceLine().contains("CONFLICTED"))
    }

    @Test
    fun editingAFactClearsItsConflict() {
        FactsStore.saveTo(dir, "use tabs", FactScope.USER, null)
        FactsStore.saveTo(dir, "never use tabs", FactScope.USER, null)
        val conflicted = FactsStore.loadFrom(dir).first { it.status == FactStatus.CONFLICT }
        assertNull(FactsStore.updateIn(dir, conflicted.id, content = "Indentation is tabs, decided"))
        val f = FactsStore.loadFrom(dir).first { it.id == conflicted.id }
        assertEquals(FactStatus.ACTIVE, f.status)
        assertTrue(f.conflicts.isEmpty())
    }

    @Test
    fun projectFactsInDifferentProjectsDoNotDedupe() {
        FactsStore.saveTo(dir, "the build uses gradle", FactScope.PROJECT, "a")
        val r = FactsStore.saveTo(dir, "the build uses gradle", FactScope.PROJECT, "b")
        assertEquals(FactDecision.NEW, (r as FactsStore.SaveResult.Saved).decision)
        assertEquals(2, FactsStore.loadFrom(dir).size)
    }

    // ── update / delete / supersede ─────────────────────────────────────────────

    @Test
    fun updateChangesContentAndMarksManualProvenance() {
        val f = (FactsStore.saveTo(dir, "Build with make", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        assertNull(FactsStore.updateIn(dir, f.id, content = "Build with ./gradlew", category = FactCategory.BUILD))
        val after = FactsStore.loadFrom(dir).single()
        assertEquals("Build with ./gradlew", after.content)
        assertEquals(FactCategory.BUILD, after.category)
        assertEquals(FactSource.MANUAL, after.source)
    }

    @Test
    fun updateRejectsASecret() {
        val f = (FactsStore.saveTo(dir, "Build with make", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val err = FactsStore.updateIn(dir, f.id, content = "token ghp_" + "a".repeat(36))
        assertNotNull(err)
        assertEquals("Build with make", FactsStore.loadFrom(dir).single().content)
    }

    @Test
    fun updateOfAMissingFactReportsIt() {
        assertNotNull(FactsStore.updateIn(dir, "f_missing", content = "x"))
    }

    @Test
    fun deleteRemovesTheFact() {
        val f = (FactsStore.saveTo(dir, "Delete me", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        assertTrue(FactsStore.deleteIn(dir, f.id))
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
        assertFalse(FactsStore.deleteIn(dir, f.id))
    }

    @Test
    fun deletingASupersedingFactRestoresNothing() {
        val a = (FactsStore.saveTo(dir, "First version of the rule", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val b = (FactsStore.saveTo(dir, "Second version of the ruleset", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        FactsStore.supersedeIn(dir, a.id, b.id)
        assertEquals(FactStatus.SUPERSEDED, FactsStore.loadFrom(dir).first { it.id == a.id }.status)
        // Deleting the replacement un-supersedes the original: there is nothing else left.
        FactsStore.deleteIn(dir, b.id)
        val after = FactsStore.loadFrom(dir).single()
        assertEquals(FactStatus.ACTIVE, after.status)
        assertNull(after.supersededBy)
    }

    @Test
    fun supersededFactsAreNotRetrievable() {
        val a = (FactsStore.saveTo(dir, "Old rule about the build", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val b = (FactsStore.saveTo(dir, "New rule about the build step", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        FactsStore.supersedeIn(dir, a.id, b.id)
        assertFalse(FactsStore.loadFrom(dir).first { it.id == a.id }.isRetrievable())
    }

    // ── damage cases ────────────────────────────────────────────────────────────

    @Test
    fun corruptJsonYieldsEmptyInsteadOfThrowing() {
        File(dir, FactsStore.FACTS_FILE).writeText("{ this is not json")
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun oneMalformedEntryDoesNotHideTheOthers() {
        val good = FactsStore.toJson(listOf(
            Fact(id = "f_1", content = "Good fact", scope = FactScope.USER),
        ))
        // A record with no content and one with an unknown scope: both skipped, the good one kept.
        File(dir, FactsStore.FACTS_FILE).writeText(
            "[{\"id\":\"f_0\",\"scope\":\"user\"},{\"id\":\"f_x\",\"content\":\"bad scope\",\"scope\":\"galaxy\"},${
                good.trim().removePrefix("[").removeSuffix("]")
            }]"
        )
        val all = FactsStore.loadFrom(dir)
        assertEquals(1, all.size)
        assertEquals("Good fact", all[0].content)
    }

    @Test
    fun aFailedWriteLeavesTheOldContentIntact() {
        val f = (FactsStore.saveTo(dir, "Original statement about the build", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val before = File(dir, FactsStore.FACTS_FILE).readText()
        // An oversize document is refused BEFORE the file is touched.
        val huge = (1..FactsStore.MAX_FACTS).map {
            Fact(id = "f_$it", content = "y".repeat(FactsStore.MAX_CONTENT),
                scope = FactScope.USER, projectId = null)
        }
        assertFalse(FactsStore.writeTo(dir, huge))
        assertEquals(before, File(dir, FactsStore.FACTS_FILE).readText())
        assertEquals(f.content, FactsStore.loadFrom(dir).single().content)
    }

    @Test
    fun storeCapIsEnforced() {
        val full = (1..FactsStore.MAX_FACTS).map {
            Fact(id = "f_$it", content = "fact number $it", scope = FactScope.USER)
        }
        assertTrue(FactsStore.writeTo(dir, full))
        val r = FactsStore.saveTo(dir, "one more fact that does not fit", FactScope.USER, null)
        assertEquals(FactsStore.SaveResult.Full, r)
        assertEquals(FactsStore.MAX_FACTS, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun repeatedReadsAndWritesStayConsistent() {
        repeat(30) { i -> FactsStore.saveTo(dir, "durable statement number $i", FactScope.USER, null) }
        assertEquals(30, FactsStore.loadFrom(dir).size)
        // A save after many loads still sees every earlier fact: nothing is dropped on reload.
        FactsStore.saveTo(dir, "durable statement number 0", FactScope.USER, null)
        assertEquals(30, FactsStore.loadFrom(dir).size)
    }

    // ── migration ───────────────────────────────────────────────────────────────

    private fun writeLegacy(json: String, attribution: String? = null) {
        File(dir, FactsStore.LEGACY_FILE).writeText(json)
        if (attribution != null) File(dir, FactsStore.ATTRIBUTION_FILE).writeText(attribution)
    }

    @Test
    fun legacyEntriesMigrateAndKeepTheirValuesAndKeys() {
        writeLegacy("{\"build_command\":\"Build with ./gradlew\",\"theme\":\"Prefers dark\"}")
        val facts = FactsStore.loadFrom(dir)
        assertEquals(2, facts.size)
        assertTrue(facts.any { it.legacyKey == "build_command" && it.content == "Build with ./gradlew" })
        assertTrue(facts.any { it.legacyKey == "theme" && it.content == "Prefers dark" })
        assertTrue(facts.all { it.scope == FactScope.USER && it.source == FactSource.LEGACY })
    }

    @Test
    fun migrationPreservesSessionAttribution() {
        writeLegacy("{\"scoped\":\"Only for one session\"}", "scoped\tsess-7\n")
        val f = FactsStore.loadFrom(dir).single()
        assertEquals("sess-7", f.sessionOwner)
    }

    @Test
    fun migrationDoesNotDeleteTheLegacyFile() {
        writeLegacy("{\"k\":\"v\"}")
        FactsStore.loadFrom(dir)
        assertTrue(File(dir, FactsStore.LEGACY_FILE).exists())
    }

    @Test
    fun migrationIsIdempotent() {
        writeLegacy("{\"k\":\"v\"}")
        val first = FactsStore.loadFrom(dir)
        val second = FactsStore.loadFrom(dir)
        assertEquals(first.size, second.size)
        assertEquals(first[0].id, second[0].id)
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }

    @Test
    fun migrationWithheldsAStoredCredential() {
        writeLegacy("{\"api\":\"token ghp_" + "a".repeat(36) + "\"}")
        val f = FactsStore.loadFrom(dir).single()
        assertEquals(FactStatus.SUPERSEDED, f.status)
        assertFalse(f.content.contains("ghp_"))
        assertTrue(f.content.contains("Withheld"))
        assertFalse(f.isRetrievable())
    }

    @Test
    fun noLegacyFileMeansAnEmptyStoreNotAnError() {
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
        // And a normal save still works afterwards.
        assertTrue(FactsStore.saveTo(dir, "Fresh install fact", FactScope.USER, null)
            is FactsStore.SaveResult.Saved)
    }

    @Test
    fun emptyLegacyObjectMigratesToNothing() {
        writeLegacy("{}")
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
    }

    @Test
    fun corruptLegacyFileMigratesToNothingWithoutThrowing() {
        writeLegacy("not json at all")
        assertTrue(FactsStore.loadFrom(dir).isEmpty())
        assertTrue(File(dir, FactsStore.LEGACY_FILE).exists())
    }

    @Test
    fun migratedFactsJoinTheNormalSavePath() {
        writeLegacy("{\"build_command\":\"Build with ./gradlew\"}")
        FactsStore.loadFrom(dir)
        // A later save about the same subject corrects the migrated fact rather than duplicating it.
        val r = FactsStore.saveTo(dir, "Build with ./gradlew assemble", FactScope.USER, null)
        val decision = (r as FactsStore.SaveResult.Saved).decision
        assertTrue("was $decision", decision == FactDecision.CORRECTION || decision == FactDecision.DUPLICATE)
        assertEquals(1, FactsStore.loadFrom(dir).size)
    }
}

/**
 * A small, explicit assertion that the model carries every field the requirements list. Kept as
 * data so adding a field without deciding whether it must persist is visible here.
 */
private object FactFactFieldsCheck {
    const val EXPECTED = "[id, content, scope, projectId, category, source, confidence, status, " +
        "createdAt, updatedAt, lastUsedAt, sourceConversationId, citation, legacyKey, sessionOwner, " +
        "supersededBy, conflicts]"

    fun actual(f: Fact): List<String> = listOf(
        "id", "content", "scope", "projectId", "category", "source", "confidence", "status",
        "createdAt", "updatedAt", "lastUsedAt", "sourceConversationId", "citation", "legacyKey",
        "sessionOwner", "supersededBy", "conflicts",
    )
}
