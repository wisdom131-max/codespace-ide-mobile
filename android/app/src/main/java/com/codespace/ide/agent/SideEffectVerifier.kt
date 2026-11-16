package com.codespace.ide.agent

import org.json.JSONObject
import java.io.File

/**
 * PROJECT FOUR, STAGE 4.5 (2026-11-16): a write that returned is not a write that landed.
 *
 * For a handful of file tools the outcome can be checked from the filesystem in microseconds, so it
 * is checked rather than assumed. This never re-runs the tool - re-running a write to prove the first
 * one worked is how work gets done twice - and it never edits the output the model sees beyond
 * appending what was observed.
 */
object SideEffectVerifier {

    enum class Result { VERIFIED, REFUTED, NOT_CHECKABLE }

    data class Verification(val result: Result, val note: String) {
        val ok: Boolean get() = result != Result.REFUTED
    }

    private val NOTHING = Verification(Result.NOT_CHECKABLE, "")

    /**
     * @param projectRoot the open project's root, used to resolve relative paths exactly as the tool did.
     */
    fun verify(tool: String, args: JSONObject, projectRoot: String?): Verification {
        return try {
            when (tool) {
                "write_file", "replace_string_in_file", "multi_replace_string_in_file", "grep_replace_all" ->
                    verifyWrite(tool, args, projectRoot)
                "move_path" -> verifyMove(args, projectRoot)
                "create_directory" -> verifyDirectory(args, projectRoot)
                "zip_create", "zip_extract" -> verifyPath(args, projectRoot, "created")
                else -> NOTHING
            }
        } catch (t: Throwable) {
            // The verifier failing says nothing about the tool.
            NOTHING
        }
    }

    private fun target(args: JSONObject, root: String?): File? {
        val raw = firstString(args, "path", "file", "filename", "filePath", "target") ?: return null
        val file = File(raw)
        // A relative path with no known root cannot be resolved, and an unresolvable path is not
        // evidence of a failure: say nothing rather than refute a change that may have landed.
        return if (file.isAbsolute) file else if (root != null) File(root, raw) else null
    }

    private fun verifyWrite(tool: String, args: JSONObject, root: String?): Verification {
        val file = target(args, root) ?: return NOTHING
        if (!file.exists()) {
            return Verification(Result.REFUTED, tool + " reported success but " + file.name + " is not there")
        }
        if (file.isDirectory) return NOTHING
        val intended = firstString(args, "content", "new_string", "newStr", "text")
        val onDisk = runCatching { file.readText() }.getOrNull()
        if (onDisk == null) {
            return Verification(Result.REFUTED, file.name + " exists but could not be read back")
        }
        if (intended != null && tool == "write_file" && intended != onDisk) {
            return Verification(
                Result.REFUTED,
                file.name + " was written but its contents do not match what was asked for",
            )
        }
        if (intended != null && tool != "write_file" && !onDisk.contains(intended.take(80))) {
            return Verification(Result.REFUTED, file.name + " does not contain the replacement that was reported")
        }
        return Verification(Result.VERIFIED, "verified: " + file.name + " holds the change")
    }

    private fun verifyMove(args: JSONObject, root: String?): Verification {
        val from = firstString(args, "from", "source", "oldPath", "path") ?: return NOTHING
        val to = firstString(args, "to", "destination", "newPath", "target") ?: return NOTHING
        val dest = resolve(to, root)
        val src = resolve(from, root)
        if (src == null || dest == null) return NOTHING
        if (!dest.exists()) {
            return Verification(Result.REFUTED, "the move was reported but " + dest.name + " is not there")
        }
        if (src.exists() && src.absolutePath != dest.absolutePath) {
            return Verification(Result.REFUTED, "both " + src.name + " and " + dest.name + " exist after a move")
        }
        return Verification(Result.VERIFIED, "verified: " + dest.name + " is in place")
    }

    private fun verifyDirectory(args: JSONObject, root: String?): Verification {
        val dir = target(args, root) ?: return NOTHING
        return if (dir.isDirectory) {
            Verification(Result.VERIFIED, "verified: " + dir.name + " exists")
        } else {
            Verification(Result.REFUTED, "the directory was reported created but " + dir.name + " is not there")
        }
    }

    private fun verifyPath(args: JSONObject, root: String?, what: String): Verification {
        val file = target(args, root) ?: return NOTHING
        return if (file.exists()) {
            Verification(Result.VERIFIED, "verified: " + file.name + " is " + what)
        } else {
            Verification(Result.REFUTED, "reported " + what + " but " + file.name + " is not on disk")
        }
    }

    private fun resolve(path: String, root: String?): File? {
        val file = File(path)
        return if (file.isAbsolute) file else if (root != null) File(root, path) else null
    }

    private fun firstString(args: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = args.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }
}
