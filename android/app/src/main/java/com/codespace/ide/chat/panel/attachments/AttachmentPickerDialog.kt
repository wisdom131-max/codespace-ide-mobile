package com.codespace.ide.chat.panel.attachments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import java.io.File

/**
 * The attachment picker — a floating file manager over the conversation, per the owner's
 * reference: a dark modal, three tiles across, type-badged file icons, folders that open into
 * their own listing, a "＋ Add from Phone" action at the bottom and explicit way(s) back.
 *
 * Every tile is real: a folder opens the folder it names, a file is staged into the composer as
 * a chip through the same ChatSession.addPending path the system picker uses, so the 10 MB cap,
 * the credential-name refusal and the model-capability gate all still apply. Nothing here can
 * attach something the transcript cannot carry.
 */
@Composable
internal fun AttachmentPickerDialog(
    root: String,
    colors: ChatColors,
    onDismiss: () -> Unit,
    /** Returns null when staged, or the reason it was refused. */
    onStage: (File) -> String?,
    onAddFromPhone: () -> Unit,
) {
    var dir by remember(root) { mutableStateOf(root) }
    var problem by remember(root) { mutableStateOf<String?>(null) }
    var staged by remember(root) { mutableStateOf(0) }
    val listing = remember(dir) { AttachmentPicker.listing(File(dir)) }
    val parent = AttachmentPicker.parentOf(dir, root)
    val crumb = remember(dir, root) {
        val rel = dir.removePrefix(File(root).absolutePath).trim('/')
        if (rel.isEmpty()) File(root).name else File(root).name + " / " + rel.replace("/", " / ")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = colors.surface,
                contentColor = colors.text,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 560.dp)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    // Swallows taps so a mis-tap inside the sheet never dismisses it.
                    .clickable(enabled = false) {},
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    PickerHeader(
                        crumb = crumb,
                        canGoUp = parent != null,
                        colors = colors,
                        onUp = { parent?.let { dir = it; problem = null } },
                        onClose = onDismiss,
                    )
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider(color = colors.border)
                    Spacer(Modifier.size(8.dp))

                    Box(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        when {
                            listing.problem != null -> PickerNotice(listing.problem!!, colors, colors.warningBg)
                            listing.entries.isEmpty() -> PickerNotice("This folder is empty.", colors, colors.surfaceAlt)
                            else -> PickerGrid(
                                entries = listing.entries,
                                colors = colors,
                                onOpen = { entry ->
                                    if (entry.isDir) { dir = entry.path; problem = null }
                                    else problem = onStage(File(entry.path)).also { if (it == null) staged++ }
                                },
                            )
                        }
                    }

                    if (listing.truncated) {
                        Text(
                            "Showing the first " + AttachmentPicker.MAX_ENTRIES + " entries in this folder.",
                            color = colors.textSecondary,
                            fontSize = ChatTokens.FONT_MICRO.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    problem?.let {
                        Text(
                            it,
                            color = colors.error,
                            fontSize = ChatTokens.FONT_MICRO.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    Spacer(Modifier.size(10.dp))
                    HorizontalDivider(color = colors.border)
                    Spacer(Modifier.size(10.dp))
                    PickerFooter(
                        colors = colors,
                        staged = staged,
                        onAddFromPhone = onAddFromPhone,
                        onDone = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(
    crumb: String,
    canGoUp: Boolean,
    colors: ChatColors,
    onUp: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Way back, disabled at the project root rather than silently doing nothing.
        Text(
            "↑ Up",
            color = if (canGoUp) colors.accent else colors.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(enabled = canGoUp, onClickLabel = "Go up one folder", onClick = onUp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            crumb,
            color = colors.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Text(
            "✕",
            color = colors.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier
                .clickable(onClickLabel = "Close the file picker", onClick = onClose)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PickerGrid(
    entries: List<PickerEntry>,
    colors: ChatColors,
    onOpen: (PickerEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { entry ->
                    PickerTile(entry, colors, Modifier.weight(1f)) { onOpen(entry) }
                }
                // Keep the last row's tiles the same width as a full row.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PickerTile(
    entry: PickerEntry,
    colors: ChatColors,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(
                onClickLabel = if (entry.isDir) "Open folder " + entry.name else "Attach " + entry.name,
                onClick = onClick,
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (entry.isDir) {
            Box(
                Modifier.size(46.dp).background(colors.surfaceAlt, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { FolderGlyph(Color(0xFF4A90D9)) }
        } else {
            Box(
                Modifier
                    .size(46.dp)
                    .background(badgeColor(entry, colors), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    AttachmentPicker.badgeFor(entry),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            entry.name,
            color = colors.text,
            fontSize = ChatTokens.FONT_MICRO.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!entry.isDir && entry.sizeBytes > 0) {
            Text(
                AttachmentPicker.sizeLabel(entry.sizeBytes),
                color = colors.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
        }
    }
}

/** Drawn, not an emoji and not a bitmap: a folder is a tab plus a body, two rounded rects. */
@Composable
private fun FolderGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val tabH = h * 0.22f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, tabH * 1.2f),
            size = androidx.compose.ui.geometry.Size(w, h - tabH * 1.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(w * 0.46f, tabH * 2.1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
        )
    }
}

@Composable
private fun PickerFooter(
    colors: ChatColors,
    staged: Int,
    onAddFromPhone: () -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f)
                .heightIn(min = 40.dp)
                .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .background(colors.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .clickable(onClickLabel = "Add a file from this phone", onClick = onAddFromPhone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "＋ Add from Phone",
                color = colors.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (staged > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .background(colors.accent, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickable(onClickLabel = "Finish attaching", onClick = onDone)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Done (" + staged + ")",
                    color = colors.bg,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PickerNotice(text: String, colors: ChatColors, background: Color) {
    Box(
        Modifier.fillMaxWidth()
            .background(background, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(12.dp),
    ) {
        Text(text, color = colors.text, fontSize = ChatTokens.FONT_SMALL.sp)
    }
}

/**
 * One colour per file family, so a folder of mixed types is readable at a glance. These are the
 * document-family colours everyone already knows (Word blue, Excel green, PowerPoint orange).
 */
private fun badgeColor(entry: PickerEntry, colors: ChatColors): Color = when (entry.kind) {
    PickerKind.WORD -> Color(0xFF2B579A)
    PickerKind.EXCEL -> Color(0xFF217346)
    PickerKind.POWERPOINT -> Color(0xFFD24726)
    PickerKind.PDF -> Color(0xFFB3111B)
    PickerKind.IMAGE -> Color(0xFF6A4C93)
    PickerKind.ARCHIVE -> Color(0xFF8A6D3B)
    PickerKind.AUDIO -> Color(0xFF1F7A5A)
    PickerKind.VIDEO -> Color(0xFF7A3E9D)
    PickerKind.CODE, PickerKind.MARKDOWN -> colors.accent
    PickerKind.TEXT -> Color(0xFF5A6570)
    else -> Color(0xFF4A5560)
}
