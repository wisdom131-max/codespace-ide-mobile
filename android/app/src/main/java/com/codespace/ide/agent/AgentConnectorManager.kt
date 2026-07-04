package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AgentConnectorManager — OAuth connector system for external services.
 * Mirrors Superagent's connector capability (Gmail, Calendar, Slack, etc.)
 *
 * Available connectors and their OAuth endpoints:
 *  - gmail:     Google Gmail API
 *  - gcalendar: Google Calendar API
 *  - gdrive:    Google Drive API
 *  - slack:     Slack API
 *  - github:    GitHub API
 *
 * Tokens are stored encrypted via SecureTokenStore.
 * OAuth flow: returns auth URL -> user opens in WebView -> callback captures token.
 */
object AgentConnectorManager {

    data class Connector(
        val id: String,
        val name: String,
        val authUrl: String,
        val tokenUrl: String,
        val scopesParam: String,
        val apiBase: String,
        val description: String
    )

    // Connector registry — add more services here
    private val CONNECTORS = mapOf(
        "gmail" to Connector(
            "gmail", "Gmail",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/gmail",
            "https://gmail.googleapis.com/gmail/v1",
            "Read and send Gmail messages"
        ),
        "gcalendar" to Connector(
            "gcalendar", "Google Calendar",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/calendar/v3",
            "View and manage calendar events"
        ),
        "gdrive" to Connector(
            "gdrive", "Google Drive",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/drive/v3",
            "Read and write Google Drive files"
        ),
        "slack" to Connector(
            "slack", "Slack",
            "https://slack.com/oauth/v2/authorize",
            "https://slack.com/api/oauth.v2.access",
            "",
            "https://slack.com/api",
            "Send and read Slack messages"
        ),
        "github" to Connector(
            "github", "GitHub",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "",
            "https://api.github.com",
            "Full GitHub API access"
        )
    )

    fun listConnectors(context: Context): String {
        val store = SecureTokenStore(context)
        val sb = StringBuilder("Available connectors:\n")
        for ((id, conn) in CONNECTORS) {
            val token = store.aiKey("connector_${id}_token")
            val status = if (token != null) "[CONNECTED]" else "[available]"
            sb.append("  $status ${conn.name} ($id): ${conn.description}\n")
        }
        return sb.toString().trim()
    }

    fun connectService(service: String, scopes: JSONArray?, context: Context): String {
        val conn = CONNECTORS[service]
            ?: return "Unknown service: $service. Available: ${CONNECTORS.keys.joinToString(", ")}"

        // Build OAuth URL — the app's WebView will open this and capture the callback
        val scopeStr = if (scopes != null && scopes.length() > 0) {
            scopes.joinToString(",") { it.toString() }
        } else {
            conn.scopesParam
        }

        val clientId = SecureTokenStore(context).aiKey("connector_${service}_client_id")
            ?: "CLIENT_ID_NOT_SET"

        val authUrl = when (service) {
            "gmail", "gcalendar", "gdrive" -> {
                "${conn.authUrl}?client_id=$clientId&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=$scopeStr"
            }
            "slack" -> {
                "${conn.authUrl}?client_id=$clientId&scope=chat:write,channels:read&user_scope=chat:write"
            }
            "github" -> {
                "${conn.authUrl}?client_id=$clientId&scope=repo,read:user,user:email"
            }
            else -> conn.authUrl
        }

        return "To connect ${conn.name}, open this URL in a browser:\n$authUrl\n\n" +
               "After authorization, paste the code here and I'll exchange it for a token.\n" +
               "Use: <tool>{\"name\":\"save_secret\",\"arguments\":{\"key\":\"connector_${service}_token\",\"value\":\"YOUR_CODE\"}}</tool>"
    }

    fun useConnector(
        service: String,
        method: String,
        endpoint: String,
        body: String,
        context: Context
    ): String {
        val conn = CONNECTORS[service]
            ?: return "Unknown service: $service"

        val token = SecureTokenStore(context).aiKey("connector_${service}_token")
            ?: return "${conn.name} is not connected. Use connect_service first."

        return try {
            val url = "${conn.apiBase}$endpoint"
            val httpConn = URL(url).openConnection() as HttpURLConnection
            httpConn.requestMethod = method.uppercase()
            httpConn.connectTimeout = 15000
            httpConn.readTimeout = 30000

            // Auth header
            when (service) {
                "gmail", "gcalendar", "gdrive" -> httpConn.setRequestProperty("Authorization", "Bearer $token")
                "slack" -> httpConn.setRequestProperty("Authorization", "Bearer $token")
                "github" -> {
                    httpConn.setRequestProperty("Authorization", "token $token")
                    httpConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                }
            }

            if (method.uppercase() in listOf("POST", "PUT", "PATCH")) {
                httpConn.setRequestProperty("Content-Type", "application/json")
                httpConn.doOutput = true
                httpConn.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = httpConn.responseCode
            val respBody = if (code in 200..299) {
                httpConn.inputStream.bufferedReader().use { it.readText() }
            } else {
                httpConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            }
            "HTTP $code\n${respBody.take(6000)}"
        } catch (e: Exception) {
            "Connector call failed: ${e.message}"
        }
    }
}
