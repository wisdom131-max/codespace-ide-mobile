package com.codespace.ide.editor

import android.content.Context
import com.codespace.ide.domain.Language

/**
 * P41-R: Per-language formatter configuration.
 *
 * Stores the user's preferred formatter for each language, allowing selection
 * between multiple available formatters (e.g. Python: black vs autopep8 vs yapf).
 * Falls back to a built-in indentation formatter for languages without external tools.
 *
 * Persisted in SharedPreferences as JSON: { "PYTHON": "autopep8", "KOTLIN": "ktlint", ... }
 */
object FormatterConfig {

    private const val PREFS_NAME = "formatter_prefs"
    private const val KEY_SELECTIONS = "formatter_selections"

    /** Available formatters per language. */
    val availableFormatters: Map<Language, List<FormatterOption>> = mapOf(
        Language.KOTLIN to listOf(
            FormatterOption("ktlint", "ktlint --format '\$FILE' 2>&1", "command -v ktlint"),
            FormatterOption("ktfmt", "ktfmt '\$FILE' 2>&1", "command -v ktfmt"),
        ),
        Language.JAVASCRIPT to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
            FormatterOption("eslint", "eslint --fix '\$FILE' 2>&1", "command -v eslint"),
        ),
        Language.TYPESCRIPT to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
            FormatterOption("eslint", "eslint --fix '\$FILE' 2>&1", "command -v eslint"),
        ),
        Language.PYTHON to listOf(
            FormatterOption("black", "black --quiet '\$FILE' 2>&1", "command -v black"),
            FormatterOption("autopep8", "autopep8 --in-place '\$FILE' 2>&1", "command -v autopep8"),
            FormatterOption("yapf", "yapf --in-place '\$FILE' 2>&1", "command -v yapf"),
            FormatterOption("isort", "isort '\$FILE' 2>&1", "command -v isort"),
        ),
        Language.GO to listOf(
            FormatterOption("gofmt", "gofmt -w '\$FILE' 2>&1", "command -v gofmt"),
            FormatterOption("goimports", "goimports -w '\$FILE' 2>&1", "command -v goimports"),
        ),
        Language.JAVA to listOf(
            FormatterOption("google-java-format", "google-java-format --replace '\$FILE' 2>&1", "command -v google-java-format"),
            FormatterOption("clang-format", "clang-format -i '\$FILE' 2>&1", "command -v clang-format"),
        ),
        Language.RUST to listOf(
            FormatterOption("rustfmt", "rustfmt '\$FILE' 2>&1", "command -v rustfmt"),
        ),
        Language.HTML to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
        ),
        Language.CSS to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
        ),
        Language.JSON to listOf(
            FormatterOption("python3", "python3 -m json.tool '\$FILE' --compact 2>/dev/null | python3 -m json.tool > '\$FILE.tmp' && mv '\$FILE.tmp' '\$FILE' 2>&1", "command -v python3"),
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
        ),
        Language.XML to listOf(
            FormatterOption("xmllint", "xmllint --format '\$FILE' > '\$FILE.tmp' && mv '\$FILE.tmp' '\$FILE' 2>&1", "command -v xmllint"),
        ),
        Language.SHELL to listOf(
            FormatterOption("shfmt", "shfmt -w '\$FILE' 2>&1", "command -v shfmt"),
        ),
        // Languages with no external formatter — use built-in fallback
        Language.C to listOf(
            FormatterOption("clang-format", "clang-format -i '\$FILE' 2>&1", "command -v clang-format"),
            FormatterOption("built-in", null, null),  // fallback
        ),
        Language.CPP to listOf(
            FormatterOption("clang-format", "clang-format -i '\$FILE' 2>&1", "command -v clang-format"),
            FormatterOption("built-in", null, null),  // fallback
        ),
        Language.MARKDOWN to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
            FormatterOption("built-in", null, null),  // fallback
        ),
        Language.YAML to listOf(
            FormatterOption("prettier", "prettier --write '\$FILE' 2>&1", "command -v prettier"),
            FormatterOption("built-in", null, null),  // fallback
        ),
    )

    data class FormatterOption(
        val name: String,
        val commandTemplate: String?,  // null = built-in fallback
        val checkCommand: String?,      // null = no check needed
    )

    /** Get the user's selected formatter for a language, or the default (first option). */
    fun getSelectedFormatter(context: Context, language: Language): FormatterOption {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SELECTIONS, null)
        val selections = if (json != null) {
            try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
        } else {
            org.json.JSONObject()
        }
        val selectedName = selections.optString(language.name, availableFormatters[language]?.firstOrNull()?.name ?: "")
        return availableFormatters[language]?.find { it.name == selectedName }
            ?: availableFormatters[language]?.firstOrNull()
            ?: FormatterOption("built-in", null, null)
    }

    /** Set the user's preferred formatter for a language. */
    fun setSelectedFormatter(context: Context, language: Language, formatterName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SELECTIONS, null)
        val selections = if (json != null) {
            try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
        } else {
            org.json.JSONObject()
        }
        selections.put(language.name, formatterName)
        prefs.edit().putString(KEY_SELECTIONS, selections.toString()).apply()
    }

    /** Check if a language has a built-in fallback formatter (no external tool needed). */
    fun hasFallbackFormatter(language: Language): Boolean {
        return language in listOf(Language.C, Language.CPP, Language.MARKDOWN, Language.YAML,
            Language.SQL, Language.DART, Language.SWIFT, Language.PHP, Language.RUBY, Language.TOML,
            Language.PLAINTEXT)
    }

    /**
     * Built-in fallback formatter — normalizes indentation (tabs→spaces or vice versa)
     * and trims trailing whitespace. No external tool needed.
     */
    fun fallbackFormat(content: String, indentSize: Int = 4): String {
        val lines = content.split("\n")
        val formatted = lines.map { line ->
            // Trim trailing whitespace
            val trimmed = line.trimEnd()
            // Convert tabs to spaces (or keep tabs if line starts with tabs consistently)
            if (trimmed.contains("\t")) {
                trimmed.replace("\t", " ".repeat(indentSize))
            } else {
                trimmed
            }
        }
        // Ensure file ends with single newline
        val joined = formatted.joinToString("\n")
        return if (joined.isNotEmpty() && !joined.endsWith("\n")) "$joined\n" else joined
    }
}
