package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SkillFormat
import com.codespace.ide.data.SkillStore
import com.codespace.ide.security.TrustState

/**
 * PHASE 6b (owner decision 4, 2026-11-14): the Settings side of local skills.
 *
 * A skill lives as one JSON file in `.vncode/skills` inside the open project, or in the app's own
 * folder when no project is open - the scope line says which, because "my skill vanished" is
 * almost always "I changed project". Switching a skill off keeps its file and removes it from what
 * Copilot reads; deleting removes the file, so it asks first.
 *
 * The Connectors Hub is untouched: this is local skills only, and Google Drive import stays out
 * of scope by the same decision.
 */
@Composable
internal fun SkillsSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val projectRoot = com.codespace.ide.security.TrustState.activeProjectRoot(context)
    var reload by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf<SkillStore.Skill?>(null) }
    var confirmDelete by remember { mutableStateOf<SkillStore.Skill?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val listed = remember(reload, projectRoot) { SkillStore.list(context, projectRoot) }
    val skills = when (listed) {
        is SkillStore.ListResult.Ok -> listed.skills
        is SkillStore.ListResult.Unreadable -> emptyList()
    }
    val dir = SkillStore.skillsDir(context, projectRoot)

    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull() ?: "Imported skill.txt"
            val plan = text?.let { SkillImportPlan.fromFile(displayName, it) }
            message = if (plan == null) {
                "Nothing was imported: the file was empty or could not be read."
            } else {
                runCatching { SkillStore.save(context, projectRoot, plan.name, plan.description, plan.body) }
                    .fold(
                        onSuccess = { reload++; "Imported " + plan.name + "." },
                        onFailure = { "Could not import " + plan.name + ": " + (it.message ?: "storage error") },
                    )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skills", color = Color(0xFFD4D4D4), fontSize = 14.sp) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Stored in " + dir.absolutePath,
                    color = Color(0xFF858585), fontSize = 10.sp,
                )
                if (listed is SkillStore.ListResult.Unreadable) {
                    Text(listed.reason, color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
                message?.let { Text(it, color = Color(0xFF858585), fontSize = 11.sp) }
                if (skills.isEmpty()) {
                    Text(
                        "No skills yet. Write one here, or import a file from your phone.",
                        color = Color(0xFF858585), fontSize = 11.sp,
                    )
                }
                skills.forEach { skill ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, color = Color(0xFFD4D4D4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (skill.description.isNotBlank()) {
                                Text(skill.description, color = Color(0xFF858585), fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { draft = skill },
                                ) { Text("Edit", fontSize = 11.sp) }
                                TextButton(onClick = { confirmDelete = skill }) {
                                    Text("Delete", fontSize = 11.sp, color = Color(0xFFFF8A80))
                                }
                            }
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { on ->
                                val changed = SkillStore.setEnabled(context, projectRoot, skill.name, on)
                                reload++
                                message = if (changed) {
                                    if (on) skill.name + " is on." else skill.name + " is off. Its text is kept."
                                } else {
                                    "Could not change " + skill.name + "."
                                }
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { importer.launch("*/*") }) { Text("Import from phone", fontSize = 11.sp) }
                    TextButton(onClick = {
                        draft = SkillStore.Skill("", "", "", "", true)
                    }) { Text("New skill", fontSize = 11.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    draft?.let { editing ->
        SkillEditorDialog(
            initial = editing,
            onDismiss = { draft = null },
            onSave = { name, description, body ->
                runCatching { SkillStore.save(context, projectRoot, name, description, body) }
                    .fold(
                        onSuccess = { draft = null; reload++; message = "Saved " + name + "." },
                        onFailure = { message = "Could not save: " + (it.message ?: "storage error") },
                    )
            },
        )
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete " + doomed.name + "?", fontSize = 13.sp) },
            text = { Text("The skill file is removed from this device.", color = Color(0xFF858585), fontSize = 11.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val gone = SkillStore.delete(context, projectRoot, doomed.name)
                    confirmDelete = null
                    reload++
                    message = if (gone) doomed.name + " deleted." else "Could not delete " + doomed.name + "."
                }) { Text("Delete", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** Name, one-line description, body. Nothing else: a skill is text Copilot can read. */
@Composable
private fun SkillEditorDialog(
    initial: SkillStore.Skill,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var body by remember { mutableStateOf(initial.body) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "New skill" else "Edit skill", fontSize = 13.sp) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, singleLine = true,
                    label = { Text("When to use it", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it },
                    label = { Text("Steps", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && body.isNotBlank(),
                onClick = { onSave(name.trim(), description.trim(), body) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
