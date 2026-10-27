package com.codespace.ide.spike

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Pane B — bundled WebView. ALL content is local via WebViewAssetLoader (no
 * remote URLs possible; the CSP in index.html forbids remote/inline script).
 * The bridge is the narrow typed object below: exactly four methods, no eval,
 * no reflection, strings only from the fixed corpus.
 */
class SpikeBridge(private val webView: WebView) {

    @JavascriptInterface
    fun startMessage(id: Int, role: String) {
        val cls = if (role == "user") "user" else if (role == "error") "err" else "assistant"
        runJs("ensureMsg($id, \"$cls\")")
    }

    @JavascriptInterface
    fun delta(id: Int, chunk: String) {
        val esc = chunk
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
        runJs("pushDelta($id, \"$esc\")")
    }

    @JavascriptInterface
    fun doneMessage(id: Int) {
        runJs("scrollBottom()")
    }

    @JavascriptInterface
    fun clearAll() {
        runJs("clearAll()")
    }

    private fun runJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }
}

/** Builds the WebView once; the benchmark drives it through the bridge. */
object SpikeWeb {
    var view: WebView? = null
    var bridge: SpikeBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun build(context: android.content.Context): WebView {
        val loader = WebViewAssetLoader.Builder()
            .setDomain("vn-spike.local")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.mediaPlaybackRequiresUserGesture = true
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }
        bridge = SpikeBridge(wv)
        wv.addJavascriptInterface(bridge!!, "SpikeBridge")
        wv.loadUrl("https://vn-spike.local/assets/spike/index.html")
        view = wv
        return wv
    }
}

@Composable
fun WebViewChatPane(modifier: androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.key("spikeweb") {
        AndroidView(
            modifier = modifier,
            factory = { _ -> SpikeWeb.build(ctx) }
        )
    }
}
