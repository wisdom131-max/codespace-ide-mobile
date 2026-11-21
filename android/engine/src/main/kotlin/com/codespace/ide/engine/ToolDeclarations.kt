package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.2 — the tool list the model is TOLD about, provider-neutral.
 *
 * The engine could already PARSE native tool calls ([ToolCallNormaliser], [ProviderStreamNormaliser])
 * but no adapter ever ADVERTISED a tool: there was no field on [EngineConfig] to carry a declaration,
 * so every provider was sent a text prompt and the model answered with a `<tool>` block. This type is
 * the missing half of that pair. A declaration that is never sent cannot produce a native call, which
 * is why parsing alone changed nothing.
 *
 * [parametersJson] is a JSON Schema object as TEXT, passed through verbatim. It is not parsed here on
 * purpose: this module ships no JSON library, and re-encoding a schema the caller already built is a
 * way to lose a field silently. An empty or blank value is replaced with the smallest valid schema so
 * a declaration can never make the request body invalid.
 */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val parametersJson: String = EMPTY_SCHEMA,
) {
    companion object {
        /** The smallest valid JSON Schema: an object with no properties. */
        const val EMPTY_SCHEMA: String = "{\"type\":\"object\",\"properties\":{}}"
    }

    /** The schema to send, never blank. */
    internal val schema: String
        get() = if (parametersJson.isBlank()) EMPTY_SCHEMA else parametersJson
}

/**
 * Insert a provider's tools fragment into a finished request body, immediately before the closing
 * brace.
 *
 * Splicing into the finished body rather than threading tools through every builder is deliberate:
 * each adapter has several payload variants (plain, attachments, tool-exchange history) and a variant
 * that forgets the tools is a silent regression that only shows up as "the agent stopped calling
 * tools when I attached a file". There is one seam per adapter ([ProviderAdapter.withTools]) and every
 * variant passes through it.
 *
 * An empty fragment returns the body unchanged, so an adapter with no tools sends byte-identical
 * bytes to what it sent before this existed.
 */
internal fun spliceTools(body: String, fragment: String): String {
    if (fragment.isEmpty()) return body
    val end = body.lastIndexOf('}')
    if (end < 0) return body
    return body.substring(0, end) + fragment + body.substring(end)
}
