package com.codespace.ide.spike

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.DownloadManager
import android.content.pm.PackageManager

/**
 * The browser's engine configuration, kept out of the Compose tree.
 *
 * Written against how VS Code's own integrated browser behaves rather than against how a
 * locked-down WebView is usually configured: pages load as they would in Chrome, sign-in
 * flows work (third-party cookies, popups, JS dialogs), uploads and downloads work, and a
 * site asking for the camera, microphone or location gets an answer instead of silence.
 */
object BrowserEngine {

    /** Chrome on Windows — sites serve their real desktop experience instead of a stub. */
    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** The device's own Chrome UA with the WebView marker removed, so sites do not refuse it. */
    fun mobileUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(" Version/4.0", "")

    fun normalizeInput(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        val hasScheme = text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("file://") || text.startsWith("about:")
        if (hasScheme) return text
        // A bare host or host/path is a URL, not a search — same rule the integrated browser uses.
        return if (text.contains(" ") || !text.contains(".")) {
            "https://duckduckgo.com/?q=" + Uri.encode(text)
        } else {
            "https://$text"
        }
    }

    fun configure(
        webView: WebView,
        desktop: Boolean,
        onDownloadStarted: (String) -> Unit,
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowContentAccess = true
            // Local files stay readable so a file:// page opens like any other. This is the
            // spike, which exists to answer "does the browser do what I want", not to be the
            // shipping surface.
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            // A https page pulling http assets used to render broken; a browser shows it.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktop) DESKTOP_UA else mobileUserAgent(webView.context)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setSafeBrowsingEnabled(true)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        attachDownloads(webView, onDownloadStarted)
    }

    private fun attachDownloads(webView: WebView, onStarted: (String) -> Unit) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                mimeType?.let { request.setMimeType(it) }
                userAgent?.let { request.addRequestHeader("User-Agent", it) }
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                try {
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } catch (_: Throwable) {
                    request.setDestinationInExternalFilesDir(
                        webView.context, Environment.DIRECTORY_DOWNLOADS, name,
                    )
                }
                val dm = webView.context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.enqueue(request)
                onStarted(name)
            } catch (_: Throwable) {
                onStarted("failed")
            }
        }
    }

    /** Camera and microphone are granted when Android already allows them to this app. */
    fun handlePermissionRequest(context: Context, request: PermissionRequest?) {
        if (request == null) return
        fun granted(permission: String): Boolean =
            try {
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        val allow = request.resources.all { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(android.Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(android.Manifest.permission.CAMERA)
                else -> true
            }
        }
        try {
            if (allow) request.grant(request.resources) else request.deny()
        } catch (_: Throwable) {}
    }

    /** A popup reuses the one view, so an OAuth window still lands where the user is looking. */
    fun popupInto(webView: WebView, context: Context, desktop: Boolean): WebView {
        val popup = WebView(context)
        configure(popup, desktop) {}
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                webView.loadUrl(target)
                return true
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                window?.destroy()
            }
        }
        return popup
    }
}
