package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileScopeTest {
    @Test fun onlyTheCurrentProfile() {
        val p = ProfileScope("alice")
        assertTrue(p.accepts("alice"))
        assertFalse(p.accepts("bob"))
        assertEquals(listOf("alice:x"), p.filter(listOf("alice:x", "bob:y")) { it.substringBefore(58.toChar()) })
    }
}
