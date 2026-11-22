package com.codespace.ide.engine

object SelfCritique {
    val CHECKLIST = listOf(
        "Does the change match what was asked, and nothing more?",
        "Was every claim of success backed by output actually seen?",
        "Is anything left undone or unverified?",
    )
    data class Finding(val ok: Boolean, val note: String)
    fun parse(reply: String): List<Finding> = reply.lineSequence().mapNotNull { line ->
        val t = line.trim()
        when {
            t.startsWith("PASS") -> Finding(true, t.removePrefix("PASS").trim().trimStart(58.toChar()).trim())
            t.startsWith("FAIL") -> Finding(false, t.removePrefix("FAIL").trim().trimStart(58.toChar()).trim())
            else -> null
        }
    }.toList()
    fun anyFailed(f: List<Finding>): Boolean = f.any { !it.ok }
}
