package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptRegistryTest {

    @Test
    fun aRegisteredPromptCanBeRead() {
        val r = PromptRegistry.Registry()
        r.register("agent", "you are an agent")
        assertEquals("you are an agent", r.get("agent")?.body)
    }

    @Test
    fun replacingBumpsTheVersion() {
        val r = PromptRegistry.Registry()
        assertEquals(1, r.register("agent", "v1").version)
        assertEquals(2, r.register("agent", "v2").version)
        assertEquals("v2", r.get("agent")?.body)
    }

    @Test
    fun anUnknownPromptIsNull() {
        assertNull(PromptRegistry.Registry().get("nothing"))
    }

    @Test
    fun namesKeepRegistrationOrder() {
        val r = PromptRegistry.Registry()
        r.register("a", "1")
        r.register("b", "2")
        assertEquals(listOf("a", "b"), r.names())
    }
}
