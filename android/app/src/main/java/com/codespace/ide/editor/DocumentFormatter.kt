package com.codespace.ide.editor

import android.content.Context
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import java.io.File

/**
 * DocumentFormatter — language-aware code formatting via proot shell.
 *
 * Detects the file's language and runs the appropriate formatter:
 * - Kotlin: ktlint
 * - JavaScript/TypeScript: prettier
 * - Python: black
 * - Go: gofmt
 * - Java: google-java-format
 * - JSON: python -m json.tool
 * - HTML/CSS: prettier
 * - XML: xmllint --format
 * - Shell: shfmt
 *
 * Phase 22-E — Format Document.
 */

object DocumentFormatter {

    data class FormatResult(val success: Boolean, val formattedContent: String?, val message: String)

    /**
     * Returns the formatter command for the given language, or null if no formatter is available.
     */
    fun formatterCommand(language: Language, fileName: String): String? {
        return when (language) {
            Language.KOTLIN -> "ktlint --format '$fileName' 2>&1"
            Language.JAVASCRIPT, Language.TYPESCRIPT -> "prettier --write '$fileName' 2>&1"
            Language.PYTHON -> "black --quiet '$fileName' 2>&1"
            Language.GO -> "gofmt -w '$fileName' 2>&1"
            Language.JAVA -> "google-java-format --replace '$fileName' 2>&1"
            Language.JSON -> "python3 -m json.tool '$fileName' --compact 2>/dev/null | python3 -m json.tool > '$fileName.tmp' && mv '$fileName.tmp' '$fileName' 2>&1"
            Language.HTML, Language.CSS -> "prettier --write '$fileName' 2>&1"
            Language.XML -> "xmllint --format '$fileName' > '$fileName.tmp' && mv '$fileName.tmp' '$fileName' 2>&1"
            Language.SHELL -> "shfmt -w '$fileName' 2>&1"
            else -> null
        }
    }

    /**
     * Returns true if a formatter is available for this language.
     */
    fun isFormattable(language: Language): Boolean {
        return formatterCommand(language, "test") != null
    }

    /**
     * Format the file at the given path using the appropriate formatter.
     * The file must already exist on disk with the current content.
     * Returns the formatted content after running the formatter.
     */
    fun format(
        context: Context,
        filePath: String,
        language: Language,
    ): FormatResult {
        val file = File(filePath)
        if (!file.exists()) {
            return FormatResult(false, null, "File not found: $filePath")
        }

        val fileName = file.name
        val command = formatterCommand(language, fileName) ?: return FormatResult(false, null, "No formatter for ${language.displayName}")

        // Get the directory containing the file for the workdir
        val workdir = file.parent ?: "/"
        val guestPath = ProotInstaller.hostToGuestPath(context, workdir)
        if (guestPath == null) {
            return FormatResult(false, null, "Cannot access proot environment")
        }

        val originalContent = file.readText()

        // Run the formatter
        val output = ProotInstaller.execOnce(context, command, guestPath.absolutePath, timeoutSeconds = 30)

        // Read back the formatted content
        val formattedContent = if (file.exists()) file.readText() else originalContent

        return if (formattedContent != originalContent) {
            FormatResult(true, formattedContent, "Formatted with $language formatter")
        } else {
            // Check if formatter was available
            val noChange = if (output.contains("not found") || output.contains("command not found") || output.contains("No such file")) {
                "Formatter not installed for ${language.displayName}"
            } else {
                "No formatting changes needed"
            }
            FormatResult(false, formattedContent, noChange)
        }
    }

    /**
     * Basic indentation-based formatting as a fallback when no external formatter is available.
     * Normalizes tabs to spaces, trims trailing whitespace, ensures final newline.
     */
    fun basicFormat(content: String, indentSize: Int = 4): String {
        val lines = content.lines()
        val result = mutableListOf<String>()
        for (line in lines) {
            // Trim trailing whitespace
            var formatted = line.trimEnd()
            // Convert tabs to spaces
            formatted = formatted.replace("\t", " ".repeat(indentSize))
            result.add(formatted)
        }
        // Ensure file ends with newline
        return result.joinToString("\n") + if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
    }
}
