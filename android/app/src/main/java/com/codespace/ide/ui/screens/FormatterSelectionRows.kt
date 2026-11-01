package com.codespace.ide.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.FormatterConfig

/**
 * Which formatter runs, per language. One composable, one store, one surface.
 *
 * It lived in Settings > Formatter Selection and the owner asked for it in the settings page
 * reached from the gear menu instead, where Format on Save already sits: a formatter choice and
 * the switch that runs it belong on the same page, and two copies of the same picker read and
 * write one store while telling the user they are two settings.
 */
@Composable
internal fun FormatterSelectionRows(context: Context) {
    Column {
        Text(
            "Built-in fallback (indentation + trailing whitespace) is used for languages without an external formatter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        FormatterConfig.availableFormatters.entries
            .filter { it.value.size > 1 }
            .sortedBy { it.key.displayName }
            .forEach { (lang, options) ->
                val currentFormatter = remember { FormatterConfig.getSelectedFormatter(context, lang) }
                var expanded by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(lang.displayName) },
                    supportingContent = { Text("Formatter: ${currentFormatter.name}") },
                    trailingContent = {
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(currentFormatter.name, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.name) },
                                        onClick = {
                                            FormatterConfig.setSelectedFormatter(context, lang, option.name)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
    }
}
