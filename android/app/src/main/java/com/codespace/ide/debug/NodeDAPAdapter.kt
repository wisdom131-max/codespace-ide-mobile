package com.codespace.ide.debug

import android.content.Context
import android.util.Log
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import com.codespace.ide.diagnostics.AppOutputLog
import org.json.JSONObject

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

    private var client: DAPClient? = null
    private var caps: DAPCapabilities? = null

    @Volatile private var threadId: Int = 1
    @Volatile private var currentFrameId: Int = 0

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
        val c = client ?: return false
        val bpsByFile = if (breakpoints.isEmpty()) {
            mapOf(session.filePath to emptyList<DebugBreakpoint>())
        } else {
            breakpoints.groupBy { it.filePath }
        }

        var allOk = true
        bpsByFile.forEach { (filePath, bps) ->
            // For live updates, the filePath is already a guest path (mapped at launch time)
            val guestPath = filePath
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path", guestPath))
                put("breakpoints", JSONArray().apply {
                    bps.forEach { bp ->
                        put(JSONObject().apply {
                            put("line", bp.line + 1)
                            if (bp.condition != null) put("condition", bp.condition)
                            if (bp.logMessage != null) put("logMessage", bp.logMessage)
                        })
                    }
                })
            }
            val resp = c.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
            if (resp == null) {
                AppOutputLog.log("[DAP] setBreakpoints failed for ${filePath.substringAfterLast("/")}", "lsp")
                allOk = false
            } else {
                AppOutputLog.log("[DAP] setBreakpoints OK for ${filePath.substringAfterLast("/")}: ${bps.size} breakpoint(s)", "lsp")
            }
        }
        return allOk
    }

    // ── Installation ──────────────────────────────────────────────────

    fun isJsDebugInstalled(context: Context): Boolean {
        val out = ProotInstaller.execOnce(context,
            "npm list -g @vscode/js-debug --depth=0 2>/dev/null | grep js-debug || echo NOT_FOUND",
            timeoutSeconds = 15)
        return "NOT_FOUND" !in out && out.isNotBlank() &&
               !out.contains("Exit code") &&
               !out.contains("Error")
    }

    fun installJsDebug(context: Context): Boolean {
        Log.d(TAG, "Installing @vscode/js-debug...")
        val result = ProotInstaller.execOnce(context,
            "apt-get update -qq 2>/dev/null; " +
            "apt-get install -y --no-install-recommends nodejs npm 2>/dev/null; " +
            "npm install -g @vscode/js-debug 2>&1 | tail -5",
            timeoutSeconds = 180)
        Log.d(TAG, "js-debug install: $result")
        return isJsDebugInstalled(context)
    }

    /** Find the dapDebugServer.js entry point in the global npm prefix. */
    private fun findDapServerPath(context: Context): String? {
        val out = ProotInstaller.execOnce(context,
            "node -e 'const p=require.resolve(\"@vscode/js-debug/src/dapDebugServer\"); console.log(p)' 2>/dev/null " +
            "|| find \$(npm root -g 2>/dev/null) -name 'dapDebugServer.js' -maxdepth 5 2>/dev/null | head -1",
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
        onStopped: () -> Unit,
    ): Boolean {
        Log.d(TAG, "launch: ${session.filePath}")
        return launchInternal(
            context, session, breakpoints, onOutput, onPaused, onStopped,
            attachParams = null
        )
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
        onStopped: () -> Unit,
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
        onStopped: () -> Unit,
        attachParams: JSONObject?,
    ): Boolean {
        // 1. Ensure js-debug is installed
        if (!isJsDebugInstalled(context)) {
            onOutput("[js-debug] @vscode/js-debug not installed — installing (~60s)...\n")
            if (!installJsDebug(context)) {
                onOutput("[js-debug] Install failed. Falling back to node inspect.\n")
                return false
            }
            onOutput("[js-debug] Installed.\n")
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
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        val headArgs = baseArgs.dropLast(2).toTypedArray()
        val serverCmd = "node '$serverPath' --stdio"
        // P32: Use bash -c (non-login) with profile sourcing redirected to /dev/null.
        // Same fix as LSP startServer — prevents [Agent] banner text from corrupting
        // the DAP JSON-RPC stream on stdout.
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec $serverCmd"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)

        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(false)
        val envMap = pb.environment()
        envVars.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
        }

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
        client = dapClient

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
            threadId = body.optInt("threadId", 1)
            val reason = body.optString("reason", "breakpoint")
            Log.d(TAG, "DAP stopped: reason=$reason threadId=$threadId")
            onOutput("[js-debug] Paused: $reason\n")

            Thread {
                val frames = fetchStackFrames(dapClient, threadId)
                val frameId = if (frames.isNotEmpty()) frames.first().frameId else 0
                currentFrameId = frameId
                val vars = fetchVariables(dapClient, frameId)
                onPaused(frames, vars)
            }.also { it.isDaemon = true }.start()
        }

        dapClient.onEvent("terminated") { _ ->
            Log.d(TAG, "DAP terminated")
            onOutput("[js-debug] Session terminated.\n")
            onStopped()
        }

        dapClient.onEvent("exited") { body ->
            val code = body.optInt("exitCode", 0)
            onOutput("[js-debug] Process exited with code $code.\n")
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
            put("clientName", "CodeSpace IDE")
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
            stopProcess()
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
            AppOutputLog.log("[DAP] WARNING: initialized event timeout — setBreakpoints may fail", "lsp")
        } else {
            Log.d(TAG, "Got initialized event, sending setBreakpoints")
        }

        // 9. setBreakpoints (AFTER initialized event — per DAP spec)
        if (breakpoints.isNotEmpty()) {
            val bpsByFile = breakpoints.groupBy { it.filePath }
            bpsByFile.forEach { (filePath, bps) ->
                val guestPath = if (filePath.startsWith(context.filesDir.absolutePath + "/")) {
                    "/host-files/" + filePath.removePrefix(context.filesDir.absolutePath + "/")
                } else filePath
                val bpArgs = JSONObject().apply {
                    put("source", JSONObject().put("path", guestPath))
                    put("breakpoints", JSONArray().apply {
                        bps.forEach { bp ->
                            put(JSONObject().apply {
                                put("line", bp.line + 1) // DAP is 1-based
                                if (bp.condition != null) put("condition", bp.condition)
                                if (bp.logMessage != null) put("logMessage", bp.logMessage)
                            })
                        }
                    })
                }
                val bpResp = dapClient.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
                if (bpResp == null) {
                    AppOutputLog.log("[DAP] Initial setBreakpoints failed for ${filePath.substringAfterLast("/")}", "lsp")
                } else {
                    AppOutputLog.log("[DAP] Initial setBreakpoints OK for ${filePath.substringAfterLast("/")}: ${bps.size} breakpoint(s)", "lsp")
                }
            }
        }

        // 10. configurationDone (AFTER setBreakpoints — tells adapter to start running)
        if (caps?.supportsConfigurationDoneRequest == true) {
            dapClient.sendRequest("configurationDone")
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

    // ── Control ───────────────────────────────────────────────────────

    override fun stop(session: DebugSession) {
        try { client?.request("terminate", timeoutSeconds = 3) } catch (_: Exception) {}
        stopProcess()
    }

    override fun pause(session: DebugSession) {
        client?.sendRequest("pause", JSONObject().put("threadId", threadId))
    }

    override fun resume(session: DebugSession) {
        client?.sendRequest("continue", JSONObject().put("threadId", threadId))
    }

    override fun stepOver(session: DebugSession) {
        client?.sendRequest("next", JSONObject().put("threadId", threadId))
    }

    override fun stepInto(session: DebugSession) {
        client?.sendRequest("stepIn", JSONObject().put("threadId", threadId))
    }

    override fun stepOut(session: DebugSession) {
        client?.sendRequest("stepOut", JSONObject().put("threadId", threadId))
    }

    override fun evaluate(session: DebugSession, expression: String, frameId: Int): String? {
        val evalArgs = JSONObject().apply {
            put("expression", expression)
            put("context", "repl")
            put("frameId", if (frameId > 0) frameId else currentFrameId)
        }
        val resp = client?.request("evaluate", evalArgs, timeoutSeconds = 5) ?: return null
        return if (resp.has("result") && !resp.isNull("result")) resp.getString("result") else null
    }

    // ── Stack / Variables ─────────────────────────────────────────────

    private fun fetchStackFrames(dapClient: DAPClient, threadId: Int): List<DebugStackFrame> {
        val args = JSONObject().put("threadId", threadId).put("startFrame", 0).put("levels", 20)
        val resp = dapClient.request("stackTrace", args, timeoutSeconds = 5) ?: return emptyList()
        val frames = resp.optJSONArray("stackFrames") ?: return emptyList()
        return (0 until frames.length()).map { i ->
            val f = frames.optJSONObject(i) ?: JSONObject()
            val rawId = f.optInt("id", 0)
            val src = f.optJSONObject("source")
            val srcPath = src?.optString("path", "") ?: src?.optString("name", "") ?: ""
            DebugStackFrame(
                function = f.optString("name", "<anonymous>"),
                file = srcPath,  // P27-2: clean path, frameId stored separately
                line = f.optInt("line", 0) - 1, // DAP 1-based → 0-based
                active = i == 0,
                frameId = rawId,
            )
        }
    }

    private fun fetchVariables(dapClient: DAPClient, frameId: Int): List<DebugVariable> {
        // Get scopes first
        val scopeArgs = JSONObject().put("frameId", frameId)
        val scopeResp = dapClient.request("scopes", scopeArgs, timeoutSeconds = 5) ?: return emptyList()
        val scopes = scopeResp.optJSONArray("scopes") ?: return emptyList()
        if (scopes.length() == 0) return emptyList()

        // Get variables from first scope (locals)
        val firstScope = scopes.optJSONObject(0) ?: return emptyList()
        val varRef = firstScope.optInt("variablesReference", 0)
        if (varRef == 0) return emptyList()

        val varArgs = JSONObject().put("variablesReference", varRef)
        val varResp = dapClient.request("variables", varArgs, timeoutSeconds = 5) ?: return emptyList()
        val variables = varResp.optJSONArray("variables") ?: return emptyList()

        return (0 until minOf(variables.length(), 50)).mapNotNull { i ->
            val v = variables.optJSONObject(i) ?: return@mapNotNull null
            DebugVariable(
                name = v.optString("name", "?"),
                type = v.optString("type", ""),
                value = v.optString("value", "undefined"),
                expandable = v.optInt("variablesReference", 0) > 0,
            )
        }
    }

    private fun stopProcess() {
        client?.stop()
        client = null
    }
}
