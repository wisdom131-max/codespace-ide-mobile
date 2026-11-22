package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackChainTest {

    private val chain = FallbackChain.Chain(listOf("a", "b", "c"))

    @Test
    fun itIsOffByDefault() {
        assertNull(FallbackChain.nextAfter(FallbackChain.Chain(listOf("a", "b")), "a", true, emptySet()))
    }

    @Test
    fun anEnabledChainMovesOn() {
        assertEquals("b", FallbackChain.nextAfter(chain.copy(enabled = true), "a", true, emptySet()))
    }

    @Test
    fun aFailureThatIsNotTheModelsFaultDoesNotMove() {
        assertNull(
            "a bad key fails the same way everywhere",
            FallbackChain.nextAfter(chain.copy(enabled = true), "a", false, emptySet()),
        )
    }

    @Test
    fun alreadyTriedModelsAreSkipped() {
        assertEquals("c", FallbackChain.nextAfter(chain.copy(enabled = true), "b", true, setOf("a")))
    }

    @Test
    fun anExhaustedChainIsNull() {
        assertNull(FallbackChain.nextAfter(chain.copy(enabled = true), "c", true, setOf("a", "b")))
    }
}
