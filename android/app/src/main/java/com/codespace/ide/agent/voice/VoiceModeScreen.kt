package com.codespace.ide.agent.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.chat.ui.ChatColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * PROJECT 2.25 (2026-11-15): the voice screen.
 *
 * Everything on this surface is driven by [VoiceTurnMachine.State]. The orb moves because a level
 * arrived from the microphone, not because a timer says "listening"; when the phase is idle nothing
 * animates at all, which is both the honest signal and the cheap one on a low-memory phone.
 *
 * Panel rules from the project apply: 12dp horizontal and 10dp vertical padding, corners 8-12dp, and
 * every piece of composition extracted into its own function so no composable body carries inline
 * work that would count against the JVM bytecode limit.
 */
@Composable
internal fun VoiceModeScreen(
    state: VoiceTurnMachine.State,
    statusLine: String,
    providerLabel: String,
    providerDetail: String,
    spokenReplies: Boolean,
    onToggleMic: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onToggleSpokenReplies: () -> Unit,
) {
    val colors = ChatTokens.LocalChatColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VoiceHeaderRow(
            phase = state.phase,
            providerLabel = providerLabel,
            onClose = onClose,
            colors = colors,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(phase = state.phase, energy = state.energy01, lastWordAtMs = state.lastWordAtMs, wordGapMs = state.wordGapMs, accent = colors.accent)
        }
        VoiceStatusLine(
            phase = state.phase,
            statusLine = statusLine,
            lastError = state.lastError,
            transcript = state.transcriptPreview,
        )
        Spacer(Modifier.height(10.dp))
        VoiceControlsRow(
            state = state,
            onToggleMic = onToggleMic,
            onStop = onStop,
            onClose = onClose,
        )
        Spacer(Modifier.height(6.dp))
        VoiceProviderStrip(
            providerLabel = providerLabel,
            providerDetail = providerDetail,
            spokenReplies = spokenReplies,
            onToggleSpokenReplies = onToggleSpokenReplies,
            shape = shape,
            colors = colors,
        )
    }
}

@Composable
private fun VoiceHeaderRow(
    phase: VoiceTurnMachine.Phase,
    providerLabel: String,
    onClose: () -> Unit,
    colors: ChatColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Voice", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(providerLabel, color = colors.textSecondary, fontSize = 12.sp)
        }
        Text(PHASE_LABEL.getValue(phase), color = colors.textSecondary, fontSize = 12.sp)
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                contentDescription = "Return to chat",
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * The orb. Two rings plus a core: the core tracks the measured level, one ring breathes while a
 * phase is active, and the idle state draws a still circle. The infinite transition exists only when
 * something is actually happening.
 */
@Composable
private fun VoiceOrb(
    phase: VoiceTurnMachine.Phase,
    energy: Float,
    lastWordAtMs: Long,
    wordGapMs: Long,
    accent: Color,
) {
    val pulseNow = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (phase == VoiceTurnMachine.Phase.SPEAKING) {
            pulseNow.value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60L)
        }
    }
    val target = if (phase == VoiceTurnMachine.Phase.SPEAKING) {
        SpeakingPulse.energyAt(pulseNow.value, lastWordAtMs, wordGapMs)
    } else {
        energy
    }
    val active = phase == VoiceTurnMachine.Phase.LISTENING ||
        phase == VoiceTurnMachine.Phase.SPEAKING ||
        phase == VoiceTurnMachine.Phase.PROCESSING
    val level by animateFloatAsState(
        targetValue = if (active) target.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "voice-energy",
    )
    val breathing = if (active) {
        val transition = rememberInfiniteTransition(label = "voice-breath")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "voice-breath-value",
        )
    } else {
        null
    }
    val breath = breathing?.value ?: 0f
    val tint by animateColorAsState(
        targetValue = when (phase) {
            VoiceTurnMachine.Phase.SPEAKING -> accent
            VoiceTurnMachine.Phase.LISTENING -> accent
            VoiceTurnMachine.Phase.PROCESSING -> accent.copy(alpha = 0.6f)
            VoiceTurnMachine.Phase.ERROR -> Color(0xFFB3261E)
            VoiceTurnMachine.Phase.IDLE -> accent.copy(alpha = 0.4f)
        },
        animationSpec = tween(220),
        label = "voice-tint",
    )
    Canvas(modifier = Modifier.size(240.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.26f
        val coreScale = 1f + level * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.05f)),
                center = centre,
                radius = base * 2.2f,
            ),
            radius = base * (1.6f + breath * 0.25f),
            center = centre,
        )
        drawCircle(color = tint.copy(alpha = 0.9f), radius = base * coreScale, center = centre)
        for (ring in 0..2) {
            val phaseOffset = (ring / 3f)
            val radius = base * (1.5f + ring * 0.55f) * (1f + level * 0.35f)
            val alpha = (0.35f - ring * 0.09f) * (0.6f + breath * 0.4f)
            drawCircle(
                color = tint.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = radius,
                center = centre,
                style = Stroke(width = 2f + level * 6f),
            )
            if (phaseOffset >= 0f) {
                val waveRadius = base * 0.9f
                val angle = (breath * 2f * PI.toFloat()) + phaseOffset * PI.toFloat()
                drawCircle(
                    color = tint.copy(alpha = 0.5f),
                    radius = 3f + level * 6f,
                    center = Offset(
                        centre.x + kotlin.math.cos(angle) * waveRadius,
                        centre.y + sin(angle) * waveRadius,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VoiceStatusLine(
    phase: VoiceTurnMachine.Phase,
    statusLine: String,
    lastError: String?,
    transcript: String,
) {
    val colors = ChatTokens.LocalChatColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val error = lastError
        val message = when {
            error != null -> error
            statusLine.isNotBlank() -> statusLine
            else -> PHASE_HINT.getValue(phase)
        }
        Text(
            text = message,
            color = if (error != null) Color(0xFFB3261E) else colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
        if (transcript.isNotBlank()) {
            Text(
                text = "\u201C" + transcript + "\u201D",
                color = colors.text,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun VoiceControlsRow(
    state: VoiceTurnMachine.State,
    onToggleMic: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ChatTokens.LocalChatColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleMic) {
            Icon(
                painter = painterResource(
                    if (state.muted) android.R.drawable.ic_lock_silent_mode
                    else android.R.drawable.ic_btn_speak_now
                ),
                contentDescription = if (state.muted) "Unmute microphone" else "Mute microphone",
                tint = if (state.muted) colors.textSecondary else colors.accent,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                painter = painterResource(android.R.drawable.ic_media_pause),
                contentDescription = "Stop and leave voice mode",
                tint = colors.textSecondary,
            )
        }
        TextButton(onClick = onClose) {
            Text("Back to chat", color = colors.accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun VoiceProviderStrip(
    providerLabel: String,
    providerDetail: String,
    spokenReplies: Boolean,
    onToggleSpokenReplies: () -> Unit,
    shape: RoundedCornerShape,
    colors: ChatColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = providerLabel,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleSpokenReplies) {
                Text(
                    text = if (spokenReplies) "Spoken replies on" else "Spoken replies off",
                    color = colors.accent,
                    fontSize = 12.sp,
                )
            }
        }
        Text(providerDetail, color = colors.textSecondary, fontSize = 11.sp)
    }
}

private val PHASE_LABEL: Map<VoiceTurnMachine.Phase, String> = mapOf(
    VoiceTurnMachine.Phase.IDLE to "Idle",
    VoiceTurnMachine.Phase.LISTENING to "Listening",
    VoiceTurnMachine.Phase.PROCESSING to "Thinking",
    VoiceTurnMachine.Phase.SPEAKING to "Speaking",
    VoiceTurnMachine.Phase.ERROR to "Unavailable",
)

private val PHASE_HINT: Map<VoiceTurnMachine.Phase, String> = mapOf(
    VoiceTurnMachine.Phase.IDLE to "Voice mode is closed.",
    VoiceTurnMachine.Phase.LISTENING to "Speak when you are ready.",
    VoiceTurnMachine.Phase.PROCESSING to "Copilot is working on your request.",
    VoiceTurnMachine.Phase.SPEAKING to "Copilot is speaking. Speak over it to interrupt.",
    VoiceTurnMachine.Phase.ERROR to "Voice mode cannot run right now.",
)
