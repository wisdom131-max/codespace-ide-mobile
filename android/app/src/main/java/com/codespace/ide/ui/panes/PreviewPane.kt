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
                    Icons.Default.OpenInNew,
                    contentDescription = "Open in browser",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── Browser address bar (only in BROWSER mode) ────────────────────
        if (activeMode == PreviewMode.BROWSER || activeMode == PreviewMode.REMOTION) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (activeMode == PreviewMode.REMOTION) Icons.Default.Movie else Icons.Default.Lock,
                    null, tint = TextMuted, modifier = Modifier.size(16.dp)
                )
                OutlinedTextField(
                    value = browserInput,
                    onValueChange = { browserInput = it },
                    modifier = Modifier
                        .weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        color = TextPrimary,
                    ),
                    placeholder = { Text("http://localhost:3000", fontSize = 13.sp, color = TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                Button(
                    onClick = {
                        browserUrl = browserInput
                        webViewRef?.loadUrl(browserInput)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Go", fontSize = 13.sp)
                }
            }
        }

        Divider(color = Border, thickness = 1.dp)

        // ── Page title strip ─────────────────────────────────────────────
        if (pageTitle.isNotBlank() && activeMode != PreviewMode.BROWSER) {
            Text(
                pageTitle,
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 1,
            )
        }

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

        // ── WebView ──────────────────────────────────────────────────────
        Box(Modifier.fillMaxSize()) {
            when (activeMode) {
                PreviewMode.HTML     -> HtmlPreview(content, language, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
                PreviewMode.MARKDOWN -> MarkdownPreview(content, onWebView = { webViewRef = it }, onLoading = { isLoading = it })
                PreviewMode.SVG      -> SvgPreview(content, onWebView = { webViewRef = it })
                PreviewMode.BROWSER  -> BrowserPreview(browserUrl, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
                PreviewMode.DASHBOARD -> DashboardPreview(activeFilePath, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
                PreviewMode.REMOTION -> RemotionPreview(browserUrl, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
            }
        }
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
    val html = remember(content, language) {
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
            else -> content.ifBlank {
                """<!DOCTYPE html><html><body style="background:#1e1e1e;color:#717171;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;">
                   <p>Open an HTML file to preview it here.</p></body></html>"""
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
            if (wv.url != url) wv.loadUrl(url)
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
                    val spec = org.json.JSONObject(content)
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

// ── JSON spec to dashboard HTML converter ──
private fun generateDashboardFromJson(spec: org.json.JSONObject): String {
    val title = spec.optString("title", "AI Dashboard")
    val widgets = spec.optJSONArray("widgets") ?: return generateDefaultDashboard()
    
    val widgetHtml = StringBuilder()
    for (i in 0 until widgets.length()) {
        val w = widgets.getJSONObject(i)
        val type = w.optString("type", "stat")
        val wTitle = w.optString("title", "Widget")
        when (type) {
            "stat" -> {
                val value = w.optString("value", "0")
                val label = w.optString("label", "")
                val trend = w.optString("trend", "")
                val trendDir = w.optString("trendDirection", "up")
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      <div class="stat-value">$value</div>
                      <div class="stat-label">$label</div>
                      <div class="stat-trend $trendDir">${if (trendDir == "up") "&#9650;" else "&#9660;"} $trend</div>
                    </div>
                """.trimIndent())
            }
            "chart" -> {
                val chartType = w.optString("chartType", "bar")
                val labels = w.optJSONArray("labels")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                } ?: listOf("Mon","Tue","Wed","Thu","Fri")
                val data = w.optJSONArray("data")?.let { arr ->
                    List(arr.length()) { arr.getDouble(it) }
                } ?: listOf(30.0, 50.0, 45.0, 60.0, 40.0)
                val color = w.optString("color", "#e94560")
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="if(this.parentElement._chart)this.parentElement._chart.destroy();this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      <div class="chart-container"><canvas id="chart_$i"></canvas></div>
                      <script>
                        setTimeout(function() {
                          var ctx = document.getElementById('chart_$i');
                          if (ctx) {
                            var chart = new Chart(ctx, {
                              type: '$chartType',
                              data: { labels: [${labels.map { "'$it'" }.joinToString(",")}],
                              datasets: [{ data: [${data.joinToString(",")}], backgroundColor: '$color', borderRadius: 4 }]
                            });
                            ctx.parentElement.parentElement._chart = chart;
                          }
                        }, 100);
                      </script>
                    </div>
                """.trimIndent())
            }
            "progress" -> {
                val pct = w.optInt("percent", 50)
                val label = w.optString("label", "Progress")
                val color = w.optString("color", "#e94560")
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      <div class="stat-value" style="color:$color">${pct}%</div>
                      <div class="progress-bar"><div class="progress-fill" style="width:${pct}%;background:$color"></div></div>
                      <div class="stat-label" style="margin-top:6px">$label</div>
                    </div>
                """.trimIndent())
            }
            "table" -> {
                val headers = w.optJSONArray("headers")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                } ?: listOf("Name", "Value")
                val rows = w.optJSONArray("rows") ?: org.json.JSONArray()
                val headerHtml = headers.joinToString("") { "<th>$it</th>" }
                val rowsHtml = StringBuilder()
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    val cells = List(row.length()) { "<td>${row.getString(it)}</td>" }.joinToString("")
                    rowsHtml.append("<tr>$cells</tr>")
                }
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      <table class="table-widget"><tr>$headerHtml</tr>$rowsHtml</table>
                    </div>
                """.trimIndent())
            }
            "icons" -> {
                val icons = w.optJSONArray("icons") ?: org.json.JSONArray()
                val iconHtml = StringBuilder()
                for (ic in 0 until icons.length()) {
                    val icon = icons.getJSONObject(ic)
                    val emoji = icon.optString("icon", "&#128204;")
                    val label = icon.optString("label", "")
                    val color = icon.optString("color", "#3a86ff")
                    iconHtml.append("""
                        <div class="icon-item">
                          <div class="icon-circle" style="background:${color}22;color:$color"
                            onclick="this.style.transform='scale(1.3)';setTimeout(()=>this.style.transform='',150)">$emoji</div>
                          <span>$label</span>
                        </div>
                    """.trimIndent())
                }
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      <div class="icon-grid">$iconHtml</div>
                    </div>
                """.trimIndent())
            }
            else -> {
                val customHtml = w.optString("html", "")
                widgetHtml.append("""
                    <div class="widget">
                      <span class="widget-close" onclick="this.parentElement.remove()">&times;</span>
                      <div class="widget-header"><span class="widget-title">$wTitle</span></div>
                      $customHtml
                    </div>
                """.trimIndent())
            }
        }
    }
    
    return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>$title</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: #1a1a2e; color: #eee; font-family: 'Segoe UI', system-ui, sans-serif;
    overflow-x: hidden; min-height: 100vh; user-select: none; -webkit-user-select: none; }
  #toolbar { position: sticky; top: 0; z-index: 100; background: #16213e; padding: 8px 12px;
    display: flex; gap: 8px; overflow-x: auto; border-bottom: 1px solid #0f3460; align-items: center; }
  #toolbar label { font-size: 11px; color: #888; white-space: nowrap; margin-right: 4px; }
  .palette-btn { background: #0f3460; color: #e94560; border: 1px solid #e94560; border-radius: 6px;
    padding: 6px 12px; font-size: 12px; cursor: pointer; white-space: nowrap; transition: all 0.2s; }
  .palette-btn:hover { background: #e94560; color: #fff; }
  .palette-btn:active { transform: scale(0.95); }
  #dashboard { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px; padding: 16px; min-height: calc(100vh - 50px); }
  .widget { background: #16213e; border: 1px solid #0f3460; border-radius: 10px;
    padding: 16px; cursor: move; position: relative; transition: box-shadow 0.2s, transform 0.1s;
    touch-action: none; min-height: 80px; }
  .widget:hover { box-shadow: 0 4px 20px rgba(233,69,96,0.3); border-color: #e94560; }
  .widget.dragging { opacity: 0.6; transform: scale(0.95); }
  .widget-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
  .widget-title { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: 1px; }
  .widget-close { color: #e94560; cursor: pointer; font-size: 16px; line-height: 1; padding: 2px 6px; border-radius: 4px; }
  .widget-close:hover { background: rgba(233,69,96,0.2); }
  .stat-value { font-size: 28px; font-weight: 700; color: #e94560; }
  .stat-label { font-size: 12px; color: #aaa; margin-top: 4px; }
  .stat-trend { font-size: 11px; margin-top: 6px; }
  .stat-trend.up { color: #4ecca3; }
  .stat-trend.down { color: #e94560; }
  .chart-container { position: relative; height: 120px; margin-top: 8px; }
  .icon-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 8px; }
  .icon-item { display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 10px; color: #aaa; }
  .icon-item .icon-circle { width: 36px; height: 36px; border-radius: 50%; display: flex;
    align-items: center; justify-content: center; font-size: 18px; cursor: pointer; transition: transform 0.2s; }
  .icon-item .icon-circle:hover { transform: scale(1.15); }
  .icon-item .icon-circle:active { transform: scale(0.9); }
  .progress-bar { height: 6px; background: #0f3460; border-radius: 3px; margin-top: 8px; overflow: hidden; }
  .progress-fill { height: 100%; border-radius: 3px; transition: width 0.5s ease; }
  .table-widget { width: 100%; font-size: 12px; margin-top: 8px; }
  .table-widget th { text-align: left; color: #888; padding: 4px 8px; border-bottom: 1px solid #0f3460; }
  .table-widget td { padding: 6px 8px; border-bottom: 1px solid rgba(15,52,96,0.5); color: #ccc; }
  .activity-item { display: flex; gap: 8px; align-items: center; padding: 6px 0; border-bottom: 1px solid rgba(15,52,96,0.3); font-size: 12px; }
  .activity-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
  .dashboard-title { text-align: center; padding: 16px; font-size: 18px; font-weight: 700; color: #e94560; }
</style>
</head>
<body>
<div class="dashboard-title">$title</div>
<div id="toolbar">
  <label>Add:</label>
  <button class="palette-btn" onclick="addWidget('stat')">+ Stat</button>
  <button class="palette-btn" onclick="addWidget('chart')">+ Chart</button>
  <button class="palette-btn" onclick="addWidget('progress')">+ Progress</button>
  <button class="palette-btn" onclick="addWidget('table')">+ Table</button>
  <button class="palette-btn" onclick="addWidget('icons')">+ Icons</button>
</div>
<div id="dashboard">$widgetHtml</div>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<script>
(function() {
  function makeDraggable(el) {
    let startX, startY, origX, origY, dragging = false;
    function onDown(e) {
      if (e.target.classList.contains('widget-close')) return;
      dragging = true; el.classList.add('dragging');
      var touch = e.touches ? e.touches[0] : e;
      startX = touch.clientX; startY = touch.clientY;
      var rect = el.getBoundingClientRect();
      var parent = el.parentElement.getBoundingClientRect();
      origX = rect.left - parent.left; origY = rect.top - parent.top;
      el.style.position = 'absolute'; el.style.left = origX+'px'; el.style.top = origY+'px'; el.style.zIndex = 999;
      e.preventDefault();
    }
    function onMove(e) {
      if (!dragging) return;
      var touch = e.touches ? e.touches[0] : e;
      el.style.left = (origX + touch.clientX - startX)+'px';
      el.style.top = (origY + touch.clientY - startY)+'px';
      e.preventDefault();
    }
    function onUp() {
      if (!dragging) return;
      dragging = false; el.classList.remove('dragging'); el.style.zIndex = '';
      el.style.position = ''; el.style.left = ''; el.style.top = '';
      var widgets = Array.from(document.querySelectorAll('.widget'));
      widgets.sort(function(a,b) {
        var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
        return ar.top-br.top || ar.left-br.left;
      });
      widgets.forEach(function(w){ document.getElementById('dashboard').appendChild(w); });
    }
    el.addEventListener('mousedown',onDown);
    el.addEventListener('touchstart',onDown,{passive:false});
    document.addEventListener('mousemove',onMove);
    document.addEventListener('touchmove',onMove,{passive:false});
    document.addEventListener('mouseup',onUp);
    document.addEventListener('touchend',onUp);
  }
  document.querySelectorAll('.widget').forEach(makeDraggable);
  // Initialize charts from AI-generated widgets
  document.querySelectorAll('canvas[id^="chart_"]').forEach(function(canvas) {
    var script = canvas.closest('.widget').querySelector('script');
    if (script) { try { eval(script.textContent); } catch(e){} }
  });
})();
</script>
</body>
</html>
    """.trimIndent()
}

// ── Default dashboard template with drag-and-drop + Chart.js ──
private fun generateDefaultDashboard(): String {
    return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Dashboard</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    background: #1a1a2e; color: #eee; font-family: 'Segoe UI', system-ui, sans-serif;
    overflow-x: hidden; min-height: 100vh; user-select: none; -webkit-user-select: none;
  }
  #toolbar {
    position: sticky; top: 0; z-index: 100; background: #16213e; padding: 8px 12px;
    display: flex; gap: 8px; overflow-x: auto; border-bottom: 1px solid #0f3460;
    align-items: center;
  }
  #toolbar label { font-size: 11px; color: #888; white-space: nowrap; margin-right: 4px; }
  .palette-btn {
    background: #0f3460; color: #e94560; border: 1px solid #e94560; border-radius: 6px;
    padding: 6px 12px; font-size: 12px; cursor: pointer; white-space: nowrap;
    transition: all 0.2s;
  }
  .palette-btn:hover { background: #e94560; color: #fff; }
  .palette-btn:active { transform: scale(0.95); }
  #dashboard {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px; padding: 16px; min-height: calc(100vh - 50px);
  }
  .widget {
    background: #16213e; border: 1px solid #0f3460; border-radius: 10px;
    padding: 16px; cursor: move; position: relative; transition: box-shadow 0.2s, transform 0.1s;
    touch-action: none; min-height: 80px;
  }
  .widget:hover { box-shadow: 0 4px 20px rgba(233,69,96,0.3); border-color: #e94560; }
  .widget.dragging { opacity: 0.6; transform: scale(0.95); }
  .widget-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
  .widget-title { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: 1px; }
  .widget-close { color: #e94560; cursor: pointer; font-size: 16px; line-height: 1; padding: 2px 6px; border-radius: 4px; }
  .widget-close:hover { background: rgba(233,69,96,0.2); }
  .stat-value { font-size: 28px; font-weight: 700; color: #e94560; }
  .stat-label { font-size: 12px; color: #aaa; margin-top: 4px; }
  .stat-trend { font-size: 11px; margin-top: 6px; }
  .stat-trend.up { color: #4ecca3; }
  .stat-trend.down { color: #e94560; }
  .chart-container { position: relative; height: 120px; margin-top: 8px; }
  .icon-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 8px; }
  .icon-item { display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 10px; color: #aaa; }
  .icon-item .icon-circle {
    width: 36px; height: 36px; border-radius: 50%; display: flex; align-items: center;
    justify-content: center; font-size: 18px; cursor: pointer; transition: transform 0.2s;
  }
  .icon-item .icon-circle:hover { transform: scale(1.15); }
  .icon-item .icon-circle:active { transform: scale(0.9); }
  .progress-bar { height: 6px; background: #0f3460; border-radius: 3px; margin-top: 8px; overflow: hidden; }
  .progress-fill { height: 100%; border-radius: 3px; transition: width 0.5s ease; }
  .table-widget { width: 100%; font-size: 12px; margin-top: 8px; }
  .table-widget th { text-align: left; color: #888; padding: 4px 8px; border-bottom: 1px solid #0f3460; }
  .table-widget td { padding: 6px 8px; border-bottom: 1px solid rgba(15,52,96,0.5); color: #ccc; }
  .activity-item { display: flex; gap: 8px; align-items: center; padding: 6px 0; border-bottom: 1px solid rgba(15,52,96,0.3); font-size: 12px; }
  .activity-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
  .empty-state { text-align: center; padding: 40px; color: #555; }
  .empty-state h3 { font-size: 16px; margin-bottom: 8px; }
  .empty-state p { font-size: 13px; }
  .empty-state .hint { margin-top: 16px; font-size: 11px; color: #e94560; }
</style>
</head>
<body>

<div id="toolbar">
  <label>Add:</label>
  <button class="palette-btn" onclick="addWidget('stat')">+ Stat Card</button>
  <button class="palette-btn" onclick="addWidget('chart')">+ Chart</button>
  <button class="palette-btn" onclick="addWidget('progress')">+ Progress</button>
  <button class="palette-btn" onclick="addWidget('table')">+ Table</button>
  <button class="palette-btn" onclick="addWidget('activity')">+ Activity</button>
  <button class="palette-btn" onclick="addWidget('icons')">+ Icon Grid</button>
  <button class="palette-btn" onclick="clearAll()" style="border-color:#666;color:#666;margin-left:auto;">Clear All</button>
  <button class="palette-btn" onclick="exportDashboard()" style="border-color:#4ecca3;color:#4ecca3;">Export HTML</button>
</div>

<div id="dashboard">
  <div class="empty-state" id="emptyState">
    <h3>Interactive Dashboard</h3>
    <p>Tap any component button above to add widgets.</p>
    <p>Drag widgets to reposition. Tap X to remove.</p>
    <p style="margin-top:12px;">AI can generate dashboards by creating HTML files with div id="dashboard".</p>
    <p class="hint">Tip: Open an HTML file with dashboard content to load it here.</p>
  </div>
</div>

<script>
(function() {
  let widgetId = 0;
  let charts = {};
  const dashboard = document.getElementById('dashboard');
  const emptyState = document.getElementById('emptyState');

  function hideEmpty() { if (emptyState) emptyState.style.display = 'none'; }
  function showEmpty() { if (dashboard.children.length <= 1) emptyState.style.display = 'block'; }

  // ── Drag and drop logic (touch + mouse) ──
  function makeDraggable(el) {
    let startX, startY, origX, origY, dragging = false;
    function onDown(e) {
      if (e.target.classList.contains('widget-close')) return;
      dragging = true;
      el.classList.add('dragging');
      const touch = e.touches ? e.touches[0] : e;
      startX = touch.clientX; startY = touch.clientY;
      const rect = el.getBoundingClientRect();
      const parent = el.parentElement.getBoundingClientRect();
      origX = rect.left - parent.left; origY = rect.top - parent.top;
      el.style.position = 'absolute';
      el.style.left = origX + 'px';
      el.style.top = origY + 'px';
      el.style.zIndex = 999;
      e.preventDefault();
    }
    function onMove(e) {
      if (!dragging) return;
      const touch = e.touches ? e.touches[0] : e;
      const dx = touch.clientX - startX;
      const dy = touch.clientY - startY;
      el.style.left = (origX + dx) + 'px';
      el.style.top = (origY + dy) + 'px';
      e.preventDefault();
    }
    function onUp(e) {
      if (!dragging) return;
      dragging = false;
      el.classList.remove('dragging');
      el.style.zIndex = '';
      // Snap to grid position (convert back to flow)
      const parent = el.parentElement.getBoundingClientRect();
      const rect = el.getBoundingClientRect();
      const colWidth = (parent.width - 32) / Math.max(1, Math.floor(parent.width / 212));
      const col = Math.round((rect.left - parent.left - 16) / (colWidth + 12));
      const row = Math.round((rect.top - parent.top - 16) / 100);
      el.style.position = '';
      el.style.left = '';
      el.style.top = '';
      // Reorder DOM based on position
      const widgets = Array.from(dashboard.querySelectorAll('.widget'));
      widgets.sort((a, b) => {
        const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
        return ar.top - br.top || ar.left - br.left;
      });
      widgets.forEach(w => dashboard.appendChild(w));
      e.preventDefault();
    }
    el.addEventListener('mousedown', onDown);
    el.addEventListener('touchstart', onDown, {passive: false});
    document.addEventListener('mousemove', onMove);
    document.addEventListener('touchmove', onMove, {passive: false});
    document.addEventListener('mouseup', onUp);
    document.addEventListener('touchend', onUp);
  }

  // ── Widget creation ──
  function createWidget(type) {
    hideEmpty();
    const id = 'w' + (++widgetId);
    const el = document.createElement('div');
    el.className = 'widget';
    el.id = id;

    const close = document.createElement('span');
    close.className = 'widget-close';
    close.innerHTML = '&times;';
    close.onclick = (e) => {
      e.stopPropagation();
      if (charts[id]) { charts[id].destroy(); delete charts[id]; }
      el.remove();
      showEmpty();
    };
    el.appendChild(close);

    const content = document.createElement('div');
    el.appendChild(content);

    switch(type) {
      case 'stat':
        content.innerHTML = `
          <div class="widget-header"><span class="widget-title">Revenue</span></div>
          <div class="stat-value">$` + (Math.random()*90000+10000).toFixed(0).replace(/\B(?=(\d{3})+(?!\d))/g,',') + `</div>
          <div class="stat-label">Total this month</div>
          <div class="stat-trend up">&#9650; ` + (Math.random()*20+5).toFixed(1) + `% vs last month</div>`;
        break;
      case 'chart':
        content.innerHTML = `<div class="widget-header"><span class="widget-title">Weekly Activity</span></div><div class="chart-container"><canvas></canvas></div>`;
        setTimeout(() => {
          const canvas = el.querySelector('canvas');
          if (canvas) {
            charts[id] = new Chart(canvas, {
              type: 'bar',
              data: {
                labels: ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'],
                datasets: [{ data: Array.from({length:7},()=>Math.floor(Math.random()*100)), backgroundColor: '#e94560', borderRadius: 4 }]
              },
              options: { responsive: true, maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: { x: { grid:{display:false}, ticks:{color:'#888'} }, y: { grid:{color:'#0f3460'}, ticks:{color:'#888'} } } }
            });
          }
        }, 50);
        break;
      case 'progress':
        const pct = Math.floor(Math.random()*80+20);
        const color = ['#e94560','#4ecca3','#f9a826','#3a86ff'][Math.floor(Math.random()*4)];
        content.innerHTML = `
          <div class="widget-header"><span class="widget-title">Project Progress</span></div>
          <div class="stat-value" style="color:${color}">${pct}%</div>
          <div class="progress-bar"><div class="progress-fill" style="width:${pct}%;background:${color}"></div></div>
          <div class="stat-label" style="margin-top:6px">` + Math.floor(Math.random()*15+5) + ` tasks remaining</div>`;
        break;
      case 'table':
        content.innerHTML = `
          <div class="widget-header"><span class="widget-title">Recent Files</span></div>
          <table class="table-widget">
            <tr><th>Name</th><th>Size</th><th>Mod</th></tr>
            <tr><td>Main.kt</td><td>12KB</td><td>2h</td></tr>
            <tr><td>styles.css</td><td>4KB</td><td>5h</td></tr>
            <tr><td>index.html</td><td>8KB</td><td>1d</td></tr>
            <tr><td>config.json</td><td>2KB</td><td>3d</td></tr>
          </table>`;
        break;
      case 'activity':
        const activities = [
          {color:'#4ecca3', text:'Build #854 succeeded', time:'2m ago'},
          {color:'#e94560', text:'Build #853 failed', time:'1h ago'},
          {color:'#f9a826', text:'New branch created', time:'3h ago'},
          {color:'#3a86ff', text:'Commit pushed to main', time:'5h ago'},
        ];
        content.innerHTML = `<div class="widget-header"><span class="widget-title">Activity Feed</span></div>` +
          activities.map(a => `<div class="activity-item"><div class="activity-dot" style="background:${a.color}"></div><span style="flex:1">${a.text}</span><span style="color:#555">${a.time}</span></div>`).join('');
        break;
      case 'icons':
        const icons = [
          {emoji:'📁',label:'Files',color:'#3a86ff'},
          {emoji:'🔧',label:'Settings',color:'#f9a826'},
          {emoji:'📊',label:'Stats',color:'#4ecca3'},
          {emoji:'🔒',label:'Security',color:'#e94560'},
          {emoji:'🎨',label:'Themes',color:'#a855f7'},
          {emoji:'📦',label:'Packages',color:'#3a86ff'},
          {emoji:'🚀',label:'Deploy',color:'#4ecca3'},
          {emoji:'💬',label:'Chat',color:'#e94560'},
        ];
        content.innerHTML = `<div class="widget-header"><span class="widget-title">Quick Actions</span></div><div class="icon-grid">` +
          icons.map(i => `<div class="icon-item"><div class="icon-circle" style="background:${i.color}22;color:${i.color}" onclick="this.style.transform='scale(1.3)';setTimeout(()=>this.style.transform='',150)">${i.emoji}</div><span>${i.label}</span></div>`).join('') + `</div>`;
        break;
    }

    dashboard.appendChild(el);
    makeDraggable(el);

    // Add appear animation
    el.style.opacity = '0';
    el.style.transform = 'scale(0.8)';
    requestAnimationFrame(() => {
      el.style.transition = 'opacity 0.3s, transform 0.3s';
      el.style.opacity = '1';
      el.style.transform = 'scale(1)';
      setTimeout(() => { el.style.transition = 'box-shadow 0.2s, transform 0.1s'; }, 300);
    });
  }

  window.addWidget = createWidget;

  window.clearAll = function() {
    if (!confirm('Clear all widgets?')) return;
    Object.values(charts).forEach(c => c.destroy());
    charts = {};
    dashboard.querySelectorAll('.widget').forEach(w => w.remove());
    showEmpty();
  };

  window.exportDashboard = function() {
    const html = '<!DOCTYPE html>\n' + document.documentElement.outerHTML;
    const blob = new Blob([html], {type: 'text/html'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'dashboard.html'; a.click();
    URL.revokeObjectURL(url);
  };

  // Make existing widgets draggable
  dashboard.querySelectorAll('.widget').forEach(w => makeDraggable(w));

  // Add 3 default widgets on first load
  setTimeout(() => {
    if (Object.keys(charts).length === 0 && dashboard.querySelectorAll('.widget').length === 0) {
      createWidget('stat');
      setTimeout(() => createWidget('chart'), 100);
      setTimeout(() => createWidget('progress'), 200);
    }
  }, 300);
})();
</script>
</body>
</html>
    """.trimIndent()
}

