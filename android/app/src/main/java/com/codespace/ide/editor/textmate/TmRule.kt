package com.codespace.ide.editor.textmate

/**
 * TextMate rule model — represents the parsed rules from a .tmLanguage.json grammar.
 *
 * Written from scratch based on the TextMate grammar specification and the
 * architecture study of sora-editor's tm4e implementation (LGPL 2.1 — study only).
 *
 * TextMate grammars have these rule types:
 * - MatchRule: a regex pattern that, when matched, applies a scope name and optional captures
 * - BeginEndRule: a begin/end pair that creates a scope spanning multiple tokens/lines
 * - BeginWhileRule: a begin/while pair (like begin/end but uses while condition instead of end pattern)
 * - IncludeRule: references another rule by name (e.g., "$self", "#repository-entry", "scope.name")
 * - CaptureRule: applied to capture groups within a match
 */

/** A single rule in the grammar rule tree. */
sealed class TmRule {
    /** Scope name applied when this rule matches (e.g., "keyword.control.if") */
    abstract var name: String?
    /** Content scope name (applied to content between begin/end) */
    abstract var contentName: String?
    /** Unique ID assigned during grammar compilation */
    abstract var id: Int

    /** A simple match rule — regex pattern that applies a scope when matched. */
    data class MatchRule(
        override var id: Int = -1,
        override var name: String? = null,
        override var contentName: String? = null,
        val match: String,
        val captures: MutableList<CaptureRule> = mutableListOf(),
    ) : TmRule()

    /** A begin/end rule — opens a scope with a begin pattern, closes with an end pattern. */
    data class BeginEndRule(
        override var id: Int = -1,
        override var name: String? = null,
        override var contentName: String? = null,
        val begin: String,
        val end: String?,
        val beginCaptures: MutableList<CaptureRule> = mutableListOf(),
        val endCaptures: MutableList<CaptureRule> = mutableListOf(),
        val applyEndPatternLast: Boolean = false,
        val patterns: MutableList<TmRule> = mutableListOf(),
    ) : TmRule()

    /** A begin/while rule — like begin/end but the while pattern keeps the scope open. */
    data class BeginWhileRule(
        override var id: Int = -1,
        override var name: String? = null,
        override var contentName: String? = null,
        val begin: String,
        val whilePattern: String,
        val beginCaptures: MutableList<CaptureRule> = mutableListOf(),
        val whileCaptures: MutableList<CaptureRule> = mutableListOf(),
        val patterns: MutableList<TmRule> = mutableListOf(),
    ) : TmRule()

    /** An include reference — points to another rule or repository entry. */
    data class IncludeRule(
        override var id: Int = -1,
        override var name: String? = null,
        override var contentName: String? = null,
        val include: String,
    ) : TmRule()

    /** A capture rule — applied to a specific capture group index. */
    data class CaptureRule(
        override var id: Int = -1,
        override var name: String? = null,
        override var contentName: String? = null,
        /** Which capture group (0-based index) this rule applies to. */
        val groupIndex: Int = 0,
        /** If non-null, retokenize the captured text with these patterns. */
        val patterns: MutableList<TmRule> = mutableListOf(),
    ) : TmRule()
}

/** Repository — named rules that can be included by other rules via "#name". */
data class TmRepository(
    val entries: MutableMap<String, TmRule> = mutableMapOf(),
)

/** A complete parsed grammar. */
data class TmGrammar(
    val scopeName: String,
    val name: String?,
    val fileTypes: List<String>,
    val firstLineMatch: String?,
    val repository: TmRepository,
    val patterns: MutableList<TmRule>,
    /** All rules indexed by ID for fast lookup during tokenization. */
    val rulesById: MutableMap<Int, TmRule> = mutableMapOf(),
    /** Next available rule ID. */
    var nextRuleId: Int = 2,
)
