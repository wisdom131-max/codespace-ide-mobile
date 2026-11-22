package com.codespace.ide.engine

object PlanDigest {
    data class Step(val text: String, val done: Boolean)
    fun render(steps: List<Step>, maxChars: Int = 700): String {
        if (steps.isEmpty()) return ""
        val sb = StringBuilder("## PLAN\n")
        for ((i, s) in steps.withIndex()) {
            sb.append(i + 1).append(". ").append(if (s.done) "[x] " else "[ ] ").append(s.text.trim()).append(10.toChar())
            if (sb.length >= maxChars) break
        }
        return sb.toString().take(maxChars)
    }
}
