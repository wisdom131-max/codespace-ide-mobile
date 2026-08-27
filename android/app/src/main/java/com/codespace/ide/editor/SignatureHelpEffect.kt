package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.codespace.ide.domain.Language
import kotlinx.coroutines.delay

/**
 * R3-EXTRACT: Extracted from CodeEditor.kt to reduce JVM 64KB method bytecode.
 * Handles signature help (parameter hints) with LSP + local fallback and stale-response protection.
 */
@Composable
fun SignatureHelpEffect(
    isInsideCall: Boolean,
    selectionEnd: Int,
    text: String,
    language: Language,
    editorEvent: EditorEvent,
    lspSignatureHelpProvider: ((line: Int, col: Int) -> SignatureInfo?)?,
    positionMapper: PositionMapper,
    lspGens: LspRequestGens,
    activeSignatureState: MutableState<SignatureInfo?>,
) {
    LaunchedEffect(isInsideCall, selectionEnd, language, editorEvent) {
        if (!isInsideCall) {
            activeSignatureState.value = null
            return@LaunchedEffect
        }
        if (!editorEvent.shouldTriggerSignatureHelp) {
            activeSignatureState.value = SignatureHelpAnalyzer.findActiveCall(text, selectionEnd, language)
            return@LaunchedEffect
        }
        delay(200)
        lspGens.signatureHelp++
        val myGen = lspGens.signatureHelp
        val mySigServerGen = com.codespace.ide.lsp.LspManager.getServerGeneration(language)
        val cOff = selectionEnd
        val cPos = positionMapper.offsetToPosition(cOff)
        val cLine = cPos.line
        val cCol = cPos.column
        val result = if (lspSignatureHelpProvider != null) {
            try { lspSignatureHelpProvider.invoke(cLine, cCol) } catch (_: Exception) { null }
                ?: SignatureHelpAnalyzer.findActiveCall(text, cOff, language)
        } else {
            SignatureHelpAnalyzer.findActiveCall(text, cOff, language)
        }
        if (myGen != lspGens.signatureHelp) {
            com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale request-gen for signature-help", "lsp")
            return@LaunchedEffect
        }
        if (mySigServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
            com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale generation for signature-help", "lsp")
            return@LaunchedEffect
        }
        activeSignatureState.value = result
    }
}
