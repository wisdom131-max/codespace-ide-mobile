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
 * Phase 37-FIX — Auto-install missing formatters (same pattern as LspManager).
 */

object DocumentFormatter {

    data class FormatResult(val success: Boolean, val formattedContent: String?, val message: String)

    /**
     * P41-R: Returns the formatter command for the given language using the user's
     * preferred formatter from FormatterConfig, falling back to the default if not set.
     * Returns null if no formatter is available (or uses built-in fallback).
     */
    fun formatterCommand(language: Language, fileName: String): String? {
        // P41-R: Use FormatterConfig to get the user's selected formatter
        // This is called without context (legacy), so use the default (first option)
        val formatters = FormatterConfig.availableFormatters[language] ?: return null
        val selected = formatters.firstOrNull() ?: return null
        return selected.commandTemplate?.replace("\$FILE", fileName)
    }

    /**
     * P41-R: Returns the formatter command using the user's selected formatter.
     * This is the preferred entry point — it respects per-language formatter preferences.
     */
    fun formatterCommandForContext(context: Context, language: Language, fileName: String): String? {
        val selected = FormatterConfig.getSelectedFormatter(context, language)
        return selected.commandTemplate?.replace("\$FILE", fileName)
    }

    /** P41-R: Check if the user's selected formatter is the built-in fallback. */
    fun isUsingFallback(context: Context, language: Language): Boolean {
        val selected = FormatterConfig.getSelectedFormatter(context, language)
        return selected.commandTemplate == null
    }

    /**
     * Returns the shell check command to verify a formatter is installed.
     * Returns null if no check is needed (e.g. gofmt ships with golang).
     */
    private fun formatterCheckCommand(language: Language): String? {
        return when (language) {
            Language.KOTLIN -> "command -v ktlint"
            Language.JAVASCRIPT, Language.TYPESCRIPT, Language.HTML, Language.CSS -> "command -v prettier"
            Language.PYTHON -> "command -v black"
            Language.GO -> "command -v gofmt"  // ships with golang-go
            Language.JAVA -> "command -v google-java-format"
            Language.JSON -> "command -v python3"
            Language.XML -> "command -v xmllint"
            Language.SHELL -> "command -v shfmt"
            else -> null
        }
    }

    /**
     * Returns the auto-install command for a missing formatter.
     * Uses the same dpkg-lock-clearing preamble as LspManager to avoid stale lock issues.
     * Returns null if auto-install is not supported for this language.
     */
    private fun formatterInstallCommand(language: Language): String? {
        val dpkgFix = "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
            "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; "
        return when (language) {
            Language.JAVASCRIPT, Language.TYPESCRIPT, Language.HTML, Language.CSS -> {
                // prettier via npm — npm is available after JS/TS LSP server setup
                dpkgFix + "( command -v npm >/dev/null 2>&1 || " +
                    "( apt-get update -qq && apt-get install -y --no-install-recommends nodejs npm 2>/dev/null ) ) && " +
                    "npm install -g prettier"
            }
            Language.PYTHON -> {
                // black via pip3 — pip3 is available after Python LSP server setup
                dpkgFix + "( command -v pip3 >/dev/null 2>&1 || " +
                    "( apt-get update -qq && apt-get install -y --no-install-recommends python3-pip ) ) && " +
                    "pip3 install --break-system-packages black"
            }
            Language.KOTLIN -> {
                // ktlint needs JRE — available after Kotlin LSP server setup
                dpkgFix + "( command -v java >/dev/null 2>&1 || " +
                    "( apt-get update -qq && apt-get install -y --no-install-recommends default-jre-headless ) ) && " +
                    "curl -fsSL https://github.com/pinterest/ktlint/releases/download/1.3.1/ktlint -o /usr/local/bin/ktlint && " +
                    "chmod +x /usr/local/bin/ktlint"
            }
            Language.JAVA -> {
                // google-java-format needs JRE
                dpkgFix + "( command -v java >/dev/null 2>&1 || " +
                    "( apt-get update -qq && apt-get install -y --no-install-recommends default-jre-headless ) ) && " +
                    "curl -fsSL https://github.com/google/google-java-format/releases/download/v1.24.0/google-java-format-1.24.0-all-deps.jar " +
                    "-o /usr/local/lib/google-java-format.jar && " +
                    "echo '#!/bin/bash' > /usr/local/bin/google-java-format && " +
                    "echo 'java -jar /usr/local/lib/google-java-format.jar \"$$@\"' >> /usr/local/bin/google-java-format && " +
                    "chmod +x /usr/local/bin/google-java-format"
            }
            Language.XML -> {
                // xmllint via libxml2-utils
                dpkgFix + "apt-get update -qq && apt-get install -y --no-install-recommends libxml2-utils"
            }
            Language.SHELL -> {
                // shfmt via GitHub binary
                dpkgFix + "( uname -m | grep -q aarch64 && arch=arm64 || arch=amd64 ) && " +
                    "curl -fsSL \"https://github.com/mvdan/sh/releases/download/v3.9.1/shfmt_3.9.1_linux_${'$'}{arch}\" " +
                    "-o /usr/local/bin/shfmt && chmod +x /usr/local/bin/shfmt"
            }
            Language.GO -> null  // gofmt ships with golang-go, no separate install
            Language.JSON -> null  // python3 is part of the base rootfs
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
     * Auto-installs the formatter if it's missing (same pattern as LspManager).
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
        // P41-R: Use context-aware formatter selection (respects user preferences)
        val selectedFormatter = FormatterConfig.getSelectedFormatter(context, language)
        // If the selected formatter is built-in fallback, use it directly
        if (selectedFormatter.commandTemplate == null) {
            val originalContent = file.readText()
            val formatted = FormatterConfig.fallbackFormat(originalContent)
            return if (formatted != originalContent) {
                file.writeText(formatted)
                FormatResult(true, formatted, "Formatted with built-in formatter")
            } else {
                FormatResult(true, formatted, "No formatting changes needed")
            }
        }
        val command = selectedFormatter.commandTemplate.replace("\$FILE", fileName)
            ?: return FormatResult(false, null, "No formatter for ${language.displayName}")

        // Get the directory containing the file for the workdir
        val workdir = file.parent ?: "/"
        val guestPath = ProotInstaller.hostToGuestPath(context, workdir)
        if (guestPath == null) {
            return FormatResult(false, null, "Cannot access proot environment")
        }

        val originalContent = file.readText()

        // ── Check if formatter is installed, auto-install if missing ──
        val checkCmd = formatterCheckCommand(language)
        if (checkCmd != null) {
            val checkResult = ProotInstaller.execOnce(context, checkCmd, null, timeoutSeconds = 10)
            if (!checkResult.trim().endsWith("/ktlint") &&
                !checkResult.trim().endsWith("/prettier") &&
                !checkResult.trim().endsWith("/black") &&
                !checkResult.trim().endsWith("/gofmt") &&
                !checkResult.trim().endsWith("/google-java-format") &&
                !checkResult.trim().endsWith("/python3") &&
                !checkResult.trim().endsWith("/xmllint") &&
                !checkResult.trim().endsWith("/shfmt") &&
                checkResult.trim().isNotEmpty() &&
                !checkResult.contains("not found") &&
                !checkResult.contains("No such file")) {
                // Formatter is installed — proceed
            } else {
                // Formatter is missing — auto-install
                val installCmd = formatterInstallCommand(language)
                if (installCmd != null) {
                    val installResult = ProotInstaller.execOnce(context, installCmd, null, timeoutSeconds = 180)
                    // Check if install succeeded
                    val recheck = ProotInstaller.execOnce(context, checkCmd, null, timeoutSeconds = 10)
                    if (recheck.contains("not found") || recheck.contains("No such file") || recheck.trim().isEmpty()) {
                        return FormatResult(false, null,
                            "Formatter install failed for ${language.displayName}. Install output: ${installResult.take(200)}")
                    }
                }
            }
        }

        // Run the formatter
        val output = ProotInstaller.execOnce(context, command, guestPath, timeoutSeconds = 30)

        // Read back the formatted content
        val formattedContent = if (file.exists()) file.readText() else originalContent

        return if (formattedContent != originalContent) {
            FormatResult(true, formattedContent, "Formatted with ${language.displayName} formatter")
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
