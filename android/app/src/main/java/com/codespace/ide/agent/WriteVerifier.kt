package com.codespace.ide.agent

/**
 * P3.C - an attempted write is not a successful write.
 *
 * The turn used to report "Done - wrote file" as soon as `executeTool` returned without throwing. A
 * write can return cleanly and still not be on disk in the state that was asked for: a path resolved
 * somewhere else, an edit that matched nothing, a rename lost to a full filesystem. The Copilot then
 * told the user the change was made.
 *
 * This reads the file back and says which of the three things is true: it holds what was asked for,
 * it exists but differs, or it is not there. It never claims more than the file shows.
 */
object WriteVerifier {

    /** Tools whose effect can be checked by reading the target back. */
    val VERIFIABLE = setOf(
        "write_file", "create_file", "insert_edit_into_file",
        "multi_replace_string_in_file", "replace_string_in_file", "edit_file", "apply_patch",
    )

    sealed class Result {
        /** The file exists and holds exactly the requested content. */
        data class Verified(val path: String) : Result()

        /** The file exists but is NOT what was asked for. The edit was attempted, not achieved. */
        data class Differs(val path: String) : Result()

        /** The file is not there. */
        data class Missing(val path: String) : Result()

        /** The tool writes, but not in a way this can check by reading one path (e.g. a patch). */
        data class Uncheckable(val path: String, val why: String) : Result()

        /** Not a write, or no path recorded: nothing to say. */
        data object NotApplicable : Result()
    }

    /**
     * [readFile] returns the file's text, or null when it cannot be read. A path that cannot be read is
     * NOT evidence of success, so it is reported as [Result.Missing] rather than quietly passing.
     */
    fun verify(tool: String, args: Map<String, String>, readFile: (String) -> String?): Result {
        if (tool !in VERIFIABLE) return Result.NotApplicable
        val path = args["path"] ?: args["file"] ?: args["filePath"] ?: return Result.NotApplicable
        val actual = readFile(path) ?: return Result.Missing(path)
        val wanted = args["content"]
            ?: args["new_string"]?.let { it }
            ?: return Result.Uncheckable(path, "the tool does not record the exact content it wrote")
        return when {
            wanted.isEmpty() -> Result.Uncheckable(path, "no content was recorded for this write")
            // The recorded content may be fingerprinted when it was long (see ArgFingerprint).
            ArgFingerprint.matches(
                if (wanted.length > ArgFingerprint.KEEP) "sha256:" + ArgFingerprint.sha256(wanted) else wanted,
                actual,
            ) -> Result.Verified(path)
            // A partial edit (replace/insert) only guarantees its fragment is present.
            tool != "write_file" && tool != "create_file" && actual.contains(wanted) -> Result.Verified(path)
            else -> Result.Differs(path)
        }
    }

    /** One line for the user and for the model, or null when there is nothing worth saying. */
    fun line(result: Result): String? = when (result) {
        is Result.Verified -> null // silence: a verified write is the expected case, not news
        is Result.Differs -> "Attempted, not confirmed: " + result.path + " exists but does not hold the requested content."
        is Result.Missing -> "Attempted, not confirmed: " + result.path + " is not there after the write."
        is Result.Uncheckable -> null
        Result.NotApplicable -> null
    }
}
