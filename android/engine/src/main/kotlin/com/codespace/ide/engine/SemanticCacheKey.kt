package com.codespace.ide.engine

object SemanticCacheKey {
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val RUNS = Regex(" +")
    fun of(request: String, modelId: String): String {
        val norm = RUNS.replace(NON_WORD.replace(request.lowercase(), " "), " ").trim()
        return modelId + "|" + norm
    }
}
