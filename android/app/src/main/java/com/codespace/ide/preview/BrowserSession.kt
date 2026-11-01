package com.codespace.ide.preview

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * One WebView for browser mode, owned here instead of by the composable.
 *
 * Rotation was resetting the page because Compose discarded the AndroidView (the panel keys its
 * content on orientation, and the landscape layout is a different subtree). A WebView lives in a
 * view hierarchy, not in Compose state, so it has to be held somewhere that outlives
 * recomposition; the DOM, scroll position, history and form state then survive a rotate.
 *
 * The user agent is deliberately COHERENT rather than impressive. The old code forced a hardcoded
 * "Chrome/125 on Windows" string and a fake navigator.userAgentData. Sign-in providers and bot
 * checks compare the UA against the TLS/JS fingerprint and the engine's real version, so a client
 * claiming desktop Chrome 125 while running WebView 141 is exactly what "your browser is not
 * secure" means. We now report the engine's real version, with "; wv" removed.
 */
object BrowserSession {

    /** Where a long press landed, for the action sheet. */
    data class Target(val url: String, val isImage: Boolean, val anchorUrl: String?)

    var webView: WebView? = null
        private set
    var desktopMode: Boolean = false
        private set

    /** Set by a long press; the action sheet reads and clears it. */
    var pendingLongPress: Target? by androidx.compose.runtime.mutableStateOf(null)

    var onLongPress: ((Target) -> Unit)? = null
    var onRendererGone: (() -> Unit)? = null
    var onDownloadStarted: ((String) -> Unit)? = null

    fun attach(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            return existing
        }
        val created = WebView(context.applicationContext)
        configure(created, context.applicationContext)
        webView = created
        return created
    }

    fun release() {
        val wv = webView ?: return
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
    }

    fun destroy() {
        webView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
    }

    fun applyUserAgent(context: Context, desktop: Boolean) {
        desktopMode = desktop
        webView?.let {
            it.settings.userAgentString = userAgent(context, desktop)
            it.reload()
        }
    }

    fun userAgent(context: Context, desktop: Boolean): String {
        val real = WebSettings.getDefaultUserAgent(context)
        return if (desktop) desktopUserAgentFrom(real) else mobileUserAgentFrom(real)
    }

    /**
     * The engine's own UA with the "wv" token removed: same Chrome major version, no WebView
     * branding. Coherence is the whole point - a UA that disagrees with the UA-CH metadata and
     * the JS APIs is what bot protection rejects.
     */
    fun mobileUserAgentFrom(real: String): String = real.replace("; wv", "")

    /** Desktop UA that still claims the engine's real version rather than an invented one. */
    fun desktopUserAgentFrom(real: String): String {
        val major = Regex("Chrome/(\\d+)").find(real)?.groupValues?.getOrNull(1) ?: "141"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, appContext: Context) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgent(appContext, desktopMode)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        attachDownloads(webView)
        attachLongPress(webView)
    }

    private fun attachLongPress(webView: WebView) {
        webView.setOnLongClickListener { view ->
            val wv = view as WebView
            val hit = wv.hitTestResult
            val url = hit.extra
            if (url.isNullOrBlank()) {
                false
            } else {
                when (hit.type) {
                    WebView.HitTestResult.IMAGE_TYPE -> {
                        onLongPress?.invoke(Target(url, isImage = true, anchorUrl = null))
                        true
                    }
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                        // hit.extra is the image; the enclosing link is not exposed by the
                        // hit test, so offer the image actions only.
                        onLongPress?.invoke(Target(url, isImage = true, anchorUrl = null))
                        true
                    }
                    WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                        onLongPress?.invoke(Target(url, isImage = false, anchorUrl = url))
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun attachDownloads(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setTitle(name)
                try {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } catch (_: Throwable) {
                    request.setDestinationInExternalFilesDir(
                        webView.context, Environment.DIRECTORY_DOWNLOADS, name,
                    )
                }
                val dm = webView.context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                onDownloadStarted?.invoke(name)
            } catch (_: Throwable) {
                // A refused download must not leave the browser looking dead.
            }
        }
    }

    /** Clients in one place, so browser mode cannot silently lose a handler again. */
    fun installClients(
        webView: WebView,
        onLoading: (Boolean) -> Unit,
        onTitle: (String) -> Unit,
        onNavChange: (canBack: Boolean, canForward: Boolean) -> Unit,
        onError: (String, String) -> Unit,
    ) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onLoading(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoading(false)
                val wv = view ?: return
                onTitle(wv.title ?: "")
                onNavChange(wv.canGoBack(), wv.canGoForward())
                CookieManager.getInstance().flush()
            }

            // Only a failed MAIN frame is an error page. The deprecated overload fired for every
            // subresource, so one broken avatar replaced a working page with "Cannot connect" —
            // that is the freeze that only a refresh cleared.
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                onLoading(false)
                onError(request.url?.toString() ?: "", error?.description?.toString() ?: "Load failed")
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                val scheme = url.substringBefore(':', "")
                val foreign = scheme !in setOf("http", "https", "about", "data", "blob", "file", "javascript")
                if (!foreign) return false
                return try {
                    view?.context?.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)),
                    )
                    true
                } catch (_: Throwable) {
                    true
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                // The renderer died (usually memory). Returning true keeps the app alive; the
                // pane rebuilds the view, which is what "tap refresh" was doing by hand.
                destroy()
                onRendererGone?.invoke()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) onLoading(false)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                onTitle(title ?: "")
            }

            // Popups and window.open used to open a window nobody displayed, so the page waited
            // forever for a handle it never got. Route the URL into this view instead.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val parent = view ?: return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(parent.context.applicationContext)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.toString()?.let { parent.loadUrl(it) }
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val ctx = webView.context
                val granted = request.resources.all { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> ctx.checkSelfPermission(
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> ctx.checkSelfPermission(
                            android.Manifest.permission.CAMERA,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        else -> true
                    }
                }
                try {
                    if (granted) request.grant(request.resources) else request.deny()
                } catch (_: Throwable) {
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    @Suppress("unused")
    fun startDownload(context: Context, url: String, userAgent: String?, mimeType: String?) {
        try {
            val name = android.webkit.URLUtil.guessFileName(url, null, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle(name)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            try {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } catch (_: Throwable) {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            onDownloadStarted?.invoke(name)
        } catch (_: Throwable) {
        }
    }
}
