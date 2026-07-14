package com.codespace.ide.build

/**
 * Phase 11-F: Parse Gradle build output into structured diagnostics.
 *
 * Extracts errors and warnings from Gradle stdout/stderr and converts them
 * into clickable BuildProblem entries that can feed into the ProblemsPanel.
 *
 * Handles common Gradle error formats:
 * - e: /path/file.kt:(line, col): error: message
 * - > Task :compileDebugJavaWithJavac FAILED
 * - FAILURE: Build failed with an exception.
 * - * What went wrong: ...
 * - * Where: Build file '...' line: N
 */
object GradleErrorParser {

    data class BuildProblem(
        val severity: Severity,
        val file: String,
        val line: Int,
        val column: Int,
        val message: String,
        val task: String = "",
    )

    enum class Severity { ERROR, WARNING, INFO }

    /**
     * Extract all errors from Gradle build output.
     */
    fun extractErrors(output: String): List<BuildProblem> {
        val problems = mutableListOf<BuildProblem>()
        val lines = output.lines()

        var currentTask = ""
        var inFailureSection = false
        var failureMessage = ""

        for (i in lines.indices) {
            val line = lines[i]

            // Track current task
            val taskMatch = Regex("""> Task :(\S+)""").find(line)
            if (taskMatch != null) {
                currentTask = taskMatch.groupValues[1]
                if (line.contains("FAILED")) {
                    problems.add(BuildProblem(
                        severity = Severity.ERROR,
                        file = "",
                        line = 0,
                        column = 0,
                        message = "Task ':$currentTask' FAILED",
                        task = currentTask,
                    ))
                }
            }

            // Kotlin compiler errors: e: /path/file.kt:(line, col): error: message
            // Kotlin compiler errors (alt): e: file:///path/file.kt:(line, col): error: message
            val kotlinErrorMatch = Regex(
                """^e:\s+(?:file://)?(.+?):\((\d+),\s*(\d+)\):\s*(.*)"""
            ).find(line)
            if (kotlinErrorMatch != null) {
                problems.add(BuildProblem(
                    severity = Severity.ERROR,
                    file = kotlinErrorMatch.groupValues[1].removePrefix("file://"),
                    line = kotlinErrorMatch.groupValues[2].toIntOrNull() ?: 0,
                    column = kotlinErrorMatch.groupValues[3].toIntOrNull() ?: 0,
                    message = kotlinErrorMatch.groupValues[4],
                    task = currentTask,
                ))
            }

            // Java compiler errors: /path/file.java:line: error: message
            val javaErrorMatch = Regex(
                """^(/[^:]+\.java):(\d+):\s*error:\s*(.*)"""
            ).find(line)
            if (javaErrorMatch != null) {
                problems.add(BuildProblem(
                    severity = Severity.ERROR,
                    file = javaErrorMatch.groupValues[1],
                    line = javaErrorMatch.groupValues[2].toIntOrNull() ?: 0,
                    column = 0,
                    message = javaErrorMatch.groupValues[3],
                    task = currentTask,
                ))
            }

            // "What went wrong:" section — capture the next few lines
            if (line.contains("* What went wrong:")) {
                inFailureSection = true
                failureMessage = ""
                continue
            }
            if (inFailureSection) {
                if (line.startsWith("*") || line.isBlank()) {
                    if (failureMessage.isNotEmpty()) {
                        problems.add(BuildProblem(
                            severity = Severity.ERROR,
                            file = "",
                            line = 0,
                            column = 0,
                            message = failureMessage.trim(),
                            task = currentTask,
                        ))
                        inFailureSection = false
                        failureMessage = ""
                    }
                } else {
                    failureMessage += line.trim() + " "
                }
            }

            // Build file line references: "Build file '...' line: N"
            val buildFileMatch = Regex(
                """Build file '([^']+)' line: (\d+)"""
            ).find(line)
            if (buildFileMatch != null) {
                val nextLine = if (i + 1 < lines.size) lines[i + 1] else ""
                problems.add(BuildProblem(
                    severity = Severity.ERROR,
                    file = buildFileMatch.groupValues[1],
                    line = buildFileMatch.groupValues[2].toIntOrNull() ?: 0,
                    column = 0,
                    message = nextLine.trim(),
                    task = currentTask,
                ))
            }
        }

        // Handle trailing failure section
        if (inFailureSection && failureMessage.isNotEmpty()) {
            problems.add(BuildProblem(
                severity = Severity.ERROR,
                file = "",
                line = 0,
                column = 0,
                message = failureMessage.trim(),
                task = currentTask,
            ))
        }

        return problems
    }

    /**
     * Extract all warnings from Gradle build output.
     */
    fun extractWarnings(output: String): List<BuildProblem> {
        val problems = mutableListOf<BuildProblem>()
        val lines = output.lines()

        var currentTask = ""

        for (line in lines) {
            val taskMatch = Regex("""> Task :(\S+)""").find(line)
            if (taskMatch != null) {
                currentTask = taskMatch.groupValues[1]
            }

            // Kotlin warnings: w: /path/file.kt:(line, col): warning: message
            val kotlinWarnMatch = Regex(
                """^w:\s+(?:file://)?(.+?):\((\d+),\s*(\d+)\):\s*(.*)"""
            ).find(line)
            if (kotlinWarnMatch != null) {
                problems.add(BuildProblem(
                    severity = Severity.WARNING,
                    file = kotlinWarnMatch.groupValues[1].removePrefix("file://"),
                    line = kotlinWarnMatch.groupValues[2].toIntOrNull() ?: 0,
                    column = kotlinWarnMatch.groupValues[3].toIntOrNull() ?: 0,
                    message = kotlinWarnMatch.groupValues[4],
                    task = currentTask,
                ))
            }

            // Java warnings: /path/file.java:line: warning: message
            val javaWarnMatch = Regex(
                """^(/[^:]+\.java):(\d+):\s*warning:\s*(.*)"""
            ).find(line)
            if (javaWarnMatch != null) {
                problems.add(BuildProblem(
                    severity = Severity.WARNING,
                    file = javaWarnMatch.groupValues[1],
                    line = javaWarnMatch.groupValues[2].toIntOrNull() ?: 0,
                    column = 0,
                    message = javaWarnMatch.groupValues[3],
                    task = currentTask,
                ))
            }

            // Gradle deprecation warnings
            if (line.contains("has been deprecated") || line.contains("is deprecated")) {
                problems.add(BuildProblem(
                    severity = Severity.WARNING,
                    file = "",
                    line = 0,
                    column = 0,
                    message = line.trim(),
                    task = currentTask,
                ))
            }
        }

        return problems
    }

    /**
     * Get all problems (errors + warnings) sorted by severity.
     */
    fun extractAllProblems(output: String): List<BuildProblem> {
        val errors = extractErrors(output)
        val warnings = extractWarnings(output)
        return errors + warnings
    }

    /**
     * Generate a human-readable build summary from the output.
     */
    fun generateSummary(output: String, durationMs: Long): String {
        val errors = extractErrors(output)
        val warnings = extractWarnings(output)
        val isSuccess = output.contains("BUILD SUCCESSFUL", ignoreCase = true)

        val sb = StringBuilder()
        sb.appendLine(if (isSuccess) "✅ BUILD SUCCESSFUL" else "❌ BUILD FAILED")
        sb.appendLine("Duration: ${(durationMs / 1000)}s")
        sb.appendLine("Errors: ${errors.size}")
        sb.appendLine("Warnings: ${warnings.size}")

        if (errors.isNotEmpty()) {
            sb.appendLine("\nErrors:")
            errors.take(10).forEachIndexed { i, err ->
                val location = if (err.file.isNotEmpty()) "${err.file}:${err.line}" else "Task: ${err.task}"
                sb.appendLine("  ${i + 1}. $location — ${err.message}")
            }
            if (errors.size > 10) {
                sb.appendLine("  ... and ${errors.size - 10} more")
            }
        }

        return sb.toString()
    }
}
