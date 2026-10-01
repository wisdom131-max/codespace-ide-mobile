package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.codespace.ide.domain.Language
import com.codespace.ide.util.CanonicalPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The deferred local lint never captures LSP state. The render merge is file-owned. */
@Composable
fun rememberEditorDiagnostics(path: String?, text: String, language: Language, lsp: List<LintError>): List<LintError> {
    val identity = path?.let { CanonicalPaths.canonicalKey(it) }
    var local by remember(identity) { mutableStateOf<List<LintError>>(emptyList()) }
    LaunchedEffect(identity, text, language) {
        delay(500)
        val analyzed = withContext(Dispatchers.Default) { LintAnalyzer.analyze(text, language) }
        local = analyzed
    }
    return remember(identity, local, lsp) {
        (local + lsp).distinctBy { Triple(it.start, it.end, it.message) }
            .sortedWith(compareBy({ it.start }, { it.severity }, { it.code ?: "" }))
    }
}
