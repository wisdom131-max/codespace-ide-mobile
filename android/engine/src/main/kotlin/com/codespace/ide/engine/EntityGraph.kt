package com.codespace.ide.engine

/**
 * PROJECT NINE 9.12 (catalog 48) - facts about the project as relationships, not as text.
 *
 * Memory stored sentences, so answering "what uses the token budget" meant hoping the right
 * sentence was written and then finding it by word overlap. A small triple store answers the
 * question directly: subject, relationship, target.
 *
 * Scoped to the project and bounded, because this is the piece most likely to grow without limit
 * if nobody says stop.
 */
object EntityGraph {

    data class Triple(val subject: String, val predicate: String, val target: String)

    class Graph(private val maxTriples: Int = 5000) {

        private val triples = LinkedHashSet<Triple>()

        /** False when the triple was already known, so a caller can report only new knowledge. */
        fun add(subject: String, predicate: String, target: String): Boolean {
            val t = Triple(subject.trim(), predicate.trim(), target.trim())
            if (t.subject.isEmpty() || t.predicate.isEmpty() || t.target.isEmpty()) return false
            if (triples.contains(t)) return false
            if (triples.size >= maxTriples) return false
            return triples.add(t)
        }

        fun objects(subject: String, predicate: String? = null): List<String> =
            triples.filter { it.subject == subject && (predicate == null || it.predicate == predicate) }
                .map { it.target }

        fun subjects(predicate: String, target: String): List<String> =
            triples.filter { it.predicate == predicate && it.target == target }.map { it.subject }

        fun neighbours(subject: String): List<Triple> = triples.filter { it.subject == subject }

        fun size(): Int = triples.size
    }
}
