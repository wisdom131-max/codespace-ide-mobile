package com.codespace.ide.chat.provider

import com.codespace.ide.engine.ExchangeStatus
import com.codespace.ide.engine.ToolExchange

/**
 * PROJECT FIVE, STAGE 5.4 PART 2 — the producer ChatSession was missing.
 *
 * Stage 5.4 part 1 taught the engine to send a tool call and its result as a pair, but nothing in the
 * app ever built one: `ChatEngineImpl.exchanges` stayed empty and the model kept receiving prose about
 * tool runs. This log is written at the ONE place a result is recorded ([record]) and read when the
 * next request is built, so the pair is derived from what actually happened instead of from a second
 * hand-maintained transcript.
 *
 * Deliberate choices:
 *  - Keyed by the provider's own call id (or the wire's synthetic id), so a replayed stream cannot
 *    produce two entries for one call: the first outcome wins and a later duplicate is ignored.
 *  - Bounded, because a long session must not grow this without limit on a phone.
 *  - The status is read from the result text the executor produced, which is the same text the user
 *    sees, so "Unsure" in the transcript and UNCERTAIN on the wire cannot disagree.
 */
class ToolExchangeLog(private val limit: Int = 60) {

    private val entries = LinkedHashMap<String, ToolExchange>()

    /**
     * Records one finished call. A repeated id is ignored: the first outcome is the real one.
     *
     * [id] is the provider's own call id when the wire carried one, or the stable synthetic id the
     * chat path assigns (`wire-<turn>-<index>`) when it did not. It is never invented per attempt, so a
     * retry cannot create a second entry for one call.
     */
    fun record(id: String, name: String, argumentsJson: String, output: String, complete: Boolean = true) {
        if (entries.containsKey(id)) return
        entries[id] = ToolExchange(
            callId = id,
            name = name,
            argumentsJson = argumentsJson,
            status = statusOf(output),
            output = output,
            complete = complete,
        )
        trim()
    }

    /** A call the app refused to run. It is not a failure of the tool, and it says so. */
    fun recordRefused(id: String, name: String, argumentsJson: String, reason: String) {
        if (entries.containsKey(id)) return
        entries[id] = ToolExchange(
            callId = id,
            name = name,
            argumentsJson = argumentsJson,
            status = ExchangeStatus.REFUSED,
            output = reason,
            complete = true,
        )
        trim()
    }

    /** What the next request should carry. Already de-duplicated and in call order. */
    fun snapshot(): List<ToolExchange> = entries.values.toList()

    fun clear() = entries.clear()

    val size: Int get() = entries.size

    private fun trim() {
        while (entries.size > limit) {
            val oldest = entries.keys.firstOrNull() ?: return
            entries.remove(oldest)
        }
    }

    companion object {
        /**
         * The status the executor's own words imply. Anything unrecognised is UNCERTAIN, never OK:
         * an outcome this app cannot read is not evidence of success.
         */
        fun statusOf(output: String): ExchangeStatus = when {
            output.startsWith(ToolResultNormaliser.UNCERTAIN_PREFIX, ignoreCase = true) -> ExchangeStatus.UNCERTAIN
            output.startsWith("CANCELLED", ignoreCase = true) -> ExchangeStatus.UNCERTAIN
            output.startsWith("FAILED", ignoreCase = true) -> ExchangeStatus.FAILED
            output.startsWith("REFUSED", ignoreCase = true) -> ExchangeStatus.REFUSED
            output.startsWith("NOT RUN", ignoreCase = true) -> ExchangeStatus.REFUSED
            output.isBlank() -> ExchangeStatus.UNCERTAIN
            else -> ExchangeStatus.OK
        }
    }
}
