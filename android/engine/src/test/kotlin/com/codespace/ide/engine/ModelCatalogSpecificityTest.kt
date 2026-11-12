package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P5: the capability lookup used to be first-match-wins, so a shorter family id could win over the
 * longer id the model actually names, and the payload budget followed the wrong context window.
 */
class ModelCatalogSpecificityTest {

    @Test fun anExactIdIsReportedAsExact() {
        val r = ModelCatalog.resolve("gemini", "gemini-3.8-flash")
        assertEquals(ModelCatalog.Source.EXACT, r.source)
        assertNotNull(r.capability)
    }

    @Test fun aDatedOrVariantIdIsASubstringMatch() {
        val r = ModelCatalog.resolve("openrouter", "claude-sonnet-5.5-20260101")
        assertEquals(ModelCatalog.Source.SUBSTRING, r.source)
        assertEquals(1_000_000, r.capability!!.contextTokens)
    }

    @Test fun anUnknownModelHasNoCapabilityRatherThanAnInventedOne() {
        val r = ModelCatalog.resolve("custom", "some-new-model-9000")
        assertEquals(ModelCatalog.Source.UNKNOWN, r.source)
        assertNull(r.capability)
    }

    @Test fun aBlankIdIsUnknown() {
        assertEquals(ModelCatalog.Source.UNKNOWN, ModelCatalog.resolve("gemini", "  ").source)
    }

    @Test fun theMostSpecificRowWins() {
        // Two rows match "llama-3.3-70b-instruct": the family row and the exact one.
        val specific = ModelCatalog.resolve("openrouter", "llama-3.3-70b")
        assertEquals(131_072, specific.capability!!.contextTokens)
        val variant = ModelCatalog.resolve("openrouter", "meta/llama-3.3-70b:free")
        assertEquals(ModelCatalog.Source.SUBSTRING, variant.source)
        assertEquals(131_072, variant.capability!!.contextTokens)
    }

    @Test fun honestyIsSilentForAnExactMatchAndSpeaksOtherwise() {
        assertNull(ModelCatalog.honestyNote("gemini", "gemini-3.8-flash"))
        assertNotNull(ModelCatalog.honestyNote("openrouter", "claude-sonnet-5.5-20260101"))
        assertNotNull(ModelCatalog.honestyNote("custom", "unknown-model"))
    }

    @Test fun anUnknownModelStillGetsTheUsersOwnBudget() {
        assertEquals(48_000, ModelCatalog.payloadBudgetChars(ModelCatalog.capabilityFor("x", "nope")))
    }
}
