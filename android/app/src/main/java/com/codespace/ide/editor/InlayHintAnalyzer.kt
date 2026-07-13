package com.codespace.ide.editor

import com.codespace.ide.domain.Language

/**
 * P2-11 Inlay Hints — lightweight static analysis.
 *
 * Produces small grey labels that appear at the end of a line to hint:
 *   • inferred types  (val x = 42  →  ": Int")
 *   • function return types  (fun foo() =  →  ": <type>")
 *   • parameter names for common calls  (setText(true)  →  "enabled:")
 *
 * Deliberately kept simple (regex-based) — no AST.  Accuracy ~80 % for
 * idiomatic Kotlin / JS; good enough for an IDE hint layer on mobile.
 */
data class InlayHint(
    /** 0-based line index in the raw (un-folded) text. */
    val line: Int,
    /** Short label, e.g. ": Int" or "name:" */
    val label: String,
    /** Hint category — controls colour. */
    val kind: Kind,
) {
    enum class Kind { TYPE, PARAM, RETURN }
}

object InlayHintAnalyzer {

    // ── Kotlin type-inference hints ──────────────────────────────────────

    /** val/var x = <literal>  → append ": <Type>" */
    private val VAL_INT    = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*-?\d+[lL]?\s*$""")
    private val VAL_LONG   = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*-?\d+[lL]\s*$""")
    private val VAL_FLOAT  = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*-?[\d.]+[fF]\s*$""")
    private val VAL_DOUBLE = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*-?[\d.]+\s*$""")
    private val VAL_BOOL   = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*(?:true|false)\s*$""")
    private val VAL_STRING = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*".*"\s*$""")
    private val VAL_CHAR   = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*'.'s*$""")
    private val VAL_LIST   = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*(?:mutableListOf|listOf|emptyList)\s*[(<]""")
    private val VAL_MAP    = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*(?:mutableMapOf|mapOf|emptyMap)\s*[(<]""")
    private val VAL_SET    = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*(?:mutableSetOf|setOf|emptySet)\s*[(<]""")
    private val VAL_PAIR   = Regex("""^\s*(?:val|var)\s+\w+\s*=\s*\w+\s*to\s*\w+""")

    // fun foo() = <expr> — single-expression function return hint
    private val FUN_EXPR   = Regex("""^\s*(?:private\s+|internal\s+|protected\s+|public\s+)?(?:inline\s+|suspend\s+)?fun\s+\w+\s*\([^)]*\)\s*=\s*(.+)""")

    // ── JS/TS type hints ─────────────────────────────────────────────────
    private val JS_CONST_NUM = Regex("""^\s*(?:const|let|var)\s+\w+\s*=\s*-?[\d.]+\s*;?\s*$""")
    private val JS_CONST_BOOL = Regex("""^\s*(?:const|let|var)\s+\w+\s*=\s*(?:true|false)\s*;?\s*$""")
    private val JS_CONST_STR = Regex("""^\s*(?:const|let|var)\s+\w+\s*=\s*["'`].*["'`]\s*;?\s*$""")

    // ── Kotlin param hints ───────────────────────────────────────────────
    // Detect calls like:  setText(true), setEnabled(false), setVisible(true)
    private val SINGLE_BOOL_CALL = Regex("""(\w+)\(\s*(true|false)\s*\)""")
    private val SINGLE_NUM_CALL  = Regex("""(\w+)\(\s*(-?\d+(?:\.\d+)?[fFlL]?)\s*\)""")

    private fun boolParamName(fnName: String): String? = when {
        fnName.startsWith("set") && fnName.length > 3 ->
            fnName[3].lowercaseChar() + fnName.substring(4)
        fnName == "require" || fnName == "check" -> "value"
        else -> null
    }

    // ── Main entry ────────────────────────────────────────────────────────

    fun analyze(text: String, language: Language): List<InlayHint> {
        val hints = mutableListOf<InlayHint>()
        val lines = text.lines()
        lines.forEachIndexed { idx, line ->
            when (language) {
                Language.KOTLIN -> hints += kotlinHints(idx, line)
                Language.JAVASCRIPT, Language.TYPESCRIPT -> hints += jsHints(idx, line)
                else -> { /* no hints for plain text / unknown */ }
            }
        }
        return hints
    }

    private fun kotlinHints(idx: Int, line: String): List<InlayHint> {
        val out = mutableListOf<InlayHint>()
        // Skip lines that already have an explicit type annotation
        val hasExplicitType = Regex(""":\s*\w+""").containsMatchIn(line)

        if (!hasExplicitType) {
            when {
                VAL_BOOL.matches(line)   -> out += InlayHint(idx, ": Boolean", InlayHint.Kind.TYPE)
                VAL_FLOAT.matches(line)  -> out += InlayHint(idx, ": Float",   InlayHint.Kind.TYPE)
                VAL_DOUBLE.matches(line) -> out += InlayHint(idx, ": Double",  InlayHint.Kind.TYPE)
                VAL_LONG.matches(line)   -> out += InlayHint(idx, ": Long",    InlayHint.Kind.TYPE)
                VAL_INT.matches(line)    -> out += InlayHint(idx, ": Int",     InlayHint.Kind.TYPE)
                VAL_STRING.matches(line) -> out += InlayHint(idx, ": String",  InlayHint.Kind.TYPE)
                VAL_LIST.matches(line)   -> out += InlayHint(idx, ": List<…>", InlayHint.Kind.TYPE)
                VAL_MAP.matches(line)    -> out += InlayHint(idx, ": Map<…>",  InlayHint.Kind.TYPE)
                VAL_SET.matches(line)    -> out += InlayHint(idx, ": Set<…>",  InlayHint.Kind.TYPE)
                VAL_PAIR.matches(line)   -> out += InlayHint(idx, ": Pair<…>", InlayHint.Kind.TYPE)
            }
            // single-expression fun return hint
            FUN_EXPR.find(line)?.let { m ->
                val body = m.groupValues[1].trim()
                val retHint = when {
                    body.startsWith("\"") || body.startsWith("\"\"\"") -> ": String"
                    body == "true" || body == "false" -> ": Boolean"
                    body.matches(Regex("""-?\d+[lL]""")) -> ": Long"
                    body.matches(Regex("""-?[\d.]+[fF]""")) -> ": Float"
                    body.matches(Regex("""-?\d+""")) -> ": Int"
                    body.startsWith("listOf") || body.startsWith("mutableListOf") -> ": List<…>"
                    body.startsWith("mapOf") || body.startsWith("mutableMapOf") -> ": Map<…>"
                    else -> null
                }
                retHint?.let { out += InlayHint(idx, it, InlayHint.Kind.RETURN) }
            }
        }

        // Param hints for single-bool / single-num calls
        SINGLE_BOOL_CALL.findAll(line).forEach { m ->
            val fn = m.groupValues[1]
            val paramName = boolParamName(fn)
            if (paramName != null) {
                out += InlayHint(idx, "$paramName:", InlayHint.Kind.PARAM)
            }
        }

        return out
    }

    private fun jsHints(idx: Int, line: String): List<InlayHint> {
        val out = mutableListOf<InlayHint>()
        val hasTypeAnnotation = line.contains(":")
        if (!hasTypeAnnotation) {
            when {
                JS_CONST_BOOL.matches(line) -> out += InlayHint(idx, ": boolean", InlayHint.Kind.TYPE)
                JS_CONST_NUM.matches(line)  -> out += InlayHint(idx, ": number",  InlayHint.Kind.TYPE)
                JS_CONST_STR.matches(line)  -> out += InlayHint(idx, ": string",  InlayHint.Kind.TYPE)
            }
        }
        return out
    }
}
