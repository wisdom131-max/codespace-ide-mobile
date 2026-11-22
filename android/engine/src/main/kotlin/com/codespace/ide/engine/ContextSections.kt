package com.codespace.ide.engine

/**
 * PROJECT NINE 9.2 (catalog 29) - one framing for every section of the prompt.
 *
 * Sections were concatenated with bare headings, so the model could not tell where a section
 * ended and the user's own text began. A closed tag does that unambiguously.
 *
 * An empty section is omitted rather than rendered empty: an empty tag is a section the model
 * will try to fill, and that costs attention for nothing.
 */
object ContextSections {

    private const val CLOSE = "</section>"

    fun render(sections: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        for ((name, body) in sections) {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) continue
            // A body containing the closing tag would end its section early and leave the rest of
            // the prompt outside the frame, so the literal is defused.
            val safe = trimmed.replace(CLOSE, "<\\/section>")
            sb.append("<section name=\"").append(name).append("\">\n")
            sb.append(safe).append('\n')
            sb.append(CLOSE).append('\n')
        }
        return sb.toString()
    }
}
