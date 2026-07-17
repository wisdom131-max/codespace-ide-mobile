// ⚠️ DEAD CODE — DO NOT EDIT OR RELY ON THIS FILE
// Used Google's deprecated OOB OAuth 2.0 flow (killed by Google in 2022).
// Replaced by ConnectorsHubSheet.kt + Railway backend OAuth (backend/src/connectors/).
// Kept for reference only.

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
 * deployed on Railway) which holds real OAuth client secrets and does a proper
 * authorization-code -> access-token exchange server-side.
 *
 * GitHub is intentionally NOT handled here — GitHub sign-in is a separate, already-working
 * system (GitHubAuth.kt's Device Flow, wired into Settings > Accounts and used for git
 * push/pull auth). Keeping two different "GitHub connector" code paths would be confusing;
 * point users there instead.
 */
object AgentConnectorManager {

    /** Services the backend actually supports (see backend/src/connectors/connector-registry.ts). */
    private val SERVICES = listOf("gmail", "gcalendar", "gdrive", "slack")

    private val DISPLAY_NAMES = mapOf(
        "gmail" to "Gmail",
        "gcalendar" to "Google Calendar",
        "gdrive" to "Google Drive",
        "slack" to "Slack",
    )

    private fun requireAccessToken(context: Context): String? =
        SecureTokenStore(context).lastAccessToken?.takeIf { it.isNotBlank() }

    fun listConnectors(context: Context): String {
        val token = requireAccessToken(context)
            ?: return "Not signed in to CodeSpace IDE — sign in first (cloud sync auth), then connectors become available."

        val result = ConnectorsApiClient.fetchStatus(token)
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
    fun connectService(service: String, _scopes: JSONArray?, context: Context): String {
        if (service !in SERVICES) {
            return "Unknown or unsupported service: $service. Available: ${SERVICES.joinToString(", ")}. " +
                "For GitHub, use Settings > Accounts > Sign in with GitHub instead."
        }
        val token = requireAccessToken(context)
            ?: return "Not signed in to CodeSpace IDE — sign in first, then try connecting ${DISPLAY_NAMES[service]} again."

        val result = ConnectorsApiClient.fetchAuthUrl(token, service)
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

        val result = ConnectorsApiClient.proxyCall(token, service, method, endpoint, body)
        return result.fold(
            onSuccess = { it.take(6000) },
            onFailure = { e -> "Connector call failed: ${e.message}" },
        )
    }
}
