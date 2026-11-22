package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCacheKeyTest {
    @Test fun ignoresCaseAndPunctuation() {
        assertEquals(SemanticCacheKey.of("Fix  the BUG!", "m"), SemanticCacheKey.of("fix the bug", "m"))
    }
    @Test fun differentModelsDoNotShare() {
        assertTrue(SemanticCacheKey.of("x", "a") != SemanticCacheKey.of("x", "b"))
    }
}
