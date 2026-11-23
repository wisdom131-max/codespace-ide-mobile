package com.codespace.ide.engine

/**
 * PROJECT NINE 9.71 (catalog 54 + 55 + 48 WIRED) - filter, group and record what a search found.
 *
 * 54: a question that names a language ("kotlin", "gradle") should not return Python files. The
 * filter is derived from the question, so the caller passes nothing extra.
 *
 * 55: a file is one result. Three matching lines in one file used to be three separate results,
 * crowding out other files; they are now one block, header first, its best lines under it.
 *
 * 48: every match records path -> line in the entity graph, so a later step can ask what a symbol
 * touched without re-running the search.
 */
object SearchRank {

    data class Line(val id: String, val path: String, val text: String)

    private const val LINES_PER_FILE = 3
    private const val EDGES_RECORDED = 20

    fun rank(
        query: String,
        literal: List<String>,
        widened: List<String>,
        limit: Int = 60,
        maxChars: Int = 12000,
    ): String {
        val all = LinkedHashMap<String, Line>()
        for (s in literal + widened) {
            all[s] = Line(s, s.substringBefore(":"), s)
        }
        if (all.isEmpty()) return noMatches(query)

        val candidates = all.values.toList()
        val spec = MetadataFilter.specFromQuery(query)
        val allowed = MetadataFilter
            .apply(candidates.map { MetadataFilter.Doc(it.id, it.path, null) }, spec)
            .map { it.id }
            .toSet()
        // An empty allow-list means the filter found nothing to say, not that nothing matched.
        val kept = if (allowed.isEmpty()) candidates else candidates.filter { allowed.contains(it.id) }
        if (kept.isEmpty()) return noMatches(query)

        val semantic = SemanticRanker.rank(query, kept.map { SemanticRanker.Doc(it.id, it.text) }, limit)
        val keptIds = kept.map { it.id }.toSet()
        val lexical = literal.filter { keptIds.contains(it) }

        val byId = kept.associateBy { it.id }
        val terms = QueryRewriter.rewrite(query, 8)
        val ordered = Reranker
            .rerank(
                HybridSearch.combine(lexical, semantic)
                    .mapNotNull { f -> byId[f.id]?.let { Reranker.Candidate(it.id, it.text, f.score) } },
                terms,
            )
            .mapNotNull { byId[it.id] }

        val byPath = LinkedHashMap<String, MutableList<Line>>()
        for (line in ordered) {
            byPath.getOrPut(line.path) { ArrayList() }.add(line)
        }
        val blocks = byPath.entries.map { entry ->
            entry.key + "\n" + entry.value.take(LINES_PER_FILE).joinToString("\n") { "  " + it.text }
        }

        for (line in ordered.take(EDGES_RECORDED)) {
            EntityGraph.add(line.path, "matched", line.id)
        }

        // PROJECT NINE 9.83 (catalog 34): the blackboard is read now, not only written. A file
        // written moments ago is marked in the results, so a step stops re-reading a file to
        // discover its own edit.
        val lastWrite: String = Blackboard.read("last.write")?.value?.substringBefore(" (")?.trim() ?: ""
        val marked = if (lastWrite.isEmpty()) blocks
            else blocks.map { if (it.startsWith(lastWrite)) "[you wrote this file recently]\n" + it else it }
        return SearchBudget
            .fit(marked.mapIndexed { i, b -> SearchBudget.Item(i.toString(), b) }, maxChars)
            .joinToString("\n\n") { it.text }
    }

    /**
     * PROJECT NINE 10.05 (catalog 53 WIRED): a search that finds nothing used to return an empty
     * string, and the model would either give up or repeat the same search. HyDE's prompt is
     * returned instead: the model writes the passage that WOULD answer the question and searches
     * again with its distinctive words. The generator is the model itself, so this costs no extra
     * call and nothing is invented on its behalf.
     */
    private fun noMatches(query: String): String =
        "No matches for \"" + query + "\".\n" + HydePrompt.build(query)
}
