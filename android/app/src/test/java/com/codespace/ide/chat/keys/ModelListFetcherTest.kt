package com.codespace.ide.chat.keys
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
/**
 * WP4: pure /models parsing + plain-words verdicts. NO network in CI - the
 * fetch path is a thin HttpURLConnection wrapper around the tested parse.
 */
class ModelListFetcherTest {
    @Test
    fun parsesOpenAiCompatibleModelsShape() {
        val body = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("id", "gpt-test-1"))
                .put(JSONObject().put("id", "llama-test-2"))
        ).toString()
        assertEquals(listOf("gpt-test-1", "llama-test-2"), ModelListFetcher.parseModelsJson(body))
    }
    @Test
    fun malformedBodyReturnsEmptyNeverThrows() {
        assertTrue(ModelListFetcher.parseModelsJson("not json at all").isEmpty())
        assertTrue(ModelListFetcher.parseModelsJson("{}").isEmpty())
        assertTrue(ModelListFetcher.parseModelsJson("{\"data\":\"nope\"}").isEmpty())
    }
    @Test
    fun blankAndMissingIdsAreSkipped() {
        val body = "{\"data\":[{\"id\":\"\"},{\"name\":\"no-id-field\"},{\"id\":\"real-1\"}]}"
        assertEquals(listOf("real-1"), ModelListFetcher.parseModelsJson(body))
    }
    @Test
    fun verdictsArePlainWordsWithoutKeyMaterial() {
        val ok = ModelListFetcher.verdictOf(ModelListFetcher.FetchResult.Models(listOf("a", "b")))
        assertTrue(ok.contains("2 models"))
        val rejected = ModelListFetcher.verdictOf(
            ModelListFetcher.FetchResult.Failed(401, "sk-secret-detail"))
        assertTrue(rejected.contains("key was rejected"))
        assertTrue(!rejected.contains("sk-secret-detail"))
        val notFound = ModelListFetcher.verdictOf(
            ModelListFetcher.FetchResult.Failed(404, ""))
        assertTrue(notFound.contains("/models was not found"))
        val netFail = ModelListFetcher.verdictOf(
            ModelListFetcher.FetchResult.Failed(null, "peer reset"))
        assertTrue(netFail.contains("Could not reach"))
    }
    // PHASE 1 (2026-11-17): the 405 cause and its correction.
    @org.junit.Test
    fun `cloudflare inference paths are detected and rerouted to the catalogue`() {
        val base = "https://api.cloudflare.com/client/v4/accounts/acct123/ai/run/@cf/meta/llama-3-8b-instruct"
        assertTrue(ModelListFetcher.isCloudflareAi(base))
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct123/ai/models/search",
            ModelListFetcher.cloudflareModelsUrl(base),
        )
    }
    @org.junit.Test
    fun `openai compatible urls are not treated as cloudflare`() {
        assertFalse(ModelListFetcher.isCloudflareAi("https://api.openai.com/v1"))
        assertNull(ModelListFetcher.cloudflareModelsUrl("https://api.openai.com/v1"))
    }
    @org.junit.Test
    fun `a cloudflare catalogue body parses to model names`() {
        val body = "{\"success\":true,\"result\":[{\"name\":\"@cf/meta/llama-3-8b-instruct\"}," +
            "{\"name\":\"@cf/baai/bge-small-en-v1.5\"}]}"
        assertEquals(
            listOf("@cf/meta/llama-3-8b-instruct", "@cf/baai/bge-small-en-v1.5"),
            ModelListFetcher.parseModelsJson(body),
        )
    }
    @org.junit.Test
    fun `a 405 verdict names the method problem instead of the bare code`() {
        val v = ModelListFetcher.verdictOf(ModelListFetcher.FetchResult.Failed(405, ""))
        assertTrue(v.contains("405"))
        assertTrue(v.contains("method"))
    }
}
