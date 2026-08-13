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

/** Cursor mode - in-app custom cursor overlay vs system/phone default cursor. */
enum class CursorMode {
    IN_APP,    // Custom cursor overlay - wider, tap-to-type, drag-to-move
    SYSTEM,    // Built-in phone/Android system cursor - thin native text caret
}

/** Python diagnostics source — which LSP server provides completions + diagnostics. */
enum class DiagnosticsSource {
    PYLSP,      // python-lsp-server (jedi-based) — default
    PYRIGHT,    // pyright-langserver (Node.js-based, Microsoft)
}

/** TypeScript version — controls which TS LSP server is used.
 *  TS 7 ships only tsc.js (no tsserver.js), so it uses vtsls instead of typescript-language-server.
 *  Older versions use typescript-language-server which requires tsserver.js. */
enum class TypeScriptVersion(val displayName: String, val lspServer: String, val npmPackage: String) {
    TS7("TypeScript 7 (Latest)", "tsc --lsp", "typescript@7"),
    TS5("TypeScript 5.6.3 (Stable)", "typescript-language-server", "typescript-language-server typescript@5.6.3"),
    TS4("TypeScript 4.9.5 (Legacy)", "typescript-language-server", "typescript-language-server typescript@4.9.5"),
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

    // ── Smart Completion Toggle ─────────────────────────────────────────
    /** When enabled, LSP completions take priority. If LSP doesn't respond within 5s,
     *  falls back to regex/local completions. Once LSP responds, local completions are suppressed.
     *  When disabled, both LSP and local completions show simultaneously (legacy behavior). */
    val smartCompletionEnabled: MutableState<Boolean> = mutableStateOf(true)

    // ── Custom Cursor Overlay ────────────────────────────────────────────
    /** Custom cursor overlay — a draggable, tap-to-type cursor that summons the keyboard on tap.
     *  Replaces the default thin text cursor with a visible, touch-friendly overlay. */
    val customCursorOverlayEnabled: MutableState<Boolean> = mutableStateOf(false)

    // -- Cursor Mode --
    val cursorMode: MutableState<CursorMode> = mutableStateOf(CursorMode.IN_APP)

    // ── Python / LSP ────────────────────────────────────────────────────
    val diagnosticsSource: MutableState<DiagnosticsSource> = mutableStateOf(DiagnosticsSource.PYRIGHT)
    // ── TypeScript Version ──────────────────────────────────────────────
    /** Which TypeScript version + LSP server to use. Default: TS 7 (vtsls). */
    val typescriptVersion: MutableState<TypeScriptVersion> = mutableStateOf(TypeScriptVersion.TS7)
    /** Pyright version string or path to local pyright-langserver.js (empty = auto-install latest). */
    val pyrightVersion: MutableState<String> = mutableStateOf("")
    /** Node.js CLI args for pyright (e.g. --max-old-space-size=8192). */
    val pyrightNodeArgs: MutableState<String> = mutableStateOf("--max-old-space-size=8192")

    // ── Item 4: TS/JS + Accessibility settings (from vscode.dev screenshots) ──

    // Accessibility Signals
    val accSignalPositionWarning: MutableState<Boolean> = mutableStateOf(true)
    val accSignalProgress: MutableState<Boolean> = mutableStateOf(true)

    // JS/TS Format
    val tsFormatEnabled: MutableState<Boolean> = mutableStateOf(true)
    val tsFormatIndentSwitchCase: MutableState<Boolean> = mutableStateOf(true)
    val tsFormatSpaceAfterComma: MutableState<Boolean> = mutableStateOf(true)
    val tsFormatSpaceAfterConstructor: MutableState<Boolean> = mutableStateOf(false)
    val tsFormatSpaceAfterFunctionKeyword: MutableState<Boolean> = mutableStateOf(false)
    val tsFormatSpaceAfterControlFlow: MutableState<Boolean> = mutableStateOf(false)

    // JS/TS Tsserver
    val tsServerLog: MutableState<Boolean> = mutableStateOf(false)
    val tsUseSyntaxServer: MutableState<Boolean> = mutableStateOf(true)

    // JS/TS Inlay Hints
    val tsInlayHintSuppressMatchName: MutableState<Boolean> = mutableStateOf(true)
    val tsInlayHintParamTypes: MutableState<Boolean> = mutableStateOf(false)

    // JS/TS Workspace Symbols
    val tsWsSymbolsExcludeLib: MutableState<Boolean> = mutableStateOf(false)
    val tsWsSymbolsScope: MutableState<String> = mutableStateOf("allOpenProjects")

    // Window
    val windowTitle: MutableState<String> = mutableStateOf("\${activeEditorShort}\${separator}\${rootName}")

    // Terminal
    val terminalIntegratedNotifications: MutableState<Boolean> = mutableStateOf(true)
    val terminalCommandsToSkipShell: MutableState<String> = mutableStateOf("")

    // Extensions
    val extensionsIgnoreRecommendations: MutableState<Boolean> = mutableStateOf(false)

    // Task
    val taskNotifyWindowOnCompletion: MutableState<Boolean> = mutableStateOf(true)

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
        typescriptVersion.value = try {
            TypeScriptVersion.valueOf(prefs.getString("typescript_version", TypeScriptVersion.TS7.name) ?: TypeScriptVersion.TS7.name)
        } catch (_: Exception) { TypeScriptVersion.TS7 }
        pyrightVersion.value = prefs.getString("pyright_version", "") ?: ""
        pyrightNodeArgs.value = prefs.getString("pyright_node_args", "--max-old-space-size=8192") ?: "--max-old-space-size=8192"
        extraKeysEnabled.value = prefs.getBoolean("extra_keys_enabled", true)
        zenModeExitButtonEnabled.value = prefs.getBoolean("zen_mode_exit_button", true)
        formatOnSaveEnabled.value = prefs.getBoolean("format_on_save", true)
        lspEnabled.value = prefs.getBoolean("lsp_enabled", true)
        smartCompletionEnabled.value = prefs.getBoolean("smart_completion_enabled", true)
        customCursorOverlayEnabled.value = prefs.getBoolean("custom_cursor_overlay", false)
        cursorMode.value = try {
            CursorMode.valueOf(prefs.getString("cursor_mode", CursorMode.IN_APP.name) ?: CursorMode.IN_APP.name)
        } catch (_: Exception) { CursorMode.IN_APP }

        // ── Item 4: TS/JS + Accessibility prefs ──
        accSignalPositionWarning.value = prefs.getBoolean("acc_signal_position_warning", true)
        accSignalProgress.value = prefs.getBoolean("acc_signal_progress", true)
        tsFormatEnabled.value = prefs.getBoolean("ts_format_enabled", true)
        tsFormatIndentSwitchCase.value = prefs.getBoolean("ts_format_indent_switch_case", true)
        tsFormatSpaceAfterComma.value = prefs.getBoolean("ts_format_space_after_comma", true)
        tsFormatSpaceAfterConstructor.value = prefs.getBoolean("ts_format_space_after_constructor", false)
        tsFormatSpaceAfterFunctionKeyword.value = prefs.getBoolean("ts_format_space_after_function_keyword", false)
        tsFormatSpaceAfterControlFlow.value = prefs.getBoolean("ts_format_space_after_control_flow", false)
        tsServerLog.value = prefs.getBoolean("ts_server_log", false)
        tsUseSyntaxServer.value = prefs.getBoolean("ts_use_syntax_server", true)
        tsInlayHintSuppressMatchName.value = prefs.getBoolean("ts_inlay_hint_suppress_match_name", true)
        tsInlayHintParamTypes.value = prefs.getBoolean("ts_inlay_hint_param_types", false)
        tsWsSymbolsExcludeLib.value = prefs.getBoolean("ts_ws_symbols_exclude_lib", false)
        tsWsSymbolsScope.value = prefs.getString("ts_ws_symbols_scope", "allOpenProjects") ?: "allOpenProjects"
        windowTitle.value = prefs.getString("window_title", "\${activeEditorShort}\${separator}\${rootName}") ?: "\${activeEditorShort}\${separator}\${rootName}"
        terminalIntegratedNotifications.value = prefs.getBoolean("terminal_integrated_notifications", true)
        terminalCommandsToSkipShell.value = prefs.getString("terminal_commands_to_skip_shell", "") ?: ""
        extensionsIgnoreRecommendations.value = prefs.getBoolean("extensions_ignore_recommendations", false)
        taskNotifyWindowOnCompletion.value = prefs.getBoolean("task_notify_window_on_completion", true)
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
    fun setSmartCompletionEnabled(value: Boolean) {
        smartCompletionEnabled.value = value
        prefs.edit().putBoolean("smart_completion_enabled", value).apply()
    }
    fun setCustomCursorOverlayEnabled(value: Boolean) {
        customCursorOverlayEnabled.value = value
        prefs.edit().putBoolean("custom_cursor_overlay", value).apply()
    }
    fun setCursorMode(mode: CursorMode) {
        cursorMode.value = mode
        prefs.edit().putString("cursor_mode", mode.name).apply()
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
    fun setTypeScriptVersion(version: TypeScriptVersion) {
        typescriptVersion.value = version
        prefs.edit().putString("typescript_version", version.name).apply()
    }
    fun setPyrightVersion(version: String) {
        pyrightVersion.value = version
        prefs.edit().putString("pyright_version", version).apply()
    }
    fun setPyrightNodeArgs(args: String) {
        pyrightNodeArgs.value = args
        prefs.edit().putString("pyright_node_args", args).apply()
    }

    // ── Item 4 setters ──────────────────────────────────────────────────
    fun setAccSignalPositionWarning(v: Boolean) { accSignalPositionWarning.value = v; prefs.edit().putBoolean("acc_signal_position_warning", v).apply() }
    fun setAccSignalProgress(v: Boolean) { accSignalProgress.value = v; prefs.edit().putBoolean("acc_signal_progress", v).apply() }
    fun setTsFormatEnabled(v: Boolean) { tsFormatEnabled.value = v; prefs.edit().putBoolean("ts_format_enabled", v).apply() }
    fun setTsFormatIndentSwitchCase(v: Boolean) { tsFormatIndentSwitchCase.value = v; prefs.edit().putBoolean("ts_format_indent_switch_case", v).apply() }
    fun setTsFormatSpaceAfterComma(v: Boolean) { tsFormatSpaceAfterComma.value = v; prefs.edit().putBoolean("ts_format_space_after_comma", v).apply() }
    fun setTsFormatSpaceAfterConstructor(v: Boolean) { tsFormatSpaceAfterConstructor.value = v; prefs.edit().putBoolean("ts_format_space_after_constructor", v).apply() }
    fun setTsFormatSpaceAfterFunctionKeyword(v: Boolean) { tsFormatSpaceAfterFunctionKeyword.value = v; prefs.edit().putBoolean("ts_format_space_after_function_keyword", v).apply() }
    fun setTsFormatSpaceAfterControlFlow(v: Boolean) { tsFormatSpaceAfterControlFlow.value = v; prefs.edit().putBoolean("ts_format_space_after_control_flow", v).apply() }
    fun setTsServerLog(v: Boolean) { tsServerLog.value = v; prefs.edit().putBoolean("ts_server_log", v).apply() }
    fun setTsUseSyntaxServer(v: Boolean) { tsUseSyntaxServer.value = v; prefs.edit().putBoolean("ts_use_syntax_server", v).apply() }
    fun setTsInlayHintSuppressMatchName(v: Boolean) { tsInlayHintSuppressMatchName.value = v; prefs.edit().putBoolean("ts_inlay_hint_suppress_match_name", v).apply() }
    fun setTsInlayHintParamTypes(v: Boolean) { tsInlayHintParamTypes.value = v; prefs.edit().putBoolean("ts_inlay_hint_param_types", v).apply() }
    fun setTsWsSymbolsExcludeLib(v: Boolean) { tsWsSymbolsExcludeLib.value = v; prefs.edit().putBoolean("ts_ws_symbols_exclude_lib", v).apply() }
    fun setTsWsSymbolsScope(v: String) { tsWsSymbolsScope.value = v; prefs.edit().putString("ts_ws_symbols_scope", v).apply() }
    fun setWindowTitle(v: String) { windowTitle.value = v; prefs.edit().putString("window_title", v).apply() }
    fun setTerminalIntegratedNotifications(v: Boolean) { terminalIntegratedNotifications.value = v; prefs.edit().putBoolean("terminal_integrated_notifications", v).apply() }
    fun setTerminalCommandsToSkipShell(v: String) { terminalCommandsToSkipShell.value = v; prefs.edit().putString("terminal_commands_to_skip_shell", v).apply() }
    fun setExtensionsIgnoreRecommendations(v: Boolean) { extensionsIgnoreRecommendations.value = v; prefs.edit().putBoolean("extensions_ignore_recommendations", v).apply() }
    fun setTaskNotifyWindowOnCompletion(v: Boolean) { taskNotifyWindowOnCompletion.value = v; prefs.edit().putBoolean("task_notify_window_on_completion", v).apply() }
}
