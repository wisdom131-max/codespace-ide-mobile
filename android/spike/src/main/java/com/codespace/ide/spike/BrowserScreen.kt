package com.codespace.ide.spike

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val Bg = Color(0xFF101012)
private val Bar = Color(0xFF1B1B1F)
private val Field = Color(0xFF26262C)
private val Ink = Color(0xFFE8E8EA)
private val Muted = Color(0xFF8A8A93)
private val Accent = Color(0xFF4FC3F7)
private val Danger = Color(0xFFFF6B6B)

/** Carries WebView's file-chooser callback to the composition's activity launcher. */
private class FileChooserBridge {
    var pending: ValueCallback<Array<Uri>>? = null
    var launch: (() -> Unit)? = null

    fun open(callback: ValueCallback<Array<Uri>>?) {
        pending = callback
        launch?.invoke()
    }

    fun deliver(uris: Array<Uri>?) {
        pending?.onReceiveValue(uris)
        pending = null
    }
}

@Composable
fun BrowserScreen(startUrl: String = "https://example.com") {
    val context = LocalContext.current
    var address by remember { mutableStateOf(startUrl) }
    var desktop by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val chooser = remember { FileChooserBridge() }
    val singleFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        chooser.deliver(if (uri != null) arrayOf(uri) else null)
    }
    androidx.compose.runtime.SideEffect {
        chooser.launch = { singleFile.launch("*/*") }
    }

    DisposableEffect(Unit) {
        onDispose { CookieManager.getInstance().flush() }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        BrowserHeader(
            address = address,
            onAddressChange = { address = it },
            desktop = desktop,
            onToggleDesktop = {
                desktop = !desktop
                webView?.let { view ->
                    BrowserEngine.configure(view, desktop) { note = "Downloading $it" }
                    view.reload()
                }
            },
            canBack = canBack,
            canForward = canForward,
            onBack = { webView?.goBack() },
            onForward = { webView?.goForward() },
            onReload = { error = null; webView?.reload() },
            onGo = {
                val target = BrowserEngine.normalizeInput(address)
                address = target
                error = null
                webView?.loadUrl(target)
            },
            onOpenExternally = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                } catch (_: Throwable) {}
            },
        )
        BrowserStatus(progress = progress, error = error, note = note)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    BrowserEngine.configure(this, desktop) { note = "Downloading $it" }
                    webViewClient = browserClient(
                        onStart = { url -> address = url; progress = 0 },
                        onFinish = { view ->
                            canBack = view.canGoBack()
                            canForward = view.canGoForward()
                        },
                        onError = { message -> error = message },
                        onExternal = { uri ->
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: Throwable) {}
                        },
                    )
                    webChromeClient = browserChrome(
                        onProgress = { progress = it },
                        onPermission = { BrowserEngine.handlePermissionRequest(ctx, it) },
                        onPopup = { transport, popupUrl ->
                            transport.webView = BrowserEngine.popupInto(this, ctx, desktop)
                            popupUrl?.let { address = it }
                            true
                        },
                        onFileChooser = { callback -> chooser.open(callback); true },
                    )
                    loadUrl(BrowserEngine.normalizeInput(startUrl))
                }
            },
            update = { webView = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Slim control row: back, forward, reload, address, and the two escape hatches. */
@Composable
private fun BrowserHeader(
    address: String,
    onAddressChange: (String) -> Unit,
    desktop: Boolean,
    onToggleDesktop: () -> Unit,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onGo: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(Bar, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarGlyph("‹", enabled = canBack, onClick = onBack)
        BarGlyph("›", enabled = canForward, onClick = onForward)
        BarGlyph("⟳", enabled = true, onClick = onReload)
        TextField(
            value = address,
            onValueChange = onAddressChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 12.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Field,
                unfocusedContainerColor = Field,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
            ),
            modifier = Modifier.weight(1f).height(42.dp),
        )
        BarGlyph(if (desktop) "D" else "M", enabled = true, onClick = onToggleDesktop)
        BarGlyph("↗", enabled = true, onClick = onOpenExternally)
    }
}

@Composable
private fun BarGlyph(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .background(Field, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Ink else Muted.copy(alpha = 0.4f),
            fontSize = 15.sp,
        )
    }
}

/** Two thin lines: load progress, then whatever went wrong or was downloaded. */
@Composable
private fun BrowserStatus(progress: Int, error: String?, note: String?) {
    if (progress in 1..99) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            color = Accent,
            trackColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
    if (error != null) {
        Text(
            error,
            color = Danger,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        )
    } else if (note != null) {
        Text(
            note,
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private fun browserClient(
    onStart: (String) -> Unit,
    onFinish: (WebView) -> Unit,
    onError: (String) -> Unit,
    onExternal: (Uri) -> Unit,
): WebViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        url?.let(onStart)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        view?.let(onFinish)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        val handOff = url.startsWith("intent:") || url.startsWith("mailto:") ||
            url.startsWith("tel:") || url.startsWith("market:")
        if (handOff) {
            request?.url?.let(onExternal)
            return true
        }
        return false
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: android.webkit.WebResourceResponse?,
    ) {
        if (request?.isForMainFrame == true) {
            onError("HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase ?: ""}")
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            onError("${error?.description ?: "Load failed"} — ${request.url}")
        }
    }
}

private fun browserChrome(
    onProgress: (Int) -> Unit,
    onPermission: (android.webkit.PermissionRequest?) -> Unit,
    onPopup: (android.webkit.WebView.WebViewTransport, String?) -> Boolean,
    onFileChooser: (ValueCallback<Array<Uri>>?) -> Boolean,
): WebChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)

    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) = onPermission(request)

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, true, false)
    }

    override fun onShowFileChooser(
        view: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean = onFileChooser(filePathCallback)

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val started = onPopup(transport, null)
        if (started) resultMsg.sendToTarget()
        return started
    }
}
