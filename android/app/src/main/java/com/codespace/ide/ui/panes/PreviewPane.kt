package com.codespace.ide.ui.panes

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.viewinterop.AndroidView
import com.codespace.ide.domain.Language
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// PreviewPane — live preview for HTML/CSS/JS, Markdown, SVG, and local servers
// Shown as BottomTab.PREVIEW in ProjectShellScreen
// ─────────────────────────────────────────────────────────────────────────────

private enum class PreviewMode(val label: String) {
    HTML("HTML"),
    MARKDOWN("Markdown"),
    SVG("SVG"),
    BROWSER("Browser"),
    DASHBOARD("Dashboard"),
    REMOTION("Remotion"),
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
) {
    // Read file content from disk whenever path changes
    val content by produceState(initialValue = "", key1 = activeFilePath) {
        value = if (activeFilePath.isNotBlank()) {
            try { java.io.File(activeFilePath).readText() } catch (e: Exception) { "" }
        } else ""
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
    val defaultMode = when (language) {
        Language.HTML                          -> PreviewMode.HTML
        Language.MARKDOWN                      -> PreviewMode.MARKDOWN
        Language.XML                           -> if (activeFilePath.endsWith(".svg")) PreviewMode.SVG else PreviewMode.HTML
        Language.CSS, Language.JAVASCRIPT,
        Language.TYPESCRIPT                    -> PreviewMode.HTML
        else                                   -> PreviewMode.HTML
    }

    var activeMode by remember(activeFilePath, initialPort) { mutableStateOf(if (initialPort != null) PreviewMode.BROWSER else defaultMode) }
    var showGuide by remember { mutableStateOf(false) }
    var browserUrl by remember(initialPort) { mutableStateOf(initialPort?.let { "http://localhost:$it" } ?: "http://localhost:3000") }
    var browserInput by remember(initialPort) { mutableStateOf(initialPort?.let { "http://localhost:$it" } ?: "http://localhost:3000") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(initialPort) { if (initialPort != null) webViewRef?.loadUrl("http://localhost:$initialPort") }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // ── Top bar ─────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Surface)
                .padding(horizontal = 8.dp),
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
                    val active = mode == activeMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                width = if (active) 1.dp else 0.dp,
                                color = if (active) Accent else Color.Transparent,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable { activeMode = mode }
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
                Icon(
                    Icons.Default.HelpOutline,
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
                            when (activeMode) {
                                PreviewMode.BROWSER -> webViewRef?.reload()
                                else -> webViewRef?.reload()
                            }
                        }
                )
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen preview",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp).clickable { isFullscreen = true }
                )
            }
        }

        // ── Browser address bar (BROWSER + REMOTION modes) ─────────────────
        // Compact pill design 2026-07-06 — the default Material3 OutlinedTextField reserves a
        // lot of built-in vertical padding (meant for floating labels), which made this bar look
        // oversized and left dead empty space in the toolbar row. Swapped for a tight, fixed-height
        // pill (matches the STT/Root/Zsh quick-actions row styling elsewhere in the app) that fills
        // the space it actually needs instead of leaving a gap.
        if (activeMode == PreviewMode.BROWSER || activeMode == PreviewMode.REMOTION) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Surface)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (activeMode == PreviewMode.REMOTION) Icons.Default.Movie else Icons.Default.Lock,
                    null, tint = TextMuted, modifier = Modifier.size(14.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(BgDark)
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (browserInput.isEmpty()) {
                        Text("http://localhost:3000", fontSize = 12.sp, color = TextMuted)
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = browserInput,
                        onValueChange = { browserInput = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                            browserUrl = browserInput
                            webViewRef?.loadUrl(browserInput)
                        }),
                    )
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent)
                        .clickable {
                            browserUrl = browserInput
                            webViewRef?.loadUrl(browserInput)
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Go", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }

        Divider(color = Border, thickness = 1.dp)

        // (Redundant "page title" strip removed 2026-07-06 — it repeated info already visible
        // in the mode tab pill above and the editor tab/filename, adding a dead breadcrumb row
        // under every Preview sub-tab for no benefit.)

        // ── How-to-use guide dialog ─────────────────────────────────────
        if (showGuide) {
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
                            "Open any .md file. Rendered with dark-mode styling (h1–h4, code blocks, tables, blockquotes). Requires internet for marked.js.")
                        PreviewGuideRow("SVG", Color(0xFFF1FA8C),
                            "Open any .svg file. Rendered centered on a dark background. No JS.")
                        PreviewGuideRow("Browser", Color(0xFFFF79C6),
                            "Type any URL in the address bar and tap Go. Default is localhost:3000 — start your dev server in the terminal first, then switch here to see it live.")
                        PreviewGuideRow("Dashboard", Color(0xFF4EC9B0),
                            "Interactive dashboard builder with drag-and-drop components. AI generates charts, stat cards, tables, and widgets. Tap any component palette item to add it. Drag elements to reposition. Includes Chart.js for live data visualization.")
                        PreviewGuideRow("Remotion", Color(0xFFCE9178),
                            "Connects to Remotion Studio running in Ubuntu proot. Start it with 'npx remotion studio' in the terminal, then tap Go to preview video compositions, render clips, and see live previews.")
                        Divider(color = Color(0xFF3C3C3C))
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
                            Text("Tap ↺ to manually refresh. The preview auto-updates when you switch files.", fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
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

        // ── Preview body ────────────────────────────────────────────────────
        Box(Modifier.fillMaxSize()) {
            PreviewBody(
                activeMode = activeMode,
                content = content,
                language = language,
                activeFilePath = activeFilePath,
                browserUrl = browserUrl,
                onWebView = { webViewRef = it },
                onTitle = { pageTitle = it },
                onLoading = { isLoading = it },
            )
        }
    }

    // ── Fullscreen overlay ───────────────────────────────────────────────────
    // Tapping the fullscreen icon opens the SAME preview content in a window-filling Dialog —
    // centered, with its own back/X so there's always a clear way out. Works identically for
    // every sub-tab (HTML, Markdown, SVG, Browser, Dashboard, Remotion) since it just re-renders
    // the shared PreviewBody at fillMaxSize.
    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(BgDark),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Surface)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            activeMode.label,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Exit fullscreen",
                        tint = TextMuted,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .size(22.dp)
                            .clickable { isFullscreen = false },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PreviewBody(
                        activeMode = activeMode,
                        content = content,
                        language = language,
                        activeFilePath = activeFilePath,
                        browserUrl = browserUrl,
                        onWebView = { webViewRef = it },
                        onTitle = { pageTitle = it },
                        onLoading = { isLoading = it },
                    )
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
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    when (activeMode) {
        PreviewMode.HTML      -> HtmlPreview(content, language, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading)
        PreviewMode.MARKDOWN  -> MarkdownPreview(content, onWebView = onWebView, onLoading = onLoading)
        PreviewMode.SVG       -> SvgPreview(content, onWebView = onWebView)
        PreviewMode.BROWSER   -> BrowserPreview(browserUrl, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading)
        PreviewMode.DASHBOARD -> DashboardPreview(activeFilePath, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading)
        PreviewMode.REMOTION  -> RemotionPreview(browserUrl, onWebView = onWebView, onTitle = onTitle, onLoading = onLoading)
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
) {
    // Detect React/JSX content
    val isReact = content.contains("import React") || content.contains("from 'react'") ||
                  content.contains("from \"react\"") || content.contains("ReactDOM") ||
                  content.contains("useState") || content.contains("jsx")
    
    val html = remember(content, language, isReact) {
        when (language) {
            Language.CSS -> """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>$content</style>
                </head><body style="background:#fff;padding:16px;font-family:sans-serif;">
                <h2>CSS Preview</h2><p class="demo">Sample paragraph</p>
                <button class="demo">Button</button>
                <div class="demo" style="width:80px;height:80px;margin-top:12px;"></div>
                </body></html>
            """.trimIndent()
            Language.JAVASCRIPT, Language.TYPESCRIPT -> """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
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
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
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
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
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
                }
                onWebView(this)
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            onWebView(wv)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Markdown Preview — renders via marked.js (bundled inline, no internet needed)
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownPreview(
    content: String,
    onWebView: (WebView) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    // Escape backticks and backslashes for JS template literal
    val escaped = content
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")

    val html = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          body { background:#1e1e1e; color:#d4d4d4; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
                 padding:20px; line-height:1.7; max-width:800px; margin:0 auto; }
          h1,h2,h3,h4 { color:#569cd6; border-bottom:1px solid #3c3c3c; padding-bottom:6px; }
          code { background:#2d2d2d; color:#ce9178; padding:2px 6px; border-radius:3px; font-family:monospace; font-size:0.9em; }
          pre  { background:#2d2d2d; padding:16px; border-radius:6px; overflow-x:auto; }
          pre code { background:none; padding:0; }
          blockquote { border-left:4px solid #007acc; margin:0; padding-left:16px; color:#9cdcfe; }
          a { color:#4ec9b0; }
          table { border-collapse:collapse; width:100%; }
          th,td { border:1px solid #3c3c3c; padding:8px 12px; }
          th { background:#252526; }
          img { max-width:100%; }
          hr { border-color:#3c3c3c; }
        </style>
        <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
        </head><body>
        <div id="content"></div>
        <script>
          const md = `$escaped`;
          document.getElementById('content').innerHTML = marked.parse(md);
        </script>
        </body></html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { onLoading(true) }
                    override fun onPageFinished(view: WebView?, url: String?) { onLoading(false) }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL("https://cdn.jsdelivr.net", html, "text/html", "UTF-8", null)
            onWebView(wv)
        },
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
    val html = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>body{background:#1e1e1e;display:flex;align-items:center;justify-content:center;
               min-height:100vh;margin:0;padding:16px;box-sizing:border-box;}
               svg{max-width:100%;max-height:80vh;}</style>
        </head><body>$content</body></html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                onWebView(this)
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            onWebView(wv)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Browser Preview — points at localhost or any URL (e.g. running dev server)
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPreview(
    url: String,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    // codespace-ide fix (2026-07-08): raw video/audio URLs (e.g. http://localhost:3000/test.mp4)
    // loaded directly via loadUrl() don't reliably render inline in this WebView — sometimes a
    // blank page, sometimes nothing at all. Auto-detect known media extensions and wrap them in a
    // minimal HTML page with a real <video>/<audio> tag instead, same trick documented as a manual
    // workaround in the proot debug notes — now automatic, no hand-written wrapper file needed.
    var lastLoadedUrl by remember { mutableStateOf("") }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // Without this, autoplay on the generated <video>/<audio> wrapper silently no-ops
                // until the user taps once — looks exactly like "the video doesn't show".
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { onLoading(true) }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle(view?.title ?: "")
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        onLoading(false)
                        val errHtml = """<html><body style="background:#1e1e1e;color:#f48771;font-family:sans-serif;padding:24px;display:flex;align-items:center;justify-content:center;min-height:80vh;text-align:center;">
                            <div><h2>Cannot connect</h2><p>$description</p><p style="color:#717171;font-size:13px;">Is your server running on ${url}?</p></div></body></html>"""
                        view?.loadDataWithBaseURL(null, errHtml, "text/html", "UTF-8", null)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) { onTitle(title ?: "") }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            if (lastLoadedUrl != url) {
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
            }
            onWebView(wv)
        },
        modifier = Modifier.fillMaxSize(),
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
) {
    // Default Remotion Studio runs on port 3000
    val remotionUrl = if (url.isBlank()) "http://localhost:3000" else url

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle(view?.title ?: "Remotion Studio")
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        onLoading(false)
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
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) { onTitle(title ?: "") }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            if (wv.url != remotionUrl) wv.loadUrl(remotionUrl)
            onWebView(wv)
        },
        modifier = Modifier.fillMaxSize(),
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

// ─────────────────────────────────────────────────────────────────────────────
// Dashboard Preview — Interactive drag-and-drop dashboard builder
// AI can generate dashboards, user can drag components, charts via Chart.js
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DashboardPreview(
    activeFilePath: String,
    onWebView: (WebView) -> Unit,
    onTitle: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    // Support: .html files with dashboard content, .json dashboard specs, or default template
    val dashboardHtml by produceState(initialValue = "", key1 = activeFilePath) {
        val dashFile = java.io.File(activeFilePath)
        val dashContent = if (activeFilePath.isNotBlank() && dashFile.exists()) {
            val content = try { dashFile.readText() } catch (_: Exception) { "" }
            if (content.contains("<html") || content.contains("<div id=\"dashboard\"")) {
                // Full HTML dashboard file — render as-is
                content
            } else if (activeFilePath.endsWith(".json") && content.trimStart().startsWith("{")) {
                // JSON dashboard spec — convert to HTML
                try {
                    val spec = JSONObject(content)
                    generateDashboardFromJson(spec)
                } catch (_: Exception) {
                    generateDefaultDashboard()
                }
            } else {
                generateDefaultDashboard()
            }
        } else {
            generateDefaultDashboard()
        }
        value = dashContent
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onTitle("Dashboard")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        return true
                    }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL("https://cdn.jsdelivr.net", dashboardHtml, "text/html", "UTF-8", null)
            onWebView(wv)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Shared dashboard CSS — used by both the interactive default builder and AI-generated specs ──
private val DASHBOARD_STYLES = """
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1a1a2e; color:#eee; font-family:'Segoe UI',system-ui,sans-serif; overflow-x:hidden; min-height:100vh; -webkit-user-select:none; }
.dashboard-title { text-align:center; padding:14px; font-size:16px; font-weight:700; color:#e94560; }
#toolbar { position:sticky; top:0; z-index:100; background:#16213e; padding:8px 12px; display:flex; gap:8px; overflow-x:auto; border-bottom:1px solid #0f3460; align-items:center; }
#toolbar label { font-size:11px; color:#888; white-space:nowrap; margin-right:4px; }
.palette-btn { background:#0f3460; color:#e94560; border:1px solid #e94560; border-radius:6px; padding:6px 12px; font-size:12px; cursor:pointer; white-space:nowrap; }
.palette-btn:active { transform:scale(0.95); }
.palette-btn.green { border-color:#4ecca3; color:#4ecca3; }
#dashboard { display:flex; flex-wrap:wrap; align-content:flex-start; gap:12px; padding:16px; min-height:calc(100vh - 50px); }
.widget { background:#16213e; border:1px solid #0f3460; border-radius:10px; padding:10px; position:relative; transition:box-shadow 0.2s; width:170px; height:150px; min-width:110px; min-height:90px; max-width:96vw; max-height:80vh; resize:both; overflow:auto; display:flex; flex-direction:column; }
.widget:hover { box-shadow:0 4px 20px rgba(233,69,96,0.3); border-color:#e94560; }
.widget.dragging { opacity:0.6; }
.widget-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; flex-shrink:0; cursor:move; touch-action:none; }
.widget-title { font-size:10px; color:#888; text-transform:uppercase; letter-spacing:1px; }
.widget-close { color:#e94560; cursor:pointer; font-size:15px; line-height:1; padding:2px 6px; border-radius:4px; }
.widget-body { flex:1; min-height:0; overflow:auto; }
.stat-value { font-size:22px; font-weight:700; color:#e94560; }
.stat-label { font-size:11px; color:#aaa; margin-top:3px; }
.stat-trend { font-size:10px; margin-top:5px; }
.stat-trend.up { color:#4ecca3; }
.stat-trend.down { color:#e94560; }
.chart-container { position:relative; height:100%; min-height:60px; }
.icon-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(50px,1fr)); gap:6px; }
.icon-item { display:flex; flex-direction:column; align-items:center; gap:3px; font-size:9px; color:#aaa; }
.icon-item .icon-circle { width:30px; height:30px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-size:15px; cursor:pointer; transition:transform 0.2s; }
.icon-item .icon-circle:active { transform:scale(0.9); }
.progress-bar { height:6px; background:#0f3460; border-radius:3px; margin-top:6px; overflow:hidden; }
.progress-fill { height:100%; border-radius:3px; transition:width 0.5s ease; }
.table-widget { width:100%; font-size:11px; }
.table-widget th { text-align:left; color:#888; padding:3px 6px; border-bottom:1px solid #0f3460; }
.table-widget td { padding:4px 6px; border-bottom:1px solid rgba(15,52,96,0.5); color:#ccc; }
.activity-item { display:flex; gap:6px; align-items:center; padding:5px 0; border-bottom:1px solid rgba(15,52,96,0.3); font-size:11px; }
.activity-dot { width:7px; height:7px; border-radius:50%; flex-shrink:0; }
.empty-state { text-align:center; padding:40px; color:#555; }
.empty-state h3 { font-size:16px; margin-bottom:8px; }
.empty-state p { font-size:13px; }
.empty-state .hint { margin-top:16px; font-size:11px; color:#e94560; }
"""

// ── Shared drag-to-reorder script — attaches to .widget-header (the drag handle). Actual
// resizing is native CSS `resize:both` on .widget, no JS needed for that. ──
private val DASHBOARD_DRAG_SCRIPT = """
(function(){
function mk(handle){
  var el=handle.closest('.widget');
  var sx,sy,ox,oy,dr=false;
  function dn(e){
    if(e.target.classList.contains('widget-close'))return;
    dr=true; el.classList.add('dragging');
    var t=e.touches?e.touches[0]:e; sx=t.clientX; sy=t.clientY;
    var r=el.getBoundingClientRect(), p=el.parentElement.getBoundingClientRect();
    ox=r.left-p.left; oy=r.top-p.top;
    el.style.position='absolute'; el.style.left=ox+'px'; el.style.top=oy+'px'; el.style.zIndex=999;
    e.preventDefault();
  }
  function mv(e){
    if(!dr)return;
    var t=e.touches?e.touches[0]:e;
    el.style.left=(ox+t.clientX-sx)+'px'; el.style.top=(oy+t.clientY-sy)+'px';
    e.preventDefault();
  }
  function up(){
    if(!dr)return;
    dr=false; el.classList.remove('dragging');
    el.style.zIndex=''; el.style.position=''; el.style.left=''; el.style.top='';
    var dash=el.parentElement;
    var ws=Array.from(dash.querySelectorAll('.widget'));
    ws.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return ar.top-br.top||ar.left-br.left;});
    ws.forEach(function(w){dash.appendChild(w);});
  }
  handle.addEventListener('mousedown',dn);
  handle.addEventListener('touchstart',dn,{passive:false});
  document.addEventListener('mousemove',mv);
  document.addEventListener('touchmove',mv,{passive:false});
  document.addEventListener('mouseup',up);
  document.addEventListener('touchend',up);
}
document.querySelectorAll('.widget-header').forEach(mk);
})();
"""

// ── JSON spec to dashboard HTML converter ──
private fun generateDashboardFromJson(spec: JSONObject): String {
    val title = spec.optString("title", "AI Dashboard")
    val widgets = spec.optJSONArray("widgets") ?: return generateDefaultDashboard()

    val widgetHtml = StringBuilder()
    for (i in 0 until widgets.length()) {
        val w = widgets.getJSONObject(i)
        val type = w.optString("type", "stat")
        val wTitle = w.optString("title", "Widget")
        val bodyHtml = StringBuilder()
        var extraScript = ""
        when (type) {
            "stat" -> {
                val value = w.optString("value", "0")
                val label = w.optString("label", "")
                val trend = w.optString("trend", "")
                val trendDir = w.optString("trendDirection", "up")
                val arrow = if (trendDir == "up") "&#9650;" else "&#9660;"
                bodyHtml.append("<div class=\"stat-value\">").append(value).append("</div>")
                    .append("<div class=\"stat-label\">").append(label).append("</div>")
                    .append("<div class=\"stat-trend ").append(trendDir).append("\">").append(arrow).append(" ").append(trend).append("</div>")
            }
            "chart" -> {
                val chartType = w.optString("chartType", "bar")
                val color = w.optString("color", "#e94560")
                val labelsArr = mutableListOf<String>()
                w.optJSONArray("labels")?.let { arr -> for (j in 0 until arr.length()) labelsArr.add(arr.getString(j)) }
                if (labelsArr.isEmpty()) labelsArr.addAll(listOf("Mon", "Tue", "Wed", "Thu", "Fri"))
                val dataArr = mutableListOf<Double>()
                w.optJSONArray("data")?.let { arr -> for (j in 0 until arr.length()) dataArr.add(arr.getDouble(j)) }
                if (dataArr.isEmpty()) dataArr.addAll(listOf(30.0, 50.0, 45.0, 60.0, 40.0))
                val jsLabels = labelsArr.joinToString(",") { "'$it'" }
                val jsData = dataArr.joinToString(",")
                bodyHtml.append("<div class=\"chart-container\"><canvas id=\"chart_").append(i).append("\"></canvas></div>")
                extraScript = "<script>setTimeout(function(){var ctx=document.getElementById('chart_" + i + "');if(ctx){ctx.closest('.widget')._chart=new Chart(ctx,{type:'" + chartType +
                    "',data:{labels:[" + jsLabels + "],datasets:[{data:[" + jsData +
                    "],backgroundColor:'" + color + "',borderRadius:4}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{grid:{display:false},ticks:{color:'#888'}},y:{grid:{color:'#0f3460'},ticks:{color:'#888'}}}}});}},100);</script>"
            }
            "progress" -> {
                val pct = w.optInt("percent", 50)
                val label = w.optString("label", "Progress")
                val color = w.optString("color", "#e94560")
                bodyHtml.append("<div class=\"stat-value\" style=\"color:").append(color).append("\">").append(pct).append("%</div>")
                    .append("<div class=\"progress-bar\"><div class=\"progress-fill\" style=\"width:").append(pct).append("%;background:").append(color).append("\"></div></div>")
                    .append("<div class=\"stat-label\" style=\"margin-top:6px\">").append(label).append("</div>")
            }
            "table" -> {
                val headers = mutableListOf<String>()
                w.optJSONArray("headers")?.let { arr -> for (j in 0 until arr.length()) headers.add(arr.getString(j)) }
                if (headers.isEmpty()) headers.addAll(listOf("Name", "Value"))
                val rows = w.optJSONArray("rows") ?: JSONArray()
                val headerHtml = headers.joinToString("") { "<th>$it</th>" }
                val rowsHtml = StringBuilder()
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    val cells = StringBuilder()
                    for (c in 0 until row.length()) cells.append("<td>").append(row.getString(c)).append("</td>")
                    rowsHtml.append("<tr>").append(cells).append("</tr>")
                }
                bodyHtml.append("<table class=\"table-widget\"><tr>").append(headerHtml).append("</tr>").append(rowsHtml).append("</table>")
            }
            "icons" -> {
                val icons = w.optJSONArray("icons") ?: JSONArray()
                val iconHtml = StringBuilder()
                for (ic in 0 until icons.length()) {
                    val icon = icons.getJSONObject(ic)
                    val emoji = icon.optString("icon", "&#128204;")
                    val label = icon.optString("label", "")
                    val color = icon.optString("color", "#3a86ff")
                    iconHtml.append("<div class=\"icon-item\"><div class=\"icon-circle\" style=\"background:")
                        .append(color).append("22;color:").append(color).append("\" onclick=\"this.style.transform='scale(1.3)';setTimeout(()=>this.style.transform='',150)\">")
                        .append(emoji).append("</div><span>").append(label).append("</span></div>")
                }
                bodyHtml.append("<div class=\"icon-grid\">").append(iconHtml).append("</div>")
            }
            else -> {
                bodyHtml.append(w.optString("html", ""))
            }
        }
        widgetHtml.append("<div class=\"widget\">")
            .append("<div class=\"widget-header\"><span class=\"widget-title\">").append(wTitle).append("</span>")
            .append("<span class=\"widget-close\" onclick=\"var w=this.closest('.widget');if(w._chart){w._chart.destroy();}w.remove();\">&times;</span></div>")
            .append("<div class=\"widget-body\">").append(bodyHtml).append("</div></div>")
            .append(extraScript)
    }

    return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=no\">" +
        "<title>" + title + "</title>" +
        "<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js\"></script>" +
        "<style>" + DASHBOARD_STYLES + "</style></head><body>" +
        "<div class=\"dashboard-title\">" + title + "</div>" +
        "<div id=\"dashboard\">" + widgetHtml + "</div>" +
        "<script>" + DASHBOARD_DRAG_SCRIPT + "</script>" +
        "</body></html>"
}

// ── Default dashboard template ──
private fun generateDefaultDashboard(): String {
    val js = """
(function(){
var wid=0;
var dash=document.getElementById('dashboard'), es=document.getElementById('emptyState');
function hideE(){ if(es) es.style.display='none'; }
function showE(){ if(es && dash.children.length===0) es.style.display='block'; }
function mkDrag(handle){
  var el=handle.closest('.widget');
  var sx,sy,ox,oy,dr=false;
  function dn(e){
    if(e.target.classList.contains('widget-close'))return;
    dr=true; el.classList.add('dragging');
    var t=e.touches?e.touches[0]:e; sx=t.clientX; sy=t.clientY;
    var r=el.getBoundingClientRect(), p=el.parentElement.getBoundingClientRect();
    ox=r.left-p.left; oy=r.top-p.top;
    el.style.position='absolute'; el.style.left=ox+'px'; el.style.top=oy+'px'; el.style.zIndex=999;
    e.preventDefault();
  }
  function mv(e){
    if(!dr)return;
    var t=e.touches?e.touches[0]:e;
    el.style.left=(ox+t.clientX-sx)+'px'; el.style.top=(oy+t.clientY-sy)+'px';
    e.preventDefault();
  }
  function up(){
    if(!dr)return;
    dr=false; el.classList.remove('dragging');
    el.style.zIndex=''; el.style.position=''; el.style.left=''; el.style.top='';
    var ws=Array.from(dash.querySelectorAll('.widget'));
    ws.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return ar.top-br.top||ar.left-br.left;});
    ws.forEach(function(w){dash.appendChild(w);});
  }
  handle.addEventListener('mousedown',dn);
  handle.addEventListener('touchstart',dn,{passive:false});
  document.addEventListener('mousemove',mv);
  document.addEventListener('touchmove',mv,{passive:false});
  document.addEventListener('mouseup',up);
  document.addEventListener('touchend',up);
}
function addW(type){
  hideE();
  var id='w'+(++wid);
  var el=document.createElement('div'); el.className='widget'; el.id=id;
  var titleText='Widget';
  if(type==='stat')titleText='Revenue';
  else if(type==='chart')titleText='Weekly Activity';
  else if(type==='progress')titleText='Project Progress';
  else if(type==='table')titleText='Recent Files';
  else if(type==='activity')titleText='Activity Feed';
  else if(type==='icons')titleText='Quick Actions';
  el.innerHTML='<div class="widget-header"><span class="widget-title">'+titleText+'</span><span class="widget-close">&times;</span></div><div class="widget-body"></div>';
  dash.appendChild(el);
  var header=el.querySelector('.widget-header');
  var closeBtn=el.querySelector('.widget-close');
  var ct=el.querySelector('.widget-body');
  closeBtn.onclick=function(e){ e.stopPropagation(); if(el._chart){el._chart.destroy();} el.remove(); showE(); };
  mkDrag(header);
  if(type==='stat'){
    var v=Math.floor(Math.random()*90000+10000).toString().replace(/\B(?=(\d{3})+(?!\d))/g,',');
    var tr=(Math.random()*20+5).toFixed(1);
    ct.innerHTML='<div class="stat-value">$'+v+'</div><div class="stat-label">Total this month</div><div class="stat-trend up">&#9650; '+tr+'% vs last month</div>';
  } else if(type==='chart'){
    ct.innerHTML='<div class="chart-container"><canvas></canvas></div>';
    setTimeout(function(){
      var cv=el.querySelector('canvas');
      if(cv){ el._chart=new Chart(cv,{type:'bar',data:{labels:['Mon','Tue','Wed','Thu','Fri','Sat','Sun'],datasets:[{data:Array.from({length:7},function(){return Math.floor(Math.random()*100)}),backgroundColor:'#e94560',borderRadius:4}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{grid:{display:false},ticks:{color:'#888'}},y:{grid:{color:'#0f3460'},ticks:{color:'#888'}}}}}); }
    },50);
  } else if(type==='progress'){
    var p=Math.floor(Math.random()*80+20);
    var c=['#e94560','#4ecca3','#f9a826','#3a86ff'][Math.floor(Math.random()*4)];
    ct.innerHTML='<div class="stat-value" style="color:'+c+'">'+p+'%</div><div class="progress-bar"><div class="progress-fill" style="width:'+p+'%;background:'+c+'"></div></div><div class="stat-label" style="margin-top:6px">'+Math.floor(Math.random()*15+5)+' tasks remaining</div>';
  } else if(type==='table'){
    ct.innerHTML='<table class="table-widget"><tr><th>Name</th><th>Size</th><th>Mod</th></tr><tr><td>Main.kt</td><td>12KB</td><td>2h</td></tr><tr><td>styles.css</td><td>4KB</td><td>5h</td></tr><tr><td>index.html</td><td>8KB</td><td>1d</td></tr></table>';
  } else if(type==='activity'){
    var acts=[{c:'#4ecca3',t:'Build #854 succeeded',tm:'2m ago'},{c:'#e94560',t:'Build #853 failed',tm:'1h ago'},{c:'#f9a826',t:'New branch created',tm:'3h ago'},{c:'#3a86ff',t:'Commit pushed',tm:'5h ago'}];
    ct.innerHTML=acts.map(function(a){return '<div class="activity-item"><div class="activity-dot" style="background:'+a.c+'"></div><span style="flex:1">'+a.t+'</span><span style="color:#555">'+a.tm+'</span></div>'}).join('');
  } else if(type==='icons'){
    var ics=[{e:'\uD83D\uDCC1',l:'Files',c:'#3a86ff'},{e:'\uD83D\uDD27',l:'Settings',c:'#f9a826'},{e:'\uD83D\uDCCA',l:'Stats',c:'#4ecca3'},{e:'\uD83D\uDD12',l:'Security',c:'#e94560'},{e:'\uD83C\uDFA8',l:'Themes',c:'#a855f7'},{e:'\uD83D\uDCE6',l:'Packages',c:'#3a86ff'},{e:'\uD83D\uDE80',l:'Deploy',c:'#4ecca3'},{e:'\uD83D\uDCAC',l:'Chat',c:'#e94560'}];
    ct.innerHTML='<div class="icon-grid">'+ics.map(function(i){return '<div class="icon-item"><div class="icon-circle" style="background:'+i.c+'22;color:'+i.c+'" onclick="this.style.transform=\'scale(1.3)\';setTimeout(()=>this.style.transform=\'\',150)">'+i.e+'</div><span>'+i.l+'</span></div>'}).join('')+'</div>';
  }
  el.style.opacity='0'; el.style.transform='scale(0.8)';
  requestAnimationFrame(function(){
    el.style.transition='opacity 0.3s,transform 0.3s';
    el.style.opacity='1'; el.style.transform='scale(1)';
    setTimeout(function(){ el.style.transition='box-shadow 0.2s'; },300);
  });
}
window.addWidget=addW;
window.clearAll=function(){
  dash.querySelectorAll('.widget').forEach(function(w){ if(w._chart)w._chart.destroy(); w.remove(); });
  showE();
};
window.exportDashboard=function(){
  var html='<!DOCTYPE html>\n'+document.documentElement.outerHTML;
  var b=new Blob([html],{type:'text/html'});
  var u=URL.createObjectURL(b);
  var a=document.createElement('a'); a.href=u; a.download='dashboard.html'; a.click();
  URL.revokeObjectURL(u);
};
setTimeout(function(){
  if(dash.querySelectorAll('.widget').length===0){
    addW('stat'); setTimeout(function(){addW('chart');},100); setTimeout(function(){addW('progress');},200);
  }
},300);
})();
"""
    return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=no\">" +
        "<title>Dashboard</title>" +
        "<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js\"></script>" +
        "<style>" + DASHBOARD_STYLES + "</style></head><body>" +
        "<div id=\"toolbar\"><label>Add:</label>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('stat')\">+ Stat Card</button>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('chart')\">+ Chart</button>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('progress')\">+ Progress</button>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('table')\">+ Table</button>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('activity')\">+ Activity</button>" +
        "<button class=\"palette-btn\" onclick=\"addWidget('icons')\">+ Icon Grid</button>" +
        "<button class=\"palette-btn\" onclick=\"clearAll()\" style=\"border-color:#666;color:#666;margin-left:auto\">Clear All</button>" +
        "<button class=\"palette-btn green\" onclick=\"exportDashboard()\">Export HTML</button></div>" +
        "<div id=\"dashboard\"></div>" +
        "<div class=\"empty-state\" id=\"emptyState\" style=\"display:none\"><h3>No widgets yet</h3><p>Tap a button above to add a widget, or ask the AI to generate a dashboard.</p><div class=\"hint\">Drag a widget's header to reorder it — drag the bottom-right corner to resize it.</div></div>" +
        "<script>" + js + "</script>" +
        "</body></html>"
}
