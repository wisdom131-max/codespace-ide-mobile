package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.data.SessionStateStore
import com.codespace.ide.terminal.BusyboxInstaller
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.codespace.ide.terminal.TerminalEnhancementManager
import com.codespace.ide.ui.panes.*

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

private enum class SidePanel { EXPLORER, SEARCH, GIT, RUN, EXTENSIONS }

// NotifItem moved to NotificationDrawerOverlay.kt
private enum class BottomTab  { PROBLEMS, OUTPUT, TERMINAL, DEBUG, PORTS, SPLIT, PREVIEW }

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
        MenuAction("",divider=true), MenuAction("Preferences"), MenuAction("Exit"),
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

@Composable
fun ProjectShellScreen(
    projectId: String,
    isDark: Boolean,
    currentTheme: String = if (isDark) "Dark (Default)" else "Light (Default)",
    onSelectTheme: (String) -> Unit = {},
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    tokenStore: SecureTokenStore,
    sessionStateStore: SessionStateStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
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
    var showBottomPanel    by remember(projectId, restoredState) { mutableStateOf(restoredState?.showBottomPanel ?: true) }
    var showSplitTerminal  by remember { mutableStateOf(false) }
    var splitTerminalWidth by remember { mutableFloatStateOf(300f) }
    // Shared terminal state — both TerminalPane and SplitTerminalPanel share this
    val sharedTerminalState = rememberTerminalState(context)

    var activeBottomTab    by remember(projectId, restoredState) { mutableStateOf(restoredState?.bottomTab?.let { BottomTab.valueOf(it) } ?: BottomTab.TERMINAL) }
    var totalWidth         by remember { mutableFloatStateOf(1080f) }
    var totalHeight        by remember { mutableFloatStateOf(1920f) }
    var sidePanelWidth     by remember { mutableFloatStateOf(280f) }
    var bottomPanelHeight  by remember { mutableFloatStateOf(300f) }
    var aiPanelWidth       by remember { mutableFloatStateOf(300f) }
    var openMenuBar        by remember { mutableStateOf<String?>(null) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showColorTheme     by remember { mutableStateOf(false) }
    var showFindBar        by remember { mutableStateOf(false) }
    var findQuery          by remember { mutableStateOf("") }
    var replaceQuery       by remember { mutableStateOf("") }
    var showReplaceRow     by remember { mutableStateOf(false) }
    var showMoreMenu       by remember { mutableStateOf(false) }
    var showPersonMenu     by remember { mutableStateOf(false) }
    var showChatPanel      by remember { mutableStateOf(false) }
    var chatInput          by remember { mutableStateOf("") }
    var terminalCommandToRun by remember { mutableStateOf<String?>(null) }
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
    var showNotifDrawer by remember { mutableStateOf(false) }
    // Connectors hub (replaces Person menu)
    var showConnectorsSheet by remember { mutableStateOf(false) }
    val terminalEnhancements = remember { TerminalEnhancementManager(context) }
    var terminalTheme by remember { mutableStateOf(terminalEnhancements.currentTheme()) }
    var showTerminalThemePicker by remember { mutableStateOf(false) }
    val debugInput = remember { mutableStateOf("") }
    val debugMessages = remember { mutableStateListOf("Debugger ready. Press Run to start.") }
    var cursorLine         by remember { mutableStateOf(1) }
    var cursorCol          by remember { mutableStateOf(1) }
    var editorFontSize     by remember(projectId, restoredState) { mutableStateOf(restoredState?.editorFontSize ?: 13) }
    val editorTabs         = remember(projectId) { mutableStateListOf<String>() }
    var activeEditorTab    by remember(projectId, restoredState) { mutableStateOf(restoredState?.activeFilePath) }
    var keyboardInsert     by remember { mutableStateOf<((String) -> Unit)?>(null) }

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
            "Problems"           -> { showBottomPanel = true; activeBottomTab = BottomTab.PROBLEMS }
            "Output"             -> { showBottomPanel = true; activeBottomTab = BottomTab.OUTPUT }
            "New Terminal"       -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
            "Find"               -> { showFindBar = true; showReplaceRow = false }
            "Replace"            -> { showFindBar = true; showReplaceRow = true }
            "Find in Files"      -> activePanel = SidePanel.SEARCH
            "Go to File"         -> showCommandPalette = true
            "Change Color Theme" -> showColorTheme = true
            "Zoom In"            -> editorFontSize = (editorFontSize + 1).coerceAtMost(24)
            "Zoom Out"           -> editorFontSize = (editorFontSize - 1).coerceAtLeast(8)
            "Exit"               -> onBack()
            "About Visual Node Code"-> showNotification("Visual Node Code — VS Code for mobile", "info")
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
            .onGloballyPositioned { totalWidth = it.size.width.toFloat(); totalHeight = it.size.height.toFloat() }
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top Bar (VS Code style)
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFF8F8F8))
                    .border(1.dp, DividerColor, RoundedCornerShape(0.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: back button
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.KeyboardArrowUp, null, tint = TabTextInactive,
                    modifier = Modifier.size(20.dp).clickable { onBack() })
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
                        Text("Workspace", fontSize = 13.sp, color = TabTextInactive, maxLines = 1)
                    }
                }
                // Right: action icons
                Icon(Icons.Default.Computer, null, tint = TabTextInactive,
                    modifier = Modifier.size(20.dp).clickable { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp).clickable { handleMenuAction("Run Program") })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.VerticalSplit, null, tint = Color(0xFF007ACC),
                    modifier = Modifier.size(20.dp).clickable { showBottomPanel = true; activeBottomTab = BottomTab.SPLIT })
                Spacer(Modifier.width(8.dp))

                Box(
                    Modifier
                        .background(if (showChatPanel) Color(0xFF007ACC) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable { showChatPanel = !showChatPanel }
                        .padding(4.dp)
                ) {
                    Icon(Icons.Default.Chat, null, tint = if (showChatPanel) Color.White else TabTextInactive, modifier = Modifier.size(20.dp))
                }
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

            // Menu bar removed — all actions accessible via command palette

            // ── Main body
            Row(Modifier.weight(1f).fillMaxWidth()) {

                // Activity Bar
                Column(
                    Modifier.width(48.dp).fillMaxHeight().background(ActivityBarBg)
                        .border(1.dp, DividerColor, RoundedCornerShape(0.dp)).padding(end = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf(
                        SidePanel.EXPLORER to Icons.Default.Description,
                        SidePanel.SEARCH   to Icons.Default.Search,
                        SidePanel.GIT      to Icons.Default.AccountTree,
                        SidePanel.RUN      to Icons.Default.BugReport,
                        SidePanel.EXTENSIONS to Icons.Default.Extension,
                    ).forEach { (panel, icon) ->
                        val isActive = activePanel == panel
                        Box(
                            Modifier.fillMaxWidth().height(48.dp)
                                .clickable { activePanel = if (activePanel == panel) null else panel },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isActive) Box(Modifier.width(2.dp).height(24.dp).align(Alignment.CenterStart).background(Color(0xFF007ACC)))
                            Icon(icon, null, tint = if (isActive) ActivityBarIconActive else ActivityBarIcon, modifier = Modifier.size(24.dp))
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Connectors hub (GitHub + SSH + Services)
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showPersonMenu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AccountCircle, null, tint = ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showGearMenu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, null, tint = ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                                    }

                // Side Panel
                if (activePanel != null) {
                    val spWidth = with(density) { sidePanelWidth.toDp() }.coerceIn(150.dp, 500.dp)
                    Column(Modifier.width(spWidth).fillMaxHeight().background(BgColor)) {
                        when (activePanel) {
                            SidePanel.EXPLORER -> ExplorerSidePanel(
                                onOpenFile = { path ->
                                    if (!editorTabs.contains(path)) editorTabs.add(path)
                                    activeEditorTab = path
                                    activePanel = null
                                    showNotification("Opened ${path.substringAfterLast("/")}", "success")
                                },
                                onMoreMenu = { showExplorerMore = true },
                                onOpenInTerminal = { path ->
                                    showBottomPanel = true
                                    activeBottomTab = BottomTab.TERMINAL
                                    terminalCommandToRun = "cd \"$path\"\r"
                                    showNotification("Opened terminal at workspace path", "success")
                                },
                            )
                            SidePanel.SEARCH     -> SearchPanel()
                            SidePanel.GIT        -> GitSidePanel()
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

                // Editor Column + right Chat Panel
                val editorWeight = if (showChatPanel) 0.55f else 1f
                Column(Modifier.weight(editorWeight).fillMaxHeight()) {

                    // Editor tab bar
                    if (editorTabs.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().height(35.dp).background(TabBarBg)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            editorTabs.forEach { tab ->
                                val isActive = tab == activeEditorTab
                                Column(Modifier.clickable { activeEditorTab = tab }.background(if (isActive) TabActiveBg else TabInactiveBg)) {
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

                    // Quick actions row removed — Run/Debug/Terminal/Split moved to menu bar

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
                                    OutlinedButton(onClick = {}, modifier = Modifier.height(36.dp)) { Text("Replace", fontSize = 11.sp) }
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedButton(onClick = {}, modifier = Modifier.height(36.dp)) { Text("All", fontSize = 11.sp) }
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
                                openFilePath    = activeEditorTab,
                                fontSize        = editorFontSize,
                                onInsertRequest = { fn -> keyboardInsert = fn },
                                onCursorChange  = { line, col -> cursorLine = line; cursorCol = col },
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

                    // Bottom Panel
                    if (showBottomPanel) {
                        Box(
                            Modifier.fillMaxWidth().height(4.dp).background(DividerColor)
                                .pointerInput(Unit) {
                                    detectDragGestures { _, dragAmount ->
                                        val nh = bottomPanelHeight - dragAmount.y
                                        if (nh < 60f) showBottomPanel = false
                                        else bottomPanelHeight = nh.coerceIn(60f, totalHeight * 0.92f)
                                    }
                                }
                        )
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFFF3F3F3)).height(22.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BottomTab.entries.forEach { tab ->
                                val isActive = tab == activeBottomTab
                                Box(
                                    Modifier.clickable { activeBottomTab = tab }
                                        .background(if (isActive) Color(0xFFDCEAFB) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .border(if (isActive) 1.dp else 0.dp, if (isActive) Color(0xFF007ACC) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(tab.name, fontSize = 10.sp,
                                        color = if (isActive) Color(0xFF007ACC) else Color(0xFF717171),
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Spacer(Modifier.weight(1f))

                            Icon(Icons.Default.OpenInFull, null, tint = TabTextInactive,
                                modifier = Modifier.size(16.dp).clickable {
                                    bottomPanelHeight = if (bottomPanelHeight > totalHeight * 0.5f) 260f else totalHeight * 0.75f
                                })
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Close, null, tint = TabTextInactive,
                                modifier = Modifier.size(16.dp).clickable { showBottomPanel = false })
                            Spacer(Modifier.width(4.dp))
                        }
                        HorizontalDivider(color = DividerColor)
                        val bh = with(density) { bottomPanelHeight.toDp() }.coerceIn(60.dp, 600.dp)
                        Box(Modifier.fillMaxWidth().height(bh).background(PanelBg)) {
                            when (activeBottomTab) {
                                BottomTab.TERMINAL -> TerminalPane(
                                    initialCommand = terminalCommandToRun,
                                    onCommandConsumed = { terminalCommandToRun = null },
                                    externalState = sharedTerminalState,
                                )
                                BottomTab.PROBLEMS -> ProblemsPanel()
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
                                )
                                BottomTab.PORTS    -> PortsPanel()
                                BottomTab.SPLIT    -> SplitTerminalPanel(sharedState = sharedTerminalState)
                                BottomTab.PREVIEW  -> PreviewPane(
                                    activeFilePath = activeEditorTab ?: "",
                                )
                            }
                        }
                    }

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
                            TerminalPane(externalState = sharedTerminalState)
                        }
                    }
                }

            } // end main Row (editor + optional chat panel)

        // Simple overlay menus

        // ── Connectors Hub Sheet ─────────────────────────────────────────
        if (showConnectorsSheet) {
            ConnectorsHubSheet(onDismiss = { showConnectorsSheet = false })
        }


        if (showPanelMenu) { Box(Modifier.fillMaxSize().clickable { showPanelMenu = false }) { Card(Modifier.align(Alignment.BottomEnd).padding(bottom = 90.dp, end = 8.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { val items = when (activeBottomTab) { BottomTab.TERMINAL -> listOf("New Terminal","Split Terminal","Kill Terminal","Clear"); BottomTab.OUTPUT -> listOf("Clear Output","Copy All"); BottomTab.PROBLEMS -> listOf("Filter","Show Errors Only"); BottomTab.DEBUG -> listOf("Clear Console","Copy All"); BottomTab.PORTS -> listOf("Forward Port","Stop Forwarding"); BottomTab.SPLIT -> listOf("New Terminal","Pin Split","Swap Panels","Kill Split"); BottomTab.PREVIEW -> listOf("Refresh Preview","Open in Browser","HTML Mode","Markdown Mode") }; items.forEach { item -> Row(Modifier.fillMaxWidth().clickable { when (item) { "New Terminal" -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL } }; showPanelMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showExplorerMore) { Box(Modifier.fillMaxSize().clickable { showExplorerMore = false }) { Card(Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 48.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("New File","New Folder","Refresh","Collapse All","Open in Terminal").forEach { item -> Row(Modifier.fillMaxWidth().clickable { showExplorerMore = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }


    // ── First-launch onboarding walkthrough ─────────────────────────────
            // ── VS Code status bar (blue bar at bottom) ──────────────────
            Row(
                Modifier.fillMaxWidth().height(22.dp).background(StatusBarBg).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AccountTree, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("main", fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.weight(1f))
                Text("Ln 1, Col 1", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(8.dp))
                Text("UTF-8", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
            }
    } // end Column

        // ── All overlays — direct children of root Box so they cover full screen ──

        // Copilot Chat Panel
        if (showChatPanel) {
            CopilotChatPanelOverlay(onClose = { showChatPanel = false })
        }

        // Notification Drawer — scrim already in NotificationDrawerOverlay
        if (showNotifDrawer) {
            NotificationDrawerOverlay(
                notifList = notifList,
                onDismiss = { showNotifDrawer = false },
                onClear = { notifList.clear() },
            )
        }

        // Command Palette
        if (showCommandPalette) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable { showCommandPalette = false }
            ) {
                Card(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column {
                        androidx.compose.foundation.text.BasicTextField(
                            value = commandQuery,
                            onValueChange = { commandQuery = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MenuText, fontFamily = FontFamily.Default),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            decorationBox = { inner ->
                                if (commandQuery.isEmpty()) Text("> Type a command or file name…", fontSize = 14.sp, color = MenuText.copy(alpha = 0.4f))
                                inner()
                            },
                            singleLine = true,
                        )
                        HorizontalDivider(color = DividerColor)
                        val filtered = listOf(
                            "New File", "New Folder", "Save File", "Open File",
                            "Toggle Sidebar", "Toggle Terminal", "Select Color Theme",
                            "Go to File", "Find in Files", "Run Program", "Split Terminal",
                        ).filter { commandQuery.isEmpty() || it.contains(commandQuery, ignoreCase = true) }
                        filtered.take(8).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (item == filtered.firstOrNull() && commandQuery.isNotEmpty()) CmdSelectedBg.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { handleMenuAction(item); showCommandPalette = false; commandQuery = "" }
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

        // Person / Account menu
        if (showPersonMenu) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x44000000))
                    .clickable { showPersonMenu = false }
            ) {
                Card(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 52.dp, bottom = 56.dp)
                        .width(220.dp)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = MenuBg),
                    elevation = CardDefaults.cardElevation(8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Signed in as", fontSize = 11.sp, color = MenuText.copy(alpha = 0.5f), modifier = Modifier.padding(8.dp))
                        Text("Wisdom Ijezie", fontSize = 13.sp, color = MenuText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                        listOf("Settings", "Sign Out").forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { showPersonMenu = false }.padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(item, fontSize = 13.sp, color = MenuText)
                            }
                        }
                    }
                }
            }
        }

    } // end root Box
}

@Composable private fun ProblemsPanel() {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROBLEMS", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.FilterList, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
            Text("✓  No problems detected in the workspace.", fontSize = 13.sp, color = Color(0xFF717171))
        }
    }
}

@Composable private fun OutputPanel() {
    val logs = remember { mutableStateListOf("[info]  Visual Node Code started","[info]  Gradle build started","[info]  BUILD SUCCESSFUL","[info]  APK: app-prod-arm64-v8a-debug.apk") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.Delete, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { logs.clear() })
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
            items(logs) { log -> Text(log, fontSize = 12.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable private fun DebugConsolePanel(
    messages: SnapshotStateList<String>,
    input: MutableState<String>,
    onSend: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DEBUG CONSOLE", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF007ACC), modifier = Modifier.size(16.dp).clickable { messages.add("[debug] Run request queued") })
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

@Composable private fun PortsPanel() {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PORTS", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.Add, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
            Text("No forwarded ports. Tap + to forward a local server port.", fontSize = 13.sp, color = Color(0xFF717171))
        }
    }

}

// ConnectorRow moved to ConnectorsHubSheet.kt
