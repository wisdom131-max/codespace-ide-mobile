package com.codespace.ide.preview

import android.content.Context

/**
 * The bundled mermaid build. The script is inlined into the preview document rather than
 * fetched: the preview is served from loopback and must work with no network at all.
 * Read once and cached — it is roughly a megabyte, so it is only inlined when the document
 * actually contains a mermaid fence.
 */
object MermaidAssets {

    private var cached: String? = null

    fun hasMermaidFence(markdown: String): Boolean =
        markdown.lineSequence().any { it.trimStart().startsWith("```mermaid") }

    fun script(context: Context): String? {
        cached?.let { return it }
        return try {
            val text = context.assets.open("mermaid/mermaid.min.js")
                .bufferedReader().use { it.readText() }
            cached = text
            text
        } catch (_: Throwable) {
            null
        }
    }
}
