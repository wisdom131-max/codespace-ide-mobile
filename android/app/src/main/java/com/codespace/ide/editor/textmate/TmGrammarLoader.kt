package com.codespace.ide.editor.textmate

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import java.io.Reader

/**
 * Loads and parses .tmLanguage.json files into TmGrammar objects.
 *
 * Written from scratch based on the TextMate grammar JSON spec.
 * Architecture study reference: org.eclipse.tm4e.core.internal.grammar.raw.RawGrammarReader (EPL 2.0)
 *
 * The .tmLanguage.json format:
 * {
 *   "scopeName": "source.kotlin",
 *   "name": "Kotlin",
 *   "fileTypes": ["kt", "kts"],
 *   "firstLineMatch": "^#!.*\\bkotlinc\\b",
 *   "repository": { ... named rules ... },
 *   "patterns": [ ... top-level rules ... ]
 * }
 *
 * Each rule can be:
 * { "match": "regex", "name": "scope.name", "captures": { "0": {"name": "scope"}, ... } }
 * { "begin": "regex", "end": "regex", "name": "scope", "contentName": "scope",
 *   "beginCaptures": {...}, "endCaptures": {...}, "patterns": [...],
 *   "applyEndPatternLast": true }
 * { "include": "#repositoryEntry" | "$self" | "source.otherLang" }
 * { "begin": "regex", "while": "regex", "name": "scope", ... }
 */
object TmGrammarLoader {

    /**
     * Parse a .tmLanguage.json file from a Reader.
     */
    fun load(reader: Reader): TmGrammar {
        val root = JsonParser.parseReader(reader).asJsonObject
        return parseGrammar(root)
    }

    /**
     * Parse a grammar from a JSON string.
     */
    fun loadFromString(json: String): TmGrammar {
        val root = JsonParser.parseString(json).asJsonObject
        return parseGrammar(root)
    }

    private fun parseGrammar(root: JsonObject): TmGrammar {
        val scopeName = root.get("scopeName")?.asString ?: "source.unknown"
        val name = root.get("name")?.asString
        val fileTypes = root.get("fileTypes")?.asJsonArray?.map { it.asString } ?: emptyList()
        val firstLineMatch = root.get("firstLineMatch")?.asString

        val grammar = TmGrammar(
            scopeName = scopeName,
            name = name,
            fileTypes = fileTypes,
            firstLineMatch = firstLineMatch,
            repository = TmRepository(),
            patterns = mutableListOf(),
        )

        // Parse repository first (rules reference it)
        root.get("repository")?.let { repoElem ->
            if (repoElem.isJsonObject) {
                for ((key, ruleElem) in repoElem.asJsonObject.entrySet()) {
                    if (ruleElem.isJsonObject) {
                        val rule = parseRule(ruleElem.asJsonObject, grammar)
                        grammar.repository.entries[key] = rule
                    }
                }
            }
        }

        // Parse top-level patterns
        root.get("patterns")?.let { patternsElem ->
            if (patternsElem.isJsonArray) {
                for (patternElem in patternsElem.asJsonArray) {
                    if (patternElem.isJsonObject) {
                        grammar.patterns.add(parseRule(patternElem.asJsonObject, grammar))
                    }
                }
            }
        }

        // Assign IDs to all rules
        assignIds(grammar)

        return grammar
    }

    private fun parseRule(obj: JsonObject, grammar: TmGrammar): TmRule {
        val name = obj.get("name")?.asString
        val contentName = obj.get("contentName")?.asString

        // Include rule
        obj.get("include")?.let { includeElem ->
            val include = includeElem.asString
            return TmRule.IncludeRule(
                name = name,
                contentName = contentName,
                include = include,
            )
        }

        // Begin/End or Begin/While rule
        obj.get("begin")?.let { beginElem ->
            val begin = beginElem.asString
            val end = obj.get("end")?.asString
            val whilePattern = obj.get("while")?.asString
            val applyEndPatternLast = obj.get("applyEndPatternLast")?.asBoolean ?: false

            val beginCaptures = parseCaptures(obj, "beginCaptures", grammar)
            val patterns = mutableListOf<TmRule>()
            obj.get("patterns")?.let { patternsElem ->
                if (patternsElem.isJsonArray) {
                    for (patternElem in patternsElem.asJsonArray) {
                        if (patternElem.isJsonObject) {
                            patterns.add(parseRule(patternElem.asJsonObject, grammar))
                        }
                    }
                }
            }

            if (whilePattern != null) {
                val whileCaptures = parseCaptures(obj, "whileCaptures", grammar)
                return TmRule.BeginWhileRule(
                    name = name,
                    contentName = contentName,
                    begin = begin,
                    whilePattern = whilePattern,
                    beginCaptures = beginCaptures,
                    whileCaptures = whileCaptures,
                    patterns = patterns,
                )
            } else {
                val endCaptures = parseCaptures(obj, "endCaptures", grammar)
                return TmRule.BeginEndRule(
                    name = name,
                    contentName = contentName,
                    begin = begin,
                    end = end,
                    beginCaptures = beginCaptures,
                    endCaptures = endCaptures,
                    applyEndPatternLast = applyEndPatternLast,
                    patterns = patterns,
                )
            }
        }

        // Match rule
        obj.get("match")?.let { matchElem ->
            val match = matchElem.asString
            val captures = parseCaptures(obj, "captures", grammar)
            return TmRule.MatchRule(
                name = name,
                contentName = contentName,
                match = match,
                captures = captures,
            )
        }

        // Fallback: treat as a container with patterns only (no name/match)
        val patterns = mutableListOf<TmRule>()
        obj.get("patterns")?.let { patternsElem ->
            if (patternsElem.isJsonArray) {
                for (patternElem in patternsElem.asJsonArray) {
                    if (patternElem.isJsonObject) {
                        patterns.add(parseRule(patternElem.asJsonObject, grammar))
                    }
                }
            }
        }
        // Return a match rule that never matches but carries sub-patterns
        // (This is a degenerate case — grammars usually don't have pattern-only top-level rules)
        return TmRule.MatchRule(
            name = name,
            match = "\uFFFF", // never matches
            captures = mutableListOf(),
        )
    }

    /**
     * Parse captures from a "captures" or "beginCaptures"/"endCaptures" object.
     * Keys are 0-based capture group indices as strings.
     */
    private fun parseCaptures(obj: JsonObject, key: String, grammar: TmGrammar): MutableList<TmRule.CaptureRule> {
        val result = mutableListOf<TmRule.CaptureRule>()
        obj.get(key)?.let { capturesElem ->
            if (!capturesElem.isJsonObject) return result
            val capturesObj = capturesElem.asJsonObject
            for ((idxStr, captureElem) in capturesObj.entrySet()) {
                if (!captureElem.isJsonObject) continue
                val idx = idxStr.toIntOrNull() ?: continue
                val captureObj = captureElem.asJsonObject
                val capName = captureObj.get("name")?.asString
                val capContentName = captureObj.get("contentName")?.asString
                val capPatterns = mutableListOf<TmRule>()
                captureObj.get("patterns")?.let { patternsElem ->
                    if (patternsElem.isJsonArray) {
                        for (patternElem in patternsElem.asJsonArray) {
                            if (patternElem.isJsonObject) {
                                capPatterns.add(parseRule(patternElem.asJsonObject, grammar))
                            }
                        }
                    }
                }
                result.add(TmRule.CaptureRule(
                    groupIndex = idx,
                    name = capName,
                    contentName = capContentName,
                    patterns = capPatterns,
                ))
            }
        }
        // Sort by group index for deterministic ordering
        result.sortBy { it.groupIndex }
        return result
    }

    /**
     * Assign unique IDs to all rules in the grammar for fast lookup during tokenization.
     * ID 0 = end rule sentinel, ID 1 = while rule sentinel.
     */
    private fun assignIds(grammar: TmGrammar) {
        // Assign IDs to repository rules
        for ((_, rule) in grammar.repository.entries) {
            assignId(rule, grammar)
        }
        // Assign IDs to top-level patterns
        for (rule in grammar.patterns) {
            assignId(rule, grammar)
        }
    }

    private fun assignId(rule: TmRule, grammar: TmGrammar) {
        if (rule.id != -1) return // already assigned
        rule.id = grammar.nextRuleId++
        grammar.rulesById[rule.id] = rule

        when (rule) {
            is TmRule.MatchRule -> {
                for (cap in rule.captures) assignId(cap, grammar)
            }
            is TmRule.BeginEndRule -> {
                for (cap in rule.beginCaptures) assignId(cap, grammar)
                for (cap in rule.endCaptures) assignId(cap, grammar)
                for (sub in rule.patterns) assignId(sub, grammar)
            }
            is TmRule.BeginWhileRule -> {
                for (cap in rule.beginCaptures) assignId(cap, grammar)
                for (cap in rule.whileCaptures) assignId(cap, grammar)
                for (sub in rule.patterns) assignId(sub, grammar)
            }
            is TmRule.CaptureRule -> {
                for (sub in rule.patterns) assignId(sub, grammar)
            }
            is TmRule.IncludeRule -> { /* no children */ }
        }
    }

    /**
     * Resolve an include reference to the actual rule(s).
     * - "#name" → repository entry in the current grammar
     * - "$self" → the grammar's top-level patterns
     * - "$base" → same as $self (the root grammar)
     * - "scope.name" → an external grammar (not yet supported, returns empty)
     */
    fun resolveInclude(include: String, grammar: TmGrammar): List<TmRule> {
        return when {
            include == "\$self" || include == "\$base" -> grammar.patterns
            include.startsWith("#") -> {
                val key = include.substring(1)
                grammar.repository.entries[key]?.let { listOf(it) } ?: emptyList()
            }
            include.startsWith("\$") -> {
                // Other $ references (e.g., $text) — not supported, return empty
                emptyList()
            }
            else -> {
                // External scope reference (e.g., "source.js") — not supported yet
                // Would need a grammar registry to look up the referenced grammar
                emptyList()
            }
        }
    }
}
