package com.codespace.ide.agent

import com.codespace.ide.diagnostics.DiagnosticManager

/**
 * VS Code parity: `get_errors` (verified against microsoft/vscode-copilot-chat
 * `ToolName.GetErrors`, category "VS Code Interaction").
 *
 * Why this is a formatter and not a tool: the tool wrapper needs an Android Context to resolve
 * paths, while the reporting rules are pure text decisions and are the part worth testing. The
 * report is also where honesty lives - a snapshot that is stale, or a filter that matches
 * nothing, must say so instead of returning an empty string that reads as "no problems".
 */
internal object DiagnosticReport {

    const val MAX_ITEMS = 60

    fun format(
        items: List<DiagnosticManager.Diagnostic>,
        pathFilter: String? = null,
        max: Int = MAX_ITEMS,
    ): String {
        if (items.isEmpty()) {
            return "No diagnostics are being reported right now. That is not proof the code is " +
                "clean: language servers and compilers only report for what they are watching."
        }
        val needle = pathFilter?.trim().orEmpty()
        val selected = if (needle.isEmpty()) items else items.filter {
            it.filePath.contains(needle, ignoreCase = true) || it.uri.contains(needle, ignoreCase = true)
        }
        if (selected.isEmpty()) {
            return "No diagnostics match \"" + needle + "\". " + items.size +
                " diagnostic(s) exist elsewhere in the project."
        }

        val sorted = selected.sortedWith(
            compareBy({ it.filePath }, { it.range.startLine }, { it.range.startColumn }),
        )
        var errors = 0
        var warnings = 0
        var stale = 0
        val body = StringBuilder()
        for (d in sorted.take(max)) {
            when (d.severity) {
                DiagnosticManager.Severity.ERROR -> errors++
                DiagnosticManager.Severity.WARNING -> warnings++
                else -> Unit
            }
            if (d.isStale) stale++
            body.append(d.filePath)
                .append(':').append(d.range.startLine)
                .append(':').append(d.range.startColumn)
                .append(' ').append(d.severity.name)
                .append(" [").append(d.sourceId).append("] ")
                .append(d.message.replace('\n', ' '))
            if (d.isStale) body.append(" (stale)")
            body.append('\n')
        }

        val header = StringBuilder()
        header.append(errors).append(errors.let { if (it == 1) " error" else " errors" })
            .append(", ").append(warnings).append(if (warnings == 1) " warning" else " warnings")
        if (sorted.size > max) {
            header.append(" (showing the first ").append(max).append(" of ").append(sorted.size).append(')')
        }
        if (stale > 0) {
            header.append("; ").append(stale).append(" marked stale - the file changed since they were reported")
        }
        return header.append('\n').append(body).toString().trimEnd()
    }
}
