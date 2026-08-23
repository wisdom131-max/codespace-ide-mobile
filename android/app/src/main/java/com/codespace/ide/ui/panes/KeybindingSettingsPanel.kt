package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.EditorAction
import com.codespace.ide.editor.KeyBindingRegistry
import com.codespace.ide.editor.KeyCombination

/**
 * Keybinding Settings Panel — VS Code-style keybinding editor.
 *
 * Displays all editor actions in searchable categories with their current
 * keybindings. Users can:
 *  - View current bindings
 *  - Search by action name or key combo
 *  - Reset individual bindings to defaults
 *  - Reset all bindings to defaults
 *
 * The panel is a self-contained composable designed to be embedded inside
 * InProjectSettingsDialog's content area.
 */
@Composable
fun KeybindingSettingsPanel(
    bgColor: Color,
    surfaceColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color,
    dividerColor: Color,
) {
    var searchQuery by remember { mutableStateOf("") }
    var bindings by remember { mutableStateOf(KeyBindingRegistry.getAllBindings()) }
    var recordingAction by remember { mutableStateOf<EditorAction?>(null) }

    // Categories for grouping
    data class ActionGroup(val title: String, val actions: List<EditorAction>)

    val groups = remember {
        listOf(
            ActionGroup("File", listOf(
                EditorAction.SAVE, EditorAction.OPEN_FILE, EditorAction.CLOSE_TAB,
                EditorAction.NEXT_TAB, EditorAction.PREV_TAB,
            )),
            ActionGroup("Search", listOf(
                EditorAction.FIND, EditorAction.REPLACE, EditorAction.FIND_NEXT,
                EditorAction.FIND_PREVIOUS, EditorAction.GO_TO_LINE,
            )),
            ActionGroup("Editing", listOf(
                EditorAction.UNDO, EditorAction.REDO, EditorAction.COPY, EditorAction.PASTE,
                EditorAction.CUT, EditorAction.SELECT_ALL, EditorAction.FORMAT,
                EditorAction.COMMENT_TOGGLE, EditorAction.DUPLICATE_LINE,
                EditorAction.DELETE_LINE, EditorAction.INDENT, EditorAction.UNINDENT,
                EditorAction.SMART_ENTER,
            )),
            ActionGroup("Navigation", listOf(
                EditorAction.GO_TO_DEFINITION, EditorAction.SHOW_HOVER,
                EditorAction.QUICK_FIX, EditorAction.RENAME, EditorAction.COMMAND_PALETTE,
            )),
            ActionGroup("View", listOf(
                EditorAction.TOGGLE_WORD_WRAP, EditorAction.ZOOM_IN,
                EditorAction.ZOOM_OUT, EditorAction.ZOOM_RESET,
                EditorAction.MOVE_LINE_UP, EditorAction.MOVE_LINE_DOWN,
            )),
        )
    }

    // Filter actions based on search
    val filteredGroups = remember(searchQuery, bindings) {
        if (searchQuery.isBlank()) {
            groups
        } else {
            val q = searchQuery.lowercase()
            groups.map { group ->
                ActionGroup(
                    group.title,
                    group.actions.filter { action ->
                        val label = action.name.replace('_', ' ').lowercase()
                        val combo = bindings[action]?.toString()?.lowercase() ?: ""
                        label.contains(q) || combo.contains(q)
                    }
                )
            }.filter { it.actions.isNotEmpty() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar + Reset All button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search keybindings...", fontSize = 13.sp, color = textSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = textSecondary, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = textPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = dividerColor,
                    cursorColor = accentColor,
                ),
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    KeyBindingRegistry.resetAllBindings()
                    bindings = KeyBindingRegistry.getAllBindings()
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = accentColor)
                Spacer(Modifier.width(4.dp))
                Text("Reset All", fontSize = 12.sp, color = accentColor)
            }
        }

        HorizontalDivider(color = dividerColor)

        // Binding list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            filteredGroups.forEach { group ->
                // Category header
                item {
                    Text(
                        text = group.title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = textSecondary,
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 6.dp)
                            .fillMaxWidth(),
                    )
                }
                items(group.actions, key = { it.name }) { action ->
                    KeybindingRow(
                        action = action,
                        combo = bindings[action],
                        defaultCombo = KeyBindingRegistry.getDefaultBinding(action),
                        isRecording = recordingAction == action,
                        bgColor = surfaceColor,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        accentColor = accentColor,
                        dividerColor = dividerColor,
                        onReset = {
                            KeyBindingRegistry.resetBinding(action)
                            bindings = KeyBindingRegistry.getAllBindings()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeybindingRow(
    action: EditorAction,
    combo: KeyCombination?,
    defaultCombo: KeyCombination?,
    isRecording: Boolean,
    bgColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color,
    dividerColor: Color,
    onReset: () -> Unit,
) {
    var showReset by remember { mutableStateOf(false) }
    val isModified = combo != null && defaultCombo != null && combo != defaultCombo

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { showReset = !showReset },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Action label
        Text(
            text = action.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() },
            fontSize = 13.sp,
            color = textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))

        // Key combo badge
        if (combo != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = bgColor,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    text = combo.toString(),
                    fontSize = 11.sp,
                    color = if (isModified) accentColor else textSecondary,
                    fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }

        // Reset button (only show if modified)
        if (isModified && showReset) {
            IconButton(
                onClick = onReset,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reset to default",
                    tint = accentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
    HorizontalDivider(color = dividerColor.copy(alpha = 0.3f))
}
