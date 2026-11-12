package com.codespace.ide.agent.net

/**
 * P8 - CUSTOM REQUEST HEADERS, ON A LEASH.
 *
 * `web_fetch` handed whatever header JSON it was given straight to `HttpURLConnection`. Three things
 * go wrong with that, and all three are silent:
 *
 *  - **transport-owned headers** (`Host`, `Content-Length`, `Connection`, `Transfer-Encoding`,
 *    `Upgrade`, `Proxy-*`) are rejected by the platform or corrupt the request;
 *  - **credential collisions**: a caller-supplied `authorization` fights the token store's own
 *    header, and which one wins depends on iteration order;
 *  - **leaks**: an error message or a log line echoing the headers prints the credential.
 *
 * So: a blacklist that is not negotiable, a collision check that is case-insensitive (HTTP headers
 * are), and a redactor that is applied to anything that leaves this object as text.
 */
object HeaderPolicy {

    /** Never forwarded from a caller, whatever it asks for. Lowercase for comparison. */
    val forbidden: Set<String> = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "host", "content-length", "connection", "keep-alive", "transfer-encoding",
        "upgrade", "te", "trailer", "expect",
    )

    private val forbiddenPrefixes = listOf("proxy-", "sec-")

    /** Header names whose VALUE is a credential and must never be echoed. */
    private val secretish = setOf("authorization", "cookie", "x-api-key", "api-key", "x-auth-token")

    data class Decision(
        val accepted: Map<String, String>,
        val rejected: List<String>,
    ) {
        fun summary(): String = when {
            rejected.isEmpty() -> ""
            else -> "Ignored " + rejected.size + " request header(s): " + rejected.joinToString(", ") +
                ". Transport-owned and credential headers are managed here, not by the caller."
        }
    }

    fun isForbidden(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower in forbidden || forbiddenPrefixes.any { lower.startsWith(it) }
    }

    /**
     * Split what may be sent from what may not. A name that appears twice with different casing is a
     * collision and NEITHER copy is sent: picking one would make the outcome depend on map order.
     */
    fun apply(headers: Map<String, String>): Decision {
        val seen = HashMap<String, String>()
        val accepted = LinkedHashMap<String, String>()
        val rejected = ArrayList<String>()
        for ((rawName, value) in headers) {
            val name = rawName.trim()
            if (name.isEmpty()) continue
            val lower = name.lowercase()
            if (isForbidden(name)) { rejected.add(name); continue }
            val prior = seen.put(lower, name)
            if (prior != null && prior != name) {
                // Same header, two spellings: drop both rather than choose silently.
                accepted.remove(name)
                accepted.remove(prior)
                rejected.add(name + " (collides with " + prior + ")")
                continue
            }
            accepted[name] = value.trim()
        }
        return Decision(accepted, rejected.distinct())
    }

    /** For anything that becomes text: a value for a credential-bearing name is replaced whole. */
    fun redact(headers: Map<String, String>): Map<String, String> = headers.mapValues { (k, v) ->
        if (k.trim().lowercase() in secretish) "[redacted]" else v
    }

    /** A one-line, safe description of what was sent, for a tool result or a log. */
    fun describe(headers: Map<String, String>): String =
        if (headers.isEmpty()) "" else redact(headers).entries.joinToString(", ") { it.key + ": " + it.value }

    /**
     * P8 - MAY THE CALLER'S HEADERS FOLLOW THIS REDIRECT?
     *
     * A redirect is where a header for one API silently becomes a header for someone else's server.
     * They survive only when the hop stays on the same host over https; anything else drops them.
     */
    fun redirectKeepsCustomHeaders(fromUrl: String, toUrl: String): Boolean {
        fun hostOf(u: String): String? = runCatching { java.net.URL(u).host?.lowercase() }.getOrNull()
        fun secure(u: String): Boolean = runCatching { java.net.URL(u).protocol == "https" }.getOrDefault(false)
        val from = hostOf(fromUrl) ?: return false
        val to = hostOf(toUrl) ?: return false
        return from == to && secure(toUrl)
    }
}
