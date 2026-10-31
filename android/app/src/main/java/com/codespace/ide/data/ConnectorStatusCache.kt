package com.codespace.ide.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last connector statuses the Hub actually saw (2026-10-31, owner report: "it always
 * reloads for a long time before showing connectors").
 *
 * The Hub could only ever paint after a live `fetchStatus` round-trip, so every open began with
 * a spinner even when nothing had changed since the last open seconds earlier. The list is
 * small and changes only when the user connects or disconnects something, which is exactly the
 * case a cache is for: paint what was true a moment ago, then correct it in the background.
 *
 * Writes are best-effort. A cache that fails to save is a slow open next time, never a broken
 * Hub, so no error is surfaced from here.
 */
object ConnectorStatusCache {

    private const val FILE = "connectors-status.json"

    private fun file(context: Context) = java.io.File(context.filesDir, FILE)

    fun load(context: Context): List<ConnectorsApiClient.ConnectorStatus> = try {
        val raw = file(context).readText()
        decode(raw)
    } catch (_: Throwable) {
        emptyList()
    }

    fun save(context: Context, statuses: List<ConnectorsApiClient.ConnectorStatus>) {
        try {
            file(context).writeText(encode(statuses))
        } catch (_: Throwable) {
            // Best-effort: see the class comment.
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Throwable) {}
    }

    // --- pure codec, unit-tested ----------------------------------------------------------

    fun encode(statuses: List<ConnectorsApiClient.ConnectorStatus>): String {
        val array = JSONArray()
        statuses.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("connected", s.connected)
                    put("configured", s.configured)
                    s.scope?.let { put("scope", it) }
                    put("authType", s.authType)
                    s.tokenHelpUrl?.let { put("tokenHelpUrl", it) }
                    s.tokenHint?.let { put("tokenHint", it) }
                }
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<ConnectorsApiClient.ConnectorStatus> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            ConnectorsApiClient.ConnectorStatus(
                id = id,
                name = o.optString("name", id),
                connected = o.optBoolean("connected", false),
                configured = o.optBoolean("configured", false),
                scope = o.optString("scope").takeIf { it.isNotBlank() },
                authType = o.optString("authType", "oauth"),
                tokenHelpUrl = o.optString("tokenHelpUrl").takeIf { it.isNotBlank() },
                tokenHint = o.optString("tokenHint").takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
