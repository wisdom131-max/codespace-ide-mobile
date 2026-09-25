package com.codespace.ide.testing

import android.content.Context
import com.codespace.ide.domain.Language
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.security.TrustState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * F2 (F-TRACK TG01 + TG02): Honest Run — per-test command execution.
 *
 * A tapped Run Test lens runs EXACTLY that test via the language's runner
 * (pytest node id, jest -t, gradle --tests, flutter --plain-name) — never the
 * whole file or project as a side effect. The old behavior (one log line with
 * a file-wide command that was never executed) is DELETED; the run is real,
 * streamed to the Output tab "test" channel, cancellable, and reports a TYPED
 * result. Failures are visible failures (no stderr suppression, no placebo
 * fallback commands).
 *
 * Trust gate (IG02/P2c pattern): the first gated action for an untrusted
 * project prompts once via TrustState; a refusal cancels the run honestly.
 * Headless callers fail closed (no context to prompt under → refused).
 *
 * Sequencing note: cancelRun() is the API for the F4 Test pane's stop control.
 * Debug-test lenses are intentionally NOT supported here — the detector's
 * Debug lenses are filtered out at the EditorPane site until F5 routes them
 * through UniversalDebugManager.
 */
object TestRunManager {

    /** Typed terminal states for one test run (S01 family: never claim success without verification). */
    enum class TestRunStatus { RUNNING, PASSED, FAILED, TIMED_OUT, CANCELLED, LAUNCH_FAILED, UNTRUSTED, UNSUPPORTED, BUSY }

    data class TestRunResult(
        val testId: String,
        val status: TestRunStatus,
        val exitCode: Int?,
        val durationMs: Long,
    )

    @Volatile private var active = false
    @Volatile private var cancelRequested = false
    private var currentProcess: Process? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // Capability bitset (TG01): a lens must never promise what its tap cannot do.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Run capability per language — the languages the F1 detector emits lenses
     * for and this manager can really execute: pytest, jest, gradle, flutter.
     */
    fun supportsRun(language: Language): Boolean = when (language) {
        Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT,
        Language.KOTLIN, Language.JAVA, Language.DART -> true
        else -> false
    }

    /**
     * Debug capability for TEST lenses (F5, TG07p1): Python via debugpy
     * module+args launch, JS/TS via node --inspect-brk + js-debug attach —
     * both through UniversalDebugManager, so breakpoints/pause/step work in
     * the existing Debug Console. JVM is F6 (JDWP decision pending); Dart and
     * everything else stays honestly false (DG04: a control may never promise
     * a debug session that does not exist).
     */
    fun supportsDebug(language: Language): Boolean = when (language) {
        Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT -> true
        else -> false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Cancellation (consumed by the F4 Test pane stop control)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Kills the live run, if any. CANCELLED is sticky for the result line. */
    fun cancelRun() {
        cancelRequested = true
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // F4 batch runner (Testing pane run-all / re-run-failed)
    // ─────────────────────────────────────────────────────────────────────────────

    /** One batch target — TestStore items mapped 1:1 onto runTest arguments. */
    data class BatchTarget(
        val testId: String,
        val hostFilePath: String,
        val language: Language,
        val suite: Boolean,
        val lineIndex: Int,
    )

    /**
     * Runs targets SEQUENTIALLY through runTest — one runner at a time, the
     * pane observes progress live via the F3 result store (RUNNING recorded
     * per test). Stops when the caller cancels (stop button), when the
     * current run ends CANCELLED (sticky, PR01 pattern), or when the list is
     * exhausted. Individual UNTRUSTED/UNSUPPORTED results are recorded and
     * the batch continues — one unreachable file must not hide the others.
     */
    suspend fun runBatch(
        context: Context,
        projectRoot: String?,
        targets: List<BatchTarget>,
        isCancelled: () -> Boolean,
    ): List<TestRunResult> {
        val results = mutableListOf<TestRunResult>()
        for (t in targets) {
            if (isCancelled()) break
            val r = runTest(context, projectRoot, t.hostFilePath, t.language, t.testId, t.suite, t.lineIndex)
            results.add(r)
            if (r.status == TestRunStatus.CANCELLED) break
        }
        return results
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Execution
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Runs ONE test (or suite) for real. Streams runner output to the Output
     * tab "test" channel and returns the typed outcome. Trust-gated: refuses
     * UNTRUSTED for untrusted projects. One run at a time — a second
     * concurrent tap returns BUSY instead of interleaving two runners.
     */
    suspend fun runTest(
        context: Context,
        projectRoot: String?,
        hostFilePath: String,
        language: Language,
        testId: String,
        suite: Boolean,
        lineIndex: Int = -1,
    ): TestRunResult = withContext(Dispatchers.IO) {
        val leaf = leafName(testId, hostFilePath)

        // One run at a time — interleaved runners produce unreadable output
        // and double-spawned gradle daemons.
        if (active) {
            log("[TestRun] A test run is already active — cancel it before starting another.")
            return@withContext TestRunResult(testId, TestRunStatus.BUSY, null, 0)
        }
        active = true
        cancelRequested = false
        try {
            // Trust gate first (IG02/P2c pattern): prompt once, refuse on no.
            val trustPath = projectRoot ?: File(hostFilePath).parent
            if (!TrustState.awaitTrusted(context, trustPath)) {
                log("[TestRun] Project not trusted — test run refused. Trust the project and try again.")
                return@withContext TestRunResult(testId, TestRunStatus.UNTRUSTED, null, 0)
            }

            // Guest translation — the file must be reachable inside the rootfs.
            val guestFile = ProotInstaller.hostToGuestPath(context, hostFilePath)
            if (guestFile == null) {
                log("[TestRun] Cannot reach this file inside the Ubuntu sandbox — test run refused.")
                return@withContext TestRunResult(testId, TestRunStatus.UNSUPPORTED, null, 0)
            }
            val guestRoot = (projectRoot?.let { ProotInstaller.hostToGuestPath(context, it) })
                ?: File(guestFile).parent

            val command = buildCommand(guestRoot, guestFile, hostFilePath, language, testId, suite)
            if (command == null) {
                log("[TestRun] No test runner wired for ${language.displayName} yet — nothing was run.")
                return@withContext TestRunResult(testId, TestRunStatus.UNSUPPORTED, null, 0)
            }

            val (cmd, timeoutSeconds) = command
            log("[TestRun] \u25b6 Running " + (if (suite) "suite " else "test ") + leaf + "\u2026")
            val started = System.currentTimeMillis()

            // F3 (TG03): live RUNNING state on the test line so the gutter
            // shows the run in progress; replaced by the terminal outcome.
            if (lineIndex >= 0) {
                TestResultStore.record(
                    TestResultItem(
                        testId = testId, lineIndex = lineIndex,
                        ownState = TestResultState.RUNNING,
                        computedState = TestResultState.RUNNING,
                        ownDurationMs = 0, message = null, retired = false,
                        runId = started,
                    )
                )
            }

            val result = ProotInstaller.execTyped(
                context, cmd, workdir = guestRoot,
                timeoutSeconds = timeoutSeconds,
                logToOutput = true, maxLines = 10000, logTag = "test",
                onProcess = { proc -> currentProcess = proc },
            )
            val durationMs = System.currentTimeMillis() - started

            val status = when {
                result.launchError != null -> TestRunStatus.LAUNCH_FAILED
                result.timedOut -> TestRunStatus.TIMED_OUT
                cancelRequested -> TestRunStatus.CANCELLED
                result.exitCode == 0 -> TestRunStatus.PASSED
                else -> TestRunStatus.FAILED
            }
            // ── F3 (TG03): parse per-test outcomes, record them, bridge failures ──
            recordOutcomes(
                status, testId, lineIndex, hostFilePath, projectRoot, language,
                result, started, durationMs, timeoutSeconds,
            )
            logResult(status, leaf, result.exitCode, durationMs, result.launchError)
            TestRunResult(testId, status, result.exitCode, durationMs)
        } finally {
            currentProcess = null
            active = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // F5 (TG07p1): Debug Test — routed through UniversalDebugManager
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Starts a REAL debug session for ONE test (or suite) through
     * UniversalDebugManager — the same adapters, breakpoints, pause/step and
     * Debug Console as the file-debug path. The typed return reflects the
     * LAUNCH only (the session runs on after it); the terminal outcome is
     * recorded in TestResultStore from the session's exit truth (STOPPED =
     * exit 0 = PASSED, CRASHED = non-zero = FAILED).
     *
     * Python: debugpy launches pytest as module+args with the tapped node id.
     * JS/TS: jest is spawned under node --inspect-brk and js-debug ATTACHES;
     * debugging requires jest installed locally (npx-fetch cannot be attached).
     * JVM is F6 — UNSUPPORTED here, honestly.
     */
    suspend fun debugTest(
        context: Context,
        projectRoot: String?,
        hostFilePath: String,
        language: Language,
        testId: String,
        suite: Boolean,
        lineIndex: Int = -1,
    ): TestRunResult = withContext(Dispatchers.IO) {
        val leaf = leafName(testId, hostFilePath)

        if (!supportsDebug(language)) {
            log("[TestDebug] No test debugger wired for ${language.displayName} yet - nothing was started.")
            return@withContext TestRunResult(testId, TestRunStatus.UNSUPPORTED, null, 0)
        }
        if (active) {
            log("[TestDebug] A test run is already active - cancel it before debugging.")
            return@withContext TestRunResult(testId, TestRunStatus.BUSY, null, 0)
        }
        active = true
        try {
            // Trust gate first (same choke point as Run).
            val trustPath = projectRoot ?: File(hostFilePath).parent
            if (!TrustState.awaitTrusted(context, trustPath)) {
                log("[TestDebug] Project not trusted - debug session refused.")
                return@withContext TestRunResult(testId, TestRunStatus.UNTRUSTED, null, 0)
            }

            val guestFile = ProotInstaller.hostToGuestPath(context, hostFilePath)
            if (guestFile == null) {
                log("[TestDebug] Cannot reach this file inside the Ubuntu sandbox - debug refused.")
                return@withContext TestRunResult(testId, TestRunStatus.UNSUPPORTED, null, 0)
            }
            val guestRoot = (projectRoot?.let { ProotInstaller.hostToGuestPath(context, it) })
                ?: File(guestFile).parent

            val spec = buildDebugSpec(guestRoot, guestFile, hostFilePath, projectRoot, language, testId)
            if (spec == null) {
                return@withContext TestRunResult(testId, TestRunStatus.UNSUPPORTED, null, 0)
            }

            log("[TestDebug] \u25b6 Debugging " + (if (suite) "suite " else "test ") + leaf + "\u2026")
            val started = System.currentTimeMillis()

            // Live RUNNING marker on the test line, same as Run (F3).
            if (lineIndex >= 0) {
                TestResultStore.record(
                    TestResultItem(
                        testId = testId, lineIndex = lineIndex,
                        ownState = TestResultState.RUNNING,
                        computedState = TestResultState.RUNNING,
                        ownDurationMs = 0, message = null, retired = false,
                        runId = started,
                    )
                )
            }

            val sessionId = com.codespace.ide.debug.UniversalDebugManager.startDebug(
                language, hostFilePath, projectRoot, context,
                testDebug = spec,
            )
            if (sessionId == null) {
                // Honest cleanup: no session was created — drop the RUNNING
                // marker we wrote instead of leaving a stuck "running" row.
                if (lineIndex >= 0) TestResultStore.remove(testId)
                log("[TestDebug] The debugger failed to start - see the Debug Console output for the reason.")
                return@withContext TestRunResult(testId, TestRunStatus.LAUNCH_FAILED, null, 0)
            }

            // Terminal outcome from the session's exit truth: STOPPED means
            // exit 0 (P27-10 crash detection in UDM), CRASHED means non-zero.
            recordDebugOutcomeWhenDone(sessionId, testId, lineIndex, language)
            logResult(TestRunStatus.RUNNING, leaf, null, System.currentTimeMillis() - started, null)
            TestRunResult(testId, TestRunStatus.RUNNING, null, 0)
        } finally {
            active = false
        }
    }

    /** Per-runner launch spec for the adapters (F5): pytest node id or jest -t. */
    private fun buildDebugSpec(
        guestRoot: String,
        guestFile: String,
        hostFilePath: String,
        projectRoot: String?,
        language: Language,
        testId: String,
    ): com.codespace.ide.debug.TestDebugSpec? {
        val chain = chainOf(testId, hostFilePath)
        if (chain.isEmpty()) return null
        val leaf = chain.lastOrNull() ?: return null
        return when (language) {
            Language.PYTHON -> {
                // Same node id shape as the Run path: file::Class::method.
                val node = (listOf(guestFile) + chain).joinToString("::")
                com.codespace.ide.debug.TestDebugSpec(
                    runner = "pytest",
                    guestArgs = listOf(node, "-v"),
                    guestWorkdir = guestRoot,
                    hostWorkdir = projectRoot,
                )
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                // Requires jest installed locally — checked host-side by the
                // adapter before spawning (honest refusal, no npx fallback).
                com.codespace.ide.debug.TestDebugSpec(
                    runner = "jest",
                    guestArgs = listOf(guestFile, "-t", leaf),
                    guestWorkdir = guestRoot,
                    hostWorkdir = projectRoot,
                )
            }
            else -> null
        }
    }

    /**
     * Records the terminal outcome when this debug session ends. The listener
     * self-removes on the first terminal state of THIS session id.
     */
    private fun recordDebugOutcomeWhenDone(
        sessionId: String,
        testId: String,
        lineIndex: Int,
        language: Language,
    ) {
        lateinit var listener: (com.codespace.ide.debug.DebugSession) -> Unit
        listener = { s ->
            if (s.id == sessionId) {
                val terminal = s.state == com.codespace.ide.debug.DebugState.STOPPED ||
                    s.state == com.codespace.ide.debug.DebugState.CRASHED ||
                    s.state == com.codespace.ide.debug.DebugState.FAILED ||
                    s.state == com.codespace.ide.debug.DebugState.ERROR
                if (terminal) {
                    com.codespace.ide.debug.UniversalDebugManager.removeOnSessionStateChangedListener(listener)
                    // A failed LAUNCH never reached the runner — drop the
                    // marker rather than claiming a test outcome.
                    val launchFailed = s.state == com.codespace.ide.debug.DebugState.FAILED ||
                        s.state == com.codespace.ide.debug.DebugState.ERROR
                    if (launchFailed) {
                        TestResultStore.remove(testId)
                    } else {
                        // STOPPED = exit 0 = PASSED; CRASHED = non-zero (P27-10).
                        val passed = s.state == com.codespace.ide.debug.DebugState.STOPPED
                        val st = if (passed) TestResultState.PASSED else TestResultState.FAILED
                        if (lineIndex >= 0) {
                            TestResultStore.record(
                                TestResultItem(
                                    testId = testId, lineIndex = lineIndex,
                                    ownState = st, computedState = st,
                                    ownDurationMs = 0,
                                    message = if (passed) null else "debug session ended abnormally (" + s.state + ")",
                                    retired = false,
                                    runId = System.currentTimeMillis(),
                                )
                            )
                        }
                    }
                }
            }
        }
        com.codespace.ide.debug.UniversalDebugManager.addOnSessionStateChangedListener(listener)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // F3 (TG03): result recording + Problems bridging
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Maps the run to per-test result items, records them in TestResultStore
     * (retiring same-file items this run did not cover), records the run
     * summary, and bridges failures to Problems under the TEST source.
     * No outcome is recorded for BUSY/UNTRUSTED/UNSUPPORTED/LAUNCH_FAILED —
     * those runs claim no result (honesty rule).
     */
    private fun recordOutcomes(
        status: TestRunStatus,
        testId: String,
        lineIndex: Int,
        hostFilePath: String,
        projectRoot: String?,
        language: Language,
        result: com.codespace.ide.terminal.ProotResult,
        runId: Long,
        durationMs: Long,
        timeoutSeconds: Long,
    ) {
        when (status) {
            TestRunStatus.PASSED, TestRunStatus.FAILED -> {
                val cases = parseOutcomes(hostFilePath, projectRoot, language, runId)
                var passed = 0
                var failedCount = 0
                var erroredCount = 0
                var skipped = 0
                val failures = mutableListOf<Pair<Int, String>>()
                val liveIds = mutableSetOf<String>()

                if (cases.isEmpty()) {
                    // No machine-readable report — record the tapped node from
                    // the raw typed status (exit code is still verified truth).
                    val st = if (status == TestRunStatus.PASSED) {
                        TestResultState.PASSED
                    } else {
                        TestResultState.FAILED
                    }
                    val msg = if (st == TestResultState.FAILED) {
                        "exit " + (result.exitCode ?: -1)
                    } else {
                        null
                    }
                    TestResultStore.record(
                        TestResultItem(testId, lineIndex, st, st, durationMs, msg, false, runId)
                    )
                    liveIds.add(testId)
                    if (st == TestResultState.PASSED) passed += 1 else erroredCount += 1
                    // Clear stale failure rows for this file when passing.
                    if (st == TestResultState.PASSED) {
                        com.codespace.ide.diagnostics.DiagnosticPublisher
                            .publishTestFailures(hostFilePath, emptyList())
                    }
                } else {
                    for (case in cases) {
                        val id = mapTestId(hostFilePath, language, case)
                        val line = case.fileLine ?: (if (id == testId) lineIndex else -1)
                        TestResultStore.record(
                            TestResultItem(id, line, case.state, case.state, case.durationMs, case.message, false, runId)
                        )
                        liveIds.add(id)
                        when (case.state) {
                            TestResultState.PASSED -> passed += 1
                            TestResultState.FAILED -> {
                                failedCount += 1
                                if (line >= 0 && case.message != null) failures.add(line to case.message)
                            }
                            TestResultState.ERRORED -> {
                                erroredCount += 1
                                if (line >= 0 && case.message != null) failures.add(line to case.message)
                            }
                            TestResultState.SKIPPED -> skipped += 1
                            else -> {}
                        }
                    }
                    // Container rollup: the tapped suite (or test) line shows
                    // the merged state of everything this run covered.
                    var merged = cases.first().state
                    for (c in cases) {
                        merged = TestResultStates.merge(merged, c.state)
                    }
                    val containerMsg = cases.firstOrNull { it.message != null }?.message
                    TestResultStore.record(
                        TestResultItem(testId, lineIndex, merged, merged, durationMs, containerMsg, false, runId)
                    )
                    liveIds.add(testId)
                    com.codespace.ide.diagnostics.DiagnosticPublisher
                        .publishTestFailures(hostFilePath, failures)
                }

                TestResultStore.retireMissing(hostFilePath, liveIds)
                TestResultStore.recordRun(
                    TestRun(
                        id = runId,
                        name = testId.substringAfterLast('/'),
                        completedAt = System.currentTimeMillis(),
                        passed = passed,
                        failed = failedCount,
                        errored = erroredCount,
                        skipped = skipped,
                    )
                )
            }
            TestRunStatus.TIMED_OUT -> {
                TestResultStore.record(
                    TestResultItem(
                        testId, lineIndex, TestResultState.ERRORED, TestResultState.ERRORED,
                        durationMs, "Runner timed out after " + timeoutSeconds + "s", false, runId,
                    )
                )
                TestResultStore.retireMissing(hostFilePath, setOf(testId))
            }
            TestRunStatus.CANCELLED -> {
                TestResultStore.record(
                    TestResultItem(
                        testId, lineIndex, TestResultState.RETIRED, TestResultState.RETIRED,
                        durationMs, null, true, runId,
                    )
                )
                TestResultStore.retireMissing(hostFilePath, setOf(testId))
            }
            else -> {} // BUSY / UNTRUSTED / UNSUPPORTED / LAUNCH_FAILED: no outcome claim
        }
    }

    /**
     * Reads the runner's machine-readable report for this run. Report files
     * land in the project root (guest bind = the same host directory), so
     * parsing is host-side with no path translation.
     */
    private fun parseOutcomes(
        hostFilePath: String,
        projectRoot: String?,
        language: Language,
        runStart: Long,
    ): List<TestOutputParsers.ParsedCase> {
        val hostRoot = projectRoot ?: File(hostFilePath).parent ?: return emptyList()
        return when (language) {
            Language.PYTHON -> {
                val f = File(hostRoot, ".codespace-test-result.xml")
                if (!f.exists()) return emptyList()
                val parsed = TestOutputParsers.parseJunitXml(f.readText(), File(hostFilePath).name)
                f.delete()
                parsed
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                val f = File(hostRoot, ".codespace-test-result.json")
                if (!f.exists()) return emptyList()
                val parsed = TestOutputParsers.parseJestJson(f.readText())
                f.delete()
                parsed
            }
            Language.KOTLIN, Language.JAVA -> {
                // Gradle writes build/test-results/test/TEST-*.xml; only files
                // from THIS run are parsed (older runs would replay stale ids).
                val dir = File(hostRoot, "build/test-results/test")
                val xmls = dir.listFiles()
                    ?.filter { it.name.startsWith("TEST-") && it.lastModified() >= runStart }
                    ?: return emptyList()
                xmls.flatMap { TestOutputParsers.parseJunitXml(it.readText(), File(hostFilePath).name) }
            }
            else -> emptyList()
        }
    }

    /** Maps a parsed case back to the F1 TestId shape for this file. */
    private fun mapTestId(hostFilePath: String, language: Language, case: TestOutputParsers.ParsedCase): String {
        return when (language) {
            Language.PYTHON -> hostFilePath + "/" + case.key
            Language.JAVASCRIPT, Language.TYPESCRIPT ->
                hostFilePath + "/" + (case.ancestors + case.key).joinToString("/")
            else -> {
                // JVM: "com.example.Outer$Corner" -> [Outer, Corner]; method "()"-stripped.
                val method = case.key.removeSuffix("()")
                val chain = case.className
                    ?.split('$')
                    ?.map { seg -> seg.substringAfterLast('.', seg) }
                    ?: emptyList()
                hostFilePath + "/" + (chain + method).joinToString("/")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-test command builders (TG02)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the ONE-TEST command for the language's runner, or null when the
     * language has no wired runner. The chain comes from the F1 TestId
     * ("<filePath>/<container chain>/<name>") minus the file prefix.
     */
    internal fun buildCommand(
        guestRoot: String,
        guestFile: String,
        hostFilePath: String,
        language: Language,
        testId: String,
        suite: Boolean,
    ): Pair<String, Long>? {
        val chain = chainOf(testId, hostFilePath)
        val leaf = chain.lastOrNull() ?: return null

        return when (language) {
            Language.PYTHON -> {
                // pytest node id: file::Class::method (suite) or file::method.
                // --junitxml into the project root (guest bind = host file) so
                // F3 parses per-test outcomes and failure locations.
                val node = (listOf(guestFile) + chain).joinToString("::")
                Pair(
                    "python3 -m pytest " + q(node) +
                        " -v --junitxml .codespace-test-result.xml",
                    180L,
                )
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                // jest -t matches the leaf name within THIS file's tests;
                // --json --outputFile lands in the project root for F3 parsing.
                Pair(
                    "npx jest " + q(guestFile) + " -t " + q(leaf) +
                        " --json --outputFile .codespace-test-result.json",
                    240L,
                )
            }
            Language.KOTLIN, Language.JAVA -> {
                val pattern = gradleTestPattern(hostFilePath, chain, suite) ?: return null
                val runner = "./gradlew"
                Pair(runner + " test --tests " + q(pattern) + " --no-daemon --console=plain 2>&1", 600L)
            }
            Language.DART -> {
                Pair("flutter test " + q(guestFile) + " --plain-name " + q(leaf), 300L)
            }
            else -> null
        }
    }

    /**
     * gradle --tests pattern from the file layout and the TestId chain.
     * src/test/java (or kotlin) layout yields the full FQCN
     * "pkg.Outer$Nested" (+ ".method" for a test); files outside a test
     * source set fall back to an any-package wildcard "*." prefix.
     */
    private fun gradleTestPattern(hostFilePath: String, chain: List<String>, suite: Boolean): String? {
        val containers = if (suite) chain else chain.dropLast(1)
        val classChain = containers.joinToString("$")
        if (classChain.isEmpty()) return null
        val leaf = chain.lastOrNull() ?: return null

        val normalized = hostFilePath.replace('\\', '/')
        val marker = listOf("/src/test/java/", "/src/test/kotlin/").firstOrNull { normalized.contains(it) }
        val fqcn = if (marker != null) {
            val after = normalized.substringAfter(marker)
            val pkg = after.substringBeforeLast('/').replace('/', '.')
            val dot = if (pkg.isEmpty()) "" else "."
            pkg + dot + classChain
        } else {
            // No test source set on the path — match any package.
            "*." + classChain
        }
        return if (suite) fqcn else fqcn + "." + leaf
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /** TestId chain after the file prefix: "SomeClass", "SomeClass/my test". */
    private fun chainOf(testId: String, hostFilePath: String): List<String> {
        if (testId.startsWith(hostFilePath)) {
            return testId.removePrefix(hostFilePath).trimStart('/')
                .split('/').filter { it.isNotEmpty() }
        }
        // Defensive: id without a matching file prefix — treat every segment
        // after the extension as the chain.
        return testId.split('/').filter { it.isNotEmpty() }.let { all ->
            val lastFileIdx = all.indexOfLast { it.endsWith(".kt") || it.endsWith(".java") ||
                it.endsWith(".py") || it.endsWith(".js") || it.endsWith(".ts") || it.endsWith(".dart") }
            if (lastFileIdx >= 0) all.drop(lastFileIdx + 1) else all
        }
    }

    private fun leafName(testId: String, hostFilePath: String): String =
        chainOf(testId, hostFilePath).lastOrNull() ?: testId.substringAfterLast('/')

    /** Single-quote shell escaping for interpolated command arguments. */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun log(msg: String) = AppOutputLog.log(msg, "test")

    private fun logResult(status: TestRunStatus, leaf: String, exit: Int?, durationMs: Long, launchError: String?) {
        val secs = "%.1f".format(durationMs / 1000.0)
        val line = when (status) {
            TestRunStatus.PASSED -> "[TestRun] \u2714 PASSED \u2014 " + leaf + " (exit 0, " + secs + "s)"
            TestRunStatus.FAILED -> "[TestRun] \u2718 FAILED \u2014 " + leaf + " (exit " + (exit ?: -1) + ", " + secs + "s) \u2014 see the runner output above"
            TestRunStatus.TIMED_OUT -> "[TestRun] \u23f1 TIMED OUT \u2014 " + leaf + " (killed after the runner timeout)"
            TestRunStatus.CANCELLED -> "[TestRun] \u2716 CANCELLED \u2014 " + leaf
            TestRunStatus.LAUNCH_FAILED -> "[TestRun] \u26a0 LAUNCH FAILED \u2014 " + leaf + " (" + (launchError ?: "unknown error") + ")"
            else -> "[TestRun] " + status.name + " \u2014 " + leaf
        }
        log(line)
    }
}
