package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ChatSettingsPrefs

/**
 * M5 — the session a memory access belongs to, and the owner's chosen scope.
 *
 * The chat host sets [activeSessionId] when it opens a conversation; [currentMemoryScope] is read
 * from preferences at each access so changing the setting takes effect on the next tool call
 * without a restart.
 */
object MemoryScopeState {
    @Volatile
    var activeSessionId: String = ""
}

/** Read at call time: a scope change must apply to the very next memory access. */
internal fun currentMemoryScope(): MemoryScope = MemoryScope.parse(ChatSettingsPrefs.memoryScopeId())
