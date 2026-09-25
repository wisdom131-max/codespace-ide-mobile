package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import org.json.JSONArray
import org.json.JSONObject

/**
 * F1 (F-TRACK TG05 v2): Test Lens Detector — annotation/framework-driven detection.
 *
 * Scans file content for real test declarations and generates synthetic CodeLens
 * entries ("Run Test" / "Debug Test") plus VS Code-shaped TestId path strings that
 * F3/F4 consume as stable identities.
 *
 * Detection rules (F-TRACK-PLAN F1):
 * - Kotlin/Java: lens ONLY on @Test, @ParameterizedTest, @TestFactory, @RepeatedTest
 *   annotated functions and @Nested classes (children inherit). The old "class name
 *   contains Test/Spec, therefore any fun with test in its name" heuristic is
 *   DELETED (the fun testing() false-positive). Backtick test names recognized.
 * - Python: def test_* / class Test* (prefix-driven, unchanged from v1).
 * - JS/TS: test/it (+ .skip/.only variants), test.each/it.each, describe.
 * - Dart: test/testWidgets/group.
 *
 * TestId shape (simplified VS Code testId.ts): slash-joined chain
 * "<filePath>/<container...>/<name>", e.g. "src/Test.kt/MyClass/my test".
 * Line numbers are carried in lens data but are NOT part of identity (they shift
 * on edit); name-chain collisions (overloads) are a documented F3 concern.
 */
object TestLensDetector {

    private const val CMD_RUN = "codespace.runTest"
    private const val CMD_DEBUG = "codespace.debugTest"
    private const val TITLE_RUN_TEST = "\u25b6 Run Test"
    private const val TITLE_RUN_TESTS = "\u25b6 Run Tests"
    private const val TITLE_DEBUG = "Debug Test"

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /** Scans content and returns synthetic CodeLens entries for real test targets. */
    fun detectTestLenses(content: String, language: Language, filePath: String): JSONArray {
        val lines = content.lines()
        return when (language) {
            Language.KOTLIN, Language.JAVA -> detectJvmTests(lines, filePath)
            Language.PYTHON -> detectPythonTests(lines, filePath)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> detectJsTests(lines, filePath)
            Language.DART -> detectDartTests(lines, filePath)
            else -> JSONArray()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JVM (Kotlin/Java)
    // ─────────────────────────────────────────────────────────────────────────────

    private val JVM_TEST_ANNOTATIONS = setOf(
        "@Test", "@ParameterizedTest", "@TestFactory", "@RepeatedTest",
    )
    private val CLASS_DECL = Regex(
        """(?:[\w<>?,.\s]+\s)?(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )

    private fun detectJvmTests(lines: List<String>, filePath: String): JSONArray {
        val lenses = JSONArray()
        val scope = BraceScope()
        // Annotations seen since the last declaration / non-annotation line.
        var pending: MutableSet<String> = mutableSetOf()

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            scope.advance(line)

            // Function declarations FIRST so "@Test fun x()" on one line is not
            // swallowed by the annotation branch below.
            val funName = jvmFunName(trimmed)
            if (funName != null) {
                val annotations = consume(pending)
                // Also catch "@Test fun x()" on one line.
                val sameLine = JVM_TEST_ANNOTATIONS.any { trimmed.startsWith(it) }
                val isTest = annotations.any { it in JVM_TEST_ANNOTATIONS } || sameLine
                if (isTest) {
                    addLenses(lenses, idFor(filePath, scope.chain(), funName), lineIndex, suite = false)
                }
                return@forEachIndexed
            }

            val classMatch = CLASS_DECL.find(trimmed)
            if (classMatch != null) {
                val name = classMatch.groupValues[1]
                val annotations = consume(pending)
                val isNested = annotations.contains("@Nested")
                if (isNested) {
                    // @Nested class: its @Test children run as part of the parent
                    // suite — a runnable suite node, not a test function. The id
                    // chain is captured BEFORE the push so the class is not
                    // duplicated into its own path.
                    val chainBefore = scope.chain()
                    addLenses(lenses, idFor(filePath, chainBefore, name), lineIndex, suite = true)
                }
                scope.push(name)
                return@forEachIndexed
            }

            if (trimmed.startsWith("@")) {
                // Accumulate annotation lines above a declaration (e.g. @Test then
                // @DisplayName then the fun).
                val ann = trimmed.substringBefore(' ')
                    .substringBefore('(').trim()
                pending.add(ann)
                return@forEachIndexed
            }

            // Any other code line ends the pending annotation block.
            if (trimmed.isNotEmpty()) pending = mutableSetOf()
        }
        return lenses
    }

    /** Extracts the function name from a Kotlin/Java declaration line, or null. */
    private fun jvmFunName(trimmed: String): String? {
        // Kotlin: fun `backtick name`() — recognized exactly (TG05 backtick rule).
        if (trimmed.startsWith("fun ")) {
            val rest = trimmed.removePrefix("fun ").trim()
            if (rest.startsWith("`")) {
                val end = rest.indexOf('`', 1)
                if (end > 1) return rest.substring(1, end)
            }
            val name = rest.substringBefore('(').trim()
            if (name.isNotEmpty() && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return name
            return null
        }
        // Java: modifiers + return type + name(...) — e.g. "public void testFoo() {"
        if (!trimmed.startsWith("private fun") && trimmed.contains("(")) {
            val head = trimmed.substringBefore('(').trim()
            val name = head.substringAfterLast(' ').trim()
            if (name.isNotEmpty() && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
                (head.contains(" ") && !head.startsWith("fun"))
            ) {
                return name
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Python (prefix-driven — unchanged detection from v1, plus TestIds)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun detectPythonTests(lines: List<String>, filePath: String): JSONArray {
        val lenses = JSONArray()
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("def test_") || trimmed.startsWith("async def test_")) {
                val name = trimmed.substringAfter("def test_").substringBefore('(').trim()
                addLenses(lenses, idFor(filePath, "", name.ifEmpty { "test" }), lineIndex, suite = false)
            }
            if (trimmed.startsWith("class Test") && trimmed.contains(":")) {
                val name = trimmed.substringAfter("class ").substringBefore('(')
                    .substringBefore(':').trim()
                addLenses(lenses, idFor(filePath, "", name.ifEmpty { "Test" }), lineIndex, suite = true)
            }
        }
        return lenses
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JS/TS
    // ─────────────────────────────────────────────────────────────────────────────

    private fun detectJsTests(lines: List<String>, filePath: String): JSONArray {
        val lenses = JSONArray()
        val scope = BraceScope()
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            scope.advance(line)

            val describe = matchCall(trimmed, listOf("describe(")) ?: matchCall(trimmed, listOf("describe.each("))
            if (describe != null) {
                // Chain captured BEFORE the push: the id names the suite itself, not
                // itself nested inside itself.
                val chainBefore = scope.chain()
                addLenses(lenses, idFor(filePath, chainBefore, describe), lineIndex, suite = true)
                scope.push(describe)
                return@forEachIndexed
            }

            val test = matchCall(trimmed, listOf(
                "it(", "it.skip(", "it.only(", "it.each(", "it.concurrent(",
                "test(", "test.skip(", "test.only(", "test.each(", "test.concurrent(",
            ))
            if (test != null) {
                addLenses(lenses, idFor(filePath, scope.chain(), test), lineIndex, suite = false)
            }
        }
        return lenses
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Dart
    // ─────────────────────────────────────────────────────────────────────────────

    private fun detectDartTests(lines: List<String>, filePath: String): JSONArray {
        val lenses = JSONArray()
        val scope = BraceScope()
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            scope.advance(line)

            val group = matchCall(trimmed, listOf("group("))
            if (group != null) {
                val chainBefore = scope.chain()
                addLenses(lenses, idFor(filePath, chainBefore, group), lineIndex, suite = true)
                scope.push(group)
                return@forEachIndexed
            }

            val test = matchCall(trimmed, listOf("test(", "testWidgets("))
            if (test != null) {
                addLenses(lenses, idFor(filePath, scope.chain(), test), lineIndex, suite = false)
            }
        }
        return lenses
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the first quoted (or backtick) argument of a matched call prefix,
     * or null when the line does not start with any of the prefixes.
     */
    private fun matchCall(trimmed: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it) } ?: return null
        val rest = trimmed.removePrefix(prefix).trim()
        val quote = when {
            rest.startsWith("'") -> '\''
            rest.startsWith("\"") -> '"'
            rest.startsWith("`") -> '`'
            else -> return null
        }
        val end = rest.indexOf(quote, 1)
        if (end <= 0) return null
        return rest.substring(1, end).ifEmpty { return null }
    }

    /** "<file>/<chain>/<name>" — slash-joined, VS Code testId shape. */
    private fun idFor(filePath: String, chain: String, name: String): String {
        val file = filePath.trimEnd('/')
        return if (chain.isEmpty()) "$file/$name" else "$file/$chain/$name"
    }

    private fun consume(pending: MutableSet<String>): Set<String> {
        val copy = pending.toSet()
        pending.clear()
        return copy
    }

    /**
     * Naive brace-depth scope tracker. String templates like dollar-brace are
     * balanced pairs and do not skew the count; unbalanced braces inside string
     * literals are accepted noise for a heuristic detector.
     */
    private class BraceScope {
        private val names = mutableListOf<String>()
        private val depths = mutableListOf<Int>()
        private var depth = 0

        /** Depth at which a declaration on the CURRENT line sits. */
        private var declDepth = 0

        fun advance(line: String) {
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            // A declaration opens its brace after itself, so it sits at the depth
            // before this line's opens (a closing brace before it, if any, applied).
            declDepth = depth - closes
            val depthAfter = declDepth + opens
            // A scope entered at depth d is alive while content depth exceeds d.
            while (names.isNotEmpty() && depthAfter <= depths.last()) {
                names.removeAt(names.size - 1)
                depths.removeAt(depths.size - 1)
            }
            depth = depthAfter
        }

        fun push(name: String) {
            names.add(name)
            depths.add(declDepth)
        }

        fun chain(): String = names.joinToString("/")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Lens JSON (VS Code CodeLens shape; arguments carry TestId + line for F2/F3)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun addLenses(lenses: JSONArray, id: String, lineIndex: Int, suite: Boolean) {
        lenses.put(makeLens(id, lineIndex, suite, debug = false))
        lenses.put(makeLens(id, lineIndex, suite, debug = true))
    }

    private fun makeLens(id: String, lineIndex: Int, suite: Boolean, debug: Boolean): JSONObject {
        val lens = JSONObject()
        val range = JSONObject()
        val start = JSONObject()
        start.put("line", lineIndex)
        start.put("character", 0)
        val end = JSONObject()
        end.put("line", lineIndex)
        end.put("character", 0)
        range.put("start", start)
        range.put("end", end)
        lens.put("range", range)

        val command = JSONObject()
        command.put("title", if (debug) TITLE_DEBUG else if (suite) TITLE_RUN_TESTS else TITLE_RUN_TEST)
        command.put("command", if (debug) CMD_DEBUG else CMD_RUN)
        // TestId path string FIRST (F3/F4 identity), line index SECOND (F1 handler
        // compatibility until F2 routes through TestRunManager).
        command.put("arguments", JSONArray().put(id).put(lineIndex))
        lens.put("command", command)

        val data = JSONObject()
        data.put("type", if (debug) "debugTest" else "runTest")
        data.put("id", id)
        data.put("line", lineIndex)
        data.put("name", id.substringAfterLast('/'))
        lens.put("data", data)
        return lens
    }
}
