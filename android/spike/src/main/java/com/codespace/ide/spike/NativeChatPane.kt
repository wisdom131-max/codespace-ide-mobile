package com.codespace.ide.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pane A — native Compose chat with the spike design tokens. Selection and
 * copy work DURING streaming via SelectionContainer; fling via LazyColumn.
 * Composables stay extracted (64KB rule) and panels follow the 8-12dp/
 * 12h-10v corner/padding rule.
 */
data class SpikeMsg(val id: Int, val role: String, val text: String)

@Composable
fun NativeChatPane(messages: SnapshotStateList<SpikeMsg>, modifier: Modifier) {
    SelectionContainer {
        LazyColumn(
            modifier = modifier
                .background(SpikeTokens.bg)
                .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                MessageBubble(m)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: SpikeMsg) {
    val bg = if (m.role == "user") SpikeTokens.bubbleUser
             else if (m.role == "error") SpikeTokens.bubbleError
             else SpikeTokens.bubbleAssistant
    Box(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(SpikeTokens.cornerBubble))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV)
    ) {
        MiniMarkdown(m.text)
    }
}

/**
 * Mini markdown renderer (### h3, **bold**, `inline`, fenced blocks) — the
 * SAME feature set as spike.js so both panes render identical bytes. Pure
 * function, no state: recomposition cost is what the benchmark measures.
 */
@Composable
fun MiniMarkdown(text: String) {
    Column {
        val segments = text.split("```")
        segments.forEachIndexed { i, seg ->
            if (i % 2 == 1) {
                CodeBlock(seg)
            } else {
                InlineSegments(seg)
            }
        }
    }
}

@Composable
private fun InlineSegments(seg: String) {
    val lines = seg.split("\n")
    lines.forEachIndexed { li, line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("### ")) {
            Text(trimmed.removePrefix("### "), fontWeight = FontWeight.Bold)
        } else if (trimmed.isNotEmpty()) {
            Text(boldAware(trimmed), fontSize = SpikeTokens.fontBody)
        } else if (li < lines.size - 1) {
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** `inline code` + **bold** splitting on one line, inline-free. */
@Composable
private fun boldAware(line: String) {
    // Simplest honest pass: render inline-code spans in monospace by splitting.
    val pieces = line.split("`")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        pieces.forEachIndexed { i, p ->
            Text(
                p.replace("**", ""),
                fontFamily = if (i % 2 == 1) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                color = if (i % 2 == 1) SpikeTokens.accent else SpikeTokens.textPrimary,
                fontSize = if (i % 2 == 1) SpikeTokens.fontMono else SpikeTokens.fontBody
            )
        }
    }
}

@Composable
private fun CodeBlock(seg: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(SpikeTokens.codeBg, RoundedCornerShape(SpikeTokens.cornerBubble))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV)
    ) {
        Text(
            seg.trim().split("\n").joinToString("\n"),
            color = SpikeTokens.codeFg,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = SpikeTokens.fontMono
        )
    }
}
