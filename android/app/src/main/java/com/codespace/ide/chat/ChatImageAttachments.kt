package com.codespace.ide.chat

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * R8-VISION (2026-09-12, VS Code images-in-chat parity, ALL providers):
 * attach images from the device to a chat request; they ride the LAST user
 * message as structured multimodal parts, converted per-vendor inside each
 * provider:
 *   - OpenAI / xAI / DeepSeek / OpenRouter / Custom: {type:"image_url",
 *     image_url:{url:"data:<mime>;base64,..."}} content parts
 *     (docs: platform.openai.com image_url; docs.x.ai; api-docs.deepseek.com/guides/vision;
 *     openrouter.ai/docs — all OpenAI-compatible shapes)
 *   - Gemini: {inline_data:{mime_type, data}} parts (ai.google.dev gemini-vision)
 *   - Anthropic: {type:"image", source:{type:"base64", media_type, data}}
 *     (docs.claude.com/en/docs/build-with-claude/vision)
 * Supported formats per vendor docs: JPEG, PNG, GIF, WebP.
 *
 * Images NEVER enter the text path (never injected as ATTACHED CONTEXT blocks,
 * never persisted into session history). Files are COPIED into app-private
 * chat-images/ storage at attach time so the content grant survives the pick.
 */
object ChatImageAttachments {

    /** Hard cap per image — keeps base64 (~1.37x) well under vendor request limits. */
    const val MAX_IMAGE_BYTES: Long = 5L * 1024 * 1024

    private val EXT_BY_MIME = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png",
        "image/gif" to "gif", "image/webp" to "webp",
    )

    /** Resolved MIME for a picked image, from the content resolver, falling back to extension sniffing. */
    fun sniffMime(context: Context, uri: Uri, fallbackName: String): String? {
        val declared = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (declared != null && declared in EXT_BY_MIME) return declared
        // fallback: sniff from bytes (JPEG FF D8 FF, PNG 89 50 4E 47, GIF 47 49 46 38,
        // WEBP RIFF....WEBP)
        return try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val head = ByteArray(16)
                val n = s.read(head)
                val b = head
                if (n >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8) "image/jpeg"
                else if (n >= 4 && (b[0].toInt() and 0xFF) == 0x89 && b[1] == 0x50.toByte()) "image/png"
                else if (n >= 4 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte()) "image/gif"
                else if (n >= 12 && b[0] == 0x52.toByte() && b[8] == 0x57.toByte()) "image/webp"
                else {
                    val byExt = fallbackName.substringAfterLast('.', "").lowercase()
                    when (byExt) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        else -> null
                    }
                }
            } ?: null
        } catch (_: Exception) { null }
    }

    /**
     * Copy the picked image into filesDir/chat-images and return an IMAGE attachment.
     * Throws with a user-readable message when the file is too large or not a
     * supported format — the panel surfaces it as a toast, nothing attaches.
     */
    fun importFromUri(context: Context, uri: Uri): ChatAttachment {
        val mime = sniffMime(context, uri, uri.lastPathSegment ?: "image")
            ?: throw Exception("Unsupported image format. Use JPEG, PNG, GIF, or WebP.")
        val ext = EXT_BY_MIME[mime] ?: "img"
        val dir = File(context.filesDir, "chat-images").apply { mkdirs() }
        val dest = File(dir, "image-${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > MAX_IMAGE_BYTES) {
                            throw Exception("Image is too large (max 5 MB).")
                        }
                        output.write(buf, 0, r)
                    }
                }
            } ?: throw Exception("Could not read the picked image.")
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        if (dest.length() == 0L || dest.length() > MAX_IMAGE_BYTES) {
            val tooBig = dest.length() > MAX_IMAGE_BYTES
            dest.delete()
            throw Exception(if (tooBig) "Image is too large (max 5 MB)." else "Could not read the picked image.")
        }
        return ChatAttachment(
            path = dest.absolutePath,
            relPath = dest.name,
            name = dest.name,
            kind = ChatAttachment.Kind.IMAGE,
            mimeType = mime,
        )
    }

    /**
     * Storage hygiene: copied images are single-use (chips clear on send) — prune
     * anything older than 7 days on app start so filesDir doesn't accumulate.
     * Never throws.
     */
    fun pruneOldImages(context: Context) {
        try {
            val dir = File(context.filesDir, "chat-images")
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        } catch (_: Exception) { }
    }

    /**
     * Build the request image list (base64) for the send path. FAIL-CLOSED with a
     * clear message when a previously attached image became unreadable — never a
     * silent skip. Not counted toward the 24k text-attachment char cap (images are
     * billed/tokens by vendors separately; gauge underestimates — accepted v1).
     */
    fun toRequestImages(attachments: List<ChatAttachment>): List<ChatRequestImage> {
        val images = attachments.filter { it.kind == ChatAttachment.Kind.IMAGE }
        if (images.isEmpty()) return emptyList()
        return images.map { att ->
            try {
                val bytes = File(att.path).readBytes()
                ChatRequestImage(
                    mimeType = att.mimeType ?: "image/png",
                    base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    name = att.name,
                )
            } catch (_: Exception) {
                throw Exception("Attached image '${att.name}' could not be read. Remove the chip and re-attach it.")
            }
        }
    }
}
