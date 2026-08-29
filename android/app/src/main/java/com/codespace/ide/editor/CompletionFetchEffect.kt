package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspCompletionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R3-EXTRACT: Extracted from CodeEditor.kt to reduce JVM 64KB method bytecode.
 * Handles LSP completion fetching with smart timeout, stale-response protection,
 * and workspace symbol search.
 */
@Composable
fun CompletionFetchEffect(
    prefix: String,
    isDotContext: Boolean,
    selectionEnd: Int,
    pathContext: PathCompletionProvider.PathContext?,
    editorEvent: EditorEvent,
    dotWasTyped: Boolean,
    completionContextValue: com.codespace.ide.lsp.CompletionContextDetector.ContextInfo,
    lspCompletionProvider: ((line: Int, col: Int) -> Pair<List<LspCompletionItem>, Boolean>)?,
    lspWorkspaceSymbolProvider: ((query: String) -> List<LspCompletionItem>)?,
    lspCancellationProvider: ((Long) -> Unit)?,
    lspRequestIdProvider: (() -> Long)?,
    language: Language,
    smartCompletion: Boolean,
    positionMapper: PositionMapper,
    lspGens: LspRequestGens,
    lspCompletionsState: MutableState<List<LspCompletionItem>>,
    workspaceCompletionsState: MutableState<List<LspCompletionItem>>,
    lspTimedOutState: MutableState<Boolean>,
    lspHasRespondedState: MutableState<Boolean>,
    lspRequestIdState: MutableState<Long>,
    lspIsIncompleteState: MutableState<Boolean>,
    lspLastPrefixState: MutableState<String>,
) {
    // TEMP: LSP timing counters (remove after diagnostic session)
    var __lspSuccessCount by remember { mutableStateOf(0) }
    var __lspTimeoutCount by remember { mutableStateOf(0) }
    var __lspTotalMs by remember { mutableStateOf(0L) }
    var __lspSlowCount by remember { mutableStateOf(0) } // >2s

    LaunchedEffect(prefix, isDotContext, selectionEnd, pathContext, editorEvent) {
        // P41-G: Skip LSP completions when path context is active
        if (pathContext != null) {
            lspCompletionsState.value = emptyList()
            workspaceCompletionsState.value = emptyList()
            return@LaunchedEffect
        }

        // Phase X-3: Block completion on non-user events
        if (!editorEvent.shouldTriggerCompletion) {
            com.codespace.ide.diagnostics.AppOutputLog.log("[EDITOR] COMPLETION_TRIGGER blocked=" + editorEvent.logTag, "lsp")
            return@LaunchedEffect
        }
        com.codespace.ide.diagnostics.AppOutputLog.log("[EDITOR] COMPLETION_TRIGGER allowed=true", "lsp")
        if (isDotContext) { com.codespace.ide.diagnostics.AppOutputLog.log("[EDITOR] DOT_CONTEXT=true", "lsp") }
        if (dotWasTyped) { com.codespace.ide.diagnostics.AppOutputLog.log("[EDITOR] DOT_TYPED=true", "lsp") }

        if (lspCompletionProvider != null) {
            // ─── FREEZE/REFILTER MODEL (VS Code pattern) ───
            // When the previous LSP response had isIncomplete=false, the server returned
            // ALL completions for that trigger position. Subsequent keystrokes that EXTEND
            // the prefix (forward typing) can be served from cache — just refilter locally.
            // The allCompletions block in CodeEditor.kt (keyed on prefix) already re-runs
            // rank()/fuzzyScore() against lspCompletionsState on every prefix change.
            //
            // Re-query the server when:
            //   1. No previous response yet (first query)
            //   2. Previous response was isIncomplete=true (server may have more items)
            //   3. Dot trigger (triggerKind=TriggerCharacter — always re-query)
            //   4. Prefix doesn't extend the cached prefix (backspace, new word, etc.)
            val canFreeze = !dotWasTyped &&
                !isDotContext &&
                !lspIsIncompleteState.value &&
                lspLastPrefixState.value.isNotEmpty() &&
                lspCompletionsState.value.isNotEmpty() &&
                prefix.startsWith(lspLastPrefixState.value) &&
                prefix.length > lspLastPrefixState.value.length

            if (canFreeze) {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[LSP-FREEZE] refiltering cached results prefix='$prefix' cachedPrefix='${lspLastPrefixState.value}' items=${lspCompletionsState.value.size} — NO server request",
                    "lsp"
                )
                return@LaunchedEffect
            }

            delay(150)  // debounce
            delay(70)   // R3-A: show delay

            lspGens.completion++
            val myCompGen = lspGens.completion
            val myCompServerGen = com.codespace.ide.lsp.LspManager.getServerGeneration(language)
            if (lspRequestIdState.value >= 0 && lspCancellationProvider != null) {
                try { lspCancellationProvider.invoke(lspRequestIdState.value) } catch (_: Exception) {}
                lspRequestIdState.value = -1L
            }

            val cOff = selectionEnd
            val cPos = positionMapper.offsetToPosition(cOff)
            val cLine = cPos.line
            val cCol = cPos.column

            if (lspRequestIdProvider != null) {
                try { lspRequestIdState.value = lspRequestIdProvider.invoke() } catch (_: Exception) {}
            }
            if (dotWasTyped) {
                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] COMPLETION triggerKind=2 triggerCharacter=.", "lsp")
            } else {
                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] COMPLETION triggerKind=1 (Invoked)", "lsp")
            }

            if (smartCompletion) {
                val wasInFallback = lspTimedOutState.value || !lspHasRespondedState.value
                lspTimedOutState.value = false
                val __t0 = System.currentTimeMillis()
                val results = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(5000L) {
                        val lspResult = try { lspCompletionProvider.invoke(cLine, cCol) }
                        catch (e: Exception) {
                            com.codespace.ide.diagnostics.AppOutputLog.log(
                                "[LSP-DIAG] CRASH in lspCompletionProvider: " + e.javaClass.simpleName + ": " + e.message + "\n" + e.stackTraceToString().take(2000),
                                "lsp"
                            )
                            Pair(emptyList<LspCompletionItem>(), false)
                        }
                        val lsp = lspResult.first
                        lspIsIncompleteState.value = lspResult.second
                        val ws = if (lspWorkspaceSymbolProvider != null && prefix.length >= 3) {
                            try { lspWorkspaceSymbolProvider.invoke(prefix).take(50) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                        } else emptyList()
                        Pair(lsp, ws)
                    }
                }
                val __elapsed = System.currentTimeMillis() - __t0
                if (results != null) {
                    if (myCompGen != lspGens.completion) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] STALE gen discarded, elapsed=${__elapsed}ms items=${results.first.size}", "lsp")
                        return@LaunchedEffect
                    }
                    if (myCompServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] STALE servergen discarded, elapsed=${__elapsed}ms items=${results.first.size}", "lsp")
                        return@LaunchedEffect
                    }
                    lspHasRespondedState.value = true
                    lspCompletionsState.value = results.first
                    workspaceCompletionsState.value = results.second
                    // Update freeze state: store isIncomplete flag + prefix that produced this response
                    lspLastPrefixState.value = prefix
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] SUCCESS elapsed=${__elapsed}ms lspItems=${results.first.size} wsItems=${results.second.size} prefix='${prefix}' isIncomplete=${lspIsIncompleteState.value}", "lsp")
                    __lspSuccessCount++
                    __lspTotalMs += __elapsed
                    if (__elapsed > 2000) __lspSlowCount++
                    val __avg = if (__lspSuccessCount > 0) __lspTotalMs / __lspSuccessCount else 0L
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] STATS success=$__lspSuccessCount timeout=$__lspTimeoutCount slow(>2s)=$__lspSlowCount avgMs=$__avg", "lsp")
                    if (wasInFallback) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] completion restored after recovery", "lsp")
                    }
                } else {
                    lspTimedOutState.value = true
                    lspHasRespondedState.value = false
                    lspCompletionsState.value = emptyList()
                    workspaceCompletionsState.value = emptyList()
                    lspIsIncompleteState.value = false
                    lspLastPrefixState.value = ""
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] TIMEOUT >5000ms prefix='${prefix}'", "lsp")
                    __lspTimeoutCount++
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP-TIMING] STATS success=$__lspSuccessCount timeout=$__lspTimeoutCount slow(>2s)=$__lspSlowCount avgMs=$__lspTotalMs", "lsp")
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] completion timed out, using regex fallback", "lsp")
                }
            } else {
                val results = withContext(Dispatchers.IO) {
                    val lspResult = try { lspCompletionProvider.invoke(cLine, cCol) } catch (_: Exception) { Pair(emptyList<LspCompletionItem>(), false) }
                    val lsp = lspResult.first
                    lspIsIncompleteState.value = lspResult.second
                    val ws = if (lspWorkspaceSymbolProvider != null && prefix.length >= 3) {
                        try { lspWorkspaceSymbolProvider.invoke(prefix).take(50) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                    } else emptyList()
                    Pair(lsp, ws)
                }
                if (myCompGen != lspGens.completion) {
                    com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale request-gen for completion", "lsp")
                    return@LaunchedEffect
                }
                if (myCompServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
                    com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale generation for completion", "lsp")
                    return@LaunchedEffect
                }
                lspCompletionsState.value = results.first
                workspaceCompletionsState.value = results.second
                lspLastPrefixState.value = prefix
            }
        } else {
            lspCompletionsState.value = emptyList()
            workspaceCompletionsState.value = emptyList()
            lspLastPrefixState.value = ""
        }
    }
}
