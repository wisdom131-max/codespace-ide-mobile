package com.codespace.ide.debug

import android.content.Context
import android.util.Log
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.environment.IdeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import com.codespace.ide.diagnostics.AppOutputLog
import org.json.JSONObject

/** F6-d/JS: one staged install at a time, process-wide (shared across adapter instances). */
private val jsDebugInstallInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * P26-3a: NodeDAPAdapter — debug JavaScript/TypeScript via DAP.
 *
 * Uses @vscode/js-debug (the VS Code JS debugger, runs as a standalone DAP adapter)
 * inside the proot Ubuntu environment. Falls back to the legacy NodeJsDebugProvider
 * (node inspect / node --inspect-brk) when js-debug is unavailable.
 *
 * Install: npm install -g @vscode/js-debug
 * Launch:  node <js-debug>/src/dapDebugServer.js   (listens on a port) — HOWEVER,
 *          we use the stdin/stdout mode via a small adapter shim so DAPClient's
 *          existing Content-Length framing works without TCP sockets:
 *              node <js-debug>/src/dapDebugServer.js --stdio
 *
 * P26-3b: Attach Mode — attach to a running Node process by pid or port.
 *          Supported: attach request with hostName/port or processId.
 *          UI: "Attach…" button in the debugger panel shows a port/PID picker.
 *
 * P26-3c: Capability Negotiation — initialize reads the response capabilities
 *          and exposes them via capabilities() so the UI can hide unsupported actions.
 *
 * Session lifecycle (launch):
 *   1. Check @vscode/js-debug installed  (npm list -g @vscode/js-debug)
 *   2. If not: npm install -g @vscode/js-debug  (skip on timeout, fall through to legacy)
 *   3. Locate dapDebugServer.js in global npm prefix
 *   4. Spawn via proot: node <path>/src/dapDebugServer.js --stdio
 *   5. DAPClient.start()
 *   6. initialize → launch/attach → wait 'initialized' → setBreakpoints → configurationDone
 *   7. Wire stopped/output/terminated events → callbacks
 */
class NodeDAPAdapter : DebugAdapter {

    override val id = "node-dap"
    override val displayName = "Node.js (js-debug)"

    private val TAG = "NodeDAPAdapter"

    // DG12 (P4b): this adapter used to hold ONE mutable client field (plus
    // shared thread/frame/test-process state) on its single registered
    // instance — a second JS/TS session OVERWROTE it: stop(A) killed B's
    // client and pause/step/evaluate routed to whichever session launched
    // last. All per-session runtime state now lives in a map keyed by
    // DebugSession.id; every event closure closes over its own runtime and
    // stop(session) tears down only that session's client + test process.
    private class SessionRuntime(val client: DAPClient) {
        // P27-10: Track process exit code for crash detection
        @Volatile var lastExitCode: Int = 0
        // F5 (TG07p1): the jest test-debuggee spawned under node --inspect-brk —
        // the DAP session ATTACHES to it; stop() must kill it too.
        @Volatile var testProcess: Process? = null
        @Volatile var testInspectPort: Int = 9229
        @Volatile var threadId: Int = 1
        // P2-PAGING: totalFrames from last stackTrace response (-1 = unknown); frames loaded so far
        @Volatile var lastTotalFrames: Int = -1
        @Volatile var framesLoaded: Int = 0
        @Volatile var currentFrameId: Int = 0
    }
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, SessionRuntime>()
    private fun runtime(sessionId: String): SessionRuntime? = runtimes[sessionId]

    // DG02 (P3b): live sendBreakpoints needs the SAME host->guest translation
    // as launchInternal — capture the launch/attach context for it.
    @Volatile private var appContext: Context? = null
    private var caps: DAPCapabilities? = null

    override fun canDebug(language: Language, filePath: String) =
        language == Language.JAVASCRIPT ||
        language == Language.TYPESCRIPT ||
        filePath.endsWith(".js") || filePath.endsWith(".mjs") ||
        filePath.endsWith(".cjs") || filePath.endsWith(".ts")

    override fun capabilities() = caps

    /**
     * P32-BREAKPOINT-FIX: Send updated breakpoints to js-debug during a running session.
     * Called by UDM when breakpoints change while debugging is active.
     */
    override fun sendBreakpoints(session: DebugSession, breakpoints: List<DebugBreakpoint>): Boolean {
        val c = runtime(session.id)?.client ?: return false
        val ctx = appContext
        val bpsByFile = if (breakpoints.isEmpty()) {
            // DG02: clear-all also goes through the shared host->guest mapper.
            mapOf((if (ctx != null) DapPathMapper.toDapSourcePath(ctx, session.filePath) else session.filePath)
                to emptyList<DebugBreakpoint>())
        } else {
            breakpoints.groupBy { it.filePath }
        }

        var allOk = true
        bpsByFile.forEach { (filePath, bps) ->
            // DG02 FIX: the old "already a guest path" claim was FALSE — UDM's
            // breakpoint store is HOST-keyed, so live sends used raw host paths
            // while launch used guest paths. Translate with the shared mapper.
            val guestPath = if (ctx != null) DapPathMapper.toDapSourcePath(ctx, filePath) else filePath
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path", guestPath))
                put("breakpoints", JSONArray().apply {
                    bps.forEach { bp ->
                        put(JSONObject().apply {
                            put("line", bp.line + 1)
                            if (bp.condition != null) put("condition", bp.condition)
                            if (bp.logMessage != null) put("logMessage", bp.logMessage)
                            if (bp.hitCondition != null) put("hitCondition", bp.hitCondition)
                        })
                    }
                })
            }
            val resp = c.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
            if (resp == null) {
                AppOutputLog.log("[DAP] setBreakpoints failed for ${filePath.substringAfterLast("/")}", "debug")
                allOk = false
            } else {
                AppOutputLog.log("[DAP] setBreakpoints OK for ${filePath.substringAfterLast("/")}: ${bps.size} breakpoint(s)", "debug")
            }
        }
        return allOk
    }

    // ── Installation ──────────────────────────────────────────────────

    fun isJsDebugInstalled(context: Context, workDir: String? = null): Boolean {
        // JS-DEBUG-VENDOR: @vscode/js-debug is NOT on the npm registry (404 confirmed
        // on-device twice) — health is now the vendored tarball's extracted entry
        // point at /opt/js-debug/src/dapDebugServer.js.
        val out = ProotInstaller.execOnce(context,
            "test -f /opt/js-debug/src/dapDebugServer.js && echo INSTALLED || echo NOT_FOUND",
            workdir = workDir, timeoutSeconds = 15)
        return "INSTALLED" in out
    }

    /**
     * F6-d/JS: STAGED install. The old one-shot chain (apt update + apt install
     * nodejs+npm + npm install, all inside a single 180s window) could not
     * finish under proot — and every retry restarted the WHOLE chain from
     * scratch. Each stage now checks the REAL on-disk state first and runs only
     * what is missing, so a retry (or an install killed at ANY point, including
     * by the old 180s cap) RESUMES: the on-disk state IS the marker — no
     * separate marker files that can drift out of sync. Per-stage timeouts are
     * sized for proot reality (apt index 300s, node+npm unpack 600s, npm fetch
     * 300s). Progress streams to the Output tab; a concurrent call only checks
     * status (never double-runs the chain).
     */
    fun installJsDebug(context: Context, workDir: String? = null): Boolean {
        if (!jsDebugInstallInFlight.compareAndSet(false, true)) {
            AppOutputLog.log("[JS-DEBUG] staged install already running elsewhere — this call only checked status", "debug")
            return isJsDebugInstalled(context, workDir)
        }
        try {
            // Resume gate: if a previous partial run already delivered node+npm,
            // the two apt stages are skipped entirely.
            val nodePresent = { s: String -> "NODE_READY" in s }
            val nodeProbe = "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && echo NODE_READY || echo NODE_MISSING"
            if (nodePresent(ProotInstaller.execOnce(context, nodeProbe, workdir = workDir, timeoutSeconds = 15))) {
                AppOutputLog.log("[JS-DEBUG] node+npm already present — skipping the apt stages (resumed or pre-installed).", "debug")
            } else {
                AppOutputLog.log("[JS-DEBUG] stage 1/3: apt-get update (1-2 min under proot)…", "debug")
                ProotInstaller.execOnce(context, "apt-get update -qq", timeoutSeconds = 300)
                AppOutputLog.log("[JS-DEBUG] stage 2/3: apt install nodejs+npm (largest stage — up to 10 min under proot)…", "debug")
                // dpkg --configure -a first: an install killed mid-apt can leave
                // dpkg with pending configure/lock state that blocks the retry.
                val aptOut = ProotInstaller.execOnce(context,
                    "dpkg --configure -a 2>/dev/null; " +
                    "apt-get install -y --no-install-recommends nodejs npm 2>&1 | tail -5",
                    timeoutSeconds = 600)
                if (!nodePresent(ProotInstaller.execOnce(context, nodeProbe, workdir = workDir, timeoutSeconds = 15))) {
                    // SELF-HEAL (2026-10-03, owner-approved): second genuine source for
                    // node+npm — the same NodeSource chain the LSP installs use (it
                    // exists precisely because Ubuntu apt's nodejs is regularly wedged
                    // by the libnode115 conflict). Purge the broken apt install first,
                    // then NodeSource Node 20. Only fail when BOTH methods fail.
                    AppOutputLog.log("[JS-DEBUG] stage 2/3: apt node/npm failed — trying NodeSource fallback (purge + setup_20.x)…", "debug")
                    ProotInstaller.execOnce(context,
                        "dpkg --configure -a 2>/dev/null; " +
                        "apt-get install -f -y 2>/dev/null; " +
                        "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                        "apt-get autoremove -y 2>/dev/null; " +
                        "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                        "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                        "apt-get install -y nodejs",
                        timeoutSeconds = 600)
                    if (!nodePresent(ProotInstaller.execOnce(context, nodeProbe, workdir = workDir, timeoutSeconds = 15))) {
                        AppOutputLog.log("[JS-DEBUG] stage 2/3 FAILED — node/npm still absent after apt AND NodeSource. Last apt lines: " + aptOut.takeLast(300), "debug")
                        return false
                    }
                    AppOutputLog.log("[JS-DEBUG] stage 2/3: NodeSource fallback delivered node+npm.", "debug")
                }
            }
            // JS-DEBUG-VENDOR (npm 404 fix): @vscode/js-debug is only published as a
            // GitHub release tarball, vendored in APK assets like jdap. Stage 3 is now a
            // local bundle extraction — no registry fetch, seconds not minutes.
            AppOutputLog.log("[JS-DEBUG] stage 3/3: provisioning @vscode/js-debug from the vendored tarball (npm-registry 404 fix)…", "debug")
            val ensureDiag = ProotInstaller.ensureJsDebug(context)
            val ok = isJsDebugInstalled(context, workDir)
            if (ok) {
                AppOutputLog.log("[JS-DEBUG] install complete — js-debug is healthy at /opt/js-debug. Press Debug again.", "debug")
            } else {
                AppOutputLog.log("[JS-DEBUG] stage 3/3 FAILED — vendored extraction did not yield /opt/js-debug/src/dapDebugServer.js. ensureJsDebug diagnostic: $ensureDiag", "debug")
            }
            return ok
        } finally {
            jsDebugInstallInFlight.set(false)
        }
    }

    /** Find the dapDebugServer.js entry point in the global npm prefix. */
    private fun findDapServerPath(context: Context): String? {
        val out = ProotInstaller.execOnce(context,
            "[ -f /opt/js-debug/src/dapDebugServer.js ] && echo /opt/js-debug/src/dapDebugServer.js || " +
            "(node -e 'const p=require.resolve(\"@vscode/js-debug/src/dapDebugServer\"); console.log(p)' 2>/dev/null " +
            "|| find \$(npm root -g 2>/dev/null) -name 'dapDebugServer.js' -maxdepth 5 2>/dev/null | head -1)",
            timeoutSeconds = 10)
        val path = out.trim().lines().firstOrNull { it.endsWith(".js") }
        Log.d(TAG, "dapDebugServer.js path: $path")
        return path?.takeIf { it.isNotBlank() }
    }

    // ── Launch ────────────────────────────────────────────────────────

    override fun launch(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean {
        Log.d(TAG, "launch: ${session.filePath}")
        // F5 (TG07p1): a test-debug session spawns the runner under
        // node --inspect-brk and ATTACHES to it — the existing attach path
        // (P26-3b) then carries breakpoints, pause, step and variables.
        val debugSpec = session.testDebug
        if (debugSpec != null) {
            // DEBUG-TEST-SELF-HEAL (2026-10-03): ensure js-debug BEFORE spawning the
            // debuggee — previously the test path spawned jest first and the js-debug
            // install check only ran inside launchInternal, so a missing debugger
            // wasted a spawn and the message arrived after the process was killed.
            if (!isJsDebugInstalled(context, debugSpec.hostWorkdir ?: debugSpec.guestWorkdir)) {
                onOutput("[js-debug] js-debug missing - starting the staged install. Press Debug Test again once it completes (progress is in the Output tab).\n")
                Thread { installJsDebug(context) }.also { it.isDaemon = true }.start()
                return false
            }
            // DG12: the spawned debuggee belongs to THIS session's runtime, not
            // the adapter singleton — spawn first, remember the port, and hand
            // both to launchInternal via the per-session runtime it creates.
            val spawned = spawnJestTest(context, debugSpec, onOutput) ?: return false
            val ok = launchInternal(
                context, session, breakpoints, onOutput, onPaused, onStopped,
                attachParams = JSONObject()
                    .put("port", spawned.second)
                    .put("address", "127.0.0.1"),
                preSpawned = spawned,
            )
            if (!ok) {
                // DG12: launch failed after the debuggee spawned — kill it now;
                // a per-session runtime only exists once launchInternal connects.
                onOutput("[js-debug] Launch failed — stopping the spawned test process.\n")
                spawned.first.destroyForcibly()
            }
            return ok
        }
        return launchInternal(
            context, session, breakpoints, onOutput, onPaused, onStopped,
            attachParams = null
        )
    }

    /**
     * F5 (TG07p1): spawns jest for ONE test under node --inspect-brk in the
     * proot environment. The process waits for the inspector attach before
     * running anything, so the DAP handshake + setBreakpoints complete first.
     * Output is drained (a full pipe would deadlock the runner) and streamed
     * to the Debug Console.
     */
    /** DG12: returns (process, inspectPort) — both belong to the CALLING session. */
    /** POSIX single-quote (guest workdirs contain spaces — "My codespace app 3"). */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun spawnJestTest(
        context: Context,
        spec: TestDebugSpec,
        onOutput: (String) -> Unit,
    ): Pair<Process, Int>? {
        val hostWorkdir = spec.hostWorkdir
        val jestPresent = hostWorkdir != null && java.io.File(hostWorkdir, "node_modules/.bin/jest").exists()
        // DEBUG-TEST-SELF-HEAL (2026-10-03, owner-directed): the Run path auto-fetches
        // jest via npx, but Debug used to hard-refuse with a manual "npm install"
        // instruction — the one Debug Test path that broke the auto-everything pattern.
        // Debugging needs the LOCAL binary (node_modules/.bin/jest runs node directly
        // under --inspect-brk; an npx wrapper spawns its own node the inspector cannot
        // see), so we install jest INTO the project automatically instead of asking.
        if (!jestPresent && hostWorkdir != null && spec.guestWorkdir != null) {
            onOutput("[js-debug] jest is not installed under node_modules in this project - installing it automatically (npm install --save-dev jest, one-time)...\n")
            val npmOut = ProotInstaller.execOnce(context,
                "cd " + shQuote(spec.guestWorkdir) + " && npm install --save-dev jest 2>&1 | tail -4",
                timeoutSeconds = 300, logToOutput = true)
            if (java.io.File(hostWorkdir, "node_modules/.bin/jest").exists()) {
                onOutput("[js-debug] jest installed into this project.\n")
            } else {
                onOutput("[js-debug] jest auto-install failed. Tail:\n" + npmOut.takeLast(300) + "\n[js-debug] Debug needs the local jest binary; Run still works via npx.\n")
                return null
            }
        } else if (!jestPresent) {
            onOutput("[js-debug] Cannot locate this project's working directory - cannot debug its tests. Run still works via npx.\n")
            return null
        }
        val guestWorkdir = spec.guestWorkdir
        if (guestWorkdir == null) {
            onOutput("[js-debug] No project root to run jest from - debug refused.\n")
            return null
        }

        // Free-port probe on the shared loopback (proot shares the Android
        // network namespace, so the inspector port is device-loopback too).
        val port = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            onOutput("[js-debug] Cannot allocate an inspector port: ${e.message}\n")
            return null
        }
        val prootEnv = IdeEnvironment.forSubprocess(context)
        val proot = prootEnv.proot
        val headArgs = prootEnv.args.dropLast(2).toTypedArray()
        val jestArgs = spec.guestArgs.joinToString(" ") { "'" + it.replace("'", "'\''") + "'" }
        val shellCommand = "cd '" + guestWorkdir.replace("'", "'\''") + "' && " +
            "node --inspect-brk=127.0.0.1:" + port + " node_modules/.bin/jest " + jestArgs +
            " 2>&1"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)
        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(true)
        IdeEnvironment.applyToProcessBuilder(pb, prootEnv.envVars)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            onOutput("[js-debug] Failed to spawn jest under the debugger: ${e.message}\n")
            return null
        }
        onOutput("[js-debug] jest starting under node --inspect-brk (port $port) - attaching debugger...\n")
        onOutput("[js-debug] After attach, tap Continue in the Debug Console to run the test.\n")

        // Drain the debuggee's output so a full pipe never blocks the runner.
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
        return Pair(proc, port)
    }

    /**
     * P26-3b: Attach to a running Node.js process.
     *
     * @param context Android context.
     * @param session DebugSession to manage this attach session.
     * @param port    Localhost port the Node process is listening on (--inspect / --inspect-brk).
     *                Typically 9229. Pass -1 to attach by PID instead.
     * @param pid     Process ID to attach to. Used only when port == -1.
     */
    fun attach(
        context: Context,
        session: DebugSession,
        port: Int = 9229,
        pid: Int = -1,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean {
        Log.d(TAG, "attach: port=$port pid=$pid file=${session.filePath}")
        val attachParams = if (pid > 0) {
            JSONObject().put("processId", pid)
        } else {
            JSONObject().put("port", port).put("address", "127.0.0.1")
        }
        return launchInternal(
            context, session, emptyList(), onOutput, onPaused, onStopped,
            attachParams = attachParams
        )
    }

    private fun launchInternal(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
        attachParams: JSONObject?,
        preSpawned: Pair<Process, Int>? = null,
    ): Boolean {
        // 1. js-debug must be present before launch. F6-d/JS: the staged
        // install can legitimately take minutes under proot — never block a
        // debug tap on it. If it is mid-flight, say so; if idle, kick it in
        // the background and let the user press Debug again once Output
        // reports completion.
        if (!isJsDebugInstalled(context)) {
            if (jsDebugInstallInFlight.get()) {
                onOutput("[js-debug] @vscode/js-debug is still installing (staged install in flight — see the Output tab for stage progress).\n")
            } else {
                onOutput("[js-debug] @vscode/js-debug is not installed — starting the staged install in the background now (progress in the Output tab). Press Debug again once it reports complete.\n")
                Thread { installJsDebug(context) }.also { it.isDaemon = true }.start()
            }
            return false
        }

        // 2. Locate dapDebugServer.js
        val serverPath = findDapServerPath(context)
        if (serverPath == null) {
            onOutput("[js-debug] Cannot locate dapDebugServer.js. Falling back to legacy.\n")
            return false
        }

        // 3. Map host script path to proot guest path
        val guestScriptPath = if (attachParams != null) {
            // Attach mode — no script path needed for launch, but still resolve for breakpoints
            session.filePath.let { hp ->
                val filesDir = context.filesDir.absolutePath
                if (hp.startsWith("$filesDir/")) "/host-files/" + hp.removePrefix("$filesDir/")
                else ProotInstaller.hostToGuestPath(context, hp) ?: hp
            }
        } else {
            val filesDir = context.filesDir.absolutePath
            if (session.filePath.startsWith("$filesDir/")) {
                "/host-files/" + session.filePath.removePrefix("$filesDir/")
            } else {
                ProotInstaller.hostToGuestPath(context, session.filePath)
                    ?: run {
                        onOutput("[js-debug] Cannot map script path to proot guest path.\n")
                        return false
                    }
            }
        }

        // 4. Spawn: node <dapDebugServer.js> --stdio
        // Gap 1: Use IdeEnvironment.forSubprocess — central env config.
        val prootEnv = IdeEnvironment.forSubprocess(context)
        val proot = prootEnv.proot
        val envVars = prootEnv.envVars
        val headArgs = prootEnv.args.dropLast(2).toTypedArray()
        val serverCmd = "node '$serverPath' --stdio"
        // P32: Use bash -c (non-login) with profile sourcing redirected to /dev/null.
        // Same fix as LSP startServer — prevents [Agent] banner text from corrupting
        // the DAP JSON-RPC stream on stdout.
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec $serverCmd"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)

        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(false)
        IdeEnvironment.applyToProcessBuilder(pb, envVars)

        Log.d(TAG, "Spawning js-debug DAP server: $serverCmd")
        onOutput("[js-debug] Starting DAP server (${if (attachParams != null) "attach" else "launch"} mode)...\n")

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn js-debug: ${e.message}")
            onOutput("[js-debug] Spawn failed: ${e.message}\n")
            return false
        }

        // 5. Create and start DAPClient
        val dapClient = DAPClient(process)
        // DG12: per-session runtime — the client and ALL its mutable state are
        // keyed by session id from here on.
        val rt = SessionRuntime(dapClient)
        if (preSpawned != null) {
            // F5 (TG07p1): the jest debuggee spawned by launch() for THIS session.
            rt.testProcess = preSpawned.first
            rt.testInspectPort = preSpawned.second
        }
        runtimes[session.id] = rt
        // DG13 (P4b): register the DAP server process with the ProcessTracker
        // (previously dead code with zero callers).
        UniversalDebugManager.trackProcess(session.id, process, "js-debug (node)",
            session.filePath.substringBeforeLast("/"))

        // Wire events BEFORE start() so no race
        dapClient.onEvent("output") { body ->
            val category = body.optString("category", "console")
            val text = body.optString("output", "")
            if (text.isNotBlank()) {
                val prefix = when (category) {
                    "stderr" -> "[stderr] "
                    "stdout" -> ""
                    "console" -> ""
                    else -> "[$category] "
                }
                onOutput(prefix + text)
            }
        }

        dapClient.onEvent("stopped") { body ->
            rt.threadId = body.optInt("threadId", 1)
            val reason = body.optString("reason", "breakpoint")
            Log.d(TAG, "DAP stopped: reason=$reason threadId=${rt.threadId}")
            onOutput("[js-debug] Paused: $reason\n")

            Thread {
                val frames = fetchStackFrames(dapClient, rt.threadId, rt)
                val frameId = if (frames.isNotEmpty()) frames.first().frameId else 0
                rt.currentFrameId = frameId
                val vars = fetchVariables(dapClient, frameId)
                onPaused(frames, vars)
            }.also { it.isDaemon = true }.start()
        }

        dapClient.onEvent("terminated") { _ ->
            Log.d(TAG, "DAP terminated")
            onOutput("[js-debug] Session terminated.\n")
            onStopped(rt.lastExitCode)
            runtimes.remove(session.id)  // DG12: only THIS session's runtime
        }

        dapClient.onEvent("exited") { body ->
            val code = body.optInt("exitCode", 0)
            onOutput("[js-debug] Process exited with code $code.\n")

            rt.lastExitCode = code  // P27-10
        }

        dapClient.onEvent("thread") { body ->
            val reason = body.optString("reason", "")
            val tid = body.optInt("threadId", -1)
            Log.d(TAG, "DAP thread: reason=$reason tid=$tid")
        }

        // P32-DAP-ORDER: Register initialized event handler BEFORE start().
        // DAP spec: setBreakpoints should be sent after the 'initialized' event,
        // not before launch. Some adapters (debugpy) require launch first; others
        // (js-debug) send initialized after initialize. To handle both, we:
        //   1. Send launch/attach
        //   2. Wait for initialized event
        //   3. Send setBreakpoints
        //   4. Send configurationDone
        val initializedLatch = CountDownLatch(1)
        dapClient.onEvent("initialized") { _ ->
            Log.d(TAG, "DAP initialized event received — ready for configuration")
            initializedLatch.countDown()
        }

        dapClient.start()

        // 6. initialize
        val initArgs = JSONObject().apply {
            put("clientID", "codespace-ide")
            put("clientName", "VN Code")
            put("adapterID", "node")
            put("locale", "en-US")
            put("linesStartAt1", true)
            put("columnsStartAt1", true)
            put("pathFormat", "path")
            put("supportsVariableType", true)
            put("supportsVariablePaging", false)
            put("supportsRunInTerminalRequest", false)
            put("supportsMemoryReferences", false)
        }

        Log.d(TAG, "Sending initialize...")
        val initResp = dapClient.request("initialize", initArgs, timeoutSeconds = 15)
        if (initResp == null) {
            onOutput("[js-debug] initialize timed out (15s). Is Node.js installed?\n")
            dapClient.stop()
            runtimes.remove(session.id)  // DG12: no orphan runtime on failed launch
            UniversalDebugManager.untrackProcess(session.id)  // DG13
            return false
        }

        // P26-3c: Capability negotiation
        caps = initResp.toDAPCapabilities()
        Log.d(TAG, "js-debug capabilities: $caps")
        onOutput("[js-debug] Capabilities negotiated. configDone=${caps?.supportsConfigurationDoneRequest}\n")

        // 7. launch or attach (BEFORE setBreakpoints — the adapter needs to start
        // the debuggee before it can accept breakpoint configuration)
        if (attachParams != null) {
            // Attach mode
            val args = JSONObject().apply {
                put("type", "node")
                put("request", "attach")
                put("name", "Attach to Node.js")
                attachParams.keys().forEach { k -> put(k, attachParams[k]) }
                put("localRoot", guestScriptPath.substringBeforeLast("/"))
                put("remoteRoot", guestScriptPath.substringBeforeLast("/"))
            }
            Log.d(TAG, "Sending DAP attach: $args")
            dapClient.sendRequest("attach", args)
            onOutput("[js-debug] Attached to Node.js process.\n")
        } else {
            // Launch mode
            val launchArgs = buildLaunchArgs(guestScriptPath, session)
            Log.d(TAG, "Sending DAP launch: $launchArgs")
            dapClient.sendRequest("launch", launchArgs)
            onOutput("[js-debug] Launched ${session.filePath.substringAfterLast("/")}.\n")
        }

        // 8. Wait for 'initialized' event — adapter confirms debuggee is ready
        // for configuration. Without this, setBreakpoints may fail.
        if (!initializedLatch.await(15, TimeUnit.SECONDS)) {
            onOutput("[js-debug] WARNING: 'initialized' event not received within 15s\n")
            AppOutputLog.log("[DAP] WARNING: initialized event timeout — setBreakpoints may fail", "debug")
        } else {
            Log.d(TAG, "Got initialized event, sending setBreakpoints")
        }

        // 9. setBreakpoints (AFTER initialized event — per DAP spec)
        // DG02 (P3b): host->guest translation shared with the LIVE send path via
        // DapPathMapper — one identity conversion for launch and live.
        appContext = context
        if (breakpoints.isNotEmpty()) {
            val bpsByFile = breakpoints.groupBy { it.filePath }
            bpsByFile.forEach { (filePath, bps) ->
                val guestPath = DapPathMapper.toDapSourcePath(context, filePath)
                val bpArgs = JSONObject().apply {
                    put("source", JSONObject().put("path", guestPath))
                    put("breakpoints", JSONArray().apply {
                        bps.forEach { bp ->
                            put(JSONObject().apply {
                                put("line", bp.line + 1) // DAP is 1-based
                                if (bp.condition != null) put("condition", bp.condition)
                                if (bp.logMessage != null) put("logMessage", bp.logMessage)
                                // DG02: hitCondition parity with the live send path.
                                if (bp.hitCondition != null) put("hitCondition", bp.hitCondition)
                            })
                        }
                    })
                }
                val bpResp = dapClient.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
                if (bpResp == null) {
                    AppOutputLog.log("[DAP] Initial setBreakpoints failed for ${filePath.substringAfterLast("/")}", "debug")
                } else {
                    AppOutputLog.log("[DAP] Initial setBreakpoints OK for ${filePath.substringAfterLast("/")}: ${bps.size} breakpoint(s)", "debug")
                    // P27-11: Extract verification status from DAP response
                    val bpBody = bpResp.optJSONObject("body")
                    val bpArray = bpBody?.optJSONArray("breakpoints")
                    if (bpArray != null) {
                        val verifiedMap = mutableMapOf<Int, Boolean>()
                        for (j in 0 until bpArray.length()) {
                            val bpInfo = bpArray.optJSONObject(j)
                            val dapLine = bpInfo?.optInt("line", -1) ?: -1
                            val isVerified = bpInfo?.optBoolean("verified", false) ?: false
                            if (dapLine > 0) verifiedMap[dapLine] = isVerified
                        }
                        UniversalDebugManager.markBreakpointsVerified(filePath, verifiedMap)
                    }
                }
            }
        }

        // P2-FUNCBP: push stored function breakpoints before configurationDone
        if (caps?.supportsFunctionBreakpoints == true) {
            val storedFbs = UniversalDebugManager.getFunctionBreakpoints()
            if (storedFbs.isNotEmpty()) {
                setFunctionBreakpoints(session, storedFbs)
            }
        }

        // 10. configurationDone (AFTER setBreakpoints — tells adapter to start running)
        if (caps?.supportsConfigurationDoneRequest == true) {
            dapClient.sendRequest("configurationDone")
            // P1-D4: send default-enabled exception breakpoint filters after config
            val defaultFilters = caps?.exceptionFilters.orEmpty().filter { it.defaultOn }.map { it.filter }
            if (defaultFilters.isNotEmpty()) {
                dapClient.sendRequest("setExceptionBreakpoints", JSONObject().put("filters", JSONArray(defaultFilters)))
            }
        }

        return true
    }

    private fun buildLaunchArgs(guestScriptPath: String, session: DebugSession): JSONObject {
        val isTs = session.filePath.endsWith(".ts")
        return JSONObject().apply {
            put("type", "node")
            put("request", "launch")
            put("name", "Debug Node.js")
            put("program", guestScriptPath)
            put("stopOnEntry", false)
            put("sourceMaps", isTs)
            put("cwd", guestScriptPath.substringBeforeLast("/"))
            if (isTs) {
                // ts-node integration via runtimeArgs
                put("runtimeExecutable", "node")
                put("runtimeArgs", JSONArray().apply {
                    put("-r"); put("ts-node/register")
                })
            } else {
                put("runtimeExecutable", "node")
            }
        }
    }

    // ── P2: threads / paging / restartFrame / function breakpoints ──────────

    override fun getThreads(session: DebugSession): List<DebugThread> {
        val rt = runtime(session.id) ?: return emptyList()
        val dapClient = rt.client
        val resp = dapClient.request("threads", timeoutSeconds = 5) ?: return emptyList()
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
        val dapClient = rt.client
        if (rt.lastTotalFrames in 0..rt.framesLoaded) return Pair(emptyList(), rt.lastTotalFrames)
        val args = JSONObject().put("threadId", rt.threadId).put("startFrame", rt.framesLoaded).put("levels", 20)
        val resp = dapClient.request("stackTrace", args, timeoutSeconds = 5) ?: return Pair(emptyList(), rt.lastTotalFrames)
        val frames = resp.optJSONArray("stackFrames") ?: return Pair(emptyList(), rt.lastTotalFrames)
        if (frames.length() == 0) return Pair(emptyList(), rt.lastTotalFrames)
        rt.lastTotalFrames = resp.optInt("totalFrames", rt.framesLoaded + frames.length())
        val parsed = (0 until frames.length()).map { i -> parseFrame(frames.optJSONObject(i) ?: JSONObject(), rt.framesLoaded + i) }
        rt.framesLoaded += frames.length()
        return Pair(parsed, rt.lastTotalFrames)
    }

    override fun restartFrame(session: DebugSession, frameId: Int): Boolean {
        if (caps?.supportsRestartFrame != true) return false
        // On success the adapter emits a 'stopped' event (reason=frameEntry) which
        // refreshes the stack/variables UI through the normal paused path.
        return runtime(session.id)?.client?.request("restartFrame", JSONObject().put("frameId", frameId), timeoutSeconds = 5) != null
    }

    override fun setFunctionBreakpoints(session: DebugSession, bps: List<DebugFunctionBreakpoint>): Boolean {
        val rt = runtime(session.id) ?: return false
        if (caps?.supportsFunctionBreakpoints != true) return false
        val args = JSONObject().put("breakpoints", JSONArray().apply {
            bps.forEach { fb -> put(JSONObject().put("name", fb.name)) }
        })
        val resp = rt.client.request("setFunctionBreakpoints", args, timeoutSeconds = 5) ?: return false
        val arr = resp.optJSONArray("breakpoints")
        val verified = mutableMapOf<String, Pair<Boolean, String?>>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val nm = b.optString("name", bps.getOrNull(i)?.name ?: continue)
                verified[nm] = Pair(b.optBoolean("verified", false), b.optString("message", "").ifEmpty { null })
            }
        }
        UniversalDebugManager.markFunctionBreakpointsVerified(verified)
        return true
    }

    // ── Control ───────────────────────────────────────────────────────

    override fun stop(session: DebugSession) {
        val rt = runtimes.remove(session.id) ?: return
        // DG13 (P4b): send the DAP teardown BEFORE killing the local server
        // process — disconnect(terminateDebuggee=true) tells js-debug to end
        // the debuggee so it cannot be reparented inside the rootfs and keep
        // running; terminate is the fallback when disconnect fails.
        try {
            val resp = rt.client.request("disconnect", JSONObject().put("terminateDebuggee", true), timeoutSeconds = 3)
            if (resp == null) rt.client.sendRequest("terminate")
        } catch (_: Exception) {}
        // F5: the jest test-debuggee is a separate spawned process — the DAP
        // teardown does not cover it. Kill it or the runner keeps running.
        rt.testProcess?.destroyForcibly()
        rt.testProcess = null
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
        val evalArgs = JSONObject().apply {
            put("expression", expression)
            put("context", "repl")
            put("frameId", if (frameId > 0) frameId else rt.currentFrameId)
        }
        val resp = rt.client.request("evaluate", evalArgs, timeoutSeconds = 5) ?: return null
        return if (resp.has("result") && !resp.isNull("result")) resp.getString("result") else null
    }

    // ── Stack / Variables ─────────────────────────────────────────────

    private fun fetchStackFrames(dapClient: DAPClient, threadId: Int, rt: SessionRuntime): List<DebugStackFrame> {
        val args = JSONObject().put("threadId", threadId).put("startFrame", 0).put("levels", 20)
        val resp = dapClient.request("stackTrace", args, timeoutSeconds = 5) ?: return emptyList()
        val frames = resp.optJSONArray("stackFrames") ?: return emptyList()
        // P2-PAGING: record total frame count so the UI can offer "load more"
        rt.lastTotalFrames = resp.optInt("totalFrames", frames.length())
        rt.framesLoaded = frames.length()
        return (0 until frames.length()).map { i -> parseFrame(frames.optJSONObject(i) ?: JSONObject(), i) }
    }

    private fun parseFrame(f: JSONObject, index: Int): DebugStackFrame {
        val rawId = f.optInt("id", 0)
        val src = f.optJSONObject("source")
        val srcPath = src?.optString("path", "") ?: src?.optString("name", "") ?: ""
        return DebugStackFrame(
            function = f.optString("name", "<anonymous>"),
            file = srcPath,  // P27-2: clean path, frameId stored separately
            line = f.optInt("line", 0) - 1, // DAP 1-based → 0-based
            active = index == 0,
            frameId = rawId,
        )
    }

    private fun fetchVariables(dapClient: DAPClient, frameId: Int): List<DebugVariable> {
        // P1-D5: fetch ALL scopes (not just the first), tag each variable with its scope name.
        // Expensive scopes are skipped at pause-time (VS Code lazy-loads them too).
        val scopeArgs = JSONObject().put("frameId", frameId)
        val scopeResp = dapClient.request("scopes", scopeArgs, timeoutSeconds = 5) ?: return emptyList()
        val scopes = scopeResp.optJSONArray("scopes") ?: return emptyList()
        val result = mutableListOf<DebugVariable>()
        for (i in 0 until scopes.length()) {
            val scope = scopes.optJSONObject(i) ?: continue
            if (scope.optBoolean("expensive", false)) continue
            val scopeName = scope.optString("name", "Variables")
            val scopeRef = scope.optInt("variablesReference", 0)
            if (scopeRef == 0) continue
            val varResp = dapClient.request("variables", JSONObject().put("variablesReference", scopeRef), timeoutSeconds = 5) ?: continue
            val variables = varResp.optJSONArray("variables") ?: continue
            for (j in 0 until minOf(variables.length(), 100)) {
                val v = variables.optJSONObject(j) ?: continue
                val ref = v.optInt("variablesReference", 0)
                result += DebugVariable(
                    name = v.optString("name", "?"),
                    type = v.optString("type", ""),
                    value = v.optString("value", "undefined"),
                    expandable = ref > 0,
                    variablesReference = ref,
                    scopeName = scopeName,
                    containerRef = scopeRef,
                )
            }
        }
        return result
    }

    // P27-AUDIT: Public override — fetch child variables by DAP variablesReference
    override fun getVariables(session: DebugSession, variablesReference: Int): List<DebugVariable> {
        if (variablesReference == 0) return emptyList()
        val dapClient = runtime(session.id)?.client ?: return emptyList()
        val varArgs = JSONObject().put("variablesReference", variablesReference)
        val varResp = dapClient.request("variables", varArgs, timeoutSeconds = 5) ?: return emptyList()
        val variables = varResp.optJSONArray("variables") ?: return emptyList()
        return (0 until minOf(variables.length(), 50)).mapNotNull { i ->
            val v = variables.optJSONObject(i) ?: return@mapNotNull null
            val ref = v.optInt("variablesReference", 0)
            DebugVariable(
                name = v.optString("name", "?"),
                type = v.optString("type", ""),
                value = v.optString("value", "undefined"),
                expandable = ref > 0,
                variablesReference = ref,
                containerRef = variablesReference,
            )
        }
    }


    /**
     * P1-D3: DAP setVariable — edit a variable's value in place.
     * Returns the new value string on success, null on failure.
     */
    override fun setVariable(session: DebugSession, variablesReference: Int, name: String, value: String): String? {
        val dapClient = runtime(session.id)?.client ?: return null
        val args = JSONObject()
            .put("variablesReference", variablesReference)
            .put("name", name)
            .put("value", value)
        val resp = dapClient.request("setVariable", args, timeoutSeconds = 5) ?: return null
        return if (resp.optBoolean("success", false)) resp.optString("value", value) else null
    }

    /**
     * P1-D4: DAP setExceptionBreakpoints — push enabled exception filter ids to the adapter.
     */
    override fun setExceptionBreakpoints(session: DebugSession, filterIds: List<String>): Boolean {
        val dapClient = runtime(session.id)?.client ?: return false
        val args = JSONObject().put("filters", JSONArray(filterIds))
        val resp = dapClient.request("setExceptionBreakpoints", args, timeoutSeconds = 5)
        return resp != null
    }

}
