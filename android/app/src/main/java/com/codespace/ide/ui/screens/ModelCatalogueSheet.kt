package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.engine.ModelCatalog

/**
 * PHASE 8a: what each model in the bundled catalogue is known to accept.
 *
 * It exists so the payload budget stops being a number the user has to take on faith: every row
 * shows the context window the budget is derived from, and the tri-state tool column says "not
 * verified" instead of pretending.
 */
@Composable
internal fun ModelCatalogueSheet(onDismiss: () -> Unit) {
    val entries = ModelCatalogue.entries(ModelCatalog.BUNDLED)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model catalogue", color = Color(0xFFD4D4D4), fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                Text(
                    "Context windows decide how much of the conversation is sent. The estimate " +
                        "is " + ModelCatalog.CHARS_PER_TOKEN_ESTIMATE + " characters per token.",
                    color = Color(0xFF858585), fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries) { entry ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2A2A2A))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    entry.id,
                                    color = Color(0xFFD4D4D4), fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(entry.contextLabel, color = Color(0xFF4FC3F7), fontSize = 11.sp)
                            }
                            Text(
                                listOfNotNull(
                                    entry.toolsLabel,
                                    if (entry.images) "images" else null,
                                    if (entry.pdf) "pdf" else null,
                                    if (entry.reasoning) "reasoning" else null,
                                ).joinToString(" · "),
                                color = Color(0xFF858585), fontSize = 10.sp,
                            )
                            Text(entry.providers, color = Color(0xFF6F6F6F), fontSize = 9.sp)
                        }
                    }
                }
                Text(
                    ModelCatalogue.footer(),
                    color = Color(0xFF6F6F6F), fontSize = 9.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
