package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HydePromptTest {

    @Test
    fun thePromptCarriesTheQuestion() {
        assertTrue(HydePrompt.build("where is the token budget set").contains("where is the token budget set"))
    }

    @Test
    fun shortWordsAreNotSearchTerms() {
        val terms = HydePrompt.searchTerms("the cat sat on a mat")
        assertTrue(terms.none { it.length < 4 })
    }

    @Test
    fun termsAreDedupedAndCapped() {
        val terms = HydePrompt.searchTerms("gradle gradle compose compose dagger dagger hilt hilt", maxTerms = 2)
        assertEquals(listOf("gradle", "compose"), terms)
    }
}
