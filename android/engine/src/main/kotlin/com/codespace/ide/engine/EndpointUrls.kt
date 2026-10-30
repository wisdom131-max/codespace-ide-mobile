package com.codespace.ide.engine

/**
 * ONE place that turns a configured base URL into the URL actually requested.
 *
 * WHY THIS EXISTS (2026-10-29 audit): the presets store a FULL endpoint path
 * ("https://api.openai.com/v1/chat/completions") while a custom endpoint's form asks the
 * user for a BASE URL ("https://api.groq.com/openai/v1"). `OpenAIAdapter.endpoint` sent
 * whatever it was given verbatim, so every custom endpoint POSTed to ".../v1" and got a
 * 404/405 - while model discovery appended "/models" to the same base, which only works
 * for the base form. The two halves demanded incompatible URL shapes, which is why a
 * correctly filled custom endpoint could never work.
 *
 * The archive had this (CustomOpenAiProvider.chatUrl/modelsUrl, "saved once, applied
 * everywhere"); the rebuild lost it with the old provider layer. VS Code's Custom Endpoint
 * provider documents the same split: a provider `baseUrl` used for discovery plus per-model
 * entries, with the API type deciding the path.
 *
 * Rules: trim, drop trailing slashes, never duplicate a path segment, and accept BOTH
 * forms so an existing preset or a pasted full URL keeps working.
 */
object EndpointUrls {

    private const val CHAT = "/chat/completions"
    private const val MODELS = "/models"
    private const val MESSAGES = "/v1/messages"

    /** The POST target for an OpenAI-compatible provider. */
    fun chatCompletions(baseUrl: String): String {
        val (b, q) = splitQuery(baseUrl)
        if (b.isEmpty()) return b
        if (b.endsWith(CHAT)) return withQuery(b, q)
        // A pasted /models URL is corrected rather than sent as-is.
        if (b.endsWith(MODELS)) return withQuery(b.removeSuffix(MODELS) + CHAT, q)
        return withQuery(b + CHAT, q)
    }

    /** The GET target for model discovery. Works from a base, a chat path or a /models path. */
    fun models(baseUrl: String): String {
        val (b, q) = splitQuery(baseUrl)
        if (b.isEmpty()) return b
        if (b.endsWith(MODELS)) return withQuery(b, q)
        if (b.endsWith(CHAT)) return withQuery(b.removeSuffix(CHAT) + MODELS, q)
        return withQuery(b + MODELS, q)
    }

    /** The POST target for the Anthropic Messages API (x-api-key auth). */
    fun messages(baseUrl: String): String {
        val (b, q) = splitQuery(baseUrl)
        if (b.isEmpty()) return b
        if (b.endsWith("/messages")) return withQuery(b, q)
        // A base that already carries the version segment must not gain a second one.
        if (b.endsWith("/v1")) return withQuery(b + "/messages", q)
        return withQuery(b + MESSAGES, q)
    }

    /**
     * Gemini's streamGenerateContent target. The version segment is Gemini's own, so a
     * base that already ends in /v1beta (pasted from the docs) is not doubled.
     */
    fun geminiStream(baseUrl: String, modelId: String): String {
        // The query is NOT carried here: Gemini's stream route takes exactly `alt=sse`, and a
        // second "?" would be invalid. Anything the user typed in a query is dropped on purpose.
        var b = splitQuery(baseUrl).first
        if (b.endsWith("/v1beta")) b = b.removeSuffix("/v1beta")
        if (b.endsWith("/v1")) b = b.removeSuffix("/v1")
        return b + "/v1beta/models/" + modelId + ":streamGenerateContent?alt=sse"
    }

    /**
     * Settings-time validation. Deliberately shallow: scheme + a host, nothing about paths,
     * because providers differ and the server is the authority on its own routes.
     */
    fun validBase(baseUrl: String): Boolean {
        val b = splitQuery(baseUrl).first
        if (!b.startsWith("http://") && !b.startsWith("https://")) return false
        val rest = b.removePrefix("https://").removePrefix("http://")
        val host = rest.substringBefore('/')
        return host.isNotBlank() && !host.contains(' ')
    }

    /** Path only, for error text: never the query string (a key can live there). */
    fun redactedPath(url: String): String {
        val noQuery = splitQuery(url).first
        return if (noQuery.length <= 120) noQuery else noQuery.take(117) + "..."
    }

    /**
     * Split a configured URL into its PATH and its query, dropping any fragment. A query on a
     * base URL is meaningful (Azure-style `?api-version=...` gateways), so it is CARRIED, never
     * dropped and never straddled: appending a segment after the "?" produces the nonsense
     * `.../v1?x=1/chat/completions` that the first version of this file would have emitted.
     */
    private fun splitQuery(raw: String): Pair<String, String> {
        val noFragment = raw.trim().substringBefore('#')
        val q = noFragment.indexOf('?')
        return if (q < 0) noFragment.trimEnd('/') to ""
        else noFragment.substring(0, q).trimEnd('/') to noFragment.substring(q + 1)
    }

    /** Reattach a carried query to a resolved path. */
    private fun withQuery(path: String, query: String): String =
        if (query.isEmpty()) path else path + "?" + query
}
