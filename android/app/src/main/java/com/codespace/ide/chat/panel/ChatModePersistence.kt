package com.codespace.ide.chat.panel

import com.codespace.ide.chat.session.SessionMode

/**
 * Item 18 (owner report 2026-10-30) — the mode belongs to the SESSION, not to the process.
 *
 * The bug this fixes: the mode was a plain `remember { ASK }`, so Agent (or Edit) was lost the
 * moment the panel was recreated - closing the app and coming back dropped the user into Ask,
 * which reads as a silent regression against what they chose.
 *
 * The rule now: restore the session's stored mode when the panel opens and whenever the active
 * session changes; write it when the user switches. ChatMode is a UI enum, SessionMode is the
 * on-disk token, so a UI rename can never corrupt a stored session.
 */
internal object ChatModePersistence {

    fun load(manager: ChatSessionManager, projectId: String, sessionId: String): ChatMode =
        toUi(manager.sessionMode(projectId, sessionId))

    fun save(
        manager: ChatSessionManager,
        projectId: String,
        sessionId: String,
        mode: ChatMode,
    ): Boolean = manager.setSessionMode(projectId, sessionId, toStored(mode)) == null

    fun toUi(mode: SessionMode): ChatMode = when (mode) {
        SessionMode.ASK -> ChatMode.ASK
        SessionMode.EDIT -> ChatMode.EDIT
        SessionMode.AGENT -> ChatMode.AGENT
    }

    fun toStored(mode: ChatMode): SessionMode = when (mode) {
        ChatMode.ASK -> SessionMode.ASK
        ChatMode.EDIT -> SessionMode.EDIT
        ChatMode.AGENT -> SessionMode.AGENT
    }
}
