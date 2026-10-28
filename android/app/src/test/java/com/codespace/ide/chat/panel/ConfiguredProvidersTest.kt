package com.codespace.ide.chat.panel

import com.codespace.ide.chat.keys.StoredCredentialsSnapshot
import com.codespace.ide.chat.keys.StoredEndpoint
import com.codespace.ide.chat.keys.StoredKeySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U1: what the panel considers "configured", built from real stored shapes. */
class ConfiguredProvidersTest {

    private fun slot(pid: String, n: Int? = null, has: Boolean = true) =
        StoredKeySlot(pid, n, com.codespace.ide.chat.keys.SlotNaming.primarySlot(pid) +
            (if (n != null) "_" + n else ""), has)

    private fun ep(id: String, enabled: Boolean = true) = StoredEndpoint(
        id, id, "https://" + id + ".example/v1/chat/completions", enabled, true,
        if (id == "default") "custom" else "custom_" + id)

    @Test
    fun onlyProvidersWithANonBlankKeyCount() {
        val snap = StoredCredentialsSnapshot(
            keySlots = listOf(slot("gemini"), slot("openai", has = false)),
            endpoints = emptyList(),
        )
        assertEquals(listOf("gemini"), configuredProvidersFrom(snap).withKey)
    }

    @Test
    fun preferenceOrderDecidesTheDefaultAmongSeveralKeys() {
        val snap = StoredCredentialsSnapshot(
            keySlots = listOf(slot("xai"), slot("gemini"), slot("anthropic")),
            endpoints = emptyList(),
        )
        assertEquals(listOf("gemini", "anthropic", "xai"), configuredProvidersFrom(snap).withKey)
    }

    @Test
    fun disabledEndpointsAreNotOfferedButKeptInTheStore() {
        val snap = StoredCredentialsSnapshot(
            keySlots = emptyList(),
            endpoints = listOf(ep("default"), ep("local", enabled = false)),
        )
        val cfg = configuredProvidersFrom(snap)
        assertEquals(listOf("custom" to "https://default.example/v1/chat/completions"), cfg.customEndpoints)
        assertTrue(snap.endpoints.any { it.id == "local" }) // kept, not orphaned
    }

    @Test
    fun poolSlotsAndPrimaryCollapseToOneProvider() {
        val snap = StoredCredentialsSnapshot(
            keySlots = listOf(slot("gemini"), slot("gemini", 2), slot("gemini", 3)),
            endpoints = emptyList(),
        )
        assertEquals(listOf("gemini"), configuredProvidersFrom(snap).withKey)
    }
}
