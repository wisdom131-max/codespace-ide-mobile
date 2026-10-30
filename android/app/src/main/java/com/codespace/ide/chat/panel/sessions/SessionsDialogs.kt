package com.codespace.ide.chat.panel.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.chat.session.SessionFilter
import com.codespace.ide.chat.session.SessionQuery
import com.codespace.ide.chat.session.SessionSort
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * M2 — the Sessions panel's four dialogs.
 *
 * Written as plain rounded surfaces rather than Material sheets: the chat surface has its own
 * generated tokens (12 dp panels, 8 dp chips, 12/10 padding) and a Material sheet would not
 * match them. Every dialog is dismissible by its own cancel affordance.
 *
 * The filter dialog lists ALL EIGHT filters of the reference, in the reference order. Two of
 * them cannot be produced by anything in VN Code today, so they are rendered with their reason
 * and are not selectable. That is the difference between a control that is honest about its
 * data and a control that pretends.
 */
@Composable
internal fun RowActionsDialog(
    row: SessionRowUi,
    colors: ChatColors,
    onPick: (SessionRowAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(row.title, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            SessionsViewLogic.rowActions(row.archived).forEach { action ->
                MenuRow(
                    label = action.label,
                    colors = colors,
                    destructive = action == SessionRowAction.DELETE,
                    onClick = { onPick(action) },
                )
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            MenuRow(SessionsPanelSpec.DELETE_CANCEL, colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun FilterDialog(
    query: SessionQuery,
    colors: ChatColors,
    onToggle: (SessionFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(SessionsPanelSpec.FILTER_TITLE, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                SessionsViewLogic.filters().forEach { filter ->
                    val usable = SessionsViewLogic.isUsable(filter)
                    FilterRow(
                        filter = filter,
                        selected = filter in query.filters,
                        usable = usable,
                        colors = colors,
                        onToggle = { onToggle(filter) },
                    )
                }
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            MenuRow("Close", colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun FilterRow(
    filter: SessionFilter,
    selected: Boolean,
    usable: Boolean,
    colors: ChatColors,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .then(if (usable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "✓" else " ",
            color = colors.accent,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier.width((ChatTokens.GAP * 2).dp),
        )
        Text(
            filter.label,
            color = if (usable) colors.text else colors.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            modifier = Modifier.weight(1f),
        )
        if (!usable) {
            Text(
                SessionsPanelSpec.UNAVAILABLE_NOTE,
                color = colors.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
            )
        }
    }
}

@Composable
internal fun SortDialog(
    current: SessionSort,
    colors: ChatColors,
    onPick: (SessionSort) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(SessionsPanelSpec.SORT_TITLE, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            SessionSort.entries.forEach { sort ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
                        .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                        .clickable { onPick(sort) }
                        .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (sort == current) "✓" else " ",
                        color = colors.accent,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier.width((ChatTokens.GAP * 2).dp),
                    )
                    Text(sort.label, color = colors.text, fontSize = ChatTokens.FONT_SMALL.sp)
                }
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            MenuRow("Close", colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun SimpleMenuDialog(
    title: String,
    items: List<String>,
    colors: ChatColors,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(title, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            items.forEachIndexed { i, label ->
                MenuRow(label, colors, destructive = false, onClick = { onPick(i) })
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            MenuRow("Close", colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    confirmIsDestructive: Boolean,
    colors: ChatColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(title, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP_TIGHT.dp))
            Text(body, color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            MenuRow(confirm, colors, destructive = confirmIsDestructive, onClick = onConfirm)
            MenuRow(SessionsPanelSpec.DELETE_CANCEL, colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun RenameDialog(
    initial: String,
    colors: ChatColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(colors) {
            Text(SessionsPanelSpec.RENAME_TITLE, color = colors.text, fontSize = ChatTokens.FONT_BODY.sp)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .background(colors.input)
                    .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colors.text,
                        fontSize = ChatTokens.FONT_BODY.sp,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            // A blank title is refused by the store; keeping the button enabled and reporting
            // the refusal is more honest than a silently dead button.
            MenuRow(SessionsPanelSpec.RENAME_CONFIRM, colors, destructive = false, onClick = { onConfirm(text) })
            MenuRow(SessionsPanelSpec.DELETE_CANCEL, colors, destructive = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun MenuRow(label: String, colors: ChatColors, destructive: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = if (destructive) colors.error else colors.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
        )
    }
}

@Composable
private fun Surface(colors: ChatColors, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}
