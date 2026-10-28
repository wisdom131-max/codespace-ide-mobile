package com.codespace.ide.chat.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
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
}
