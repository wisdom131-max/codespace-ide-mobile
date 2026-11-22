package com.codespace.ide.engine

object CodeChunker {
    data class Chunk(val startLine: Int, val endLine: Int, val header: String)
    private val DECL = Regex("^(fun|class|object|interface|enum|data class|sealed class|abstract class)([^A-Za-z0-9_]|$)")
    fun chunk(source: String, maxLines: Int = 120): List<Chunk> {
        val lines = source.lines()
        val starts = ArrayList<Int>()
        for ((i, l) in lines.withIndex()) if (DECL.containsMatchIn(l)) starts.add(i)
        if (starts.isEmpty()) return if (lines.isEmpty()) emptyList() else listOf(Chunk(0, lines.size - 1, ""))
        val out = ArrayList<Chunk>()
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] - 1 else lines.size - 1
            out.add(Chunk(s, minOf(e, s + maxLines - 1), lines[s].trim()))
        }
        return out
    }
}
