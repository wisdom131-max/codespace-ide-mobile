package com.codespace.ide.chat

import kotlinx.coroutines.delay

/**
 * MULTI-KEY FAILOVER ENGINE (2026-09-13, Wisdom-approved automatic policy):
 *
 *  - 401/403 (key REJECTED): cool that key down 10 minutes (session-scoped),
 *    immediately retry with the NEXT key of the same provider.
 *  - 429 (rate LIMITED — key is fine): back off and retry the SAME key first:
 *    honor the response Retry-After header (capped 30s), else 1.5s then 4s.
 *    Only after 2 failed same-key retries move on to the next key (if any).
 *  - 400/404/5xx: NOT a key problem — surfaced as-is, never fail over.
 *  - Mid-stream failures: if output has already been delivered (streaming),
 *    never fail over — a second attempt would duplicate visible text.
 *  - Malformed-token detection at SAVE time (AiKeyFormats) is the earlier
 *    safety net; this engine handles failures at REQUEST time.
 *
 * Cooldowns are in-memory only — a rejected key is retried on app restart.
 */
object ChatKeyFailover {
    // slot suffix -> epoch ms until which the key is skipped (session-scoped)
    private val authCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val AUTH_COOLDOWN_MS: Long = 10 * 60 * 1000L

    fun clearCooldowns() = authCooldowns.clear()

    /**
     * R10-A: labels of slots currently in a 401/403 auth cooldown — read by the
     * Copilot status sheet. Empty when all keys are healthy.
     */
    fun coolingLabels(): List<String> =
        authCooldowns.entries
            .filter { it.value > System.currentTimeMillis() }
            .map { com.codespace.ide.chat.ChatKeyPool.label(it.key).ifEmpty { it.key } }

    suspend fun <T> execute(
        providerId: String,
        tokenStore: com.codespace.ide.data.SecureTokenStore?,
        hasStarted: (() -> Boolean)? = null,
        onInfo: ((String) -> Unit)? = null,
        block: suspend (apiKey: String) -> T,
    ): T {
        val all = ChatKeyPool.keys(tokenStore, providerId)
        if (all.isEmpty()) {
            throw Exception("No API key configured for this provider. Add one in Settings \\u2192 AI Providers.")
        }
        val now = System.currentTimeMillis()
        val candidates = all.filter { (suf, _) -> (authCooldowns[suf] ?: 0L) < now }
        if (candidates.isEmpty()) {
            throw ChatHttpException(
                401,
                "All configured keys for this provider were rejected (401/403) recently. Check or replace them in Settings \\u2192 AI Providers.",
            )
        }
        var lastError: ChatHttpException? = null
        for ((suffix, key) in candidates) {
            val label = ChatKeyPool.label(suffix).ifEmpty { suffix }
            var rateLimitAttempt = 0
            while (true) {
                try {
                    val result = block(key)
                    authCooldowns.remove(suffix)
                    return result
                } catch (he: ChatHttpException) {
                    if (hasStarted?.invoke() == true) {
                        // Output already streamed — retrying would duplicate it.
                        throw he
                    }
                    if (he.statusCode == 401 || he.statusCode == 403) {
                        authCooldowns[suffix] = System.currentTimeMillis() + AUTH_COOLDOWN_MS
                        onInfo?.invoke("[KEY-FAILOVER] '" + label + "' rejected (" + he.statusCode + ") \\u2014 switching to the next key")
                        lastError = he
                        break
                    }
                    if (he.statusCode == 429) {
                        rateLimitAttempt++
                        if (rateLimitAttempt <= 2) {
                            val waitMs = he.retryAfterMs ?: if (rateLimitAttempt == 1) 1500L else 4000L
                            onInfo?.invoke("[KEY-FAILOVER] '" + label + "' rate-limited (429) \\u2014 retrying the SAME key in " + waitMs + "ms")
                            delay(waitMs)
                            continue
                        }
                        onInfo?.invoke("[KEY-FAILOVER] '" + label + "' still rate-limited after retries \\u2014 moving to the next key")
                        lastError = he
                        break
                    }
                    throw he // 400/404/5xx — server-side, not a key problem
                }
            }
        }
        val base = lastError?.message ?: "Key rejected"
        throw ChatHttpException(lastError?.statusCode ?: 401, base + " \\u2014 no other working key for this provider.")
    }
}
