package com.codespace.ide.chat.panel

/**
 * PROJECT FOUR, STAGE 4.2 (2026-11-16): what must be true BEFORE a capability can be used.
 *
 * Stage 4.1 answered "is there a tool for this?" It could not answer "is the thing that has to
 * happen first done?", which is the other half of a real request. Replacing text across a project
 * needs the match to exist; analysing an archive needs it inspected; a test run is worth less than
 * the build before it. None of that was representable, so the model found out by failing.
 *
 * Two rules keep this honest:
 *
 *  - Prerequisites are METADATA about capabilities, not permission. Nothing here grants a tool,
 *    widens a mode, or approves a write. [com.codespace.ide.agent.runtime.PrereqLedger] records what
 *    actually succeeded, and the dispatch gate still runs every existing check afterwards.
 *  - Only real prerequisites are declared. An optional step is marked OPTIONAL and never blocks
 *    anything; an independent capability has no edges at all. A made-up dependency is worse than a
 *    missing one, because it turns two calls into four and teaches the user to distrust the plan.
 */
internal object ToolPrerequisites {

    enum class Strength(val blocking: Boolean) {
        /** Must have succeeded, with evidence, before the dependent capability may run. */
        REQUIRED(true),

        /** Worth doing first; nobody is stopped if it did not happen. */
        OPTIONAL(false),
    }

    /**
     * What a prerequisite refers to. A capability is the normal case; [TOOL] is for the rare rule
     * that is about one tool rather than a class of work.
     */
    enum class Kind { CAPABILITY, TOOL, CONTEXT }

    data class Prereq(
        val of: String,
        val strength: Strength = Strength.REQUIRED,
        /** Shown to the model verbatim when this prerequisite is missing, so it can act on it. */
        val why: String = "",
        val kind: Kind = Kind.CAPABILITY,
    )

    /** Which capability each tool serves, so a real call can satisfy a prerequisite. */
    private val TOOL_CAPABILITY: Map<String, String> = mapOf(
        "grep_search" to "find",
        "file_search" to "find",
        "search_files" to "find",
        "list_files" to "structure",
        "list_directory" to "structure",
        "project_overview" to "structure",
        "get_project_setup_info" to "structure",
        "read_file" to "read",
        "write_file" to "edit",
        "replace_string_in_file" to "edit",
        "multi_replace_string_in_file" to "edit",
        "grep_replace_all" to "replace",
        "apply_patch" to "edit",
        "diff_files" to "diff",
        "git_diff" to "diff",
        "git_status" to "git",
        "git_log" to "git",
        "git_commit" to "git",
        "runTests" to "test",
        "test_search" to "test",
        "test_failure" to "test",
        "run_command" to "shell",
        "zip_list" to "archive",
        "zip_extract" to "unzip",
        "zip_create" to "zip",
        "web_search" to "web",
        "fetch_url" to "web",
        "image_ocr" to "ocr",
        "image_convert" to "image",
        "video_frames" to "video",
    )

    /**
     * The declared prerequisites, by capability. Every entry here is a claim that the dependent
     * capability is cheaper or safer once the prerequisite has actually happened.
     */
    private val PREREQS: Map<String, List<Prereq>> = mapOf(
        // Replacing text is only meaningful once the text is known to be there. This is the one
        // REQUIRED edge with real teeth: a replace that never found its match is a silent no-op, and
        // "grep_replace_all changed nothing" is the exact failure this prevents.
        "replace" to listOf(
            Prereq("find", Strength.REQUIRED, "find the match first, so the replace has a target"),
        ),
        // Editing an existing file goes better after reading it, but a new file needs no read, so
        // this cannot be required.
        "edit" to listOf(
            Prereq("read", Strength.OPTIONAL, "read the file first when it already exists"),
        ),
        // Looking inside an archive's contents needs the archive inspected; extracting it blindly is
        // how an archive bomb gets unpacked.
        "unzip" to listOf(
            Prereq("archive", Strength.REQUIRED, "list the archive before extracting it"),
        ),
        "archive" to listOf(
            Prereq("find", Strength.OPTIONAL, "locate the archive file first"),
        ),
        // A test run is more useful after a build, but a project with no build step is normal here.
        "test" to listOf(
            Prereq("build", Strength.OPTIONAL, "build before testing when the project has a build"),
        ),
        "build" to listOf(
            Prereq("structure", Strength.OPTIONAL, "find the module or build file first"),
        ),
        // A commit means nothing until the user can see what changed.
        "git" to listOf(
            Prereq("diff", Strength.OPTIONAL, "see the change before committing it"),
        ),
    )

    /** The capabilities a tool serves. Unknown tools serve nothing, which is not an error. */
    fun capabilityOf(tool: String): String? = TOOL_CAPABILITY[tool.trim()]

    /** Capabilities reachable from a tool name, honouring the table above. */
    fun capabilitiesOf(tool: String): Set<String> = listOfNotNull(capabilityOf(tool)).toSet()

    fun prereqsOf(capability: String): List<Prereq> = PREREQS[capability].orEmpty()

    /** Required edges only: what actually blocks. */
    fun requiredOf(capability: String): List<Prereq> =
        prereqsOf(capability).filter { it.strength == Strength.REQUIRED }

    fun optionalOf(capability: String): List<Prereq> =
        prereqsOf(capability).filter { it.strength == Strength.OPTIONAL }

    /** Every capability this table knows about, for coverage reporting and cycle detection. */
    fun knownCapabilities(): Set<String> =
        (PREREQS.keys + PREREQS.values.flatten().map { it.of } + TOOL_CAPABILITY.values).toSet()
}
