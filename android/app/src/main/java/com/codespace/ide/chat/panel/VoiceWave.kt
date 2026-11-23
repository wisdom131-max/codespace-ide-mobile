package com.codespace.ide.chat.panel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.panel.voice.VoiceLoopPolicy
import com.codespace.ide.chat.panel.voice.VoiceLoopState
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import kotlin.math.abs

/**
 * OWNER REQUEST (2026-11-23): "I need the newly built streaming icon to be put the way you see in
 * the images... so when you tap on the mic icon it starts the animations I asked for, but the way
 * you see it in the screenshot."
 *
 * The screenshot puts the soundwave in the HEADER, between Sessions and +, not on a black full-screen
 * surface. So the wave lives there now: the header chip IS the meter, it animates in place, and the
 * big surface is gone. Same cream pills, same idea, at the size the header actually has.
 *
 * The chip is never a dead glyph: at rest it shows a low static wave so it still reads as "voice",
 * and from the first tap it breathes, rises with the microphone, pulses while the reply is being
 * written, and rides the reading voice while speaking.
 */
@Composable
internal fun VoiceMicChip(
    state: VoiceLoopState,
    level: Float,
    colors: ChatColors,
    onClick: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "voice-mic").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-pulse",
    )
    val smoothed by animateFloatAsState(
        targetValue = VoiceLoopPolicy.targetAmplitude(state, level, pulse),
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "voice-level",
    )
    val active = state != VoiceLoopState.IDLE
    val tint = if (active) colors.accent else colors.textSecondary
    Box(
        Modifier
            .heightIn(min = ChatToolbarLayout.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp,
                vertical = ChatTokens.CHIP_PAD_VERTICAL.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(24.dp).height(15.dp)) {
            val bars = 5
            val slot = size.width / bars
            val barWidth = slot * 0.46f
            val mid = size.height / 2f
            for (i in 0 until bars) {
                val t = i / (bars - 1f)
                // Symmetric, short at the ends: the reference shape, at header scale.
                val envelope = 1f - abs(t - 0.5f) * 1.35f
                val h = (size.height * (0.18f + 0.82f * smoothed) * (0.45f + 0.55f * envelope))
                    .coerceIn(2.5f, size.height)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, mid - h / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

/**
 * The one-line state strip under the header: the live transcript while listening, what is happening
 * while thinking, and the interruption hint while speaking. Stop is always one tap away.
 */
@Composable
internal fun VoiceStrip(
    state: VoiceLoopState,
    partial: String,
    colors: ChatColors,
    onStop: () -> Unit,
) {
    val label = when (state) {
        VoiceLoopState.IDLE -> ""
        VoiceLoopState.LISTENING -> if (partial.isBlank()) "Listening" else partial
        VoiceLoopState.THINKING -> "Working on it"
        VoiceLoopState.SPEAKING -> "Speaking · talk to interrupt"
    }
    if (label.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .background(colors.surfaceAlt)
            .padding(
                horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp,
                vertical = ChatTokens.CHIP_PAD_VERTICAL.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = colors.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .clickable(onClick = onStop)
                .padding(horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
        ) {
            Text("Stop", color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
        }
    }
}
