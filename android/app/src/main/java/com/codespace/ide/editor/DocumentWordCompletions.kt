package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IC13 (P4c): document-word suggestions. No provider ever derived completions
 * from words already in the open document — only curated keyword/type/snippet/
 * stdlib tables existed. VS Code ships word-based suggestions; this is the
 * on-device equivalent, deliberately cheap: ONE scan per completion popup open,
 * not per keystroke.
 */
internal object DocumentWordCompletions {

    /** Identifiers of 3+ chars, first-come order, capped. */
    internal fun scan(content: String, excludePrefix: String, cap: Int = 200): List<String> {
        val out = LinkedHashSet<String>()
        val re = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
        for (m in re.findAll(content)) {
            val w = m.value
            if (w.equals(excludePrefix, ignoreCase = true)) continue
            out.add(w)
            if (out.size >= cap) break
        }
        return out.toList()
    }

    /** The snapshot taken when the popup OPENED, filtered by the current prefix. */
    internal fun currentWords(snapshot: List<String>, prefix: String, exclude: String, max: Int = 20): List<String> {
        if (prefix.isBlank()) return emptyList()
        val out = ArrayList<String>(max)
        for (w in snapshot) {
            if (w.equals(exclude, ignoreCase = true)) continue
            if (!w.startsWith(prefix, ignoreCase = true)) continue
            out.add(w)
            if (out.size >= max) break
        }
        return out
    }
}

/**
 * Snapshot state holder — lives in its own file so the CodeEditor composable
 * body gains zero inline effect code (64KB rule).
 */
@Composable
internal fun rememberDocumentWords(
    popupVisible: Boolean,
    enabled: Boolean,
    content: String,
    prefixAtOpen: String,
): List<String> {
    var snapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    // One scan per popup-open transition, off the UI thread.
    LaunchedEffect(popupVisible, enabled) {
        if (popupVisible && enabled) {
            snapshot = withContext(Dispatchers.Default) {
                DocumentWordCompletions.scan(content, prefixAtOpen)
            }
        }
    }
    return snapshot
}
