package com.codespace.ide.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 8a (2026-11-15): a slow command is POLLED, not killed and called a timeout.
 *
 * What was wrong: the agent's shell call blocked until the command returned. Anything longer than
 * the caller's patience came back as a failure, and a failure invites a retry - which runs the
 * same command a second time. That is the same class of mistake as re-sending a request.
 *
 * VS Code's own terminal tools hand the agent a terminal it can poll (run_in_terminal,
 * get_terminal_output, kill_terminal) rather than one blocking call expected to finish. This is
 * the same shape: the command starts immediately, the caller waits a bounded window, and past it
 * the call returns a handle for [com.codespace.ide.agent.AgentTerminalFeed] - which the existing
 * terminal_list / terminal_kill tools already read.
 */
internal object BackgroundCommands {

    /** How long a caller waits inline before being handed a handle. */
    const val INLINE_WAIT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Deferred<String>>()

    /**
     * Start the command now and return the token to poll with. The run is announced to the
     * terminal feed BEFORE exec, so it is visible while it runs instead of only afterwards.
     */
    fun start(
        context: Context,
        command: String,
        workdir: String?,
        runId: Long,
    ): Deferred<String> {
        val job = scope.async {
            try {
                val out = com.codespace.ide.terminal.ProotInstaller.execOnce(context, command, workdir)
                com.codespace.ide.agent.AgentTerminalFeed.finish(runId, out)
                com.codespace.ide.terminal.TerminalAiBridge.recordRun(command, out, workdir)
                com.codespace.ide.util.FsChangeNotifier.notifyChanged()
                out
            } catch (t: Throwable) {
                com.codespace.ide.agent.AgentTerminalFeed.finish(
                    runId, t.message ?: t.toString(), failed = true,
                )
                throw t
            } finally {
                running.remove(runId)
            }
        }
        running[runId] = job
        return job
    }

    /** True while the command handed out under this run id is still executing. */
    fun isRunning(runId: Long): Boolean = running.containsKey(runId)

    /** The line the model is given instead of a false timeout. */
    fun handleText(runId: Long, sentinel: String = runId.toString()): String = TerminalHandle.stillRunning(sentinel)
}
