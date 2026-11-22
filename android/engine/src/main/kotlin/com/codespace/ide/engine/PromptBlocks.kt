package com.codespace.ide.engine

object PromptBlocks {
    fun render(plan: String, ledger: String, rules: String, maxChars: Int = 1200): String {
        val parts = listOf(plan, ledger, rules).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.length + p.length + 2 > maxChars) break
            sb.append(p).append(10.toChar()).append(10.toChar())
        }
        return sb.toString().trim()
    }
}
