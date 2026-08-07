package com.codespace.ide.editor

import android.content.Context

/**
 * P41-R: Per-language formatter picker + fallback formatters for languages without LSP.
 *
 * Stores user's preferred formatter per language in SharedPreferences.
 * Falls back to a sensible default if no preference is set.
 */
object FormatterConfig {

    private const val PREFS_NAME = "formatter_config"

    /**
     * Available formatters per language.
     * "lsp" = use the LSP server's formatting capability (default for most).
     * Others are CLI tools run via proot.
     */
    val formatterOptions: Map<String, List<String>> = mapOf(
        "kotlin" to listOf("lsp", "ktlint"),
        "java" to listOf("lsp", "google-java-format"),
        "python" to listOf("lsp", "black", "autopep8", "yapf"),
        "javascript" to listOf("lsp", "prettier", "eslint --fix"),
        "typescript" to listOf("lsp", "prettier", "eslint --fix"),
        "go" to listOf("lsp", "gofmt", "goimports"),
        "rust" to listOf("lsp", "rustfmt"),
        "c" to listOf("lsp", "clang-format"),
        "cpp" to listOf("lsp", "clang-format"),
        "html" to listOf("lsp", "prettier"),
        "css" to listOf("lsp", "prettier", "stylelint --fix"),
        "scss" to listOf("lsp", "prettier"),
        "json" to listOf("lsp", "prettier"),
        "yaml" to listOf("lsp", "prettier", "yamlfmt"),
        "markdown" to listOf("lsp", "prettier", "mdformat"),
        "sh" to listOf("shfmt"),
        "bash" to listOf("shfmt"),
        "ruby" to listOf("lsp", "rubocop"),
        "php" to listOf("lsp", "php-cs-fixer"),
        "dart" to listOf("lsp", "dart format"),
        "swift" to listOf("lsp", "swiftformat"),
    )

    /**
     * Default formatter per language (first option from formatterOptions, or "lsp").
     */
    fun getDefaultFormatter(language: String): String {
        return formatterOptions[language]?.firstOrNull() ?: "lsp"
    }

    /**
     * Get the user's preferred formatter for a language, or the default.
     */
    fun getFormatter(context: Context, language: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("formatter_$language", null) ?: getDefaultFormatter(language)
    }

    /**
     * Set the user's preferred formatter for a language.
     */
    fun setFormatter(context: Context, language: String, formatter: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("formatter_$language", formatter)
            .apply()
    }

    /**
     * Get the CLI command for a formatter tool.
     * Returns null for "lsp" (handled by LSP formatting request).
     * For other tools, returns the shell command to run for formatting a file.
     */
    fun getFormatterCommand(formatter: String, filePath: String): String? {
        return when (formatter) {
            "lsp" -> null // LSP handles formatting
            "prettier" -> "npx prettier --write \"$filePath\""
            "eslint --fix" -> "npx eslint --fix \"$filePath\""
            "black" -> "python3 -m black \"$filePath\""
            "autopep8" -> "python3 -m autopep8 --in-place \"$filePath\""
            "yapf" -> "python3 -m yapf --in-place \"$filePath\""
            "gofmt" -> "gofmt -w \"$filePath\""
            "goimports" -> "goimports -w \"$filePath\""
            "rustfmt" -> "rustfmt \"$filePath\""
            "clang-format" -> "clang-format -i \"$filePath\""
            "ktlint" -> "ktlint --format \"$filePath\""
            "google-java-format" -> "google-java-format --in-place \"$filePath\""
            "shfmt" -> "shfmt -w \"$filePath\""
            "rubocop" -> "rubocop --auto-correct \"$filePath\""
            "php-cs-fixer" -> "php-cs-fixer fix \"$filePath\""
            "dart format" -> "dart format \"$filePath\""
            "swiftformat" -> "swiftformat \"$filePath\""
            "mdformat" -> "mdformat \"$filePath\""
            "yamlfmt" -> "yamlfmt \"$filePath\""
            "stylelint --fix" -> "npx stylelint --fix \"$filePath\""
            else -> null
        }
    }

    /**
     * Check if a formatter is a fallback (non-LSP) formatter.
     */
    fun isFallbackFormatter(formatter: String): Boolean = formatter != "lsp"

    /**
     * Get all configured languages (those with saved preferences).
     */
    fun getConfiguredLanguages(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("formatter_") && value is String) {
                result[key.removePrefix("formatter_")] = value
            }
        }
        return result
    }
}
