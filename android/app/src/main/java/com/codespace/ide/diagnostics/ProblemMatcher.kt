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

    // PR06 (P4a-2): per-tool matcher selection — VS Code matchers are OPT-IN
    // per task; the old single gate ran ALL compiler formats against ANY
    // build-ish command, so python/npm/node runtime output produced false BUILD
    // rows (e.g. a script log line "server.py:42: error: retry failed" matched
    // the javac format and published a build problem that was not one).
    // Formats now apply only to tools that actually emit them:
    //  - build tools/compilers (gradle, make, gcc/clang, javac, kotlinc, tsc,
    //    cargo, dotnet, cmake) -> full compiler scan (GCC / KT_PROC / TSC / JAVAC)
    //  - python/python3 -> TRACEBACK pairing ONLY (real navigable errors; the
    //    compiler formats no longer run against script output)
    //  - npm/npx/yarn -> TSC format ONLY (build scripts wrap tsc; the strict
    //    "error TSxxxx" prefix cannot false-positive on package-manager output)
    //  - node -> REMOVED from the gate entirely: it is a pure runtime with no
    //    stable compiler format — scanning its output was all false-positive risk.
    private val COMPILER_TOOLS = Regex(
        "(^|[\\s;/])(gradlew?|make|gcc|g\\+\\+|clang\\+\\+|clang|cc|javac|kotlinc|tsc|cargo|dotnet|cmake)([\\s;|$]|$)"
    )
    private val PY_TOOLS = Regex("(^|[\\s;/])(python3?)([\\s;|$]|$)")
    private val TSC_WRAPPERS = Regex("(^|[\\s;/])(npm|npx|yarn)([\\s;|$]|$)")

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
        return COMPILER_TOOLS.containsMatchIn(command) ||
            PY_TOOLS.containsMatchIn(command) ||
            TSC_WRAPPERS.containsMatchIn(command)
    }

    private fun sev(word: String): DiagnosticManager.Severity {
        return when (word) {
            "error" -> DiagnosticManager.Severity.ERROR
            "warning" -> DiagnosticManager.Severity.WARNING
            "note" -> DiagnosticManager.Severity.INFO
            else -> DiagnosticManager.Severity.HINT
        }
    }

    /**
     * One line against the static compiler patterns (null when no match).
     * PR06: [allowCompiler] gates GCC/KT_PROC/JAVAC (true only for build
     * tools/compilers); [allowTsc] gates TSC (true for compilers and the
     * npm/npx/yarn wrapper class). A command class that disables a format
     * can never match with it — that is the whole per-task opt-in.
     */
    private fun matchLine(line: String, allowCompiler: Boolean, allowTsc: Boolean): MatchedProblem? {
        if (allowCompiler) {
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
            JAVAC.find(line)?.let { m ->
                return MatchedProblem(
                    m.groupValues[1], m.groupValues[2].toInt(), 1,
                    sev(m.groupValues[3]), m.groupValues[4],
                )
            }
        }
        if (allowTsc) {
            TSC.find(line)?.let { m ->
                return MatchedProblem(
                    m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    sev(m.groupValues[4]), m.groupValues[5] + ": " + m.groupValues[6],
                )
            }
        }
        return null
    }

    /** ANSI-strips then scans full tool output; deduped, capped at 200 problems.
     *  PR06: the format flags select which patterns apply — never all of them. */
    fun scan(
        output: String?,
        allowCompiler: Boolean = true,
        allowPy: Boolean = true,
        allowTsc: Boolean = true,
    ): List<MatchedProblem> {
        if (output.isNullOrBlank()) return emptyList()
        val out = ArrayList<MatchedProblem>()
        val seen = HashSet<String>()
        var pyFile: String? = null
        var pyLine = 1
        for (raw in ANSI.replace(output, "").lineSequence()) {
            if (out.size >= 200) break
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (allowPy) {
                val fm = PY_FILE.find(line)
                if (fm != null) {
                    pyFile = fm.groupValues[1]
                    pyLine = fm.groupValues[2].toInt()
                    continue
                }
            }
            val direct = matchLine(line, allowCompiler, allowTsc)
            if (direct != null) {
                if (seen.add(direct.file + ":" + direct.line + ":" + direct.column + ":" + direct.message)) {
                    out.add(direct)
                }
                continue
            }
            if (!allowPy) continue
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
    // BUG-A FIX: resolve matched tool paths (often RELATIVE, e.g. python tracebacks
    // "File "cwtest.py", line 2") against the command's working directory so the
    // Problems panel carries absolute paths and jump-to-source finds the open tab.
    private fun bugaResolve(raw: String, workdir: String?): String {
        if (raw.isBlank()) return raw
        return try {
            var f = java.io.File(raw)
            if (!f.isAbsolute && !workdir.isNullOrBlank()) f = java.io.File(workdir, raw)
            val canon = f.canonicalFile
            if (canon.exists()) canon.absolutePath else f.absolutePath
        } catch (_: Exception) { raw }
    }

    fun publishFromCommand(command: String?, output: String?, workdir: String? = null) {
        if (!isBuildishCommand(command)) return
        // PR06: the command class decides which formats may run (opt-in per task).
        val compiler = COMPILER_TOOLS.containsMatchIn(command!!)
        val py = PY_TOOLS.containsMatchIn(command)
        val tsc = compiler || TSC_WRAPPERS.containsMatchIn(command)
        val problems = scan(output, allowCompiler = compiler, allowPy = py, allowTsc = tsc).map { m ->
            MatchedProblem(bugaResolve(m.file, workdir), m.line, m.column, m.severity, m.message)
        }
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
