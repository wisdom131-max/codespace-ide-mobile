package com.codespace.ide.engine

/**
 * PROJECT NINE 9.8 (catalog 53) - search with an imagined answer, not only the question.
 *
 * A short question shares few words with the code that answers it. HyDE asks the model to write
 * the paragraph it imagines would answer, then searches for that - the imagined text is much
 * closer in wording to the real material than the question was.
 *
 * The prompt is built here rather than in the UI so every caller asks the same way, and the
 * term extraction is bounded, because a long imagined paragraph would otherwise flood the index.
 */
object HydePrompt {

    fun build(query: String): String =
        "Write a short, factual paragraph that would answer the question below, as if it appeared " +
            "in this project's documentation. Do not hedge and do not ask questions back.\n\n" +
            "Question: " + query

    fun searchTerms(hypothetical: String, maxTerms: Int = 12): List<String> =
        hypothetical.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 4 }
            .distinct()
            .take(maxTerms)
}
