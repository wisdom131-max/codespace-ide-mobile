package com.codespace.ide.chat.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The check VS Code performs before it offers a local model provider.
 *
 * VS Code treats Ollama as a LOCAL SERVER, not as a provider waiting for a key. Its question is "is
 * the server there, and what has it pulled", which is a different question from "is this key valid".
 * This app had the Ollama preset wired correctly (localhost:11434/v1, no key, never a cloud call) but
 * routed it through key-slot machinery, so the screen read as a provider missing a key it would never
 * get. This probe gives the screen the three honest states instead of one blank field.
 */
sealed class LocalProbeResult {
    /** The server answered and listed what it has. `models` may be empty - that is a real state. */
    data class Running(val models: List<String>) : LocalProbeResult()

    /** Nothing answered on the port: the server is not running, or not reachable from this device. */
    object NotRunning : LocalProbeResult()

    /** It answered but not usefully. The detail is shown rather than swallowed. */
    data class Failed(val detail: String) : LocalProbeResult()
}

object LocalModelProbe {
    /** Ollama's own tags endpoint. Not the OpenAI-compatible path: that one has no model list. */
    const val TAGS_URL: String = "http://localhost:11434/api/tags"

    suspend fun tags(url: String = TAGS_URL): LocalProbeResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.requestMethod = "GET"
            try {
                val status = conn.responseCode
                if (status !in 200..299) {
                    return@withContext LocalProbeResult.Failed("answered " + status)
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                LocalProbeResult.Running(parseNames(body))
            } finally {
                conn.disconnect()
            }
        } catch (e: java.net.ConnectException) {
            LocalProbeResult.NotRunning
        } catch (e: java.net.SocketTimeoutException) {
            LocalProbeResult.NotRunning
        } catch (e: Exception) {
            // Never swallowed silently: the reason is what tells the owner whether to start the
            // server or fix the port.
            LocalProbeResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Ollama answers `{"models":[{"name":"llama3:8b","model":"llama3:8b",...}]}`. Read the names only,
     * de-duplicated, sorted, because that is what a person picks from. A body with no names is an
     * empty list, never a fabricated entry.
     */
    internal fun parseNames(body: String): List<String> =
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

    /** The one line the row shows. States what is true, never a guess. */
    fun statusLine(r: LocalProbeResult): String = when (r) {
        is LocalProbeResult.Running ->
            if (r.models.isEmpty()) "Running, no models pulled"
            else "Running, " + r.models.size + (if (r.models.size == 1) " model" else " models")
        LocalProbeResult.NotRunning -> "Not reachable on port 11434"
        is LocalProbeResult.Failed -> "Reached it, but " + r.detail
    }
}
