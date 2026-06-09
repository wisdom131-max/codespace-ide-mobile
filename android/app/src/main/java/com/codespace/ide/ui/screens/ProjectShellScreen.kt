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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
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
private enum class BottomTab  { PROBLEMS, OUTPUT, TERMINAL, DEBUG, PORTS }

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
        MenuAction("Toggle Sidebar","Ctrl+B"), MenuAction("Toggle AI Panel"),
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
        MenuAction("About CodeSpace IDE"),
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
) {
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
    var activePanel        by remember { mutableStateOf<SidePanel?>(null) }
    var showBottomPanel    by remember { mutableStateOf(true) }
    var showAiPanel        by remember { mutableStateOf(false) }

    var activeBottomTab    by remember { mutableStateOf(BottomTab.TERMINAL) }
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
    var showGearMenu       by remember { mutableStateOf(false) }
    var showRunMenu        by remember { mutableStateOf(false) }
    var showPanelMenu      by remember { mutableStateOf(false) }
    var showExplorerMore   by remember { mutableStateOf(false) }
    var commandQuery       by remember { mutableStateOf("") }
    var commandTab         by remember { mutableStateOf("Commands") }
    var notificationMsg    by remember { mutableStateOf<String?>(null) }
    var notificationType   by remember { mutableStateOf("info") }
    var cursorLine         by remember { mutableStateOf(1) }
    var cursorCol          by remember { mutableStateOf(1) }
    var editorFontSize     by remember { mutableStateOf(13) }
    val editorTabs         = remember { mutableStateListOf<String>() }
    var activeEditorTab    by remember { mutableStateOf<String?>(null) }
    var keyboardInsert     by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(notificationMsg) {
        if (notificationMsg != null) { kotlinx.coroutines.delay(3000); notificationMsg = null }
    }

    fun showNotification(msg: String, type: String = "info") { notificationMsg = msg; notificationType = type }

    fun handleMenuAction(action: String) {
        openMenuBar = null
        when (action) {
            "Explorer"           -> activePanel = SidePanel.EXPLORER
            "Search"             -> activePanel = SidePanel.SEARCH
            "Source Control"     -> activePanel = SidePanel.GIT
            "Run & Debug"        -> activePanel = SidePanel.RUN
            "Extensions"         -> activePanel = SidePanel.EXTENSIONS
            "Toggle Sidebar"     -> activePanel = if (activePanel == null) SidePanel.EXPLORER else null
            "Toggle AI Panel"    -> showAiPanel = !showAiPanel
            "Terminal"           -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
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
            "About CodeSpace IDE"-> showNotification("CodeSpace IDE — VS Code for mobile", "info")
            "Run Program", "Start Debugging" -> {
                showBottomPanel = true; activeBottomTab = BottomTab.DEBUG
                showNotification("Starting debug session…", "info")
            }
            "Save" -> showNotification("File saved ✓", "success")
            else   -> {}
        }
    }

    Box(
        Modifier.fillMaxSize().background(BgColor)
            .onGloballyPositioned { totalWidth = it.size.width.toFloat(); totalHeight = it.size.height.toFloat() }
    ) {
        Column(Modifier.fillMaxSize().padding(top = with(androidx.compose.ui.platform.LocalDensity.current) {
            androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp()
        })) {

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
                Icon(Icons.Default.AutoAwesome, null,
                    tint = if (showAiPanel) ActivityBarIconActive else TabTextInactive,
                    modifier = Modifier.size(20.dp).clickable { showAiPanel = !showAiPanel })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Notifications, null, tint = TabTextInactive, modifier = Modifier.size(20.dp))
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
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showMoreMenu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MoreHoriz, null, tint = ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showAiPanel = !showAiPanel }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, null, tint = if (showAiPanel) ActivityBarIconActive else ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showPersonMenu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(48.dp).clickable { showGearMenu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, null, tint = ActivityBarIcon, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
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
                            )
                            SidePanel.SEARCH     -> SearchPanel()
                            SidePanel.GIT        -> GitSidePanel()
                            SidePanel.RUN        -> RunDebugPanel(onMoreMenu = { showRunMenu = true })
                            SidePanel.EXTENSIONS -> ExtensionsPanel()
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

                // Editor Column
                Column(Modifier.weight(1f).fillMaxHeight()) {

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
                                    Icon(Icons.Default.Code, null, tint = Color(0xFFDDDDDD), modifier = Modifier.size(64.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text("CodeSpace IDE", fontSize = 28.sp, color = Color(0xFFDDDDDD), fontWeight = FontWeight.Light)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Open Explorer → tap a file to start", fontSize = 13.sp, color = Color(0xFFCCCCCC))
                                    Spacer(Modifier.height(24.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        listOf("Explorer" to Icons.Default.Description, "Terminal" to Icons.Default.Computer, "AI Chat" to Icons.Default.AutoAwesome).forEach { (lbl, ico) ->
                                            OutlinedButton(onClick = {
                                                when (lbl) {
                                                    "Explorer" -> activePanel = SidePanel.EXPLORER
                                                    "Terminal" -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL }
                                                    "AI Chat"  -> showAiPanel = true
                                                }
                                            }) {
                                                Icon(ico, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(lbl, fontSize = 12.sp)
                                            }
                                        }
                                    }
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
                            SPECIAL_KEYS.forEach { key ->
                                Box(
                                    Modifier.height(32.dp).defaultMinSize(minWidth = 36.dp)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .border(1.dp, DividerColor, RoundedCornerShape(4.dp))
                                        .clickable { keyboardInsert?.invoke(key) }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(key, fontSize = 13.sp, color = TabText, fontFamily = FontFamily.Monospace) }
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
                                        else bottomPanelHeight = nh.coerceIn(60f, totalHeight * 0.75f)
                                    }
                                }
                        )
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFFF3F3F3)).height(28.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BottomTab.entries.forEach { tab ->
                                val isActive = tab == activeBottomTab
                                Box(
                                    Modifier.clickable { activeBottomTab = tab }
                                        .background(if (isActive) Color(0xFFDCEAFB) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .border(if (isActive) 1.dp else 0.dp, if (isActive) Color(0xFF007ACC) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(tab.name, fontSize = 11.sp,
                                        color = if (isActive) Color(0xFF007ACC) else Color(0xFF717171),
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.MoreHoriz, null, tint = TabTextInactive,
                                modifier = Modifier.size(16.dp).clickable { showPanelMenu = true })
                            Spacer(Modifier.width(8.dp))
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
                                BottomTab.TERMINAL -> TerminalPane()
                                BottomTab.PROBLEMS -> ProblemsPanel()
                                BottomTab.OUTPUT   -> OutputPanel()
                                BottomTab.DEBUG    -> DebugConsolePanel()
                                BottomTab.PORTS    -> PortsPanel()
                            }
                        }
                    }

                } // end editor Column

                // AI Panel
                if (showAiPanel) {
                    val aw = with(density) { aiPanelWidth.toDp() }.coerceIn(200.dp, 500.dp)
                    Box(Modifier.width(4.dp).fillMaxHeight().background(DividerColor)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                aiPanelWidth = (aiPanelWidth - dragAmount.x).coerceIn(150f, totalWidth * 0.5f)
                            }
                        })
                    Column(Modifier.width(aw).fillMaxHeight().background(BgColor)) {
                        Row(Modifier.fillMaxWidth().background(TabBarBg).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = ActivityBarIconActive, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("AI CHAT", fontSize = 11.sp, color = SectionHeaderText, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Close, null, tint = TabTextInactive,
                                modifier = Modifier.size(16.dp).clickable { showAiPanel = false })
                        }
                        HorizontalDivider(color = DividerColor)
                        AiAssistantPane(tokenStore = tokenStore)
                    }
                }

            } // end main Row

            // Status Bar
            HorizontalDivider(color = DividerColor)
            Row(
                Modifier.fillMaxWidth().height(22.dp).background(StatusBarBg).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AccountTree, null, tint = Color(0xFF424242), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text("main", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.SwapHoriz, null, tint = Color(0xFF424242),
                    modifier = Modifier.size(14.dp).clickable { showBottomPanel = !showBottomPanel })
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Close, null, tint = Color(0xFF424242), modifier = Modifier.size(12.dp))
                Text(" 0", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Warning, null, tint = Color(0xFF424242), modifier = Modifier.size(12.dp))
                Text(" 0", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (activeEditorTab != null) {
                    Text("Ln $cursorLine, Col $cursorCol", color = Color(0xFF424242), fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(activeEditorTab?.substringAfterLast(".")?.let {
                    when (it.lowercase()) {
                        "kt","kts" -> "Kotlin"; "py" -> "Python"; "js" -> "JavaScript"
                        "ts" -> "TypeScript"; "java" -> "Java"; "json" -> "JSON"
                        "md" -> "Markdown"; "xml" -> "XML"; "sh" -> "Shell"
                        else -> it.uppercase()
                    }
                } ?: "Plain Text", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("UTF-8", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("LF", color = Color(0xFF424242), fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("${editorFontSize}px", color = Color(0xFF424242), fontSize = 11.sp,
                    modifier = Modifier.clickable { editorFontSize = 13 })
                Spacer(Modifier.width(4.dp))
            }

        } // end outer Column

        // Notification
        if (notificationMsg != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .background(when (notificationType) { "success" -> Color(0xFF4CAF50); "error" -> Color(0xFFF44336); "warning" -> Color(0xFFFF9800); else -> Color(0xFF323232) }, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (notificationType) { "success" -> Icons.Default.Check; "error" -> Icons.Default.Cancel; "warning" -> Icons.Default.Warning; else -> Icons.Default.Info }, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(notificationMsg!!, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // Menu bar dropdowns
        if (openMenuBar != null) {
            Box(Modifier.fillMaxSize().clickable { openMenuBar = null }.padding(top = 64.dp)) {
                val menuIndex = MENU_BAR.indexOfFirst { it.label == openMenuBar }
                val menu = MENU_BAR.getOrNull(menuIndex)
                if (menu != null) {
                    Card(
                        Modifier.padding(start = (48 + menuIndex * 58).dp).width(230.dp),
                        colors = CardDefaults.cardColors(containerColor = MenuBg),
                        elevation = CardDefaults.cardElevation(8.dp), shape = RoundedCornerShape(4.dp),
                    ) {
                        menu.items.forEach { action ->
                            if (action.divider) {
                                HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                            } else {
                                Row(
                                    Modifier.fillMaxWidth().clickable { handleMenuAction(action.label) }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(action.label, fontSize = 13.sp, color = MenuText, modifier = Modifier.weight(1f))
                                    if (action.shortcut.isNotEmpty()) Text(action.shortcut, fontSize = 11.sp, color = TabTextInactive)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Command Palette
        if (showCommandPalette) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showCommandPalette = false; commandQuery = "" }, contentAlignment = Alignment.TopCenter) {
                Column(Modifier.padding(top = 80.dp).fillMaxWidth(0.95f).background(MenuBg, RoundedCornerShape(8.dp)).border(1.dp, MenuBorder, RoundedCornerShape(8.dp)).clickable(enabled = false) {}) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        listOf("Commands", "Files", "Settings", "Symbols").forEach { tab ->
                            val isA = commandTab == tab
                            Box(Modifier.clickable { commandTab = tab }.background(if (isA) Color(0xFF0060C0) else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(tab, fontSize = 13.sp, color = if (isA) Color.White else TabTextInactive)
                            }
                        }
                    }
                    OutlinedTextField(value = commandQuery, onValueChange = { commandQuery = it },
                        placeholder = { Text("> Type command…", fontSize = 14.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
                    HorizontalDivider(color = DividerColor)
                    val allCmds by remember(commandQuery) { derivedStateOf { listOf(
                        "Explorer","Search","Source Control","Run & Debug","Extensions",
                        "Terminal","Problems","Output","Toggle Sidebar","Toggle AI Panel",
                        "New File","Save","Find","Replace","Change Color Theme","Zoom In","Zoom Out",
                        "Run Program","Git: Commit","Git: Push","Git: Pull","Format Document",
                        "Keyboard Shortcuts","About CodeSpace IDE",
                    ).filter { commandQuery.isBlank() || it.contains(commandQuery, ignoreCase = true) } } }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(allCmds) { cmd ->
                            val idx = allCmds.indexOf(cmd)
                            Row(Modifier.fillMaxWidth().background(if (idx == 0) CmdSelectedBg else Color.Transparent)
                                .clickable { handleMenuAction(cmd); showCommandPalette = false; commandQuery = "" }
                                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(cmd, fontSize = 13.sp, color = if (idx == 0) CmdSelectedText else MenuText, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Color theme picker
        if (showColorTheme) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showColorTheme = false }, contentAlignment = Alignment.TopCenter) {
                Column(Modifier.padding(top = 80.dp).fillMaxWidth(0.9f).background(MenuBg, RoundedCornerShape(8.dp)).border(1.dp, MenuBorder, RoundedCornerShape(8.dp)).clickable(enabled = false) {}) {
                    Text("Color Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                    HorizontalDivider(color = DividerColor)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelectTheme("Light (Default)"); showColorTheme = false }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) { Text("↺ Reset to Default", fontSize = 13.sp, color = Color(0xFF007ACC)) }
                    HorizontalDivider(color = DividerColor)
                    listOf(
                        "Light (Default)" to false,
                        "Eye Care" to false,
                        "Dark (Default)" to true,
                        "AMOLED Black" to true,
                        "Dracula" to true,
                        "Nord" to true,
                        "Tokyo Night" to true,
                        "One Dark Pro" to true,
                        "Monokai" to true,
                        "Catppuccin" to true,
                        "GitHub Dark" to true,
                        "GitHub Light" to false,
                        "Solarized Light" to false,
                    ).forEach { (name, dark) ->
                        val isActive = name == currentTheme
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isActive) Color(0xFF0060C0).copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelectTheme(name); showColorTheme = false }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = 13.sp, color = if (isActive) Color(0xFF0060C0) else MenuText,
                                fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                            Text(if (dark) "🌙" else "☀", fontSize = 11.sp, color = TabTextInactive)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Simple overlay menus
        if (showMoreMenu) { Box(Modifier.fillMaxSize().clickable { showMoreMenu = false }) { Card(Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 48.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("Run & Debug","Extensions","Remote Explorer","Timeline").forEach { item -> Row(Modifier.fillMaxWidth().clickable { handleMenuAction(item); showMoreMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showPersonMenu) { Box(Modifier.fillMaxSize().clickable { showPersonMenu = false }) { Card(Modifier.align(Alignment.BottomStart).padding(bottom = 110.dp, start = 4.dp).width(220.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("Sign in with GitHub","Sign in with Microsoft","Manage Accounts").forEach { item -> Row(Modifier.fillMaxWidth().clickable { showPersonMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showGearMenu) { Box(Modifier.fillMaxSize().clickable { showGearMenu = false }) { Card(Modifier.align(Alignment.BottomStart).padding(bottom = 60.dp, start = 4.dp).width(220.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("Settings","Color Theme","Keyboard Shortcuts","Extensions").forEach { item -> Row(Modifier.fillMaxWidth().clickable { when (item) { "Color Theme" -> { showColorTheme = true; showGearMenu = false }; else -> showGearMenu = false } }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showRunMenu) { Box(Modifier.fillMaxSize().clickable { showRunMenu = false }) { Card(Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 48.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("Run Program","Start Debugging","Stop","Restart").forEach { item -> Row(Modifier.fillMaxWidth().clickable { handleMenuAction(item); showRunMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showPanelMenu) { Box(Modifier.fillMaxSize().clickable { showPanelMenu = false }) { Card(Modifier.align(Alignment.BottomEnd).padding(bottom = 90.dp, end = 8.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { val items = when (activeBottomTab) { BottomTab.TERMINAL -> listOf("New Terminal","Split Terminal","Kill Terminal","Clear"); BottomTab.OUTPUT -> listOf("Clear Output","Copy All"); BottomTab.PROBLEMS -> listOf("Filter","Show Errors Only"); BottomTab.DEBUG -> listOf("Clear Console","Copy All"); BottomTab.PORTS -> listOf("Forward Port","Stop Forwarding") }; items.forEach { item -> Row(Modifier.fillMaxWidth().clickable { when (item) { "New Terminal" -> { showBottomPanel = true; activeBottomTab = BottomTab.TERMINAL } }; showPanelMenu = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }
        if (showExplorerMore) { Box(Modifier.fillMaxSize().clickable { showExplorerMore = false }) { Card(Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 48.dp).width(200.dp), colors = CardDefaults.cardColors(containerColor = MenuBg), elevation = CardDefaults.cardElevation(8.dp)) { listOf("New File","New Folder","Refresh","Collapse All","Open in Terminal").forEach { item -> Row(Modifier.fillMaxWidth().clickable { showExplorerMore = false }.padding(16.dp)) { Text(item, fontSize = 13.sp, color = MenuText) } } } } }


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
    val logs = remember { mutableStateListOf("[info]  CodeSpace IDE started","[info]  Gradle build started","[info]  BUILD SUCCESSFUL","[info]  APK: app-prod-arm64-v8a-debug.apk") }
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

@Composable private fun DebugConsolePanel() {
    val messages = remember { mutableStateListOf("Debugger ready. Press Run to start.") }
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DEBUG CONSOLE", fontSize = 11.sp, color = Color(0xFF717171), modifier = Modifier.weight(1f))
            Icon(Icons.Default.Delete, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp).clickable { messages.clear() })
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        LazyColumn(Modifier.weight(1f).padding(8.dp)) {
            items(messages) { msg -> Text(msg, fontSize = 12.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(">", fontSize = 13.sp, color = Color(0xFF424242), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
            androidx.compose.foundation.text.BasicTextField(value = input, onValueChange = { input = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF333333)), modifier = Modifier.weight(1f), singleLine = true)
            Icon(Icons.Default.Send, null, tint = Color(0xFF007ACC), modifier = Modifier.size(18.dp).clickable { if (input.isNotBlank()) { messages.add("> $input"); input = "" } })
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
