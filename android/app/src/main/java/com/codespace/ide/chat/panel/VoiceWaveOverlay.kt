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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * OWNER REQUEST (2026-11-23): the mic should "work perfectly without showing the google icon on
 * tap", look like the reference (a row of cream pill bars on black), and be animated.
 *
 * This is that screen. It is ours end to end: the app records through its own recogniser service
 * binding, so no Google activity is launched and nothing of theirs is drawn. The bars are driven by
 * the microphone's real level, so they are a meter rather than a decoration - when the room is
 * silent they breathe, and when the owner speaks they rise.
 *
 * Palette is fixed cream on near-black on purpose: this is a voice surface, not a themed panel, and
 * it must look the same in every theme.
 */
private val WaveCream = Color(0xFFEFE7D8)
private val WaveInk = Color(0xFF08080A)
private val WaveMuted = Color(0xFF8C877C)

@Composable
internal fun VoiceWaveOverlay(
    /** Live transcript as the recogniser hears it, or blank before the first partial arrives. */
    partial: String,
    /** 0..1 microphone level, already smoothed by the recogniser's RMS callback. */
    level: Float,
    /** Shown when the recogniser is a cloud one, because then the audio does leave the device. */
    disclosure: String? = null,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val smoothed by animateFloatAsState(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
            label = "mic-level",
        )
        // Idle breathing, so a silent room still looks alive and the owner can see it is listening.
        val idle = rememberInfiniteTransition(label = "idle")
        val breath by idle.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(WaveInk)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                    val bars = 42
                    val slot = size.width / bars
                    val barWidth = slot * 0.44f
                    val mid = size.height / 2f
                    // Real level and idle breath, whichever is stronger.
                    val amplitude = maxOf(0.16f + 0.22f * breath, smoothed)
                    for (i in 0 until bars) {
                        val t = i / (bars - 1f)
                        // Symmetric envelope: tall in the middle, short at the ends, like the image.
                        val envelope = 1f - abs(t - 0.5f) * 2f
                        // A slow travelling wobble so the row does not look like a bar chart.
                        val wobble = 0.70f + 0.30f * sin(t * PI * 3f + breath * 5.2f)
                        val half = (mid * 0.92f) * envelope * wobble * (0.30f + 0.70f * amplitude)
                        val h = (half * 2f).coerceAtLeast(6f)
                        drawRoundRect(
                            color = WaveCream,
                            topLeft = Offset(i * slot + (slot - barWidth) / 2f, mid - h / 2f),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(barWidth / 2f),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    if (partial.isBlank()) "Listening" else partial,
                    color = WaveCream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tap anywhere to stop",
                    color = WaveMuted,
                    fontSize = 12.sp,
                )
                if (disclosure != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        disclosure,
                        color = WaveMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF141416), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
