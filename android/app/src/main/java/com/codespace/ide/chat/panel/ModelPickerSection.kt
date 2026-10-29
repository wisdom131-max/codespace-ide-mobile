package com.codespace.ide.chat.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatTokens

/**
 * BATCH 8 — the spec, on screen.
 *
 * This is the smallest real wiring of [ModelPickerSpec]: a search field, the spec's rows
 * (preferred models, the "Other Models" group, per-row summary and check mark), and a tap
 * that hands the chosen model to the SAME onApply the dialog already used - so the model
 * that is picked is the model the next request uses, and nothing about persistence changes.
 *
 * NOT RESTORED FROM THE ARCHIVE: no popup, no gear submenu animation, no cost multipliers.
 * The picker lives inside the existing "Provider and model" dialog, in the existing type
 * scale, so the panel looks the same before and after.
 */
@Composable
internal fun ModelPickerSection(
    providerId: String,
    current: PanelSelection,
    models: List<PickerModel>,
    effort: Effort,
    onPick: (PickerModel) -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    var query by remember(providerId) { mutableStateOf("") }
    var otherExpanded by remember(providerId) { mutableStateOf(false) }

    val state = remember(models, current.modelId, effort, query, otherExpanded) {
        ModelPickerSpec.build(
            models = models,
            selected = providerId to current.modelId,
            effort = effort,
            query = query,
            otherExpanded = otherExpanded,
            // Auto routing does not exist in this engine, so the row is absent rather than
            // decorative - the spec's own honesty rule.
            autoNote = null,
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Model",
            color = c.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ModelSearchField(query = query, onQuery = { query = it })
        state.rows.forEach { row ->
            when (row) {
                is PickerRow.Model -> ModelRow(
                    row = row,
                    onClick = { onPick(row.model) },
                )
                is PickerRow.OtherHeader -> Text(
                    ModelPickerSpec.OTHER_MODELS + " (" + row.count + ")" +
                        if (row.expanded) " - tap to collapse" else " - tap to show",
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { otherExpanded = !otherExpanded }
                        .padding(vertical = 6.dp),
                )
                is PickerRow.Auto -> Text(
                    "Auto" + (row.note?.let { " - " + it } ?: ""),
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                )
            }
        }
        state.emptyMessage?.let {
            Text(it, color = c.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
        }
    }
}

@Composable
private fun ModelSearchField(query: String, onQuery: (String) -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    // The dialog's existing field styling, not material's default chrome: the composer was
    // deliberately moved off TextField and the dialog must not reintroduce that look.
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontFamily = FontFamily.SansSerif,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                Text("Search models", color = c.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
            }
            inner()
        },
    )
}

@Composable
private fun ModelRow(row: PickerRow.Model, onClick: () -> Unit) {
    val c = ChatTokens.LocalChatColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (row.selected) "\u2713 " else "") + row.model.displayName,
            color = if (row.selected) c.accent else c.text,
            fontSize = ChatTokens.FONT_SMALL.sp,
            fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .clickable { onClick() }
                .padding(vertical = 6.dp),
        )
        if (row.summary.isNotEmpty()) {
            Text(
                row.summary,
                color = c.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp),
            )
        }
    }
}
