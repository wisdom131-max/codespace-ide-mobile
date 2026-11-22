package com.codespace.ide.engine

object InputGuardrail {
    enum class Decision { ALLOW, WARN, BLOCK }
    data class Outcome(val decision: Decision, val reason: String)
    const val MAX_CHARS = 200000
    fun check(text: String, scan: InjectionScanner.Finding): Outcome = when {
        text.length > MAX_CHARS -> Outcome(Decision.BLOCK, "pasted text is " + text.length + " chars")
        scan.verdict == InjectionScanner.Verdict.HOSTILE -> Outcome(Decision.WARN, "contains instructions aimed at the agent")
        scan.verdict == InjectionScanner.Verdict.SUSPICIOUS -> Outcome(Decision.WARN, "instruction-like wording")
        else -> Outcome(Decision.ALLOW, "")
    }
}
