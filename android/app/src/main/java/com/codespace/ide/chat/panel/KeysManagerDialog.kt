package com.codespace.ide.chat.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.keys.StoredKeySlot
import com.codespace.ide.chat.ui.ChatTokens
import com.codespace.ide.engine.ProviderPresets

/**
 * WP7: key management returns HOME. Lists every preset provider plus every
 * stored slot that is NOT a preset slot (legacy names like ai_google, custom
 * endpoint keys). Add, replace, delete primary keys and POOL keys
 * (ai_<provider>_2/_3...). Values are NEVER displayed - presence only.
 * All writes go through SecureTokenStore (encrypted at rest); deleting a key
 * here never touches custom endpoints. UI rule: rounded 8-12dp, padding 12
 * horizontal / 10 vertical.
 */
@Composable
fun KeysManagerDialog(
    tokenStore: com.codespace.ide.data.SecureTokenStore,
    onDismiss: () -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    var bump by remember { mutableStateOf(0) }
    var editingSlot by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }
    val slots: List<StoredKeySlot> = remember(bump) {
        StoredCredentialsReader(tokenStore, com.codespace.ide.chat.CustomEndpointStore)
            .snapshot().keySlots
    }

    fun write(slotSuffix: String, value: String?) {
        tokenStore.setAiKey(slotSuffix, value)
        bump++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keys") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ProviderPresets.ALL.filter { it.requiresKey }.forEach { p ->
                    KeyProviderSection(
                        label = p.label,
                        providerId = p.providerId,
                        slots = slots.filter { it.providerId == p.providerId },
                        editingSlot = editingSlot,
                        editValue = editValue,
                        onEditStart = { slot -> editingSlot = slot; editValue = "" },
                        onEditCancel = { editingSlot = null },
                        onValueChange = { editValue = it },
                        onWrite = ::write,
                    )
                }
                val covered = ProviderPresets.ALL.map { it.providerId }.toSet()
                val extra = slots.filter { it.providerId !in covered }
                if (extra.isNotEmpty()) {
                    Text(
                        "Other stored slots",
                        color = c.textSecondary,
                        fontSize = ChatTokens.FONT_SMALL.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = ChatTokens.GAP.dp),
                    )
                    extra.forEach { slot ->
                        KeySlotRow(
                            slotName = slot.slotName,
                            hasKey = slot.hasKey,
                            editing = editingSlot == slot.slotName,
                            editValue = editValue,
                            onStartEdit = { editingSlot = slot.slotName; editValue = "" },
                            onCancelEdit = { editingSlot = null },
                            onValueChange = { editValue = it },
                            onSave = { write(slot.slotName.removePrefix("ai_"), editValue.trim()); editingSlot = null },
                            onDelete = { write(slot.slotName.removePrefix("ai_"), null) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** One preset provider: its stored slots (primary first, then pools) + add row. */
@Composable
private fun KeyProviderSection(
    label: String,
    providerId: String,
    slots: List<StoredKeySlot>,
    editingSlot: String?,
    editValue: String,
    onEditStart: (String) -> Unit,
    onEditCancel: () -> Unit,
    onValueChange: (String) -> Unit,
    onWrite: (slotSuffix: String, value: String?) -> Unit,
) {
    val c = ChatTokens.LocalChatColors.current
    val sorted = slots.sortedBy { it.poolIndex ?: 0 }
    Text(
        label,
        color = c.text,
        fontSize = ChatTokens.FONT_SMALL.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = ChatTokens.GAP.dp),
    )
    if (sorted.isEmpty()) {
        KeySlotRow(
            slotName = "ai_" + providerId,
            hasKey = false,
            editing = editingSlot == "ai_" + providerId,
            editValue = editValue,
            onStartEdit = { onEditStart("ai_" + providerId) },
            onCancelEdit = onEditCancel,
            onValueChange = onValueChange,
            onSave = { onWrite(providerId, editValue.trim()) },
            onDelete = { onWrite(providerId, null) },
        )
        return
    }
    sorted.forEach { slot ->
        KeySlotRow(
            slotName = slot.slotName,
            hasKey = slot.hasKey,
            editing = editingSlot == slot.slotName,
            editValue = editValue,
            onStartEdit = { onEditStart(slot.slotName) },
            onCancelEdit = onEditCancel,
            onValueChange = onValueChange,
            onSave = { onWrite(slot.slotName.removePrefix("ai_"), editValue.trim()) },
            onDelete = { onWrite(slot.slotName.removePrefix("ai_"), null) },
        )
    }
    val nextPool = (sorted.mapNotNull { it.poolIndex }.maxOrNull() ?: 1) + 1
    val addSlot = "ai_" + providerId + "_" + nextPool
    KeySlotRow(
        slotName = addSlot,
        hasKey = false,
        isAddRow = true,
        editing = editingSlot == addSlot,
        editValue = editValue,
        onStartEdit = { onEditStart(addSlot) },
        onCancelEdit = onEditCancel,
        onValueChange = onValueChange,
        onSave = { onWrite(addSlot.removePrefix("ai_"), editValue.trim()) },
        onDelete = {},
    )
}

/** One slot row: name, presence (never the value), replace/delete, inline editor. */
@Composable
private fun KeySlotRow(
    slotName: String,
    hasKey: Boolean,
    editing: Boolean,
    editValue: String,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    isAddRow: Boolean = false,
) {
    val c = ChatTokens.LocalChatColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = ChatTokens.GAP.dp)
            .background(c.surfaceAlt, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                (if (isAddRow) "+ " else "") + slotName +
                    (if (isAddRow) "" else if (hasKey) "  -  stored" else "  -  empty"),
                color = if (hasKey || isAddRow) c.text else c.textSecondary,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (isAddRow) "Add" else "Replace",
                color = c.accent,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.clickable { onStartEdit() }.padding(horizontal = ChatTokens.GAP.dp),
            )
            if (!isAddRow) {
                Text(
                    "Delete",
                    color = c.warning,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { onDelete() },
                )
            }
        }
        if (editing) {
            TextField(
                value = editValue,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text("Paste key (never shown)", fontSize = ChatTokens.FONT_MICRO.sp) },
                modifier = Modifier.fillMaxWidth().padding(vertical = ChatTokens.GAP.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Save",
                    color = c.accent,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { onSave() }.padding(horizontal = ChatTokens.GAP.dp),
                )
                Text(
                    "Cancel",
                    color = c.textSecondary,
                    fontSize = ChatTokens.FONT_SMALL.sp,
                    modifier = Modifier.clickable { onCancelEdit() },
                )
            }
        }
    }
}
