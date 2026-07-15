package com.codespace.ide.ui.screens

import com.codespace.ide.ui.panels.ToolchainPanel
import com.codespace.ide.ui.panels.TaskRunnerPanel
import com.codespace.ide.ui.panels.BuildHistoryPanel
import com.codespace.ide.ui.panels.ArtifactPanel
import com.codespace.ide.ui.panels.DownloadCenterPanel
import com.codespace.ide.ui.panels.CloudBackupPanel

import com.codespace.ide.util.WorkspaceManager
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.data.SessionStateStore
import com.codespace.ide.terminal.BusyboxInstaller
import com.codespace.ide.ui.panes.TerminalState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.codespace.ide.terminal.TerminalEnhancementManager
import com.codespace.ide.ui.panes.*
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.diagnostics.MemoryMonitor
import com.codespace.ide.diagnostics.SyncState
import com.codespace.ide.diagnostics.SyncStatusMonitor
import com.codespace.ide.diagnostics.CodeMetrics
import com.codespace.ide.diagnostics.LintChecker
import com.codespace.ide.diagnostics.Problem
import com.codespace.ide.diagnostics.PortsScanner
import com.codespace.ide.diagnostics.ForwardedPort
import com.codespace.ide.ui.panes.LogcatPanel
import com.codespace.ide.ui.panes.VariableInspectorPanel
import com.codespace.ide.ui.panes.SymbolSearchPanel
import com.codespace.ide.ui.panes.ProjectFileSearchPanel
import com.codespace.ide.ui.panes.BuildPanel
import com.codespace.ide.editor.FileIndexer
import org.json.JSONArray

// ── Theme-aware colors (read from MaterialTheme + currentTheme name) ──────────
private data class IdeColors(
    val BgColor: Color,
    val ActivityBarBg: Color,
    val ActivityBarIcon: Color,
    val ActivityBarIconActive: Color,
    val TabBarBg: Color,
    val TabActiveBg: Color,
    val TabInactiveBg: Color,
    val TabActiveIndicator: Color,
    val TabText: Color,
    val TabTextInactive: Color,
    val DividerColor: Color,
    val StatusBarBg: Color,
    val PanelBg: Color,
    val SectionHeaderText: Color,
    val MenuBg: Color,
    val MenuBorder: Color,
    val MenuText: Color,
    val CmdSelectedBg: Color,
    val CmdSelectedText: Color,
    val KeyboardToolbarBg: Color,
)

@Composable
private fun ideColors(themeName: String): IdeColors {
    val isDark = !themeName.contains("Light")
    return when (themeName) {
        "Dracula" -> IdeColors(
            BgColor = Color(0xFF282A36), ActivityBarBg = Color(0xFF21222C),
            ActivityBarIcon = Color(0xFF6272A4), ActivityBarIconActive = Color(0xFFBD93F9),
            TabBarBg = Color(0xFF21222C), TabActiveBg = Color(0xFF282A36),
            TabInactiveBg = Color(0xFF21222C), TabActiveIndicator = Color(0xFFBD93F9),
            TabText = Color(0xFFF8F8F2), TabTextInactive = Color(0xFF6272A4),
            DividerColor = Color(0xFF44475A), StatusBarBg = Color(0xFF6272A4),
            PanelBg = Color(0xFF21222C), SectionHeaderText = Color(0xFF6272A4),
            MenuBg = Color(0xFF282A36), MenuBorder = Color(0xFF44475A),
            MenuText = Color(0xFFF8F8F2), CmdSelectedBg = Color(0xFF44475A),
            CmdSelectedText = Color(0xFFF8F8F2), KeyboardToolbarBg = Color(0xFF21222C),
        )
        "AMOLED Black" -> IdeColors(
            BgColor = Color(0xFF000000), ActivityBarBg = Color(0xFF0A0A0A),
            ActivityBarIcon = Color(0xFF555555), ActivityBarIconActive = Color(0xFFFF79C6),
            TabBarBg = Color(0xFF0A0A0A), TabActiveBg = Color(0xFF000000),
            TabInactiveBg = Color(0xFF0A0A0A), TabActiveIndicator = Color(0xFFFF79C6),
            TabText = Color(0xFFEEEEEE), TabTextInactive = Color(0xFF555555),
            DividerColor = Color(0xFF222222), StatusBarBg = Color(0xFF0A0A0A),
            PanelBg = Color(0xFF0A0A0A), SectionHeaderText = Color(0xFF555555),
            MenuBg = Color(0xFF111111), MenuBorder = Color(0xFF222222),
            MenuText = Color(0xFFEEEEEE), CmdSelectedBg = Color(0xFFFF79C6),
            CmdSelectedText = Color(0xFF000000), KeyboardToolbarBg = Color(0xFF0A0A0A),
        )
        "Monokai" -> IdeColors(
            BgColor = Color(0xFF272822), ActivityBarBg = Color(0xFF1E1F1C),
            ActivityBarIcon = Color(0xFF75715E), ActivityBarIconActive = Color(0xFFA6E22E),
            TabBarBg = Color(0xFF1E1F1C), TabActiveBg = Color(0xFF272822),
            TabInactiveBg = Color(0xFF1E1F1C), TabActiveIndicator = Color(0xFFA6E22E),
            TabText = Color(0xFFF8F8F2), TabTextInactive = Color(0xFF75715E),
            DividerColor = Color(0xFF3E3D32), StatusBarBg = Color(0xFF1E1F1C),
            PanelBg = Color(0xFF1E1F1C), SectionHeaderText = Color(0xFF75715E),
            MenuBg = Color(0xFF272822), MenuBorder = Color(0xFF3E3D32),
            MenuText = Color(0xFFF8F8F2), CmdSelectedBg = Color(0xFFA6E22E),
            CmdSelectedText = Color(0xFF272822), KeyboardToolbarBg = Color(0xFF1E1F1C),
        )
        "Nord" -> IdeColors(
            BgColor = Color(0xFF2E3440), ActivityBarBg = Color(0xFF242933),
            ActivityBarIcon = Color(0xFF4C566A), ActivityBarIconActive = Color(0xFF88C0D0),
            TabBarBg = Color(0xFF242933), TabActiveBg = Color(0xFF2E3440),
            TabInactiveBg = Color(0xFF242933), TabActiveIndicator = Color(0xFF88C0D0),
            TabText = Color(0xFFECEFF4), TabTextInactive = Color(0xFF4C566A),
            DividerColor = Color(0xFF3B4252), StatusBarBg = Color(0xFF242933),
            PanelBg = Color(0xFF242933), SectionHeaderText = Color(0xFF4C566A),
            MenuBg = Color(0xFF2E3440), MenuBorder = Color(0xFF3B4252),
            MenuText = Color(0xFFECEFF4), CmdSelectedBg = Color(0xFF88C0D0),
            CmdSelectedText = Color(0xFF2E3440), KeyboardToolbarBg = Color(0xFF242933),
        )
        "Tokyo Night" -> IdeColors(
            BgColor = Color(0xFF1A1B26), ActivityBarBg = Color(0xFF16161E),
            ActivityBarIcon = Color(0xFF565F89), ActivityBarIconActive = Color(0xFF7DCFFF),
            TabBarBg = Color(0xFF16161E), TabActiveBg = Color(0xFF1A1B26),
            TabInactiveBg = Color(0xFF16161E), TabActiveIndicator = Color(0xFF7DCFFF),
            TabText = Color(0xFFC0CAF5), TabTextInactive = Color(0xFF565F89),
            DividerColor = Color(0xFF292E42), StatusBarBg = Color(0xFF16161E),
            PanelBg = Color(0xFF16161E), SectionHeaderText = Color(0xFF565F89),
            MenuBg = Color(0xFF1A1B26), MenuBorder = Color(0xFF292E42),
            MenuText = Color(0xFFC0CAF5), CmdSelectedBg = Color(0xFF7DCFFF),
            CmdSelectedText = Color(0xFF1A1B26), KeyboardToolbarBg = Color(0xFF16161E),
        )
        "One Dark Pro" -> IdeColors(
            BgColor = Color(0xFF282C34), ActivityBarBg = Color(0xFF21252B),
            ActivityBarIcon = Color(0xFF5C6370), ActivityBarIconActive = Color(0xFF61AFEF),
            TabBarBg = Color(0xFF21252B), TabActiveBg = Color(0xFF282C34),
            TabInactiveBg = Color(0xFF21252B), TabActiveIndicator = Color(0xFF61AFEF),
            TabText = Color(0xFFABB2BF), TabTextInactive = Color(0xFF5C6370),
            DividerColor = Color(0xFF3E4451), StatusBarBg = Color(0xFF21252B),
            PanelBg = Color(0xFF21252B), SectionHeaderText = Color(0xFF5C6370),
            MenuBg = Color(0xFF282C34), MenuBorder = Color(0xFF3E4451),
            MenuText = Color(0xFFABB2BF), CmdSelectedBg = Color(0xFF61AFEF),
            CmdSelectedText = Color(0xFF282C34), KeyboardToolbarBg = Color(0xFF21252B),
        )
        "GitHub Dark" -> IdeColors(
            BgColor = Color(0xFF0D1117), ActivityBarBg = Color(0xFF010409),
            ActivityBarIcon = Color(0xFF484F58), ActivityBarIconActive = Color(0xFFD2A8FF),
            TabBarBg = Color(0xFF010409), TabActiveBg = Color(0xFF0D1117),
            TabInactiveBg = Color(0xFF010409), TabActiveIndicator = Color(0xFFD2A8FF),
            TabText = Color(0xFFE6EDF3), TabTextInactive = Color(0xFF484F58),
            DividerColor = Color(0xFF21262D), StatusBarBg = Color(0xFF010409),
            PanelBg = Color(0xFF010409), SectionHeaderText = Color(0xFF484F58),
            MenuBg = Color(0xFF0D1117), MenuBorder = Color(0xFF21262D),
            MenuText = Color(0xFFE6EDF3), CmdSelectedBg = Color(0xFFD2A8FF),
            CmdSelectedText = Color(0xFF0D1117), KeyboardToolbarBg = Color(0xFF010409),
        )
        "Catppuccin" -> IdeColors(
            BgColor = Color(0xFF1E1E2E), ActivityBarBg = Color(0xFF181825),
            ActivityBarIcon = Color(0xFF6C7086), ActivityBarIconActive = Color(0xFF89B4FA),
            TabBarBg = Color(0xFF181825), TabActiveBg = Color(0xFF1E1E2E),
            TabInactiveBg = Color(0xFF181825), TabActiveIndicator = Color(0xFF89B4FA),
            TabText = Color(0xFFCDD6F4), TabTextInactive = Color(0xFF6C7086),
            DividerColor = Color(0xFF313244), StatusBarBg = Color(0xFF181825),
            PanelBg = Color(0xFF181825), SectionHeaderText = Color(0xFF6C7086),
            MenuBg = Color(0xFF1E1E2E), MenuBorder = Color(0xFF313244),
            MenuText = Color(0xFFCDD6F4), CmdSelectedBg = Color(0xFF89B4FA),
            CmdSelectedText = Color(0xFF1E1E2E), KeyboardToolbarBg = Color(0xFF181825),
        )
        "Dark (Default)", "Dark Modern" -> IdeColors(
            BgColor = Color(0xFF1E1E1E), ActivityBarBg = Color(0xFF333333),
            ActivityBarIcon = Color(0xFF858585), ActivityBarIconActive = Color(0xFFFFFFFF),
            TabBarBg = Color(0xFF2D2D2D), TabActiveBg = Color(0xFF1E1E1E),
            TabInactiveBg = Color(0xFF2D2D2D), TabActiveIndicator = Color(0xFF007ACC),
            TabText = Color(0xFFFFFFFF), TabTextInactive = Color(0xFF969696),
            DividerColor = Color(0xFF444444), StatusBarBg = Color(0xFF007ACC),
            PanelBg = Color(0xFF252526), SectionHeaderText = Color(0xFF969696),
            MenuBg = Color(0xFF252526), MenuBorder = Color(0xFF454545),
            MenuText = Color(0xFFCCCCCC), CmdSelectedBg = Color(0xFF0060C0),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFF2D2D2D),
        )
        "GitHub Light" -> IdeColors(
            BgColor = Color(0xFFFFFFFF), ActivityBarBg = Color(0xFFF6F8FA),
            ActivityBarIcon = Color(0xFF57606A), ActivityBarIconActive = Color(0xFF0550AE),
            TabBarBg = Color(0xFFF6F8FA), TabActiveBg = Color(0xFFFFFFFF),
            TabInactiveBg = Color(0xFFF6F8FA), TabActiveIndicator = Color(0xFF0550AE),
            TabText = Color(0xFF1F2328), TabTextInactive = Color(0xFF57606A),
            DividerColor = Color(0xFFD0D7DE), StatusBarBg = Color(0xFF0550AE),
            PanelBg = Color(0xFFF6F8FA), SectionHeaderText = Color(0xFF57606A),
            MenuBg = Color(0xFFFFFFFF), MenuBorder = Color(0xFFD0D7DE),
            MenuText = Color(0xFF1F2328), CmdSelectedBg = Color(0xFF0550AE),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFFF6F8FA),
        )
        "Solarized Light" -> IdeColors(
            BgColor = Color(0xFFFDF6E3), ActivityBarBg = Color(0xFFEEE8D5),
            ActivityBarIcon = Color(0xFF93A1A1), ActivityBarIconActive = Color(0xFF268BD2),
            TabBarBg = Color(0xFFEEE8D5), TabActiveBg = Color(0xFFFDF6E3),
            TabInactiveBg = Color(0xFFEEE8D5), TabActiveIndicator = Color(0xFF268BD2),
            TabText = Color(0xFF657B83), TabTextInactive = Color(0xFF93A1A1),
            DividerColor = Color(0xFFD3CBB8), StatusBarBg = Color(0xFF268BD2),
            PanelBg = Color(0xFFEEE8D5), SectionHeaderText = Color(0xFF93A1A1),
            MenuBg = Color(0xFFFDF6E3), MenuBorder = Color(0xFFD3CBB8),
            MenuText = Color(0xFF657B83), CmdSelectedBg = Color(0xFF268BD2),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFFEEE8D5),
        )
        "Eye Care" -> IdeColors(
            BgColor = Color(0xFFF5F0E8), ActivityBarBg = Color(0xFFEDE8DF),
            ActivityBarIcon = Color(0xFF9C8F7A), ActivityBarIconActive = Color(0xFF7A4F3A),
            TabBarBg = Color(0xFFEDE8DF), TabActiveBg = Color(0xFFF5F0E8),
            TabInactiveBg = Color(0xFFEDE8DF), TabActiveIndicator = Color(0xFF7A4F3A),
            TabText = Color(0xFF3C3328), TabTextInactive = Color(0xFF9C8F7A),
            DividerColor = Color(0xFFD5CFC4), StatusBarBg = Color(0xFF7A4F3A),
            PanelBg = Color(0xFFEDE8DF), SectionHeaderText = Color(0xFF9C8F7A),
            MenuBg = Color(0xFFF5F0E8), MenuBorder = Color(0xFFD5CFC4),
            MenuText = Color(0xFF3C3328), CmdSelectedBg = Color(0xFF7A4F3A),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFFEDE8DF),
        )
        "Eye Care" -> IdeColors(
            BgColor = Color(0xFFF5F0E8), ActivityBarBg = Color(0xFFEDE8DF),
            ActivityBarIcon = Color(0xFF9C8F7A), ActivityBarIconActive = Color(0xFF7A4F3A),
            TabBarBg = Color(0xFFEDE8DF), TabActiveBg = Color(0xFFF5F0E8),
            TabInactiveBg = Color(0xFFEDE8DF), TabActiveIndicator = Color(0xFF7A4F3A),
            TabText = Color(0xFF3C3328), TabTextInactive = Color(0xFF9C8F7A),
            DividerColor = Color(0xFFD5CFC4), StatusBarBg = Color(0xFF7A4F3A),
            PanelBg = Color(0xFFEDE8DF), SectionHeaderText = Color(0xFF9C8F7A),
            MenuBg = Color(0xFFF5F0E8), MenuBorder = Color(0xFFD5CFC4),
            MenuText = Color(0xFF3C3328), CmdSelectedBg = Color(0xFF7A4F3A),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFFEDE8DF),
        )
        else -> IdeColors( // Light Default, Light Modern, Quiet Light
            BgColor = Color(0xFFFFFFFF), ActivityBarBg = Color(0xFFFFFFFF),
            ActivityBarIcon = Color(0xFF616161), ActivityBarIconActive = Color(0xFF007ACC),
            TabBarBg = Color(0xFFECECEC), TabActiveBg = Color(0xFFFFFFFF),
            TabInactiveBg = Color(0xFFECECEC), TabActiveIndicator = Color(0xFF007ACC),
            TabText = Color(0xFF333333), TabTextInactive = Color(0xFF717171),
            DividerColor = Color(0xFFE0E0E0), StatusBarBg = Color(0xFF007ACC),
            PanelBg = Color(0xFFF3F3F3), SectionHeaderText = Color(0xFF717171),
            MenuBg = Color(0xFFFFFFFF), MenuBorder = Color(0xFFD4D4D4),
            MenuText = Color(0xFF333333), CmdSelectedBg = Color(0xFF0060C0),
            CmdSelectedText = Color(0xFFFFFFFF), KeyboardToolbarBg = Color(0xFFF0F0F0),
        )
    }
}

private enum class SidePanel { EXPLORER, SEARCH, GIT, RUN, EXTENSIONS, AI_CHAT }

// NotifItem moved to NotificationDrawerOverlay.kt
private enum class BottomTab  { PROBLEMS, OUTPUT, TERMINAL, DEBUG, PORTS, SPLIT, PREVIEW, LOGCAT, VARIABLES, BUILD, TOOLCHAIN, TASKS, HISTORY, ARTIFACTS, DOWNLOADS, BACKUP }

private val SPECIAL_KEYS = listOf(
    "{", "}", "[", "]", "(", ")", "<", ">", "=", "+", "-", "*", "/",
    ":", ";", "'", "\"", "|", "&", "!", "?", "@", "#", "$", "%", "^",
    "~", "\\", ",", ".", "_", "`", "Tab", "Esc",
)

private data class MenuBarItem(val label: String, val items: List<MenuAction>)
private data class MenuAction(val label: String, val shortcut: String = "", val divider: Boolean = false)

private val MENU_BAR = listOf(
    MenuBarItem("File", listOf(
        MenuAction("New File","Ctrl+N"), MenuAction("New Folder"),
        MenuAction("Open File","Ctrl+O"), MenuAction("Open Folder"),
        MenuAction("",divider=true),
        MenuAction("Save","Ctrl+S"), MenuAction("Save As","Ctrl+Shift+S"), MenuAction("Auto Save"),
        MenuAction("",divider=true), MenuAction("Create Snapshot"), MenuAction("Diagnostics Report"), MenuAction("",divider=true), MenuAction("Preferences"), MenuAction("Exit"),
    )),
    MenuBarItem("Edit", listOf(
        MenuAction("Undo","Ctrl+Z"), MenuAction("Redo","Ctrl+Y"),
        MenuAction("",divider=true),
        MenuAction("Cut","Ctrl+X"), MenuAction("Copy","Ctrl+C"), MenuAction("Paste","Ctrl+V"),
        MenuAction("",divider=true),
        MenuAction("Find","Ctrl+F"), MenuAction("Replace","Ctrl+H"), MenuAction("Find in Files","Ctrl+Shift+F"),
    )),
    MenuBarItem("Selection", listOf(
        MenuAction("Select All","Ctrl+A"), MenuAction("Expand Selection"),
        MenuAction("Shrink Selection"), MenuAction("Add Cursor Above"), MenuAction("Add Cursor Below"),
    )),
    MenuBarItem("View", listOf(
        MenuAction("Explorer","Ctrl+Shift+E"), MenuAction("Search","Ctrl+Shift+F"),
        MenuAction("Source Control","Ctrl+Shift+G"), MenuAction("Run & Debug","Ctrl+Shift+D"),
        MenuAction("Extensions","Ctrl+Shift+X"),
        MenuAction("",divider=true),
        MenuAction("Terminal","Ctrl+`"), MenuAction("Problems","Ctrl+Shift+M"),
        MenuAction("Output"), MenuAction("",divider=true),
        MenuAction("Toggle Sidebar","Ctrl+B"),
        MenuAction("Zoom In"), MenuAction("Zoom Out"),
    )),
    MenuBarItem("Go", listOf(
        MenuAction("Go to File","Ctrl+P"), MenuAction("Go to Symbol","Ctrl+Shift+O"),
        MenuAction("Go to Line","Ctrl+G"), MenuAction("Go to Definition","F12"),
    )),
    MenuBarItem("Run", listOf(
        MenuAction("Run Program","F5"), MenuAction("Start Debugging","F5"),
        MenuAction("Stop","Shift+F5"), MenuAction("Restart","Ctrl+Shift+F5"),
        MenuAction("",divider=true), MenuAction("Add Breakpoint","F9"),
    )),
    MenuBarItem("Terminal", listOf(
        MenuAction("New Terminal","Ctrl+`"), MenuAction("Split Terminal"),
        MenuAction("Kill Terminal"), MenuAction("",divider=true), MenuAction("Clear"),
    )),
    MenuBarItem("Help", listOf(
        MenuAction("Documentation"), MenuAction("Keyboard Shortcuts"),
        MenuAction("Release Notes"), MenuAction("",divider=true),
        MenuAction("About Visual Node Code"),
    )),
)

/** P2-10 Navigation history entry. */
private data class NavEntry(val path: String, val line: Int = 0)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ProjectShellScreen(
    projectId: String,
    isDark: Boolean,
    currentTheme: String = if (isDark) "Dark (Default)" else "Light (Default)",
    onSelectTheme: (String) -> Unit = {},
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    tokenStore: SecureTokenStore,
    sessionStateStore: SessionStateStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resolve human-readable project name from local project store
    val projectName = remember(projectId) {
        try {
            val str = context.getSharedPreferences("projects", android.content.Context.MODE_PRIVATE)
                .getString("list", null) ?: return@remember projectId
            val arr = JSONArray(str)
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.getString("id") == projectId }
                ?.getString("name") ?: projectId
        } catch (_: Exception) { projectId }
    }
    val density = LocalDensity.current
    // Rotation fix (#8): key on orientation so raw AlertDialog windows get a fresh,
    // correctly-sized window on rotate.
    val orientation = LocalConfiguration.current.orientation
    val t = ideColors(currentTheme)
    val BgColor = t.BgColor
    val ActivityBarBg = t.ActivityBarBg
    val ActivityBarIcon = t.ActivityBarIcon
    val ActivityBarIconActive = t.ActivityBarIconActive
    val TabBarBg = t.TabBarBg
    val TabActiveBg = t.TabActiveBg
    val TabInactiveBg = t.TabInactiveBg
    val TabActiveIndicator = t.TabActiveIndicator
    val TabText = t.TabText
    val TabTextInactive = t.TabTextInactive
    val DividerColor = t.DividerColor
    val StatusBarBg = t.StatusBarBg
    val PanelBg = t.PanelBg
    val SectionHeaderText = t.SectionHeaderText
    val MenuBg = t.MenuBg
    val MenuBorder = t.MenuBorder
    val MenuText = t.MenuText
    val CmdSelectedBg = t.CmdSelectedBg
    val CmdSelectedText = t.CmdSelectedText
    val KeyboardToolbarBg = t.KeyboardToolbarBg
    val restoredState = remember(projectId) { sessionStateStore.loadShellState(projectId) }
    val prefs = remember { context.getSharedPreferences("app_prefs", 0) }
    var activePanel        by remember(projectId, restoredState) { mutableStateOf<SidePanel?>(restoredState?.activePanel?.let { SidePanel.valueOf(it) }) }
    val showBottomPanelMs = remember(projectId, restoredState) { mutableStateOf(restoredState?.showBottomPanel ?: true) }; var showBottomPanel by showBottomPanelMs
    val showSplitTerminalMs = remember { mutableStateOf(false) }; var showSplitTerminal by showSplitTerminalMs
    val splitTerminalWidthMs = remember { mutableFloatStateOf(300f) }; var splitTerminalWidth by splitTerminalWidthMs
    // Shared terminal state — both TerminalPane and SplitTerminalPanel share this.
    // FIX #12 (2026-07-08): this was previously unkeyed, so Compose handed back the SAME
    // TerminalState (same tabs, same live TerminalSession/PTY) no matter which project was
    // open — every other piece of restorable state in this file (activePanel, showBottomPanel,
    // activeBottomTab below) was correctly keyed with `remember(projectId, ...)`, this one line
    // was the sole miss. key(projectId) forces a fresh TerminalState per project, matching the
    // now project-tagged session tracking in TerminalService. See AGENTS.md #12 for the writeup.
    val sharedTerminalState = androidx.compose.runtime.key(projectId) { rememberTerminalState(context) }
    // Lifted up here (not inside PreviewPane) so switching to Terminal/Problems/etc. and back
    // to Preview doesn't reset the active sub-tab or the connected Browser/Remotion URL.
    val sharedPreviewState = com.codespace.ide.ui.panes.rememberPreviewState()

    val activeBottomTabMs = remember(projectId, restoredState) { mutableStateOf(restoredState?.bottomTab?.let { BottomTab.valueOf(it) } ?: BottomTab.TERMINAL) }; var activeBottomTab by activeBottomTabMs
    var totalWidth         by remember { mutableFloatStateOf(1080f) }
    // P15-H: two-column layout on wide landscape screens (tablets / foldables)
    // NOTE: totalWidth starts at 1080f and gets updated via onGloballyPositioned.
    // isWideLayout is derived state so it recomputes when totalWidth changes.
    val isWideLayout by remember { derivedStateOf {
        orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && totalWidth > 1400f
    } }
    var totalHeight        by remember { mutableFloatStateOf(1920f) }
    var sidePanelWidth     by remember { mutableFloatStateOf(280f) }
    val showChatPanelMs = remember { mutableStateOf(false) }; var showChatPanel by showChatPanelMs
    val aiPanelWidthMs = remember { mutableFloatStateOf(300f) }; var aiPanelWidth by aiPanelWidthMs
    val bottomPanelHeightMs = remember { mutableFloatStateOf(300f) }; var bottomPanelHeight by bottomPanelHeightMs
    val bottomPanelPrevHeightMs = remember { mutableFloatStateOf(300f) }; var bottomPanelPrevHeight by bottomPanelPrevHeightMs
    val bottomPanelMaximizedMs = remember { mutableStateOf(false) }; var bottomPanelMaximized by bottomPanelMaximizedMs
    val showSymbolSearchMs = remember { mutableStateOf(false) }; var showSymbolSearch by showSymbolSearchMs
    // P15-E: File search overlay (Ctrl+P / 🔍 icon)
    val showFileSearchMs = remember { mutableStateOf(false) }; var showFileSearch by showFileSearchMs
    // P15-G: delay heavy panels 8s after launch to not block editor warmup
    var heavyPanesReady    by remember { mutableStateOf(false) }
    val indexerScope = rememberCoroutineScope()
    val isDraggingBottomPanelMs = remember { mutableStateOf(false) }; var isDraggingBottomPanel by isDraggingBottomPanelMs
    var openMenuBar        by remember { mutableStateOf<String?>(null) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var appWakeLockOn by remember { mutableStateOf(false) }
    var showColorTheme     by remember { mutableStateOf(false) }
    val showFindBarMs = remember { mutableStateOf(false) }; var showFindBar by showFindBarMs
    val wordWrapMs = remember { mutableStateOf(false) }; var wordWrap by wordWrapMs
    val showInlayHintsMs = remember { mutableStateOf(true) }; var showInlayHints by showInlayHintsMs  // P2-11
    val showGoToLineMs = remember { mutableStateOf(false) }; var showGoToLine by showGoToLineMs
    var goToLineInput      by remember { mutableStateOf("") }
    val scrollTargetLineMs = remember { mutableStateOf(0) }; var scrollTargetLine by scrollTargetLineMs
    val findQueryMs = remember { mutableStateOf("") }; var findQuery by findQueryMs
    val replaceQueryMs = remember { mutableStateOf("") }; var replaceQuery by replaceQueryMs
    val showReplaceRowMs = remember { mutableStateOf(false) }; var showReplaceRow by showReplaceRowMs
    var showMoreMenu       by remember { mutableStateOf(false) }
    var showPersonMenu     by remember { mutableStateOf(false) }
    var chatInput          by remember { mutableStateOf("") }
    val terminalCommandToRunMs = remember { mutableStateOf<String?>(null) }; var terminalCommandToRun by terminalCommandToRunMs
    val previewPortMs = remember { mutableStateOf<Int?>(null) }; var previewPort by previewPortMs
    var showGearMenu       by remember { mutableStateOf(false) }
    var showRunMenu        by remember { mutableStateOf(false) }
    var showPanelMenu      by remember { mutableStateOf(false) }
    var showExplorerMore   by remember { mutableStateOf(false) }
    var commandQuery       by remember { mutableStateOf("") }
    var commandTab         by remember { mutableStateOf("Commands") }
    var notificationMsg    by remember { mutableStateOf<String?>(null) }
    var notificationType   by remember { mutableStateOf("info") }
    // Persistent notification list (bell drawer)
    val notifList = remember { mutableStateListOf<NotifItem>() }
    var notifUnread by remember { mutableStateOf(0) }
    var showNotifDrawer    by remember { mutableStateOf(false) }
    var snapshotMessage    by remember { mutableStateOf<String?>(null) }
    // Connectors hub (replaces Person menu)
    var showConnectorsSheet by remember { mutableStateOf(false) }
    val terminalEnhancements = remember { TerminalEnhancementManager(context) }
    var terminalTheme by remember { mutableStateOf(terminalEnhancements.currentTheme()) }
    var showTerminalThemePicker by remember { mutableStateOf(false) }
    val debugInput = remember { mutableStateOf("") }
    val debugMessages = remember { mutableStateListOf("Debugger ready. Press Run to start.") }
    val cursorLineMs = remember { mutableStateOf(1) }; var cursorLine by cursorLineMs
    val cursorColMs = remember { mutableStateOf(1) }; var cursorCol by cursorColMs
    // Reset scroll target after use so the same line can be re-triggered
    LaunchedEffect(scrollTargetLine) {
        if (scrollTargetLine > 0) {
            kotlinx.coroutines.delay(500)
            scrollTargetLine = 0
        }
    }
    // P15-G: heavy panels (Logcat, Variables, BuildHistory) ready after 8s startup headstart
    LaunchedEffect(projectId) {
        kotlinx.coroutines.delay(8_000L)
        heavyPanesReady = true
    }
    val editorFontSizeMs = remember(projectId, restoredState) { mutableStateOf(restoredState?.editorFontSize ?: 13) }; var editorFontSize by editorFontSizeMs
    val editorTabs         = remember(projectId) { mutableStateListOf<String>() }
    val activeEditorTabMs = remember(projectId, restoredState) { mutableStateOf(restoredState?.activeFilePath) }; var activeEditorTab by activeEditorTabMs
    val keyboardInsertMs = remember { mutableStateOf<((String) -> Unit)?>(null) }; var keyboardInsert by keyboardInsertMs
    /** Breadcrumb: when set, ExplorerSidePanel auto-expands and scrolls to this dir. */
    val breadcrumbNavDirMs = remember { mutableStateOf<String?>(null) }; var breadcrumbNavDir by breadcrumbNavDirMs

    // P2-10 Jump back/forward navigation history
    val navBackStack  = remember { mutableStateListOf<NavEntry>() }
    val navFwdStack   = remember { mutableStateListOf<NavEntry>() }

    LaunchedEffect(projectId, restoredState) {
        if (editorTabs.isEmpty() && restoredState?.openFilePaths?.isNotEmpty() == true) {
            restoredState.openFilePaths.forEach { path ->
                if (path.isNotBlank() && !editorTabs.contains(path)) {
                    editorTabs.add(path)
                }
            }
            if (activeEditorTab == null) {
                activeEditorTab = restoredState.activeFilePath ?: restoredState.openFilePaths.firstOrNull()
            }
        }
    }

    // P9-1: Start background file indexer when project opens
    LaunchedEffect(projectId) {
        if (projectId.isNotBlank()) {
            val wsPath = java.io.File(context.filesDir, "projects/$projectId").absolutePath
            if (wsPath != null) {
                FileIndexer.startIndexing(wsPath, indexerScope)
            }
        }
    }

    LaunchedEffect(projectId, activePanel, activeBottomTab, showBottomPanel, activeEditorTab, editorFontSize) {
        val state = SessionStateStore.ShellState(
            projectId = projectId,
            activePanel = activePanel?.name,
            bottomTab = activeBottomTab.name,
            showBottomPanel = showBottomPanel,
            activeFilePath = activeEditorTab,
            openFilePaths = editorTabs.toList(),
            editorFontSize = editorFontSize,
        )
        sessionStateStore.saveShellState(projectId, state)
    }

    LaunchedEffect(notificationMsg) {
        if (notificationMsg != null) { kotlinx.coroutines.delay(3000); notificationMsg = null }
    }

    fun showNotification(msg: String, type: String = "info") {
        notificationMsg = msg
        notificationType = type
        // Also push to persistent notification list
        notifList.add(0, NotifItem(System.currentTimeMillis(), msg, type))
        if (notifList.size > 50) notifList.removeAt(notifList.size - 1)
        if (!showNotifDrawer) notifUnread++
    }

        // ── P2-10: Navigation history helpers ──────────────────────────────────
    /** Push the current position onto the back-stack and clear the forward-stack. */
    fun pushNavEntry(path: String?, line: Int) {
        val p = path ?: return
        val entry = NavEntry(p, line)
        // Ignore duplicate consecutive entries
        if (navBackStack.lastOrNull() == entry) return
        navBackStack.add(entry)
        if (navBackStack.size > 100) navBackStack.removeAt(0)
        navFwdStack.clear()
    }

    /** Jump backwards one step, pushing the current position onto the forward-stack. */
    fun navBack() {
        val prev = navBackStack.removeLastOrNull() ?: return
        val current = activeEditorTab?.let { NavEntry(it, scrollTargetLine) }
        if (current != null) navFwdStack.add(current)
        activeEditorTab = prev.path
        scrollTargetLine = prev.line
        if (!editorTabs.contains(prev.path)) editorTabs.add(prev.path)
    }

    /** Jump forwards one step, pushing the current position onto the back-stack. */
    fun navForward() {
        val next = navFwdStack.removeLastOrNull() ?: return
        val current = activeEditorTab?.let { NavEntry(it, scrollTargetLine) }
        if (current != null) navBackStack.add(current)
        activeEditorTab = next.path
        scrollTargetLine = next.line
        if (!editorTabs.contains(next.path)) editorTabs.add(next.path)
    }

    fun handleMenuAction(action: String) {
        openMenuBar = null
        when (action) {
            "Explorer"           -> activePanel = SidePanel.EXPLORER
            "Search"             -> activePanel = SidePanel.SEARCH
            "Source Control"     -> activePanel = SidePanel.GIT
            "Run & Debug"        -> activePanel = SidePanel.RUN
            "Extensions"         -> activePanel = SidePanel.EXTENSIONS
            "Toggle Sidebar"     -> activePanel = if (activePanel == null) SidePanel.EXPLORER else null
            "Terminal"           -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
            "Preview"            -> { showBottomPanel = true; activeBottomTab = BottomTab.PREVIEW }
            "Split Terminal"     -> { showBottomPanel = true; activeBottomTab = BottomTab.SPLIT }
            "Go to Symbol"        -> { showSymbolSearch = true }
                        "Problems"           -> { showBottomPanel = true; activeBottomTab = BottomTab.PROBLEMS }
            "Output"             -> { showBottomPanel = true; activeBottomTab = BottomTab.OUTPUT }
            "New Terminal"       -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
            "Find"               -> { showFindBar = true; showReplaceRow = false }
            "Replace"            -> { showFindBar = true; showReplaceRow = true }
            "Go to Line"         -> { showGoToLine = true }
            "Explorer"           -> activePanel = SidePanel.EXPLORER
            "Search"             -> activePanel = SidePanel.SEARCH
            "Source Control"     -> activePanel = SidePanel.GIT
            "Run & Debug"        -> activePanel = SidePanel.RUN
            "Extensions"         -> activePanel = SidePanel.EXTENSIONS
            "Terminal"           -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
            "Problems"           -> { showBottomPanel = true; activeBottomTab = BottomTab.PROBLEMS }
            "Output"             -> { showBottomPanel = true; activeBottomTab = BottomTab.OUTPUT }
            "Toggle Sidebar"     -> { activePanel = if (activePanel == null) SidePanel.EXPLORER else null }
            "Zoom In"            -> { editorFontSize = (editorFontSize + 2).coerceAtMost(32) }
            "Zoom Out"           -> { editorFontSize = (editorFontSize - 2).coerceAtLeast(8) }
            "New Terminal"       -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
            "Split Terminal"     -> { showSplitTerminal = true }
            "Kill Terminal"      -> { showSplitTerminal = false }
            "Clear"              -> { sharedTerminalState.active?.session?.write("clear\n") }
            "Auto Save"          -> { showNotification("Auto Save toggled", "info") }
            "Save"               -> { showNotification("File saved", "success") }
            "About Visual Node Code" -> { showNotification("CodeSpace IDE v1.0.0 — VS Code for Android", "info") }
            "Documentation"      -> { showNotification("Opening docs...", "info") }
            "Keyboard Shortcuts" -> { showCommandPalette = true }
            "Preferences"        -> { showColorTheme = true }
            "Color Theme"        -> { showColorTheme = true }
            "Replace"            -> { showFindBar = true; showReplaceRow = true }
            "Find in Files"      -> { showFileSearch = true }
            "Go to File"         -> showCommandPalette = true
            "Change Color Theme" -> showColorTheme = true
            "Zoom In"            -> editorFontSize = (editorFontSize + 1).coerceAtMost(24)
            "Zoom Out"           -> editorFontSize = (editorFontSize - 1).coerceAtLeast(8)
            "Exit"               -> onBack()
            "About Visual Node Code"-> showNotification("Visual Node Code — VS Code for mobile", "info")
            "Create Snapshot" -> {
                scope.launch {
                    try {
                        val projectDir = java.io.File(context.filesDir, "projects/$projectId")
                        val outFile = WorkspaceManager.createSnapshot(context, projectDir)
                        snapshotMessage = "Saved to Downloads/CodespaceIDE/${outFile.name}"
                        showNotification("Snapshot created!", "success")
                    } catch (e: Exception) {
                        showNotification("Snapshot failed: ${e.message}", "error")
                    }
                }
            }
            "Diagnostics Report" -> {
                scope.launch {
                    try {
                        val (_, intent) = WorkspaceManager.generateDiagnosticsReport(context)
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Diagnostics"))
                    } catch (e: Exception) {
                        showNotification("Diagnostics failed: ${e.message}", "error")
                    }
                }
            }
            "Run Program", "Start Debugging" -> {
                showBottomPanel = true; activeBottomTab = BottomTab.DEBUG
                debugMessages.add("[debug] Launching session...")
                showNotification("Starting debug session…", "info")
            }
            "Terminal Theme" -> { showTerminalThemePicker = true }
            "Setup Shell Profile" -> {
                terminalEnhancements.ensureProfile()
                showNotification("Shell profile installed", "success")
            }
            "Setup Offline Shell" -> {
                showNotification("Setting up offline shell...", "info")
                scope.launch { withContext(Dispatchers.IO) { BusyboxInstaller.ensureOfflineShell(context) }; showNotification("Offline shell ready", "success") }
            }
            "Install Offline Essentials" -> {
                showNotification("Installing...", "info")
                scope.launch { withContext(Dispatchers.IO) { BusyboxInstaller.installIfNeeded(context) }; showNotification("Offline essentials staged", "success") }
            }
            "Backup Shell Profile" -> {
                terminalEnhancements.backupProfile()
                showNotification("Shell profile backed up", "success")
            }
            "Restore Shell Profile" -> {
                terminalEnhancements.restoreProfile()
                showNotification("Shell profile restored", "success")
            }
            "Save" -> showNotification("File saved ✓", "success")
            else   -> {}
        }
    }

    // FIXED 2026-07-03: the hardware/gesture back action had NO handler at all here --
    // only specific in-app UI elements (the "Exit" menu item, a specific icon) called
    // onBack() directly. The system back button/gesture did nothing, which is exactly
    // "the back button to return to the home screen doesn't work." Wire a real BackHandler:
    // close whichever overlay/menu/dialog is topmost first (natural back-stack feel, so
    // back doesn't jump straight to the home screen while a menu is open), otherwise call
    // onBack() to actually leave to the home screen.
    BackHandler {
        when {
            showCommandPalette      -> showCommandPalette = false
            showConnectorsSheet     -> showConnectorsSheet = false
            showNotifDrawer         -> showNotifDrawer = false
            showTerminalThemePicker -> showTerminalThemePicker = false
            showPanelMenu           -> showPanelMenu = false
            showExplorerMore        -> showExplorerMore = false
            showMoreMenu            -> showMoreMenu = false
            showPersonMenu          -> showPersonMenu = false
            showGearMenu            -> showGearMenu = false
            showRunMenu             -> showRunMenu = false
            showColorTheme          -> showColorTheme = false
            showChatPanel           -> showChatPanel = false
            showReplaceRow          -> showReplaceRow = false
            showFindBar             -> showFindBar = false
            openMenuBar != null     -> openMenuBar = null
            else                    -> onBack()
        }
    }

    Box(
        Modifier.fillMaxSize().background(BgColor)
            .then(if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                Modifier.statusBarsPadding() else Modifier) // shield: portrait only — landscape is fine edge-to-edge
            .onGloballyPositioned { totalWidth = it.size.width.toFloat(); totalHeight = it.size.height.toFloat() }
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top Bar (VS Code style)
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFF8F8F8))
                    .border(1.dp, DividerColor, RoundedCornerShape(0.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: back button — proper ArrowBack + 44dp touch target
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(44.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TabTextInactive,
                        modifier = Modifier.size(22.dp))
                }
                // Center: Workspace title (clickable opens command palette)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .background(Color(0xFFECECEC), RoundedCornerShape(4.dp))
                            .clickable { showCommandPalette = true }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Search, null, tint = TabTextInactive, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(projectName, fontSize = 13.sp, color = TabTextInactive, maxLines = 1)
                    }
                }
                // Right: action icons
                Icon(Icons.Default.Computer, null, tint = TabTextInactive,
                    modifier = Modifier.size(20.dp).clickable { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp).clickable { handleMenuAction("Run Program") })
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Build, null, tint = Color(0xFF007ACC),
                    modifier = Modifier.size(20.dp).clickable {
                        showBottomPanel = true; activeBottomTab = BottomTab.BUILD
                    })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.VerticalSplit, null, tint = Color(0xFF007ACC),
                    modifier = Modifier.size(20.dp).clickable { showBottomPanel = true; activeBottomTab = BottomTab.SPLIT })
                Spacer(Modifier.width(8.dp))

                // Copilot Chat toggle — animated bot icon, primary way to open the chat panel
                AnimatedBotIcon(
                    modifier = Modifier.size(20.dp).clickable { showChatPanel = !showChatPanel },
                )
                Spacer(Modifier.width(8.dp))

                // Notification bell with unread badge
                Box(Modifier.size(28.dp).clickable {
                    showNotifDrawer = !showNotifDrawer
                    if (showNotifDrawer) notifUnread = 0
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Notifications, null,
                        tint = if (notifUnread > 0) Color(0xFFF44336) else TabTextInactive,
                        modifier = Modifier.size(20.dp))
                    if (notifUnread > 0) {
                        Box(Modifier.align(Alignment.TopEnd).size(14.dp)
                            .background(Color(0xFFF44336), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(if (notifUnread > 9) "9+" else notifUnread.toString(),
                                color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            // ── Menu bar — VS Code style File/Edit/View/etc dropdowns
            Row(
                Modifier.fillMaxWidth().height(26.dp).background(BgColor),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MENU_BAR.forEach { menuItem ->
                    Box {
                        val isOpen = openMenuBar == menuItem.label
                        Text(
                            menuItem.label,
                            fontSize = 12.sp,
                            color = if (isOpen) MenuText else MenuText.copy(alpha = 0.85f),
                            modifier = Modifier
                                .background(if (isOpen) MenuBg else Color.Transparent)
                                .clickable { openMenuBar = if (isOpen) null else menuItem.label }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = isOpen,
                            onDismissRequest = { openMenuBar = null },
                        ) {
                            menuItem.items.forEach { action ->
                                if (action.divider) {
                                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 2.dp))
                                } else {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(action.label, fontSize = 12.sp, color = MenuText)
                                                if (action.shortcut.isNotEmpty()) {
                                                    Text(action.shortcut, fontSize = 10.sp, color = MenuText.copy(alpha = 0.5f))
                                                }
                                            }
                                        },
                                        onClick = {
                                            handleMenuAction(action.label)
                                            openMenuBar = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(currentTheme, fontSize = 10.sp, color = MenuText.copy(alpha = 0.5f), modifier = Modifier.padding(end = 8.dp))
            }

            // ── Main body
            Row(Modifier.weight(1f).fillMaxWidth()) {

                // Activity Bar — extracted to PssActivityBar (DEX register reduction)
                PssActivityBar(
                    projectId = projectId,
                    activeEditorTab = activeEditorTab,
                    activePanel = activePanel,
                    onActivePanelChange = { activePanel = it },
                    onShowPersonMenu = { showPersonMenu = true },
                    onShowGearMenu = { showGearMenu = true },
                    activityBarBg = ActivityBarBg,
                    activityBarIcon = ActivityBarIcon,
                    activityBarIconActive = ActivityBarIconActive,
                    dividerColor = DividerColor,
                )

                // Side Panel
                if (activePanel != null) {
                    val spWidth = with(density) { sidePanelWidth.toDp() }.coerceIn(150.dp, 500.dp)
                    Column(Modifier.width(spWidth).fillMaxHeight().background(BgColor)) {
                        when (activePanel) {
                            SidePanel.EXPLORER -> ExplorerSidePanel(
                                projectId = projectId,
                                onOpenFile = { path ->
                                    if (!editorTabs.contains(path)) editorTabs.add(path)
                                    pushNavEntry(activeEditorTab, scrollTargetLine)
                                    activeEditorTab = path
                                    activePanel = null
                                    showNotification("Opened ${path.substringAfterLast("/")}", "success")
                                },
                                onFileRenamed = { oldPath, newPath ->
                                    val idx = editorTabs.indexOf(oldPath)
                                    if (idx >= 0) {
                                        editorTabs[idx] = newPath
                                        if (activeEditorTab == oldPath) activeEditorTab = newPath
                                    }
                                },
                                onOpenFileAtLine = { path, line ->
                                    if (!editorTabs.contains(path)) editorTabs.add(path)
                                    pushNavEntry(activeEditorTab, scrollTargetLine)
                                    activeEditorTab = path
                                    scrollTargetLine = line
                                    activePanel = null
                                },
                                onMoreMenu = { showExplorerMore = true },
                                onOpenInTerminal = { path ->
                                    showBottomPanel = true
                                    activeBottomTab = BottomTab.TERMINAL
                                    terminalCommandToRun = "cd \"$path\"\r"
                                    showNotification("Opened terminal at workspace path", "success")
                                },
                                openTabs = editorTabs.toList(),
                                activeFilePath = activeEditorTab,
                                onCloseTab = { tabPath ->
                                    editorTabs.remove(tabPath)
                                    if (activeEditorTab == tabPath) activeEditorTab = editorTabs.lastOrNull()
                                },
                                tokenStore = tokenStore,
                            
                                navigateToDir = breadcrumbNavDir,
                            )
                            SidePanel.SEARCH     -> SearchPanel(
                                projectId = projectId,
                                onOpenFileAtLine = { path, line ->
                                    if (!editorTabs.contains(path)) editorTabs.add(path)
                                    pushNavEntry(activeEditorTab, scrollTargetLine)
                                    activeEditorTab = path
                                    scrollTargetLine = line
                                    activePanel = null
                                    showNotification("Opened " + path.substringAfterLast("/") + ":" + line, "success")
                                },
                            )
                            SidePanel.GIT        -> GitSidePanel(projectId)
                            SidePanel.RUN        -> RunDebugPanel(onMoreMenu = { showRunMenu = true })
                            SidePanel.EXTENSIONS -> {
                                    ExtensionsPanel()
                                    androidx.compose.material3.HorizontalDivider(color = Color(0xFF2D2D2D), thickness = 1.dp)
                                    McpPanel()
                                }
                            else                 -> {}
                        }
                    }
                    Box(
                        Modifier.width(4.dp).fillMaxHeight().background(DividerColor)
                            .pointerInput(Unit) {
                                detectDragGestures { _, dragAmount ->
                                    val nw = sidePanelWidth + dragAmount.x
                                    if (nw < 80f) activePanel = null else sidePanelWidth = nw.coerceIn(80f, totalWidth * 0.7f)
                                }
                            }
                    )
                }

                // Editor Column + Split Terminal + Chat Panel
                // Extracted to PssEditorColumn to fix VerifyError (DEX register v293)
                PssEditorColumn(
                    modifier = Modifier.weight(1f),
                    projectId = projectId,
                    context = context,
                    tokenStore = tokenStore,
                    editorTabs = editorTabs,
                    heavyPanesReady = heavyPanesReady,
                    wordWrapMs = wordWrapMs,
                    showInlayHintsMs = showInlayHintsMs,
                    showGoToLineMs = showGoToLineMs,
                    sessionStateStore = sessionStateStore,
                    keyboardToolbarBg = KeyboardToolbarBg,
                    navBackStack = navBackStack,
                    navFwdStack = navFwdStack,
                    sharedTerminalState = sharedTerminalState,
                    sharedPreviewState = sharedPreviewState,
                    debugMessages = debugMessages,
                    debugInput = debugInput,
                    totalWidth = totalWidth,
                    totalHeight = totalHeight,
                    tabBarBg = TabBarBg,
                    tabActiveBg = TabActiveBg,
                    tabInactiveBg = TabInactiveBg,
                    tabActiveIndicator = TabActiveIndicator,
                    tabText = TabText,
                    tabTextInactive = TabTextInactive,
                    dividerColor = DividerColor,
                    panelBg = PanelBg,
                    bgColor = BgColor,
                    onHandleMenuAction = { handleMenuAction(it) },
                    onShowNotification = { msg, type -> showNotification(msg, type) },
                    onPushNavEntry = { path, line -> pushNavEntry(path, line) },
                    onNavBack = { navBack() },
                    onNavForward = { navForward() },
                    activeBottomTabMs = activeBottomTabMs,
                    activeEditorTabMs = activeEditorTabMs,
                    aiPanelWidthMs = aiPanelWidthMs,
                    bottomPanelHeightMs = bottomPanelHeightMs,
                    bottomPanelMaximizedMs = bottomPanelMaximizedMs,
                    bottomPanelPrevHeightMs = bottomPanelPrevHeightMs,
                    breadcrumbNavDirMs = breadcrumbNavDirMs,
                    cursorColMs = cursorColMs,
                    cursorLineMs = cursorLineMs,
                    editorFontSizeMs = editorFontSizeMs,
                    findQueryMs = findQueryMs,
                    isDraggingBottomPanelMs = isDraggingBottomPanelMs,
                    keyboardInsertMs = keyboardInsertMs,
                    previewPortMs = previewPortMs,
                    replaceQueryMs = replaceQueryMs,
                    scrollTargetLineMs = scrollTargetLineMs,
                    showBottomPanelMs = showBottomPanelMs,
                    showChatPanelMs = showChatPanelMs,
                    showFileSearchMs = showFileSearchMs,
                    showFindBarMs = showFindBarMs,
                    showReplaceRowMs = showReplaceRowMs,
                    showSplitTerminalMs = showSplitTerminalMs,
                    showSymbolSearchMs = showSymbolSearchMs,
                    splitTerminalWidthMs = splitTerminalWidthMs,
                    terminalCommandToRunMs = terminalCommandToRunMs,
                )
            } // end main Row (editor + optional chat panel)

        // Simple overlay menus

        // ── Connectors Hub Sheet ─────────────────────────────────────────
        if (showConnectorsSheet) {
            ConnectorsHubSheet(onDismiss = { showConnectorsSheet = false })
        }


        if (showPanelMenu) { Box(Modifier.fillMaxSize().clickable { showPanelMenu = false }) { Card(Modifier.align(Alignment.BottomEnd).padding(bottom = 90.dp, end = 8.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { val items = when (activeBottomTab) { BottomTab.TERMINAL -> listOf("New Terminal","Split Terminal","Kill Terminal","Clear"); BottomTab.OUTPUT -> listOf("Clear Output","Copy All"); BottomTab.PROBLEMS -> listOf("Filter","Show Errors Only"); BottomTab.DEBUG -> listOf("Clear Console","Copy All"); BottomTab.PORTS -> listOf("Forward Port","Stop Forwarding"); BottomTab.SPLIT -> listOf("New Terminal","Pin Split","Swap Panels","Kill Split"); BottomTab.PREVIEW -> listOf("Refresh Preview","Open in Browser","HTML Mode","Markdown Mode"); BottomTab.LOGCAT -> listOf("Clear Log","Pause","Resume","Filter"); BottomTab.VARIABLES -> listOf("Add Watch","Clear All","Copy All"); BottomTab.BUILD -> listOf("Build","Clean","Check Environment","Cancel Build"); BottomTab.TOOLCHAIN -> listOf("Scan Tools","Refresh"); BottomTab.TASKS -> listOf("Run Task","Cancel Task","Clear Log"); BottomTab.HISTORY -> listOf("Clear History","Export Log"); BottomTab.ARTIFACTS -> listOf("Refresh","Open Folder","Delete All"); BottomTab.DOWNLOADS -> listOf("Clear Completed","Retry Failed"); BottomTab.BACKUP -> listOf("Backup Now","Restore") }; items.forEach { item -> Row(Modifier.fillMaxWidth().clickable { when (item) { "New Terminal" -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL } }; showPanelMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showExplorerMore) { Box(Modifier.fillMaxSize().clickable { showExplorerMore = false }) { Card(Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 48.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("New File","New Folder","Refresh","Collapse All","Open in Terminal").forEach { item -> Row(Modifier.fillMaxWidth().clickable { showExplorerMore = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }


    // ── First-launch onboarding walkthrough ─────────────────────────────
            // P9-1: Symbol Search overlay
            if (showSymbolSearch) {
                SymbolSearchOverlay(
                    activeEditorTab = activeEditorTab,
                    onNavigate = { filePath ->
                        activeEditorTab = filePath
                        showBottomPanel = false
                        showSymbolSearch = false
                    },
                    onDismiss = { showSymbolSearch = false },
                )
            }
    // P15-E: File search overlay (shown over full screen)
    if (showFileSearch) {
        ProjectFileSearchPanel(
            projectRoot = java.io.File(context.filesDir, "projects/$projectId").absolutePath,
            onOpenFile = { path ->
                if (!editorTabs.contains(path)) editorTabs.add(path)
                activeEditorTab = path
                showFileSearch = false
            },
            onOpenFileAtLine = { path, _ ->
                if (!editorTabs.contains(path)) editorTabs.add(path)
                activeEditorTab = path
                showFileSearch = false
            },
            onDismiss = { showFileSearch = false },
        )
    }


            // ── VS Code status bar (blue bar at bottom) ──
            StatusBarContent(
                statusBarBg = StatusBarBg,
                activeEditorTab = activeEditorTab,
                cursorLine = cursorLine,
                cursorCol = cursorCol,
            )
    } // end Editor Column


        PssOverlays(
            activePanel = activePanel,
            onActivePanelChange = { activePanel = it },
            showBottomPanel = showBottomPanel,
            onShowBottomPanelChange = { showBottomPanel = it },
            showChatPanel = showChatPanel,
            onShowChatPanelChange = { showChatPanel = it },
            showCommandPalette = showCommandPalette,
            onShowCommandPaletteChange = { showCommandPalette = it },
            appWakeLockOn = appWakeLockOn,
            onAppWakeLockOnChange = { appWakeLockOn = it },
            showColorTheme = showColorTheme,
            onShowColorThemeChange = { showColorTheme = it },
            showGoToLine = showGoToLine,
            onShowGoToLineChange = { showGoToLine = it },
            goToLineInput = goToLineInput,
            onGoToLineInputChange = { goToLineInput = it },
            scrollTargetLine = scrollTargetLine,
            onScrollTargetLineChange = { scrollTargetLine = it },
            showPersonMenu = showPersonMenu,
            onShowPersonMenuChange = { showPersonMenu = it },
            showGearMenu = showGearMenu,
            onShowGearMenuChange = { showGearMenu = it },
            commandQuery = commandQuery,
            onCommandQueryChange = { commandQuery = it },
            showNotifDrawer = showNotifDrawer,
            onShowNotifDrawerChange = { showNotifDrawer = it },
            snapshotMessage = snapshotMessage,
            onSnapshotMessageChange = { snapshotMessage = it },
            editorFontSize = editorFontSize,
            onEditorFontSizeChange = { editorFontSize = it },
            notifList = notifList,
            BgColor = BgColor,
            ActivityBarIconActive = ActivityBarIconActive,
            TabActiveIndicator = TabActiveIndicator,
            TabTextInactive = TabTextInactive,
            DividerColor = DividerColor,
            SectionHeaderText = SectionHeaderText,
            MenuBg = MenuBg,
            MenuText = MenuText,
            CmdSelectedBg = CmdSelectedBg,
            CmdSelectedText = CmdSelectedText,
            currentTheme = currentTheme,
            onSelectTheme = onSelectTheme,
            onSignOut = onSignOut,
            onOpenSettings = onOpenSettings,
            context = context,
            orientation = orientation,
            handleMenuAction = { handleMenuAction(it) },
            showNotification = { msg, type -> showNotification(msg, type) },
        )
    } // end root Box
}

/**
 * Maps a file path to the shell command that actually runs it inside the
 * Ubuntu proot environment, mirroring VS Code's "Run" (▷) behavior for a
 * given language. Returns null for file types with no direct runner (the
 * user still has the Terminal for anything custom).
 */
/**
 * Dialogs, sheets, and overlay panels for ProjectShellScreen.
 * Extracted to reduce DEX register count in the main function.
 */
@Composable
private fun PssOverlays(
    // State
    activePanel: SidePanel?,
    onActivePanelChange: (SidePanel?) -> Unit,
    showBottomPanel: Boolean,
    onShowBottomPanelChange: (Boolean) -> Unit,
    showChatPanel: Boolean,
    onShowChatPanelChange: (Boolean) -> Unit,
    showCommandPalette: Boolean,
    onShowCommandPaletteChange: (Boolean) -> Unit,
    appWakeLockOn: Boolean,
    onAppWakeLockOnChange: (Boolean) -> Unit,
    showColorTheme: Boolean,
    onShowColorThemeChange: (Boolean) -> Unit,
    showGoToLine: Boolean,
    onShowGoToLineChange: (Boolean) -> Unit,
    goToLineInput: String,
    onGoToLineInputChange: (String) -> Unit,
    scrollTargetLine: Int,
    onScrollTargetLineChange: (Int) -> Unit,
    showPersonMenu: Boolean,
    onShowPersonMenuChange: (Boolean) -> Unit,
    showGearMenu: Boolean,
    onShowGearMenuChange: (Boolean) -> Unit,
    commandQuery: String,
    onCommandQueryChange: (String) -> Unit,
    showNotifDrawer: Boolean,
    onShowNotifDrawerChange: (Boolean) -> Unit,
    snapshotMessage: String?,
    onSnapshotMessageChange: (String?) -> Unit,
    editorFontSize: Int,
    onEditorFontSizeChange: (Int) -> Unit,
    notifList: androidx.compose.runtime.snapshots.SnapshotStateList<NotifItem>,
    // Colors
    BgColor: Color,
    ActivityBarIconActive: Color,
    TabActiveIndicator: Color,
    TabTextInactive: Color,
    DividerColor: Color,
    SectionHeaderText: Color,
    MenuBg: Color,
    MenuText: Color,
    CmdSelectedBg: Color,
    CmdSelectedText: Color,
    // Params
    currentTheme: String,
    onSelectTheme: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
    context: android.content.Context,
    orientation: Int,
    handleMenuAction: (String) -> Unit,
    showNotification: (String, String) -> Unit,
) {
        // Notification Drawer — scrim already in NotificationDrawerOverlay
        if (showNotifDrawer) {
            NotificationDrawerOverlay(
                notifList = notifList,
                onDismiss = { onShowNotifDrawerChange(false) },
                onClear = { notifList.clear() },
            )
        }

        // Command Palette — centered VS Code-style dropdown, not full width
        if (showCommandPalette) {
            val cmdFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(showCommandPalette) {
                if (showCommandPalette) {
                    kotlinx.coroutines.delay(80) // let the dialog compose before requesting focus
                    cmdFocusRequester.requestFocus()
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x88000000))
                    .pointerInput(Unit) {
                        detectTapGestures { onShowCommandPaletteChange(false); onCommandQueryChange("") }
                    }
            ) {
                Card(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp)
                        .fillMaxWidth(0.75f)
                        .widthIn(max = 380.dp)
                        .heightIn(max = 240.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { /* swallow taps so they don't reach the dismiss layer behind */ }
                        },
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column {
                        androidx.compose.foundation.text.BasicTextField(
                            value = commandQuery,
                            onValueChange = { onCommandQueryChange(it) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MenuText, fontFamily = FontFamily.Default),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .focusRequester(cmdFocusRequester),
                            decorationBox = { inner ->
                                Box {
                                    if (commandQuery.isEmpty()) Text("> Type a command or file name…", fontSize = 13.sp, color = MenuText.copy(alpha = 0.4f))
                                    inner()
                                }
                            },
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MenuText),
                        )
                        HorizontalDivider(color = DividerColor)
                        val filtered = listOf(
                            "New File", "New Folder", "Save File", "Open File",
                            "Toggle Sidebar", "Toggle Terminal", "Select Color Theme",
                            "Go to File", "Find in Files", "Run Program", "Split Terminal",
                            "Explorer", "Search", "Source Control", "Run & Debug", "Extensions",
                            "Git: Commit", "Git: Push", "Git: Pull", "Git: Stage All",
                            "Format Document", "Command Palette",
                            "Close All Editors", "Close Editor",
                            "Open Folder", "Refresh Explorer", "Collapse All in Explorer",
                            "Toggle Word Wrap", "Go to Line",
                        ).filter { commandQuery.isEmpty() || it.contains(commandQuery, ignoreCase = true) }
                        // LazyColumn so the list scrolls properly (incl. after rotation, when
                        // available height shrinks and more items overflow the visible area)
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(filtered) { item ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (item == filtered.firstOrNull() && commandQuery.isNotEmpty()) CmdSelectedBg.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { handleMenuAction(item); onShowCommandPaletteChange(false); onCommandQueryChange("") }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item, fontSize = 13.sp, color = MenuText, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Gear / Settings menu — VS Code-style bottom-left dropdown
        if (showGearMenu) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x44000000))
                    .pointerInput(Unit) {
                        detectTapGestures { onShowGearMenuChange(false) }
                    }
            ) {
                Card(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 52.dp, bottom = 56.dp)
                        .width(240.dp)
                        .heightIn(max = 360.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { /* swallow taps inside the card */ }
                        },
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    LazyColumn(Modifier.padding(4.dp)) {
                        item {
                            Text("Settings", fontSize = 11.sp, color = MenuText.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
                        }
                        val gearItems = listOf(
                            "Color Theme" to { onShowColorThemeChange(true); onShowGearMenuChange(false) },
                            "Toggle Sidebar" to { onActivePanelChange(if (activePanel == null) SidePanel.EXPLORER else null); onShowGearMenuChange(false) },
                            "Toggle Terminal" to { onShowBottomPanelChange(!showBottomPanel); onShowGearMenuChange(false) },
                            "Toggle Copilot Chat" to { onShowChatPanelChange(!showChatPanel); onShowGearMenuChange(false) },
                            "Font Size +" to { onEditorFontSizeChange((editorFontSize + 1).coerceAtMost(32)); onShowGearMenuChange(false) },
                            "Font Size -" to { onEditorFontSizeChange((editorFontSize - 1).coerceAtLeast(8)); onShowGearMenuChange(false) },
                            "App WakeLock: ${if (appWakeLockOn) "ON" else "OFF"}" to {
                                onAppWakeLockOnChange(!appWakeLockOn)
                                val appCtx = context.applicationContext as com.codespace.ide.CodeSpaceApplication
                                if (appWakeLockOn) {
                                    appCtx.acquireAppWakeLock()
                                    showNotification("App WakeLock ON — CPU stays active", "success")
                                } else {
                                    appCtx.releaseAppWakeLock()
                                    showNotification("App WakeLock OFF", "info")
                                }
                                onShowGearMenuChange(false)
                            },
                        )
                        items(gearItems) { (label, action) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { action() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, fontSize = 13.sp, color = MenuText)
                            }
                        }
                        item { HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp)) }
                        item {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onShowGearMenuChange(false); onOpenSettings() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Open Settings Page", fontSize = 13.sp, color = MenuText)
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onShowGearMenuChange(false); onSignOut() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Sign Out", fontSize = 13.sp, color = MenuText)
                            }
                        }
                    }
                }
            }
        }

        // Person / Account menu
        if (showPersonMenu) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x44000000))
                    .pointerInput(Unit) {
                        detectTapGestures { onShowPersonMenuChange(false) }
                    }
            ) {
                Card(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 52.dp, bottom = 56.dp)
                        .width(220.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { /* swallow taps inside the card */ }
                        },
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Signed in as", fontSize = 11.sp, color = MenuText.copy(alpha = 0.5f), modifier = Modifier.padding(8.dp))
                        Text("Wisdom Ijezie", fontSize = 13.sp, color = MenuText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                        listOf(
                            "Settings" to { onShowPersonMenuChange(false); onOpenSettings() },
                            "Sign Out" to { onShowPersonMenuChange(false); onSignOut() }
                        ).forEach { (item, action) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { action() }.padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(item, fontSize = 13.sp, color = MenuText)
                            }
                        }
                    }
                }
            }
        }


        // ── Color Theme Picker Dialog ──────────────────────────────────────
        // P7-1 Snapshot result dialog
    if (snapshotMessage != null) {
        val _snapOrient = LocalConfiguration.current.orientation
        key(_snapOrient) {
        AlertDialog(
            onDismissRequest = { onSnapshotMessageChange(null) },
            title = { Text("Snapshot Created") },
            text  = { Text(snapshotMessage!!, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { onSnapshotMessageChange(null) }) { Text("OK") }
            },
        )
        }
    }

    if (showColorTheme) {
            val allThemes = listOf(
                "Dark (Default)", "Dark Modern", "Dracula", "AMOLED Black",
                "Monokai", "One Dark Pro", "GitHub Dark", "Tokyo Night",
                "Nord", "Catppuccin",
                "Light (Default)", "Light Modern", "GitHub Light",
                "Quiet Light", "Solarized Light", "Eye Care",
            )
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x88000000))
                    .pointerInput(Unit) { detectTapGestures { onShowColorThemeChange(false) } }
            ) {
                Card(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.7f)
                        .pointerInput(Unit) { detectTapGestures { /* swallow */ } },
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Header
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Select Color Theme", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MenuText)
                            Icon(Icons.Default.Close, "Close", tint = MenuText.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp).clickable { onShowColorThemeChange(false) })
                        }
                        HorizontalDivider(color = DividerColor)
                        // Theme grid
                        LazyColumn(
                            Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // Dark themes section
                            item {
                                Text("Dark Themes", fontSize = 11.sp, color = SectionHeaderText,
                                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
                            }
                            val darkThemes = allThemes.filter { !it.contains("Light") && !it.contains("Eye Care") }
                            items(darkThemes) { themeName ->
                                val isSelected = themeName == currentTheme
                                val ti = ideColors(themeName)
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (isSelected) CmdSelectedBg else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .clickable { onSelectTheme(themeName); onShowColorThemeChange(false) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Color preview swatches
                                    Row(Modifier.width(60.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Box(Modifier.size(16.dp).background(ti.BgColor, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                        Box(Modifier.size(16.dp).background(ti.ActivityBarIconActive, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                        Box(Modifier.size(16.dp).background(ti.TabActiveIndicator, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(themeName, fontSize = 13.sp,
                                        color = if (isSelected) CmdSelectedText else MenuText,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                    Spacer(Modifier.weight(1f))
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, "Selected", tint = CmdSelectedText, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            // Light themes section
                            item {
                                Text("Light Themes", fontSize = 11.sp, color = SectionHeaderText,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                            }
                            val lightThemes = allThemes.filter { it.contains("Light") || it == "Eye Care" }
                            items(lightThemes) { themeName ->
                                val isSelected = themeName == currentTheme
                                val ti = ideColors(themeName)
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (isSelected) CmdSelectedBg else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .clickable { onSelectTheme(themeName); onShowColorThemeChange(false) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(Modifier.width(60.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Box(Modifier.size(16.dp).background(ti.BgColor, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                        Box(Modifier.size(16.dp).background(ti.ActivityBarIconActive, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                        Box(Modifier.size(16.dp).background(ti.TabActiveIndicator, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(themeName, fontSize = 13.sp,
                                        color = if (isSelected) CmdSelectedText else MenuText,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                    Spacer(Modifier.weight(1f))
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, "Selected", tint = CmdSelectedText, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Go to Line dialog ─────────────────────────────────────────────
        if (showGoToLine) {
            key(orientation) {
            AlertDialog(
                onDismissRequest = { onShowGoToLineChange(false) },
                title = { Text("Go to Line", color = MenuText) },
                text = {
                    OutlinedTextField(
                        value = goToLineInput,
                        onValueChange = { onGoToLineInputChange(it.filter { c -> c.isDigit() }) },
                        label = { Text("Line number") },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MenuText,
                            unfocusedTextColor = MenuText,
                            focusedLabelColor = TabActiveIndicator,
                            unfocusedLabelColor = TabTextInactive,
                            focusedBorderColor = TabActiveIndicator,
                            unfocusedBorderColor = DividerColor,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val lineNum = goToLineInput.toIntOrNull()
                        if (lineNum != null && lineNum > 0) {
                            onScrollTargetLineChange(lineNum)
                            showNotification("Jumping to line $lineNum", "info")
                        }
                        onShowGoToLineChange(false)
                        onGoToLineInputChange("")
                    }) { Text("Go") }
                },
                dismissButton = {
                    TextButton(onClick = { onShowGoToLineChange(false); onGoToLineInputChange("") }) { Text("Cancel") }
                },
                containerColor = MenuBg,
                titleContentColor = MenuText,
            )
            }
        }
}

/**
 * Activity bar (left icon strip). Extracted from ProjectShellScreen to reduce DEX register count.
 */
@Composable
private fun PssActivityBar(
    projectId: String,
    activeEditorTab: String?,
    activePanel: SidePanel?,
    onActivePanelChange: (SidePanel?) -> Unit,
    onShowPersonMenu: () -> Unit,
    onShowGearMenu: () -> Unit,
    activityBarBg: Color,
    activityBarIcon: Color,
    activityBarIconActive: Color,
    dividerColor: Color,
) {
    val context = LocalContext.current
    Column(
        Modifier.width(48.dp).fillMaxHeight().background(activityBarBg)
            .border(1.dp, dividerColor, RoundedCornerShape(0.dp)).padding(end = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val gitBadgeCount by produceState(0, projectId, activeEditorTab) {
            withContext(Dispatchers.IO) {
                try {
                    val repoDir = java.io.File(
                        if (activeEditorTab != null) java.io.File(activeEditorTab!!).parent ?: context.filesDir.absolutePath
                        else java.io.File(context.filesDir, "projects/$projectId").absolutePath
                    )
                    if (java.io.File(repoDir, ".git").exists()) {
                        val guestPath = com.codespace.ide.terminal.ProotInstaller.hostToGuestPath(context, repoDir.absolutePath)
                        if (guestPath != null) {
                            val out = com.codespace.ide.terminal.ProotInstaller.execOnce(context, "git -C '$guestPath' status --porcelain 2>/dev/null", timeoutSeconds = 10L)
                            value = out.lines().count { it.isNotBlank() && !it.startsWith("Exit") && !it.startsWith("Error") }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        val runBadgeCount by produceState(0, activeEditorTab) {
            withContext(Dispatchers.IO) {
                try {
                    val path = activeEditorTab
                    if (path != null) {
                        val src = java.io.File(path).takeIf { it.exists() }?.readText() ?: ""
                        value = LintChecker.check(path, src).count { it.severity == Problem.Severity.ERROR }
                    }
                } catch (_: Exception) {}
            }
        }
        listOf(
            Triple(SidePanel.EXPLORER, Icons.Default.Description, 0),
            Triple(SidePanel.SEARCH, Icons.Default.Search, 0),
            Triple(SidePanel.GIT, Icons.Default.AccountTree, gitBadgeCount),
            Triple(SidePanel.RUN, Icons.Default.BugReport, runBadgeCount),
            Triple(SidePanel.EXTENSIONS, Icons.Default.Extension, 0),
        ).forEach { (panel, icon, badge) ->
            val isActive = activePanel == panel
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .clickable { onActivePanelChange(if (activePanel == panel) null else panel) },
                contentAlignment = Alignment.Center,
            ) {
                if (isActive) Box(Modifier.width(2.dp).height(24.dp).align(Alignment.CenterStart).background(Color(0xFF007ACC)))
                Icon(icon, null, tint = if (isActive) activityBarIconActive else activityBarIcon, modifier = Modifier.size(24.dp))
                if (badge > 0) {
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xFF007ACC), androidx.compose.foundation.shape.CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(badge.toString(), fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(48.dp).clickable { onShowPersonMenu() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AccountCircle, null, tint = activityBarIcon, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.fillMaxWidth().height(48.dp).clickable { onShowGearMenu() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Settings, null, tint = activityBarIcon, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Extracted from ProjectShellScreen to keep the parent method's DEX register count
 * below ART's 256-register limit (which causes VerifyError on older ART runtimes).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PssBottomPanelContent(
    showBottomPanel: Boolean,
    onHideBottomPanel: () -> Unit,
    bottomPanelHeight: Float,
    onBottomPanelHeightChange: (Float) -> Unit,
    bottomPanelPrevHeight: Float = 300f,
    onBottomPanelPrevHeightChange: (Float) -> Unit = {},
    bottomPanelMaximized: Boolean = false,
    onBottomPanelMaximizedChange: (Boolean) -> Unit = {},
    isDraggingBottomPanel: Boolean,
    onDraggingChange: (Boolean) -> Unit,
    activeBottomTab: BottomTab,
    onActiveBottomTabChange: (BottomTab) -> Unit,
    terminalCommandToRun: String?,
    onCommandConsumed: () -> Unit,
    sharedTerminalState: TerminalState,
    activeEditorTab: String?,
    debugMessages: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    debugInput: androidx.compose.runtime.MutableState<String>,
    sharedPreviewState: com.codespace.ide.ui.panes.PreviewState,
    previewPort: Int?,
    onPreviewPortChange: (Int) -> Unit,
    projectId: String,
    totalHeight: Float,
    dividerColor: Color,
    panelBg: Color,
    tabTextInactive: Color,
    onRunInTerminal: (String) -> Unit = {},
    heavyPanesReady: Boolean = false,
) {
    val density = LocalDensity.current
    if (!showBottomPanel) return

    Box(
        Modifier.fillMaxWidth().height(8.dp).background(dividerColor.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDraggingChange(true) },
                    onDragEnd = {
                        onDraggingChange(false)
                        if (bottomPanelHeight < 60f) {
                            onHideBottomPanel()
                            onBottomPanelHeightChange(260f)
                        }
                    },
                    onDragCancel = { onDraggingChange(false) },
                ) { _, dragAmount ->
                    val nh = bottomPanelHeight - dragAmount.y
                    onBottomPanelHeightChange(nh.coerceIn(0f, totalHeight * 0.92f))
                    if (bottomPanelMaximized) onBottomPanelMaximizedChange(false)
                }
            }
    )
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF3F3F3)).height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(BottomTab.entries) { tab ->
                val isActive = tab == activeBottomTab
                Box(
                    Modifier.clickable { onActiveBottomTabChange(tab) }
                        .background(if (isActive) Color(0xFFDCEAFB) else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(if (isActive) 1.dp else 0.dp, if (isActive) Color(0xFF007ACC) else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab.name, fontSize = 10.sp,
                        color = if (isActive) Color(0xFF007ACC) else Color(0xFF717171),
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.width(3.dp))
            }
        }
        Icon(
            if (bottomPanelMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
            contentDescription = if (bottomPanelMaximized) "Restore" else "Maximize",
            tint = tabTextInactive,
            modifier = Modifier.size(18.dp).padding(1.dp).clickable {
                if (bottomPanelMaximized) {
                    // Restore to previous height
                    onBottomPanelHeightChange(bottomPanelPrevHeight.coerceAtLeast(200f))
                    onBottomPanelMaximizedChange(false)
                } else {
                    // Save current height, then maximize
                    onBottomPanelPrevHeightChange(bottomPanelHeight)
                    onBottomPanelHeightChange(totalHeight * 0.88f)
                    onBottomPanelMaximizedChange(true)
                }
            })
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.Close, null, tint = tabTextInactive,
            modifier = Modifier.size(18.dp).padding(1.dp).clickable { onHideBottomPanel() })
        Spacer(Modifier.width(6.dp))
    }
    HorizontalDivider(color = dividerColor)
    val maxDp = with(density) { totalHeight.toDp() }
    val bh = with(density) { bottomPanelHeight.toDp() }.coerceIn(0.dp, maxDp)
    val animatedBh by animateDpAsState(
        targetValue = bh,
        animationSpec = if (isDraggingBottomPanel) snap() else tween(180),
        label = "bottomPanelHeight",
    )
    Box(Modifier.fillMaxWidth().height(animatedBh).background(panelBg)) {
        when (activeBottomTab) {
            BottomTab.TERMINAL -> TerminalPane(
                initialCommand = terminalCommandToRun,
                onCommandConsumed = onCommandConsumed,
                externalState = sharedTerminalState,
                projectId = projectId,
            )
            BottomTab.PROBLEMS -> ProblemsPanel(
                activeFilePath = activeEditorTab,
                onJumpToSource = { onHideBottomPanel() },
            )
            BottomTab.OUTPUT   -> OutputPanel()
            BottomTab.DEBUG    -> DebugConsolePanel(
                messages = debugMessages,
                input = debugInput,
                onSend = { text ->
                    if (text.isNotBlank()) {
                        debugMessages.add("> $text")
                        debugInput.value = ""
                    }
                },
                onRun = {
                    val path = activeEditorTab
                    if (path.isNullOrBlank()) {
                        debugMessages.add("[debug] No file open — open a file first, then press Run.")
                    } else {
                        val cmd = buildRunCommand(path)
                        if (cmd == null) {
                            debugMessages.add("[debug] Don't know how to run '${path.substringAfterLast('/')}' — unsupported file type.")
                        } else {
                            debugMessages.add("> $cmd")
                            debugMessages.add("[debug] Dispatched to Terminal tab — switch there to see live output.")
                            AppOutputLog.log("Running ${path.substringAfterLast('/')}", "debug")
                            onRunInTerminal(cmd)
                            onActiveBottomTabChange(BottomTab.TERMINAL)
                        }
                    }
                },
            )
            BottomTab.PORTS    -> PortsPanel(onOpenInPreview = { port ->
                onPreviewPortChange(port)
                onActiveBottomTabChange(BottomTab.PREVIEW)
            })
            BottomTab.SPLIT    -> SplitTerminalPanel(sharedState = sharedTerminalState)
            BottomTab.PREVIEW  -> PreviewPane(
                activeFilePath = activeEditorTab ?: "",
                initialPort = previewPort ?: 0,
                externalState = sharedPreviewState,
            )
            BottomTab.LOGCAT   -> if (heavyPanesReady) {
                LogcatPanel(modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF569CD6))
                }
            }
            BottomTab.TOOLCHAIN -> ToolchainPanel(
                modifier = Modifier.fillMaxSize(),
                onRunInstall = { cmd ->
                    onRunInTerminal(cmd)
                    onActiveBottomTabChange(BottomTab.TERMINAL)
                },
            )
            BottomTab.TASKS -> TaskRunnerPanel(
                projectPath = if (activeEditorTab != null) {
                    java.io.File(activeEditorTab!!).parent ?: ""
                } else "",
                modifier = Modifier.fillMaxSize(),
            )
            BottomTab.HISTORY -> if (heavyPanesReady) {
                BuildHistoryPanel(modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF569CD6))
                }
            }
            BottomTab.ARTIFACTS -> ArtifactPanel(
                projectPath = if (activeEditorTab != null) {
                    java.io.File(activeEditorTab!!).parent ?: ""
                } else "",
                modifier = Modifier.fillMaxSize(),
            )
            BottomTab.DOWNLOADS -> DownloadCenterPanel(modifier = Modifier.fillMaxSize())
            BottomTab.BACKUP -> {
                CloudBackupPanel(
                    projectId  = projectId,
                    backendUrl = "https://codespace-ide-mobile-production.up.railway.app",
                    onDismiss  = { onActiveBottomTabChange(BottomTab.TERMINAL) },
                )
            }
            BottomTab.VARIABLES -> if (heavyPanesReady) {
                VariableInspectorPanel(
                    activeFilePath = activeEditorTab,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF569CD6))
                }
            }
            BottomTab.BUILD -> BuildPanel(
                projectPath = if (activeEditorTab != null) {
                    java.io.File(activeEditorTab!!).parent ?: ""
                } else "",
            )
        }
    }
}

private fun buildRunCommand(path: String): String? {
    val quoted = "\"$path\""
    return when {
        path.endsWith(".py")               -> "python3 $quoted"
        path.endsWith(".js") || path.endsWith(".mjs") -> "node $quoted"
        path.endsWith(".ts")               -> "npx ts-node $quoted"
        path.endsWith(".sh")               -> "bash $quoted"
        path.endsWith(".rb")               -> "ruby $quoted"
        path.endsWith(".go")               -> "go run $quoted"
        path.endsWith(".rs")               -> "rustc $quoted -o /tmp/rust_out && /tmp/rust_out"
        path.endsWith(".c")                -> "gcc $quoted -o /tmp/c_out && /tmp/c_out"
        path.endsWith(".cpp") || path.endsWith(".cc") -> "g++ $quoted -o /tmp/cpp_out && /tmp/cpp_out"
        path.endsWith(".php")              -> "php $quoted"
        else -> null
    }
}

@Composable private fun ProblemsPanel(activeFilePath: String?, onJumpToSource: () -> Unit) {
    val problems = remember(activeFilePath) {
        if (activeFilePath.isNullOrBlank()) emptyList()
        else try { LintChecker.check(activeFilePath, loadFileContent(activeFilePath)) } catch (_: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROBLEMS" + if (problems.isNotEmpty()) " (${problems.size})" else "", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.FilterList, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        if (activeFilePath.isNullOrBlank()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("Open a file to see problems detected in it.", fontSize = 13.sp, color = Color(0xFF717171))
            }
        } else if (problems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("✓  No problems detected in ${activeFilePath.substringAfterLast('/')}.", fontSize = 13.sp, color = Color(0xFF717171))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(problems) { p ->
                    val (icon, tint) = when (p.severity) {
                        Problem.Severity.ERROR   -> Icons.Default.Cancel to Color(0xFFE51400)
                        Problem.Severity.WARNING -> Icons.Default.Warning to Color(0xFFCCA700)
                        Problem.Severity.INFO    -> Icons.Default.Info to Color(0xFF007ACC)
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { onJumpToSource() }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(p.message, fontSize = 12.sp, color = Color(0xFF424242), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${activeFilePath.substringAfterLast('/')}:${p.line}", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                    }
                }
            }
        }
    }
}

@Composable private fun OutputPanel() {
    val logs = AppOutputLog.lines
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.Delete, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { AppOutputLog.clear() })
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        LazyColumn(Modifier.fillMaxSize().padding(8.dp), state = listState) {
            items(logs) { log -> Text(log, fontSize = 12.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable private fun DebugConsolePanel(
    messages: SnapshotStateList<String>,
    input: MutableState<String>,
    onSend: (String) -> Unit,
    onRun: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DEBUG CONSOLE", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF007ACC), modifier = Modifier.size(16.dp).clickable { onRun() })
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Delete, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { messages.clear(); messages.add("Debugger ready. Press Run to start.") })
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        LazyColumn(Modifier.weight(1f).padding(8.dp)) {
            items(messages) { msg -> Text(msg, fontSize = 12.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(">", fontSize = 13.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
            androidx.compose.foundation.text.BasicTextField(value = input.value, onValueChange = { input.value = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF333333)), modifier = Modifier.weight(1f), singleLine = true)
            Icon(Icons.Default.Send, null, tint = Color(0xFF007ACC), modifier = Modifier.size(18.dp).clickable { onSend(input.value) })
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable private fun PortsPanel(onOpenInPreview: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    // Rotation fix (#8): see ProjectShellScreen above for rationale.
    val orientation = LocalConfiguration.current.orientation
    var ports by remember { mutableStateOf<List<ForwardedPort>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var customPorts by remember { mutableStateOf(listOf<Int>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addPortText by remember { mutableStateOf("") }

    suspend fun rescan() {
        scanning = true
        ports = PortsScanner.scan(customPorts)
        scanning = false
    }
    LaunchedEffect(customPorts) { rescan() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PORTS", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFF717171))
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Default.Refresh, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { scope.launch { rescan() } })
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Default.Add, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { showAddDialog = true })
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        if (ports.isEmpty() && !scanning) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("No forwarded ports detected. Start a dev server (e.g. Remotion on :3000) then tap ⟳, or tap + to check a specific port.", fontSize = 13.sp, color = Color(0xFF717171))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ports) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenInPreview(p.port) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).background(Color(0xFF89D185), androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${p.port}", fontSize = 13.sp, color = Color(0xFF212121), fontFamily = FontFamily.Monospace)
                            Text(p.label, fontSize = 11.sp, color = Color(0xFF717171))
                        }
                        Icon(Icons.Default.OpenInBrowser, null, tint = Color(0xFF007ACC), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Forward a port") },
            text = {
                OutlinedTextField(value = addPortText, onValueChange = { addPortText = it.filter { c -> c.isDigit() } },
                    label = { Text("Port number") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    addPortText.toIntOrNull()?.let { customPorts = customPorts + it }
                    addPortText = ""; showAddDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
        }
    }
}

// ConnectorRow moved to ConnectorsHubSheet.kt

// ── P9: Extracted composables to keep ProjectShellScreen under JVM method limit ──

@Composable
private fun SymbolSearchOverlay(
    activeEditorTab: String?,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable { onDismiss() }
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp).padding(top = 60.dp)
                .clickable { /* consume click */ }
        ) {
            SymbolSearchPanel(
                onNavigate = { filePath, line ->
                    onNavigate(filePath)
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun StatusBarContent(
    statusBarBg: Color,
    activeEditorTab: String?,
    cursorLine: Int,
    cursorCol: Int,
) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(statusBarBg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AccountTree, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("main", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.width(8.dp))
        // P9-5: Live file metrics
        if (activeEditorTab != null) {
            val fileStats = remember(activeEditorTab) {
                try { CodeMetrics.analyze(java.io.File(activeEditorTab).readText()) } catch (_: Exception) { null }
            }
            if (fileStats != null) {
                Text("${fileStats.lineCount} lines", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(6.dp))
                Text(fileStats.sizeLabel, fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(6.dp))
                if (fileStats.functionCount > 0) {
                    Text("${fileStats.functionCount} fn", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // P9-5: Live cursor position
        Text("Ln $cursorLine, Col $cursorCol", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text("UTF-8", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        // P9-3: RAM usage indicator
        val memInfo = remember { mutableStateOf(MemoryMonitor.getMemInfo()) }
        LaunchedEffect(Unit) {
            while (true) {
                memInfo.value = MemoryMonitor.getMemInfo()
                kotlinx.coroutines.delay(5000)
            }
        }
        Text(
            "${memInfo.value.usedMb}/${memInfo.value.totalMb}MB",
            fontSize = 9.sp,
            color = if (memInfo.value.isLowRam) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(8.dp))
        // MCP Agent API status indicator
        val mcpConnected = remember { mutableStateOf(com.codespace.ide.agent.AgentApiServer.isRunning()) }
        LaunchedEffect(Unit) {
            while (true) {
                mcpConnected.value = com.codespace.ide.agent.AgentApiServer.isRunning()
                kotlinx.coroutines.delay(3000)
            }
        }
        Box(Modifier.size(7.dp).background(
            if (mcpConnected.value) Color(0xFF4CAF50) else Color(0xFFF44336),
            CircleShape
        ))
        Spacer(Modifier.width(3.dp))
        Text("MCP", fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
        // P16-F: Sync status indicator
        val syncState by SyncStatusMonitor.syncState.collectAsState()
        when (val s = syncState) {
            is SyncState.Syncing -> {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(7.dp), strokeWidth = 1.dp, color = Color(0xFF007ACC))
                Spacer(Modifier.width(3.dp))
                Text(s.label, fontSize = 9.sp, color = Color(0xFF007ACC))
            }
            is SyncState.Success -> {
                Spacer(Modifier.width(6.dp))
                Text("✓ ${s.label}", fontSize = 9.sp, color = Color(0xFF4CAF50))
            }
            is SyncState.Error -> {
                Spacer(Modifier.width(6.dp))
                Text("⚠ ${s.msg.take(30)}", fontSize = 9.sp, color = Color(0xFFCC0000))
            }
            is SyncState.Idle -> { /* nothing */ }
        }
    }
}

/**
 * Editor Column + Split Terminal + Chat Panel composable.
 * Extracted from ProjectShellScreen to reduce the main function's DEX register count,
 * fixing: VerifyError copy-cat1 v22<-v293 type=High-half Constant (classes12.dex).
 *
 * State vars that are mutated by this composable are passed as MutableState<T> so the
 * body can use `var X by XMs` delegation — exactly the same read/write semantics as
 * the original inlined code, with zero logic changes needed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PssEditorColumn(
    projectId: String,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    editorTabs: SnapshotStateList<String>,
    heavyPanesReady: Boolean,
    wordWrapMs: MutableState<Boolean>,
    showInlayHintsMs: MutableState<Boolean>,
    showGoToLineMs: MutableState<Boolean>,
    sessionStateStore: com.codespace.ide.data.SessionStateStore,
    keyboardToolbarBg: Color,
    navBackStack: SnapshotStateList<NavEntry>,
    navFwdStack: SnapshotStateList<NavEntry>,
    sharedTerminalState: TerminalState,
    sharedPreviewState: com.codespace.ide.ui.panes.PreviewState,
    debugMessages: SnapshotStateList<String>,
    debugInput: MutableState<String>,
    totalWidth: Float,
    totalHeight: Float,
    tabBarBg: Color,
    tabActiveBg: Color,
    tabInactiveBg: Color,
    tabActiveIndicator: Color,
    tabText: Color,
    tabTextInactive: Color,
    dividerColor: Color,
    panelBg: Color,
    bgColor: Color,
    onHandleMenuAction: (String) -> Unit,
    onShowNotification: (String, String) -> Unit,
    onPushNavEntry: (path: String?, line: Int) -> Unit,
    onNavBack: () -> Unit,
    onNavForward: () -> Unit,
    // Mutable state pass-through (var X by XMs in body — zero logic changes)
    activeBottomTabMs: MutableState<BottomTab>,
    activeEditorTabMs: MutableState<String?>,
    aiPanelWidthMs: MutableState<Float>,
    bottomPanelHeightMs: MutableState<Float>,
    bottomPanelMaximizedMs: MutableState<Boolean>,
    bottomPanelPrevHeightMs: MutableState<Float>,
    breadcrumbNavDirMs: MutableState<String?>,
    cursorColMs: MutableState<Int>,
    cursorLineMs: MutableState<Int>,
    editorFontSizeMs: MutableState<Int>,
    findQueryMs: MutableState<String>,
    isDraggingBottomPanelMs: MutableState<Boolean>,
    keyboardInsertMs: MutableState<((String) -> Unit)?>,
    previewPortMs: MutableState<Int?>,
    replaceQueryMs: MutableState<String>,
    scrollTargetLineMs: MutableState<Int>,
    showBottomPanelMs: MutableState<Boolean>,
    showChatPanelMs: MutableState<Boolean>,
    showFileSearchMs: MutableState<Boolean>,
    showFindBarMs: MutableState<Boolean>,
    showReplaceRowMs: MutableState<Boolean>,
    showSplitTerminalMs: MutableState<Boolean>,
    showSymbolSearchMs: MutableState<Boolean>,
    splitTerminalWidthMs: MutableState<Float>,
    terminalCommandToRunMs: MutableState<String?>,
) {
    val density = LocalDensity.current
    // Color param aliases — body code uses PascalCase originals, params are camelCase
    val TabBarBg = tabBarBg; val TabActiveBg = tabActiveBg; val TabInactiveBg = tabInactiveBg
    val TabActiveIndicator = tabActiveIndicator; val TabText = tabText; val TabTextInactive = tabTextInactive
    val DividerColor = dividerColor; val PanelBg = panelBg; val BgColor = bgColor
    val KeyboardToolbarBg = keyboardToolbarBg
    var wordWrap by wordWrapMs
    var showInlayHints by showInlayHintsMs
    var showGoToLine by showGoToLineMs
    // Local function aliases — these mirror the original local fun declarations in PSS
    // so the extracted body code works with zero changes
    val showNotification: (String, String) -> Unit = onShowNotification
    val handleMenuAction: (String) -> Unit = onHandleMenuAction
    fun pushNavEntry(path: String?, line: Int) = onPushNavEntry(path, line)
    fun navBack() = onNavBack()
    fun navForward() = onNavForward()
    // MutableState delegation — same read/write semantics as original inlined vars
    var activeBottomTab by activeBottomTabMs
    var activeEditorTab by activeEditorTabMs
    var aiPanelWidth by aiPanelWidthMs
    var bottomPanelHeight by bottomPanelHeightMs
    var bottomPanelMaximized by bottomPanelMaximizedMs
    var bottomPanelPrevHeight by bottomPanelPrevHeightMs
    var breadcrumbNavDir by breadcrumbNavDirMs
    var cursorCol by cursorColMs
    var cursorLine by cursorLineMs
    var editorFontSize by editorFontSizeMs
    var findQuery by findQueryMs
    var isDraggingBottomPanel by isDraggingBottomPanelMs
    var keyboardInsert by keyboardInsertMs
    var previewPort by previewPortMs
    var replaceQuery by replaceQueryMs
    var scrollTargetLine by scrollTargetLineMs
    var showBottomPanel by showBottomPanelMs
    var showChatPanel by showChatPanelMs
    var showFileSearch by showFileSearchMs
    var showFindBar by showFindBarMs
    var showReplaceRow by showReplaceRowMs
    var showSplitTerminal by showSplitTerminalMs
    var showSymbolSearch by showSymbolSearchMs
    var splitTerminalWidth by splitTerminalWidthMs
    var terminalCommandToRun by terminalCommandToRunMs

    // Editor Column
    Column(modifier.fillMaxHeight()) {

        // Editor tab bar
        if (editorTabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(35.dp).background(TabBarBg)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
            ) {
                var tabContextMenuFor by remember { mutableStateOf<String?>(null) }
                editorTabs.forEach { tab ->
                    val isActive = tab == activeEditorTab
                    Box {
                        Column(Modifier.clickable { pushNavEntry(activeEditorTab, scrollTargetLine); activeEditorTab = tab }
                            .combinedClickable(
                                onClick = { activeEditorTab = tab },
                                onLongClick = { tabContextMenuFor = tab },
                            )
                            .background(if (isActive) TabActiveBg else TabInactiveBg)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.substringAfterLast("/"), fontSize = 13.sp,
                                    color = if (isActive) TabText else TabTextInactive, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Close, null, tint = TabTextInactive,
                                    modifier = Modifier.size(14.dp).clickable {
                                        editorTabs.remove(tab)
                                        if (activeEditorTab == tab) activeEditorTab = editorTabs.lastOrNull()
                                    })
                            }
                            if (isActive) Box(Modifier.fillMaxWidth().height(1.dp).background(TabActiveIndicator))
                            else Spacer(Modifier.height(1.dp))
                        }
                        DropdownMenu(
                            expanded = tabContextMenuFor == tab,
                            onDismissRequest = { tabContextMenuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Close", fontSize = 13.sp) },
                                onClick = {
                                    editorTabs.remove(tab)
                                    if (activeEditorTab == tab) activeEditorTab = editorTabs.lastOrNull()
                                    tabContextMenuFor = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close Others", fontSize = 13.sp) },
                                onClick = {
                                    editorTabs.removeAll { it != tab }
                                    activeEditorTab = tab
                                    tabContextMenuFor = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close All", fontSize = 13.sp) },
                                onClick = {
                                    editorTabs.clear()
                                    activeEditorTab = null
                                    tabContextMenuFor = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close Saved", fontSize = 13.sp) },
                                onClick = {
                                    // Keep only dirty tabs — since we auto-save, all are "saved"
                                    // This closes all tabs (none are unsaved in our model)
                                    editorTabs.clear()
                                    activeEditorTab = null
                                    tabContextMenuFor = null
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Copy Path", fontSize = 13.sp) },
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", tab))
                                    Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                                    tabContextMenuFor = null
                                },
                            )
                        }
                    }
                    Box(Modifier.width(1.dp).height(35.dp).background(DividerColor))
                }
            }
        }

        // Breadcrumb
        if (activeEditorTab != null) {
            Row(
                Modifier.fillMaxWidth().height(22.dp).background(BgColor).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = activeEditorTab!!.removePrefix("/storage/emulated/0/").split("/")
                parts.forEachIndexed { idx, part ->
                    Text(part, fontSize = 12.sp, color = if (idx == parts.lastIndex) TabText else TabTextInactive, maxLines = 1)
                    if (idx < parts.lastIndex) Icon(Icons.Default.ChevronRight, null, tint = TabTextInactive, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // ── Editor toolbar — quick action icons ───────────────────
        if (activeEditorTab != null) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(BgColor)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Find
                Box(Modifier.size(28.dp).clickable { showFindBar = !showFindBar; showReplaceRow = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Search, null, tint = if (showFindBar) TabActiveIndicator else TabTextInactive, modifier = Modifier.size(16.dp))
                }
                // Replace
                Box(Modifier.size(28.dp).clickable { showFindBar = true; showReplaceRow = !showReplaceRow }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.FindReplace, null, tint = if (showReplaceRow) TabActiveIndicator else TabTextInactive, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(DividerColor))
                Spacer(Modifier.width(4.dp))
                // Zoom out
                Box(Modifier.size(28.dp).clickable { editorFontSize = (editorFontSize - 1).coerceAtLeast(8) }, contentAlignment = Alignment.Center) {
                    Text("−", fontSize = 16.sp, color = TabTextInactive)
                }
                Text("${'$'}editorFontSize", fontSize = 10.sp, color = TabTextInactive, modifier = Modifier.padding(horizontal = 2.dp))
                // Zoom in
                Box(Modifier.size(28.dp).clickable { editorFontSize = (editorFontSize + 1).coerceAtMost(32) }, contentAlignment = Alignment.Center) {
                    Text("+", fontSize = 16.sp, color = TabTextInactive)
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(DividerColor))
                Spacer(Modifier.width(4.dp))
                // Word wrap toggle
                Box(Modifier.size(28.dp).clickable { wordWrap = !wordWrap }, contentAlignment = Alignment.Center) {
                    Text("↵", fontSize = 14.sp, color = if (wordWrap) TabActiveIndicator else TabTextInactive)
                }
                Box(Modifier.size(28.dp).clickable { showInlayHints = !showInlayHints }, contentAlignment = Alignment.Center) {
                    Text("⊕", fontSize = 13.sp, color = if (showInlayHints) TabActiveIndicator else TabTextInactive)
                }
                // Go to line
                Box(Modifier.size(28.dp).clickable { showGoToLine = true }, contentAlignment = Alignment.Center) {
                    Text(":${'$'}", fontSize = 14.sp, color = TabTextInactive, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(DividerColor))
                Spacer(Modifier.width(4.dp))
                // P2-10 Nav back
                Box(
                    Modifier.size(28.dp).clickable(enabled = navBackStack.isNotEmpty()) { navBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("←", fontSize = 16.sp,
                        color = if (navBackStack.isNotEmpty()) TabTextInactive else TabTextInactive.copy(alpha = 0.25f))
                }
                // P2-10 Nav forward
                Box(
                    Modifier.size(28.dp).clickable(enabled = navFwdStack.isNotEmpty()) { navForward() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("→", fontSize = 16.sp,
                        color = if (navFwdStack.isNotEmpty()) TabTextInactive else TabTextInactive.copy(alpha = 0.25f))
                }
                Spacer(Modifier.weight(1f))
                // Match count for find
                if (showFindBar && findQuery.isNotEmpty()) {
                    val active = activeEditorTab
                    if (active != null) {
                        val content = try { java.io.File(active).readText() } catch (_: Exception) { "" }
                        val count = content.split(findQuery).size - 1
                        val matchWord = if (count == 1) "match" else "matches"
                        Text(count.toString() + " " + matchWord, fontSize = 10.sp, color = TabTextInactive)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // Find & Replace bar
        if (showFindBar) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF5F5F5))
                    .border(1.dp, DividerColor, RoundedCornerShape(0.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findQuery, onValueChange = { findQuery = it },
                        placeholder = { Text("Find", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.weight(1f).height(36.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.border(1.dp, DividerColor, RoundedCornerShape(3.dp)).padding(4.dp)) {
                        Text("Aa", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.border(1.dp, DividerColor, RoundedCornerShape(3.dp)).padding(4.dp)) {
                        Text("\\b", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.border(1.dp, DividerColor, RoundedCornerShape(3.dp)).padding(4.dp)) {
                        Text(".*", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.KeyboardArrowUp, null, tint = TabTextInactive, modifier = Modifier.size(20.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = TabTextInactive, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Close, null, tint = TabTextInactive,
                        modifier = Modifier.size(18.dp).clickable { showFindBar = false; findQuery = ""; replaceQuery = "" })
                }
                if (showReplaceRow) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = replaceQuery, onValueChange = { replaceQuery = it },
                            placeholder = { Text("Replace", fontSize = 12.sp) },
                            singleLine = true, modifier = Modifier.weight(1f).height(36.dp))
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            val active = activeEditorTab
                            if (active != null && findQuery.isNotEmpty()) {
                                try {
                                    val content = java.io.File(active).readText()
                                    val idx = content.indexOf(findQuery)
                                    if (idx >= 0) {
                                        val newContent = content.substring(0, idx) + replaceQuery + content.substring(idx + findQuery.length)
                                        java.io.File(active).writeText(newContent)
                                        showNotification("Replaced 1 occurrence", "info")
                                    }
                                } catch (e: Exception) {
                                    showNotification("Replace failed: ${'$'}{e.message}", "error")
                                }
                            }
                        }, modifier = Modifier.height(36.dp)) { Text("Replace", fontSize = 11.sp) }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            val active = activeEditorTab
                            if (active != null && findQuery.isNotEmpty()) {
                                try {
                                    val content = java.io.File(active).readText()
                                    val newContent = content.replace(findQuery, replaceQuery)
                                    java.io.File(active).writeText(newContent)
                                    showNotification("Replaced ${'$'}{content.split(findQuery).size - 1} occurrences", "info")
                                } catch (e: Exception) {
                                    showNotification("Replace failed: ${'$'}{e.message}", "error")
                                }
                            }
                        }, modifier = Modifier.height(36.dp)) { Text("All", fontSize = 11.sp) }
                    }
                } else {
                    TextButton(onClick = { showReplaceRow = true }) { Text("Replace", fontSize = 12.sp) }
                }
            }
        }

        // Editor area
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (activeEditorTab != null) {
                EditorPane(
                    openFilePath       = activeEditorTab,
                    fontSize           = editorFontSize,
                    onInsertRequest    = { fn -> keyboardInsert = fn },
                    onCursorChange     = { line, col -> cursorLine = line; cursorCol = col },
                    wordWrap           = wordWrap,
                    showInlayHints     = showInlayHints,
                    scrollToLine       = scrollTargetLine,
                    projectId          = projectId,
                    sessionStateStore  = sessionStateStore,
                )
            } else {
                Box(Modifier.fillMaxSize().background(BgColor), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.codespace.ide.R.drawable.vncode_watermark),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Visual Node Code", fontSize = 22.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(8.dp))
                        Text("Open Explorer → tap a file to start", fontSize = 13.sp, color = Color(0xFFCCCCCC))

                    }
                }
            }
        }

        // Coding toolbar
        if (activeEditorTab != null) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(KeyboardToolbarBg)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(4.dp))
                val isDarkToolbar = KeyboardToolbarBg.red < 0.5f
                val keyBg = if (isDarkToolbar) Color(0xFF3A3A3A) else Color(0xFFFFFFFF)
                val keyText = if (isDarkToolbar) Color(0xFFEEEEEE) else Color(0xFF333333)
                val keyBorder = if (isDarkToolbar) Color(0xFF555555) else DividerColor
                SPECIAL_KEYS.forEach { key ->
                    Box(
                        Modifier.height(32.dp).defaultMinSize(minWidth = 36.dp)
                            .background(keyBg, RoundedCornerShape(4.dp))
                            .border(1.dp, keyBorder, RoundedCornerShape(4.dp))
                            .clickable { keyboardInsert?.invoke(key) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(key, fontSize = 13.sp, color = keyText, fontFamily = FontFamily.Monospace) }
                    Spacer(Modifier.width(4.dp))
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // Bottom Panel — extracted to PssBottomPanelContent to keep
        // ProjectShellScreen's DEX method register count below ART's 256-register
        // verifier limit (VerifyError fix).
        PssBottomPanelContent(
            showBottomPanel = showBottomPanel,
            onHideBottomPanel = { showBottomPanel = false },
            bottomPanelHeight = bottomPanelHeight,
            onBottomPanelHeightChange = { bottomPanelHeight = it },
            bottomPanelPrevHeight = bottomPanelPrevHeight,
            onBottomPanelPrevHeightChange = { bottomPanelPrevHeight = it },
            bottomPanelMaximized = bottomPanelMaximized,
            onBottomPanelMaximizedChange = { bottomPanelMaximized = it },
            isDraggingBottomPanel = isDraggingBottomPanel,
            onDraggingChange = { isDraggingBottomPanel = it },
            activeBottomTab = activeBottomTab,
            onActiveBottomTabChange = { activeBottomTab = it },
            terminalCommandToRun = terminalCommandToRun,
            onCommandConsumed = { terminalCommandToRun = null },
            sharedTerminalState = sharedTerminalState,
            activeEditorTab = activeEditorTab,
            debugMessages = debugMessages,
            debugInput = debugInput,
            sharedPreviewState = sharedPreviewState,
            previewPort = previewPort,
            onPreviewPortChange = { previewPort = it },
            projectId = projectId,
            totalHeight = totalHeight,
            dividerColor = DividerColor,
            panelBg = PanelBg,
            tabTextInactive = TabTextInactive,
            onRunInTerminal = { cmd -> terminalCommandToRun = cmd + "\r" },
            heavyPanesReady = heavyPanesReady,
        )

    } // end editor Column

    // Split Terminal Panel
    if (showSplitTerminal) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            Modifier
                .width(with(density) { splitTerminalWidth.toDp() })
                .fillMaxHeight()
        ) {
            Column(Modifier.fillMaxSize()) {
                // Drag handle
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(DividerColor)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                splitTerminalWidth = (splitTerminalWidth - dragAmount)
                                    .coerceIn(200f, 600f)
                            }
                        }
                )
                // Header
                Row(
                    Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF252526)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TERMINAL 2", fontSize = 11.sp, color = Color(0xFF969696), modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, null, tint = Color(0xFF969696),
                        modifier = Modifier.size(14.dp).clickable { showSplitTerminal = false })
                }
                HorizontalDivider(color = DividerColor)
                TerminalPane(externalState = sharedTerminalState, projectId = projectId)
            }
        }
    }


    // ── AI Chat Panel (right side, draggable, own region — not shared with Explorer) ──
    if (showChatPanel) {
        val chatWidth = with(density) { aiPanelWidth.toDp() }.coerceIn(0.dp, 600.dp)
        // Drag handle on left edge of chat panel — mirrors Explorer's mechanics but
        // flipped: drag right→left widens (handle moves left, panel gets wider),
        // drag left→right shrinks it down to a full close.
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(DividerColor)
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        val nw = aiPanelWidth - dragAmount.x
                        if (nw < 20f) {
                            showChatPanel = false
                            aiPanelWidth = 300f
                        } else {
                            aiPanelWidth = nw.coerceIn(0f, totalWidth * 0.8f)
                        }
                    }
                }
        )
        // Chat panel content
        Box(Modifier.width(chatWidth).fillMaxHeight().background(PanelBg)) {
            CopilotChatPanelInline(
                onClose = { showChatPanel = false },
                colors = ChatPanelColors(
                    background = BgColor,
                    surface = PanelBg,
                    text = TabText,
                    textSecondary = TabTextInactive,
                    accent = TabActiveIndicator,
                    userBubble = TabActiveIndicator,
                    assistantBubble = PanelBg,
                    inputBg = PanelBg,
                    divider = DividerColor,
                    headerBg = PanelBg,
                    scrim = Color(0x66000000),
                ),
                tokenStore = tokenStore,
                // AI auto-opens files it writes — no manual navigation needed
                onOpenFile = { path ->
                    if (!editorTabs.contains(path)) editorTabs.add(path)
                    activeEditorTab = path
                },
                onSwitchToPreview = { path ->
                    showBottomPanel = true
                    activeBottomTab = BottomTab.PREVIEW
                    // activeEditorTab drives PreviewPane.activeFilePath — already set in onOpenFile
                },
            )
        }
    }

}

