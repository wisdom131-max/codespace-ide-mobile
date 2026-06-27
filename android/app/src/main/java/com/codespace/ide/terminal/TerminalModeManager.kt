package com.codespace.ide.terminal

import android.content.Context

class TerminalModeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("terminal_mode", Context.MODE_PRIVATE)

    fun currentMode(): String = prefs.getString(KEY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    fun isOfflinePreferred(): Boolean = currentMode() == MODE_OFFLINE || currentMode() == MODE_OLLAMA

    fun isUbuntuPreferred(): Boolean = currentMode() == MODE_UBUNTU

    fun defaultModeForDevice(): String {
        val compat = DeviceCompatibility(context)
        return if (compat.shouldUseOfflineOnly()) MODE_OFFLINE else MODE_OLLAMA
    }

    companion object {
        const val MODE_OFFLINE = "offline"
        const val MODE_OLLAMA = "ollama"
        const val MODE_UBUNTU = "ubuntu"
        // DEFAULT = offline — ollama mode does a 3-second health check on every terminal open
        // which blocks ash startup when backend is unreachable (always on device).
        const val DEFAULT_MODE = MODE_OFFLINE
        private const val KEY_MODE = "mode"
    }
}
