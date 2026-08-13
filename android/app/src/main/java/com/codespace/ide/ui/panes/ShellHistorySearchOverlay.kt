package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Colour palette (terminal dark theme) ────────────────────────────────────
private val HistBg      = Color(0xFF1E1E1E)
private val HistText    = Color(0xFFD4D4D4)
private val HistDim     = Color(0xFF808080)
private val HistAccent  = Color(0xFF007ACC)
private val HistDivider = Color(0xFF3C3C3C)
private val HistHover   = Color(0xFF2A2D2E)

// ── TerminalHistoryStore ─────────────────────────────────────────────────────
/**
 * Persists the last MAX shell commands in SharedPreferences.
 * Duplicate consecutive entries are collapsed. Thread-safe for read; callers
 * must serialise writes if calling from multiple coroutines.
 */
object TerminalHistoryStore {
    private const val PREF_FILE = "terminal_history"
    private const val PREF_KEY  = "shell_history"
    private const val MAX       = 500

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(PREF_KEY, "") ?: ""
        val prefList = if (raw.isEmpty()) emptyList() else raw.split("\u0000").filter { it.isNotBlank() }
        // TEST-45-FIX: Also read .bash_history from proot rootfs so the command palette
        // shows real terminal history, not just commands run through the palette itself.
        val bashHistFile = java.io.File(ctx.filesDir, "ubuntu-rootfs/root/.bash_history")
        val bashHist = if (bashHistFile.exists()) {
            try {
                bashHistFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        // Merge: bash_history first (oldest), then SharedPreferences (which has dedup), 
        // then deduplicate keeping the last occurrence (most recent position)
        val merged = (bashHist + prefList)
        val seen = mutableSetOf<String>()
        return merged.reversed().filter { seen.add(it) }.reversed()  // dedup keeping last
    }

    /**
     * Appends [cmd] to history. Deduplicates (moves existing entry to end),
     * caps at MAX entries, and persists.
     */
    fun append(ctx: Context, cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        val list = load(ctx).toMutableList()
        list.remove(trimmed)      // dedup — remove old occurrence if any
        list.add(trimmed)
        val capped = if (list.size > MAX) list.takeLast(MAX) else list
        prefs(ctx).edit()
            .putString(PREF_KEY, capped.joinToString("\u0000"))
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(PREF_KEY).apply()
    }
}

// ── ShellHistorySearchOverlay ────────────────────────────────────────────────
/**
 * Ctrl+R–style history search overlay for the terminal.
 *
 * Usage:
 *   ShellHistorySearchOverlay(
 *       onDismiss = { showHistory = false },
 *       onSelect  = { cmd -> injectToTerminal(cmd) },
 *       historyLines = TerminalHistoryStore.load(context).reversed(),
 *   )
 *
 * - Full-screen scrim dismisses on tap.
 * - Search card slides from the top; TextField auto-focuses.
 * - Tapping a history item calls [onSelect] and closes.
 * - History shown newest-first (caller should pass reversed list).
 */
@Composable
fun ShellHistorySearchOverlay(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    historyLines: List<String>,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val filtered = remember(query, historyLines) {
        if (query.isBlank()) historyLines
        else historyLines.filter { it.contains(query, ignoreCase = true) }
    }

    // Full-screen scrim
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(onClick = onDismiss),
    ) {
        // Card — stop click propagation to scrim
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.TopCenter)
                .background(HistBg, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .clickable(enabled = false, onClick = {})  // consume clicks
                .padding(bottom = 8.dp),
        ) {
            // ── Search header ────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = HistAccent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text("Search history…", color = HistDim, fontSize = 13.sp)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor        = HistText,
                        unfocusedTextColor      = HistText,
                        focusedBorderColor      = HistAccent,
                        unfocusedBorderColor    = HistDivider,
                        cursorColor             = HistAccent,
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize    = 13.sp,
                        fontFamily  = FontFamily.Monospace,
                        color       = HistText,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = HistDim, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = HistDivider, thickness = 1.dp)

            // ── Results ──────────────────────────────────────────────────
            if (filtered.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (historyLines.isEmpty()) "No history yet" else "No matches for \"$query\"",
                        color = HistDim,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(filtered) { cmd ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(cmd)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cmd,
                                color = HistText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.SubdirectoryArrowLeft,
                                contentDescription = "Use",
                                tint = HistDim,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        HorizontalDivider(color = HistDivider, thickness = 0.5.dp)
                    }
                }
            }

            // ── Footer hint ───────────────────────────────────────────────
            Text(
                "Tap an item to paste it into the terminal",
                color = HistDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    // N11 FIX (2026-08-10): delayed focus + explicit keyboard show
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
        keyboardController?.show()
    }
}
