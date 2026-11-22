package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFilterTest {

    private val docs = listOf(
        MetadataFilter.Doc("a", "app/src/main/java/chat/Panel.kt", "kotlin"),
        MetadataFilter.Doc("b", "app/src/main/java/chat/notes.md", "markdown"),
        MetadataFilter.Doc("c", "engine/src/main/kotlin/Engine.kt", "kotlin"),
        MetadataFilter.Doc("d", "README.md", "markdown"),
    )

    @Test
    fun anEmptySpecRestrictsNothing() {
        assertEquals(docs, MetadataFilter.apply(docs, MetadataFilter.Spec()))
    }

    @Test
    fun anExtensionNarrowsTheList() {
        val out = MetadataFilter.apply(docs, MetadataFilter.Spec(extensions = setOf("kt")))
        assertEquals(listOf("a", "c"), out.map { it.id })
    }

    @Test
    fun aPathFilterNarrowsTheList() {
        val out = MetadataFilter.apply(docs, MetadataFilter.Spec(pathContains = listOf("engine/")))
        assertEquals(listOf("c"), out.map { it.id })
    }

    @Test
    fun anExclusionWinsOverAMatch() {
        val out = MetadataFilter.apply(
            docs,
            MetadataFilter.Spec(extensions = setOf("kt"), pathExcludes = listOf("chat/")),
        )
        assertEquals("chat must be excluded even though it matches .kt", listOf("c"), out.map { it.id })
    }

    @Test
    fun aDocumentWithNoLanguageIsDroppedOnlyWhenALanguageIsAskedFor() {
        val withNull = docs + MetadataFilter.Doc("e", "x/Unknown.kt", null)
        assertTrue(MetadataFilter.apply(withNull, MetadataFilter.Spec()).any { it.id == "e" })
        assertTrue(MetadataFilter.apply(withNull, MetadataFilter.Spec(languages = setOf("kotlin")))
            .none { it.id == "e" })
    }

    @Test
    fun aQueryCanProduceASpec() {
        val spec = MetadataFilter.specFromQuery("find the retry logic in engine/src/main .kt")
        assertTrue("extension read", spec.extensions.contains("kt"))
        assertTrue("directory read", spec.pathContains.any { it.contains("engine/src/main") })
    }

    @Test
    fun anOrdinaryWordIsNotMistakenForAnExtension() {
        val spec = MetadataFilter.specFromQuery("where is the agent loop")
        assertTrue("no extension invented", spec.extensions.isEmpty())
    }
}
