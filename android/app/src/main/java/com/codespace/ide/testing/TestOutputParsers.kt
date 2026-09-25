package com.codespace.ide.testing

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * F3 (F-TRACK TG03): machine-readable runner output parsers.
 *
 * pytest (via --junitxml) and Gradle (build test-results XML files) share the
 * JUnit XML shape; jest is parsed from its --json --outputFile payload.
 * These parsers replace exit-code-only inference: per-test states and real
 * failure messages come from the runner's own report, not from guessing.
 */
object TestOutputParsers {

    /** One parsed test case, before mapping back to a TestId. */
    data class ParsedCase(
        /** junit name / jest title. */
        val key: String,
        /** junit classname (null for jest). */
        val className: String?,
        /** jest ancestorTitles chain (empty otherwise). */
        val ancestors: List<String>,
        val state: TestResultState,
        /** Failure/error message; null when passing. */
        val message: String?,
        /**
         * 0-based failure line inside the run's own file, when the report
         * carries one (pytest traceback "file.py:12:"); else null.
         */
        val fileLine: Int?,
        val durationMs: Long,
    )

    /**
     * JUnit XML parser (pytest --junitxml AND gradle TEST-*.xml shapes).
     * fileBase: the run's file basename — traceback file:line extraction
     * only fires for lines belonging to THIS file.
     */
    fun parseJunitXml(xml: String, fileBase: String): List<ParsedCase> {
        val cases = mutableListOf<ParsedCase>()
        // Guard: a malformed XML must not throw into the run pipeline.
        if (xml.isBlank() || !xml.contains("<testcase")) return cases
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            var inCase = false
            var name = ""
            var className: String? = null
            var timeMs = 0L
            var state = TestResultState.PASSED
            var message: String? = null
            var wantText = false
            var textBuf = java.lang.StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        if (tag == "testcase") {
                            inCase = true
                            name = parser.getAttributeValue(null, "name") ?: ""
                            className = parser.getAttributeValue(null, "classname")
                            val t = parser.getAttributeValue(null, "time")
                            timeMs = ((t?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                            state = TestResultState.PASSED
                            message = null
                            textBuf = java.lang.StringBuilder()
                            wantText = false
                        } else if (inCase) {
                            when (tag) {
                                "failure" -> {
                                    state = TestResultState.FAILED
                                    message = parser.getAttributeValue(null, "message")
                                    wantText = true
                                }
                                "error" -> {
                                    state = TestResultState.ERRORED
                                    message = parser.getAttributeValue(null, "message")
                                    wantText = true
                                }
                                "skipped" -> {
                                    state = TestResultState.SKIPPED
                                    message = parser.getAttributeValue(null, "message")
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCase && wantText) textBuf.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name
                        if (tag == "testcase" && inCase) {
                            val body = textBuf.toString()
                            // Fallback message: the first non-blank line of the failure body.
                            if (message.isNullOrBlank() && body.isNotBlank()) {
                                message = body.lines().firstOrNull { it.isNotBlank() }
                            }
                            cases.add(
                                ParsedCase(
                                    key = name,
                                    className = className,
                                    ancestors = emptyList(),
                                    state = state,
                                    message = message?.take(500),
                                    fileLine = tracebackLine(body + " " + (message ?: ""), fileBase),
                                    durationMs = timeMs,
                                )
                            )
                            inCase = false
                        }
                    }
                }
                event = parser.next()
            }
            cases
        } catch (_: Exception) {
            // Malformed report — caller falls back to the raw typed status.
            cases
        }
    }

    /**
     * jest --json payload (from --outputFile). Maps statuses to the 7-state
     * model and carries ancestorTitles for exact F1 TestId reconstruction.
     */
    fun parseJestJson(json: String): List<ParsedCase> {
        val cases = mutableListOf<ParsedCase>()
        if (json.isBlank()) return cases
        return try {
            val root = JSONObject(json)
            val testResults = root.optJSONArray("testResults") ?: return cases
            for (i in 0 until testResults.length()) {
                val fileResult = testResults.optJSONObject(i) ?: continue
                val assertions = fileResult.optJSONArray("assertionResults") ?: continue
                for (j in 0 until assertions.length()) {
                    val a = assertions.optJSONObject(j) ?: continue
                    val status = a.optString("status", "")
                    val state = when (status) {
                        "passed" -> TestResultState.PASSED
                        "failed" -> TestResultState.FAILED
                        else -> TestResultState.SKIPPED
                    }
                    val failureMessages = a.optJSONArray("failureMessages")
                    var message: String? = null
                    if (failureMessages != null && failureMessages.length() > 0) {
                        message = failureMessages.optString(0, "").take(500).ifBlank { null }
                    }
                    val location = a.optJSONObject("location")
                    val line = location?.optInt("line", -1) ?: -1
                    val ancestorsList = mutableListOf<String>()
                    val ancestorsJson = a.optJSONArray("ancestorTitles")
                    if (ancestorsJson != null) {
                        for (k in 0 until ancestorsJson.length()) {
                            ancestorsList.add(ancestorsJson.optString(k, ""))
                        }
                    }
                    cases.add(
                        ParsedCase(
                            key = a.optString("title", ""),
                            className = null,
                            ancestors = ancestorsList.filter { it.isNotBlank() },
                            state = state,
                            message = message,
                            // jest location.line is 1-based; store 0-based.
                            fileLine = if (line > 0) line - 1 else null,
                            durationMs = a.optDouble("duration", 0.0).toLong(),
                        )
                    )
                }
            }
            cases
        } catch (_: Exception) {
            cases
        }
    }

    /** First "basename.py:NN" line in the failure text that belongs to this file. */
    private fun tracebackLine(text: String, fileBase: String): Int? {
        if (fileBase.isBlank()) return null
        val regex = Regex(Regex.escape(fileBase) + ":(\\d+)")
        val match = regex.find(text) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        // 1-based traceback line -> 0-based editor index.
        return if (line > 0) line - 1 else null
    }
}
