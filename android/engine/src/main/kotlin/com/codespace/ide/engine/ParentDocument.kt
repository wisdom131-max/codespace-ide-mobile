package com.codespace.ide.engine

/**
 * PROJECT NINE 9.9 (catalog 55) - a hit inside a chunk returns the whole parent.
 *
 * Chunks are small so they can be found precisely, but a chunk on its own is often unreadable: it
 * is half a function with no signature. Expanding a hit to the document it came from gives the
 * model the context that made the chunk mean something.
 *
 * Expansion is bounded, because a parent can be an entire file and returning five of them would
 * cost more than the search saved.
 */
object ParentDocument {

    data class Chunk(val id: String, val parentId: String, val text: String)
    data class Parent(val id: String, val text: String)

    fun expand(
        hits: List<String>,
        chunks: List<Chunk>,
        parents: List<Parent>,
        maxChars: Int = 4000,
    ): List<Parent> {
        val byId = chunks.associateBy { it.id }
        val parentById = parents.associateBy { it.id }
        val out = ArrayList<Parent>()
        val seen = HashSet<String>()
        var used = 0
        for (hit in hits) {
            val parentId = byId[hit]?.parentId ?: continue
            if (!seen.add(parentId)) continue
            val parent = parentById[parentId] ?: continue
            if (used + parent.text.length > maxChars) continue
            out.add(parent)
            used += parent.text.length
        }
        return out
    }
}
