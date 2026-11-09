package com.codespace.ide.chat.panel

import com.codespace.ide.engine.BudgetGuard
import org.junit.Test

/**
 * MEASUREMENT of what actually goes out, per component, in TOKENS (BudgetGuard.estimateTokens =
 * chars / 4). JVM numbers, no device, no key, no network.
 *
 * Why this exists: the owner's org-scoped model refused even "hi". Before changing anything I need
 * to know WHICH part is heavy, and the first answer is surprising enough to be worth printing:
 * our agent tools are NOT sent as JSON tool schemas. [AgentToolWire.protocol] renders them as a text
 * protocol inside the system instruction, so the weight sits in the system prompt, not in a tools
 * array. That changes the fix from "schema budget" to "doc budget + history fitting".
 *
 * Assumptions stated, not hidden: one conversation turn is modelled at 600 chars of assistant text
 * plus 200 chars of user text (~200 tokens a turn), which matches the short technical exchanges in
 * this project; workspace is measured at its configured cap, not at a real tree.
 */
class RequestCompositionMeasurement {

    private fun tok(chars: Int) = BudgetGuard.estimateTokens("x".repeat(chars))

    private val modes = listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT)

    @Test
    fun printTheCompositionTable() {
        val turnChars = 800
        println("[comp] component | chars | tokens (est)")
        for (mode in modes) {
            val instruction = ChatModePrompts.instruction(mode)
            val wire = AgentToolWire.protocol(mode)
            val systemChars = instruction.length + wire.length
            val wsChars = WorkspaceContext.MAX_CHARS
            println("[comp] mode=" + mode.name +
                " systemInstruction=" + systemChars + "ch/" + tok(systemChars) + "t" +
                " (instructions " + instruction.length + ", toolWire " + wire.length + ")" +
                " workspace=" + wsChars + "ch/" + tok(wsChars) + "t" +
                " userMsg=2ch/0t")
            for (turns in listOf(5, 20, 50)) {
                val histChars = turns * turnChars
                val total = systemChars + wsChars + histChars
                println("[comp]   after " + turns + " turns: history=" + histChars + "ch/" +
                    tok(histChars) + "t  TOTAL=" + total + "ch/" + tok(total) + "t")
            }
        }
    }

    @Test
    fun theToolWireIsTheHeavyHalfOfTheSystemPrompt() {
        val ask = AgentToolWire.protocol(ChatMode.ASK).length
        val agent = AgentToolWire.protocol(ChatMode.AGENT).length
        println("[comp] toolWire ask=" + ask + "ch agent=" + agent + "ch (" + (agent - ask) + "ch more)")
        println("[comp] builtin tool names advertised: " + com.codespace.ide.agent.AgentTools.toolNames().size)
        // A single 6,000-token per-minute account (the Groq case) is 24,000 chars TOTAL. This is the
        // number that decides whether "hi" can even be sent.
        val budgetChars = 6000 * 4
        println("[comp] a 6000-token account allows ~" + budgetChars + "ch total")
    }
}
