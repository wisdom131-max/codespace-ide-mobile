package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.editor.CursorBlinkStyle
import com.codespace.ide.editor.CursorMode
import com.codespace.ide.editor.FormatterConfig
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.DiagnosticsSource
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.FlowMode
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.editor.TypeScriptVersion
import androidx.compose.material.icons.filled.ArrowDropDown

/**
 * In-Project Settings — VS Code-style settings dialog with search bar,
 * categorized sidebar, and multiple sections:
 *
 *   - AI Agent Flow (Flow Mode, Verbose Tool Output)
 *   - Editor Features (11 FeatureToggleStore toggles)
 *   - Notifications (Task completion threshold, Terminal notifications, Verbose download)
 *   - Text Editor (Cursor blinking style)
 *   - Python / LSP (Diagnostics source, Pyright version, Node arguments)
 */
@Composable
fun InProjectSettingsDialog(onDismiss: () -> Unit) {
    val bg       = Color(0xFF1E1E1E)
    val sidebarBg = Color(0xFF252526)
    val surface  = Color(0xFF2D2D2D)
    val textPri  = Color(0xFFE0E0E0)
    val textSec  = Color(0xFF888888)
    val accent   = Color(0xFF4FC3F7)
    val accentDim = Color(0xFF3794C3)
    val divider  = Color(0xFF333333)
    val activeCatBg = Color(0xFF37373D)

    var searchQuery by remember { mutableStateOf("") }
    var activeCategory by remember { mutableStateOf(SettingsCategory.AI_AGENT) }

    val categories = SettingsCategory.entries.toList()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = bg, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                // ── Title bar with search ──────────────────────────────
                Row(
                    Modifier.fillMaxWidth().background(sidebarBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("In-Project Settings",
                        color = textPri, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(16.dp))

                    // Search bar
                    Box(
                        Modifier.weight(1f)
                            .background(Color(0xFF3C3C3C), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = textSec,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = textPri, fontSize = 13.sp),
                                cursorBrush = SolidColor(accent),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text("Search settings...",
                                            color = textSec, fontSize = 13.sp)
                                    }
                                    inner()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = textSec,
                            modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = divider)

                // ── Body: sidebar + content ────────────────────────────
                Row(Modifier.fillMaxSize()) {
                    // Sidebar
                    LazyColumn(
                        Modifier.width(200.dp).background(sidebarBg).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(categories) { category ->
                            val isActive = activeCategory == category && searchQuery.isEmpty()
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (isActive) activeCatBg else Color.Transparent)
                                    .clickable { activeCategory = category; searchQuery = "" }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(category.label,
                                    color = if (isActive) accent else textPri,
                                    fontSize = 13.sp,
                                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }

                    VerticalDivider(color = divider, modifier = Modifier.fillMaxHeight())

                    // Content
                    val allRows = remember { buildAllSettingsRows() }
                    val filteredRows = if (searchQuery.isEmpty()) {
                        allRows.filter { it.category == activeCategory }
                    } else {
                        val q = searchQuery.lowercase()
                        allRows.filter {
                            it.label.lowercase().contains(q) ||
                            it.description.lowercase().contains(q) ||
                            it.category.label.lowercase().contains(q)
                        }
                    }

                    if (searchQuery.isNotEmpty() && filteredRows.isNotEmpty()) {
                        Text(
                            "${filteredRows.size} Setting${if (filteredRows.size > 1) "s" else ""} Found",
                            color = textSec, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }

                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (filteredRows.isEmpty()) {
                            item {
                                Text("No settings found",
                                    color = textSec, fontSize = 14.sp,
                                    modifier = Modifier.padding(24.dp))
                            }
                        } else {
                            var lastCategory: SettingsCategory? = null
                            for (row in filteredRows) {
                                if (searchQuery.isNotEmpty() && row.category != lastCategory) {
                                    item(key = "header_${row.category}") {
                                        SectionHeader(row.category.label, textPri)
                                    }
                                    lastCategory = row.category
                                }
                                item(key = row.id) {
                                    SettingsRowRenderer(row, accent, textPri, textSec, surface, divider)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Data model for settings rows ─────────────────────────────────────

enum class SettingsCategory(val label: String) {
    AI_AGENT("AI Agent Flow"),
    EDITOR("Editor Features"),
    NOTIFICATIONS("Notifications"),
    TEXT_EDITOR("Text Editor"),
    FORMATTING("Formatting"),
    PYTHON_LSP("Python / LSP"),
    LSP_SERVERS("LSP Servers"),
}

data class SettingsRow(
    val id: String,
    val category: SettingsCategory,
    val label: String,
    val description: String,
    val type: RowType,
)

enum class RowType {
    FLOW_MODE_DROPDOWN,
    VERBOSE_TOOL_CHECKBOX,
    FEATURE_TOGGLE,           // index into FeatureToggleStore.toggles
    TASK_NOTIFY_THRESHOLD,
    TERMINAL_NOTIFY_CHECKBOX,
    VERBOSE_DOWNLOAD_CHECKBOX,
    EXTRA_KEYS_CHECKBOX,
    CURSOR_BLINK_DROPDOWN,
    ZEN_MODE_EXIT_CHECKBOX,
    FORMAT_ON_SAVE_CHECKBOX,
    FORMATTER_DROPDOWN,      // Phase R — per-language formatter selection
    DIAGNOSTICS_SOURCE_DROPDOWN,
    PYRIGHT_VERSION_INPUT,
    PYRIGHT_NODE_ARGS_INPUT,
    LSP_SERVER_LIST,
    LSP_ENABLED_CHECKBOX,
    TS_VERSION_DROPDOWN,
    CUSTOM_CURSOR_CHECKBOX,
    CURSOR_MODE_DROPDOWN,
}

private fun buildAllSettingsRows(): List<SettingsRow> = buildList {
    add(SettingsRow("flow_mode", SettingsCategory.AI_AGENT, "Flow Mode",
        "Auto = tool calls execute immediately, Manual = approve each step",
        RowType.FLOW_MODE_DROPDOWN))
    add(SettingsRow("verbose_tool", SettingsCategory.AI_AGENT, "Verbose Tool Output",
        "Show full JSON args/results in agent chat",
        RowType.VERBOSE_TOOL_CHECKBOX))

    FeatureToggleStore.toggles.forEachIndexed { idx, toggle ->
        add(SettingsRow("toggle_$idx", SettingsCategory.EDITOR,
            toggle.label, toggle.description,
            RowType.FEATURE_TOGGLE))
    }

    add(SettingsRow("task_notify_threshold", SettingsCategory.NOTIFICATIONS,
        "Task: Notify On Task Completion",
        "Show notification when a long-running task finishes (ms, -1 = never, 0 = always)",
        RowType.TASK_NOTIFY_THRESHOLD))
    add(SettingsRow("terminal_notify", SettingsCategory.NOTIFICATIONS,
        "Terminal: Enable Notifications",
        "Show foreground-service notification while terminal is running",
        RowType.TERMINAL_NOTIFY_CHECKBOX))
    add(SettingsRow("verbose_download", SettingsCategory.NOTIFICATIONS,
        "LSP: Verbose Download Notification",
        "Show detailed progress for LSP server downloads and installs",
        RowType.VERBOSE_DOWNLOAD_CHECKBOX))

    add(SettingsRow("extra_keys", SettingsCategory.TEXT_EDITOR,
        "Extra Coding Keys",
        "Show the toolbar with Tab, Esc, brackets and symbols above the keyboard",
        RowType.EXTRA_KEYS_CHECKBOX))

    add(SettingsRow("cursor_mode", SettingsCategory.TEXT_EDITOR,
        "Cursor Type",
        "In-App: custom touch-friendly overlay cursor. System: phone's built-in native cursor.",
        RowType.CURSOR_MODE_DROPDOWN))
    add(SettingsRow("cursor_blink", SettingsCategory.TEXT_EDITOR,
        "Cursor Blinking",
        "Controls cursor animation style",
        RowType.CURSOR_BLINK_DROPDOWN))
    add(SettingsRow("custom_cursor", SettingsCategory.TEXT_EDITOR,
        "Custom Cursor Overlay",
        "A draggable, touch-friendly cursor overlay that summons the keyboard on tap. Replaces the thin default text cursor.",
        RowType.CUSTOM_CURSOR_CHECKBOX))

    add(SettingsRow("zen_mode_exit", SettingsCategory.TEXT_EDITOR,
        "Zen Mode Exit Button",
        "Show a draggable floating button to exit Zen Mode (disable to use menu only)",
        RowType.ZEN_MODE_EXIT_CHECKBOX))

    // Phase R: Formatting settings
    add(SettingsRow("format_on_save", SettingsCategory.FORMATTING,
        "Format on Save",
        "Run the language formatter before saving files",
        RowType.FORMAT_ON_SAVE_CHECKBOX))

    // Phase R: Per-language formatter dropdowns — only for languages with multiple formatters
    add(SettingsRow("fmt_kotlin", SettingsCategory.FORMATTING,
        "Kotlin Formatter",
        "Select the formatter for .kt and .kts files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_js", SettingsCategory.FORMATTING,
        "JavaScript Formatter",
        "Select the formatter for .js and .jsx files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_ts", SettingsCategory.FORMATTING,
        "TypeScript Formatter",
        "Select the formatter for .ts and .tsx files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_python", SettingsCategory.FORMATTING,
        "Python Formatter",
        "Select the formatter for .py files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_go", SettingsCategory.FORMATTING,
        "Go Formatter",
        "Select the formatter for .go files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_java", SettingsCategory.FORMATTING,
        "Java Formatter",
        "Select the formatter for .java files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_json", SettingsCategory.FORMATTING,
        "JSON Formatter",
        "Select the formatter for .json files",
        RowType.FORMATTER_DROPDOWN))
    add(SettingsRow("fmt_c_cpp", SettingsCategory.FORMATTING,
        "C/C++ Formatter",
        "Select the formatter for C and C++ files",
        RowType.FORMATTER_DROPDOWN))

    add(SettingsRow("diag_source", SettingsCategory.PYTHON_LSP,
        "Diagnostics Source",
        "Which language server provides Python completions and diagnostics",
        RowType.DIAGNOSTICS_SOURCE_DROPDOWN))
    add(SettingsRow("pyright_version", SettingsCategory.PYTHON_LSP,
        "Pyright Version",
        "Version string or path to local pyright-langserver.js (empty = auto-install latest)",
        RowType.PYRIGHT_VERSION_INPUT))
    add(SettingsRow("pyright_node_args", SettingsCategory.PYTHON_LSP,
        "Node Arguments",
        "CLI arguments passed to Node.js when running Pyright (e.g. --max-old-space-size=8192)",
        RowType.PYRIGHT_NODE_ARGS_INPUT))

    // LSP Servers — master toggle + server list
    add(SettingsRow("lsp_enabled", SettingsCategory.LSP_SERVERS,
        "Enable LSP Servers",
        "Master switch for all language servers. When off, only fallback completions are used.",
        RowType.LSP_ENABLED_CHECKBOX))
    add(SettingsRow("lsp_server_list", SettingsCategory.LSP_SERVERS,
        "Available Language Servers",
        "These servers auto-install when you open a file of the matching language",
        RowType.LSP_SERVER_LIST))
    add(SettingsRow("ts_version", SettingsCategory.LSP_SERVERS,
        "TypeScript Version",
        "TypeScript 7 uses native LSP (tsc --lsp --stdio). Older versions use typescript-language-server.",
        RowType.TS_VERSION_DROPDOWN))
}

// ── Row renderer ─────────────────────────────────────────────────────

@Composable
private fun SettingsRowRenderer(
    row: SettingsRow,
    accent: Color,
    textPri: Color,
    textSec: Color,
    surface: Color,
    divider: Color,
) {
    when (row.type) {
        RowType.FLOW_MODE_DROPDOWN -> FlowModeRow(accent, textPri, textSec, divider)
        RowType.VERBOSE_TOOL_CHECKBOX -> VerboseToolOutputRow(textPri, textSec, divider)
        RowType.FEATURE_TOGGLE -> {
            val idx = row.id.removePrefix("toggle_").toInt()
            val toggle = FeatureToggleStore.toggles[idx]
            val state = remember(toggle.key) { FeatureToggleStore.state(toggle.key) }
            ToggleRow(toggle.label, toggle.description, state.value,
                { state.value = it; FeatureToggleStore.set(toggle.key, it) },
                textPri, textSec, divider)
        }
        RowType.TASK_NOTIFY_THRESHOLD -> TaskNotifyThresholdRow(textPri, textSec, divider)
        RowType.TERMINAL_NOTIFY_CHECKBOX -> TerminalNotifyRow(textPri, textSec, divider)
        RowType.VERBOSE_DOWNLOAD_CHECKBOX -> VerboseDownloadRow(textPri, textSec, divider)
        RowType.EXTRA_KEYS_CHECKBOX -> ExtraKeysRow(textPri, textSec, divider)
        RowType.CURSOR_BLINK_DROPDOWN -> CursorBlinkRow(accent, textPri, textSec, divider)
        RowType.ZEN_MODE_EXIT_CHECKBOX -> ZenModeExitRow(textPri, textSec, divider)
        RowType.FORMAT_ON_SAVE_CHECKBOX -> FormatOnSaveRow(textPri, textSec, divider)
        RowType.FORMATTER_DROPDOWN -> FormatterDropdownRow(row, accent, textPri, textSec, divider)
        RowType.DIAGNOSTICS_SOURCE_DROPDOWN -> DiagnosticsSourceRow(accent, textPri, textSec, divider)
        RowType.PYRIGHT_VERSION_INPUT -> PyrightVersionRow(textPri, textSec, surface, divider)
        RowType.PYRIGHT_NODE_ARGS_INPUT -> PyrightNodeArgsRow(textPri, textSec, surface, divider)
        RowType.LSP_SERVER_LIST -> LspServerListRow(accent, textPri, textSec, surface, divider)
        RowType.LSP_ENABLED_CHECKBOX -> LspEnabledRow(textPri, textSec, divider)
        RowType.TS_VERSION_DROPDOWN -> TypeScriptVersionRow(accent, textPri, textSec, divider)
        RowType.CUSTOM_CURSOR_CHECKBOX -> CustomCursorOverlayRow(textPri, textSec, divider)
        RowType.CURSOR_MODE_DROPDOWN -> CursorModeRow(accent, textPri, textSec, divider)
    }
}

@Composable
private fun ZenModeExitRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.zenModeExitButtonEnabled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Zen Mode Exit Button", color = textPri, fontSize = 13.sp)
            Text("Show a draggable floating button to exit Zen Mode (disable to use menu only)",
                color = textSec, fontSize = 11.sp)
        }
        Switch(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setZenModeExitButtonEnabled(it) },
        )
    }
    HorizontalDivider(color = divider, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun TypeScriptVersionRow(accent: Color, textPri: Color, textSec: Color, divider: Color) {
    var expanded by remember { mutableStateOf(false) }
    val current = ProjectSettingsStore.typescriptVersion.value

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("TypeScript Version", color = textPri, fontSize = 13.sp)
                Text(current.displayName, color = accent, fontSize = 11.sp)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select", tint = textSec, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TypeScriptVersion.entries.forEach { version ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(version.displayName, fontSize = 13.sp)
                            Text("LSP: ${version.lspServer}", fontSize = 10.sp, color = textSec)
                        }
                    },
                    onClick = {
                        ProjectSettingsStore.setTypeScriptVersion(version)
                        expanded = false
                    },
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = divider)
    }
}

@Composable
private fun LspEnabledRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.lspEnabled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enable LSP Servers", color = textPri, fontSize = 13.sp)
            Text("Master switch for all language servers. When off, only fallback completions are used.",
                color = textSec, fontSize = 11.sp)
        }
        Switch(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setLspEnabled(it) },
        )
    }
    HorizontalDivider(color = divider, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun CursorModeRow(accent: Color, textPri: Color, textSec: Color, divider: Color) {
    val cursorMode = ProjectSettingsStore.cursorMode
    var expanded by remember { mutableStateOf(false) }
    val options = CursorMode.entries.toList()
    val labels = mapOf(
        CursorMode.IN_APP to "In-App (Custom Overlay)",
        CursorMode.SYSTEM to "System (Phone Built-in)",
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Cursor Type", color = textPri, fontSize = 13.sp)
            Text("In-App: custom touch-friendly overlay. System: phone's built-in cursor.",
                color = textSec, fontSize = 11.sp)
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(labels[cursorMode.value] ?: cursorMode.value.name,
                    color = accent, fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(labels[mode] ?: mode.name, fontSize = 12.sp) },
                        onClick = {
                            ProjectSettingsStore.setCursorMode(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = divider, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun CustomCursorOverlayRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.customCursorOverlayEnabled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Custom Cursor Overlay", color = textPri, fontSize = 13.sp)
            Text("A draggable, touch-friendly cursor overlay that summons the keyboard on tap",
                color = textSec, fontSize = 11.sp)
        }
        Switch(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setCustomCursorOverlayEnabled(it) },
        )
    }
    HorizontalDivider(color = divider, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun FormatOnSaveRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.formatOnSaveEnabled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Format on Save", color = textPri, fontSize = 13.sp)
            Text("Run the language formatter before saving files",
                color = textSec, fontSize = 11.sp)
        }
        Switch(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setFormatOnSaveEnabled(it) },
        )
    }
    HorizontalDivider(color = divider, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun FormatterDropdownRow(
    row: SettingsRow,
    accent: Color,
    textPri: Color,
    textSec: Color,
    divider: Color,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Map row ID to Language
    val language = when (row.id) {
        "fmt_kotlin" -> Language.KOTLIN
        "fmt_js" -> Language.JAVASCRIPT
        "fmt_ts" -> Language.TYPESCRIPT
        "fmt_python" -> Language.PYTHON
        "fmt_go" -> Language.GO
        "fmt_java" -> Language.JAVA
        "fmt_json" -> Language.JSON
        "fmt_c_cpp" -> Language.CPP
        else -> return
    }

    val formatters = FormatterConfig.availableFormatters[language] ?: return
    val selected = FormatterConfig.getSelectedFormatter(context, language)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(row.description, color = textSec, fontSize = 11.sp)
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected.name, fontSize = 12.sp, color = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                formatters.forEach { fmt ->
                    DropdownMenuItem(
                        text = { Text(fmt.name) },
                        onClick = {
                            FormatterConfig.setSelectedFormatter(context, language, fmt.name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(text,
        color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun FlowModeRow(accent: Color, textPri: Color, textSec: Color, divider: Color) {
    var expanded by remember { mutableStateOf(false) }
    val currentMode = ProjectSettingsStore.flowMode.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Flow Mode", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                if (currentMode == FlowMode.AUTO)
                    "Tool calls execute immediately (default)"
                else
                    "Each tool call pauses for your approval",
                color = textSec, fontSize = 11.sp,
            )
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (currentMode == FlowMode.AUTO) "Auto" else "Manual",
                    fontSize = 12.sp, color = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Auto — execute immediately") },
                    onClick = { ProjectSettingsStore.setFlowMode(FlowMode.AUTO); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("Manual — approve each step") },
                    onClick = { ProjectSettingsStore.setFlowMode(FlowMode.MANUAL); expanded = false },
                )
            }
        }
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun VerboseToolOutputRow(textPri: Color, textSec: Color, divider: Color) {
    val verbose = ProjectSettingsStore.verboseToolOutput
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Verbose Tool Output", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show full JSON args/results in agent chat", color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = verbose.value,
            onCheckedChange = { ProjectSettingsStore.setVerboseToolOutput(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun ToggleRow(
    label: String, description: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    textPri: Color, textSec: Color, divider: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun TaskNotifyThresholdRow(textPri: Color, textSec: Color, divider: Color) {
    val threshold = ProjectSettingsStore.taskNotifyThresholdMs.value
    var textValue by remember(threshold) { mutableStateOf(threshold.toString()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Task: Notify On Task Completion", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show notification when a long-running task finishes (ms, -1 = never, 0 = always)", color = textSec, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = textValue,
            onValueChange = { newText ->
                textValue = newText.filter { it.isDigit() || it == '-' }
                textValue.toIntOrNull()?.let { ProjectSettingsStore.setTaskNotifyThresholdMs(it) }
            },
            modifier = Modifier.width(100.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = textPri, fontSize = 12.sp),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun TerminalNotifyRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.terminalNotifications
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Terminal: Enable Notifications", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show foreground-service notification while terminal is running", color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setTerminalNotifications(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun VerboseDownloadRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.verboseDownloadNotify
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("LSP: Verbose Download Notification", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show detailed progress for LSP server downloads and installs", color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setVerboseDownloadNotify(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun ExtraKeysRow(textPri: Color, textSec: Color, divider: Color) {
    val enabled = ProjectSettingsStore.extraKeysEnabled
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Extra Coding Keys", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Show the toolbar with Tab, Esc, brackets and symbols above the keyboard", color = textSec, fontSize = 11.sp)
        }
        Checkbox(
            checked = enabled.value,
            onCheckedChange = { ProjectSettingsStore.setExtraKeysEnabled(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4FC3F7)),
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun CursorBlinkRow(accent: Color, textPri: Color, textSec: Color, divider: Color) {
    var expanded by remember { mutableStateOf(false) }
    val current = ProjectSettingsStore.cursorBlinkStyle.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Cursor Blinking", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Controls cursor animation style", color = textSec, fontSize = 11.sp)
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(current.name.lowercase().replaceFirstChar { it.titlecase() },
                    fontSize = 12.sp, color = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                CursorBlinkStyle.entries.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.name.lowercase().replaceFirstChar { it.titlecase() }) },
                        onClick = {
                            ProjectSettingsStore.setCursorBlinkStyle(style)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun DiagnosticsSourceRow(accent: Color, textPri: Color, textSec: Color, divider: Color) {
    var expanded by remember { mutableStateOf(false) }
    val current = ProjectSettingsStore.diagnosticsSource.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Diagnostics Source", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Which language server provides Python completions and diagnostics", color = textSec, fontSize = 11.sp)
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(when (current) {
                    DiagnosticsSource.PYLSP -> "Pylsp (Jedi)"
                    DiagnosticsSource.PYRIGHT -> "Pyright"
                }, fontSize = 12.sp, color = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Pylsp (Jedi) — default") },
                    onClick = { ProjectSettingsStore.setDiagnosticsSource(DiagnosticsSource.PYLSP); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("Pyright — Microsoft") },
                    onClick = { ProjectSettingsStore.setDiagnosticsSource(DiagnosticsSource.PYRIGHT); expanded = false },
                )
            }
        }
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun PyrightVersionRow(textPri: Color, textSec: Color, surface: Color, divider: Color) {
    var text by remember { mutableStateOf(ProjectSettingsStore.pyrightVersion.value) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Pyright Version", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Version string or path to local pyright-langserver.js (empty = auto-install)", color = textSec, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; ProjectSettingsStore.setPyrightVersion(it) },
            modifier = Modifier.width(180.dp),
            singleLine = true,
            textStyle = TextStyle(color = textPri, fontSize = 12.sp),
            placeholder = { Text("auto", color = textSec, fontSize = 12.sp) },
        )
    }
    HorizontalDivider(color = divider)
}

@Composable
private fun PyrightNodeArgsRow(textPri: Color, textSec: Color, surface: Color, divider: Color) {
    var text by remember { mutableStateOf(ProjectSettingsStore.pyrightNodeArgs.value) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Node Arguments", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("CLI arguments passed to Node.js when running Pyright", color = textSec, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; ProjectSettingsStore.setPyrightNodeArgs(it) },
            modifier = Modifier.width(180.dp),
            singleLine = true,
            textStyle = TextStyle(color = textPri, fontSize = 12.sp),
            placeholder = { Text("--max-old-space-size=8192", color = textSec, fontSize = 12.sp) },
        )
    }
    HorizontalDivider(color = divider)
}

// ── LSP Server List Row ──────────────────────────────────────────────

private data class LspServerInfo(
    val language: String,
    val serverName: String,
    val installMethod: String,
)

private val lspServerList = listOf(
    LspServerInfo("TypeScript", "typescript-language-server", "npm"),
    LspServerInfo("JavaScript", "typescript-language-server", "npm"),
    LspServerInfo("Python", "pylsp (default) / pyright", "pip3 / npm"),
    LspServerInfo("Kotlin", "kotlin-language-server", "GitHub release"),
    LspServerInfo("Go", "gopls", "go install"),
    LspServerInfo("Java", "jdtls (eclipse.jdt.ls)", "curl + tar"),
    LspServerInfo("C", "clangd", "apt"),
    LspServerInfo("C++", "clangd", "apt"),
    LspServerInfo("Rust", "rust-analyzer", "rustup"),
    LspServerInfo("PHP", "intelephense", "npm"),
    LspServerInfo("HTML", "vscode-html-language-server", "npm"),
    LspServerInfo("CSS", "vscode-css-language-server", "npm"),
    LspServerInfo("JSON", "vscode-json-language-server", "npm"),
    LspServerInfo("Ruby", "solargraph", "gem"),
    LspServerInfo("C#", "OmniSharp", "curl + tar"),
    LspServerInfo("Lua", "lua-language-server", "curl + tar"),
    LspServerInfo("Dart", "dart language-server", "curl + unzip"),
    LspServerInfo("SQL", "sql-language-server", "npm"),
    LspServerInfo("PowerShell", "PowerShellEditorServices", "curl + tar"),
    LspServerInfo("Scala", "metals", "curl"),
    LspServerInfo("R", "languageserver", "apt + R"),
    LspServerInfo("Swift", "sourcekit-lsp", "curl + tar"),
    LspServerInfo("Universal", "ctags-lsp (fallback)", "go install"),
)

@Composable
private fun LspServerListRow(accent: Color, textPri: Color, textSec: Color, surface: Color, divider: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Available Language Servers", color = textPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("Auto-install when you open a matching file type", color = textSec, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        lspServerList.forEach { server ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(server.language, color = textPri, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(90.dp))
                Text(server.serverName, color = textSec, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                Text(server.installMethod, color = accent, fontSize = 10.sp)
            }
        }
    }
    HorizontalDivider(color = divider)
}
