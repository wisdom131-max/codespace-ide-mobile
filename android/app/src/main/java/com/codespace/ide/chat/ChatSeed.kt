package com.codespace.ide.chat

import com.codespace.ide.engine.ChatMessage
import com.codespace.ide.engine.ChatRole
import com.codespace.ide.engine.MessageState

/**
 * MEMORY CONTINUITY — which stored messages are replayed into a fresh engine.
 *
 * The transcript holds more than dialogue: it also stores this UI's own "note" rows (an
 * attachment was refused, a restore problem was reported) and the restore markers. Replaying
 * those would spend the model's context on sentences the user never said, so only real turns
 * are replayed, in order, with the same text the user is reading.
 */
internal object ChatSeed {

    fun dialogue(stored: List<StoredMessage>): List<ChatMessage> {
        val seed = ArrayList<ChatMessage>(stored.size)
        for (m in stored) {
            val role = when (m.role) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> null
            } ?: continue
            if (m.text.isBlank()) continue
            seed.add(
                ChatMessage(
                    id = "restored-" + seed.size,
                    role = role,
                    text = m.text,
                    state = MessageState.DONE,
                )
            )
        }
        return seed
    }
}
