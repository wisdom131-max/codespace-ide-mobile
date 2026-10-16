package com.codespace.ide.terminal

import android.content.Context

/**
 * Advisor Part 4 (2026-10-16, GO): the staged "Link tap mode" developer setting.
 * Each mode is a SEPARATE BOUNDARY so one diagnostic sitting on the device can
 * find where a tap kills the app:
 *
 *  OFF        — the tap does nothing (no extraction, no file access).
 *  LOG_TOKEN  — read the tapped text, extract candidates, LOG them. NO file access.
 *  RESOLVE    — also resolve + probe files and SHOW the result. Does not open.
 *  FULL       — normal behavior: resolve and open the file.
 *
 * If the app dies in LOG_TOKEN, the cause is in extraction (buffer/span/regex).
 * If it survives LOG_TOKEN but dies in RESOLVE, the cause is the probe
 * (exists/canonical/proc). If it only dies in FULL, the cause is the open side.
 */
object LinkTapModeStore {

    enum class Mode { OFF, LOG_TOKEN, RESOLVE, FULL }

    private const val PREFS = "link_tap_prefs"
    private const val KEY = "mode"

    /** Default FULL — the feature stays on; the staged modes are diagnostics. */
    fun current(context: Context): Mode {
        return try {
            val name = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, Mode.FULL.name)
            Mode.values().firstOrNull { it.name == name } ?: Mode.FULL
        } catch (_: Exception) { Mode.FULL }
    }

    fun set(context: Context, mode: Mode) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, mode.name)
                .apply()
        } catch (_: Exception) { /* a diagnostics setting never throws */ }
    }

    /** Plain label for the settings dialog. */
    fun label(mode: Mode): String = when (mode) {
        Mode.OFF -> "Off — tap does nothing"
        Mode.LOG_TOKEN -> "Log token — extract + log, no file access"
        Mode.RESOLVE -> "Resolve — also probe files, show result, do not open"
        Mode.FULL -> "Full — resolve and open the file"
    }
}
