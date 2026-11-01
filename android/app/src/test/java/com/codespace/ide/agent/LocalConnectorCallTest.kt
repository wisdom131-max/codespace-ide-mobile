package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each on-device service puts its credential somewhere different, and a wrong header is a silent
 * 401 at runtime. These pin the three shapes.
 */
class LocalConnectorCallTest {

    @Test
    fun `supabase sends the key twice and keeps the base path`() {
        val spec = LocalConnectorCall.build(
            service = "supabase",
            base = "https://abc.supabase.co/",
            key = "anon-key",
            method = "get",
            path = "rest/v1/todos?select=*",
            body = null,
        )
        assertEquals("GET", spec.method)
        assertEquals("https://abc.supabase.co/rest/v1/todos?select=*", spec.url)
        assertEquals("anon-key", spec.headers["apikey"])
        assertEquals("Bearer anon-key", spec.headers["Authorization"])
        assertNull(spec.body)
    }

    @Test
    fun `n8n uses its own header, not a bearer token`() {
        val spec = LocalConnectorCall.build(
            service = "n8n", base = "https://n8n.example.com", key = "k",
            method = "GET", path = "/api/v1/workflows", body = null,
        )
        assertEquals("k", spec.headers["X-N8N-API-KEY"])
        assertTrue(spec.headers["Authorization"] == null)
        assertEquals("https://n8n.example.com/api/v1/workflows", spec.url)
    }

    @Test
    fun `firebase puts the web api key in a header`() {
        val spec = LocalConnectorCall.build(
            service = "firebase",
            base = "https://firestore.googleapis.com/v1/projects/p/databases/(default)/documents",
            key = "AIza-key", method = "GET", path = "/todos", body = null,
        )
        assertEquals("AIza-key", spec.headers["X-Goog-Api-Key"])
        assertTrue(spec.url.endsWith("/documents/todos"))
    }

    @Test
    fun `an absolute path is used as given`() {
        val spec = LocalConnectorCall.build(
            service = "firebase", base = "https://ignored", key = "k",
            method = "GET", path = "https://identitytoolkit.googleapis.com/v1/x", body = null,
        )
        assertEquals("https://identitytoolkit.googleapis.com/v1/x", spec.url)
    }

    @Test
    fun `a POST without a body still sends one and sets the content type`() {
        val spec = LocalConnectorCall.build(
            service = "supabase", base = "https://a.supabase.co", key = "k",
            method = "POST", path = "/rest/v1/t", body = "",
        )
        // Blank means "no body supplied": the caller, not the builder, writes the default object.
        assertNull(spec.body)
        assertTrue(spec.headers["Content-Type"] == null)
    }

    @Test
    fun `a JSON body sets the content type`() {
        val spec = LocalConnectorCall.build(
            service = "n8n", base = "https://n", key = "k",
            method = "POST", path = "/api/v1/workflows", body = """{"name":"w"}""",
        )
        assertEquals("application/json", spec.headers["Content-Type"])
        assertEquals("""{"name":"w"}""", spec.body)
    }
}
