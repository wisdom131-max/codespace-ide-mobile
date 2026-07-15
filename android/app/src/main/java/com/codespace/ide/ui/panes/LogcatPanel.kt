package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// P8-2 Logcat viewer — streams adb logcat, color-coded by level, filterable.

private data class LogcatEntry(
    val level: String,
    val tag: String,
    val message: String,
)

private fun parseLogcatLine(line: String): LogcatEntry {
    // adb logcat -v time format: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: message
    val parts = line.trim().split(" ", limit = 7)
    return if (parts.size >= 7) {
        LogcatEntry(
            level = parts[4].trim(),
            tag = parts[5].trimEnd(':').take(20),
            message = parts.drop(6).joinToString(" "),
        )
    } else {
        LogcatEntry(level = "V", tag = "", message = line)
    }
}

private fun levelColor(level: String): Color = when (level) {
    "E" -> Color(0xFFFF5F5F)
    "W" -> Color(0xFFFFB74D)
    "I" -> Color(0xFF81C784)
    "D" -> Color(0xFF64B5F6)
    else -> Color(0xFF9E9E9E)
}

@Composable
fun LogcatPanel(modifier: Modifier = Modifier) {
    val entries = remember { mutableStateListOf<LogcatEntry>() }
    var filter by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var pausedUi by remember { mutableStateOf(false) }
    // P15-F: level filter — empty set = show all levels
    var levelFilter by remember { mutableStateOf(setOf<String>()) }
    // Thread-safe flag read from IO coroutine — avoids reading Compose State off main thread
    val pausedFlag = remember { AtomicBoolean(false) }
    val listState = rememberLazyListState()

    // Sync UI state to atomic flag
    LaunchedEffect(pausedUi) { pausedFlag.set(pausedUi) }

    // Start adb logcat stream
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("adb", "logcat", "-v", "time"))
                val reader = process.inputStream.bufferedReader()
                while (isActive) {
                    if (pausedFlag.get()) {
                        delay(300L)
                        continue
                    }
                    val line = reader.readLine()
                    if (line != null) {
                        val entry = parseLogcatLine(line)
                        withContext(Dispatchers.Main) {
                            entries.add(entry)
                            if (entries.size > 2000) entries.removeAt(0)
                        }
                    } else {
                        delay(200L)
                    }
                }
                try { reader.close() } catch (_: Exception) {}
                try { process.destroy() } catch (_: Exception) {}
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    entries.add(
                        LogcatEntry(
                            level = "I",
                            tag = "Logcat",
                            message = "adb not available. Run 'adb logcat' in terminal to see device logs.",
                        )
                    )
                }
            }
        }
    }

    val filtered = remember(entries.toList(), filter, levelFilter) {
        entries.filter { entry ->
            val levelOk = levelFilter.isEmpty() || entry.level in levelFilter
            val textOk  = filter.isBlank() ||
                entry.tag.contains(filter, ignoreCase = true) ||
                entry.message.contains(filter, ignoreCase = true)
            levelOk && textOk
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter tag / message", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(36.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { pausedUi = !pausedUi }) {
                Text(if (pausedUi) "Resume" else "Pause", fontSize = 11.sp)
            }
            TextButton(onClick = { entries.clear() }) {
                Text("Clear", fontSize = 11.sp)
            }
            TextButton(onClick = { autoScroll = !autoScroll }) {
                Text(if (autoScroll) "Auto" else "Manual", fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF3E3E42))
        // P15-F: Level filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Level:", color = Color(0xFF808080), fontSize = 10.sp)
            listOf("E" to Color(0xFFFF5F5F), "W" to Color(0xFFFFB74D),
                   "I" to Color(0xFF81C784), "D" to Color(0xFF64B5F6),
                   "V" to Color(0xFF9E9E9E)).forEach { (level, clr) ->
                val active = level in levelFilter
                Box(
                    androidx.compose.ui.Modifier
                        .background(
                            if (active) clr.copy(alpha = 0.25f) else Color(0xFF3C3C3C),
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            levelFilter = if (active) levelFilter - level else levelFilter + level
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(level, color = if (active) clr else Color(0xFF888888), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            if (levelFilter.isNotEmpty()) {
                Box(
                    androidx.compose.ui.Modifier
                        .background(Color(0xFF3C3C3C),
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable { levelFilter = emptySet() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("✕ All", color = Color(0xFF888888), fontSize = 10.sp) }
            }
        }
        HorizontalDivider(color = Color(0xFF3E3E42))
        // Log list
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
        ) {
            items(filtered) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = entry.level,
                        color = levelColor(entry.level),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = entry.tag,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = entry.message,
                        color = levelColor(entry.level),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
