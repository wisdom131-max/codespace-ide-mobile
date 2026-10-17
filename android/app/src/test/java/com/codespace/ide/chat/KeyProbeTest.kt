package com.codespace.ide.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH5-P1 (advisor-approved spec 2026-10-17): "Test this key" may only say
 * "key accepted" when an AUTHENTICATED call proves it. Required cases:
 * public-200 endpoint + bad key -> NOT verified; keyed 401 -> REJECTED.
 * Plus the HF whoami proof (accepted/rejected), the non-HF anonymous
 * control call, and the key-never-displayed guarantees.
 */
class KeyProbeTest {

    /** Fake transport: maps (url, bearer) -> outcome; records every call. */
    private class FakeGet(
        var keyed: HttpGetOutcome,
        var anon: HttpGetOutcome? = null,
        var whoami: HttpGetOutcome? = null,
    ) : KeyProbe.HttpGet {
        val calls = mutableListOf<Pair<String, Boolean>>() // url, hadBearer
        override suspend fun get(url: String, bearer: String?): KeyProbe.HttpGetOutcome {
            calls.add(url to (bearer != null))
            return when {
                url.contains("whoami-v2") -> whoami ?: HttpGetOutcome(null)
                bearer != null -> keyed
                else -> anon ?: HttpGetOutcome(null)
            }
        }
    }

    @Test
    fun `public models list with bad key is NOT verified`() = runBlocking {
        // Advisor case 1: keyed 200 + anonymous 200 = public endpoint.
        val fake = FakeGet(keyed = KeyProbe.HttpGetOutcome(200), anon = KeyProbe.HttpGetOutcome(200))
        val r = KeyProbe.probe("https://router.huggingface.co/v1", "hf_badgarbage", fake)
        assertTrue(r.message.contains("KEY NOT VERIFIED"))
        assertFalse(r.message.contains("ACCEPTED"))
        assertEquals(200, r.httpStatus)
        assertNull(r.accountName)
    }

    @Test
    fun `keyed 401 is REJECTED`() = runBlocking {
        // Advisor case 2.
        val fake = FakeGet(keyed = KeyProbe.HttpGetOutcome(401, verdict = "AI key rejected"))
        val r = KeyProbe.probe("https://api.example.com/v1", "sk-bad", fake)
        assertTrue(r.message.contains("KEY REJECTED"))
        assertFalse(r.message.contains("NOT VERIFIED"))
        assertEquals(401, r.httpStatus)
    }

    @Test
    fun `hf whoami 200 proves the key and shows the account`() = runBlocking {
        val fake = FakeGet(
            keyed = KeyProbe.HttpGetOutcome(200),
            whoami = KeyProbe.HttpGetOutcome(200, body = """{"name":"wisdom5","type":"user"}"""),
        )
        val r = KeyProbe.probe("https://router.huggingface.co/v1", "hf_goodkey", fake)
        assertTrue(r.message.contains("KEY ACCEPTED"))
        assertEquals("wisdom5", r.accountName)
    }

    @Test
    fun `hf garbage key gets whoami 401 and is REJECTED`() = runBlocking {
        // The owner's exact device case: public models list 200, whoami 401.
        val fake = FakeGet(
            keyed = KeyProbe.HttpGetOutcome(200),
            whoami = KeyProbe.HttpGetOutcome(401),
        )
        val r = KeyProbe.probe("https://router.huggingface.co/v1", "hf_garbage", fake)
        assertTrue(r.message.contains("KEY REJECTED"))
        assertFalse(r.message.contains("KEY ACCEPTED —"))
        assertNull(r.accountName)
    }

    @Test
    fun `non-HF endpoint enforcing auth accepts the key`() = runBlocking {
        val fake = FakeGet(
            keyed = KeyProbe.HttpGetOutcome(200),
            anon = KeyProbe.HttpGetOutcome(401),
        )
        val r = KeyProbe.probe("https://api.example.com/v1", "sk-good", fake)
        assertTrue(r.message.contains("KEY ACCEPTED"))
        assertNull(r.accountName)
    }

    @Test
    fun `keyed 500 passes the classified verdict through`() = runBlocking {
        val fake = FakeGet(keyed = KeyProbe.HttpGetOutcome(500, verdict = "server error class"))
        val r = KeyProbe.probe("https://api.example.com/v1", "sk-any", fake)
        assertTrue(r.message.contains("server error class"))
        assertFalse(r.message.contains("KEY ACCEPTED"))
        assertFalse(r.message.contains("NOT VERIFIED"))
    }

    @Test
    fun `unreachable host reports unreachable`() = runBlocking {
        val fake = FakeGet(keyed = KeyProbe.HttpGetOutcome(null))
        val r = KeyProbe.probe("https://dead.example.com/v1", "sk-any", fake)
        assertNull(r.httpStatus)
        assertTrue(r.message.contains("unreachable"))
    }

    @Test
    fun `key never appears in any URL and anon call carries no bearer`() = runBlocking {
        val fake = FakeGet(
            keyed = KeyProbe.HttpGetOutcome(200),
            whoami = KeyProbe.HttpGetOutcome(200, body = """{"name":"acct"}"""),
        )
        val key = "hf_secret_value_xyz"
        val r = KeyProbe.probe("https://router.huggingface.co/v1", key, fake)
        // All keyed calls carried the bearer header; no URL contains the key.
        fake.calls.forEach { (url, hadBearer) ->
            assertFalse(url.contains(key))
        }
        assertTrue(fake.calls.all { it.second }) // models + whoami both keyed
        assertTrue(r.message.isEmpty().not() && !r.message.contains(key))
        // Non-HF path: the control call must be anonymous.
        val fake2 = FakeGet(keyed = KeyProbe.HttpGetOutcome(200), anon = KeyProbe.HttpGetOutcome(200))
        KeyProbe.probe("https://api.example.com/v1", "sk_secret", fake2)
        val anonCall = fake2.calls.single { !it.second }
        assertTrue(anonCall.first.endsWith("/models"))
    }

    @Test
    fun `invalid URL is rejected before any call`() = runBlocking {
        val fake = FakeGet(keyed = KeyProbe.HttpGetOutcome(200))
        val r = KeyProbe.probe("not a url", "sk-any", fake)
        assertEquals("invalid endpoint URL", r.message)
        assertEquals(0, fake.calls.size)
    }
}
