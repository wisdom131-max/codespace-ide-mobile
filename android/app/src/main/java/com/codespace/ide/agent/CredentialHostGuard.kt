package com.codespace.ide.agent

/**
 * P8.B - a credential only travels to the host it was issued for.
 *
 * `LocalConnectorCall.build` accepted an absolute URL from the model (`path.startsWith("http")`) and
 * then attached the project's Supabase service key, n8n API key or Firebase web key to whatever host
 * that URL named. A prompt could therefore post the user's key to a host of its choosing, and OkHttp
 * followed redirects by default, so even a correct first host could bounce the key somewhere else.
 *
 * This decides, per call, whether the target is a host the credential belongs to. It is pure so the
 * rule is asserted rather than inferred, and it fails closed: anything it cannot positively allow is
 * refused with a sentence the agent can act on.
 */
object CredentialHostGuard {

    sealed class Decision {
        data object Allowed : Decision()
        data class Refused(val reason: String) : Decision()
    }

    /** Hosts a given service's key may legitimately reach, beyond the configured base host itself. */
    private val EXTRA_HOSTS: Map<String, List<String>> = mapOf(
        // A Firebase web key is designed to be used from the client, but only against Google's own
        // API surface - not an arbitrary domain.
        "firebase" to listOf(
            "googleapis.com", "firebaseio.com", "firebaseapp.com",
            "identitytoolkit.googleapis.com", "securetoken.googleapis.com",
            "firebaseinstallations.googleapis.com",
        ),
    )

    /**
     * [base] is the URL the user configured for the service. [target] is what the call will actually
     * hit. The target must be https, and its host must be the base host or a documented host for that
     * service (a subdomain of one of them counts; a lookalike such as `googleapis.com.evil.tld` does
     * not, because the match is on label boundaries).
     */
    fun check(service: String, base: String, target: String): Decision {
        val targetUrl = parse(target) ?: return Refused("the target is not a valid URL")
        if (targetUrl.protocol != "https") {
            return Refused("only https is allowed for a credentialed call (target was " + targetUrl.protocol + ")")
        }
        val baseHost = parse(base)?.host ?: return Refused("the service's configured URL is invalid")
        val host = targetUrl.host
        if (host == baseHost || host.endsWith("." + baseHost)) return Decision.Allowed
        val extra = EXTRA_HOSTS[service.lowercase()].orEmpty()
        if (extra.any { host == it || host.endsWith("." + it) }) return Decision.Allowed
        return Refused(
            "the " + service + " credential belongs to " + baseHost + ", not " + host +
                ". Name a path relative to the configured host instead of an absolute URL."
        )
    }

    private fun Refused(why: String): Decision =
        Decision.Refused("Refused before sending: " + why + ".")

    private data class Parsed(val protocol: String, val host: String)

    private fun parse(url: String): Parsed? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val protocol = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        // Strip userinfo and a port; a host is what matters here.
        val hostPart = authority.substringAfter('@', authority).substringBefore(':')
        val host = hostPart.lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        return Parsed(protocol, host)
    }
}
