package com.codespace.ide.terminal

import org.json.JSONObject
import java.util.UUID

/**
 * P3 fixes:
 *  - All fields changed from var to val; use copy() for edits (Compose-safe)
 *  - buildCommand() shell-quotes every user-controlled argument to prevent injection
 */
data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val nickname: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val keyPath: String = "",
    val tunnelEnabled: Boolean = false,
    val tunnelType: String = "local",       // "local" (-L) or "remote" (-R)
    val tunnelLocalPort: Int = 8080,
    val tunnelRemoteHost: String = "localhost",
    val tunnelRemotePort: Int = 8080,
) {
    /** Shell-quote a single argument (single-quote wrapping with inner-quote escaping). */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun buildCommand(): String {
        val parts = mutableListOf("ssh", "-o", "StrictHostKeyChecking=accept-new")
        if (tunnelEnabled && tunnelRemoteHost.isNotEmpty()) {
            val flag = if (tunnelType == "remote") "-R" else "-L"
            parts += flag
            parts += "$tunnelLocalPort:${tunnelRemoteHost}:$tunnelRemotePort"
        }
        if (port != 22) { parts += "-p"; parts += port.toString() }
        if (keyPath.isNotEmpty()) { parts += "-i"; parts += keyPath }
        parts += "$username@$host"
        // Join with shell-quoting applied to each token
        return parts.joinToString(" ") { q(it) }
    }

    fun displayLabel(): String {
        val base = "$username@$host"
        return if (port != 22) "$base:$port" else base
    }

    fun tunnelLabel(): String? {
        if (!tunnelEnabled) return null
        val arrow = if (tunnelType == "remote") "R" else "L"
        return "-$arrow $tunnelLocalPort:$tunnelRemoteHost:$tunnelRemotePort"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("nickname", nickname); put("host", host); put("port", port)
        put("username", username); put("keyPath", keyPath)
        put("tunnelEnabled", tunnelEnabled); put("tunnelType", tunnelType)
        put("tunnelLocalPort", tunnelLocalPort); put("tunnelRemoteHost", tunnelRemoteHost)
        put("tunnelRemotePort", tunnelRemotePort)
    }

    companion object {
        fun fromJson(o: JSONObject) = SshProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            nickname = o.optString("nickname", ""),
            host = o.optString("host", ""),
            port = o.optInt("port", 22),
            username = o.optString("username", ""),
            keyPath = o.optString("keyPath", ""),
            tunnelEnabled = o.optBoolean("tunnelEnabled", false),
            tunnelType = o.optString("tunnelType", "local"),
            tunnelLocalPort = o.optInt("tunnelLocalPort", 8080),
            tunnelRemoteHost = o.optString("tunnelRemoteHost", "localhost"),
            tunnelRemotePort = o.optInt("tunnelRemotePort", 8080),
        )
    }
}
