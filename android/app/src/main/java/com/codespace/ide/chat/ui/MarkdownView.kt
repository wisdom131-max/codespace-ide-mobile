package com.codespace.ide.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * BATCH 7 — the Markdown renderer plus the two code-block actions.
 *
 * Deliberately small and token-driven: no new colours, no new layout, no composer change.
 * Every block uses the panel's existing type scale and the 8-12dp panel radius rule, so a
 * Markdown reply still looks like chat rather than a document viewer.
 *
 * SCROLLING: a long code block scrolls HORIZONTALLY inside its card. Wrapping instead would
 * turn one 200-column JSON line into thirty rows and bury the conversation, and letting it
 * grow unbounded would make the transcript jump on every streamed chunk.
 *
 * COPY AND INSERT ARE THE ONLY ACTIONS, and neither can damage work: Copy puts the code on
 * the clipboard verbatim, Insert goes through the editor's own caret path and replaces only
 * what the user had selected. When no editor is open the card says so instead of silently
 * doing nothing.
 */
@Composable
fun MarkdownView(
    text: String,
    cursorOn: Boolean = false,
    /** BATCH 7: returns false when there is no editor to insert into. */
    onInsertCode: ((String) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    // Keyed on the text: identical text never re-parses, so a settled reply costs nothing
    // while the transcript recomposes around it.
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val c = ChatTokens.LocalChatColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> MarkdownText(block.spans, ChatTokens.FONT_BODY.sp, c.text)
                is MdBlock.Heading -> MarkdownText(
                    spans = block.spans,
                    fontSize = when (block.level) { 1 -> 18f; 2 -> 16f; else -> 15f }.sp,
                    color = c.text,
                    heading = true,
                )
                is MdBlock.Bullet -> Row(
                    modifier = if (block.indent > 0) Modifier.padding(start = (block.indent * 12).dp) else Modifier,
                ) {
                    Text("\u2022  ", color = c.textSecondary, fontSize = ChatTokens.FONT_BODY.sp)
                    MarkdownText(block.spans, ChatTokens.FONT_BODY.sp, c.text)
                }
                is MdBlock.Quote -> Row {
                    Text("\u2502 ", color = c.border, fontSize = ChatTokens.FONT_SMALL.sp)
                    MarkdownText(block.spans, ChatTokens.FONT_SMALL.sp, c.textSecondary, italic = true)
                }
                is MdBlock.Code -> CodeBlockCard(
                    language = block.language,
                    code = block.code,
                    stillOpen = !block.closed,
                    onInsertCode = onInsertCode,
                )
            }
        }
        if (cursorOn) {
            // The streaming cursor is a glyph, never a parsed span: a half-typed "**" or
            // fence must not be able to turn it into formatting.
            Text("\u258C", color = c.accent, fontSize = ChatTokens.FONT_BODY.sp)
        }
    }
}

@Composable
private fun MarkdownText(
    spans: List<MdSpan>,
    fontSize: TextUnit,
    color: Color,
    heading: Boolean = false,
    italic: Boolean = false,
) {
    val c = ChatTokens.LocalChatColors.current
    val annotated = remember(spans, heading, italic, color, c.accent) {
        buildAnnotatedString {
            for (s in spans) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (s.bold || heading) FontWeight.SemiBold else null,
                        fontStyle = if (s.italic || italic) FontStyle.Italic else null,
                        fontFamily = if (s.code) FontFamily.Monospace else null,
                        color = if (s.code) c.accent else color,
                    ),
                ) { append(s.text) }
            }
        }
    }
    Text(annotated, fontSize = fontSize)
}

/**
 * One fenced block: language label, Copy, Insert, and the code itself, verbatim.
 *
 * The actions are plain text hit targets on the existing accent colour - the same pattern
 * the error and approval cards already use, so nothing new has to be learned.
 */
@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    stillOpen: Boolean = false,
    onInsertCode: ((String) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    val clipboard = LocalClipboardManager.current
    var outcome by remember(code) { mutableStateOf<String?>(null) }

    // The label clears itself; a permanent "Inserted" would describe a moment that passed.
    LaunchedEffect(outcome) {
        if (outcome != null) { delay(1_800); outcome = null }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ChatTokens.GAP_TIGHT.dp)
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (language.isBlank()) "code" else language,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (stillOpen) {
                Text(
                    "still streaming",
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_MICRO.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // Item 11: the code block carried two full-size word buttons ("Copy", "Insert")
            // that dominated a block of monocode on a phone. They are now small glyphs with the
            // same two actions and the same result feedback; nothing else about the block
            // changed, and the code text is still selectable.
            Text(
                "\u29C9",
                color = c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .clickable(onClickLabel = "Copy code") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                        outcome = "Copied"
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                "\u21B5",
                color = c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier
                    .clickable(onClickLabel = "Insert code at cursor") {
                        val ok = onInsertCode?.invoke(code) ?: false
                        outcome = if (ok) "Inserted" else "No editor open"
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            text = code,
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
        )
        outcome?.let {
            Text(it, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}
