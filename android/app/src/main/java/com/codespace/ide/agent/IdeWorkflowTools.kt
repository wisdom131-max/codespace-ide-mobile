package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure models behind the remaining VS Code tools: notebooks, patch application, and the todo
 * board. No Android, no disk: every decision is provable in a JVM test, and the executor only
 * supplies text and writes it back.
 */

/** The outcome of a patch or notebook edit: applied, or refused with nothing changed. */
internal sealed class EditResult {
    data class Ok(val text: String, val detail: String) : EditResult()
    data class Refused(val reason: String) : EditResult()
}

/**
 * apply_patch. A strict, verified unified-diff applier.
 *
 * Strictness is the point. A patch tool that "mostly" applies is worse than none: it edits
 * lines the model never saw while reporting success. Every context and removal line must
 * match the file exactly, or the whole patch is refused.
 */
internal object PatchApplier {

    private data class Hunk(
        val oldStart: Int,
        val lines: List<Pair<Char, String>>,
    )

    fun apply(original: String, diff: String): EditResult {
        val hunks = parse(diff)
        if (hunks is HunkParse.Failed) return EditResult.Refused(hunks.reason)
        val parsed = (hunks as HunkParse.Parsed).hunks
        if (parsed.isEmpty()) return EditResult.Refused("The patch contained no hunks.")

        val source = original.split("\n").toMutableList()
        var delta = 0
        var applied = 0
        for (hunk in parsed) {
            val target = hunk.oldStart - 1 + delta
            if (target < 0) return EditResult.Refused("Hunk points before the first line.")
            // Verify the whole hunk against the ORIGINAL text at the target before touching it.
            var probe = target
            for ((kind, text) in hunk.lines) {
                when (kind) {
                    ' ' -> {
                        if (probe >= source.size || source[probe] != text) {
                            return EditResult.Refused(
                                "Context does not match at line " + (probe + 1) + ": expected \"" + text + "\".",
                            )
                        }
                        probe++
                    }
                    '-' -> {
                        if (probe >= source.size || source[probe] != text) {
                            return EditResult.Refused(
                                "Removed line does not match at line " + (probe + 1) + ": expected \"" + text + "\".",
                            )
                        }
                        probe++
                    }
                    '+' -> Unit
                    else -> Unit
                }
            }
            // Now apply: rebuild the region.
            val replacement = ArrayList<String>()
            var consumed = 0
            for ((kind, text) in hunk.lines) {
                when (kind) {
                    ' ' -> {
                        replacement.add(source[target + consumed]); consumed++
                    }
                    '-' -> consumed++
                    '+' -> replacement.add(text)
                }
            }
            repeat(consumed) { source.removeAt(target) }
            source.addAll(target, replacement)
            delta += replacement.size - consumed
            applied++
        }
        return EditResult.Ok(source.joinToString("\n"), "Applied " + applied + " hunk(s).")
    }

    private sealed class HunkParse {
        data class Parsed(val hunks: List<Hunk>) : HunkParse()
        data class Failed(val reason: String) : HunkParse()
    }

    private fun parse(diff: String): HunkParse {
        val hunks = ArrayList<Hunk>()
        var oldStart = -1
        var body = ArrayList<Pair<Char, String>>()
        fun flush() {
            if (oldStart >= 0 && body.isNotEmpty()) hunks.add(Hunk(oldStart, body))
            oldStart = -1
            body = ArrayList()
        }
        for (raw in diff.split("\n")) {
            when {
                raw.startsWith("---") || raw.startsWith("+++") || raw.startsWith("diff ") ||
                    raw.startsWith("index ") -> Unit
                raw.startsWith("@@") -> {
                    flush()
                    val digits = Regex("@+\\s*-([0-9]+)").find(raw)
                    if (digits == null) return HunkParse.Failed("Malformed hunk header: " + raw)
                    oldStart = digits.groupValues[1].toInt()
                }
                oldStart < 0 -> {
                    if (raw.isNotBlank()) return HunkParse.Failed("Patch has content before the first hunk header.")
                }
                raw.startsWith("+") -> body.add('+' to raw.substring(1))
                raw.startsWith("-") -> body.add('-' to raw.substring(1))
                raw.startsWith(" ") -> body.add(' ' to raw.substring(1))
                raw.isEmpty() -> Unit
                else -> return HunkParse.Failed("Unknown patch line: " + raw)
            }
        }
        flush()
        return HunkParse.Parsed(hunks)
    }
}

/**
 * The .ipynb model. Notebooks are JSON, so everything VS Code's notebook tools do can be done
 * here without a notebook runtime: read a cell, read its outputs, add, edit, delete.
 */
internal object NotebookModel {

    data class Cell(val index: Int, val kind: String, val source: String)

    fun isNotebook(json: String): Boolean =
        kotlin.runCatching { JSONObject(json).optJSONArray("cells") != null }.getOrDefault(false)

    fun cells(json: String): List<Cell>? {
        val root = kotlin.runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = root.optJSONArray("cells") ?: return null
        val out = ArrayList<Cell>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(Cell(i, c.optString("cell_type", "code"), sourceOf(c)))
        }
        return out
    }

    private fun sourceOf(cell: JSONObject): String {
        val src = cell.opt("source") ?: return ""
        if (src is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until src.length()) sb.append(src.optString(i))
            return sb.toString()
        }
        return src.toString()
    }

    /** Outputs as text. Never invents content: a cell with no outputs says so. */
    fun outputsOf(json: String, index: Int): EditResult {
        val root = kotlin.runCatching { JSONObject(json) }.getOrNull()
            ?: return EditResult.Refused("This file is not valid JSON, so it is not a notebook.")
        val arr = root.optJSONArray("cells")
            ?: return EditResult.Refused("This file has no cells array: it is not an .ipynb notebook.")
        if (index < 0 || index >= arr.length()) {
            return EditResult.Refused("Cell " + index + " does not exist; the notebook has " + arr.length() + " cells.")
        }
        val cell = arr.optJSONObject(index)
            ?: return EditResult.Refused("Cell " + index + " is not an object.")
        val outs = cell.optJSONArray("outputs")
        if (outs == null || outs.length() == 0) {
            return EditResult.Ok("Cell " + index + " has no outputs.", "empty")
        }
        val sb = StringBuilder()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val text = o.optJSONArray("text") ?: o.optJSONObject("data")?.optJSONArray("text/plain")
            if (text != null) {
                for (j in 0 until text.length()) sb.append(text.optString(j))
            } else if (o.has("ename")) {
                sb.append(o.optString("ename")).append(": ").append(o.optString("evalue")).append('\n')
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        }
        return if (sb.isEmpty()) {
            EditResult.Ok("Cell " + index + " has outputs but none are text (image or interactive output).", "non-text")
        } else {
            EditResult.Ok(sb.toString().trim(), "text")
        }
    }

    fun summary(json: String): EditResult {
        val cells = cells(json) ?: return EditResult.Refused("This file is not an .ipynb notebook.")
        if (cells.isEmpty()) return EditResult.Ok("The notebook has no cells.", "empty")
        val sb = StringBuilder("Notebook: " + cells.size + " cell(s)\n")
        for (c in cells) {
            val first = c.source.lineSequence().firstOrNull()?.trim()?.take(90).orEmpty()
            sb.append(c.index).append(' ').append('[').append(c.kind).append("] ")
                .append(if (first.isEmpty()) "(empty)" else first).append('\n')
        }
        return EditResult.Ok(sb.toString().trim(), "summary")
    }

    /**
     * Add or replace a cell. [index] == -1 appends. Replacing keeps the cell's position, which
     * is what an editor expects; VS Code's edit_notebook_file does the same.
     */
    fun editCell(json: String, index: Int, kind: String, source: String): EditResult {
        val root = kotlin.runCatching { JSONObject(json) }.getOrNull()
            ?: return EditResult.Refused("This file is not valid JSON.")
        val arr = root.optJSONArray("cells")
            ?: return EditResult.Refused("This file has no cells array: it is not an .ipynb notebook.")
        val cell = JSONObject()
            .put("cell_type", kind)
            .put("metadata", JSONObject())
            .put("source", textToJsonArray(source))
        if (kind == "code") {
            cell.put("execution_count", JSONObject.NULL)
            cell.put("outputs", JSONArray())
        }
        return when {
            index < 0 -> {
                arr.put(cell)
                EditResult.Ok(root.toString(2), "Appended a " + kind + " cell at index " + (arr.length() - 1) + ".")
            }
            index >= arr.length() -> EditResult.Refused(
                "Cell " + index + " does not exist; the notebook has " + arr.length() + " cells. Use -1 to append.",
            )
            else -> {
                arr.put(index, cell)
                EditResult.Ok(root.toString(2), "Replaced cell " + index + " with a " + kind + " cell.")
            }
        }
    }

    /** Clear a cell's outputs, which is what re-running a cell means on disk. */
    fun clearOutputs(json: String, index: Int): EditResult {
        val root = kotlin.runCatching { JSONObject(json) }.getOrNull()
            ?: return EditResult.Refused("This file is not valid JSON.")
        val arr = root.optJSONArray("cells") ?: return EditResult.Refused("This is not a notebook.")
        if (index < 0 || index >= arr.length()) return EditResult.Refused("Cell " + index + " does not exist.")
        val cell = arr.optJSONObject(index) ?: return EditResult.Refused("Cell " + index + " is not an object.")
        cell.put("outputs", JSONArray())
        return EditResult.Ok(root.toString(2), "Cleared the outputs of cell " + index + ".")
    }

    fun newNotebook(cells: List<Pair<String, String>>): String {
        val root = JSONObject()
            .put("cells", JSONArray())
            .put("metadata", JSONObject().put("kernelspec", JSONObject().put("name", "python3").put("display_name", "Python 3")))
            .put("nbformat", 4)
            .put("nbformat_minor", 5)
        val arr = root.getJSONArray("cells")
        for ((kind, source) in cells) {
            val cell = JSONObject()
                .put("cell_type", if (kind == "markdown") "markdown" else "code")
                .put("metadata", JSONObject())
                .put("source", textToJsonArray(source))
            if (kind != "markdown") {
                cell.put("execution_count", JSONObject.NULL)
                cell.put("outputs", JSONArray())
            }
            arr.put(cell)
        }
        if (cells.isEmpty()) {
            arr.put(
                JSONObject().put("cell_type", "code").put("metadata", JSONObject())
                    .put("execution_count", JSONObject.NULL).put("outputs", JSONArray())
                    .put("source", textToJsonArray("")),
            )
        }
        return root.toString(2)
    }

    private fun textToJsonArray(text: String): JSONArray {
        val arr = JSONArray()
        val lines = text.split("\n")
        for ((i, line) in lines.withIndex()) {
            arr.put(if (i < lines.size - 1) line + "\n" else line)
        }
        return arr
    }
}

/**
 * manage_todo_list. A todo board is a small piece of state the model must be able to read back,
 * so it is written as JSON and rendered deterministically.
 */
internal object TodoBoard {

    fun render(json: String?): String {
        if (json.isNullOrBlank()) return "The todo list is empty."
        val root = kotlin.runCatching { JSONObject(json) }.getOrNull()
            ?: return "The todo list could not be read; it is not valid JSON."
        val arr = root.optJSONArray("todos") ?: return "The todo list could not be read."
        if (arr.length() == 0) return "The todo list is empty."
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val mark = when (t.optString("status")) {
                "completed" -> "x"
                "in_progress" -> ">"
                else -> " "
            }
            sb.append('[').append(mark).append("] ").append(t.optString("id", (i + 1).toString()))
                .append(". ").append(t.optString("text").ifBlank { "(no text)" }).append('\n')
        }
        return sb.toString().trim()
    }

    /** Refuses silently-invalid input rather than storing a board that cannot be rendered. */
    fun write(rawTodos: String): EditResult {
        val arr = kotlin.runCatching { JSONArray(rawTodos) }.getOrNull()
            ?: return EditResult.Refused("todos must be a JSON array of {id, text, status} objects.")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: return EditResult.Refused("Todo " + i + " is not an object.")
            val status = t.optString("status", "pending")
            if (status !in setOf("pending", "in_progress", "completed")) {
                return EditResult.Refused("Todo " + i + " has status '" + status + "' (pending, in_progress, completed).")
            }
            out.put(
                JSONObject()
                    .put("id", t.optString("id", (i + 1).toString()))
                    .put("text", t.optString("text"))
                    .put("status", status),
            )
        }
        return EditResult.Ok(JSONObject().put("todos", out).toString(2), "Saved " + out.length() + " todo(s).")
    }
}
