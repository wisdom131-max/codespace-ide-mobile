package com.codespace.ide.chat

import android.util.Log
import java.io.File

/**
 * R9-A — Custom agent modes from .agent.md / .chatmode.md files.
 *
 * Approved design (R9_PREPLAN.md, Wisdom-approved 2026-09-13):
 *  - Discovery: the .codespace/modes dir (files .agent.md / .chatmode.md) and
 *    the .github/chatmodes dir (VS Code location — real VS Code
 *    chat-mode repos work unmodified).
 *  - Frontmatter-lite parser (D5): key: value lines + "  - item" dash lists.
 *    NO YAML library, NO code execution — the body is prompt text only.
 *  - `tools:` is a RESTRICT-ONLY allowlist (D3): FlowGate + ChatPermissionStore
 *    levels stay supreme; the allowlist can never grant anything.
 *  - `model:` pins only as a prefill; the user can always switch.
 *
 * Session persistence is ADDITIVE (D2): ChatSession gains a nullable
 * customModeId; old JSON loads unchanged (missing key -> null).
 */
object CustomModeStore {

    private const val TAG = "CustomModeStore"

    data class CustomAgentMode(
        val id: String,            // filename stem, e.g. "reviewer"
        val name: String,          // frontmatter name (fallback: id)
        val description: String,   // frontmatter description (mode-menu subtitle)
        val tools: List<String>?,  // null = unrestricted; non-null = restrict-only allowlist
        val model: String?,        // null = no pin; non-null = prefill once
        val body: String,          // instruction body -> system-prompt rider
    )

    /** Scan the mode dirs; malformed files are skipped with a log line, never throw. */
    fun discover(projectRoot: String?): List<CustomAgentMode> {
        if (projectRoot.isNullOrBlank()) return emptyList()
        val dirs = listOf(
            File(projectRoot, ".codespace/modes"),
            File(projectRoot, ".github/chatmodes"),
        )
        return try {
            dirs.flatMap { d ->
                d.listFiles { f ->
                    f.isFile && (f.name.endsWith(".agent.md", true) || f.name.endsWith(".chatmode.md", true))
                }?.toList() ?: emptyList()
            }.sortedBy { it.name }.mapNotNull { f ->
                val stem = f.name.removeSuffix(".md")
                val id = stem.removeSuffix(".agent").removeSuffix(".chatmode")
                try {
                    parse(f.readText(), id)
                } catch (e: Exception) {
                    Log.w(TAG, "skipping malformed custom mode " + f.name + ": " + e.message)
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "custom-mode discovery failed: " + e.message)
            emptyList()
        }
    }

    fun findById(projectRoot: String?, id: String?): CustomAgentMode? {
        if (id.isNullOrBlank()) return null
        return discover(projectRoot).firstOrNull { it.id == id }
    }

    /** Parse one mode file. Returns null when the frontmatter or body is unusable. */
    fun parse(text: String, fallbackId: String): CustomAgentMode? {
        val fm = parseFrontmatter(text) ?: return null
        if (fm.body.isBlank()) return null
        val name = fm.fields["name"]?.takeIf { it.isNotBlank() } ?: fallbackId
        return CustomAgentMode(
            id = fallbackId,
            name = name,
            description = fm.fields["description"] ?: "",
            tools = fm.lists["tools"]?.takeIf { it.isNotEmpty() },
            model = fm.fields["model"]?.takeIf { it.isNotBlank() },
            body = fm.body,
        )
    }
}

// ── Shared frontmatter-lite parser (D5) ─────────────────────────────────────
// Reused by SkillsCatalog (R9-B). key: value pairs + "  - item" dash lists
// inside a --- block. Anything unparsable is ignored, never fatal.

internal data class Frontmatter(
    val fields: Map<String, String>,
    val lists: Map<String, List<String>>,
    val body: String,
)

internal fun parseFrontmatter(text: String): Frontmatter? {
    val t = text.trimStart()
    if (!t.startsWith("---")) return null
    val lines = t.lines()
    var i = 1
    val fields = mutableMapOf<String, String>()
    val lists = mutableMapOf<String, MutableList<String>>()
    var currentListKey: String? = null
    while (i < lines.size && lines[i].trim() != "---") {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith("- ")) {
            currentListKey?.let { k ->
                lists.getOrPut(k) { mutableListOf() }.add(trimmed.removePrefix("- ").trim())
            }
        } else {
            val idx = trimmed.indexOf(':')
            if (idx > 0) {
                val k = trimmed.substring(0, idx).trim()
                val v = trimmed.substring(idx + 1).trim()
                if (v.isEmpty()) {
                    currentListKey = k   // "tools:" alone -> following dash items collect here
                } else {
                    fields[k] = v
                    currentListKey = null
                }
            }
        }
        i++
    }
    if (i >= lines.size) return null // no closing ---
    val body = lines.drop(i + 1).joinToString("\n").trim()
    return Frontmatter(fields, lists, body)
}
