package com.codespace.ide.ui.screens

import com.codespace.ide.engine.ModelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 8a: the catalogue says what it knows and admits what it does not. */
class ModelCatalogueTest {

    @Test
    fun contextWindowsAreAbbreviatedByFloor() {
        assertEquals("131K", ModelCatalogue.contextLabel(131_072))
        assertEquals("1M", ModelCatalogue.contextLabel(1_000_000))
        assertEquals("1.04M", ModelCatalogue.contextLabel(1_048_576))
        assertEquals("8K", ModelCatalogue.contextLabel(8_192))
        assertEquals("900 tokens", ModelCatalogue.contextLabel(900))
        assertEquals("unknown", ModelCatalogue.contextLabel(0))
    }

    @Test
    fun unknownToolSupportIsNotReportedAsNoTools() {
        val unknown = ModelCapability("mystery-model", 32_000, supportsImages = false, supportsPdf = false)
        val entry = ModelCatalogue.entries(listOf(unknown)).single()
        assertEquals("tools not verified", entry.toolsLabel)
    }

    @Test
    fun aModelWithNoVerifiedProviderSaysAnyProvider() {
        val row = ModelCapability("anywhere", 8_000, supportsImages = false, supportsPdf = false)
        assertEquals("any provider", ModelCatalogue.entries(listOf(row)).single().providers)
    }

    @Test
    fun theFooterNeverCallsAnUnlistedModelUnsupported() {
        val footer = ModelCatalogue.footer()
        assertTrue(footer.contains("still works"))
        assertTrue(footer.contains("never strips tools"))
    }
}
