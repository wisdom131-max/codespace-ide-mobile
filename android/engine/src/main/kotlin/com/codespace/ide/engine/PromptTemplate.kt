package com.codespace.ide.engine

/**
 * PROJECT NINE 9.3 (catalog 18) - named placeholders instead of string concatenation.
 *
 * Prompt text was assembled with plus signs, which is why a missing value silently became an
 * empty gap or the word null. A template states what it needs, and a missing value is REPORTED
 * rather than hidden, so a caller can log it instead of shipping a prompt with a hole in it.
 */
object PromptTemplate {

    data class Rendered(val text: String, val missing: List<String>)

    private val VAR = Regex("\\{\\{([a-zA-Z0-9_.]+)(?:\\|([^}]*))?\\}\\}")

    fun render(template: String, values: Map<String, String>): Rendered {
        val missing = LinkedHashSet<String>()
        val out = VAR.replace(template) { m ->
            val name = m.groupValues[1]
            val fallback = m.groupValues[2]
            val value = values[name]
            if (value != null) {
                value
            } else {
                if (fallback.isEmpty()) missing.add(name)
                fallback
            }
        }
        return Rendered(out, missing.toList())
    }
}
