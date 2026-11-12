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

    /**
     * Wave 0.2 (owner approval 2026-11-11): connector use asks, always.
     *
     * IG15 wrote this intent in the KDoc above and no caller ever passed `forceApproval`, so under
     * AUTO_ALL a `use_connector` call went straight to the network holding the user's stored token.
     * That is the one place where a level-based shortcut is not convenience but egress, so the rule
     * lives here rather than in one call site: a caller can be rewritten, the gate cannot forget.
     */
    fun requiresForcedApproval(toolName: String): Boolean =
        toolName == "use_connector" ||
            // P8.D (spec: "MCP calls always require explicit approval; AUTO_ALL must not bypass
            // MCP approval"). An MCP tool runs code on a third-party server the owner connected,
            // with whatever that server holds. "Always allow" for a filesystem tool is not consent
            // for a remote one, and AUTO_ALL was silently granting it: the mcp_ namespace was
            // exempted in ChatPermissionStore and then never forced here.
            toolName.startsWith("mcp_")

    /**
     * The gate's decision as a PURE function, so the policy is asserted without Android prefs.
     *
     * Identical to the old behaviour for every other tool: auto-approved or session-allowed means
     * no card. A forced tool asks even when BOTH say yes, which is what stops "Always allow" and
     * AUTO_ALL from turning connector consent into a setting nobody remembers agreeing to.
     */
    fun mustAsk(toolName: String, autoApproved: Boolean, sessionAllowed: Boolean): Boolean {
        if (requiresForcedApproval(toolName)) return true
        return !(autoApproved || sessionAllowed)
    }

    /**
     * The card's answer, mapped to the verdict the chat acts on - pure, so "declining prevents the
     * call" is asserted as a rule rather than inferred from a UI.
     */
    fun verdictFor(answer: Boolean?): ApprovalVerdict = when (answer) {
        true -> ApprovalVerdict.APPROVED
        false -> ApprovalVerdict.REJECTED
        null -> ApprovalVerdict.TIMEOUT
    }

    /** A tool that always asks must not be offered "Always allow": that is a promise it breaks. */
    fun offersAlwaysAllow(toolName: String): Boolean = !requiresForcedApproval(toolName)

    suspend fun awaitApproval(
        context: android.content.Context,
        toolName: String,
        argsSummary: String,
        forceApproval: Boolean = false,
        onTrust: (() -> Unit)? = null,
    ): ApprovalVerdict {
        val forced = forceApproval || requiresForcedApproval(toolName)
        // A forced tool never consults the level or the session set: reading them would only
        // create a chance to honour them.
        val autoApproved = !forced && ChatPermissionStore.isAutoApproved(context, toolName)
        // Section 6: an explicit "allow for this session" answer is honoured WITHOUT
        // touching the persisted permission level - it dies with the process.
        val sessionAllowed = !forced && isSessionAllowed(toolName)
        return awaitDecision(
            toolName = toolName,
            argsSummary = argsSummary,
            autoApproved = autoApproved,
            sessionAllowed = sessionAllowed,
            onTrust = onTrust,
            persistAlways = { ChatPermissionStore.allowTool(context, toolName) },
        )
    }

    /**
     * The suspending half, context-free so the card machinery itself can be tested on the JVM.
     *
     * Internal on purpose: [awaitApproval] is the only entry a caller should use, because it owns
     * the forced-tool rule. This half only knows the two answers it was given.
     */
    internal suspend fun awaitDecision(
        toolName: String,
        argsSummary: String,
        autoApproved: Boolean,
        sessionAllowed: Boolean,
        onTrust: (() -> Unit)? = null,
        /** How a non-forced tool remembers "Always allow"; owned by the caller, which has the Context. */
        persistAlways: (() -> Unit)? = null,
    ): ApprovalVerdict {
        if (!mustAsk(toolName, autoApproved, sessionAllowed)) return ApprovalVerdict.APPROVED
        return approvalSlot.withLock {
            val deferred = CompletableDeferred<Boolean>()
            // A forced tool asks again next time, so offering "Always allow" would promise
            // something this gate will not honour. The card shows Approve / Decline only.
            val alwaysAllow: (() -> Unit)? = if (!offersAlwaysAllow(toolName)) null else ({
                persistAlways?.invoke()
                deferred.complete(true)
            })
            // CH09 (P4h): the old await() suspended FOREVER — a lost card left the
            // chat loading with Stop as the only escape. 10 minutes with no response
            // = the call is abandoned with an honest TIMEOUT verdict.
            // The clear runs in a finally: a cancelled wait (chat stopped, process winding down)
            // must not leave a card on screen for an operation nobody is waiting for any more.
            try {
                val outcome = withTimeoutOrNull(600_000L) { deferred.await() }
                verdictFor(outcome)
            } finally {
                pending.value = null
            }
        }
    }

    /**
     * Section 6: the scopes the approval row can actually honour. Each maps onto a
     * mechanism that ALREADY exists - nothing here widens what a tool may do, it only
     * decides how long the user's answer to this exact question is remembered.
     *  ONCE    - this call only (default; no memory at all).
     *  SESSION - remembered in memory until the process dies (cleared on restart).
     *  ALWAYS  - persisted per tool through ChatPermissionStore (the existing switch).
     * There is deliberately no "workspace" scope: no per-project permission store exists,
     * and inventing one here would create a permission surface nothing else enforces.
     */
    enum class ApprovalScope { ONCE, SESSION, ALWAYS }

    /** Session-scoped allow list. In memory by design: a restart re-asks. */
    private val sessionAllowed = LinkedHashSet<String>()

    fun sessionAllowedCount(): Int = synchronized(sessionAllowed) { sessionAllowed.size }

    fun isSessionAllowed(toolName: String): Boolean =
        synchronized(sessionAllowed) { sessionAllowed.contains(toolName) }

    fun rememberForSession(toolName: String) {
        synchronized(sessionAllowed) { sessionAllowed.add(toolName) }
    }

    fun clearSessionApprovals() {
        synchronized(sessionAllowed) { sessionAllowed.clear() }
    }

    /**
     * Resolve the pending request. Clicking twice cannot do anything twice: complete()
     * returns false the second time, so the "always allow" side effect runs at most once
     * and a late click after a timeout is a no-op instead of a phantom approval.
     * Returns true only when this call actually resolved the request.
     */
    fun approve(scope: ApprovalScope = ApprovalScope.ONCE): Boolean {
        val p = pending.value ?: return false
        if (!p.deferred.complete(true)) return false
        when (scope) {
            ApprovalScope.ONCE -> Unit
            ApprovalScope.SESSION -> rememberForSession(p.toolName)
            ApprovalScope.ALWAYS -> p.onAlwaysAllow?.invoke()
        }
        return true
    }

    fun reject(): Boolean {
        val p = pending.value ?: return false
        return p.deferred.complete(false)
    }
}
