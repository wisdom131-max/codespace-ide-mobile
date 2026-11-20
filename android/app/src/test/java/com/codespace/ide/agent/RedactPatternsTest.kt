package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactPatternsTest {

    private fun r(s: String) = CopilotDiag.redact(s)

    @Test fun `google api key is redacted`() {
        val out = r("failed with AIzaSyD-1234567890abcdefghijklmnopqrstu in body")
        assertFalse(out.contains("AIzaSyD-1234567890"))
    }

    @Test fun `aws access key id is redacted`() {
        assertFalse(r("creds AKIAIOSFODNN7EXAMPLE here").contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test fun `jwt is redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        assertFalse(r("token " + jwt).contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test fun `basic auth is redacted`() {
        assertFalse(r("Authorization: Basic dXNlcjpwYXNzd29yZDEyMw==").contains("dXNlcjpwYXNzd29yZDEyMw"))
    }

    @Test fun `a key in a url query is redacted`() {
        assertFalse(r("GET https://api.example/v1?key=SECRETVALUE123 failed").contains("SECRETVALUE123"))
    }

    @Test fun `client secret assignment is redacted`() {
        assertFalse(r("client_secret=abcd1234efgh").contains("abcd1234efgh"))
    }

    @Test fun `bearer token still redacted`() {
        assertFalse(r("Authorization: Bearer abcdefghijklmnop").contains("abcdefghijklmnop"))
    }

    @Test fun `sk style key still redacted`() {
        assertFalse(r("key sk-proj-abcdefghijklmnop").contains("abcdefghijklmnop"))
    }

    @Test fun `ordinary text survives untouched`() {
        val s = "tool write_file failed after 1200ms"
        assertEquals(s, r(s))
    }

    @Test fun `a plain word key is not over-redacted`() {
        assertTrue(r("keyboard shortcut").contains("keyboard"))
    }
}
