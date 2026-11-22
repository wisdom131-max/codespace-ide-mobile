package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractiveCompressorTest {

    @Test
    fun shortTextIsUntouched() {
        val text = "One. Two."
        val r = ExtractiveCompressor.compress(text, "anything", maxSentences = 5)
        assertEquals(text, r.text)
        assertEquals(2, r.total)
    }

    @Test
    fun theRelevantSentenceSurvives() {
        val text = "The weather is fine. Cats sleep a lot. Gradle dependency resolution failed here. " +
            "Roses are red. Violets are blue."
        val r = ExtractiveCompressor.compress(text, "gradle dependency failure", maxSentences = 1)
        assertTrue(r.text.contains("Gradle dependency"))
    }

    @Test
    fun itSaysHowMuchItDropped() {
        val text = (1..9).joinToString(" ") { "Sentence " + it + "." }
        val r = ExtractiveCompressor.compress(text, "sentence", maxSentences = 3)
        assertTrue(r.text.contains("6 sentences omitted"))
        assertEquals(3, r.kept)
    }
}
