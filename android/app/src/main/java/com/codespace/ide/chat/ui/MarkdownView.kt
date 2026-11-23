package com.codespace.ide.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
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
                if (s.code) {
                    // Code is never a link: a URL inside a snippet must stay copyable text.
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = c.accent),
                    ) { append(s.text) }
                    continue
                }
                // OWNER REPORT (2026-11-23): "there are no clickable links in the chat when the
                // copilot replies with one". The renderer drew every span as inert text, so a URL
                // was visible and dead. VS Code's chat marks a link range in the annotated string
                // and resolves the tap against that range; that is what this does.
                appendLinkAware(
                    text = s.text,
                    base = color,
                    link = c.accent,
                    weight = if (s.bold || heading) FontWeight.SemiBold else null,
                    style = if (s.italic || italic) FontStyle.Italic else null,
                )
            }
        }
    }
    // The tap is resolved by character offset against the annotations, so only the link itself
    // opens - tapping the sentence around it does nothing, and there is no hit box to misalign.
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val uri = LocalUriHandler.current
    Text(
        annotated,
        fontSize = fontSize,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(annotated) {
            detectTapGestures { pos ->
                val l = layout ?: return@detectTapGestures
                val offset = l.getOffsetForPosition(pos)
                annotated.getStringAnnotations(LINK_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { runCatching { uri.openUri(it.item) } }
            }
        },
    )
}

/** The annotation tag every link carries. One tag, so resolution is a single lookup. */
private const val LINK_TAG = "vncode_link"

/** `[label](url)` as a model writes it. */
private val MD_LINK = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")

/** A bare URL, stopped before punctuation a sentence is likely to end with. */
private val BARE_URL = Regex("""https?://[^\s<>()\[\]]+""")

/**
 * Appends one span, turning link text into an underlined range with a tap target.
 *
 * Two shapes are handled because both arrive in real replies: the Markdown form `[docs](https://…)`
 * and a plain pasted URL. The Markdown form shows its LABEL, not its brackets, which is what the
 * author meant to be read.
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendLinkAware(
    text: String,
    base: Color,
    link: Color,
    weight: FontWeight?,
    style: FontStyle?,
) {
    data class Hit(val start: Int, val end: Int, val shown: String, val url: String)

    val hits = mutableListOf<Hit>()
    MD_LINK.findAll(text).forEach { m ->
        hits += Hit(m.range.first, m.range.last + 1, m.groupValues[1], m.groupValues[2])
    }
    BARE_URL.findAll(text).forEach { m ->
        if (hits.none { m.range.first >= it.start && m.range.first < it.end }) {
            // Trailing punctuation belongs to the sentence, not the address.
            val trimmed = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            hits += Hit(m.range.first, m.range.first + trimmed.length, trimmed, trimmed)
        }
    }

    var cursor = 0
    for (hit in hits.sortedBy { it.start }) {
        if (hit.start < cursor) continue
        if (hit.start > cursor) {
            withStyle(SpanStyle(color = base, fontWeight = weight, fontStyle = style)) {
                append(text.substring(cursor, hit.start))
            }
        }
        val from = length
        withStyle(
            SpanStyle(
                color = link,
                fontWeight = weight,
                fontStyle = style,
                textDecoration = TextDecoration.Underline,
            ),
        ) { append(hit.shown) }
        addStringAnnotation(LINK_TAG, hit.url, from, length)
        cursor = hit.end
    }
    if (cursor < text.length) {
        withStyle(SpanStyle(color = base, fontWeight = weight, fontStyle = style)) {
            append(text.substring(cursor))
        }
    }
}

/**
 * One fenced block: language label, one small Copy glyph, and the code itself verbatim.
 * Insert-at-cursor was removed at the owner's request (2026-10-31).
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
            // Owner request 2026-10-31: the insert-at-cursor glyph is gone. Copying the block
            // or tapping the reply's own copy icon covers the same need without a second
            // meaning for the same glyph.
        }
        // CONSTRAINTS AUDIT (owner order 2026-11-07): a chat block can hold a pasted 13,000-line
        // file, and ONE Text that tall throws in Constraints. Draw a capped head and say so;
        // Copy still copies the WHOLE block.
        val capped = remember(code) { com.codespace.ide.editor.TextRenderLimits.cap(code) }
        Text(
            text = capped.first,
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
        )
        capped.second?.let { note ->
            Text(note, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
        outcome?.let {
            Text(it, color = c.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}
