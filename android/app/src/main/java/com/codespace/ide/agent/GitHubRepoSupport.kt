package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * github_repo, the parts that can be proven in a JVM test: which repository to read, and how to
 * render what GitHub returned.
 *
 * The default repo is never guessed. It comes from the project's own git remote, which is the
 * same binding the Source Control panel shows the user.
 */
internal object GitHubRepoSupport {

    /** "owner/name" from a remote URL, or null when the URL is not a GitHub remote. */
    fun parseRepoRef(remote: String): String? {
        val trimmed = remote.trim().removeSuffix(".git")
        if (trimmed.isEmpty()) return null
        val afterHost = when {
            trimmed.contains("github.com/") -> trimmed.substringAfter("github.com/")
            trimmed.contains("github.com:") -> trimmed.substringAfter("github.com:")
            else -> return null
        }
        val parts = afterHost.trim('/').split('/')
        if (parts.size < 2) return null
        val owner = parts[0]
        val name = parts[1]
        if (owner.isBlank() || name.isBlank()) return null
        return owner + "/" + name
    }

    /** Reads [remote "origin"] url = ... out of a .git/config body. */
    fun repoRefFromGitConfig(config: String): String? {
        var inOrigin = false
        for (raw in config.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inOrigin = line.replace(" ", "").equals("[remote\"origin\"]", ignoreCase = true)
                continue
            }
            if (inOrigin && line.startsWith("url") && line.contains("=")) {
                return parseRepoRef(line.substringAfter("="))
            }
        }
        return null
    }

    fun renderOverview(json: String): String {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return "GitHub returned something that is not JSON."
        val sb = StringBuilder()
        sb.append(o.optString("full_name", "?")).append('\n')
        o.optString("description").takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        sb.append("default branch: ").append(o.optString("default_branch", "?"))
            .append(", private: ").append(o.optBoolean("private")).append('\n')
        sb.append("open issues: ").append(o.optInt("open_issues_count"))
            .append(", stars: ").append(o.optInt("stargazers_count")).append('\n')
        o.optString("language").takeIf { it.isNotBlank() }?.let { sb.append("language: ").append(it).append('\n') }
        sb.append("url: ").append(o.optString("html_url", ""))
        return sb.toString().trim()
    }

    /** Issues or pull requests, newest first as GitHub returns them. */
    fun renderItems(json: String, kind: String): String {
        val arr = runCatching { JSONArray(json) }.getOrNull()
            ?: return "GitHub returned something that is not a list."
        if (arr.length() == 0) return "No " + kind + " found."
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val marker = if (kind == "pull requests" && o.optBoolean("draft")) " (draft)" else ""
            sb.append('#').append(o.optInt("number"))
                .append(' ').append(o.optString("title", "(no title)")).append(marker)
                .append(" [").append(o.optString("state", "?")).append(']')
            val user = o.optJSONObject("user")?.optString("login")
            if (!user.isNullOrBlank()) sb.append(" by ").append(user)
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    fun renderFile(path: String, body: String): String {
        if (body.isBlank()) return path + " is empty."
        return path + " (" + body.length + " chars):\n" + body.take(4000)
    }
}
