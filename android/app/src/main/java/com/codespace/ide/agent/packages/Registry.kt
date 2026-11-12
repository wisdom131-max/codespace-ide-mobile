package com.codespace.ide.agent.packages

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Registry access for npm and PyPI — one small client, on top of the networking the app already uses.
 *
 * AUDIT (2026-11-12): the app has one HTTP convention, [HttpURLConnection] with a 10s connect and
 * 30s read timeout, a VNCode user agent, redirects on and a hard byte cap (AgentTools.fetchOnce).
 * There is no OkHttp and no HTTP library worth adding for two JSON GETs, so this repeats the same
 * conventions rather than introducing a stack. The fetcher is INJECTABLE: every test drives
 * [npmInfo]/[pypiInfo] with fixtures and never touches the network, so a registry outage cannot
 * turn the suite red.
 *
 * CACHING: bounded (32 entries) with a 600s TTL, keyed by ecosystem + package + the version that was
 * actually requested. An explicit version request is never answered from a "latest" entry, and an
 * entry older than the TTL is not used at all — stale metadata must not silently answer a question
 * about a specific version.
 */
object PackageRegistry {

    const val TIMEOUT_CONNECT_MS = 10_000
    const val TIMEOUT_READ_MS = 20_000
    private const val MAX_BODY_BYTES = 2_000_000
    private const val CACHE_MAX = 32
    const val CACHE_TTL_MS = 600_000L

    // Output bounds — a registry document is megabytes, a useful answer is not.
    private const val MAX_DESCRIPTION = 600
    private const val MAX_DEPS = 25
    private const val MAX_KEYWORDS = 10
    private const val MAX_CLASSIFIERS = 12
    private const val MAX_URLS = 8

    /** What a fetch produced. [Ok] carries the body; the rest are failures with a readable cause. */
    sealed class Fetched {
        data class Ok(val body: String) : Fetched()
        data class Http(val code: Int, val body: String = "") : Fetched()
        data class Failed(val reason: String) : Fetched()
    }

    /** Injected by tests; the default uses the app's HTTP conventions. */
    fun interface Fetcher { fun get(url: String): Fetched }

    val http: Fetcher = Fetcher { url -> httpGet(url) }

    private data class Entry(val body: String, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > CACHE_MAX
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun cached(key: String, now: Long): String? = synchronized(cache) {
        val e = cache[key] ?: return null
        if (now - e.at > CACHE_TTL_MS) { cache.remove(key); return null }
        e.body
    }

    private fun put(key: String, body: String, now: Long) = synchronized(cache) {
        cache[key] = Entry(body, now)
    }

    // ── npm ────────────────────────────────────────────────────────────────────

    /**
     * npm metadata. A requested version is fetched as its OWN document: if it does not exist the
     * answer says so and never falls back to latest (that substitution is how a model ends up
     * describing the wrong release).
     */
    fun npmInfo(
        name: String,
        version: String? = null,
        now: Long = System.currentTimeMillis(),
        fetcher: Fetcher = http,
        refresh: Boolean = false,
    ): String {
        val clean = name.trim().lowercase()
        validateNpmName(clean)?.let { return it }
        val requested = version?.trim()?.takeIf { it.isNotEmpty() }
        requested?.let { validateVersion(it)?.let { err -> return err } }

        val key = "npm|$clean|${requested ?: "latest"}"
        val body = if (!refresh) cached(key, now) else null
        if (body != null) return renderNpm(body, clean, requested, fromCache = true)

        val url = if (requested == null) "https://registry.npmjs.org/$clean"
        else "https://registry.npmjs.org/$clean/" + encodePath(requested)
        when (val f = fetcher.get(url)) {
            is Fetched.Ok -> {
                put(key, f.body, now)
                return renderNpm(f.body, clean, requested, fromCache = false)
            }
            is Fetched.Http -> return when (f.code) {
                404 -> if (requested == null)
                    "npm package '$clean' was not found. Check the spelling; scoped packages need their scope (@scope/name)."
                else "npm package '$clean' has no version '$requested'. Ask for the package without a version to see what is published."
                429 -> "npm registry rate limit hit (HTTP 429). Try again in a minute."
                in 500..599 -> "npm registry is unavailable right now (HTTP ${f.code}). Nothing was installed."
                else -> "npm lookup failed (HTTP ${f.code}) for '$clean'."
            }
            is Fetched.Failed -> return "npm lookup failed: ${f.reason}. Nothing was installed or modified."
        }
    }

    /** Pure: a registry document in, a bounded answer out. */
    fun renderNpm(raw: String, name: String, requested: String?, fromCache: Boolean = false): String {
        val doc = try { JSONObject(raw) } catch (e: Exception) {
            return "The npm registry returned something that is not JSON (malformed response). Nothing was installed."
        }
        val distTags = doc.optJSONObject("dist-tags")
        val latest = distTags?.optString("latest")?.takeIf { it.isNotBlank() }
        val versions = doc.optJSONObject("versions")
        val picked = requested ?: latest
        val vdoc = picked?.let { versions?.optJSONObject(it) }
        if (requested != null && vdoc == null) {
            return "npm package '$name' has no version '$requested'. Published: " +
                (versions?.keys()?.asSequence()?.toList()?.takeLast(10)?.joinToString(", ") ?: "none listed")
        }
        val meta = vdoc
        val sb = StringBuilder()
        sb.append("npm ").append(name)
        if (picked != null) sb.append(" ").append(picked) else sb.append(" (no latest tag)")
        if (picked != null && latest != null && picked != latest) sb.append("  [latest is ").append(latest).append("]")
        if (requested == null && latest == null) sb.append("  [package publishes no dist-tags]")
        if (doc.optBoolean("deprecated") || meta?.optBoolean("deprecated") == true) sb.append("  [DEPRECATED]")
        sb.append('\n')

        val desc = meta?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.optString("description").takeIf { it.isNotBlank() }
        desc?.let { sb.append("Description (untrusted registry text): ").append(oneLine(it)).append('\n') }

        val license = meta?.optString("license")?.takeIf { it.isNotBlank() }
            ?: doc.optJSONObject("license")?.optString("type")
        license?.let { sb.append("License: ").append(it).append('\n') }
        meta?.optString("homepage")?.takeIf { it.isNotBlank() }?.let { sb.append("Homepage: ").append(it).append('\n') }
        repoUrl(meta)?.let { sb.append("Repository: ").append(it).append('\n') }
        meta?.optJSONObject("dist")?.optString("tarball")?.takeIf { it.isNotBlank() }
            ?.let { sb.append("Tarball: ").append(it).append('\n') }

        val maintainers = meta?.optJSONArray("maintainers") ?: doc.optJSONArray("maintainers")
        maintainers?.let { arr ->
            val names = (0 until minOf(arr.length(), 5)).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifBlank { null } }
            if (names.isNotEmpty()) sb.append("Maintainers: ").append(names.joinToString(", ")).append('\n')
        }
        val engines = meta?.optJSONObject("engines")
        engines?.optString("node")?.takeIf { it.isNotBlank() }?.let { sb.append("Node: ").append(it).append('\n') }
        timeFor(doc, picked)?.let { sb.append("Published: ").append(it).append('\n') }
        keywords(meta).let { if (it.isNotEmpty()) sb.append("Keywords: ").append(it).append('\n') }
        depBlock(sb, "Dependencies", meta?.optJSONObject("dependencies"))
        depBlock(sb, "Peer dependencies", meta?.optJSONObject("peerDependencies"))
        depBlock(sb, "Optional dependencies", meta?.optJSONObject("optionalDependencies"))

        val allVersions = versions?.length() ?: 0
        if (allVersions > 1 && requested == null) {
            val recent = versions!!.keys().asSequence().toList().takeLast(5).reversed()
            sb.append("Recent versions: ").append(recent.joinToString(", ")).append(" (").append(allVersions).append(" total)\n")
        }
        if (fromCache) sb.append("(cached for up to ").append(CACHE_TTL_MS / 1000).append("s)")
        return sb.toString().trim()
    }

    // ── PyPI ───────────────────────────────────────────────────────────────────

    fun pypiInfo(
        name: String,
        version: String? = null,
        now: Long = System.currentTimeMillis(),
        fetcher: Fetcher = http,
        refresh: Boolean = false,
    ): String {
        val clean = name.trim().lowercase()
        validatePypiName(clean)?.let { return it }
        val requested = version?.trim()?.takeIf { it.isNotEmpty() }
        requested?.let { validateVersion(it)?.let { err -> return err } }

        val key = "pypi|$clean|${requested ?: "latest"}"
        val hit = if (!refresh) cached(key, now) else null
        if (hit != null) return renderPypi(hit, clean, requested, fromCache = true)

        val url = if (requested == null) "https://pypi.org/pypi/$clean/json"
        else "https://pypi.org/pypi/$clean/" + encodePath(requested) + "/json"
        when (val f = fetcher.get(url)) {
            is Fetched.Ok -> { put(key, f.body, now); return renderPypi(f.body, clean, requested, false) }
            is Fetched.Http -> return when (f.code) {
                404 -> if (requested == null)
                    "PyPI has no project '$clean'. Check the spelling; PyPI names are case-insensitive but hyphens matter for display."
                else "PyPI has no release '$requested' of '$clean'. Ask without a version to see the available releases."
                429 -> "PyPI rate limit hit (HTTP 429). Try again in a minute."
                in 500..599 -> "PyPI is unavailable right now (HTTP ${f.code}). Nothing was installed."
                else -> "PyPI lookup failed (HTTP ${f.code}) for '$clean'."
            }
            is Fetched.Failed -> return "PyPI lookup failed: ${f.reason}. Nothing was installed or modified."
        }
    }

    /** Pure: a PyPI JSON document in, a bounded answer out. */
    fun renderPypi(raw: String, name: String, requested: String?, fromCache: Boolean = false): String {
        val doc = try { JSONObject(raw) } catch (e: Exception) {
            return "PyPI returned something that is not JSON (malformed response). Nothing was installed."
        }
        val info = doc.optJSONObject("info")
            ?: return "PyPI response had no project metadata for '$name'. Nothing was installed."
        val releases = doc.optJSONObject("releases")
        val latest = info.optString("version").takeIf { it.isNotBlank() }
        val picked = requested ?: latest
        if (requested != null && releases != null && !releases.has(requested)) {
            val available = releases.keys().asSequence().toList().takeLast(10)
            return "PyPI has no release '$requested' of '$name'." +
                if (available.isEmpty()) "" else " Recent releases: " + available.joinToString(", ")
        }
        val sb = StringBuilder("pypi ").append(name)
        if (picked != null) sb.append(" ").append(picked)
        if (picked != null && latest != null && picked != latest) sb.append("  [latest is ").append(latest).append("]")
        if (latest == null) sb.append("  [no usable release]")
        sb.append('\n')

        info.optString("summary").takeIf { it.isNotBlank() }
            ?.let { sb.append("Summary (untrusted registry text): ").append(oneLine(it)).append('\n') }
        sb.append("License: ").append(info.optString("license").ifBlank { "not stated" }).append('\n')
        info.optString("author").takeIf { it.isNotBlank() }?.let { sb.append("Author: ").append(it).append('\n') }
        info.optString("author_email").takeIf { it.isNotBlank() }?.let { sb.append("Author email: ").append(it).append('\n') }
        info.optString("requires_python").takeIf { it.isNotBlank() }
            ?.let { sb.append("Requires Python: ").append(it).append('\n') }

        val urls = info.optJSONObject("project_urls")
        if (urls != null) {
            val shown = urls.keys().asSequence().take(MAX_URLS).map { k -> k + ": " + urls.optString(k) }.toList()
            if (shown.isNotEmpty()) sb.append("Project URLs:\n").append(shown.joinToString("\n", transform = { "  - " + it })).append('\n')
        }
        info.optString("home_page").takeIf { it.isNotBlank() }
            ?.let { if (sb.indexOf(it) < 0) sb.append("Homepage: ").append(it).append('\n') }

        val requires = info.optJSONArray("requires_dist")
        if (requires != null && requires.length() > 0) {
            val listed = (0 until minOf(requires.length(), MAX_DEPS)).map { requires.optString(it) }
            sb.append("Dependencies (").append(requires.length()).append("):\n")
            listed.forEach { sb.append("  - ").append(it.take(120)).append('\n') }
            if (requires.length() > MAX_DEPS) sb.append("  ... and ").append(requires.length() - MAX_DEPS).append(" more\n")
        } else {
            sb.append("Dependencies: none declared\n")
        }

        val classifiers = info.optJSONArray("classifiers")
        if (classifiers != null && classifiers.length() > 0) {
            val short = (0 until minOf(classifiers.length(), MAX_CLASSIFIERS)).map { classifiers.optString(it) }
            sb.append("Classifiers: ").append(short.joinToString("; ")).append('\n')
        }

        val files = doc.optJSONArray("urls")
        if (files != null && files.length() > 0) {
            val kinds = (0 until minOf(files.length(), 6)).mapNotNull { i ->
                files.optJSONObject(i)?.optString("packagetype")?.ifBlank { null }
            }.distinct()
            sb.append("Distribution files: ").append(files.length())
            if (kinds.isNotEmpty()) sb.append(" (").append(kinds.joinToString(", ")).append(')')
            sb.append('\n')
        }
        val rel = picked?.let { releases?.optJSONArray(it) }
        val uploadTime = rel?.optJSONObject(0)?.optString("upload_time_iso_8601")?.takeIf { it.isNotBlank() }
        uploadTime?.let { sb.append("Released: ").append(it.take(19).replace('T', ' ')).append('\n') }
        if (releases != null && releases.length() > 1 && requested == null) {
            sb.append("Releases published: ").append(releases.length()).append('\n')
        }
        if (fromCache) sb.append("(cached for up to ").append(CACHE_TTL_MS / 1000).append("s)")
        return sb.toString().trim()
    }

    // ── validation / helpers ───────────────────────────────────────────────────

    private val NPM_NAME = Regex("^(@[a-z0-9\\-._~]+/)?[a-z0-9\\-._~]+$")
    private val PYPI_NAME = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9.+_~!-]*$")

    fun validateNpmName(name: String): String? = when {
        name.isBlank() -> "Give an npm package name."
        name.length > 214 -> "That npm package name is longer than npm allows."
        !NPM_NAME.matches(name) -> "'$name' is not a valid npm package name (lowercase letters, digits, - . _ ~, optional @scope/)."
        else -> null
    }

    fun validatePypiName(name: String): String? = when {
        name.isBlank() -> "Give a PyPI project name."
        name.length > 200 -> "That PyPI name is implausibly long."
        !PYPI_NAME.matches(name) -> "'$name' is not a valid PyPI project name (letters, digits, - . _)."
        else -> null
    }

    fun validateVersion(version: String): String? = when {
        version.length > 100 -> "That version string is too long."
        !VERSION.matches(version) -> "'$version' is not a valid version string."
        else -> null
    }

    private fun encodePath(s: String): String = s.replace("/", "%2F").replace(" ", "%20")

    private fun oneLine(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_DESCRIPTION) flat else flat.take(MAX_DESCRIPTION) + "..."
    }

    private fun repoUrl(meta: JSONObject?): String? {
        val repo = meta?.opt("repository")
        return when (repo) {
            is JSONObject -> repo.optString("url").takeIf { it.isNotBlank() }
            is String -> repo.takeIf { it.isNotBlank() }
            else -> null
        }?.replace(Regex("^git\\+"), "")?.replace(Regex("\\.git$"), "")
    }

    private fun keywords(meta: JSONObject?): String {
        val arr = meta?.optJSONArray("keywords") ?: return ""
        val list = (0 until minOf(arr.length(), MAX_KEYWORDS)).mapNotNull { arr.optString(it).ifBlank { null } }
        return list.joinToString(", ")
    }

    private fun timeFor(doc: JSONObject, version: String?): String? {
        val map = doc.optJSONObject("time") ?: return null
        val raw = (version?.let { map.optString(it) }?.takeIf { it.isNotBlank() }) ?: return null
        return raw.take(10)
    }

    private fun depBlock(sb: StringBuilder, label: String, deps: JSONObject?) {
        if (deps == null) return
        val keys = deps.keys().asSequence().toList().sorted()
        if (keys.isEmpty()) return
        sb.append(label).append(" (").append(keys.size).append("):\n")
        keys.take(MAX_DEPS).forEach { k -> sb.append("  - ").append(k).append(' ').append(deps.optString(k)).append('\n') }
        if (keys.size > MAX_DEPS) sb.append("  ... and ").append(keys.size - MAX_DEPS).append(" more\n")
    }

    /** The app's HTTP conventions: short timeouts, redirects, a hard byte cap, no retries. */
    private fun httpGet(url: String): Fetched {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_CONNECT_MS
                readTimeout = TIMEOUT_READ_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VNCode/1.0 (Android; +https://github.com/wisdom131-max/codespace-ide-mobile)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { it.readNBytes(MAX_BODY_BYTES) } ?: ByteArray(0)
            val text = String(raw, Charsets.UTF_8)
            if (code in 200..299) Fetched.Ok(text) else Fetched.Http(code, text.take(300))
        } catch (e: java.net.SocketTimeoutException) {
            Fetched.Failed("timed out after ${TIMEOUT_READ_MS / 1000}s")
        } catch (e: java.net.UnknownHostException) {
            Fetched.Failed("no network (DNS failed)")
        } catch (e: java.io.IOException) {
            Fetched.Failed(e.message?.take(120) ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }
}
