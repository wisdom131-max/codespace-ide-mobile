// LIVE CODE — this file is the implementation behind the AI agent's 3 connector tools
// (AgentTools.kt: list_connectors / connect_service / use_connector). The earlier
// "DEAD CODE" label was misleading (2026-09-07 correction): only the OLD OOB-flow
// implementation was dead; this backend-backed rewrite is live. The UI counterpart
// is ConnectorsHubSheet.kt (Connectors Hub, in Settings + Copilot chat overflow menu);
// both share ConnectorsApiClient + the backend connectors module (backend/src/connectors).

package com.codespace.ide.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.codespace.ide.data.ConnectorsApiClient
import com.codespace.ide.data.SecureTokenStore
import org.json.JSONArray

/**
 * AgentConnectorManager — OAuth connector system for external services, used by the in-app
 * AI agent's tool-calling loop (AgentTools.kt: list_connectors / connect_service / use_connector).
 *
 * REWRITTEN 2026-07-07: the previous implementation used Google's OOB ("out-of-band") OAuth
 * flow, which Google deprecated/killed in 2022, and asked the AI to manually "exchange" a
 * pasted code for a token with no real exchange step ever implemented. It never actually
 * worked for any service. This version calls the real backend (backend/src/connectors/ (TypeScript files),
 * deployed on Render) which holds real OAuth client secrets and does a proper
 * authorization-code -> access-token exchange server-side.
 *
 * GitHub is intentionally NOT handled here — GitHub sign-in is a separate, already-working
 * system (GitHubAuth.kt's Device Flow, wired into Settings > Accounts and used for git
 * push/pull auth). Keeping two different "GitHub connector" code paths would be confusing;
 * point users there instead.
 */
object AgentConnectorManager {

    /** Services the backend actually supports (see backend/src/connectors/connector-registry.ts). */
    private val OAUTH_SERVICES = listOf(
        "gmail", "gcalendar", "gdrive", "slack",
        // Phase 2 (Group A) — rows appear in the Hub; usable once server env vars are set.
        "gitlab", "notion", "figma", "linear", "jira", "discord", "canva", "huggingface",
    )

    /** Phase 1 (Item 4): personal-API-token services — user pastes the token in the Connectors Hub. */
    private val PAT_SERVICES = listOf("sentry", "vercel", "cloudflare", "posthog", "stripe", "railway", "render")

    val SERVICES get() = OAUTH_SERVICES + PAT_SERVICES

    private val DISPLAY_NAMES = mapOf(
        "gmail" to "Gmail",
        "gcalendar" to "Google Calendar",
        "gdrive" to "Google Drive",
        "slack" to "Slack",
        "sentry" to "Sentry",
        "vercel" to "Vercel",
        "cloudflare" to "Cloudflare",
        "posthog" to "PostHog",
        "stripe" to "Stripe",
        "railway" to "Railway",
        "render" to "Render",
        "gitlab" to "GitLab",
        "notion" to "Notion",
        "figma" to "Figma",
        "linear" to "Linear",
        "jira" to "Jira",
        "discord" to "Discord",
        "canva" to "Canva",
        "huggingface" to "Hugging Face",
    )

    /** Phase 1 C3: when request_connector runs, the chat UI shows an inline
     * "Connect <Service>" card. Side-channel consumed by CopilotChatPanelInline
     * right after the agent loop returns. */
    @Volatile private var pendingConnectCard: String? = null

    fun consumePendingConnectCard(): String? {
        val s = pendingConnectCard
        pendingConnectCard = null
        return s
    }

    private fun requireAccessToken(context: Context): String? =
        SecureTokenStore(context).lastAccessToken?.takeIf { it.isNotBlank() }

    fun listConnectors(context: Context): String {
        val token = requireAccessToken(context)
            ?: return "Not signed in to CodeSpace IDE — sign in first (cloud sync auth), then connectors become available."

        val result = ConnectorsApiClient.fetchStatus(token, context)
        return result.fold(
            onSuccess = { statuses ->
                val sb = StringBuilder("Available connectors:\n")
                for (s in statuses) {
                    val status = when {
                        s.connected -> "[CONNECTED]"
                        !s.configured -> "[not set up by owner yet]"
                        else -> "[available]"
                    }
                    sb.append("  $status ${s.name} (${s.id})\n")
                }
                sb.append("\nGitHub: use Settings > Accounts > Sign in with GitHub (separate system).")
                sb.toString().trim()
            },
            onFailure = { e -> "Couldn't reach the connectors backend: ${e.message}" },
        )
    }

    /**
     * Kicks off the real OAuth flow: fetches a provider-hosted consent URL from the backend
     * and opens it in the system browser (Google/Slack block embedded WebViews for OAuth —
     * "disallowed_useragent" — so this must be a real browser tab, not an in-app WebView).
     * The backend's /connectors/callback page confirms success; re-run list_connectors
     * afterward (or reopen the Connectors sheet) to see the updated CONNECTED status —
     * there's no separate "paste the code back" step anymore.
     */
    fun connectService(service: String, scopes: JSONArray?, context: Context): String {
        if (service in PAT_SERVICES) {
            return "${DISPLAY_NAMES[service]} connects with a personal API token, not a sign-in page. " +
                "The user should open the Connectors Hub (chat kebab or In-Project Settings) and tap ${DISPLAY_NAMES[service]}. " +
                "Use the request_connector tool to show them a connect card instead."
        }
        if (service !in SERVICES) {
            return "Unknown or unsupported service: $service. Available: ${SERVICES.joinToString(", ")}. " +
                "For GitHub, use Settings > Accounts > Sign in with GitHub instead."
        }
        val token = requireAccessToken(context)
            ?: return "Not signed in to CodeSpace IDE — sign in first, then try connecting ${DISPLAY_NAMES[service]} again."

        val result = ConnectorsApiClient.fetchAuthUrl(token, service, context)
        return result.fold(
            onSuccess = { authUrl ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Opened the ${DISPLAY_NAMES[service]} sign-in page in your browser. " +
                        "Finish signing in there, then come back — it'll show as connected."
                } catch (e: Exception) {
                    "Got the sign-in link but couldn't open a browser automatically: ${e.message}\n$authUrl"
                }
            },
            onFailure = { e -> "Couldn't start connecting ${DISPLAY_NAMES[service]}: ${e.message}" },
        )
    }

    /**
     * Phase 1 C3: the agent asks to surface an inline "Connect <Service>" card in
     * the chat. Generic across OAuth + PAT connector types — the card just opens
     * the Connectors Hub, where each service runs its own flow.
     */
    fun requestConnectorCard(service: String, @Suppress("UNUSED_PARAMETER") context: Context): String {
        if (service !in SERVICES) {
            return "Unknown service: $service. Available: ${SERVICES.joinToString(", ")}."
        }
        if (service == "github") return "GitHub connects from Settings > Accounts > Sign in with GitHub."
        pendingConnectCard = service
        return "The user is being shown a 'Connect ${DISPLAY_NAMES[service]}' card in the chat. " +
            "Tell them to tap Connect on it and finish in the Connectors Hub, then continue your answer. " +
            "Do not call this tool again for ${DISPLAY_NAMES[service]} in this conversation."
    }

    fun useConnector(
        service: String,
        method: String,
        endpoint: String,
        body: String,
        context: Context
    ): String {
        if (service !in SERVICES) {
            return "Unknown or unsupported service: $service. Available: ${SERVICES.joinToString(", ")}."
        }
        val token = requireAccessToken(context)
            ?: return "Not signed in to CodeSpace IDE — sign in first."

        val result = ConnectorsApiClient.proxyCall(token, service, method, endpoint, body, context)
        return result.fold(
            onSuccess = { it.take(6000) },
            onFailure = { e -> "Connector call failed: ${e.message}" },
        )
    }
}
