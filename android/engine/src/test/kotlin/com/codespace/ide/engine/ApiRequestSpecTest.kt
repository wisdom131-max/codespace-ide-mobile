package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRequestSpecTest {
    @Test fun rejectsPlainHttp() {
        val c = ApiRequestSpec.check(ApiRequestSpec.Spec(ApiRequestSpec.Method.GET, "http://x"))
        assertTrue(c is ApiRequestSpec.Check.Bad)
    }
    @Test fun rejectsGetWithBody() {
        val c = ApiRequestSpec.check(ApiRequestSpec.Spec(ApiRequestSpec.Method.GET, "https://x", body = "{}"))
        assertTrue(c is ApiRequestSpec.Check.Bad)
    }
    @Test fun acceptsPost() {
        assertEquals(ApiRequestSpec.Check.Ok, ApiRequestSpec.check(ApiRequestSpec.Spec(ApiRequestSpec.Method.POST, "https://x", body = "{}")))
    }
}
