package com.codespace.ide.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One decision for every link the app can hand to a browser: does a dev server own it, or the
 * outside world?
 *
 * The terminal used to fire ACTION_VIEW at everything, so a "Local: http://localhost:5173"
 * line threw you out of the IDE into Chrome for a page only this device can see. Matches VS
 * Code's default (`workbench.browser.openLocalhostLinks`): loopback opens in the panel, the rest
 * leaves the app.
 */
object PreviewOpenUrl {

    /** Set by [open] for the shell to consume; null when nothing is waiting. */
    var pending: String? by mutableStateOf(null)

    /** True for loopback and *.localhost, which can only mean "a server on this device". */
    fun isLocalhost(url: String): Boolean {
        // A dev server is often printed without a scheme ("localhost:5173"), and a bare
        // host:port parses with that host as the SCHEME, so retry with https:// as well.
        val host = (try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }) ?: (try {
            // "127.0.0.1:8080" is not even a parseable URI (a scheme cannot start with a
            // digit), so the retry has to be attempted after a failure, not just after null.
            java.net.URI("https://" + url).host
        } catch (_: Exception) {
            null
        }) ?: return false
        val h = host.lowercase()
        return h == "localhost" || h == "127.0.0.1" || h == "0.0.0.0" ||
            h == "::1" || h == "[::1]" || h.endsWith(".localhost")
    }

    fun open(context: Context, url: String) {
        if (url.isBlank()) return
        if (isLocalhost(url)) {
            pending = url
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}
