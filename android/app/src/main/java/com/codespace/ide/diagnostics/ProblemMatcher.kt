package com.codespace.ide.diagnostics

/**
 * CW1 — problem matchers (VS Code taskProblemMonitor.ts analog; B11 §6 + B14).
 *
 * Regex-match compiler/tool output from run commands into the shared
 * DiagnosticManager (source BUILD, sourceId "matcher") so the Problems panel
 * renders build failures from ANY tool, not just gradle-task runs. Published
 * diagnostics are REPLACED per command run (each file's group is republished,
 * and a clean run clears the previous batch) — VS Code matcher "owner" semantics.
 *
 * Covered tool formats (v1):
 *  - gcc/clang:   path:12:5: error: msg  (also fatal error / warning / note)
 *  - javac:       path:12: error: msg
 *  - kotlinc:     e: path:12:5 msg   /   w: path:12:5 msg   (gradle in-process)
 *  - tsc/MSBuild: path(12,5): error TS1234: msg
 *  - python:      File "path", line 12  paired with  ExcType: msg  (traceback)
 */
object ProblemMatcher {

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")

    /** Only build-ish commands get scanned (VS Code: matchers are opt-in per task). */
    private val BUILDISH = Regex(
        "(^|[\\s;/])(gradlew?|make|gcc|g\\+\\+|clang\\+\\+|clang|cc|javac|kotlinc|python3?|node|npm|npx|yarn|tsc|cargo|dotnet|cmake)([\\s;|$]|$)"
    )

    data class MatchedProblem(
        val file: String,
        val line: Int,
        val column: Int,
        val severity: DiagnosticManager.Severity,
        val message: String,
    )

    // gcc/clang: path:12:5: error: msg (also "fatal error:", "warning:", "note:")
    private val GCC = Regex("^(\\S+?):(\\d+):(\\d+): (?:fatal )?(error|warning|note): (.*)$")

    // javac / generic: path:12: error: msg
    private val JAVAC = Regex("^(\\S+?):(\\d+): ?(error|warning): (.*)$")

    // gradle in-process kotlin: e: path:12:5 msg
    private val KT_PROC = Regex("^([ew]): (\\S+?):(\\d+):(\\d+) (.*)$")

    // tsc / MSBuild: path(12,5): error TS1234: msg
    private val TSC = Regex("^(\\S+?)\\((\\d+),(\\d+)\\): (error|warning) (\\S+): (.*)$")

    // python traceback pairing: File "path", line 12  ...  TypeError: msg
    private val PY_FILE = Regex("^\\s*File \"(.*?)\", line (\\d+)")
    private val PY_EXC = Regex("^(\\w*(?:Error|Exception|Warning))(: .*)?$")

    @Volatile private var published = false

    fun isBuildishCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        return BUILDISH.containsMatchIn(command)
    }

    private fun sev(word: String): DiagnosticManager.Severity {
        return when (word) {
            "error" -> DiagnosticManager.Severity.ERROR
            "warning" -> DiagnosticManager.Severity.WARNING
            "note" -> DiagnosticManager.Severity.INFO
            else -> DiagnosticManager.Severity.HINT
        }
    }

    /** One line against the static compiler patterns (null when no match). */
    private fun matchLine(line: String): MatchedProblem? {
        GCC.find(line)?.let { m ->
            return MatchedProblem(
                m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                sev(m.groupValues[4]), m.groupValues[5],
            )
        }
        KT_PROC.find(line)?.let { m ->
            return MatchedProblem(
                m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                if (m.groupValues[1] == "e") DiagnosticManager.Severity.ERROR
                else DiagnosticManager.Severity.WARNING,
                m.groupValues[5],
            )
        }
        TSC.find(line)?.let { m ->
            return MatchedProblem(
                m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                sev(m.groupValues[4]), m.groupValues[5] + ": " + m.groupValues[6],
            )
        }
        JAVAC.find(line)?.let { m ->
            return MatchedProblem(
                m.groupValues[1], m.groupValues[2].toInt(), 1,
                sev(m.groupValues[3]), m.groupValues[4],
            )
        }
        return null
    }

    /** ANSI-strips then scans full tool output; deduped, capped at 200 problems. */
    fun scan(output: String?): List<MatchedProblem> {
        if (output.isNullOrBlank()) return emptyList()
        val out = ArrayList<MatchedProblem>()
        val seen = HashSet<String>()
        var pyFile: String? = null
        var pyLine = 1
        for (raw in ANSI.replace(output, "").lineSequence()) {
            if (out.size >= 200) break
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val fm = PY_FILE.find(line)
            if (fm != null) {
                pyFile = fm.groupValues[1]
                pyLine = fm.groupValues[2].toInt()
                continue
            }
            val direct = matchLine(line)
            if (direct != null) {
                if (seen.add(direct.file + ":" + direct.line + ":" + direct.column + ":" + direct.message)) {
                    out.add(direct)
                }
                continue
            }
            val exc = PY_EXC.find(line.trim())
            if (exc != null && pyFile != null) {
                val msg = exc.groupValues[1] + exc.groupValues[2]
                if (seen.add(pyFile + ":" + pyLine + ":" + msg)) {
                    out.add(
                        MatchedProblem(
                            pyFile, pyLine, 1,
                            DiagnosticManager.Severity.ERROR, msg,
                        )
                    )
                }
                pyFile = null
            }
        }
        return out
    }

    private fun toDiagnostic(p: MatchedProblem): DiagnosticManager.Diagnostic {
        val uri = "file://" + p.file
        val range = DiagnosticManager.DiagnosticRange(p.line, p.column, p.line, p.column)
        val id = DiagnosticManager.computeId(
            DiagnosticManager.DiagnosticSource.BUILD, "matcher", uri, range, p.severity, null, p.message
        )
        return DiagnosticManager.Diagnostic(
            id = id,
            source = DiagnosticManager.DiagnosticSource.BUILD,
            sourceId = "matcher",
            uri = uri,
            filePath = p.file,
            range = range,
            severity = p.severity,
            message = p.message,
        )
    }

    /**
     * Scan a command's output and REPLACE the previous matcher batch in the
     * Problems panel (clean run clears, no-op for non-build commands).
     */
    fun publishFromCommand(command: String?, output: String?) {
        if (!isBuildishCommand(command)) return
        val problems = scan(output)
        if (problems.isEmpty()) {
            if (published) {
                DiagnosticManager.clearSource(DiagnosticManager.DiagnosticSource.BUILD, "matcher")
                published = false
            }
            return
        }
        val byUri = problems.groupBy { "file://" + it.file }
        for ((uri, group) in byUri) {
            DiagnosticManager.publishDiagnostics(
                DiagnosticManager.DiagnosticSource.BUILD, "matcher",
                uri, group[0].file, group.map { toDiagnostic(it) },
            )
        }
        published = true
    }
}
