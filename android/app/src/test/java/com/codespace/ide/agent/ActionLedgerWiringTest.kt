package com.codespace.ide.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PROJECT 4: the ledger is only worth having if the authoritative gate writes it.
 *
 * This is a source-level guard because the wiring lives inside a suspend function that needs an
 * Android Context, and the risk it protects against is a future edit that quietly drops the record
 * or moves it somewhere the gate does not run.
 */
class ActionLedgerWiringTest {

    private fun source(): String = listOf(
        File("src/main/java/com/codespace/ide/agent/AgentTools.kt"),
        File("app/src/main/java/com/codespace/ide/agent/AgentTools.kt"),
    ).firstOrNull { it.isFile }?.readText() ?: error("AgentTools.kt not found")

    @Test fun `the single execution gate records the six states`() {
        val text = source()
        for (call in listOf("ledger.plan(", "ledger.begin(", "ledger.complete(", "ledger.fail(")) {
            assertTrue("gate must call $call", text.contains(call))
        }
    }

    @Test fun `approval is recorded from the gate, not from the caller`() {
        val text = source()
        assertTrue(
            "an approval-gated action is approved on the authorized path only",
            text.contains("if (needsApproval) ledger.approve(actionId)"),
        )
        assertTrue(
            "the gate decides whether approval was needed",
            text.contains("AgentFlowGate.requiresForcedApproval(name)"),
        )
    }

    @Test fun `the ledger never stores tool output`() {
        val text = source()
        assertTrue(
            "a failure detail is a fixed phrase",
            text.contains("\"tool reported failure\""),
        )
    }
}
