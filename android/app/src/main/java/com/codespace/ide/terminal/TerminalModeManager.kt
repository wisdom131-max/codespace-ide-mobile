package com.codespace.ide.terminal

import android.content.Context

class TerminalModeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("terminal_mode", Context.MODE_PRIVATE)

    fun currentMode(): String = prefs.getString(KEY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    // MODE_OLLAMA was extracted to codespace-ide-extensions (2026-09-05). Any legacy
    // stored "ollama" value degrades gracefully to offline via the != UBUNTU check.
    fun isOfflinePreferred(): Boolean = currentMode() != MODE_UBUNTU

    fun isUbuntuPreferred(): Boolean = currentMode() == MODE_UBUNTU

    fun defaultModeForDevice(): String = MODE_OFFLINE

    companion object {
        const val MODE_OFFLINE = "offline"
        const val MODE_UBUNTU = "ubuntu"
        const val DEFAULT_MODE = MODE_OFFLINE
        private const val KEY_MODE = "mode"
    }
}
