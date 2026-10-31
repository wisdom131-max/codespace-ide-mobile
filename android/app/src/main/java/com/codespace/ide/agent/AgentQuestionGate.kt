package com.codespace.ide.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The blocking half of vscode_askQuestions and vscode_get_confirmation.
 *
 * Both VS Code tools SUSPEND: the model gets the user's actual answer as the tool result. A tool
 * here returns a String the moment it returns, so without a gate the model would be handed
 * "question asked" and could invent the reply. This gate suspends the same way AgentFlowGate
 * does, with the same 10-minute ceiling, so a lost card abandons the call honestly instead of
 * leaving the chat loading forever.
 *
 * One question at a time: serialized on a Mutex, because two cards fighting for one state slot
 * is exactly the clobbering bug CH09 fixed for approvals.
 */
object AgentQuestionGate {

    enum class Outcome { ANSWERED, TIMED_OUT, DISMISSED }

    data class Answer(val choice: String?, val text: String?, val outcome: Outcome) {
        val answered: Boolean get() = outcome == Outcome.ANSWERED
    }

    data class Pending(
        val question: String,
        val options: List<String>,
        val allowFreeText: Boolean,
        val deferred: CompletableDeferred<Answer>,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val slot = Mutex()

    suspend fun ask(
        question: String,
        options: List<String> = emptyList(),
        allowFreeText: Boolean = true,
        timeoutMs: Long = 600_000L,
    ): Answer = slot.withLock {
        val deferred = CompletableDeferred<Answer>()
        _pending.value = Pending(question, options, allowFreeText, deferred)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        _pending.value = null
        result ?: Answer(null, null, Outcome.TIMED_OUT)
    }

    /** @return true only when this call resolved the pending question. */
    fun answer(choice: String?, text: String?): Boolean {
        val p = _pending.value ?: return false
        val cleaned = text?.trim()?.takeIf { it.isNotEmpty() }
        return p.deferred.complete(Answer(choice, cleaned, Outcome.ANSWERED))
    }

    fun dismiss(): Boolean {
        val p = _pending.value ?: return false
        return p.deferred.complete(Answer(null, null, Outcome.DISMISSED))
    }
}
