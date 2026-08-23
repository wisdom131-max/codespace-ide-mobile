package com.codespace.ide.editor.textmate

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.io.Reader

/**
 * TextMate theme — maps scope names to colors.
 *
 * Written from scratch. Architecture reference: org.eclipse.tm4e.core.internal.theme.Theme
 * (EPL 2.0). A theme is loaded from a .tmTheme.json file:
 *
 * {
 *   "name": "Dark+ (default dark)",
 *   "settings": [
 *     { "settings": { "background": "#1E1E1E", "foreground": "#D4D4D4" } },
 *     { "scope": "keyword", "settings": { "foreground": "#569CD6" } },
 *     { "scope": "string", "settings": { "foreground": "#CE9178" } },
 *     ...
 *   ]
 * }
 *
 * Scope matching uses the TextMate scope hierarchy:
 * "keyword.control.if.js" matches "keyword", "keyword.control", "keyword.control.if"
 * The most specific match wins.
 */
data class TmTheme(
    val name: String,
    val background: String?,
    val foreground: String?,
    val rules: List<ThemeRule>,
) {
    /** A single scope-to-color rule. */
    data class ThemeRule(
        val scopeSelectors: List<String>,
        val foreground: String?,
        val background: String?,
        val fontStyle: String?, // "bold", "italic", "underline", or combinations
    )

    companion object {
        /**
         * Parse a .tmTheme.json file from a Reader.
         */
        fun load(reader: Reader): TmTheme {
            val root = JsonParser.parseReader(reader).asJsonObject
            return parseTheme(root)
        }

        /**
         * Parse a theme from a JSON string.
         */
        fun loadFromString(json: String): TmTheme {
            val root = JsonParser.parseString(json).asJsonObject
            return parseTheme(root)
        }

        private fun parseTheme(root: JsonObject): TmTheme {
            val name = root.get("name")?.asString ?: "default"
            var background: String? = null
            var foreground: String? = null
            val rules = mutableListOf<ThemeRule>()

            val settingsArray = root.get("settings")?.asJsonArray
            if (settingsArray != null) {
                for (settingElem in settingsArray) {
                    if (!settingElem.isJsonObject) continue
                    val settingObj = settingElem.asJsonObject
                    val scopeStr = settingObj.get("scope")?.asString
                    val settingsObj = settingObj.get("settings")?.asJsonObject

                    if (scopeStr == null) {
                        // Global settings (first entry)
                        if (settingsObj != null) {
                            background = settingsObj.get("background")?.asString
                            foreground = settingsObj.get("foreground")?.asString
                        }
                    } else {
                        // Scope-specific rule
                        if (settingsObj != null) {
                            val fg = settingsObj.get("foreground")?.asString
                            val bg = settingsObj.get("background")?.asString
                            val fs = settingsObj.get("fontStyle")?.asString
                            // Scope can be comma-separated (multiple selectors)
                            val scopes = scopeStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (scopes.isNotEmpty()) {
                                rules.add(ThemeRule(scopes, fg, bg, fs))
                            }
                        }
                    }
                }
            }

            return TmTheme(name, background, foreground, rules)
        }
    }
}

/**
 * Scope matcher — finds the best color for a given scope path.
 *
 * TextMate scope matching is hierarchical:
 * - "keyword.control.if" matches selector "keyword"
 * - "keyword.control.if" matches selector "keyword.control"
 * - "keyword.control.if" matches selector "keyword.control.if"
 * - The most specific (longest) match wins
 *
 * Scope selectors can also have parent scope requirements:
 * - "keyword.control meta.function" matches "keyword.control" within "meta.function"
 */
object TmScopeMatcher {

    /**
     * Find the best theme rule for a given token's scope list.
     * Returns the rule with the highest specificity that matches.
     */
    fun match(scopes: List<String>, theme: TmTheme): TmTheme.ThemeRule? {
        var bestRule: TmTheme.ThemeRule? = null
        var bestScore = 0

        for (rule in theme.rules) {
            for (selector in rule.scopeSelectors) {
                val score = scoreMatch(selector, scopes)
                if (score > bestScore) {
                    bestScore = score
                    bestRule = rule
                }
            }
        }

        return bestRule
    }

    /**
     * Score how well a scope selector matches the token's scope path.
     * Returns 0 if no match, higher numbers for more specific matches.
     *
     * Example:
     *   token scopes: ["source.kotlin", "keyword.control.if.kotlin"]
     *   selector: "keyword" → matches "keyword.control.if.kotlin" at depth 1 → score = 1
     *   selector: "keyword.control" → matches at depth 2 → score = 2
     *   selector: "keyword.control.if" → matches at depth 3 → score = 3
     */
    private fun scoreMatch(selector: String, scopes: List<String>): Int {
        // Handle parent scope requirements: "selector1 parent1 parent2"
        // means selector1 must match AND parent1/parent2 must be in the scope path
        val parts = selector.split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return 0

        // The last part is the actual scope selector; preceding parts are parent requirements
        val scopeSelector = parts.last()
        val parentSelectors = parts.dropLast(1)

        var bestScore = 0

        for (scope in scopes) {
            val scopeParts = scope.split(".")
            val selectorParts = scopeSelector.split(".")

            // Check if selectorParts is a prefix of scopeParts
            if (selectorParts.size > scopeParts.size) continue
            var matches = true
            for (i in selectorParts.indices) {
                if (scopeParts[i] != selectorParts[i]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                val score = selectorParts.size
                // Check parent selectors — they must match other scopes in the path
                if (parentSelectors.isEmpty()) {
                    if (score > bestScore) bestScore = score
                } else {
                    // TODO: implement parent scope matching
                    // For now, just use the selector score
                    if (score > bestScore) bestScore = score
                }
            }
        }

        return bestScore
    }

    /**
     * Parse a color string ("#RRGGBB" or "#RRGGBBAA") to an ARGB int.
     * Returns null if the string is invalid.
     */
    fun parseColor(colorStr: String?): Int? {
        if (colorStr == null) return null
        val hex = colorStr.removePrefix("#")
        return try {
            when (hex.length) {
                6 -> (0xFF shl 24) or hex.toLong(16).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
