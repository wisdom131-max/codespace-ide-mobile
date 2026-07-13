package com.codespace.ide.ui.panes

import androidx.compose.ui.graphics.Color
import com.codespace.ide.diagnostics.Problem

/**
 * Renders severity indicators (colored left-border bars) in the editor gutter
 * at lines that have diagnostics problems. Called from the gutter Column in CodeEditor.
 * Returns a map of lineIndex (0-based) -> Color to use as gutter highlight.
 */
object DiagnosticsGutter {
    fun gutterColors(problems: List<Problem>): Map<Int, Color> {
        return problems.associate { p ->
            (p.line - 1) to when (p.severity) {
                Problem.Severity.ERROR   -> Color(0xFFF44747)  // VS Code red
                Problem.Severity.WARNING -> Color(0xFFCCA700)  // VS Code yellow  
                Problem.Severity.INFO    -> Color(0xFF75BEFF)  // VS Code blue
            }
        }
    }
}
