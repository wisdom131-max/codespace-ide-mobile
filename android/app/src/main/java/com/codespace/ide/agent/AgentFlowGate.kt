package com.codespace.ide.agent

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.codespace.ide.editor.FlowMode
import com.codespace.ide.editor.ProjectSettingsStore
import kotlinx.coroutines.CompletableDeferred

/**
 * P-FLOW: Gates AI Agent tool-call execution on the user's Flow Mode setting
 * (In-Project Settings → AI Agent Flow).
 *
 * - AUTO (default, unchanged behavior): returns true immediately, no pause.
 * - MANUAL: publishes a PendingApproval to [pending] and suspends until the
 *   UI (a floating card in CopilotChatPanelOverlay) calls approve()/reject().
 */
object AgentFlowGate {
    data class PendingApproval(
        val toolName: String,
        val argsSummary: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    val pending: MutableState<PendingApproval?> = mutableStateOf(null)

    suspend fun awaitApproval(toolName: String, argsSummary: String): Boolean {
        if (ProjectSettingsStore.flowMode.value == FlowMode.AUTO) return true
        val deferred = CompletableDeferred<Boolean>()
        pending.value = PendingApproval(toolName, argsSummary, deferred)
        val result = deferred.await()
        pending.value = null
        return result
    }

    fun approve() { pending.value?.deferred?.complete(true) }
    fun reject() { pending.value?.deferred?.complete(false) }
}
