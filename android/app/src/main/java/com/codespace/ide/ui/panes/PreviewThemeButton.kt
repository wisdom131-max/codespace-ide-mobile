package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Opens the custom stylesheet editor for the Markdown preview.
 *
 * Deliberately a small chip in the pane's own overlay: the preview chrome is already dense
 * and the console chip lives in the same row, so a stylesheet control belongs beside it
 * rather than inside a toolbar whose layout is spoken for.
 */
@Composable
internal fun PreviewThemeButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var draft by remember(open, com.codespace.ide.preview.PreviewTheme.css) {
        mutableStateOf(com.codespace.ide.preview.PreviewTheme.css)
    }
    val hasCustom = com.codespace.ide.preview.PreviewTheme.css.isNotBlank()

    Row(
        modifier = modifier
            .background(Color(0xE6222222), RoundedCornerShape(8.dp))
            .clickable { draft = com.codespace.ide.preview.PreviewTheme.css; open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "CSS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (hasCustom) Color(0xFF4FC3F7) else Color(0xFFBBBBBB),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Preview stylesheet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "CSS applied to the Markdown preview only. Leave empty for the built-in theme.",
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA),
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp)
                            .padding(top = 10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        placeholder = { Text("h1 { color: #4FC3F7 }", fontSize = 12.sp) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.codespace.ide.preview.PreviewTheme.save(context, draft)
                    open = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) {
                        com.codespace.ide.preview.PreviewTheme.save(context, "")
                        draft = ""
                    }
                    open = false
                }) { Text(if (draft.isNotBlank()) "Clear" else "Cancel") }
            },
        )
    }
}
