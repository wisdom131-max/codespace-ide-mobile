package com.codespace.ide.agent

/**
 * PROJECT FOUR, STAGE 4.5 (2026-11-16): what each tool promises to return when it worked.
 *
 * [ToolResultCheck] asked "does this output look like a failure?". That is not the same question as
 * "did this tool do the thing", and the gap between them is where a silent write or a partial listing
 * gets reported as success. A contract says, for a small set of tools whose output shape is knowable,
 * what a real success contains.
 *
 * Deliberately narrow: only tools whose acknowledgement text is already stable. A contract guessed
 * for a tool nobody measured is worse than no contract, because it turns a good result into a
 * refusal.
 */
object ToolOutputContract {

    data class Contract(
        /** Words that mean the change was acknowledged, any one of which is enough. */
        val acknowledgement: List<String> = emptyList(),
        /** JSON fields the result must carry when it returns JSON. */
        val jsonFields: List<String> = emptyList(),
        /** Empty output is a real answer for this tool (no matches, empty directory). */
        val emptyAllowed: Boolean = false,
        /** The result may legitimately be cut short. */
        val partialAllowed: Boolean = true,
    )

    private val ACK_WRITE = listOf("wrote", "written", "created", "updated", "replaced", "appended", "unchanged", "no changes")
    private val ACK_MOVE = listOf("moved", "renamed", "copied")
    private val ACK_ZIP = listOf("created", "extracted", "wrote", "entries")
    private val ACK_GIT = listOf("committed", "pushed", "nothing to commit", "already up to date", "on branch")

    private val CONTRACTS: Map<String, Contract> = mapOf(
        "write_file" to Contract(acknowledgement = ACK_WRITE),
        "multi_replace_string_in_file" to Contract(acknowledgement = ACK_WRITE),
        "replace_string_in_file" to Contract(acknowledgement = ACK_WRITE),
        "grep_replace_all" to Contract(acknowledgement = ACK_WRITE),
        "move_path" to Contract(acknowledgement = ACK_MOVE),
        "create_directory" to Contract(acknowledgement = listOf("created", "exists")),
        "zip_create" to Contract(acknowledgement = ACK_ZIP),
        "zip_extract" to Contract(acknowledgement = ACK_ZIP),
        "git_commit_push" to Contract(acknowledgement = ACK_GIT),
        "read_file" to Contract(emptyAllowed = false, partialAllowed = true),
        "grep_search" to Contract(emptyAllowed = true),
        "file_search" to Contract(emptyAllowed = true),
        "list_dir" to Contract(emptyAllowed = true),
        "zip_list" to Contract(emptyAllowed = true),
        "runTests" to Contract(
            acknowledgement = listOf("tests", "passed", "failed", "build successful", "no tests"),
            partialAllowed = false,
        ),
    )

    /** Null means "no contract": the tool is judged by [ToolResultCheck] alone. */
    fun of(tool: String): Contract? = CONTRACTS[tool]
}
