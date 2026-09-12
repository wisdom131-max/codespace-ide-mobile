package com.codespace.ide.agent

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred

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
    data class PendingApproval(
        val toolName: String,
        val argsSummary: String,
        val deferred: CompletableDeferred<Boolean>,
        /** ROUND 5: lets the card offer "Always allow <tool>" — may be null in headless calls. */
        val onAlwaysAllow: (() -> Unit)? = null,
    )

    val pending: MutableState<PendingApproval?> = mutableStateOf(null)

    suspend fun awaitApproval(context: android.content.Context, toolName: String, argsSummary: String): Boolean {
        if (ChatPermissionStore.isAutoApproved(context, toolName)) return true
        val deferred = CompletableDeferred<Boolean>()
        pending.value = PendingApproval(toolName, argsSummary, deferred, onAlwaysAllow = {
            ChatPermissionStore.allowTool(context, toolName)
            deferred.complete(true)
        })
        val result = deferred.await()
        pending.value = null
        return result
    }

    fun approve() { pending.value?.deferred?.complete(true) }
    fun reject() { pending.value?.deferred?.complete(false) }
}
