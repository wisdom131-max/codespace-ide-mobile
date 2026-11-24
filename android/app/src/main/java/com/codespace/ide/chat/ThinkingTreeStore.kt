package com.codespace.ide.chat

import android.content.Context
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.util.AtomicJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STAGE 10 (2026-11-20) — Thinking Tree persistence.
 *
 * WHY A SEPARATE DOCUMENT. The transcript already carries the conversation, but it is bounded to
 * 300 messages and prunes the oldest, so tool steps stored only there would silently disappear from
 * a long conversation. The tree also has to survive a transcript rewrite. So the steps live in their
 * own bounded file next to the transcript: `chat/<project>/<conversation>.tree.json`.
 *
 * IDENTITY. A node id is the row id the executor already mints ("r<round>c<n>"), never a position.
 * Re-rendering, reordering or restoring cannot renumber a node, and a duplicate id UPDATES the
 * existing node instead of appending a second one.
 *
 * GROUPING IS NOT DUPLICATED. Nothing here groups anything: restored rows go back through the same
 * `ActivityGrouping` the live tree uses, so there is exactly one grouping rule in the app.
 */
object ThinkingTreeStore {

    /** Bounded storage: a runaway tool loop must not become a slow file. */
    const val MAX_STEPS = 120

    private const val FILE_SUFFIX = ".tree.json"

    /** Where the tree for one conversation lives. Same root as the transcript, different file. */
    fun fileFor(root: File, projectId: String, conversationId: String): File {
        val safeProject = projectId.ifBlank { "_" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeConv = conversationId.ifBlank { "_" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(root, safeProject), safeConv + FILE_SUFFIX)
    }

    private fun rootOf(context: Context): File = File(context.filesDir, "chat")

    /** One restored node. `state` is the executor verdict at the time it was written. */
    data class Step(
        val nodeId: String,
        val turnId: String? = null,
        val label: String = "",
        val detail: String = "",
        val ok: Boolean = true,
        val state: String = "DONE",
        val atMs: Long = 0L,
        /** True when the process ended while this step was still running. */
        val interrupted: Boolean = false,
    )

    /**
     * OWNER DIRECTIVE (2026-11-24): "I want a full wipe of the thinking tree". Every stored tree is
     * deleted, once, on the next start. The store keeps working afterwards - this is a clean slate,
     * not a removal of the feature.
     */
    fun wipeAll(context: Context): Int {
        val root = rootOf(context)
        if (!root.isDirectory) return 0
        var removed = 0
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(FILE_SUFFIX)) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    fun load(context: Context, projectId: String, conversationId: String): List<Step> =
        loadFrom(rootOf(context), projectId, conversationId)

    /**
     * Read the steps for one conversation. A missing or unreadable file is an EMPTY tree, never a
     * guessed one: nothing here invents a step, a state or a timestamp.
     */
    fun loadFrom(root: File, projectId: String, conversationId: String): List<Step> {
        val file = fileFor(root, projectId, conversationId)
        if (!file.isFile) return emptyList()
        val arr = try {
            JSONArray(file.readText())
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Step>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("nodeId", "").trim()
            if (id.isEmpty()) continue
            val state = o.optString("state", "DONE").trim().ifBlank { "DONE" }
            // A step that was still ACTIVE when the app died did NOT finish. Saying so is the whole
            // point of persisting state: a restore must never upgrade an unfinished step to done.
            val interrupted = o.optBoolean("interrupted", false) ||
                state.equals("ACTIVE", true) || state.equals("QUEUED", true)
            out.add(
                Step(
                    nodeId = id,
                    turnId = o.optString("turnId", "").ifBlank { null },
                    label = o.optString("label", ""),
                    detail = o.optString("detail", ""),
                    // STAGE 11: an interrupted step is reported as UNKNOWN with ok=false, so no
                    // renderer can show it as running or as a success.
                    ok = if (interrupted) false else o.optBoolean("ok", true),
                    state = if (interrupted) "UNKNOWN" else state,
                    atMs = o.optLong("atMs", 0L),
                    interrupted = interrupted,
                ),
            )
        }
        return out
    }

    fun append(context: Context, projectId: String, conversationId: String, step: Step): Boolean =
        appendTo(rootOf(context), projectId, conversationId, step)

    /**
     * Add or update one node. Idempotent by [Step.nodeId]: a retry, a re-render or a restore that
     * writes the same node again cannot create a second copy. Oldest nodes are dropped past
     * [MAX_STEPS]; that prunes TREE HISTORY only and never touches a fact or the transcript.
     */
    fun appendTo(root: File, projectId: String, conversationId: String, step: Step): Boolean {
        if (step.nodeId.isBlank()) return false
        val existing = loadFrom(root, projectId, conversationId).toMutableList()
        val at = existing.indexOfFirst { it.nodeId == step.nodeId }
        if (at >= 0) {
            existing[at] = step
        } else {
            existing.add(step)
            while (existing.size > MAX_STEPS) existing.removeAt(0)
        }
        return writeTo(root, projectId, conversationId, existing)
    }

    fun writeTo(root: File, projectId: String, conversationId: String, steps: List<Step>): Boolean {
        val arr = JSONArray()
        for (s in steps.takeLast(MAX_STEPS)) {
            val o = JSONObject()
            o.put("nodeId", s.nodeId)
            if (!s.turnId.isNullOrBlank()) o.put("turnId", s.turnId)
            o.put("label", s.label)
            o.put("detail", s.detail)
            o.put("ok", s.ok)
            o.put("state", s.state)
            o.put("atMs", s.atMs)
            if (s.interrupted) o.put("interrupted", true)
            arr.put(o)
        }
        val file = fileFor(root, projectId, conversationId)
        file.parentFile?.mkdirs()
        return AtomicJson.write(file, arr.toString(2))
    }

    /** The rows a restored tree contributes, in stored order. Grouping happens in the renderer. */
    fun toRows(steps: List<Step>): List<ChatRowModel> = steps.map { s ->
        ChatRowModel.ToolActivity(
            label = s.label,
            detail = if (s.interrupted && !s.detail.contains(INTERRUPTED_MARK))
                (s.detail + " " + INTERRUPTED_MARK).trim() else s.detail,
            id = s.nodeId,
            ok = s.ok && !s.interrupted,
        )
    }

    /**
     * Put restored tool rows back where they belong: immediately BEFORE the last assistant reply,
     * because that is the turn they served. A conversation with no assistant reply yet gets them at
     * the end. Nothing else about the restored rows is touched.
     */
    fun mergeRows(rows: List<ChatRowModel>, steps: List<Step>): List<ChatRowModel> {
        // OWNER DECISION (2026-11-24): "use exactly vs codes rendering for the tree". In VS Code the
        // turn's work lives inside ONE collapsed section and never becomes a transcript row, which is
        // why hidden work cannot leak into the chat there. This function used to splice ToolActivity
        // rows into the transcript; it now returns the rows untouched. The steps are still stored and
        // still rendered - inside the section, which is the only place they belong.
        return rows
    }

    /** Stated in the row itself so a restored step never looks like it finished. */
    const val INTERRUPTED_MARK = "(interrupted when the app closed)"
}
