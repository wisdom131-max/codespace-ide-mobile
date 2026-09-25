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
     * Debug capability for TEST lenses. False for every language until F5 wires
     * lens-debug routing through UniversalDebugManager (DG04 honesty rule: no
     * control may present a debug session that does not exist).
     */
    fun supportsDebug(language: Language): Boolean = false

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
            logResult(status, leaf, result.exitCode, durationMs, result.launchError)
            TestRunResult(testId, status, result.exitCode, durationMs)
        } finally {
            currentProcess = null
            active = false
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
                val node = (listOf(guestFile) + chain).joinToString("::")
                Pair("python3 -m pytest " + q(node) + " -v", 180L)
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                // jest -t matches the leaf name within THIS file's tests.
                Pair("npx jest " + q(guestFile) + " -t " + q(leaf), 240L)
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
