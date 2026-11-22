package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.22 (catalog 54) - "find the retry logic" returns every retry comment in every
 * file unless something can say "only .kt, only under chat/".
 *
 * A pure filter over a candidate list. It never touches storage and never scores: scoring is the
 * ranker's job, and a filter that also ranked would be impossible to reason about when results
 * disappear. An empty filter means "no restriction", which is the important default - a filter
 * that starts excluding things because a field was blank is how search silently stops finding.
 */
object MetadataFilter {

    /** One candidate as the filter sees it. Path is repo-relative, forward slashes. */
    data class Doc(val id: String, val path: String, val language: String? = null)

    data class Spec(
        val extensions: Set<String> = emptySet(),
        val pathContains: List<String> = emptyList(),
        val pathExcludes: List<String> = emptyList(),
        val languages: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean
            get() = extensions.isEmpty() && pathContains.isEmpty() &&
                pathExcludes.isEmpty() && languages.isEmpty()
    }

    private fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return ""
        return name.substringAfterLast('.').lowercase()
    }

    fun apply(docs: List<Doc>, spec: Spec): List<Doc> {
        if (spec.isEmpty) return docs
        val exts = spec.extensions.map { it.lowercase().removePrefix(".") }.toSet()
        val langs = spec.languages.map { it.lowercase() }.toSet()
        return docs.filter { doc ->
            val path = doc.path.replace('\\', '/')
            val lower = path.lowercase()
            // Exclusions win over everything: an explicit "not here" is never overridden by a match.
            if (spec.pathExcludes.any { lower.contains(it.lowercase()) }) return@filter false
            if (exts.isNotEmpty() && extensionOf(path) !in exts) return@filter false
            if (spec.pathContains.isNotEmpty() && spec.pathContains.none { lower.contains(it.lowercase()) }) {
                return@filter false
            }
            if (langs.isNotEmpty()) {
                val lang = doc.language?.lowercase() ?: return@filter false
                if (lang !in langs) return@filter false
            }
            true
        }
    }

    /** Reads a filter out of plain words the user typed, so the caller does not have to parse. */
    fun specFromQuery(query: String): Spec {
        val exts = Regex("\\.([A-Za-z0-9]{1,8})\\b").findAll(query)
            .map { it.groupValues[1].lowercase() }
            .filter { it in KNOWN_EXTENSIONS }
            .toSet()
        val dirs = Regex("(?:in|under|inside)\\s+([A-Za-z0-9_./-]+)").findAll(query)
            .map { it.groupValues[1].trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toList()
        return Spec(extensions = exts, pathContains = dirs)
    }

    private val KNOWN_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "json", "md", "gradle", "toml", "yaml", "yml",
        "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "h", "cpp", "sql", "sh",
    )
}
