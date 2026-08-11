package com.codespace.ide.editor

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * P-FLOW: Settings backing the "In-Project Settings" floating page (gear menu).
 * Persisted in SharedPreferences, mirrors the FeatureToggleStore pattern.
 *
 * Expanded Aug 10 2026 to include:
 * - AI Agent Flow (Flow Mode, Verbose Tool Output)
 * - Notifications (Task completion threshold, Terminal notifications)
 * - Text Editor (Cursor blinking style)
 * - Python/LSP (Diagnostics source, Pyright version, Node arguments, Jedi completion params)
 */
enum class FlowMode { MANUAL, AUTO }

/** Cursor blink style — mirrors VS Code's editor.cursorBlinking setting. */
enum class CursorBlinkStyle {
    BLINK,      // Default — on/off blink
    PHASE,      // Fade in/out smoothly
    SOLID,      // No blink — always visible
    EXPAND,     // Block expands/contracts
    SMOOTH,     // Smooth pulse
}

/** Python diagnostics source — which LSP server provides completions + diagnostics. */
enum class DiagnosticsSource {
    PYLSP,      // python-lsp-server (jedi-based) — default
    PYRIGHT,    // pyright-langserver (Node.js-based, Microsoft)
}

object ProjectSettingsStore {
    private const val PREFS = "project_settings"
    private lateinit var prefs: android.content.SharedPreferences

    // ── AI Agent Flow ──────────────────────────────────────────────────
    val flowMode: MutableState<FlowMode> = mutableStateOf(FlowMode.AUTO)
    val verboseToolOutput: MutableState<Boolean> = mutableStateOf(false)

    // ── Editor Keyboard ───────────────────────────────────────────────
    /** Show the extra coding keys toolbar above the soft keyboard. */
    val extraKeysEnabled: MutableState<Boolean> = mutableStateOf(true)

    // ── Notifications ──────────────────────────────────────────────────
    /** Task completion notification threshold in ms. -1 = never, 0 = always. */
    val taskNotifyThresholdMs: MutableState<Int> = mutableStateOf(8000)
    /** Enable terminal foreground-service notifications. */
    val terminalNotifications: MutableState<Boolean> = mutableStateOf(true)
    /** Show verbose download/install notifications for LSP servers. */
    val verboseDownloadNotify: MutableState<Boolean> = mutableStateOf(false)

    // ── Text Editor ────────────────────────────────────────────────────
    val cursorBlinkStyle: MutableState<CursorBlinkStyle> = mutableStateOf(CursorBlinkStyle.BLINK)

    // ── Zen Mode ────────────────────────────────────────────────────────
    /** Show the floating exit button in Zen Mode. If false, use menu to exit. */
    val zenModeExitButtonEnabled: MutableState<Boolean> = mutableStateOf(true)

    // ── Formatting (Phase R) ─────────────────────────────────────────────
    /** Format on Save — when enabled, formatting runs before saving the file. */
    val formatOnSaveEnabled: MutableState<Boolean> = mutableStateOf(true)

    // ── LSP Server Toggle ───────────────────────────────────────────────
    /** Master switch for all LSP servers. When disabled, only fallback completions are used. */
    val lspEnabled: MutableState<Boolean> = mutableStateOf(true)

    // ── Custom Cursor Overlay ────────────────────────────────────────────
    /** Custom cursor overlay — a draggable, tap-to-type cursor that summons the keyboard on tap.
     *  Replaces the default thin text cursor with a visible, touch-friendly overlay. */
    val customCursorOverlayEnabled: MutableState<Boolean> = mutableStateOf(false)

    // ── Python / LSP ────────────────────────────────────────────────────
    val diagnosticsSource: MutableState<DiagnosticsSource> = mutableStateOf(DiagnosticsSource.PYRIGHT)
    /** Pyright version string or path to local pyright-langserver.js (empty = auto-install latest). */
    val pyrightVersion: MutableState<String> = mutableStateOf("")
    /** Node.js CLI args for pyright (e.g. --max-old-space-size=8192). */
    val pyrightNodeArgs: MutableState<String> = mutableStateOf("--max-old-space-size=8192")

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        flowMode.value = try {
            FlowMode.valueOf(prefs.getString("flow_mode", FlowMode.AUTO.name) ?: FlowMode.AUTO.name)
        } catch (_: Exception) { FlowMode.AUTO }
        verboseToolOutput.value = prefs.getBoolean("verbose_tool_output", false)
        taskNotifyThresholdMs.value = prefs.getInt("task_notify_threshold_ms", 8000)
        terminalNotifications.value = prefs.getBoolean("terminal_notifications", true)
        verboseDownloadNotify.value = prefs.getBoolean("verbose_download_notify", false)
        cursorBlinkStyle.value = try {
            CursorBlinkStyle.valueOf(prefs.getString("cursor_blink_style", CursorBlinkStyle.BLINK.name) ?: CursorBlinkStyle.BLINK.name)
        } catch (_: Exception) { CursorBlinkStyle.BLINK }
        diagnosticsSource.value = try {
            DiagnosticsSource.valueOf(prefs.getString("diagnostics_source", DiagnosticsSource.PYRIGHT.name) ?: DiagnosticsSource.PYRIGHT.name)
        } catch (_: Exception) { DiagnosticsSource.PYRIGHT }
        pyrightVersion.value = prefs.getString("pyright_version", "") ?: ""
        pyrightNodeArgs.value = prefs.getString("pyright_node_args", "--max-old-space-size=8192") ?: "--max-old-space-size=8192"
        extraKeysEnabled.value = prefs.getBoolean("extra_keys_enabled", true)
        zenModeExitButtonEnabled.value = prefs.getBoolean("zen_mode_exit_button", true)
        formatOnSaveEnabled.value = prefs.getBoolean("format_on_save", true)
        lspEnabled.value = prefs.getBoolean("lsp_enabled", true)
        customCursorOverlayEnabled.value = prefs.getBoolean("custom_cursor_overlay", false)
    }

    // ── Setters ────────────────────────────────────────────────────────
    fun setFlowMode(mode: FlowMode) {
        flowMode.value = mode
        prefs.edit().putString("flow_mode", mode.name).apply()
    }
    fun setVerboseToolOutput(value: Boolean) {
        verboseToolOutput.value = value
        prefs.edit().putBoolean("verbose_tool_output", value).apply()
    }
    fun setExtraKeysEnabled(value: Boolean) {
        extraKeysEnabled.value = value
        prefs.edit().putBoolean("extra_keys_enabled", value).apply()
    }
    fun setZenModeExitButtonEnabled(value: Boolean) {
        zenModeExitButtonEnabled.value = value
        prefs.edit().putBoolean("zen_mode_exit_button", value).apply()
    }
    fun setFormatOnSaveEnabled(value: Boolean) {
        formatOnSaveEnabled.value = value
        prefs.edit().putBoolean("format_on_save", value).apply()
    }
    fun setLspEnabled(value: Boolean) {
        lspEnabled.value = value
        prefs.edit().putBoolean("lsp_enabled", value).apply()
    }
    fun setCustomCursorOverlayEnabled(value: Boolean) {
        customCursorOverlayEnabled.value = value
        prefs.edit().putBoolean("custom_cursor_overlay", value).apply()
    }
    fun setTaskNotifyThresholdMs(value: Int) {
        taskNotifyThresholdMs.value = value
        prefs.edit().putInt("task_notify_threshold_ms", value).apply()
    }
    fun setTerminalNotifications(value: Boolean) {
        terminalNotifications.value = value
        prefs.edit().putBoolean("terminal_notifications", value).apply()
    }
    fun setVerboseDownloadNotify(value: Boolean) {
        verboseDownloadNotify.value = value
        prefs.edit().putBoolean("verbose_download_notify", value).apply()
    }
    fun setCursorBlinkStyle(style: CursorBlinkStyle) {
        cursorBlinkStyle.value = style
        prefs.edit().putString("cursor_blink_style", style.name).apply()
    }
    fun setDiagnosticsSource(source: DiagnosticsSource) {
        diagnosticsSource.value = source
        prefs.edit().putString("diagnostics_source", source.name).apply()
    }
    fun setPyrightVersion(version: String) {
        pyrightVersion.value = version
        prefs.edit().putString("pyright_version", version).apply()
    }
    fun setPyrightNodeArgs(args: String) {
        pyrightNodeArgs.value = args
        prefs.edit().putString("pyright_node_args", args).apply()
    }
}
