package com.codespace.ide.debug

import android.content.Context
import android.util.Log
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.environment.IdeEnvironment
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.codespace.ide.diagnostics.AppOutputLog

/**
 * F6-c: JvmDAPAdapter — debug JVM bytecode (Java sources) via the in-container
 * jdap driver (F6-a/F6-b: DapDriver on com.microsoft.java.debug.core 0.53.1,
 * /opt/jdap, launcher /usr/local/bin/jdap).
 *
 * Two modes, exactly like the Python/Node adapters before it:
 *  - FILE mode (no TestDebugSpec): compile the active .java with javac -g,
 *    spawn `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y` with
 *    /opt/jdap/jdap-evalhost.jar on the classpath (engine requirement), then
 *    attach jdap. This is the flow validated end-to-end by jdap-eval-test
 *    (line + conditional breakpoints, evaluation) on device, 7/7.
 *  - TEST mode (TestDebugSpec, runner "gradle"): spawn
 *    `./gradlew test --tests <pattern> --debug-jvm` — gradle SUSPENDS the test
 *    JVM waiting for a JDWP attach (default port 5005; actual port parsed from
 *    the "Listening for transport dt_socket at address: NNNN" line, with the
 *    default as fallback) — then attach jdap. Breakpoints/step/variables work;
 *    evaluation honestly reports the eval host not being on the gradle test
 *    worker's classpath (it cannot be injected from outside the build script).
 *
 * Launch sequence (mirrors PythonDAPAdapter + the sandbox-validated exchange):
 *   ensureJdap → preflight (jdap provisioned, JVM present) → [javac -g] →
 *   spawn debuggee (JDWP server=y suspend=y) → spawn jdap → DAPClient.start →
 *   initialize → attach → (initialized latch, warn-only) → setBreakpoints →
 *   configurationDone → stopped/stackTrace/scopes/variables/evaluate flow.
 */
class JvmDAPAdapter : DebugAdapter {

    override val id = "jvm-jdap"
    override val displayName = "Java (jdap)"

    private val TAG = "JvmDAPAdapter"

    // DG12 (P4b): ALL per-session runtime state lives in a map keyed by
    // DebugSession.id — never on the adapter singleton.
    private class SessionRuntime(val client: DAPClient) {
        @Volatile var lastExitCode: Int = 0
        @Volatile var threadId: Int = 1
        @Volatile var lastTotalFrames: Int = -1
        @Volatile var framesLoaded: Int = 0
        @Volatile var currentFrameId: Int = 0
        /** The debuggee WE spawned for this session (file mode: java, test mode: gradlew). */
        @Volatile var debuggee: Process? = null
    }
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, SessionRuntime>()
    private fun runtime(sessionId: String): SessionRuntime? = runtimes[sessionId]

    // DG02 (P3b): the live sendBreakpoints path needs the SAME host->guest
    // translation as launch — capture the launch context for it.
    @Volatile private var appContext: Context? = null
    private var caps: DAPCapabilities? = null

    override fun canDebug(language: Language, filePath: String) =
        language == Language.JAVA && filePath.endsWith(".java")

    override fun capabilities() = caps

    // ── Live breakpoint sync (UDM pushes gutter edits mid-session) ───────────

    override fun sendBreakpoints(session: DebugSession, breakpoints: List<DebugBreakpoint>): Boolean {
        val c = runtime(session.id)?.client ?: return false
        val ctx = appContext
        if (breakpoints.isEmpty()) {
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path",
                    if (ctx != null) DapPathMapper.toDapSourcePath(ctx, session.filePath) else session.filePath))
                put("breakpoints", JSONArray())
            }
            val resp = c.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
            if (resp == null) {
                AppOutputLog.log("[DAP] setBreakpoints (clear) failed for ${session.filePath.substringAfterLast("/")}", "lsp")
            }
            return resp != null
        }
        val bpsByFile = breakpoints.groupBy { it.filePath }
        var allOk = true
        for ((filePath, bps) in bpsByFile) {
            val dapPath = if (ctx != null) DapPathMapper.toDapSourcePath(ctx, filePath) else filePath
            val resp = c.request("setBreakpoints", buildSetBreakpointsArgs(dapPath, bps), timeoutSeconds = 5)
            if (resp == null) {
                AppOutputLog.log("[DAP] setBreakpoints failed for ${filePath.substringAfterLast("/")} — ${bps.size} breakpoint(s) not sent", "lsp")
                allOk = false
            } else {
                AppOutputLog.log("[DAP] setBreakpoints OK for ${filePath.substringAfterLast("/")} — ${bps.size} breakpoint(s) set", "lsp")
                markVerified(filePath, resp)
            }
        }
        return allOk
    }

    /** One setBreakpoints request body for a file's breakpoints (1-based lines). */
    private fun buildSetBreakpointsArgs(dapPath: String, bps: List<DebugBreakpoint>): JSONObject {
        return JSONObject().apply {
            put("source", JSONObject().put("name", File(dapPath).name).put("path", dapPath))
            put("breakpoints", JSONArray().also { arr ->
                bps.forEach { bp ->
                    arr.put(JSONObject().apply {
                        put("line", bp.line + 1) // DAP is 1-based, store is 0-based
                        if (bp.condition != null) put("condition", bp.condition)
                        if (bp.logMessage != null) put("logMessage", bp.logMessage)
                        if (bp.hitCondition != null) put("hitCondition", bp.hitCondition)
                    })
                }
            })
        }
    }

    /** P27-11: surface per-breakpoint verification from the DAP response. */
    private fun markVerified(filePath: String, resp: JSONObject) {
        val bpArray = resp.optJSONObject("body")?.optJSONArray("breakpoints") ?: return
        val verifiedMap = mutableMapOf<Int, Boolean>()
        for (j in 0 until bpArray.length()) {
            val bpInfo = bpArray.optJSONObject(j) ?: continue
            val dapLine = bpInfo.optInt("line", -1)
            val isVerified = bpInfo.optBoolean("verified", false)
            if (dapLine > 0) verifiedMap[dapLine] = isVerified
        }
        UniversalDebugManager.markBreakpointsVerified(filePath, verifiedMap)
    }

    // ── Preflight ───────────────────────────────────────────────────────────

    /** Honest pre-flight: the jdap bundle and a JVM must exist in the container. */
    private fun preflight(context: Context, onOutput: (String) -> Unit): Boolean {
        ProotInstaller.ensureJdap(context)
        val out = ProotInstaller.execOnce(context, "ls /opt/jdap/DapDriver.jar 2>/dev/null; command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1 || echo NO_JAVA", timeoutSeconds = 15)
        if (!out.contains("DapDriver.jar")) {
            onOutput("[jdap] The jdap driver is not present in the Ubuntu container - reinstall the app and open Ubuntu once to provision it.\n")
            return false
        }
        if (out.contains("NO_JAVA") || !out.contains("version")) {
            onOutput("[jdap] No JVM inside the Ubuntu container. Install one first: apt update && apt install -y openjdk-21-jdk-headless\n")
            return false
        }
        return true
    }

    // ── Debuggee spawn ───────────────────────────────────────────────────────

    /**
     * FILE mode: compile the active .java with javac -g (local-variable info
     * for conditionals/evaluation) and spawn it suspended under JDWP with the
     * eval host on the classpath. Returns (process, port) or null.
     */
    private fun spawnFileDebuggee(
        context: Context,
        session: DebugSession,
        port: Int,
        onOutput: (String) -> Unit,
    ): Process? {
        val guestPath = ProotInstaller.hostToGuestPath(context, session.filePath)
            ?: run {
                val filesDir = context.filesDir.absolutePath
                if (session.filePath.startsWith("$filesDir/")) {
                    "/host-files/" + session.filePath.removePrefix("$filesDir/")
                } else {
                    onOutput("[jdap] Cannot map this file into the Ubuntu container - debug refused.\n")
                    return null
                }
            }
        val guestDir = guestPath.substringBeforeLast('/')
        val baseName = File(guestPath).nameWithoutExtension // Java: public class name = file name

        // Compile with -g so breakpoints bind and evaluation sees locals.
        val compile = ProotInstaller.execOnce(context,
            "cd " + shQuote(guestDir) + " && javac -g " + shQuote(guestPath) + " 2>&1", timeoutSeconds = 60)
        if (compile.contains("error") || compile.contains("Error")) {
            onOutput("[jdap] javac failed - fix the compile errors, then debug again:\n")
            onOutput(compile.lines().takeLast(15).joinToString("\n") + "\n")
            return null
        }
        onOutput("[jdap] Compiled with debug info. Starting $baseName under the debugger...\n")

        val cp = shQuote(guestDir + ":/opt/jdap/jdap-evalhost.jar")
        val shellCommand = "cd " + shQuote(guestDir) + " && " +
            "java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port +
            " -cp " + cp + " " + shQuote(baseName) + " 2>&1"
        val proc = spawnShellProcess(context, shellCommand, onOutput,
            "[jdap] Failed to spawn the debuggee: ") ?: return null
        drainToConsole(proc, onOutput)
        return proc
    }

    /**
     * TEST mode: spawn gradle test --debug-jvm — gradle suspends the test JVM
     * waiting for a JDWP attach (default 5005). The port is parsed from the
     * "Listening for transport" line; gradle.properties may override it.
     */
    private fun spawnGradleTestDebuggee(
        context: Context,
        spec: TestDebugSpec,
        portOut: (Int) -> Unit,
        onOutput: (String) -> Unit,
    ): Process? {
        val guestWorkdir = spec.guestWorkdir
        if (guestWorkdir == null) {
            onOutput("[jdap] No project root to run gradle from - debug refused.\n")
            return null
        }
        if (spec.runner != "gradle") {
            onOutput("[jdap] Unsupported JVM test runner '" + spec.runner + "'.\n")
            return null
        }
        val args = spec.guestArgs.joinToString(" ") { shQuote(it) }
        val shellCommand = "cd " + shQuote(guestWorkdir) + " && ./gradlew " + args + " 2>&1"
        val proc = spawnShellProcess(context, shellCommand, onOutput,
            "[jdap] Failed to spawn gradle: ") ?: return null
        onOutput("[jdap] gradle starting - attaching the debugger when the test JVM listens...\n")

        // Stream output to the Debug Console AND parse the actual JDWP port from
        // the "Listening for transport" line. After parsing, keep draining to EOF
        // WITHOUT a deadline — closing the reader mid-run would fill the pipe and
        // deadlock a long gradle build.
        Thread {
            var parsed = false
            try {
                java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) onOutput(line + "\n")
                        if (!parsed && line.contains("Listening for transport dt_socket at address:")) {
                            line.substringAfterLast(':').trim().toIntOrNull()?.let { p ->
                                portOut(p)
                                parsed = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
        return proc
    }

    /** Common shell spawn: proot + bash -c, output drained to the Debug Console. */
    private fun spawnShellProcess(
        context: Context,
        shellCommand: String,
        onOutput: (String) -> Unit,
        failPrefix: String,
    ): Process? {
        val prootEnv = IdeEnvironment.forSubprocess(context)
        val proot = prootEnv.proot
        val headArgs = prootEnv.args.dropLast(2).toTypedArray()
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)
        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(true)
        IdeEnvironment.applyToProcessBuilder(pb, prootEnv.envVars)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            onOutput(failPrefix + (e.message ?: "spawn error") + "\n")
            return null
        }
        return proc
    }

    /** Drain a process's stdout to the Debug Console so a full pipe never blocks it. */
    private fun drainToConsole(proc: Process, onOutput: (String) -> Unit) {
        Thread {
            try {
                java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) onOutput(line + "\n")
                    }
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── Launch ───────────────────────────────────────────────────────────────

    override fun launch(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean {
        Log.d(TAG, "launch: ${session.filePath}")
        if (!preflight(context, onOutput)) return false

        val spec = session.testDebug // F5: JVM test-debug (gradle --debug-jvm)
        val debuggee: Process?
        val jdwpPort = java.util.concurrent.atomic.AtomicInteger(-1) // set by the gradle port-parse thread
        if (spec != null) {
            debuggee = spawnGradleTestDebuggee(context, spec, { p -> jdwpPort.set(p) }, onOutput)
            if (debuggee == null) return false
            // Give gradle time to reach the JDWP listen line; fall back to 5005.
            val deadline = System.currentTimeMillis() + 90_000
            while (jdwpPort.get() < 0 && System.currentTimeMillis() < deadline && debuggee.isAlive) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            if (jdwpPort.get() < 0) {
                jdwpPort.set(5005)
                onOutput("[jdap] No 'Listening for transport' line seen - assuming the default JDWP port 5005.\n")
            }
        } else {
            val port = try {
                ServerSocket(0).use { it.localPort } // free-port probe on shared loopback
            } catch (e: Exception) {
                onOutput("[jdap] Cannot allocate a JDWP port: ${e.message}\n")
                return false
            }
            jdwpPort.set(port)
            debuggee = spawnFileDebuggee(context, session, port, onOutput)
            if (debuggee == null) return false
        }

        // Spawn the jdap DAP driver (DAP over stdin/stdout; launcher caps its
        // JVM at -Xmx256m — F6-d captures the real two-JVM numbers).
        val prootEnv = IdeEnvironment.forSubprocess(context)
        val proot = prootEnv.proot
        val headArgs = prootEnv.args.dropLast(2).toTypedArray()
        // P32: banner suppression — profile output must never reach the DAP stream.
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec jdap"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)
        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(false)
        IdeEnvironment.applyToProcessBuilder(pb, prootEnv.envVars)
        val driver = try {
            pb.start()
        } catch (e: Exception) {
            onOutput("[jdap] Failed to spawn the jdap driver: ${e.message}\n")
            debuggee.destroyForcibly()
            return false
        }

        val dapClient = DAPClient(driver)
        val rt = SessionRuntime(dapClient)
        rt.debuggee = debuggee
        runtimes[session.id] = rt
        UniversalDebugManager.trackProcess(session.id, driver, "jdap", session.filePath.substringBeforeLast("/"))

        wireEvents(dapClient, rt, session.id, onOutput, onPaused, onStopped)

        val initializedLatch = CountDownLatch(1)
        dapClient.onEvent("initialized") { _ ->
            Log.d(TAG, "DAP initialized event received")
            initializedLatch.countDown()
        }

        dapClient.start()

        // 1. initialize
        val initArgs = JSONObject().apply {
            put("clientID", "codespace-ide")
            put("clientName", "VN Code")
            put("adapterID", "java")
            put("locale", "en-US")
            put("linesStartAt1", true)
            put("columnsStartAt1", true)
            put("pathFormat", "path")
            put("supportsVariableType", true)
            put("supportsRunInTerminalRequest", false)
        }
        val initResp = dapClient.request("initialize", initArgs, timeoutSeconds = 15)
        if (initResp == null) {
            onOutput("[jdap] initialize failed or timed out - is /opt/jdap present in the container?\n")
            teardown(rt, session.id)
            debuggee.destroyForcibly()
            return false
        }
        caps = initResp.toDAPCapabilities()

        // 2. attach (the proven jdap-eval-test exchange: attach to the JDWP
        // server, then configure breakpoints, then configurationDone).
        val attachArgs = JSONObject().apply {
            put("request", "attach")
            put("type", "java")
            put("name", if (spec != null) "Debug JVM Test (gradle)" else "Debug Java")
            put("hostName", "127.0.0.1")
            put("port", jdwpPort.get())
            put("projectName", File(session.filePath).name)
        }
        Log.d(TAG, "Sending DAP attach port=${jdwpPort.get()}")
        val attachResp = dapClient.request("attach", attachArgs, timeoutSeconds = 30)
        if (attachResp == null) {
            onOutput("[jdap] Attach to JDWP port ${jdwpPort.get()} failed - the debuggee may not be listening (see its output above).\n")
            teardown(rt, session.id)
            debuggee.destroyForcibly()
            return false
        }
        onOutput("[jdap] Attached to the debuggee (JDWP port ${jdwpPort.get()}).\n")

        // 2b. DAP spec: configure after 'initialized'. jdap's core sends it when
        // attach completes; warn-only so a missed event cannot block startup.
        if (!initializedLatch.await(10, TimeUnit.SECONDS)) {
            onOutput("[jdap] NOTE: 'initialized' event not seen within 10s - configuring anyway.\n")
            AppOutputLog.log("[DAP] NOTE: initialized event timeout (jvm) - configuring anyway", "lsp")
        }

        // 3. setBreakpoints (the sandbox-validated order: attach -> setBreakpoints
        // -> configurationDone; the initialized latch is warn-only because jdap
        // does not rely on it).
        appContext = context
        if (breakpoints.isNotEmpty()) {
            val bpsByFile = breakpoints.groupBy { it.filePath }
            for ((filePath, bps) in bpsByFile) {
                val bpGuestPath = DapPathMapper.toDapSourcePath(context, filePath)
                val bpResp = dapClient.request("setBreakpoints", buildSetBreakpointsArgs(bpGuestPath, bps), timeoutSeconds = 10)
                if (bpResp == null) {
                    AppOutputLog.log("[DAP] Initial setBreakpoints failed for ${filePath.substringAfterLast("/")}", "lsp")
                } else {
                    AppOutputLog.log("[DAP] Initial setBreakpoints OK for ${filePath.substringAfterLast("/")}: ${bps.size} breakpoint(s)", "lsp")
                    markVerified(filePath, bpResp)
                }
            }
        }

        // 4. configurationDone — the debuggee runs to the first hit.
        dapClient.sendRequest("configurationDone")

        onOutput("[jdap] Session started — running ${session.filePath.substringAfterLast("/")}\n")
        return true
    }

    /** Wire stopped/terminated/exited/output BEFORE client.start(). */
    private fun wireEvents(
        client: DAPClient,
        rt: SessionRuntime,
        sessionId: String,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ) {
        client.onEvent("output") { body ->
            val category = body.optString("category", "console")
            val text = body.optString("output", "")
            if (text.isNotBlank()) {
                val prefix = when (category) {
                    "stderr" -> "[stderr] "
                    "stdout" -> ""
                    else -> "[$category] "
                }
                onOutput(prefix + text)
            }
        }

        client.onEvent("stopped") { body ->
            rt.threadId = body.optInt("threadId", 1)
            val reason = body.optString("reason", "breakpoint")
            Log.d(TAG, "DAP stopped: reason=$reason threadId=${rt.threadId}")
            onOutput("[jdap] Paused: $reason\n")
            Thread {
                val frames = fetchStackFrames(client, rt.threadId, rt)
                val vars = if (frames.isNotEmpty()) {
                    rt.currentFrameId = frames.first().frameId
                    fetchVariables(client, rt.currentFrameId)
                } else emptyList()
                onPaused(frames, vars)
            }.also { it.isDaemon = true }.start()
        }

        client.onEvent("terminated") { _ ->
            Log.d(TAG, "DAP terminated")
            onOutput("[jdap] Session terminated.\n")
            onStopped(rt.lastExitCode)
            runtimes.remove(sessionId)
        }

        client.onEvent("exited") { body ->
            val code = body.optInt("exitCode", 0)
            onOutput("[jdap] Process exited with code $code\n")
            rt.lastExitCode = code
        }
    }

    private fun teardown(rt: SessionRuntime, sessionId: String) {
        runtimes.remove(sessionId)
        UniversalDebugManager.untrackProcess(sessionId)
        rt.client.stop()
    }

    // ── Control commands ────────────────────────────────────────────────────

    override fun stop(session: DebugSession) {
        val rt = runtimes.remove(session.id) ?: return
        // DG13 (P4b): DAP teardown BEFORE killing processes — the driver ends
        // the debuggee via disconnect(terminateDebuggee=true); destroyForcibly
        // is the belt-and-braces fallback for both JVMs.
        try {
            val resp = rt.client.request("disconnect", JSONObject().put("terminateDebuggee", true), timeoutSeconds = 3)
            if (resp == null) rt.client.sendRequest("terminate")
        } catch (_: Exception) {
        }
        rt.debuggee?.destroyForcibly()
        rt.debuggee = null
        rt.client.stop()
    }

    override fun pause(session: DebugSession) {
        val rt = runtime(session.id) ?: return
        rt.client.sendRequest("pause", JSONObject().put("threadId", rt.threadId))
    }

    override fun resume(session: DebugSession) {
        val rt = runtime(session.id) ?: return
        rt.client.sendRequest("continue", JSONObject().put("threadId", rt.threadId))
    }

    override fun stepOver(session: DebugSession) {
        val rt = runtime(session.id) ?: return
        rt.client.sendRequest("next", JSONObject().put("threadId", rt.threadId))
    }

    override fun stepInto(session: DebugSession) {
        val rt = runtime(session.id) ?: return
        rt.client.sendRequest("stepIn", JSONObject().put("threadId", rt.threadId))
    }

    override fun stepOut(session: DebugSession) {
        val rt = runtime(session.id) ?: return
        rt.client.sendRequest("stepOut", JSONObject().put("threadId", rt.threadId))
    }

    override fun evaluate(session: DebugSession, expression: String, frameId: Int): String? {
        val rt = runtime(session.id) ?: return null
        val args = JSONObject().apply {
            put("expression", expression)
            put("frameId", if (frameId > 0) frameId else rt.currentFrameId)
            put("context", "repl")
        }
        val resp = rt.client.request("evaluate", args, timeoutSeconds = 10) ?: return null
        return if (resp.has("result") && !resp.isNull("result")) resp.getString("result") else null
    }

    // ── Threads / paging / variables ─────────────────────────────────────────

    override fun getThreads(session: DebugSession): List<DebugThread> {
        val rt = runtime(session.id) ?: return emptyList()
        val resp = rt.client.request("threads", timeoutSeconds = 5) ?: return emptyList()
        val arr = resp.optJSONArray("threads") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optInt("id", -1)
            DebugThread(id = id, name = t.optString("name", "Thread $id"), active = id == rt.threadId)
        }
    }

    override fun switchThread(session: DebugSession, newThreadId: Int): List<DebugStackFrame> {
        val rt = runtime(session.id) ?: return emptyList()
        rt.threadId = newThreadId
        return fetchStackFrames(rt.client, newThreadId, rt)
    }

    override fun loadMoreFrames(session: DebugSession): Pair<List<DebugStackFrame>, Int> {
        val rt = runtime(session.id) ?: return Pair(emptyList(), -1)
        if (rt.lastTotalFrames in 0..rt.framesLoaded) return Pair(emptyList(), rt.lastTotalFrames)
        val args = JSONObject().put("threadId", rt.threadId).put("startFrame", rt.framesLoaded).put("levels", 20)
        val resp = rt.client.request("stackTrace", args, timeoutSeconds = 5) ?: return Pair(emptyList(), rt.lastTotalFrames)
        val framesArr = resp.optJSONArray("stackFrames") ?: return Pair(emptyList(), rt.lastTotalFrames)
        if (framesArr.length() == 0) return Pair(emptyList(), rt.lastTotalFrames)
        rt.lastTotalFrames = resp.optInt("totalFrames", rt.framesLoaded + framesArr.length())
        val parsed = (0 until framesArr.length()).map { i -> parseFrame(framesArr.optJSONObject(i) ?: JSONObject(), rt.framesLoaded + i) }
        rt.framesLoaded += framesArr.length()
        return Pair(parsed, rt.lastTotalFrames)
    }

    override fun getVariables(session: DebugSession, variablesReference: Int): List<DebugVariable> {
        if (variablesReference == 0) return emptyList()
        val dapClient = runtime(session.id)?.client ?: return emptyList()
        val varResp = dapClient.request("variables",
            JSONObject().put("variablesReference", variablesReference).put("count", 100),
            timeoutSeconds = 5) ?: return emptyList()
        val vars = varResp.optJSONArray("variables") ?: return emptyList()
        val result = mutableListOf<DebugVariable>()
        for (j in 0 until vars.length()) {
            val v = vars.getJSONObject(j)
            val varRef = v.optInt("variablesReference", 0)
            result += DebugVariable(
                name = v.optString("name", "?"),
                type = v.optString("type", "Object"),
                value = v.optString("value", ""),
                depth = 0,
                expandable = varRef > 0,
                variablesReference = varRef,
                scopeName = v.optString("name", "Variables"),
                containerRef = variablesReference,
            )
        }
        return result
    }

    // ── Stack frame / variable helpers ──────────────────────────────────────

    private fun fetchStackFrames(client: DAPClient, threadId: Int, rt: SessionRuntime): List<DebugStackFrame> {
        val args = JSONObject().put("threadId", threadId).put("startFrame", 0).put("levels", 20)
        val resp = client.request("stackTrace", args, timeoutSeconds = 5) ?: return emptyList()
        val framesArr = resp.optJSONArray("stackFrames") ?: return emptyList()
        rt.lastTotalFrames = resp.optInt("totalFrames", framesArr.length())
        rt.framesLoaded = framesArr.length()
        val result = mutableListOf<DebugStackFrame>()
        for (i in 0 until framesArr.length()) {
            result += parseFrame(framesArr.optJSONObject(i) ?: JSONObject(), i)
        }
        return result
    }

    private fun parseFrame(f: JSONObject, index: Int): DebugStackFrame {
        val frameId = f.optInt("id", 0)
        val src = f.optJSONObject("source")
        val path = src?.optString("path", "") ?: ""
        return DebugStackFrame(
            function = f.optString("name", "<unknown>"),
            file = path,
            line = f.optInt("line", 0) - 1, // DAP 1-based -> store 0-based
            active = index == 0,
            frameId = frameId,
        )
    }

    private fun fetchVariables(client: DAPClient, frameId: Int): List<DebugVariable> {
        val scopesResp = client.request("scopes", JSONObject().put("frameId", frameId), timeoutSeconds = 5)
            ?: return emptyList()
        val scopesArr = scopesResp.optJSONArray("scopes") ?: return emptyList()
        val result = mutableListOf<DebugVariable>()
        for (i in 0 until minOf(scopesArr.length(), 3)) {
            val scope = scopesArr.getJSONObject(i)
            val scopeName = scope.optString("name", "Variables")
            val ref = scope.optInt("variablesReference", 0)
            if (ref == 0) continue
            if (scope.optBoolean("expensive", false)) continue
            val varResp = client.request("variables",
                JSONObject().put("variablesReference", ref).put("count", 100),
                timeoutSeconds = 5) ?: continue
            val vars = varResp.optJSONArray("variables") ?: continue
            for (j in 0 until vars.length()) {
                val v = vars.getJSONObject(j)
                val varRef = v.optInt("variablesReference", 0)
                result += DebugVariable(
                    name = v.optString("name", "?"),
                    type = v.optString("type", scopeName),
                    value = v.optString("value", ""),
                    depth = 0,
                    expandable = varRef > 0,
                    variablesReference = varRef,
                    scopeName = scopeName,
                    containerRef = ref,
                )
            }
        }
        return result
    }

    /** TP11-style single-quote shell escaping for guest paths/args. */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
