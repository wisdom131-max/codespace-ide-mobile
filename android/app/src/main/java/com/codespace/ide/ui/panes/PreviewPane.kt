package com.codespace.ide.ui.panes

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import android.annotation.SuppressLint
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.UserAgentMetadata
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.codespace.ide.domain.Language
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONArray
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.*

// ─────────────────────────────────────────────────────────────────────────────
// PreviewPane — live preview for HTML/CSS/JS, Markdown, SVG, and local servers
// Shown as BottomTab.PREVIEW in ProjectShellScreen
// ─────────────────────────────────────────────────────────────────────────────

enum class PreviewMode(val label: String) {
    HTML("HTML"),
    MARKDOWN("Markdown"),
    SVG("SVG"),
    BROWSER("Browser"),
    REMOTION("Remotion"),
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared preview state — lifted up to ProjectShellScreen so switching to a
// different bottom tab (Terminal, Problems, etc.) and back doesn't reset the
// active sub-tab or the connected URL. Also fixes Browser and Remotion sharing
// one address bar (typing a port in one used to "mirror" into the other because
// they were literally the same two variables) — each mode now gets its own.
// ─────────────────────────────────────────────────────────────────────────────
class PreviewState(initialMode: PreviewMode) {
    var activeMode by androidx.compose.runtime.mutableStateOf(initialMode)
    var browserUrl by androidx.compose.runtime.mutableStateOf("http://localhost:3000")
    var browserInput by androidx.compose.runtime.mutableStateOf("http://localhost:3000")
    var remotionUrl by androidx.compose.runtime.mutableStateOf("http://localhost:3000")
    var remotionInput by androidx.compose.runtime.mutableStateOf("http://localhost:3000")
    // P32-BROWSER: track back/forward nav state so the back button can be enabled/disabled
    var canGoBack by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun rememberPreviewState(): PreviewState {
    return remember { PreviewState(PreviewMode.HTML) }
}

private val BgDark      = Color(0xFF1E1E1E)
private val Surface     = Color(0xFF252526)
private val Border      = Color(0xFF3C3C3C)
private val TextPrimary = Color(0xFFD4D4D4)
private val TextMuted   = Color(0xFF717171)
private val Accent      = Color(0xFF007ACC)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewPane(
    activeFilePath: String = "",
    initialPort: Int? = null,
    externalState: PreviewState? = null,
    projectId: String? = null,
    onClosePreview: () -> Unit = {},
) {
    // Rotation fix (#8): key the fullscreen Dialog on orientation so it gets a fresh,
    // correctly-sized window on rotation instead of being stuck at the pre-rotation size.
    val orientation = LocalConfiguration.current.orientation
    val context = LocalContext.current

    // PhaseX: Start LivePreviewServer when a project is active
    val projectRootPath = com.codespace.ide.util.ProjectPathResolver.resolveProjectRoot(context, projectId)
    LaunchedEffect(projectId) {
        if (projectRootPath != null && java.io.File(projectRootPath).exists()) {
            com.codespace.ide.preview.LivePreviewServer.start(projectRootPath)
        }
    }
    DisposableEffect(projectId) {
        onDispose {
            // Stop server when leaving preview or switching projects
            com.codespace.ide.preview.LivePreviewServer.stop()
        }
    }

    // Refresh trigger — increments when the user taps the refresh button to force a
    // re-read from disk. Without this, produceState only re-reads when activeFilePath
    // changes, so editing the file in the editor + pressing refresh shows stale content.
    var refreshTrigger by remember { mutableStateOf(0) }
    // P-LIVE: Read file content from disk whenever path changes OR refresh is triggered.
    // Also polls file lastModified every 500ms so edits made in the editor (which
    // writes to disk on every keystroke) show up in the preview instantly — no
    // manual refresh button press needed.
    val content by produceState(initialValue = "", key1 = activeFilePath, key2 = refreshTrigger) {
        if (activeFilePath.isNotBlank()) {
            var lastMod = 0L
            value = try { java.io.File(activeFilePath).readText() } catch (_: Exception) { "" }
            lastMod = try { java.io.File(activeFilePath).lastModified() } catch (_: Exception) { 0L }
            // Poll for file changes — editor writes to disk on every keystroke, so
            // we pick up changes within 500ms without needing a manual refresh.
            while (true) {
                kotlinx.coroutines.delay(500)
                val currentMod = try { java.io.File(activeFilePath).lastModified() } catch (_: Exception) { 0L }
                if (currentMod != lastMod) {
                    lastMod = currentMod
                    value = try { java.io.File(activeFilePath).readText() } catch (_: Exception) { "" }
                }
            }
        } else {
            value = ""
        }
    }
    val language = remember(activeFilePath) {
        when {
            activeFilePath.endsWith(".html") || activeFilePath.endsWith(".htm") -> Language.HTML
            activeFilePath.endsWith(".md") || activeFilePath.endsWith(".markdown") -> Language.MARKDOWN
            activeFilePath.endsWith(".svg") -> Language.XML
            activeFilePath.endsWith(".css") || activeFilePath.endsWith(".scss") -> Language.CSS
            activeFilePath.endsWith(".js") || activeFilePath.endsWith(".ts") -> Language.JAVASCRIPT
            else -> Language.HTML
        }
    }
    // Auto-detect mode from file language
    val defaultModeForFile = when (language) {
        Language.HTML                          -> PreviewMode.HTML
        Language.MARKDOWN                      -> PreviewMode.MARKDOWN
        Language.XML                           -> if (activeFilePath.endsWith(".svg")) PreviewMode.SVG else PreviewMode.HTML
        Language.CSS, Language.JAVASCRIPT,
        Language.TYPESCRIPT                    -> PreviewMode.HTML
        else                                   -> PreviewMode.HTML
    }

    // sharedState lives above this composable (in ProjectShellScreen), so switching bottom
    // tabs away and back doesn't reset the active mode or the connected URL. Reads/writes
    // below go straight through sharedState.X (Kotlin doesn't allow custom accessors on
    // local variables, so we reference the shared property directly rather than aliasing it).
    val sharedState = externalState ?: rememberPreviewState()
    // BUG-FIX (Goodluck report 2026-08-16): defaultModeForFile was computed correctly per
    // file type (HTML/MARKDOWN/SVG) but was DEAD CODE — never applied anywhere. sharedState
    // survives across file switches (by design, so manually switching tabs doesn't reset the
    // user's chosen mode), which meant opening an .svg file after an .html or .md file kept
    // showing the PREVIOUS file's mode (e.g. SVG content rendered through the HTML or
    // Markdown viewer) instead of switching to the SVG viewer. Auto-apply the detected mode
    // whenever the active file path actually changes — the user can still manually switch
    // modes afterwards for that same file if they want a different view.
    LaunchedEffect(activeFilePath) {
        sharedState.activeMode = defaultModeForFile
    }
    var showGuide by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    // P48: Shared WebView instance for Browser/Remotion modes — survives fullscreen
    // toggle without reloading the page. The same WebView is reused in both inline
    // and fullscreen, so scroll position, login state, and video playback are preserved.
    val sharedWebView = remember { mutableStateOf<WebView?>(null) }
    // First time this pane sees a deep-linked port (from the Ports panel), jump straight to
    // Browser mode pointed at it. Only fires on an actual initialPort change, not every
    // recomposition, so it doesn't fight with the user manually switching modes afterwards.
    LaunchedEffect(initialPort) {
        if (initialPort != null) {
            sharedState.activeMode = PreviewMode.BROWSER
            sharedState.browserUrl = "http://localhost:$initialPort"
            sharedState.browserInput = "http://localhost:$initialPort"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // ── Top bar ─────────────────────────────────────────────────────────
        // heightIn(min=) instead of a fixed height() — at larger system font/display scale
        // ("zoom" in device Settings > Display), a fixed height clipped the mode-tab labels
        // and icons instead of growing to fit them.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Mode tabs
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewMode.entries.forEach { mode ->
                    val active = mode == sharedState.activeMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                width = if (active) 1.dp else 0.dp,
                                color = if (active) Accent else Color.Transparent,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable { sharedState.activeMode = mode }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            mode.label,
                            fontSize = 11.sp,
                            color = if (active) Accent else TextMuted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Right controls
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Accent,
                        strokeWidth = 2.dp,
                    )
                }
                // P45-4: Close button to dismiss the preview tab
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close preview",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onClosePreview() }
                )
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "How to use Preview",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { showGuide = true }
                )
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            // Force re-read from disk (editor writes to disk on every keystroke,
                            // but produceState above only re-reads when the path changes, not when
                            // the file content changes). Incrementing refreshTrigger retriggers
                            // produceState, which feeds fresh content into the WebView's update block.
                            refreshTrigger++
                            // Also tell the WebView to reload — covers the case where the content
                            // hasn't changed but the user just wants to re-render (e.g. external CSS).
                            when (sharedState.activeMode) {
                                PreviewMode.BROWSER -> webViewRef?.reload()
                                else -> webViewRef?.reload()
                            }
                        }
                )
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen preview",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp).clickable {
                        // P48: Sync address bar with current URL before going fullscreen
                        // (shared WebView keeps its state — no save/restore needed)
                        webViewRef?.let { wv ->
                            val actualUrl = wv.url ?: ""
                            if (actualUrl.isNotBlank() && actualUrl != "about:blank") {
                                if (sharedState.activeMode == PreviewMode.BROWSER) {
                                    sharedState.browserUrl = actualUrl
                                    sharedState.browserInput = actualUrl
                                } else if (sharedState.activeMode == PreviewMode.REMOTION) {
                                    sharedState.remotionUrl = actualUrl
                                    sharedState.remotionInput = actualUrl
                                }
                            }
                        }
                        isFullscreen = true
                    }
                )
            }
        }

        // ── Browser address bar (BROWSER + REMOTION modes) ─────────────────
        // Compact pill design 2026-07-06 — the default Material3 OutlinedTextField reserves a
        // lot of built-in vertical padding (meant for floating labels), which made this bar look
        // oversized and left dead empty space in the toolbar row. Swapped for a tight, fixed-height
        // pill (matches the STT/Root/Zsh quick-actions row styling elsewhere in the app) that fills
        // the space it actually needs instead of leaving a gap.
        if (sharedState.activeMode == PreviewMode.BROWSER || sharedState.activeMode == PreviewMode.REMOTION) {
            // Browser and Remotion each read/write their OWN url+input pair on sharedState.
            val isRemotion = sharedState.activeMode == PreviewMode.REMOTION
            val currentInput = if (isRemotion) sharedState.remotionInput else sharedState.browserInput
            fun applyInput(v: String) {
                if (isRemotion) sharedState.remotionInput = v else sharedState.browserInput = v
            }
            fun connect() {
                if (isRemotion) sharedState.remotionUrl = sharedState.remotionInput
                else sharedState.browserUrl = sharedState.browserInput
                webViewRef?.loadUrl(currentInput)
            }
            // P32-BROWSER: Compact address bar — reduced height, back button, desktop-mode lock icon.
            // heightIn removed (was causing the oversized bar seen in the screenshot); fixed
            // vertical padding of 3.dp gives a tight pill row that doesn't eat panel space.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Back button — enabled only when WebView has history to go back to
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (sharedState.canGoBack) TextPrimary else TextMuted.copy(alpha = 0.35f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = sharedState.canGoBack) { webViewRef?.goBack() }
                )
                // Lock / movie icon
                Icon(
                    if (isRemotion) Icons.Default.Movie else Icons.Default.Lock,
                    null, tint = TextMuted, modifier = Modifier.size(11.dp)
                )
                // URL input pill
                Box(
                    Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BgDark)
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (currentInput.isEmpty()) {
                        Text("http://localhost:3000", fontSize = 11.sp, color = TextMuted)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentInput,
                        onValueChange = { applyInput(it) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { connect() }),
                    )
                }
                // Go button
                Box(
                    Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent)
                        .clickable { connect() }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Go", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // (Redundant "page title" strip removed 2026-07-06 — it repeated info already visible
        // in the mode tab pill above and the editor tab/filename, adding a dead breadcrumb row
        // under every Preview sub-tab for no benefit.)

        // ── How-to-use guide dialog ─────────────────────────────────────
        if (showGuide) {
            key(orientation) {
            AlertDialog(
                onDismissRequest = { showGuide = false },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color(0xFFD4D4D4),
                textContentColor = Color(0xFF9CDCFE),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Visibility, null, tint = Color(0xFFFF79C6), modifier = Modifier.size(20.dp))
                        Text("How to use Preview", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PreviewGuideRow("HTML", Color(0xFF4EC9B0),
                            "Open any .html, .htm file. Full JS + CSS rendering. CSS files preview against demo elements. JS files capture console.log output.")
                        PreviewGuideRow("Markdown", Color(0xFF569CD6),
                            "Open any .md file. Rendered with dark-mode styling (h1–h4, code blocks, tables, blockquotes). Offline rendering with bundled marked.js.")
                        PreviewGuideRow("SVG", Color(0xFFF1FA8C),
                            "Open any .svg file. Rendered centered on a dark background. No JS.")
                        PreviewGuideRow("Browser", Color(0xFFFF79C6),
                            "Type any URL in the address bar and tap Go. Default is localhost:3000 — start your dev server in the terminal first, then switch here to see it live.")
                                                PreviewGuideRow("Remotion", Color(0xFFCE9178),
                            "Connects to Remotion Studio running in Ubuntu proot. Start it with 'npx remotion studio' in the terminal, then tap Go to preview video compositions, render clips, and see live previews.")
                        HorizontalDivider(color = Color(0xFF3C3C3C))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF252526))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFF1FA8C), modifier = Modifier.size(14.dp))
                            Text("Tap ↺ to manually refresh. The preview auto-updates as you type — no refresh needed.", fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGuide = false }) {
                        Text("Got it", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
            }
        }

        // ── Preview body ────────────────────────────────────────────────────
        // P48: Only render inline when NOT fullscreen — the shared WebView moves
        // to the fullscreen Dialog when isFullscreen is true, preventing duplicate WebViews
        if (!isFullscreen) {
            Box(Modifier.fillMaxSize()) {
                PreviewBody(
                    activeMode = sharedState.activeMode,
                    content = content,
                    language = language,
                    activeFilePath = activeFilePath,
                    browserUrl = sharedState.browserUrl,
                    remotionUrl = sharedState.remotionUrl,
                    projectRootPath = projectRootPath,
                    onWebView = { webViewRef = it },
                    onTitle = { pageTitle = it },
                    onLoading = { isLoading = it },
                    onCanGoBack = { sharedState.canGoBack = it },
                    sharedWebView = sharedWebView,
                )
            }
        }
    }

    // ── Fullscreen overlay ───────────────────────────────────────────────────
    // Tapping the fullscreen icon opens the SAME preview content in a window-filling Dialog —
    // centered, with its own back/X so there's always a clear way out. Works identically for
    // every sub-tab (HTML, Markdown, SVG, Browser, Remotion) since it just re-renders
    // the shared PreviewBody at fillMaxSize.
    if (isFullscreen) {
        key(orientation) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            // P25-4: usePlatformDefaultWidth=false makes it fill the screen.
            // decorFitsSystemWindows is NOT set to false — leaving it at the default (true)
            // means Android manages system bar insets normally and they are never hidden.
            // The previous fix incorrectly hid the status bar via a DisposableEffect, which
            // made the blank-status-bar bug permanent while in fullscreen instead of fixing it.
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(BgDark),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .background(Surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // P34-BROWSER: Back button — visible in fullscreen so user can
                    // navigate back without exiting fullscreen first
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (sharedState.canGoBack) TextPrimary else TextMuted.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = sharedState.canGoBack) { webViewRef?.goBack() },
                    )
                    Spacer(Modifier.width(4.dp))
                    // P48: Address bar in fullscreen for Browser/Remotion modes
                    if (sharedState.activeMode == PreviewMode.BROWSER || sharedState.activeMode == PreviewMode.REMOTION) {
                        val isRemotion = sharedState.activeMode == PreviewMode.REMOTION
                        val currentInput = if (isRemotion) sharedState.remotionInput else sharedState.browserInput
                        Box(
                            Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(BgDark)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                currentInput,
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp).clickable { webViewRef?.reload() },
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                sharedState.activeMode.label,
                                fontSize = 13.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Exit fullscreen",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable {
                                // P48: Sync address bar with current URL before exiting
                                // (shared WebView keeps its state — no save/restore needed)
                                webViewRef?.let { wv ->
                                    val actualUrl = wv.url ?: ""
                                    if (actualUrl.isNotBlank() && actualUrl != "about:blank") {
                                        if (sharedState.activeMode == PreviewMode.BROWSER) {
                                            sharedState.browserUrl = actualUrl
                                            sharedState.browserInput = actualUrl
                                        } else if (sharedState.activeMode == PreviewMode.REMOTION) {
                                            sharedState.remotionUrl = actualUrl
                                            sharedState.remotionInput = actualUrl
                                        }
                                    }
                                }
                                isFullscreen = false
                            },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PreviewBody(
                        activeMode = sharedState.activeMode,
                        content = content,
                        language = language,
                        activeFilePath = activeFilePath,
                        browserUrl = sharedState.browserUrl,
                        remotionUrl = sharedState.remotionUrl,
                        projectRootPath = projectRootPath,
                        onWebView = { wv -> webViewRef = wv },
                        onTitle = { pageTitle = it },
                        onLoading = { isLoading = it },
                        onCanGoBack = { sharedState.canGoBack = it },
                        sharedWebView = sharedWebView,
                    )
                }
            }
        }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PreviewBody — mode dispatch, shared between the inline pane and the fullscreen Dialog so
// both always render identically.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PreviewBody(
    activeMode: PreviewMode,
    content: String,
    language: Language,
    activeFilePath: String,
    browserUrl: String,
    remotionUrl: String,
    projectRootPath: String?,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onCanGoBack: (Boolean) -> Unit = {},
    sharedWebView: MutableState<WebView?>? = null,
) {
    when (activeMode) {
        PreviewMode.HTML      -> {
                // PhaseX: Use LivePreviewServer for project web files, inline for standalone
                val isWebFile = activeFilePath.endsWith(".html") || activeFilePath.endsWith(".htm") ||
                    activeFilePath.endsWith(".css") || activeFilePath.endsWith(".js") ||
                    activeFilePath.endsWith(".mjs")
                val useLiveServer = projectRootPath != null && isWebFile
                val liveUrl = if (useLiveServer && com.codespace.ide.preview.LivePreviewServer.isRunning()) {
                    if (activeFilePath.endsWith(".html") || activeFilePath.endsWith(".htm")) {
                        com.codespace.ide.preview.LivePreviewServer.getPreviewUrl(activeFilePath)
                    } else {
                        // CSS/JS file — serve the project root (index.html) so the
                        // preview shows the rendered page, and SSE reloads it on change
                        com.codespace.ide.preview.LivePreviewServer.getPreviewUrl("")
                    }
                } else null
                HtmlPreview(content, language, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading, liveUrl = liveUrl)
            }
        PreviewMode.MARKDOWN  -> MarkdownPreview(content, onWebView = onWebView, onLoading = onLoading)
        PreviewMode.SVG       -> SvgPreview(content, onWebView = onWebView)
        PreviewMode.BROWSER   -> BrowserPreview(browserUrl, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading, onCanGoBack = onCanGoBack, sharedWebView = sharedWebView)
                // Independent from Browser's URL now — each mode has its own connection (this is the
        // fix for the "Browser and Remotion port mirroring" bug).
        PreviewMode.REMOTION  -> RemotionPreview(remotionUrl, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading, onCanGoBack = onCanGoBack, sharedWebView = sharedWebView)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared file-chooser bridge for every preview WebView (#9 hard-bucket fix).
// WebView never implements onShowFileChooser out of the box, so any
// <input type="file"> anywhere — a user-built upload form in HtmlPreview,
// a real site with an upload form in BrowserPreview
// with a CSV import, Remotion Studio's asset import — silently does nothing
// when tapped. This bridges WebView's chooser callback to a real Android
// document picker and feeds the result back into the page's JS callback.
// Supports both single-file and native multi-file (<input multiple>) inputs.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun rememberOnShowFileChooser(): (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean {
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        pending?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        pending = null
    }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        pending?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        pending = null
    }

    return onShowFileChooser@{ filePathCallback, params ->
        if (filePathCallback == null) return@onShowFileChooser false
        // A prior chooser that never got a result (e.g. page navigated away) must be
        // released here, not just overwritten — WebView leaks/hangs otherwise.
        pending?.onReceiveValue(null)
        pending = filePathCallback

        val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() && it != "*/*" } ?: emptyList()
        val mime = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
        val allowMultiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        try {
            if (allowMultiple) multiLauncher.launch(mime) else singleLauncher.launch(mime)
        } catch (_: Exception) {
            pending?.onReceiveValue(null)
            pending = null
        }
        true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HTML / CSS / JS Preview
// Renders file content directly; injects CSS resets and JS
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(
    content: String,
    language: Language,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    liveUrl: String? = null,
) {
    val fileChooserHandler = rememberOnShowFileChooser()
    // Detect React/JSX content
    val isReact = content.contains("import React") || content.contains("from 'react'") ||
                  content.contains("from \"react\"") || content.contains("ReactDOM") ||
                  content.contains("useState") || content.contains("jsx")
    
    val html = remember(content, language, isReact) {
        when (language) {
            Language.CSS -> """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=6.0, user-scalable=yes">
                <style>$content</style>
                </head><body style="background:#fff;padding:16px;font-family:sans-serif;">
                <h2>CSS Preview</h2><p class="demo">Sample paragraph</p>
                <button class="demo">Button</button>
                <div class="demo" style="width:80px;height:80px;margin-top:12px;"></div>
                </body></html>
            """.trimIndent()
            Language.JAVASCRIPT, Language.TYPESCRIPT -> """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=6.0, user-scalable=yes">
                <style>body{background:#1e1e1e;color:#d4d4d4;font-family:monospace;padding:16px;}</style>
                </head><body>
                <div id="output" style="white-space:pre-wrap;"></div>
                <script>
                const _log=console.log;
                const out=document.getElementById('output');
                console.log=(...a)=>{out.textContent+=a.join(' ')+'\n';_log(...a)};
                try{ $content }catch(e){ out.textContent+='Error: '+e.message; }
                </script></body></html>
            """.trimIndent()
            else -> {
                if (isReact) {
                    // React/JSX support via Babel standalone
                    val reactCode = content
                        .replace("import React from 'react'", "")
                        .replace("import React from \"react\"", "")
                        .replace("import ReactDOM from 'react-dom'", "")
                        .replace("import ReactDOM from \"react-dom\"", "")
                        .replace("import { useState, useEffect, useRef } from 'react'", "")
                        .replace("import { useState, useEffect, useRef } from \"react\"", "")
                        .replace("import './", "// import './")
                        .replace("export default ", "// export default ")
                    """<!DOCTYPE html><html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=6.0, user-scalable=yes">
                    <script src="https://cdn.jsdelivr.net/npm/react@18/umd/react.development.js"></script>
                    <script src="https://cdn.jsdelivr.net/npm/react-dom@18/umd/react-dom.development.js"></script>
                    <script src="https://cdn.jsdelivr.net/npm/@babel/standalone/babel.min.js"></script>
                    <style>body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#1e1e1e;color:#d4d4d4;}
                    *{box-sizing:border-box;}</style>
                    </head><body>
                    <div id="root"></div>
                    <script type="text/babel">
                    const { useState, useEffect, useRef } = React;
                    $reactCode
                    </script>
                    </body></html>"""
                } else {
                    content.ifBlank {
                        """<!DOCTYPE html><html><body style="background:#1e1e1e;color:#717171;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;">
                        <p>Open an HTML file to preview it here.</p></body></html>"""
                    }
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // P-ZOOM: Enable pinch-to-zoom for HTML preview
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                        view?.evaluateJavascript(USER_AGENT_DATA_OVERRIDE_JS, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle(view?.title ?: "")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onTitle(title ?: "")
                    }
                    override fun onShowFileChooser(
                        view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: WebChromeClient.FileChooserParams?,
                    ): Boolean = fileChooserHandler(filePathCallback, fileChooserParams)
                }
                onWebView(this)
            }
        },
        update = { wv ->
            // P-RENDER: Use content-based key for inline HTML — html.take(64) was
            // always the template header for CSS/JS, so the WebView never reloaded.
            val lastTag = wv.tag as? String
            val currentTag = if (liveUrl != null) liveUrl else content.take(128)
            if (lastTag != currentTag) {
                wv.tag = currentTag
                if (liveUrl != null) {
                    // PhaseX: Load from LivePreviewServer — gets auto-reload via SSE
                    wv.loadUrl(liveUrl)
                } else {
                    wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
                }
            }
            onWebView(wv)
        },
        onRelease = { wv -> (wv.parent as? android.view.ViewGroup)?.removeView(wv) },
        modifier = Modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Markdown Preview — uses local MarkdownRenderer (offline, no CDN dependency)
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownPreview(
    content: String,
    onWebView: (WebView) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    // P-RENDER: Use local MarkdownRenderer instead of CDN marked.js — works offline.
    val html = remember(content) {
        com.codespace.ide.editor.MarkdownRenderer.render(content)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false  // No JS needed — pure HTML output
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // P-ZOOM: Enable pinch-to-zoom
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) { onLoading(false) }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            // P-RENDER: Use content-based key — html.take(64) was always the template
            // header, so the WebView never reloaded when markdown content arrived.
            val contentKey = content.take(128)
            if (wv.tag as? String != contentKey) {
                wv.tag = contentKey
                wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            }
            onWebView(wv)
        },
        onRelease = { wv -> (wv.parent as? android.view.ViewGroup)?.removeView(wv) },
        modifier = Modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SVG Preview
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SvgPreview(
    content: String,
    onWebView: (WebView) -> Unit,
) {
    // P-RENDER: No max-width/max-height constraints so SVG can be zoomed freely.
    // overflow:auto allows panning when zoomed in.
    val html = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.3, maximum-scale=8.0, user-scalable=yes">
        <style>body{background:#1e1e1e;margin:0;padding:8px;overflow:auto;}
               svg{display:block;margin:0 auto;}</style>
        </head><body>$content</body></html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // P-ZOOM: Enable pinch-to-zoom for SVG
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                onWebView(this)
            }
        },
        update = { wv ->
            // P-RENDER: Use content-based key, NOT html.take(64) — the first 64 chars
            // are always the HTML template header, so the key never changed when SVG
            // content arrived from produceState, and the WebView never reloaded.
            val contentKey = content.take(128)
            if (wv.tag as? String != contentKey) {
                wv.tag = contentKey
                wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            }
            onWebView(wv)
        },
        onRelease = { wv -> (wv.parent as? android.view.ViewGroup)?.removeView(wv) },
        modifier = Modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Browser Preview — points at localhost or any URL (e.g. running dev server)
// ─────────────────────────────────────────────────────────────────────────────
// P48: JavaScript to override navigator.userAgentData so Google/YouTube
// don't detect this as an embedded WebView and block login.
// Also overrides Sec-CH-UA, platform, mobile, and forces desktop viewport.
private const val USER_AGENT_DATA_OVERRIDE_JS = """
(function() {
  try {
    // Override navigator.userAgentData — Google checks this to detect WebViews
    Object.defineProperty(navigator, 'userAgentData', {
      get: function() {
        return {
          brands: [
            {brand: 'Google Chrome', version: '125'},
            {brand: 'Chromium', version: '125'},
            {brand: 'Not.A/Brand', version: '24'}
          ],
          mobile: false,
          platform: 'Windows'
        };
      },
      configurable: true
    });
  } catch(e) {}
  // Override navigator.platform to match desktop Chrome
  try {
    Object.defineProperty(navigator, 'platform', {
      get: function() { return 'Win32'; },
      configurable: true
    });
  } catch(e) {}
  // Override navigator.maxTouchPoints — 0 indicates a desktop (no touch)
  try {
    Object.defineProperty(navigator, 'maxTouchPoints', {
      get: function() { return 0; },
      configurable: true
    });
  } catch(e) {}
  // TEST-51-FIX: Add window.chrome object — Google checks for this to detect real Chrome.
  // Without it, Google shows "insecure browser" and blocks sign-in.
  try {
    if (!window.chrome) {
      window.chrome = {
        runtime: { id: undefined, onConnect: {}, onMessage: {}, connect: function(){}, sendMessage: function(){} },
        app: { isInstalled: false, InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }, RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' } },
        csi: function() { return { onloadT: Date.now(), pageT: 0, startE: Date.now(), tran: 15 }; },
        loadTimes: function() { return { commitLoadTime: Date.now()/1000, connectionInfo: 'h2', finishDocumentLoadTime: Date.now()/1000, finishLoadTime: Date.now()/1000, firstPaintAfterLoadTime: Date.now()/1000, firstPaintTime: Date.now()/1000, navigationType: 'Other', npnNegotiatedProtocol: 'h2', requestTime: Date.now()/1000, startDnsLoopTime: Date.now()/1000, startLoadTime: Date.now()/1000, timeToFirstByte: 0 }; }
      };
    }
  } catch(e) {}
  // TEST-51-FIX: Override navigator.webdriver — Google detects automation
  try {
    Object.defineProperty(navigator, 'webdriver', {
      get: function() { return false; },
      configurable: true
    });
  } catch(e) {}
})();
"""

// P48: CSS to inject on YouTube pages to fix Shorts black screen and force desktop layout
private const val YOUTUBE_FIX_CSS = """
/* Force desktop viewport for YouTube */
ytd-app { min-width: 1280px !important; }
/* Ensure video elements have a visible background and non-zero size */
video, .html5-video-player, #movie_player { background-color: #000 !important; min-width: 100% !important; min-height: 100% !important; }
/* TEST-51-FIX: Fix YouTube settings page black screen */
ytd-app, ytd-settings, .settings-page, tp-yt-paper-dialog, #settings { background-color: #fff !important; color: #0f0f0f !important; display: block !important; visibility: visible !important; opacity: 1 !important; }
/* TEST-51-FIX: Force video container to have explicit dimensions (fixes Shorts audio-only) */
#movie_player, .html5-video-player, #player-container, #player-container-outerline, .player-container { width: 100% !important; height: 100vh !important; min-height: 480px !important; }
/* Ensure Shorts player has dimensions */
#shorts-player, ytd-reel-video-renderer video, ytd-shorts video { width: 100% !important; height: 100% !important; object-fit: contain !important; }
"""

// TEST-51-FIX: JS to force video playback and fix Shorts audio-only
// playsinline is an HTML attribute, NOT a CSS property — must be set via JS
private const val YOUTUBE_VIDEO_FIX_JS = """
(function() {
  function fixVideo(v) {
    if (!v) return;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', 'true');
    v.setAttribute('autoplay', 'true');
    // Force video to render (not just audio)
    if (v.videoWidth === 0 || v.videoHeight === 0) {
      v.style.width = '100%';
      v.style.height = '100%';
      v.style.objectFit = 'contain';
    }
    // Try to play if paused (autoplay was blocked)
    if (v.paused && v.readyState >= 2) {
      v.play().catch(function() {});
    }
  }
  // Fix all existing videos
  document.querySelectorAll('video').forEach(fixVideo);
  // MutationObserver: catch dynamically added videos (YouTube SPA)
  if (!window.__ytVideoObserver) {
    window.__ytVideoObserver = new MutationObserver(function(mutations) {
      mutations.forEach(function(m) {
        m.addedNodes.forEach(function(n) {
          if (n.nodeName === 'VIDEO') fixVideo(n);
          if (n.querySelectorAll) n.querySelectorAll('video').forEach(fixVideo);
        });
      });
    });
    window.__ytVideoObserver.observe(document.body || document.documentElement, {childList: true, subtree: true});
  }
  // Also poll every 500ms for 5 seconds (covers lazy-loaded players)
  if (!window.__ytVideoPollCount) window.__ytVideoPollCount = 0;
  if (window.__ytVideoPollCount < 10) {
    window.__ytVideoPollCount++;
    setTimeout(function() {
      document.querySelectorAll('video').forEach(fixVideo);
    }, 500);
  }
})();
"""

// TEST-51-FIX: JS to pierce YouTube Shadow DOM for settings page
// Standard CSS <style> tags don't pierce Shadow DOM — must inject into shadow roots
private const val YOUTUBE_SHADOW_DOM_FIX_JS = """
(function() {
  function injectShadowStyles(root, styles) {
    if (!root || !root.shadowRoot) return;
    var s = document.createElement('style');
    s.textContent = styles;
    root.shadowRoot.appendChild(s);
  }
  var settingsCSS = 'div, ytd-settings, .settings-page { background-color: #fff !important; color: #0f0f0f !important; display: block !important; visibility: visible !important; opacity: 1 !important; } * { visibility: visible !important; }';
  // Inject into all known YouTube custom elements that use shadow DOM
  var selectors = ['ytd-app', 'ytd-settings', 'ytd-browse', 'ytd-page-manager', 'tp-yt-app-drawer', 'tp-yt-paper-dialog'];
  selectors.forEach(function(sel) {
    document.querySelectorAll(sel).forEach(function(el) {
      injectShadowStyles(el, settingsCSS);
    });
  });
  // MutationObserver: catch dynamically created custom elements
  if (!window.__ytShadowObserver) {
    window.__ytShadowObserver = new MutationObserver(function(mutations) {
      mutations.forEach(function(m) {
        m.addedNodes.forEach(function(n) {
          if (n.nodeName && n.nodeName.startsWith('YTD-') || n.nodeName === 'TP-YT-PAPER-DIALOG') {
            injectShadowStyles(n, settingsCSS);
          }
          if (n.querySelectorAll) {
            n.querySelectorAll('ytd-app, ytd-settings, ytd-browse, ytd-page-manager, tp-yt-app-drawer, tp-yt-paper-dialog').forEach(function(el) {
              injectShadowStyles(el, settingsCSS);
            });
          }
        });
      });
    });
    window.__ytShadowObserver.observe(document.body || document.documentElement, {childList: true, subtree: true});
  }
})();
"""

// P48: CSS to force desktop layout on all sites
private const val DESKTOP_VIEWPORT_CSS = """
/* Force desktop viewport width */
html { min-width: 1024px !important; }
/* Disable mobile-specific CSS */
@media only screen and (max-width: 768px) { body { min-width: 1024px !important; } }
"""


// ─────────────────────────────────────────────────────────────────────────────
// P48: Configure a WebView with full browser security, desktop view, and video fixes
// Used by both BrowserPreview and RemotionPreview to share configuration
// ─────────────────────────────────────────────────────────────────────────────
private fun configureSecureWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        // P48: Desktop user-agent — latest Chrome on Windows
        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        // P48: File and content access
        allowFileAccess = true
        allowContentAccess = true
        // P48: Allow local file URLs and universal access (for local preview)
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = true
        // P48: Database + cache for SPAs
        databaseEnabled = true
        cacheMode = WebSettings.LOAD_DEFAULT
        // P48: Multiple windows for OAuth popups (YouTube/Google login)
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
        // P48: Mixed content
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // P48: Safe browsing
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setSafeBrowsingEnabled(true)
        }
    }
    // P48: Cookies — third-party needed for login flows
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    // P48: Override User-Agent client hints at the NETWORK level (HTTP headers)
    // This is the key fix — JS override alone doesn't change the Sec-CH-UA headers
    // that Google reads to detect embedded WebViews
    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
        try {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome").setMajorVersion("125").build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium").setMajorVersion("125").build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not.A/Brand").setMajorVersion("24").build()
            )
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setPlatform("Windows")
                .setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(webView.settings, metadata)
        } catch (e: Exception) {
            // Fallback: if UserAgentMetadata API isn't available, the JS override still works
        }
    }
    // P48: Set layer type to NONE — let Android manage hardware acceleration
    // LAYER_TYPE_HARDWARE causes black screen for YouTube Shorts on Samsung
    // LAYER_TYPE_SOFTWARE is too slow for video
    // LAYER_TYPE_NONE lets the Activity-level hardwareAccelerated=true handle it
    webView.setLayerType(View.LAYER_TYPE_NONE, null)
    // P48: Initial scale 0 lets WebView pick natural scale
    webView.setInitialScale(0)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPreview(
    url: String,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onCanGoBack: (Boolean) -> Unit = {},
    sharedWebView: MutableState<WebView?>? = null,
) {
    val fileChooserHandler = rememberOnShowFileChooser()
    var lastLoadedUrl by remember { mutableStateOf("") }
    AndroidView(
        factory = { ctx ->
            // P48: Reuse shared WebView if available (fullscreen mirror), else create new
            val wv = sharedWebView?.value ?: WebView(ctx)
            // CRASH-FIX 2026-08-17: When a shared WebView is reused across two
            // AndroidView composables (e.g. normal preview + fullscreen mirror),
            // Compose can call this factory for the NEW parent before onRelease
            // detaches the OLD parent, throwing "specified child already has a
            // parent". Force-detach here so re-attachment always succeeds.
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            if (sharedWebView?.value == null) {
                // First time — configure the WebView with all security + desktop + video fixes
                configureSecureWebView(wv)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                        // P48: Inject UA override on page START, not just finish —
                        // Google checks navigator.userAgentData before the page fully loads
                        view?.evaluateJavascript(USER_AGENT_DATA_OVERRIDE_JS, null)
                        // TEST-51-FIX: Inject video fix JS early for YouTube (before video elements load)
                        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                            view?.evaluateJavascript(YOUTUBE_VIDEO_FIX_JS, null)
                        }
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle(view?.title ?: "")
                        onCanGoBack(view?.canGoBack() == true)
                        // P48: Re-inject UA override after page loads
                        view?.evaluateJavascript(USER_AGENT_DATA_OVERRIDE_JS, null)
                        // P48: Inject CSS fixes for YouTube (playsinline + desktop layout)
                        val currentUrl = url ?: ""
                        if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be")) {
                            view?.evaluateJavascript("var s=document.createElement('style');s.textContent='" + YOUTUBE_FIX_CSS.replace("\n"," ") + "';document.head.appendChild(s);", null)
                            // TEST-51-FIX: Force playsinline + autoplay on video elements (fixes Shorts audio-only)
                            view?.evaluateJavascript(YOUTUBE_VIDEO_FIX_JS, null)
                            // TEST-51-FIX: Inject styles into Shadow DOM (fixes settings black screen)
                            view?.evaluateJavascript(YOUTUBE_SHADOW_DOM_FIX_JS, null)
                        }
                        // P48: Inject desktop viewport CSS on all sites
                        view?.evaluateJavascript("var s2=document.createElement('style');s2.textContent='" + DESKTOP_VIEWPORT_CSS.replace("\n"," ") + "';document.head.appendChild(s2);", null)
                        // P48: Force desktop site by overriding viewport meta tag
                        view?.evaluateJavascript("var m=document.querySelector('meta[name=viewport]');if(m)m.setAttribute('content','width=1280,initial-scale=0.4,maximum-scale=4.0,user-scalable=yes');", null)
                        CookieManager.getInstance().flush()
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        onLoading(false)
                        onCanGoBack(view?.canGoBack() == true)
                        val errHtml = """<html><body style="background:#1e1e1e;color:#f48771;font-family:sans-serif;padding:24px;display:flex;align-items:center;justify-content:center;min-height:80vh;text-align:center;">
                            <div><h2>Cannot connect</h2><p>$description</p><p style="color:#717171;font-size:13px;">Is your server running on ${url}?</p></div></body></html>"""
                        view?.loadDataWithBaseURL(null, errHtml, "text/html", "UTF-8", null)
                    }
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) { onTitle(title ?: "") }

                    override fun onShowFileChooser(
                        view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: WebChromeClient.FileChooserParams?,
                    ): Boolean = fileChooserHandler(filePathCallback, fileChooserParams)

                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean {
                        if (view == null || resultMsg == null) return false
                        val newWebView = WebView(view.context)
                        configureSecureWebView(newWebView)
                        newWebView.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(pv: WebView?, pu: String?, favicon: android.graphics.Bitmap?) {
                                pv?.evaluateJavascript(USER_AGENT_DATA_OVERRIDE_JS, null)
                            }
                            override fun onPageFinished(popupView: WebView?, popupUrl: String?) {
                                // TEST-51-FIX: Don't redirect Google sign-in OAuth back to main WebView immediately.
                                // Let the popup handle the OAuth flow — it will redirect itself
                                // back to YouTube when auth completes. Only load in main WebView
                                // if the popup URL is a YouTube URL (OAuth callback completed).
                                if (popupUrl != null &&
                                    (popupUrl.contains("youtube.com") || popupUrl.contains("google.com"))) {
                                    if (popupUrl.contains("youtube.com")) {
                                        view.loadUrl(popupUrl)
                                    }
                                }
                            }
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                                handler?.proceed()
                            }
                        }
                        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
                        transport?.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
                    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean { result?.confirm(defaultValue ?: ""); return true }

                    override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                        callback?.onCustomViewHidden()
                    }
                    override fun onHideCustomView() {}

                    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }
                sharedWebView?.value = wv
            }
            onWebView(wv)
            wv
        },
        update = { wv ->
            val isRealUrl = url.isNotBlank() && url != "http://localhost:0" && url != "http://localhost:"
            // P48: Check if WebView already has this URL loaded (shared WebView from fullscreen)
            val alreadyLoaded = wv.url != null && wv.url == url
            if (lastLoadedUrl != url && isRealUrl && !alreadyLoaded) {
                lastLoadedUrl = url
                val clean = url.substringBefore('?').substringBefore('#').lowercase()
                val videoExts = listOf(".mp4", ".webm", ".mov", ".mkv", ".m4v")
                val audioExts = listOf(".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac")
                when {
                    videoExts.any { clean.endsWith(it) } -> wv.loadDataWithBaseURL(
                        url,
                        """<html><body style="margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh;">
                            <video src="$url" controls autoplay playsinline style="max-width:100%;max-height:100vh;"></video>
                            </body></html>""",
                        "text/html", "UTF-8", null,
                    )
                    audioExts.any { clean.endsWith(it) } -> wv.loadDataWithBaseURL(
                        url,
                        """<html><body style="margin:0;background:#1e1e1e;color:#ccc;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;">
                            <audio src="$url" controls autoplay style="width:80%;"></audio>
                            </body></html>""",
                        "text/html", "UTF-8", null,
                    )
                    else -> wv.loadUrl(url)
                }
            } else if (!isRealUrl && lastLoadedUrl.isEmpty() && wv.url == null) {
                wv.loadDataWithBaseURL(null, """
                    <html><body style="background:#1e1e1e;color:#717171;font-family:sans-serif;
                    display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center;margin:0;">
                    <div>
                      <div style="font-size:36px;margin-bottom:12px;">🌐</div>
                      <p style="color:#d4d4d4;font-size:15px;margin-bottom:8px;">Browser Preview</p>
                      <p style="font-size:13px;">Enter a URL above and tap <b style="color:#007acc;">Go</b></p>
                      <p style="font-size:12px;margin-top:8px;">or forward a port from the <b>Ports</b> tab</p>
                    </div></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            onWebView(wv)
        },
        onRelease = { wv -> (wv.parent as? android.view.ViewGroup)?.removeView(wv) },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    )
}


// ─────────────────────────────────────────────────────────────────────────────
// Remotion Preview — connects to Remotion dev server running in Ubuntu proot
// Shows the Remotion Studio UI where users can preview video compositions
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RemotionPreview(
    url: String,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onCanGoBack: (Boolean) -> Unit = {},
    sharedWebView: MutableState<WebView?>? = null,
) {
    val fileChooserHandler = rememberOnShowFileChooser()
    val remotionUrl = if (url.isBlank()) "http://localhost:3000" else url

    AndroidView(
        factory = { ctx ->
            val wv = sharedWebView?.value ?: WebView(ctx)
            // CRASH-FIX 2026-08-17: When a shared WebView is reused across two
            // AndroidView composables (e.g. normal preview + fullscreen mirror),
            // Compose can call this factory for the NEW parent before onRelease
            // detaches the OLD parent, throwing "specified child already has a
            // parent". Force-detach here so re-attachment always succeeds.
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            if (sharedWebView?.value == null) {
                configureSecureWebView(wv)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                        view?.evaluateJavascript(USER_AGENT_DATA_OVERRIDE_JS, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle(view?.title ?: "Remotion Studio")
                        onCanGoBack(view?.canGoBack() == true)
                        CookieManager.getInstance().flush()
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        onLoading(false)
                        onCanGoBack(view?.canGoBack() == true)
                        val errHtml = """<html><body style="background:#1e1e1e;color:#d4d4d4;font-family:sans-serif;padding:24px;display:flex;align-items:center;justify-content:center;min-height:80vh;text-align:center;">
                            <div>
                            <div style="font-size:48px;margin-bottom:16px;">🎬</div>
                            <h2 style="color:#f48771;">Remotion Studio not running</h2>
                            <p style="color:#9cdcfe;">Start it in the terminal:</p>
                            <pre style="background:#252526;padding:12px;border-radius:6px;color:#4ec9b0;display:inline-block;text-align:left;">npx remotion studio</pre>
                            <p style="color:#717171;font-size:13px;margin-top:12px;">Then tap Go to connect.</p>
                            </div></body></html>"""
                        view?.loadDataWithBaseURL(null, errHtml, "text/html", "UTF-8", null)
                    }
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) { onTitle(title ?: "") }

                    override fun onShowFileChooser(
                        view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: WebChromeClient.FileChooserParams?,
                    ): Boolean = fileChooserHandler(filePathCallback, fileChooserParams)
                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean {
                        if (view == null || resultMsg == null) return false
                        val newWebView = WebView(view.context)
                        configureSecureWebView(newWebView)
                        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
                        transport?.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
                }
                sharedWebView?.value = wv
            }
            onWebView(wv)
            wv
        },
        update = { wv ->
            val isRealUrl = remotionUrl.isNotBlank() && remotionUrl != "http://localhost:0"
            if (wv.url != remotionUrl && isRealUrl) wv.loadUrl(remotionUrl)
            onCanGoBack(wv.canGoBack())
            onWebView(wv)
        },
        onRelease = { wv -> (wv.parent as? android.view.ViewGroup)?.removeView(wv) },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PreviewGuideRow — used inside the how-to-use dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PreviewGuideRow(mode: String, accent: Color, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(mode, fontSize = 10.sp, color = accent, fontWeight = FontWeight.Bold)
        }
        Text(description, fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}
