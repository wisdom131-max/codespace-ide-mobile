package com.codespace.ide.agent

import com.codespace.ide.chat.panel.RetryDecision
import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import com.codespace.ide.chat.panel.ToolRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Area 5 - THE GATE IN FRONT OF EVERY TOOL BODY.
 *
 * [AgentTools.executeTool] delegates its body dispatch here, so this is the production path, not a
 * parallel helper. Three things it guarantees:
 *
 *  1. a completed call is answered from the ledger, never re-run;
 *  2. an uncertain side effect is refused (or reconciled, when [reconciler] can prove what
 *     happened) instead of being retried blindly;
 *  3. two callers holding the SAME call id are serialized, so a double tap or a provider retry
 *     cannot start the same side effect twice concurrently.
 *
 * Approval gates live above this class and are untouched: the guard only decides whether a body
 * may run again, never whether it may run at all.
 */
class ToolDispatchGuard(
    private val ledger: ToolCallLedger,
    private val reconciler: ToolReconciler = ToolReconciler.None,
) {

    /** One mutex per call id, created on demand. Bounded by the ledger's own 200-entry cap. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    /**
     * Run [execute] under the ledger. [callId] is optional for existing callers; a missing id is a
     * fresh id, which means "unknown call" rather than "safe to reuse".
     */
    suspend fun run(
        callId: String?,
        tool: String,
        args: Map<String, String> = emptyMap(),
        execute: suspend () -> String,
    ): String {
        val id = callId?.trim()?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val effect = ToolEffectIndex.effectOf(tool)
        return lockFor(id).withLock {
            val prior = ledger.lookup(id)
            if (prior != null) {
                when (val d = ToolRetry.decide(prior.outcome, effect)) {
                    RetryDecision.REUSE_PERSISTED -> return@withLock prior.result
                        ?: "This call ($tool) already completed; its result was not retained. Not re-executing."
                    RetryDecision.RECONCILE_FIRST -> {
                        // Narrow, evidence-based repair. Never a re-run of the original operation.
                        val evidence = reconciler.reconcile(tool, prior)
                        if (evidence != null) {
                            ledger.recordReconciled(id, tool, effect, evidence)
                            return@withLock evidence
                        }
                        return@withLock "Refused to repeat $tool: its earlier outcome is " +
                            "${prior.outcome.name} and it cannot be proven safe. Nothing was executed."
                    }
                    RetryDecision.REFUSE -> return@withLock "Refused to repeat $tool: the earlier attempt " +
                        "did not complete (${prior.outcome.name}) and repeating it cannot be proven safe. " +
                        "Nothing was executed."
                    RetryDecision.EXECUTE -> Unit
                }
            }
            ledger.recordStarted(id, tool, effect, args)
            val out = try {
                execute()
            } catch (c: CancellationException) {
                // Cancellation is not evidence that nothing happened.
                ledger.recordFailed(id, tool, effect, ToolOutcome.CANCELLED_DURING_EXECUTION)
                throw c
            } catch (t: Throwable) {
                ledger.recordFailed(id, tool, effect, ToolOutcome.COMPLETED_ERROR)
                throw t
            }
            ledger.recordCompleted(id, tool, effect, out)
            out
        }
    }
}
