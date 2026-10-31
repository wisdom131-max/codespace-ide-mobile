package com.codespace.ide.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The half of view_image that a String return type cannot carry.
 *
 * VS Code's view_image returns a LanguageModelDataPart.image (verified in
 * vscode-copilot-chat: src/extension/tools/node/viewImageTool.tsx), i.e. the tool result ITSELF
 * holds image bytes. A tool here returns a String, so the bytes have to reach the request some
 * other way: this queue holds them until the next send turns them into ChatAttachments, which
 * the OpenAI adapter already sends as image_url parts on the final user turn (WP8).
 *
 * Capped, drained per send, never logged, never persisted. Dropping is explicit, never silent.
 */
object ToolImageQueue {

    data class Pending(val name: String, val mimeType: String, val base64: String, val label: String)

    /** Enough for one look at a few frames; more than that is a request the user did not ask for. */
    const val MAX_PENDING = 4

    private val items = CopyOnWriteArrayList<Pending>()

    /** @return false when the queue is full, so the caller can say so instead of pretending. */
    fun enqueue(pending: Pending): Boolean {
        if (items.size >= MAX_PENDING) return false
        items.add(pending)
        return true
    }

    /** Hands the pending images to ONE send, then empties the queue. */
    fun drain(): List<Pending> {
        val all = items.toList()
        items.clear()
        return all
    }

    fun size(): Int = items.size

    fun clear() {
        items.clear()
    }
}
