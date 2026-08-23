package com.codespace.ide.editor.settings

/**
 * Schema definition for all IDE settings.
 *
 * Defines every setting key, its type, default value, category, and
 * human-readable label/description. Single source of truth for the JSON store.
 *
 * Architecture reference: VS Code's IConfigurationRegistry (MIT).
 */
object SettingsSchema {

    const val CURRENT_VERSION = 1

    enum class Category {
        AI_AGENT, EDITOR_FEATURES, NOTIFICATIONS, TEXT_EDITOR, ZEN_MODE,
        FORMATTING, LSP, PYTHON, TYPESCRIPT, ACCESSIBILITY,
        TS_FORMAT, TS_TSSERVER, TS_INLAY_HINTS, TS_WORKSPACE_SYMBOLS,
        WINDOW, TERMINAL, EXTENSIONS, TEXTMATE, TASK, CURSOR,
    }

    sealed class SettingType {
        data class Bool(val default: Boolean) : SettingType()
        data class Int(val default: kotlin.Int) : SettingType()
        data class Long(val default: kotlin.Long) : SettingType()
        data class Str(val default: String) : SettingType()
        data class Enum(val default: String, val values: List<String>) : SettingType()
    }

    data class SettingDef(
        val key: String,
        val category: Category,
        val type: SettingType,
        val label: String,
        val description: String,
    )

    val all: List<SettingDef> = listOf(
        SettingDef("flow_mode", Category.AI_AGENT, SettingType.Enum("AUTO", listOf("MANUAL", "AUTO")), "Flow Mode", "AI agent execution mode"),
        SettingDef("verbose_tool_output", Category.AI_AGENT, SettingType.Bool(false), "Verbose Tool Output", "Show detailed tool output"),
        SettingDef("extra_keys_enabled", Category.TEXT_EDITOR, SettingType.Bool(true), "Extra Keys", "Show extra coding keys toolbar"),
        SettingDef("task_notify_threshold_ms", Category.NOTIFICATIONS, SettingType.Int(8000), "Task Notify Threshold", "Notification threshold in ms"),
        SettingDef("terminal_notifications", Category.NOTIFICATIONS, SettingType.Bool(true), "Terminal Notifications", "Terminal foreground-service notifications"),
        SettingDef("verbose_download_notify", Category.NOTIFICATIONS, SettingType.Bool(false), "Verbose Download Notify", "Verbose LSP download notifications"),
        SettingDef("cursor_blink_style", Category.TEXT_EDITOR, SettingType.Enum("BLINK", listOf("BLINK", "PHASE", "SOLID", "EXPAND", "SMOOTH")), "Cursor Blink Style", "Cursor blinking style"),
        SettingDef("zen_mode_exit_button", Category.ZEN_MODE, SettingType.Bool(true), "Zen Mode Exit Button", "Show floating exit button in Zen Mode"),
        SettingDef("mcp_indicator_enabled", Category.ZEN_MODE, SettingType.Bool(true), "MCP Indicator", "Show MCP status dot in status bar"),
        SettingDef("format_on_save", Category.FORMATTING, SettingType.Bool(true), "Format on Save", "Format before saving"),
        SettingDef("lsp_enabled", Category.LSP, SettingType.Bool(true), "LSP Enabled", "Master switch for all LSP servers"),
        SettingDef("lsp_idle_timeout_seconds", Category.LSP, SettingType.Long(300L), "LSP Idle Timeout", "Idle auto-close timeout in seconds"),
        SettingDef("smart_completion_enabled", Category.LSP, SettingType.Bool(true), "Smart Completion", "LSP completions with fallback"),
        SettingDef("custom_cursor_overlay", Category.CURSOR, SettingType.Bool(false), "Custom Cursor Overlay", "Draggable tap-to-type cursor"),
        SettingDef("cursor_mode", Category.CURSOR, SettingType.Enum("IN_APP", listOf("IN_APP", "SYSTEM")), "Cursor Mode", "In-app vs system cursor"),
        SettingDef("diagnostics_source", Category.PYTHON, SettingType.Enum("PYRIGHT", listOf("PYLSP", "PYRIGHT")), "Diagnostics Source", "LSP diagnostics provider"),
        SettingDef("pyright_version", Category.PYTHON, SettingType.Str(""), "Pyright Version", "Version or path to pyright-langserver.js"),
        SettingDef("pyright_node_args", Category.PYTHON, SettingType.Str("--max-old-space-size=8192"), "Pyright Node Args", "Node.js CLI args for pyright"),
        SettingDef("typescript_version", Category.TYPESCRIPT, SettingType.Enum("TS7", listOf("TS7", "TS5", "TS4")), "TypeScript Version", "TS version + LSP server"),
        SettingDef("acc_signal_position_warning", Category.ACCESSIBILITY, SettingType.Bool(true), "Acc: Position Warning", "Position warning signal"),
        SettingDef("acc_signal_progress", Category.ACCESSIBILITY, SettingType.Bool(true), "Acc: Progress", "Progress signal"),
        SettingDef("ts_format_enabled", Category.TS_FORMAT, SettingType.Bool(true), "TS Format Enabled", "Enable TS/JS formatting"),
        SettingDef("ts_format_indent_switch_case", Category.TS_FORMAT, SettingType.Bool(true), "Indent Switch Case", "Indent case in switch"),
        SettingDef("ts_format_space_after_comma", Category.TS_FORMAT, SettingType.Bool(true), "Space After Comma", "Space after commas"),
        SettingDef("ts_format_space_after_constructor", Category.TS_FORMAT, SettingType.Bool(false), "Space After Constructor", "Space after constructor keyword"),
        SettingDef("ts_format_space_after_function_keyword", Category.TS_FORMAT, SettingType.Bool(false), "Space After Function Keyword", "Space after function keyword"),
        SettingDef("ts_format_space_after_control_flow", Category.TS_FORMAT, SettingType.Bool(false), "Space After Control Flow", "Space after control flow"),
        SettingDef("ts_server_log", Category.TS_TSSERVER, SettingType.Bool(false), "TS Server Log", "Enable tsserver logging"),
        SettingDef("ts_use_syntax_server", Category.TS_TSSERVER, SettingType.Bool(true), "Use Syntax Server", "Use lighter syntax-only server"),
        SettingDef("ts_inlay_hint_suppress_match_name", Category.TS_INLAY_HINTS, SettingType.Bool(true), "Suppress Match Name Hints", "Suppress hints when name matches"),
        SettingDef("ts_inlay_hint_param_types", Category.TS_INLAY_HINTS, SettingType.Bool(false), "Parameter Type Hints", "Show parameter type hints"),
        SettingDef("ts_ws_symbols_exclude_lib", Category.TS_WORKSPACE_SYMBOLS, SettingType.Bool(false), "Exclude Library Symbols", "Exclude lib symbols"),
        SettingDef("ts_ws_symbols_scope", Category.TS_WORKSPACE_SYMBOLS, SettingType.Str("allOpenProjects"), "Workspace Symbols Scope", "Symbol search scope"),
        SettingDef("window_title", Category.WINDOW, SettingType.Str("\${activeEditorShort}\${separator}\${rootName}"), "Window Title", "Window title template"),
        SettingDef("terminal_integrated_notifications", Category.TERMINAL, SettingType.Bool(true), "Terminal Integrated Notifications", "Terminal event notifications"),
        SettingDef("terminal_commands_to_skip_shell", Category.TERMINAL, SettingType.Str(""), "Commands to Skip Shell", "Commands bypassing shell integration"),
        SettingDef("extensions_ignore_recommendations", Category.EXTENSIONS, SettingType.Bool(false), "Ignore Recommendations", "Ignore extension recommendations"),
        SettingDef("textmate_highlighting_enabled", Category.TEXTMATE, SettingType.Bool(false), "TextMate Highlighting", "TextMate grammar-based highlighting"),
        SettingDef("task_notify_window_on_completion", Category.TASK, SettingType.Bool(true), "Notify on Task Completion", "Window notification on task completion"),
    )

    val byKey: Map<String, SettingDef> = all.associateBy { it.key }

    data class ToggleDef(
        val key: String,
        val default: Boolean,
        val label: String,
        val description: String,
    )

    val featureToggles: List<ToggleDef> = listOf(
        ToggleDef("word_wrap", false, "Word wrap", "Wrap long lines instead of horizontal scroll"),
        ToggleDef("inlay_hints", true, "Inlay hints", "Inline type and parameter hints"),
        ToggleDef("minimap", true, "Minimap", "Code overview minimap in the gutter"),
        ToggleDef("code_lens", true, "CodeLens", "Run/Debug actions above functions"),
        ToggleDef("sticky_scroll", true, "Sticky scroll", "Pin current scope header while scrolling"),
        ToggleDef("error_lens", true, "Error lens", "Show inline error messages at end of line"),
        ToggleDef("color_swatches", true, "Color swatches", "Color preview boxes next to hex colors"),
        ToggleDef("document_links", true, "Document links", "Clickable links in comments and strings"),
        ToggleDef("ghost_text", true, "Ghost text", "AI suggestion preview as dimmed text"),
        ToggleDef("merge_conflicts", true, "Merge conflicts", "Highlight merge conflict markers with resolve buttons"),
        ToggleDef("lsp_highlights", true, "LSP highlights", "Highlight occurrences of symbol under cursor"),
    )

    val toggleByKey: Map<String, ToggleDef> = featureToggles.associateBy { it.key }

    fun defaultValue(key: String): Any? = byKey[key]?.let { def ->
        when (def.type) {
            is SettingType.Bool -> def.type.default
            is SettingType.Int -> def.type.default
            is SettingType.Long -> def.type.default
            is SettingType.Str -> def.type.default
            is SettingType.Enum -> def.type.default
        }
    }

    fun defaultToggle(key: String): Boolean = toggleByKey[key]?.default ?: true
}
