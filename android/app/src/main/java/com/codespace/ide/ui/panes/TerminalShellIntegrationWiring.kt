package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.termux.terminal.TerminalSession

/**
 * D15-b — SHELL-INTEGRATION UI WIRING (extracted file, 64KB rule: TerminalPane is
 * 2500+ lines; new effects/UI never grow its composable bodies).
 *
 * Two pieces, both driven by the active tab's ShellIntegrationState:
 *
 *  B1. [TerminalShellStatusBar] — slim strip under the tab bar: guest cwd +
 *      NONZERO-only exit indicator. Hidden entirely while currentCwd is null
 *      (no integration = no false data — a deleted script or non-bash shell
 *      simply renders nothing).
 *
 *  B2. [ShellRunRecorder] — observes the E -> C -> D lifecycle and captures the
 *      transcript window between C and D; hands a ShellRun (command, output,
 *      exit code, cwd, nonce-validated flag) to TerminalAiBridge so the chat
 *      attach picker can offer the last shell command. MANUAL attach only —
 *      automated/fail-closed AI consumption stays parked for D15-c per the
 *      approved D15 plan.
 *
 * Threading: OSC marks arrive on the emulator reader thread. The listener hops
 * to the main thread via Handler.post before touching Compose state. Marks fire
 * only at prompt boundaries — never per keystroke — so this costs nothing during
 * heavy output.
 */

private const val STATUS_BAR_DISPLAY_CHARS = 44

/** Leading-truncate a path so the END (most specific part) stays visible. */
private fun displayPath(path: String): String =
    if (path.length > STATUS_BAR_DISPLAY_CHARS) "\u2026" + path.takeLast(STATUS_BAR_DISPLAY_CHARS - 1) else path

@Composable
internal fun TerminalShellStatusBar(session: TerminalSession?) {
    if (session == null) return
    val state = remember(session) {
        com.codespace.ide.terminal.shellintegration.ShellIntegrationState.forSession(session)
    } ?: return // no OSC 633 integration on this session: render nothing
    var revision by remember(state) { mutableStateOf(0) }
    DisposableEffect(state) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val removeListener = state.addListener { _ -> main.post { revision++ } }
        onDispose { removeListener() }
    }
    // revision is the recomposition driver; reads below observe the latest tick.
    if (revision < 0) return
    val cwd = state.currentCwd
    val exit = state.lastCommandExitCode
    if (cwd == null) return // no prompt reported yet: no data, no strip
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Color(0xFF232323)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            displayPath(cwd),
            fontSize = 10.sp,
            color = Color(0xFF9A9A9A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (exit != null && exit != 0) {
            // NONZERO-only indicator: a checkmark on every success is noise.
            Text(
                "\u2717 " + exit,
                fontSize = 10.sp,
                color = Color(0xFFF14C4C),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
internal fun ShellRunRecorder(session: TerminalSession?) {
    if (session == null) return
    val state = remember(session) {
        com.codespace.ide.terminal.shellintegration.ShellIntegrationState.forSession(session)
    } ?: return
    DisposableEffect(state) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // Captured on the reader thread at C; sliced at D. Both marks arrive on
        // the SAME reader thread serially, so this plain var is race-free.
        var outputStartLen = -1
        val removeListener = state.addListener { kind ->
            when (kind) {
                com.codespace.ide.terminal.shellintegration.ShellIntegrationState.ChangeKind.OUTPUT_START -> {
                    outputStartLen = com.codespace.ide.terminal.TerminalAiBridge.transcriptProvider?.invoke()?.length ?: -1
                }
                com.codespace.ide.terminal.shellintegration.ShellIntegrationState.ChangeKind.COMMAND_COMPLETE -> {
                    val record = state.recentCommands(1).lastOrNull() ?: return@addListener
                    val transcript = com.codespace.ide.terminal.TerminalAiBridge.transcriptProvider?.invoke()
                    val window = com.codespace.ide.terminal.shellintegration.ShellRunCapture.sliceOutputWindow(transcript, outputStartLen)
                    val run = com.codespace.ide.terminal.shellintegration.ShellRunCapture.ShellRun(
                        command = record.command,
                        exitCode = record.exitCode,
                        cwd = record.cwd,
                        output = window,
                        nonceValidated = record.nonceValidated,
                        capturedAtMs = System.currentTimeMillis(),
                    )
                    main.post { com.codespace.ide.terminal.TerminalAiBridge.recordShellRun(run) }
                }
                else -> {}
            }
        }
        onDispose { removeListener() }
    }
}
