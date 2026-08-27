package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.codespace.ide.domain.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * R3-EXTRACT: Extracted from CodeEditor.kt to reduce JVM 64KB method bytecode.
 * Handles AI ghost text prediction with context-aware prompt framing.
 */
@Composable
fun GhostTextEffect(
    text: String,
    selectionEnd: Int,
    selectionStart: Int,
    showGhostText: Boolean,
    onAiGhostTextRequest: ((contextBefore: String, contextAfter: String, language: String) -> String?)?,
    language: Language,
    ghostTextState: MutableState<String?>,
    ghostTextLinesState: MutableState<List<String>>,
    ghostTextIsAiState: MutableState<Boolean>,
) {
    LaunchedEffect(text, selectionEnd) {
        if (showGhostText && onAiGhostTextRequest != null && ghostTextState.value == null) {
            delay(600L)
            val cursor = selectionEnd
            if (cursor == selectionStart && cursor > 0) {
                val contextBefore = text.substring(0, cursor)
                val contextAfter = text.substring(cursor)

                val currentLine = text.substring(0, cursor).substringAfterLast('\n')
                val lastNonWhitespaceBefore = contextBefore.trimEnd().lastOrNull()
                val contextHint = when {
                    currentLine.isBlank() && (contextBefore.isBlank() || contextBefore.trimEnd().endsWith('\n')) -> {
                        "FILE_SCOPE"
                    }
                    lastNonWhitespaceBefore == '}' || lastNonWhitespaceBefore == ')' -> {
                        "AFTER_BLOCK_CLOSE"
                    }
                    currentLine.isNotBlank() -> {
                        "MID_STATEMENT"
                    }
                    else -> "NEW_LINE_IN_BLOCK"
                }

                val aiResult = withContext(Dispatchers.IO) {
                    try {
                        val hintPrefix = when (contextHint) {
                            "FILE_SCOPE" -> "// [AI_CONTEXT: FILE_SCOPE — predict next top-level declaration]"
                            "AFTER_BLOCK_CLOSE" -> "// [AI_CONTEXT: AFTER_BLOCK_CLOSE — predict next statement/block]"
                            "MID_STATEMENT" -> "// [AI_CONTEXT: MID_STATEMENT — complete the current statement]"
                            else -> "// [AI_CONTEXT: NEW_LINE_IN_BLOCK — predict next statement inside block]"
                        }
                        onAiGhostTextRequest.invoke(contextBefore + "\n" + hintPrefix, contextAfter, language.name)
                    } catch (_: Exception) { null }
                }
                if (aiResult != null && aiResult.isNotBlank()) {
                    val cleanedResult = aiResult.lines().filterNot {
                        it.contains("[AI_CONTEXT:")
                    }.joinToString("\n")
                    val finalResult = cleanedResult.ifBlank { aiResult }
                    ghostTextState.value = finalResult.lines().firstOrNull() ?: ""
                    ghostTextLinesState.value = finalResult.lines()
                    ghostTextIsAiState.value = true
                }
            }
        }
    }
}
