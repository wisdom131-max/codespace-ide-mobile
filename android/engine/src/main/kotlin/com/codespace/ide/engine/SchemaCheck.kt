package com.codespace.ide.engine

/**
 * PROJECT NINE 9.22 (catalog 60) - say which field is wrong, not just that something is.
 *
 * A tool result was checked for being JSON and nothing more, so a model could return the right
 * shape with the wrong contents and the caller had no way to explain the failure. This names the
 * field and the reason, which is the difference between a retry that fixes it and a retry that
 * repeats it.
 */
object SchemaCheck {

    enum class Type { STRING, NUMBER, BOOLEAN }

    data class Field(
        val name: String,
        val type: Type,
        val required: Boolean = true,
        val maxLength: Int = 0,
    )

    data class Problem(val field: String, val reason: String)

    fun check(values: Map<String, String>, fields: List<Field>): List<Problem> {
        val problems = ArrayList<Problem>()
        for (f in fields) {
            val raw = values[f.name]
            if (raw == null) {
                if (f.required) problems.add(Problem(f.name, "missing"))
                continue
            }
            val trimmed = raw.trim()
            when (f.type) {
                Type.NUMBER -> if (trimmed.toDoubleOrNull() == null) {
                    problems.add(Problem(f.name, "not a number"))
                }
                Type.BOOLEAN -> if (trimmed != "true" && trimmed != "false") {
                    problems.add(Problem(f.name, "not a boolean"))
                }
                Type.STRING -> if (trimmed.isEmpty() && f.required) {
                    problems.add(Problem(f.name, "empty"))
                }
            }
            if (f.maxLength > 0 && trimmed.length > f.maxLength) {
                problems.add(Problem(f.name, "longer than " + f.maxLength + " characters"))
            }
        }
        return problems
    }
}
