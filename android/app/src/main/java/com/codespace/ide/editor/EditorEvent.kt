package com.codespace.ide.editor

import com.codespace.ide.diagnostics.AppOutputLog

/**
 * Phase X-1: EditorEvent — source-tagged editor interaction events.
 *
 * The editor's TextFieldValue (value) is the source of truth for text/selection state.
 * EditorEvent tags WHY value changed — distinguishing user actions from programmatic
 * changes, file opens, and recomposition artifacts.
 *
 * Only UserTyping, UserCursorMove, and UserSelection carry "trigger authority" —
 * meaning they are allowed to initiate LSP requests (completion, hover, signature help).
 * All other events are silent: context detection still recomputes (pure, cheap), but
 * no LSP request is sent.
 */
sealed class EditorEvent {

    /** First composition — CodeEditor created, cursor at initial position. */
    data class InitialCursorPlacement(val offset: Int) : EditorEvent()

    /** File opened in the editor. */
    data class FileOpen(val filePath: String, val content: String) : EditorEvent()

    /** User switched to a different file tab. */
    data class FileSwitch(val fromPath: String?, val toPath: String, val content: String) : EditorEvent()

    /** User typed or deleted text via keyboard (goes through onValueChange). */
    data class UserTyping(
        val newText: String,
        val cursor: Int,
        val previousText: String,
        val previousCursor: Int,
    ) : EditorEvent()

    /** User moved cursor via tap (direct value.copy, bypasses onValueChange). */
    data class UserCursorMove(val newOffset: Int) : EditorEvent()

    /** User selected text (long press, drag handles, shift+arrows). */
    data class UserSelection(val start: Int, val end: Int) : EditorEvent()

    /** Cursor moved programmatically (content reload, go-to-definition, format result). */
    data class ProgrammaticCursorMove(val newOffset: Int, val reason: String) : EditorEvent()

    /** Text changed programmatically (snippet insertion, format on save, external edit). */
    data class ProgrammaticTextChange(val newText: String, val cursor: Int) : EditorEvent()

    /** Whether this event has "trigger authority" — allowed to initiate LSP requests. */
    val hasTriggerAuthority: Boolean
        get() = this is UserTyping || this is UserCursorMove || this is UserSelection

    /** Only UserTyping triggers completion (not cursor move or selection). */
    val shouldTriggerCompletion: Boolean
        get() = this is UserTyping

    /** UserTyping and UserCursorMove trigger hover. UserSelection does not. */
    val shouldTriggerHover: Boolean
        get() = this is UserTyping || this is UserCursorMove

    /** UserTyping and UserCursorMove trigger signature help. */
    val shouldTriggerSignatureHelp: Boolean
        get() = this is UserTyping || this is UserCursorMove

    /** UserTyping, UserCursorMove, UserSelection, and InitialCursorPlacement trigger code actions.
     *  TEST-65/66-FIX: Also trigger on file open so the lightbulb appears immediately
     *  when the cursor lands on a line with errors. */
    val shouldTriggerCodeActions: Boolean
        get() = this is UserTyping || this is UserCursorMove || this is UserSelection || this is InitialCursorPlacement

    /** Short tag for logging. */
    val logTag: String
        get() = when (this) {
            is InitialCursorPlacement -> "INITIAL_CURSOR_PLACEMENT"
            is FileOpen -> "FILE_OPEN"
            is FileSwitch -> "FILE_SWITCH"
            is UserTyping -> "USER_TYPING"
            is UserCursorMove -> "USER_CURSOR_MOVE"
            is UserSelection -> "USER_SELECTION"
            is ProgrammaticCursorMove -> "PROGRAMMATIC_CURSOR_MOVE"
            is ProgrammaticTextChange -> "PROGRAMMATIC_TEXT_CHANGE"
        }

    /** Log this event to AppOutputLog. */
    fun log() {
        AppOutputLog.log("[EDITOR] EVENT $logTag", "lsp")
    }
}
