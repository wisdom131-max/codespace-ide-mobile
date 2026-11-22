package com.codespace.ide.engine

class EvidenceLedger {
    private val seen = LinkedHashSet<String>()
    fun record(kind: String, detail: String) {
        seen.add(kind.lowercase() + ":" + detail.trim().take(120))
    }
    fun has(kind: String): Boolean = seen.any { it.startsWith(kind.lowercase() + ":") }
    fun kinds(): List<String> = seen.map { it.substringBefore(58.toChar()) }.distinct().sorted()
    fun size(): Int = seen.size
}
