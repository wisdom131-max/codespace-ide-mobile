package com.codespace.ide.ui.panes

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * A real SVG viewer, not a thumbnail.
 *
 * What was here before rendered the file with JavaScript switched off: no zoom, no fit, no
 * export, and a malformed file came out as an empty rectangle with nothing said. The strong
 * extensions all do the same handful of things - render, zoom and pan deliberately, fit, 1:1,
 * honest transparency, raster export, data URI, and an error that names the problem - so this
 * surface does those. See docs/SVG-VIEWER-PLAN-2026-11-01.md for the survey it is based on.
 *
 * Deliberately absent: SVGO-style minification, which the extension that ships it warns mangles
 * files. A viewer renders; it does not rewrite the user's artwork.
 */
internal object SvgViewerModel {

    const val MIN_ZOOM: Float = 0.1f
    const val MAX_ZOOM: Float = 32f

    data class Intrinsic(val width: Float, val height: Float)

    /** Parse errors, in the same shape the toolbar shows: message plus the line it died on. */
    fun validate(svg: String): String? {
        val text = svg.trim()
        if (text.isEmpty()) return "This file is empty."
        return try {
            parse(text)
            null
        } catch (t: Throwable) {
            // SAXParseException carries the line, and the line is the whole point of the message:
            // "unexpected end of file" without a number sends the user hunting through 3000 lines.
            val sax = t as? org.xml.sax.SAXParseException
            if (sax != null) {
                val detail = sax.message?.trim().orEmpty().ifEmpty { "invalid XML" }
                "Line " + sax.lineNumber + ": " + detail
            } else {
                "Invalid SVG: " + (t.message?.trim().orEmpty().ifEmpty { "not valid XML" })
            }
        }
    }

    private fun parse(text: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(text)))
            .documentElement

    /**
     * The size the file asks for: width/height attributes, else the viewBox, else the browser's
     * 300x150 default. An SVG with neither is legal, and pretending otherwise would show a wrong
     * aspect ratio.
     */
    fun intrinsic(svg: String): Intrinsic {
        val root = try {
            parse(svg.trim())
        } catch (_: Throwable) {
            return Intrinsic(300f, 150f)
        }
        val w = root.getAttribute("width").toFloatOrNull()
        val h = root.getAttribute("height").toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) return Intrinsic(w, h)
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+"))
        if (viewBox.size == 4) {
            val vw = viewBox[2].toFloatOrNull()
            val vh = viewBox[3].toFloatOrNull()
            if (vw != null && vh != null && vw > 0f && vh > 0f) return Intrinsic(vw, vh)
        }
        return Intrinsic(300f, 150f)
    }

    /**
     * Element count, which is what tells you whether an export is the size you expect.
     *
     * Counts the root too: getElementsByTagName on an Element returns DESCENDANTS only, so a
     * `<svg><rect/></svg>` reported 1 and a reader who counted the tags themselves saw 2. The
     * number in the toolbar has to mean "elements in this file".
     */
    fun nodeCount(svg: String): Int {
        return try {
            parse(svg.trim()).getElementsByTagName("*").length + 1
        } catch (_: Throwable) {
            0
        }
    }

    fun sizeLabel(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> bytes.toString() + " B"
    }

    fun metricsLine(svg: String): String {
        val size = intrinsic(svg)
        val w = if (size.width % 1f == 0f) size.width.toInt().toString() else size.width.toString()
        val h = if (size.height % 1f == 0f) size.height.toInt().toString() else size.height.toString()
        return w + " x " + h + " - " + nodeCount(svg) + " nodes - " + sizeLabel(svg.toByteArray().size)
    }

    /** data: URI for pasting straight into CSS, the cssho "copy data URI scheme" feature. */
    fun dataUri(svg: String): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        return "data:image/svg+xml;base64," + encoded
    }
}

/**
 * The page that does the interactive work. Kept in one string because it ships inside the app:
 * there is no build step, no CDN and no network dependency, which matters on a phone.
 */
private object SvgViewerShell {

    const val PLACEHOLDER = "<!--SVG_DOCUMENT-->"

    fun html(svg: String, background: String): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
 html,body{margin:0;height:100%;overflow:hidden}
 body.checker{background-color:#1e1e1e;background-image:linear-gradient(45deg,#2a2a2a 25%,transparent 25%),linear-gradient(-45deg,#2a2a2a 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#2a2a2a 75%),linear-gradient(-45deg,transparent 75%,#2a2a2a 75%);background-size:16px 16px;background-position:0 0,0 8px,8px -8px,-8px 0}
 body.dark{background:#1e1e1e}
 body.light{background:#ffffff}
 body.white{background:#f2f2f2}
 #stage{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;touch-action:none;overflow:hidden}
 #holder{transform-origin:0 0;will-change:transform}
 #holder svg{display:block;max-width:none}
</style></head>
<body class="$background">
<div id="stage"><div id="holder">$svg</div></div>
<script>
(function(){
  var stage=document.getElementById('stage');
  var holder=document.getElementById('holder');
  var svg=holder.querySelector('svg');
  if(!svg){ try{SvgBridge.onError('No <svg> root element found.')}catch(e){} return; }
  function dims(){
    var w=parseFloat(svg.getAttribute('width')), h=parseFloat(svg.getAttribute('height'));
    if(!(w>0&&h>0)){
      var vb=svg.getAttribute('viewBox');
      if(vb){var p=vb.split(/[\s,]+/); if(p.length===4){w=parseFloat(p[2]);h=parseFloat(p[3]);}}
    }
    if(!(w>0&&h>0)){ try{var b=svg.getBBox(); w=b.width||300; h=b.height||150;}catch(err){w=300;h=150;} }
    return {w:w,h:h};
  }
  var d=dims(); var W=d.w, H=d.h;
  svg.setAttribute('width',W); svg.setAttribute('height',H);
  var z=1, tx=0, ty=0;
  function apply(){ holder.style.transform='translate('+tx+'px,'+ty+'px) scale('+z+')'; }
  function report(){ try{SvgBridge.onZoom(String(z))}catch(e){} }
  function clamp(n){ return Math.max(0.1, Math.min(32, n)); }
  function fit(){
    var sw=stage.clientWidth||1, sh=stage.clientHeight||1;
    z=clamp(Math.min(sw/W, sh/H)*0.94);
    tx=(sw-W*z)/2; ty=(sh-H*z)/2; apply(); report();
  }
  function setZoom(n){
    var sw=stage.clientWidth||1, sh=stage.clientHeight||1;
    var nz=clamp(n);
    var cx=(sw/2-tx)/z, cy=(sh/2-ty)/z;
    z=nz; tx=sw/2-cx*z; ty=sh/2-cy*z; apply(); report();
  }
  function actual(){ z=1; tx=((stage.clientWidth||1)-W)/2; ty=((stage.clientHeight||1)-H)/2; apply(); report(); }
  function background(name){ document.body.className=name; }
  function exportPng(){
    var clone=svg.cloneNode(true);
    clone.setAttribute('width',W); clone.setAttribute('height',H);
    var xml=new XMLSerializer().serializeToString(clone);
    var img=new Image();
    img.onload=function(){
      try{
        var c=document.createElement('canvas'); c.width=W; c.height=H;
        var ctx=c.getContext('2d'); ctx.drawImage(img,0,0,W,H);
        var url=c.toDataURL('image/png');
        SvgBridge.onPng(url.substring(url.indexOf(',')+1));
      }catch(err){ SvgBridge.onError('Could not rasterise: external references block the canvas.'); }
    };
    img.onerror=function(){ SvgBridge.onError('Could not rasterise this SVG.'); };
    img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(xml);
  }
  window.SVGViewer={fit:fit, setZoom:setZoom, actual:actual, background:background, exportPng:exportPng};

  // Gestures: one pointer pans, two pointers pinch about their midpoint.
  var pointers={}, lastDist=0, lastMid={x:0,y:0};
  function count(){ var n=0; for(var k in pointers) n++; return n; }
  function mid(){
    var mx=0,my=0,n=0;
    for(var k in pointers){ mx+=pointers[k].x; my+=pointers[k].y; n++; }
    return {x:mx/n, y:my/n};
  }
  function dist(){
    var keys=[]; for(var k in pointers) keys.push(k);
    if(keys.length<2) return 0;
    var a=pointers[keys[0]], b=pointers[keys[1]];
    return Math.sqrt(Math.pow(a.x-b.x,2)+Math.pow(a.y-b.y,2));
  }
  stage.addEventListener('pointerdown', function(e){
    pointers[e.pointerId]={x:e.clientX,y:e.clientY};
    lastDist=dist(); lastMid=mid();
    try{stage.setPointerCapture(e.pointerId)}catch(err){}
  });
  stage.addEventListener('pointermove', function(e){
    var p=pointers[e.pointerId];
    if(!p) return;
    var prev={x:p.x,y:p.y};
    p.x=e.clientX; p.y=e.clientY;
    if(count()===1){
      tx+=p.x-prev.x; ty+=p.y-prev.y; apply();
    } else if(count()>=2){
      var nd=dist();
      if(lastDist>0 && nd>0){
        var factor=nd/lastDist;
        var m=mid();
        z=clamp(z*factor);
        tx=m.x-(m.x-tx)*factor;
        ty=m.y-(m.y-ty)*factor;
        apply(); report();
      }
      lastDist=nd; lastMid=mid();
    }
  });
  function up(e){
    delete pointers[e.pointerId];
    lastDist=dist(); lastMid=mid();
  }
  stage.addEventListener('pointerup', up);
  stage.addEventListener('pointercancel', up);
  stage.addEventListener('pointerleave', up);
  window.addEventListener('resize', function(){ fit(); });
  fit();
})();
</script>
</body></html>
""".trimIndent()
}

/** Bridge for the page to talk back: zoom changes, raster exports, and render failures. */
internal class SvgViewerBridge(
    private val onZoom: (Float) -> Unit,
    private val onPng: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @android.webkit.JavascriptInterface
    fun onZoom(level: String) {
        val value = level.toFloatOrNull() ?: return
        main.post { onZoom(value) }
    }

    @android.webkit.JavascriptInterface
    fun onPng(base64: String) {
        main.post { onPng(base64) }
    }

    @android.webkit.JavascriptInterface
    fun onError(message: String) {
        main.post { onError(message) }
    }
}

private val SVG_TOOLBAR_LABEL = Color(0xFFB0B0B0)

@Composable
internal fun SvgViewer(
    content: String,
    fileName: String,
    onWebView: (WebView) -> Unit,
    servedBaseUrl: String? = null,
) {
    val context = LocalContext.current
    val validationError = remember(content) { SvgViewerModel.validate(content) }
    val metrics = remember(content) { SvgViewerModel.metricsLine(content) }
    var zoom by remember(content) { mutableStateOf(1f) }
    var background by remember { mutableStateOf("checker") }
    var showSource by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var webview by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember(content) {
        SvgViewerBridge(
            onZoom = { zoom = it },
            onPng = { base64 ->
                val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "svg-exports")
                dir.mkdirs()
                val name = fileName.substringAfterLast('/').ifEmpty { "image.svg" }
                    .substringBeforeLast('.').ifEmpty { "image" }
                val out = java.io.File(dir, name + "-" + System.currentTimeMillis() + ".png")
                try {
                    out.writeBytes(java.util.Base64.getDecoder().decode(base64))
                    message = "Saved " + out.name
                    com.codespace.ide.diagnostics.AppOutputLog.log("[SVG] exported " + out.absolutePath, "preview")
                } catch (t: Throwable) {
                    message = "Export failed: " + (t.message ?: "unknown error")
                }
            },
            onError = { message = it },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SvgToolbar(
            zoom = zoom,
            metrics = metrics,
            showSource = showSource,
            onZoomOut = {
                webview?.evaluateJavascript("window.SVGViewer.setZoom(" + (zoom / 1.5f) + ");", null)
            },
            onZoomIn = {
                webview?.evaluateJavascript("window.SVGViewer.setZoom(" + (zoom * 1.5f) + ");", null)
            },
            onFit = { webview?.evaluateJavascript("window.SVGViewer.fit();", null) },
            onActual = { webview?.evaluateJavascript("window.SVGViewer.actual();", null) },
            onCycleBackground = {
                val next = when (background) {
                    "checker" -> "dark"
                    "dark" -> "light"
                    "light" -> "white"
                    else -> "checker"
                }
                background = next
                webview?.evaluateJavascript("window.SVGViewer.background('" + next + "');", null)
            },
            onExportPng = { webview?.evaluateJavascript("window.SVGViewer.exportPng();", null) },
            onCopyDataUri = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("SVG data URI", SvgViewerModel.dataUri(content))
                )
                message = "Data URI copied"
            },
            onToggleSource = { showSource = !showSource },
        )
        message?.let { text ->
            Text(
                text,
                fontSize = 10.sp,
                color = Color(0xFF9CCC65),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (validationError != null) {
                SvgErrorBox(validationError)
            } else {
                SvgCanvas(
                    content = content,
                    background = background,
                    servedBaseUrl = servedBaseUrl,
                    bridge = bridge,
                    onWebViewCreated = {
                        webview = it
                        onWebView(it)
                    },
                )
            }
            if (showSource) {
                SvgSourcePanel(content = content, onDismiss = { showSource = false })
            }
        }
    }
}

@Composable
private fun SvgToolbar(
    zoom: Float,
    metrics: String,
    showSource: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onActual: () -> Unit,
    onCycleBackground: () -> Unit,
    onExportPng: () -> Unit,
    onCopyDataUri: () -> Unit,
    onToggleSource: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SvgToolbarButton(Icons.Default.Remove, "Zoom out", onZoomOut)
        Text(
            (zoom * 100).toInt().toString() + "%",
            fontSize = 11.sp,
            color = SVG_TOOLBAR_LABEL,
            modifier = Modifier.width(48.dp),
        )
        SvgToolbarButton(Icons.Default.Add, "Zoom in", onZoomIn)
        SvgToolbarButton(Icons.Default.FitScreen, "Fit", onFit)
        TextButton(onClick = onActual) { Text("1:1", fontSize = 11.sp) }
        SvgToolbarButton(Icons.Default.SwapVert, "Background", onCycleBackground)
        SvgToolbarButton(Icons.Default.SaveAlt, "Export PNG", onExportPng)
        SvgToolbarButton(Icons.Default.ContentCopy, "Copy data URI", onCopyDataUri)
        SvgToolbarButton(Icons.Default.Code, "Source", onToggleSource, active = showSource)
        Spacer(Modifier.width(6.dp))
        Text(metrics, fontSize = 9.sp, color = Color(0xFF808080))
    }
}

/** A 30dp box is the tap target and the click; the glyph inside stays 18dp. */
@Composable
private fun SvgToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) Color(0xFF4FC1FF) else Color(0xFFB0B0B0),
            modifier = Modifier.size(18.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SvgCanvas(
    content: String,
    background: String,
    servedBaseUrl: String?,
    bridge: SvgViewerBridge,
    onWebViewCreated: (WebView) -> Unit,
) {
    val html = remember(content, background) { SvgViewerShell.html(content, background) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.domStorageEnabled = false
                addJavascriptInterface(bridge, "SvgBridge")
                onWebViewCreated(this)
            }
        },
        update = { wv ->
            // The shell changes when the file or the background does; the zoom lives in the page.
            val key = content.hashCode().toString() + "|" + background
            if (wv.tag as? String != key) {
                wv.tag = key
                val base = servedBaseUrl?.substringBeforeLast('/', "")?.ifEmpty { null }
                wv.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { wv ->
            wv.removeJavascriptInterface("SvgBridge")
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SvgErrorBox(message: String) {
    Box(
        Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("This SVG did not parse", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A80))
            Spacer(Modifier.width(4.dp))
            Text(message, fontSize = 11.sp, color = Color(0xFFD0D0D0), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SvgSourcePanel(content: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        Text(
            content,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD4D4D4),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd),
        ) { Text("Hide", fontSize = 11.sp) }
    }
}
