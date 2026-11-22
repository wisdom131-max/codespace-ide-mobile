package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePolicyTest {

    private val reader = RolePolicy.Role("reader", setOf("read_file", "search"))
    private val owner = RolePolicy.Role("owner", emptySet(), allowAll = true)

    @Test
    fun aListedToolIsPermitted() {
        assertTrue(RolePolicy.permits(reader, "read_file"))
    }

    @Test
    fun anUnlistedToolIsRefused() {
        assertFalse(RolePolicy.permits(reader, "write_file"))
    }

    @Test
    fun allowAllPermitsEverything() {
        assertTrue(RolePolicy.permits(owner, "delete_everything"))
    }

    @Test
    fun filteringKeepsTheOriginalOrder() {
        val tools = listOf("write_file", "search", "read_file")
        assertEquals(listOf("search", "read_file"), RolePolicy.filter(reader, tools))
    }
}
