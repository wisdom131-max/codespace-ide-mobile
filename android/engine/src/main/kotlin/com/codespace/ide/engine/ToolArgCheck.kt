package com.codespace.ide.engine

/**
 * PROJECT NINE 9.95 (catalog 60 WIRED) - check a tool's arguments before the tool runs.
 *
 * SchemaCheck existed with no caller, so a call with a missing path reached the tool and
 * failed somewhere deep with a message about a null or an empty name. The model then
 * retried blind. This refuses the call up front and says which argument is missing.
 *
 * Only tools whose required arguments are certain are listed. A guessed schema would
 * refuse valid calls, which is worse than the failure it replaces.
 */
object ToolArgCheck {

    private fun field(name: String) = SchemaCheck.Field(name, SchemaCheck.Type.STRING)

    private val REQUIRED: Map<String, List<SchemaCheck.Field>> = mapOf(
        "read_file" to listOf(field("path")),
        "write_file" to listOf(field("path")),
        "run_command" to listOf(field("command")),
        "web_fetch" to listOf(field("url")),
        "docx_create" to listOf(field("path")),
        "xlsx_write" to listOf(field("path")),
    )

    /** Null when the call is fine or the tool has no schema here; a refusal sentence otherwise. */
    fun problem(tool: String, values: Map<String, String>): String? {
        val fields = REQUIRED[tool] ?: return null
        val problems = SchemaCheck.check(values, fields)
        if (problems.isEmpty()) return null
        val named = problems.joinToString(", ") { it.field + " (" + it.reason + ")" }
        return "Refused: " + tool + " needs " + named + ". Nothing was run."
    }
}
