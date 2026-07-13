package com.codespace.ide.data

import androidx.compose.runtime.mutableStateListOf

/**
 * App-wide notification store. Singleton so any class (BackupManager, TerminalSession,
 * ProotInstaller, ConnectorsHub) can push notifications without needing a context or callback.
 *
 * Usage:
 *   NotificationStore.add("Backup complete", "Container saved to /sdcard/CodespaceIDE", NotificationType.BACKUP)
 *   val unread = NotificationStore.unreadCount
 */
object NotificationStore {

    enum class Type { TERMINAL_ERROR, BUILD_STATUS, BACKUP, CONNECTOR, UBUNTU_STATUS, INFO }

    data class Item(
        val id: Long = System.currentTimeMillis(),
        val title: String,
        val body: String,
        val type: Type = Type.INFO,
        val read: Boolean = false,
    )

    val items = mutableStateListOf<Item>()

    val unreadCount: Int get() = items.count { !it.read }

    fun add(title: String, body: String, type: Type = Type.INFO) {
        items.add(0, Item(title = title, body = body, type = type))
        // Keep at most 50 notifications
        if (items.size > 50) items.removeAt(items.lastIndex)
    }

    fun markAllRead() {
        val updated = items.map { it.copy(read = true) }
        items.clear()
        items.addAll(updated)
    }

    fun clearAll() { items.clear() }

    fun dismiss(id: Long) { items.removeAll { it.id == id } }
}
