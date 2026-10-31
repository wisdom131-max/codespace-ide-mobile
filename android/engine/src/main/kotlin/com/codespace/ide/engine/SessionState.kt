package com.codespace.ide.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Session-bound transcript state. One instance per engine session, owned by
 * the engine — no global registry (the archived chat's session-store rot is
 * exactly what this design avoids). All access is synchronized; sends are
 * serialized by the caller (one send at a time per session is the contract).
 */
class SessionState(private val engineSessionId: String) {

    private val messages = ArrayList<ChatMessage>()
    private val counter = AtomicLong(0)

    fun nextId(): String = "$engineSessionId-${counter.incrementAndGet()}"

    fun append(role: ChatRole, text: String, state: MessageState): ChatMessage {
        val m = ChatMessage(nextId(), role, text, state)
        synchronized(messages) { messages.add(m) }
        return m
    }

    fun update(id: String, transform: (ChatMessage) -> ChatMessage) {
        synchronized(messages) {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = transform(messages[i])
        }
    }

    /**
     * MEMORY CONTINUITY (2026-10-31): replay a conversation that outlived this process.
     *
     * The transcript on disk is the only thing that survives a closed panel, so a fresh
     * engine starts blind unless it is handed what was already said. Restoring REPLACES the
     * in-memory list rather than appending, because this is called exactly once, before the
     * first send of a restored session, and "restore" that could double up would send the
     * same turn twice to a paid model.
     */
    fun restore(history: List<ChatMessage>) {
        synchronized(messages) {
            messages.clear()
            messages.addAll(history)
        }
    }

    /** Copy-out snapshot for renderers and for the history window. */
    fun snapshot(): List<ChatMessage> = synchronized(messages) { ArrayList(messages) }

    fun size(): Int = synchronized(messages) { messages.size }
}
