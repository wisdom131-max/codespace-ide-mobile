package com.codespace.ide.terminal

import org.json.JSONObject
import java.util.UUID

data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    var nickname: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var keyPath: String = "",
    var tunnelEnabled: Boolean = false,
    var tunnelType: String = "local",       // "local" (-L) or "remote" (-R)
    var tunnelLocalPort: Int = 8080,
    var tunnelRemoteHost: String = "localhost",
    var tunnelRemotePort: Int = 8080
) {
    fun buildCommand(): String {
        val cmd = StringBuilder("ssh -o StrictHostKeyChecking=accept-new")
        if (tunnelEnabled && tunnelRemoteHost.isNotEmpty()) {
            val flag = if (tunnelType == "remote") "-R" else "-L"
            cmd.append(" $flag $tunnelLocalPort:$tunnelRemoteHost:$tunnelRemotePort")
        }
        if (port != 22) cmd.append(" -p $port")
        if (keyPath.isNotEmpty()) cmd.append(" -i $keyPath")
        cmd.append(" $username@$host")
        return cmd.toString()
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
            tunnelRemotePort = o.optInt("tunnelRemotePort", 8080)
        )
    }
}
