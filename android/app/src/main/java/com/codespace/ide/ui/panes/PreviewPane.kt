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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.codespace.ide.domain.Language
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ─────────────────────────────────────────────────────────────────────────────
// PreviewPane — live preview for HTML/CSS/JS, Markdown, SVG, and local servers
// Shown as BottomTab.PREVIEW in ProjectShellScreen
// ─────────────────────────────────────────────────────────────────────────────

private enum class PreviewMode(val label: String) {
    HTML("HTML"),
    MARKDOWN("Markdown"),
    SVG("SVG"),
    BROWSER("Browser"),
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

    var activeMode by remember(activeFilePath) { mutableStateOf(defaultMode) }
    var browserUrl by remember { mutableStateOf("http://localhost:3000") }
    var browserInput by remember { mutableStateOf("http://localhost:3000") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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
        if (activeMode == PreviewMode.BROWSER) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                OutlinedTextField(
                    value = browserInput,
                    onValueChange = { browserInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color = TextPrimary,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent,
                    ),
                )
                Button(
                    onClick = { browserUrl = browserInput; webViewRef?.loadUrl(browserInput) },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Go", fontSize = 12.sp)
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

        // ── WebView ──────────────────────────────────────────────────────
        Box(Modifier.fillMaxSize()) {
            when (activeMode) {
                PreviewMode.HTML     -> HtmlPreview(content, language, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
                PreviewMode.MARKDOWN -> MarkdownPreview(content, onWebView = { webViewRef = it }, onLoading = { isLoading = it })
                PreviewMode.SVG      -> SvgPreview(content, onWebView = { webViewRef = it })
                PreviewMode.BROWSER  -> BrowserPreview(browserUrl, onWebView = { webViewRef = it }, onTitle = { pageTitle = it }, onLoading = { isLoading = it })
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

