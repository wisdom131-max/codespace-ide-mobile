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
 * P26-2c: PythonDAPAdapter — debug Python via debugpy over DAP.
 *
 * Replaces PythonDebugProvider with a proper DAP-based implementation.
 * LegacyDebugAdapter(PythonDebugProvider()) is used as fallback when debugpy
 * is not installed in the proot environment.
 *
 * Launch sequence:
 *   1. Check debugpy installed: python3 -m debugpy --version
 *   2. If not: pip3 install debugpy
 *   3. Spawn: python3 -m debugpy.adapter  (DAP over stdin/stdout, launches debuggee via 'launch' request)
 *   4. DAPClient.start() — reads stdout, writes stdin
 *   5. Send initialize → launch → wait for 'initialized' event → setBreakpoints → configurationDone
 *   6. Wire stopped/output/terminated events → UI callbacks
 */
class PythonDAPAdapter : DebugAdapter {

    override val id = "python-dap"
    override val displayName = "Python (debugpy)"

    private val TAG = "PythonDAPAdapter"

    private var client: DAPClient? = null

    // P27-10: Track process exit code for crash detection
    @Volatile private var lastExitCode: Int = 0
    private var caps: DAPCapabilities? = null

    // Running thread ID — set when stopped event fires
    @Volatile private var threadId: Int = 1
    @Volatile private var currentFrameId: Int = 0

    override fun canDebug(language: Language, filePath: String) =
        language == Language.PYTHON && filePath.endsWith(".py")

    override fun capabilities() = caps

    /**
     * P32-BREAKPOINT-FIX: Send updated breakpoints to the debugpy adapter during a running session.
     * Called by UDM when breakpoints change while debugging is active.
     */
    override fun sendBreakpoints(session: DebugSession, breakpoints: List<DebugBreakpoint>): Boolean {
        val c = client ?: return false
        if (breakpoints.isEmpty()) {
            // Send empty setBreakpoints to clear all breakpoints for this file
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path", session.filePath))
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
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path", filePath))
                put("breakpoints", JSONArray().also { arr ->
                    bps.forEach { bp ->
                        arr.put(JSONObject().apply {
                            put("line", bp.line + 1)
                            if (bp.condition != null) put("condition", bp.condition)
                            if (bp.logMessage != null) put("logMessage", bp.logMessage)
                        })
                    }
                })
            }
            val resp = c.request("setBreakpoints", bpArgs, timeoutSeconds = 5)
            if (resp == null) {
                AppOutputLog.log("[DAP] setBreakpoints failed for ${filePath.substringAfterLast("/")} — ${bps.size} breakpoint(s) not sent", "lsp")
                allOk = false
            } else {
                AppOutputLog.log("[DAP] setBreakpoints OK for ${filePath.substringAfterLast("/")} — ${bps.size} breakpoint(s) set", "lsp")
            }
        }
        return allOk
    }

    // ── Installation check ─────────────────────────────────────────────────

    fun isDebugpyInstalled(context: Context): Boolean {
        val output = ProotInstaller.execOnce(context, "python3 -m debugpy --version", timeoutSeconds = 10)
        return output.isNotBlank() &&
               !output.contains("No module named") &&
               !output.contains("Exit code") &&
               !output.contains("Error")
    }

    fun installDebugpy(context: Context): Boolean {
        Log.d(TAG, "Installing debugpy...")
        val result = ProotInstaller.execOnce(context,
            "pip3 install --break-system-packages debugpy", timeoutSeconds = 120)
        Log.d(TAG, "debugpy install result: $result")
        return isDebugpyInstalled(context)
    }

    // ── Launch ─────────────────────────────────────────────────────────────

    override fun launch(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean {
        Log.d(TAG, "launch: ${session.filePath}")

        // 1. Ensure debugpy is installed
        if (!isDebugpyInstalled(context)) {
            onOutput("[debugpy] Not installed — installing now (this may take ~30s)...\n")
            if (!installDebugpy(context)) {
                onOutput("[debugpy] Installation failed. Falling back to legacy pdb.\n")
                return false
            }
            onOutput("[debugpy] Installed successfully.\n")
        }

        // 2. Resolve guest path for the script
        val guestPath = ProotInstaller.hostToGuestPath(context, session.filePath)
            ?: run {
                // filesDir mapping
                val filesDir = context.filesDir.absolutePath
                if (session.filePath.startsWith("$filesDir/")) {
                    "/host-files/" + session.filePath.removePrefix("$filesDir/")
                } else {
                    Log.e(TAG, "Cannot resolve guest path for ${session.filePath}")
                    return false
                }
            }

        // 3. Spawn: python3 -m debugpy.adapter (DAP adapter over stdin/stdout)
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        val headArgs = baseArgs.dropLast(2).toTypedArray()
        // P32: Use bash -c (non-login) with profile sourcing redirected to /dev/null.
        // Same fix as LSP startServer — prevents [Agent] banner text from corrupting
        // the DAP JSON-RPC stream on stdout.
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec python3 -m debugpy.adapter"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)

        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(false)
        val envMap = pb.environment()
        envVars.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        Log.d(TAG, "Spawning debugpy.adapter (DAP over stdin/stdout), will launch $guestPath via DAP launch request")

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn debugpy: ${e.message}")
            onOutput("[debugpy] Failed to spawn process: ${e.message}\n")
            return false
        }

        // 4. Create and start DAPClient
        val dapClient = DAPClient(process)
        client = dapClient

        // Wire events BEFORE start() so no events are missed
        dapClient.onEvent("output") { body ->
            val category = body.optString("category", "console")
            val text = body.optString("output", "")
            if (text.isNotBlank()) {
                val prefix = when (category) {
                    "stderr" -> "[stderr] "
                    "stdout" -> ""
                    else     -> "[$category] "
                }
                onOutput(prefix + text)
            }
        }

        dapClient.onEvent("stopped") { body ->
            threadId = body.optInt("threadId", 1)
            val reason = body.optString("reason", "breakpoint")
            Log.d(TAG, "DAP stopped: reason=$reason threadId=$threadId")
            onOutput("[debugpy] Paused: $reason\n")

            // Fetch stack frames + variables on IO thread
            Thread {
                val frames = fetchStackFrames(dapClient, threadId)
                val vars = if (frames.isNotEmpty()) {
                    currentFrameId = frames.first().frameId
                    fetchVariables(dapClient, currentFrameId)
                } else emptyList()
                onPaused(frames, vars)
            }.also { it.isDaemon = true }.start()
        }

        dapClient.onEvent("terminated") { _ ->
            Log.d(TAG, "DAP terminated")
            onOutput("[debugpy] Session terminated.\n")
            onStopped(lastExitCode)
            client = null
        }

        dapClient.onEvent("exited") { body ->
            val code = body.optInt("exitCode", 0)
            onOutput("[debugpy] Process exited with code $code\n")
            lastExitCode = code  // P27-10
        }

        // P32-DAP-ORDER: Register initialized event handler BEFORE start().
        // debugpy sends 'initialized' AFTER launch (when the debuggee is ready),
        // NOT after initialize. setBreakpoints MUST be sent after 'initialized'
        // or debugpy returns "Server is not available".
        val initializedLatch = CountDownLatch(1)
        dapClient.onEvent("initialized") { _ ->
            Log.d(TAG, "DAP initialized event received — debuggee ready for configuration")
            initializedLatch.countDown()
        }

        dapClient.start()

        // 5. Initialize handshake
        val initArgs = JSONObject().apply {
            put("clientID", "codespace-ide")
            put("clientName", "Codespace IDE")
            put("adapterID", "python")
            put("locale", "en-US")
            put("linesStartAt1", true)
            put("columnsStartAt1", true)
            put("pathFormat", "path")
            put("supportsVariableType", true)
            put("supportsRunInTerminalRequest", false)
        }
        val initResp = dapClient.request("initialize", initArgs, timeoutSeconds = 10)
        if (initResp == null) {
            onOutput("[debugpy] initialize failed or timed out\n")
            dapClient.stop()
            return false
        }
        caps = initResp.toDAPCapabilities()
        Log.d(TAG, "DAP initialized, caps: $caps")

        // 6. Launch (BEFORE setBreakpoints — debugpy needs the debuggee running
        // before it can accept breakpoint configuration)
        val launchArgs = JSONObject().apply {
            put("request", "launch")
            put("type", "python")
            put("name", "Debug Python")
            put("program", guestPath)
            put("python", "python3")  // tell adapter which interpreter to use in proot
            put("stopOnEntry", false)
            put("justMyCode", false)
            put("noDebug", false)
            put("console", "internalConsole")
        }
        Log.d(TAG, "Sending DAP launch...")
        dapClient.sendRequest("launch", launchArgs)

        // 7. Wait for 'initialized' event — debugpy sends this after the debuggee
        // has started and is ready to accept configuration (setBreakpoints).
        // Without this, setBreakpoints fails with "Server is not available".
        if (!initializedLatch.await(15, TimeUnit.SECONDS)) {
            onOutput("[debugpy] WARNING: 'initialized' event not received within 15s\n")
            AppOutputLog.log("[DAP] WARNING: initialized event timeout — setBreakpoints may fail", "lsp")
        } else {
            Log.d(TAG, "Got initialized event, sending setBreakpoints")
        }

        // 8. Set breakpoints (AFTER initialized event — this is the fix)
        val bpsByFile = breakpoints.groupBy { it.filePath }
        for ((filePath, bps) in bpsByFile) {
            val bpGuestPath = ProotInstaller.hostToGuestPath(context, filePath)
                ?: "/host-files/" + filePath.removePrefix(context.filesDir.absolutePath + "/")
            val bpArgs = JSONObject().apply {
                put("source", JSONObject().put("path", bpGuestPath))
                put("breakpoints", JSONArray().also { arr ->
                    bps.forEach { bp ->
                        arr.put(JSONObject().apply {
                            put("line", bp.line + 1) // DAP uses 1-based lines
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

        // 9. configurationDone (AFTER setBreakpoints — tells adapter to start running)
        dapClient.sendRequest("configurationDone")
        onOutput("[debugpy] Session started — running ${session.filePath.substringAfterLast("/")}\n")
        return true
    }

    // ── Control commands ───────────────────────────────────────────────────

    override fun stop(session: DebugSession) {
        val c = client ?: return
        c.sendRequest("terminate")
        c.stop()
        client = null
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
        val c = client ?: return null
        val args = JSONObject().apply {
            put("expression", expression)
            put("frameId", if (frameId > 0) frameId else currentFrameId)
            put("context", "repl")
        }
        val resp = c.request("evaluate", args, timeoutSeconds = 5) ?: return null
        return if (resp.has("result") && !resp.isNull("result")) resp.getString("result") else null
    }

    // ── Stack frame / variable helpers ─────────────────────────────────────

    private fun fetchStackFrames(client: DAPClient, threadId: Int): List<DebugStackFrame> {
        val args = JSONObject().put("threadId", threadId).put("startFrame", 0).put("levels", 20)
        val resp = client.request("stackTrace", args, timeoutSeconds = 5) ?: return emptyList()
        val framesArr = resp.optJSONArray("stackFrames") ?: return emptyList()
        val result = mutableListOf<DebugStackFrame>()
        for (i in 0 until framesArr.length()) {
            val f = framesArr.getJSONObject(i)
            val frameId = f.optInt("id", 0)
            val src = f.optJSONObject("source")
            val path = src?.optString("path", "") ?: ""
            result += DebugStackFrame(
                function = f.optString("name", "<unknown>"),
                file     = path,
                line     = f.optInt("line", 0) - 1, // convert to 0-based
                active   = i == 0,
                frameId  = frameId,  // P27-2: store DAP frame ID directly
            )
        }
        return result
    }

    private fun fetchVariables(client: DAPClient, frameId: Int): List<DebugVariable> {
        // Get scopes for this frame
        val scopesResp = client.request("scopes", JSONObject().put("frameId", frameId), timeoutSeconds = 5)
            ?: return emptyList()
        val scopesArr = scopesResp.optJSONArray("scopes") ?: return emptyList()
        val result = mutableListOf<DebugVariable>()
        for (i in 0 until minOf(scopesArr.length(), 3)) {
            val scope = scopesArr.getJSONObject(i)
            val scopeName = scope.optString("name", "Variables")
            val ref = scope.optInt("variablesReference", 0)
            if (ref == 0) continue
            val varResp = client.request("variables",
                JSONObject().put("variablesReference", ref).put("count", 100),
                timeoutSeconds = 5) ?: continue
            val vars = varResp.optJSONArray("variables") ?: continue
            for (j in 0 until vars.length()) {
                val v = vars.getJSONObject(j)
                result += DebugVariable(
                    name       = v.optString("name", "?"),
                    type       = v.optString("type", scopeName),
                    value      = v.optString("value", ""),
                    depth      = 0,
                    expandable = v.optInt("variablesReference", 0) > 0,
                )
            }
        }
        return result
    }
}
