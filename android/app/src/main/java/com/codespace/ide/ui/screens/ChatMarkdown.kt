package com.codespace.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Input
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CHAT MARKDOWN RENDERER (Round 1, Copilot-chat parity):
 *
 * Compose-native markdown for assistant chat bubbles — the existing
 * editor/MarkdownRenderer.kt renders to HTML for the WebView preview, so chat
 * gets its own light parser here. Supports: fenced code blocks (with Copy /
 * Insert-at-cursor actions), headers, ordered/unordered lists, blockquotes,
 * tables (monospace rows), inline bold/italic/code/links (styled-only links).
 * This file owns NO chat state — everything is a pure function of
 * (text, colors, callbacks), extracted per the 64KB rule.
 */

// ── Block model ──────────────────────────────────────────────────────────────
private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Header(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListGroup(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Rule(val text: String = "") : MdBlock
    data class Mono(val text: String) : MdBlock
}

private val HEADER_RE = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET_RE = Regex("^([-*])\\s+")
private val ORDERED_RE = Regex("^\\d+[.)]\\s+")
private val TABLE_SEP_RE = Regex("^\\|?[\\s:|-]+\\|?$")

internal fun parseMarkdownBlocks(md: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = md.split('\n')
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) blocks.add(MdBlock.Paragraph(t))
        para.setLength(0)
    }
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                flushPara()
                val lang = line.trimStart().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
            }
            HEADER_RE.matchEntire(line) != null -> {
                flushPara()
                val m = HEADER_RE.matchEntire(line)!!
                blocks.add(MdBlock.Header(m.groupValues[1].length, m.groupValues[2].trim()))
            }
            line.trim() == ">" || line.startsWith("> ") -> {
                flushPara()
                val quote = StringBuilder()
                while (i < lines.size && (lines[i].trim() == ">" || lines[i].trimEnd().startsWith("> "))) {
                    quote.append(lines[i].trimEnd().removePrefix(">").trim()).append(' ')
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
                continue
            }
            BULLET_RE.containsMatchIn(line.trim()) -> {
                flushPara()
                val items = ArrayList<String>()
                while (i < lines.size && BULLET_RE.containsMatchIn(lines[i].trim())) {
                    items.add(lines[i].trim().substring(2).trim())
                    i++
                }
                blocks.add(MdBlock.ListGroup(false, items))
                continue
            }
            ORDERED_RE.containsMatchIn(line.trim()) -> {
                flushPara()
                val items = ArrayList<String>()
                while (i < lines.size && ORDERED_RE.containsMatchIn(lines[i].trim())) {
                    items.add(lines[i].trim().replaceFirst(ORDERED_RE, ""))
                    i++
                }
                blocks.add(MdBlock.ListGroup(true, items))
                continue
            }
            line.trim() == "---" || line.trim() == "***" -> {
                flushPara()
                blocks.add(MdBlock.Rule())
            }
            line.trim().startsWith("|") && line.trim().endsWith("|") && line.trim().length > 1 -> {
                // Tables (R1): aligned monospace rows — no grid layout yet (future round).
                flushPara()
                val rows = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val row = lines[i].trim()
                    if (!TABLE_SEP_RE.matches(row)) {
                        rows.append(row.removePrefix("|").removeSuffix("|").trim()).append('\n')
                    }
                    i++
                }
                blocks.add(MdBlock.Mono(rows.toString().trimEnd('\n')))
                continue
            }
            line.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara()
    return blocks
}

// ── Inline styling (single left-to-right token walk) ─────────────────────────
private val CODE_SPAN = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))
private val BOLD_SPAN = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC_SPAN = SpanStyle(fontStyle = FontStyle.Italic)
private val LINK_SPAN = SpanStyle(color = Color(0xFF569CD6), textDecoration = TextDecoration.Underline)

internal fun inlineAnnotated(
    text: String,
    accent: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // `inline code` — literal contents, no nested styling
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(CODE_SPAN.copy(background = accent.copy(alpha = 0.12f)))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            // **bold**
            c == '*' && text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    pushStyle(BOLD_SPAN)
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }
            // *italic* (bold ** is matched earlier, so this is a single asterisk)
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(ITALIC_SPAN)
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(c)
                    i++
                }
            }
            // [label](url) — styled only, not clickable (R1)
            c == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0) text.indexOf('(', close + 1) else -1
                if (close > i && open == close + 1) {
                    val endParen = text.indexOf(')', open + 1)
                    if (endParen > open) {
                        pushStyle(LINK_SPAN)
                        append(text.substring(i + 1, close))
                        pop()
                        i = endParen + 1
                    } else {
                        append(c)
                        i++
                    }
                } else {
                    append(c)
                    i++
                }
            }
            else -> {
                // plain run until the next special char
                var j = i
                while (j < text.length && text[j] != '`' && text[j] != '*' && text[j] != '[') j++
                append(text.substring(i, j))
                i = j
            }
        }
    }
}

/**
 * One assistant message body. Renders markdown blocks top-to-bottom inside the
 * caller's bubble Surface. [onCopyCode] and [onInsertCode] are supplied by the
 * panel (clipboard / KeyInsertDispatcher); insert is hidden when null.
 */
@Composable
internal fun ChatMarkdownBody(
    text: String,
    textColor: Color,
    accent: Color,
    surface: Color,
    divider: Color,
    onCopyCode: (String) -> Unit,
    onInsertCode: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b -> RenderMdBlock(b, textColor, accent, surface, divider, onCopyCode, onInsertCode) }
    }
}

@Composable
private fun RenderMdBlock(
    b: MdBlock,
    textColor: Color,
    accent: Color,
    surface: Color,
    divider: Color,
    onCopyCode: (String) -> Unit,
    onInsertCode: ((String) -> Unit)?,
) {
    when (b) {
        is MdBlock.Paragraph -> Text(inlineAnnotated(b.text, accent), color = textColor, fontSize = 13.sp)
        is MdBlock.Header -> Text(
            inlineAnnotated(b.text, accent),
            color = accent,
            fontSize = if (b.level <= 2) 15.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
        is MdBlock.Code -> CodeBlockCard(b.lang, b.code, accent, surface, divider, onCopyCode, onInsertCode)
        is MdBlock.ListGroup -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { idx, item ->
                Row {
                    Text(if (b.ordered) "${idx + 1}. " else "• ", color = accent, fontSize = 13.sp)
                    Text(inlineAnnotated(item, accent), color = textColor, fontSize = 13.sp)
                }
            }
        }
        is MdBlock.Quote -> Surface(color = surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                inlineAnnotated(b.text, accent),
                color = textColor.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        is MdBlock.Rule -> Surface(color = divider, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
        is MdBlock.Mono -> Surface(color = surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                b.text,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CodeBlockCard(
    lang: String,
    code: String,
    accent: Color,
    surface: Color,
    divider: Color,
    onCopyCode: (String) -> Unit,
    onInsertCode: ((String) -> Unit)?,
) {
    Surface(
        color = surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lang.ifBlank { "code" }, fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = accent,
                        modifier = Modifier.size(14.dp).clickableNoRipple { onCopyCode(code) },
                    )
                    if (onInsertCode != null) {
                        Icon(
                            Icons.Default.Input,
                            contentDescription = "Insert at cursor",
                            tint = accent,
                            modifier = Modifier.size(14.dp).clickableNoRipple { onInsertCode(code) },
                        )
                    }
                }
            }
            Text(
                code,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFD4D4D4),
                maxLines = 18,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** No-ripple clickable for small chat icons (no gray flash on tap). */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)
