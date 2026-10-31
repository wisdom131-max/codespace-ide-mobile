package com.codespace.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub's cache: what it keeps, and what it refuses to invent. */
class ConnectorStatusCacheTest {

    @Test
    fun `a status survives a round trip`() {
        val all = listOf(
            ConnectorsApiClient.ConnectorStatus(
                id = "gmail", name = "Gmail", connected = true, configured = true,
                scope = "mail", authType = "oauth", tokenHelpUrl = "https://x", tokenHint = "hint",
            ),
            ConnectorsApiClient.ConnectorStatus(
                id = "sentry", name = "Sentry", connected = false, configured = true,
                scope = null, authType = "pat",
            ),
        )
        val back = ConnectorStatusCache.decode(ConnectorStatusCache.encode(all))
        assertEquals(2, back.size)
        assertEquals("gmail", back[0].id)
        assertTrue(back[0].connected)
        assertEquals("mail", back[0].scope)
        assertEquals("pat", back[1].authType)
        assertEquals(null, back[1].scope)
    }

    @Test
    fun `an empty list encodes to an empty array`() {
        assertEquals("[]", ConnectorStatusCache.encode(emptyList()))
        assertTrue(ConnectorStatusCache.decode("[]").isEmpty())
    }

    @Test
    fun `garbage is an empty list, never a crash`() {
        assertTrue(ConnectorStatusCache.decode("not json").isEmpty())
        assertTrue(ConnectorStatusCache.decode("{\"a\":1}").isEmpty())
    }

    @Test
    fun `a row with no id is dropped rather than shown blank`() {
        val back = ConnectorStatusCache.decode("""[{"name":"Nameless"},{"id":"ok","name":"OK"}]""")
        assertEquals(1, back.size)
        assertEquals("ok", back[0].id)
    }
}
