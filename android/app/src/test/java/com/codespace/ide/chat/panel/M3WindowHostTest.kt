package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 — the multi-window rules that must hold for a second Android task to be safe.
 *
 * The dangerous failure is a window closing and tearing down a runtime another window is still
 * rendering (which would cancel a stream mid-reply), so the reference count is tested directly
 * rather than assumed.
 */
class M3WindowHostTest {

    @Test
    fun `the first host creates the runtime and the second reuses it`() {
        val hosts = RefCounter()
        assertTrue("first acquire creates", hosts.acquire())
        assertFalse("second acquire reuses", hosts.acquire())
        assertEquals(2, hosts.count())
    }

    @Test
    fun `closing one window keeps the runtime alive for the other`() {
        val hosts = RefCounter()
        hosts.acquire() // sidebar
        hosts.acquire() // second task
        assertFalse("still in use", hosts.release())
        assertEquals(1, hosts.count())
        assertTrue("last one out destroys", hosts.release())
        assertEquals(0, hosts.count())
    }

    @Test
    fun `a stray release never underflows into a destroy`() {
        val hosts = RefCounter()
        assertFalse(hosts.release())
        assertEquals(0, hosts.count())
        assertTrue(hosts.acquire())
        assertTrue(hosts.release())
        assertFalse("already at zero", hosts.release())
    }

    @Test
    fun `reopening after everyone left starts a fresh count`() {
        val hosts = RefCounter()
        hosts.acquire()
        hosts.release()
        assertTrue("a new host must create again", hosts.acquire())
        assertEquals(1, hosts.count())
    }

    @Test
    fun `the chat tab sentinel cannot be mistaken for a file`() {
        assertTrue(ChatTabSupport.isChatTab(ChatTabSupport.TAB_ID))
        assertTrue(ChatTabSupport.TAB_ID.contains("://"))
        assertFalse(ChatTabSupport.isChatTab("/root/project/Main.kt"))
        assertFalse(ChatTabSupport.isChatTab(null))
        assertFalse(ChatTabSupport.isChatTab("chat"))
    }

    @Test
    fun `a file request from the second window is consumed exactly once`() {
        ChatFileOpenRequest.consume(ChatFileOpenRequest.pending.value ?: "")
        ChatFileOpenRequest.request("/root/p/A.kt")
        assertEquals("/root/p/A.kt", ChatFileOpenRequest.pending.value)
        // Consuming a different path must not drop the pending one.
        ChatFileOpenRequest.consume("/root/p/B.kt")
        assertEquals("/root/p/A.kt", ChatFileOpenRequest.pending.value)
        ChatFileOpenRequest.consume("/root/p/A.kt")
        assertNull(ChatFileOpenRequest.pending.value)
    }
}
