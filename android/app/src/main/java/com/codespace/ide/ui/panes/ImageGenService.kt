package com.codespace.ide.ui.panes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Gemini native image generation ("Nano Banana" family) — added 2026-07-08.
// User request: generate labeled images (avatar, thumbnail, icon, etc.) via Gemini
// and save them straight into a project's Explorer folder, without routing through
// a local model first (small local LLMs like Nemotron-3B can't reliably do the
// structured "which image is which" hand-off — Gemini alone handles a single
// well-labeled prompt fine).
//
// Uses the classic generateContent endpoint (not the newer Interactions API) so the
// request/response shape matches callGemini() in CopilotChatPanelOverlay.kt exactly —
// same auth (?key= query param), same JSON parsing conventions, minimal new surface
// area. Default model is the legacy-but-still-supported "gemini-2.5-flash-image"
// (aka Nano Banana) rather than the newer gemini-3.1-flash-image / Nano Banana 2,
// which Google's docs currently frame around their newer Interactions API — bump this
// string once that's confirmed to work identically via generateContent.
// ─────────────────────────────────────────────────────────────────────────────

private const val DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"

private val imageHttp = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

data class GeneratedImage(val bytes: ByteArray, val mimeType: String)

/**
 * Calls Gemini's native image generation. Throws on any failure (bad key, refused
 * prompt, network error) — callers should show `e.message` directly, it's already
 * written to be user-facing.
 */
suspend fun generateGeminiImage(
    apiKey: String,
    prompt: String,
    model: String = DEFAULT_IMAGE_MODEL,
): GeneratedImage = withContext(Dispatchers.IO) {
    val body = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
        )
        .put(
            "generationConfig",
            JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
        )
        .toString()

    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
    val resp = imageHttp.newCall(
        Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()

    val respBody = resp.body?.string() ?: ""
    if (!resp.isSuccessful) {
        throw Exception("Gemini image API error (${resp.code}). Check your Gemini key in Settings.")
    }

    val json = JSONObject(respBody)
    val candidates = json.optJSONArray("candidates")
        ?: throw Exception("Gemini returned no candidates — it may have refused the prompt.")
    if (candidates.length() == 0) throw Exception("Gemini returned no candidates — it may have refused the prompt.")

    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        ?: throw Exception("Gemini returned an empty response.")

    for (i in 0 until parts.length()) {
        val part = parts.getJSONObject(i)
        // Defensive: Google's own docs/examples have shown this field as both camelCase
        // ("inlineData") and snake_case ("inline_data") across different revisions —
        // check both rather than assume one.
        val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
        if (inline != null) {
            val mime = inline.optString("mimeType", inline.optString("mime_type", "image/png"))
            val b64 = inline.optString("data")
            if (b64.isNotBlank()) {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                return@withContext GeneratedImage(bytes, mime)
            }
        }
    }
    throw Exception("Gemini didn't return an image — try rephrasing the prompt.")
}

/**
 * Heuristic subfolder suggestion from the prompt text, so "give me an avatar" defaults
 * to assets/avatars instead of dumping every generated image in one flat pile. Purely a
 * starting suggestion — the dialog lets the user override it before saving.
 */
fun suggestImageSubfolder(prompt: String): String {
    val p = prompt.lowercase()
    return when {
        "avatar" in p -> "assets/avatars"
        "thumbnail" in p || "thumb" in p -> "assets/thumbnails"
        "icon" in p -> "assets/icons"
        "logo" in p -> "assets/logos"
        "banner" in p || "cover" in p -> "assets/banners"
        "background" in p || "wallpaper" in p -> "assets/backgrounds"
        else -> "assets/generated"
    }
}

private fun extensionFor(mimeType: String): String = when {
    mimeType.contains("png") -> "png"
    mimeType.contains("webp") -> "webp"
    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
    else -> "png"
}

/**
 * Writes generated image bytes into the CURRENT project's Explorer folder. `targetDir`
 * is passed in directly by the caller (already resolved from the Explorer context-menu
 * click — the exact folder the user right-clicked), so no separate path lookup/
 * translation is needed here; this treats `targetDir` as a real, already-writable host
 * path, same as every other file operation in ExplorerPane.kt.
 */
fun saveGeneratedImage(
    targetDir: File,
    image: GeneratedImage,
    baseFileName: String,
): File {
    targetDir.mkdirs()
    val ext = extensionFor(image.mimeType)
    val safeBase = baseFileName.ifBlank { "generated" }
        .replace(Regex("[^A-Za-z0-9-_]+"), "_")
        .trim('_')
        .ifBlank { "generated" }

    var candidate = File(targetDir, "$safeBase.$ext")
    var n = 1
    while (candidate.exists()) {
        candidate = File(targetDir, "${safeBase}_$n.$ext")
        n++
    }
    candidate.writeBytes(image.bytes)
    return candidate
}
