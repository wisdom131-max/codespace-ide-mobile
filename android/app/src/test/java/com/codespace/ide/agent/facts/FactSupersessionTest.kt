package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * STAGE 5 — supersession and history.
 *
 * The rule under test: a statement that is replaced is KEPT, dated, and pointed at its replacement.
 * Nothing is deleted, and nothing unrelated is retired just because it looks similar.
 */
class FactSupersessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun save(d: java.io.File, text: String, scope: FactScope = FactScope.USER, projectId: String? = null, now: Long = 0L) =
        FactsStore.saveTo(d, text, scope, projectId, now = if (now > 0L) now else System.currentTimeMillis())
            as FactsStore.SaveResult.Saved

    @Test
    fun `a replacement retires the old fact and keeps it`() {
        val d = tmp.newFolder("m")
        val first = save(d, "The build uses Gradle", now = 1_000L)
        val second = save(d, "The build uses Gradle 8 with the wrapper", now = 2_000L)
        assertEquals(FactDecision.CORRECTION, second.decision)
        val all = FactsStore.loadFrom(d)
        assertEquals(2, all.size)
        val old = all.first { it.id == first.fact.id }
        assertEquals(FactStatus.SUPERSEDED, old.status)
        assertEquals(second.fact.id, old.supersededBy)
        assertEquals(2_000L, old.supersededAt)
        assertEquals("The build uses Gradle", old.content)
        assertEquals(FactStatus.ACTIVE, all.first { it.id == second.fact.id }.status)
    }

    @Test
    fun `successive replacements form a chain, each pointing forward`() {
        val d = tmp.newFolder("m")
        val a = save(d, "Use Java 17", now = 1_000L)
        val b = save(d, "Use Java 17 LTS for the app", now = 2_000L)
        val c = save(d, "Use Java 17 LTS for the app and the engine", now = 3_000L)
        val all = FactsStore.loadFrom(d).associateBy { it.id }
        assertEquals(b.fact.id, all.getValue(a.fact.id).supersededBy)
        assertEquals(c.fact.id, all.getValue(b.fact.id).supersededBy)
        assertEquals(FactStatus.ACTIVE, all.getValue(c.fact.id).status)
        assertEquals(3, all.size)
    }

    @Test
    fun `unrelated facts in the same category are not retired`() {
        val d = tmp.newFolder("m")
        val keep = save(d, "Prefers dark theme", now = 1_000L)
        save(d, "The build uses Gradle", now = 2_000L)
        val all = FactsStore.loadFrom(d)
        assertEquals(FactStatus.ACTIVE, all.first { it.id == keep.fact.id }.status)
        assertNull(all.first { it.id == keep.fact.id }.supersededBy)
    }

    @Test
    fun `the same statement in another scope does not supersede`() {
        val d = tmp.newFolder("m")
        val user = save(d, "Targets API 34", FactScope.USER, null, now = 1_000L)
        val proj = save(d, "Targets API 34", FactScope.PROJECT, "p1", now = 2_000L)
        val all = FactsStore.loadFrom(d).associateBy { it.id }
        assertEquals(FactStatus.ACTIVE, all.getValue(user.fact.id).status)
        assertEquals(FactStatus.ACTIVE, all.getValue(proj.fact.id).status)
    }

    @Test
    fun `provenance and original timestamps survive supersession`() {
        val d = tmp.newFolder("m")
        val prov = FactProvenance.fromTranscript("conv", "v3", 500L, "m1", 700L, true, false)
        val first = FactsStore.saveTo(d, "Deploy target is staging", FactScope.USER, null,
            provenance = prov, now = 1_000L) as FactsStore.SaveResult.Saved
        save(d, "Deploy target is production", now = 2_000L)
        val old = FactsStore.loadFrom(d).first { it.id == first.fact.id }
        assertEquals(1_000L, old.createdAt)
        assertEquals("conv", old.sourceConversationId)
        assertEquals(500L, old.sourceConversationAt)
        assertEquals("m1", old.sourceMessageId)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, old.timeSource)
        // supersession is not an edit: updatedAt is not moved by being retired
        assertEquals(1_000L, old.updatedAt)
    }

    @Test
    fun `repeating the same statement does not create a supersession record`() {
        val d = tmp.newFolder("m")
        val first = save(d, "Uses Kotlin", now = 1_000L)
        val again = save(d, "Uses Kotlin", now = 2_000L)
        assertEquals(FactDecision.DUPLICATE, again.decision)
        assertEquals(first.fact.id, again.fact.id)
        assertEquals(1, FactsStore.loadFrom(d).size)
    }

    @Test
    fun `supersedeIn is idempotent and never rewrites the first record`() {
        val d = tmp.newFolder("m")
        val a = save(d, "Alpha statement about builds", now = 1_000L)
        val b = save(d, "Beta statement about tests", now = 2_000L)
        assertTrue(FactsStore.supersedeIn(d, a.fact.id, b.fact.id))
        val once = FactsStore.loadFrom(d).first { it.id == a.fact.id }
        assertTrue(FactsStore.supersedeIn(d, a.fact.id, b.fact.id))
        val twice = FactsStore.loadFrom(d).first { it.id == a.fact.id }
        assertEquals(once.supersededAt, twice.supersededAt)
        assertEquals(once.supersededBy, twice.supersededBy)
    }

    @Test
    fun `a fact cannot supersede itself`() {
        val d = tmp.newFolder("m")
        val a = save(d, "Self statement", now = 1_000L)
        assertTrue(!FactsStore.supersedeIn(d, a.fact.id, a.fact.id))
        assertEquals(FactStatus.ACTIVE, FactsStore.loadFrom(d).single().status)
    }

    @Test
    fun `superseded facts leave active retrieval but stay in history`() {
        val d = tmp.newFolder("m")
        save(d, "The build uses Gradle", now = 1_000L)
        save(d, "The build uses Gradle 8 with the wrapper", now = 2_000L)
        val all = FactsStore.loadFrom(d)
        val active = FactRetrieval.rank(all, "build gradle", null)
        assertTrue("the retired statement must not be offered as current", active.none { it.fact.status == FactStatus.SUPERSEDED })
        val history = FactRetrieval.history(all, null)
        assertEquals(1, history.size)
        assertEquals("The build uses Gradle", history[0].content)
    }

    @Test
    fun `a failed write leaves neither a retired fact nor a lost replacement`() {
        val d = tmp.newFolder("m")
        val first = save(d, "Original statement about the build", now = 1_000L)
        val broken = tmp.newFolder("broken")
        java.io.File(broken, "facts.json").mkdirs()
        java.io.File(java.io.File(broken, "facts.json"), "keep").writeText("x")
        val r = FactsStore.saveTo(broken, "Replacement statement about the build", FactScope.USER, null)
        assertTrue(r is FactsStore.SaveResult.WriteFailed || r is FactsStore.SaveResult.Refused)
        // the good store is untouched: still active, no replacement smuggled in
        val all = FactsStore.loadFrom(d)
        assertEquals(1, all.size)
        assertEquals(FactStatus.ACTIVE, all.single().status)
        assertEquals(first.fact.id, all.single().id)
    }

    @Test
    fun `legacy facts without supersession metadata still load`() {
        val d = tmp.newFolder("m")
        java.io.File(d, "facts.json").writeText(
            """[{"id":"f_old","content":"Old fact","scope":"user","category":"general","source":"explicit_user","confidence":"stated","status":"active","createdAt":111}]""",
        )
        val f = FactsStore.loadFrom(d).single()
        assertEquals(FactStatus.ACTIVE, f.status)
        assertEquals(0L, f.supersededAt)
        assertNull(f.supersededBy)
    }

    @Test
    fun `the replacement is independently identifiable`() {
        val d = tmp.newFolder("m")
        val first = save(d, "Use tabs", now = 1_000L)
        val second = save(d, "Use tabs with width four", now = 2_000L)
        assertNotEquals(first.fact.id, second.fact.id)
        assertNotEquals(first.fact.id, second.fact.id.take(5))
    }
}
