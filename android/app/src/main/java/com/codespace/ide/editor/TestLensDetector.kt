package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import org.json.JSONArray
import org.json.JSONObject

/**
 * P41-T: Test Lens Detector — scans file content for test functions and generates
 * synthetic CodeLens entries ("▶ Run Test" / "Debug Test") to match VS Code's
 * Run/Debug test CodeLens behavior.
 *
 * Supports detection patterns:
 * - Kotlin/Java: @Test annotation, fun test*(), void test*()
 * - Python: def test_*():, class Test*
 * - JavaScript/TypeScript: it('...'), test('...'), describe('...')
 * - Dart: test('...'), testWidgets('...'), group('...')
 */
object TestLensDetector {

    /**
     * Scans file content and returns synthetic CodeLens JSON entries for test functions.
     * Each test function gets a "▶ Run Test" lens and a "Debug Test" lens.
     */
    fun detectTestLenses(content: String, language: Language): JSONArray {
        val lenses = JSONArray()
        val lines = content.lines()
        val seenLines = mutableSetOf<Int>()

        when (language) {
            Language.KOTLIN, Language.JAVA -> detectJvmTests(lines, lenses, seenLines)
            Language.PYTHON -> detectPythonTests(lines, lenses, seenLines)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> detectJsTests(lines, lenses, seenLines)
            Language.DART -> detectDartTests(lines, lenses, seenLines)
            else -> {}
        }

        return lenses
    }

    private fun detectJvmTests(lines: List<String>, lenses: JSONArray, seen: MutableSet<Int>) {
        var inTestClass = false
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()

            if (trimmed.contains("class ") && (trimmed.contains("Test") || trimmed.contains("Spec"))) {
                inTestClass = true
            }

            // @Test annotation → function is on next line
            if (trimmed == "@Test" || trimmed.startsWith("@Test ") || trimmed.startsWith("@Test(")) {
                if (lineIndex + 1 < lines.size && lineIndex + 1 !in seen) {
                    addTestLens(lenses, lineIndex + 1)
                    seen.add(lineIndex + 1)
                }
            }

            // Unannotated test functions inside test classes
            if (inTestClass && lineIndex !in seen) {
                if (trimmed.startsWith("fun ") && trimmed.contains("test")) {
                    addTestLens(lenses, lineIndex)
                    seen.add(lineIndex)
                }
                if (trimmed.contains("void ") && trimmed.contains("test")) {
                    addTestLens(lenses, lineIndex)
                    seen.add(lineIndex)
                }
            }
        }
    }

    private fun detectPythonTests(lines: List<String>, lenses: JSONArray, seen: MutableSet<Int>) {
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex in seen) return@forEachIndexed
            val trimmed = line.trim()
            if (trimmed.startsWith("def test_") || trimmed.startsWith("async def test_")) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
            if (trimmed.startsWith("class Test") && trimmed.contains(":")) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
        }
    }

    private fun detectJsTests(lines: List<String>, lenses: JSONArray, seen: MutableSet<Int>) {
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex in seen) return@forEachIndexed
            val trimmed = line.trim()
            if ((trimmed.startsWith("it(") || trimmed.startsWith("test(") ||
                 trimmed.startsWith("it.skip(") || trimmed.startsWith("it.only(")) &&
                (trimmed.contains("'") || trimmed.contains("\""))) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
            if (trimmed.startsWith("describe(") && (trimmed.contains("'") || trimmed.contains("\""))) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
        }
    }

    private fun detectDartTests(lines: List<String>, lenses: JSONArray, seen: MutableSet<Int>) {
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex in seen) return@forEachIndexed
            val trimmed = line.trim()
            if ((trimmed.startsWith("test(") || trimmed.startsWith("testWidgets(")) &&
                (trimmed.contains("'") || trimmed.contains("\""))) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
            if (trimmed.startsWith("group(") && (trimmed.contains("'") || trimmed.contains("\""))) {
                addTestLens(lenses, lineIndex)
                seen.add(lineIndex)
            }
        }
    }

    private fun addTestLens(lenses: JSONArray, lineIndex: Int) {
        // "▶ Run Test" lens
        val runLens = JSONObject()
        val runRange = JSONObject()
        val runStart = JSONObject()
        runStart.put("line", lineIndex)
        runStart.put("character", 0)
        val runEnd = JSONObject()
        runEnd.put("line", lineIndex)
        runEnd.put("character", 0)
        runRange.put("start", runStart)
        runRange.put("end", runEnd)
        runLens.put("range", runRange)
        val runCommand = JSONObject()
        runCommand.put("title", "\u25b6 Run Test")
        runCommand.put("command", "codespace.runTest")
        runCommand.put("arguments", JSONArray().put(lineIndex))
        runLens.put("command", runCommand)
        runLens.put("data", JSONObject().put("type", "runTest").put("line", lineIndex))
        lenses.put(runLens)

        // "Debug Test" lens
        val debugLens = JSONObject()
        val debugRange = JSONObject()
        val debugStart = JSONObject()
        debugStart.put("line", lineIndex)
        debugStart.put("character", 0)
        val debugEnd = JSONObject()
        debugEnd.put("line", lineIndex)
        debugEnd.put("character", 0)
        debugRange.put("start", debugStart)
        debugRange.put("end", debugEnd)
        debugLens.put("range", debugRange)
        val debugCommand = JSONObject()
        debugCommand.put("title", "Debug Test")
        debugCommand.put("command", "codespace.debugTest")
        debugCommand.put("arguments", JSONArray().put(lineIndex))
        debugLens.put("command", debugCommand)
        debugLens.put("data", JSONObject().put("type", "debugTest").put("line", lineIndex))
        lenses.put(debugLens)
    }
}
