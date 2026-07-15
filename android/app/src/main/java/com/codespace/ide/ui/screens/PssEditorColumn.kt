package com.codespace.ide.ui.screens

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.TerminalState
import com.codespace.ide.ui.panes.*

/**
 * Editor Column + Split Terminal + Chat Panel composable.
 * Extracted from ProjectShellScreen to reduce the main function's DEX register count,
 * fixing: VerifyError copy-cat1 v22<-v293 type=High-half Constant (classes12.dex).
 *
 * State vars that are mutated by this composable are passed as MutableState<T> so the
 * body can use `var X by XMs` delegation — exactly the same read/write semantics as
 * the original inlined code, with zero logic changes needed.
 */
@Composable
internal fun PssEditorColumn(
    projectId: String,
    context: android.content.Context,
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    editorTabs: SnapshotStateList<String>,
    heavyPanesReady: Boolean,
    wordWrap: Boolean,
    showInlayHints: Boolean,
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
    activeBottomTabMs: androidx.compose.runtime.MutableState<BottomTab>,
    activeEditorTabMs: androidx.compose.runtime.MutableState<String?>,
    aiPanelWidthMs: androidx.compose.runtime.MutableState<Float>,
    bottomPanelHeightMs: androidx.compose.runtime.MutableState<Float>,
    bottomPanelMaximizedMs: androidx.compose.runtime.MutableState<Boolean>,
    bottomPanelPrevHeightMs: androidx.compose.runtime.MutableState<Float>,
    breadcrumbNavDirMs: androidx.compose.runtime.MutableState<String?>,
    cursorColMs: androidx.compose.runtime.MutableState<Int>,
    cursorLineMs: androidx.compose.runtime.MutableState<Int>,
    editorFontSizeMs: androidx.compose.runtime.MutableState<Int>,
    findQueryMs: androidx.compose.runtime.MutableState<String>,
    isDraggingBottomPanelMs: androidx.compose.runtime.MutableState<Boolean>,
    keyboardInsertMs: androidx.compose.runtime.MutableState<((String) -> Unit)?>,
    previewPortMs: androidx.compose.runtime.MutableState<Int?>,
    replaceQueryMs: androidx.compose.runtime.MutableState<String>,
    scrollTargetLineMs: androidx.compose.runtime.MutableState<Int>,
    showBottomPanelMs: androidx.compose.runtime.MutableState<Boolean>,
    showChatPanelMs: androidx.compose.runtime.MutableState<Boolean>,
    showFileSearchMs: androidx.compose.runtime.MutableState<Boolean>,
    showFindBarMs: androidx.compose.runtime.MutableState<Boolean>,
    showReplaceRowMs: androidx.compose.runtime.MutableState<Boolean>,
    showSplitTerminalMs: androidx.compose.runtime.MutableState<Boolean>,
    showSymbolSearchMs: androidx.compose.runtime.MutableState<Boolean>,
    splitTerminalWidthMs: androidx.compose.runtime.MutableState<Float>,
    terminalCommandToRunMs: androidx.compose.runtime.MutableState<String?>,
) {
    val density = LocalDensity.current
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
    Column(Modifier.weight(1f).fillMaxHeight()) {

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
