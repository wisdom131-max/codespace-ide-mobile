package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Phase D: Consolidated LSP request generation counters.
 * Replaces 8 individual `var x by remember { mutableStateOf(0L) }` calls
 * with a single `val lspGens = remember { LspRequestGens() }` to reduce
 * the CodeEditor composable's bytecode size (JVM 64KB method limit).
 *
 * Each counter is incremented before an async LSP request is sent.
 * The response handler captures the counter value at request time
 * and checks it hasn't changed when the response arrives — if it has,
 * the response is stale and discarded.
 */
class LspRequestGens {
    var completion by mutableStateOf(0L)
    var signatureHelp by mutableStateOf(0L)
    var hover by mutableStateOf(0L)
    var definition by mutableStateOf(0L)
    var references by mutableStateOf(0L)
    var codeAction by mutableStateOf(0L)
    var format by mutableStateOf(0L)
    var rename by mutableStateOf(0L)
}
