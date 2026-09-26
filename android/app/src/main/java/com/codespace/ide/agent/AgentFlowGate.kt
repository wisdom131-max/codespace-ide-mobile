package com.codespace.ide.agent

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
// CH09 (P4h): Mutex/withLock serializes concurrent approvals (was: a second call
// REPLACED the pending card, leaving the first chat suspended FOREVER).
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
// CH09 (P4h): withTimeoutOrNull bounds the wait — 10 minutes with no response is a
// typed TIMEOUT, never an eternal spinner.
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P-FLOW: Gates AI Agent tool-call execution on the user's permission level
 * (In-Project Settings → AI Agent Flow, upgraded in ROUND 5).
 *
 * Levels (see [ChatPermissionStore]):
 *  - AUTO_ALL: returns true immediately, no pause (old AUTO, default).
 *  - AUTO_SAFE: read-only tools pass; state-changing tools pause.
 *  - MANUAL: every tool call pauses for approval (old MANUAL).
 * In every level, tools the user marked "Always allow" pass without pausing.
 *
 * When a call DOES pause: publishes a PendingApproval to [pending] and
 * suspends until the UI (the approval card in the chat panel) calls
 * approve()/reject()/approveAlways().
 */
object AgentFlowGate {
    /**
     * CH09 (P4h): typed verdict — a timed-out approval is no longer reported to
     * the transcript as "rejected by user" (a misattribution on the SAME surface
     * this gate exists to make honest).
     */
    enum class ApprovalVerdict { APPROVED, REJECTED, TIMEOUT }

    data class PendingApproval(
        val toolName: String,
        val argsSummary: String,
        val deferred: CompletableDeferred<Boolean>,
        /** ROUND 5: lets the card offer "Always allow <tool>" — may be null in headless calls. */
        val onAlwaysAllow: (() -> Unit)? = null,
        /**
         * P2c (CH03): "Trust this project" quick-action — non-null when this call
         * was gated by PROJECT TRUST (untrusted project, first gated action).
         * The card calls it (persist trust) and then approves this call.
         */
        val onTrust: (() -> Unit)? = null,
    )

    val pending: MutableState<PendingApproval?> = mutableStateOf(null)

    /**
     * P2c: forceApproval bypasses isAutoApproved ENTIRELY — the card shows even in
     * AUTO levels. Used for (a) use_connector (IG15: per-call consent, every call,
     * token-bearing network egress) and (b) the first gated action in an untrusted
     * project (prompt-once trust card).
     */
    /** CH09 (P4h): serializes concurrent approvals — a second gated call now WAITS
     * for the current card instead of clobbering it (the old clobber left the first
     * caller's deferred forever-incomplete = a chat stuck loading). */
    private val approvalSlot = Mutex()

    suspend fun awaitApproval(
        context: android.content.Context,
        toolName: String,
        argsSummary: String,
        forceApproval: Boolean = false,
        onTrust: (() -> Unit)? = null,
    ): ApprovalVerdict {
        if (!forceApproval && ChatPermissionStore.isAutoApproved(context, toolName)) return ApprovalVerdict.APPROVED
        return approvalSlot.withLock {
            val deferred = CompletableDeferred<Boolean>()
            pending.value = PendingApproval(toolName, argsSummary, deferred, onAlwaysAllow = {
                ChatPermissionStore.allowTool(context, toolName)
                deferred.complete(true)
            }, onTrust = onTrust)
            // CH09 (P4h): the old await() suspended FOREVER — a lost card left the
            // chat loading with Stop as the only escape. 10 minutes with no response
            // = the call is abandoned with an honest TIMEOUT verdict.
            val outcome = withTimeoutOrNull(600_000L) { deferred.await() }
            pending.value = null
            when (outcome) {
                true -> ApprovalVerdict.APPROVED
                false -> ApprovalVerdict.REJECTED
                null -> ApprovalVerdict.TIMEOUT
            }
        }
    }

    fun approve() { pending.value?.deferred?.complete(true) }
    fun reject() { pending.value?.deferred?.complete(false) }
}
