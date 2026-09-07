package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.codespace.ide.debug.DebugSession
import com.codespace.ide.debug.DebugState
import com.codespace.ide.debug.UniversalDebugManager

/**
 * P2-TOOLBAR: VS Code-style floating debug toolbar — visible whenever a debug
 * session is live, even when the user is scrolled into the editor or terminal
 * and the RUN AND DEBUG panel is out of view.
 *
 * Self-contained: registers its own UDM listener and renders nothing when no
 * session is active, so the host screen only needs a single call-site line.
 */
@Composable
internal fun DebugToolbarOverlay() {
    val udm = UniversalDebugManager
    var sessionState by remember { mutableStateOf<DebugState?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener: (DebugSession) -> Unit = { _ ->
            val sid = udm.activeSessionId
            sessionId = sid
            sessionState = sid?.let { udm.getSessionById(it)?.state }
        }
        udm.addOnSessionStateChangedListener(listener)
        onDispose { udm.removeOnSessionStateChangedListener(listener) }
    }

    val sid = sessionId
    val state = sessionState
    // Visible for the whole session lifecycle (STARTING through STOPPING); hidden
    // once stopped/crashed/failed, or when no session exists at all.
    if (sid == null || state == null) return
    if (state == DebugState.IDLE || state == DebugState.STOPPED ||
        state == DebugState.CRASHED || state == DebugState.FAILED || state == DebugState.ERROR
    ) return

    val paused = state == DebugState.PAUSED
    val canControl = state == DebugState.PAUSED || state == DebugState.RUNNING || state == DebugState.STEPPING

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false),
    ) {
        Row(
            Modifier
                .background(Color(0xF0252526), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Continue / Pause toggle
            if (paused) {
                ToolbarIcon(
                    icon = { Icon(Icons.Default.PlayArrow, "Continue", tint = Color(0xFF89D185), modifier = Modifier.size(18.dp)) },
                ) { udm.resumeSession(sid) }
            } else {
                ToolbarIcon(
                    icon = { Icon(Icons.Default.Pause, "Pause", tint = Color(0xFFDCDCAA), modifier = Modifier.size(18.dp)) },
                ) { udm.pauseSession(sid) }
            }
            // Stepping — enabled while paused
            ToolbarIcon(
                icon = { Icon(Icons.Default.SkipNext, "Step over", tint = if (paused) Color(0xFF75BEFF) else Color(0xFF5A5A5A), modifier = Modifier.size(18.dp)) },
                enabled = paused,
            ) { udm.stepOver(sid) }
            ToolbarIcon(
                icon = { Icon(Icons.Default.ArrowDownward, "Step into", tint = if (paused) Color(0xFF75BEFF) else Color(0xFF5A5A5A), modifier = Modifier.size(18.dp)) },
                enabled = paused,
            ) { udm.stepInto(sid) }
            ToolbarIcon(
                icon = { Icon(Icons.Default.ArrowUpward, "Step out", tint = if (paused) Color(0xFF75BEFF) else Color(0xFF5A5A5A), modifier = Modifier.size(18.dp)) },
                enabled = paused,
            ) { udm.stepOut(sid) }
            // Stop
            ToolbarIcon(
                icon = { Icon(Icons.Default.Stop, "Stop", tint = Color(0xFFF48771), modifier = Modifier.size(16.dp)) },
                enabled = canControl,
            ) { udm.stopSession(sid) }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(30.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { icon() }
}
