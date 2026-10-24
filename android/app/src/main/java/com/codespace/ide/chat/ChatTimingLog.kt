package com.codespace.ide.chat

/**
 * B.2 (advisor 2026-10-24): SANITIZED chat timing telemetry for the Gemini
 * slowness investigation (owner: fast before, slow on full prompts, network
 * not believed — investigate OUR side fully).
 *
 * Records ONLY: phase, provider id, model id, elapsed ms, payload size
 * (exact UTF-8 bytes at the provider, char count at the build seam),
 * chars/4 token ESTIMATE, HTTP status, exception class NAME, retry attempt
 * count, backoff wait total, time to first byte. NEVER the prompt text,
 * file contents, keys, or auth headers — none of those values are accepted
 * as parameters AT ALL (the signature is the guarantee; there is no string
 * payload parameter to misuse).
 *
 * Output: AppOutputLog lines tagged [TIMING] on the "chat" channel — the
 * persisted Output pane the owner already greps, so timings survive across
 * sessions in the saved logs.
 */
object ChatTimingLog {
    fun now(): Long = System.nanoTime()
    fun elapsedMs(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    /** Cheap payload-size estimate for the build seam: prompt + message
     *  content chars, no full JSON serialization copy. */
    fun estimateChars(systemPrompt: String, convMsgs: org.json.JSONArray): Int {
        var n = systemPrompt.length
        for (i in 0 until convMsgs.length()) {
            n += convMsgs.optJSONObject(i)?.optString("content")?.length ?: 0
        }
        return n
    }

    fun log(
        phase: String,
        providerId: String,
        modelId: String,
        ms: Long,
        payloadSize: Int = -1,
        estTokens: Int = -1,
        status: String = "ok",
        errClass: String = "",
        attempts: Int = -1,
        waitMs: Long = -1,
        ttfbMs: Long = -1,
        sizeUnit: String = "bytes",
    ) {
        val sb = StringBuilder("[TIMING] ").append(phase)
            .append(" provider=").append(providerId)
            .append(" model=").append(modelId)
            .append(" ms=").append(ms)
        if (payloadSize >= 0) sb.append(" ").append(sizeUnit).append("=").append(payloadSize)
        if (estTokens >= 0) sb.append(" est_tokens=").append(estTokens)
        if (ttfbMs >= 0) sb.append(" ttfb_ms=").append(ttfbMs)
        if (status != "ok") sb.append(" status=").append(status)
        if (errClass.isNotEmpty()) sb.append(" err=").append(errClass)
        if (attempts >= 0) sb.append(" attempts=").append(attempts)
        if (waitMs >= 0) sb.append(" wait_ms=").append(waitMs)
        try {
            com.codespace.ide.diagnostics.AppOutputLog.log(sb.toString(), "chat")
        } catch (_: Throwable) { /* telemetry must never break a send */ }
    }
}
