package com.codespace.ide.chat.panel

/**
 * PHASE 2: the queued-message list for ONE conversation, as pure data.
 *
 * Why a queue at all: while a reply streams the composer's own send is refused
 * (`sendCore` returns early when streaming), so a follow-up typed mid-answer used to be either
 * thrown away or turned into a "Stop and Send" that killed the answer. A queue keeps the second
 * message without cancelling the first, and runs it when the turn is genuinely over.
 *
 * FIFO, bounded, and no duplicates: an identical repeat is dropped rather than queued twice, so a
 * double tap cannot buy the same question twice. Never more than [MAX] waiting - past that the
 * owner is asked to wait, which is honest, instead of a list that grows without limit on a phone.
 */
internal object ChatQueue {

    /** Ten waiting messages is already far past useful; the cap is a refusal, not a target. */
    const val MAX = 10

    /** Trimmed text worth queuing, or null when the message is blank. */
    fun accept(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /**
     * Append, refusing duplicates and overflow. Returns the SAME list when the message cannot be
     * queued, so a caller can compare identity to know nothing changed.
     */
    fun enqueue(queue: List<String>, text: String): List<String> {
        val clean = accept(text) ?: return queue
        if (queue.size >= MAX) return queue
        if (queue.any { it == clean }) return queue
        return queue + clean
    }

    /** The next message to run, or null when the queue is empty. */
    fun peek(queue: List<String>): String? = queue.firstOrNull()

    /** Drop one waiting message by position (the owner can cancel a queued item). */
    fun drop(queue: List<String>, index: Int): List<String> =
        if (index in queue.indices) queue.filterIndexed { i, _ -> i != index } else queue

    /** Plain-words line for the transcript; null when nothing is waiting. */
    fun notice(queue: List<String>): String? = when (queue.size) {
        0 -> null
        1 -> "1 message queued - it runs when this answer finishes."
        else -> queue.size.toString() + " messages queued - they run in order when this answer finishes."
    }
}

/**
 * PHASE 3: steering a turn that is already running.
 *
 * What steering may NOT be: cancelling the generation and replaying the conversation. That repeats
 * every tool side effect the turn already performed. So the instruction is carried as TEXT into the
 * next request the SAME turn makes - the tool-result continuation - which is the first point where
 * the model reads anything new. Nothing already executed is re-sent for execution; the ledger,
 * approvals and duplicate-write identities all stay exactly as they were.
 *
 * Between those boundaries (a single plain completion with no tool round) there is no moment to
 * deliver into, and [Delivery] says so rather than pretending the instruction landed.
 */
internal object Steering {

    /** Marker so the model can tell an owner correction from tool output. */
    const val MARK =
        "STEERING INSTRUCTION FROM THE USER (applies from here on; do not repeat work already done):"

    enum class Delivery {
        /** Will be read at the next tool-result continuation in this same turn. */
        AT_NEXT_BOUNDARY,

        /** Nothing is running: the text is an ordinary message, and the caller must say so. */
        NO_ACTIVE_TURN,
    }

    fun accept(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /** The payload the next request carries, with the instruction in front of the tool results. */
    fun apply(payload: String, steer: String): String =
        MARK + "\n" + steer.trim() + "\n\n" + payload

    /** What the owner is told happened, in plain words. */
    fun notice(delivery: Delivery): String = when (delivery) {
        Delivery.AT_NEXT_BOUNDARY ->
            "Steering noted - it is applied at the next step of this run, without restarting it."
        Delivery.NO_ACTIVE_TURN ->
            "Nothing is running, so there was nothing to steer. Send it as a normal message."
    }
}
