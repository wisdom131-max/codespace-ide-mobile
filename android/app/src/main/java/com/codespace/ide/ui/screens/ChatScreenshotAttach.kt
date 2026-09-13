package com.codespace.ide.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * R10-E — attach a screenshot of the app itself as chat context
 * (VS Code chatScreenshotContext analog). PixelCopy-captures the
 * CURRENT activity window (API 26+, matches our minSdk) into a PNG
 * in cacheDir, wrapped as an IMAGE attachment riding the R8 vision path.
 *
 * NOTE: PixelCopy needs a window with a surface — capture happens while
 * the app is foregrounded, so the shot is always THIS app, never another.
 */
object ChatScreenshotCapture {

    /** Unwrap dialog/theme contexts down to the hosting Activity. */
    fun findActivity(context: Context): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Captures [activity]'s window. Suspend — PixelCopy is callback-based;
     * suspendCancellableCoroutine keeps the main thread free (no deadlock).
     * Returns the IMAGE attachment, or null on failure/unsupported.
     */
    suspend fun captureNow(activity: Activity): com.codespace.ide.chat.ChatAttachment? {
        if (Build.VERSION.SDK_INT < 26) return null
        val w: Int = activity.window.decorView.width
        val h: Int = activity.window.decorView.height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = try { copyWindow(activity.window, bitmap) } catch (_: Exception) { false }
        if (!ok) return null
        return withContext(Dispatchers.IO) { writeAttachment(activity, bitmap) }
    }

    private suspend fun copyWindow(window: Window, bitmap: Bitmap): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window, bitmap,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            } catch (e: Exception) {
                try { cont.resume(false) } catch (_: Exception) { }
            }
        }

    private fun writeAttachment(context: Context, bitmap: Bitmap): com.codespace.ide.chat.ChatAttachment? {
        return try {
            val dir = File(context.cacheDir, "chat_shots").apply { mkdirs() }
            val f = File(dir, "shot-" + System.currentTimeMillis() + ".png")
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            com.codespace.ide.chat.ChatAttachment(
                path = f.absolutePath,
                relPath = "screenshot.png",
                name = "screenshot-" + System.currentTimeMillis() + ".png",
                kind = com.codespace.ide.chat.ChatAttachment.Kind.IMAGE,
                mimeType = "image/png",
            )
        } catch (_: Exception) { null }
    }
}
