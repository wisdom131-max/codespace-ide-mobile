package com.codespace.ide.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 (BATCH3): opt-in 402 key failover — behavioral tests against
 * ChatKeyFailover.executeWithCandidates (no SecureTokenStore/Context needed).
 * Setting OFF by default; one pass through the pool, no loops; never logs
 * or shows key values (only ordinals in "Switched to key N").
 */
class Chat402FailoverTest {

    private fun cleanup() {
        Chat402FailoverSetting.setEnabledForTest(false)
        ChatKeyFailover.clearCooldowns()
    }

    @Test
    fun key1_402_switchesToKey2_andSucceeds() {
        cleanup()
        Chat402FailoverSetting.setEnabledForTest(true)
        val seenKeys = mutableListOf<String>()
        val notices = mutableListOf<String>()
        var calls = 0
        val result = runBlocking {
            ChatKeyFailover.executeWithCandidates(
                candidates = listOf("s1" to "KEY_ONE", "s2" to "KEY_TWO"),
                hasStarted = { false },
                onInfo = { notices.add(it) },
            ) { key ->
                calls++
                seenKeys.add(key)
                if (key == "KEY_ONE") throw ChatHttpException(402, "out of credits")
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(listOf("KEY_ONE", "KEY_TWO"), seenKeys)
        assertEquals(1, notices.size)
        assertTrue("switch notice names the ordinal", notices[0].contains("Switched to key 2 of 2"))
        assertTrue("notice names the cause in plain words", notices[0].contains("out of credits (402)"))
        cleanup()
    }

    @Test
    fun poolExhausted_402_surfacesOutOfCreditsVerdict() {
        cleanup()
        Chat402FailoverSetting.setEnabledForTest(true)
        val notices = mutableListOf<String>()
        var calls = 0
        val he = runBlocking {
            try {
                ChatKeyFailover.executeWithCandidates(
                    candidates = listOf("s1" to "KEY_ONE", "s2" to "KEY_TWO"),
                    hasStarted = { false },
                    onInfo = { notices.add(it) },
                ) { key ->
                    calls++
                    throw ChatHttpException(402, "This request failed: the provider account for this key is OUT OF CREDITS (monthly limit reached or billing issue).")
                }
                null
            } catch (e: ChatHttpException) {
                e
            }
        }
        assertEquals(2, calls) // one pass through BOTH keys, then the verdict
        assertEquals("no switch notice when there is no next key", 0, notices.size)
        assertEquals(402, he!!.statusCode)
        assertTrue("verdict keeps the out-of-credits message", he.message.contains("OUT OF CREDITS"))
        assertTrue("verdict says no other working key", he.message.contains("no other working key"))
        cleanup()
    }

    @Test
    fun settingOff_402_stopsImmediately_noFailover() {
        cleanup()
        var calls = 0
        val he = runBlocking {
            try {
                ChatKeyFailover.executeWithCandidates(
                    candidates = listOf("s1" to "KEY_ONE", "s2" to "KEY_TWO"),
                    hasStarted = { false },
                ) { _ ->
                    calls++
                    throw ChatHttpException(402, "This request failed: the provider account for this key is OUT OF CREDITS.")
                }
                null
            } catch (e: ChatHttpException) {
                e
            }
        }
        assertEquals("OFF = the FIRST key's 402 is the verdict; key 2 never tried", 1, calls)
        assertEquals(402, he!!.statusCode)
        cleanup()
    }

    @Test
    fun midStream_402_neverRetries_dedupGuard() {
        cleanup()
        Chat402FailoverSetting.setEnabledForTest(true)
        var calls = 0
        val he = runBlocking {
            try {
                ChatKeyFailover.executeWithCandidates(
                    candidates = listOf("s1" to "KEY_ONE", "s2" to "KEY_TWO"),
                    hasStarted = { true }, // output already streamed
                ) { _ ->
                    calls++
                    throw ChatHttpException(402, "OUT OF CREDITS")
                }
                null
            } catch (e: ChatHttpException) {
                e
            }
        }
        assertEquals("streamed output is never re-attempted", 1, calls)
        assertEquals(402, he!!.statusCode)
        cleanup()
    }

    @Test
    fun defaultIsOff() {
        cleanup()
        assertTrue("setting ships OFF by default", !Chat402FailoverSetting.isEnabled())
    }
}
