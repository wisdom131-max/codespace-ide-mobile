package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.preview.PreviewConsole

/**
 * The preview's console, as a button that only gets loud when something is wrong.
 *
 * A silent preview is the worst failure this app can present: the pane is blank and the reason is
 * a TypeError nobody can see. This button says "Console" in grey when there is nothing to report
 * and "2 errors" in red when there is. Tapping it opens the actual messages.
 *
 * Every composable here is a separate function and none of them keeps its body inline, per the
 * 64KB bytecode rule; no early returns out of inline lambdas, per the D8 dex rule.
 */
@Composable
fun PreviewConsoleButton(
    entries: List<PreviewConsole.Entry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val errors = entries.count { it.level == PreviewConsole.Level.ERROR }

    PreviewConsoleChip(
        label = PreviewConsole.summary(entries),
        hasErrors = errors > 0,
        onTap = { open = true },
        modifier = modifier,
    )

    if (open) {
        PreviewConsoleDialog(entries = entries, onClear = onClear, onDismiss = { open = false })
    }
}

@Composable
private fun PreviewConsoleChip(
    label: String,
    hasErrors: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (hasErrors) Color(0xFFF48771) else Color(0xFFB0B0B0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color(0xFF2D2D30), RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (hasErrors) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PreviewConsoleDialog(
    entries: List<PreviewConsole.Entry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 420.dp),
        ) {
            PreviewConsoleHeader(onClear = onClear, onDismiss = onDismiss)
            HorizontalDivider(color = Color(0xFF3C3C3C))
            if (entries.isEmpty()) {
                Text(
                    text = "No console output. Errors and warnings from the preview appear here.",
                    color = Color(0xFF9D9D9D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                PreviewConsoleList(entries = entries)
            }
        }
    }
}

@Composable
private fun PreviewConsoleHeader(onClear: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = "Preview console", color = Color(0xFFE7E7E7), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            text = "Clear",
            color = Color(0xFF3794FF),
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFB0B0B0))
        }
    }
}

@Composable
private fun PreviewConsoleList(entries: List<PreviewConsole.Entry>) {
    val ordered = remember(entries) { entries.asReversed() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(ordered, key = { it.id }) { entry ->
            PreviewConsoleRow(entry = entry)
        }
    }
}

@Composable
private fun PreviewConsoleRow(entry: PreviewConsole.Entry) {
    val color = when (entry.level) {
        PreviewConsole.Level.ERROR -> Color(0xFFF48771)
        PreviewConsole.Level.WARN -> Color(0xFFD7BA7D)
        PreviewConsole.Level.INFO -> Color(0xFF9CDCFE)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = entry.message, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (entry.location.isNotEmpty()) {
            Text(text = entry.location, color = Color(0xFF717171), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Small box helper so callers do not need a Box import for a one-line usage. */
@Composable
fun PreviewConsoleHost(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) { content() }
}
