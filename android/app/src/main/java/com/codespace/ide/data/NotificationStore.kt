package com.codespace.ide.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

/**
 * App-wide notification store. Singleton so any class (BackupManager, TerminalSession,
 * ProotInstaller, ConnectorsHub) can push notifications without needing a context or callback.
 *
 * P32-THREAD-SAFETY-FIX: All mutations to [items] are dispatched to the main thread
 * via Handler.post. This prevents "Unsupported concurrent change during composition"
 * crashes when background threads (ProotInstaller, BackupManager) call add() while
 * the notification drawer is being composed.
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

    private val mainHandler = Handler(Looper.getMainLooper())

    val items = mutableStateListOf<Item>()

    val unreadCount: Int get() = items.count { !it.read }

    fun add(title: String, body: String, type: Type = Type.INFO) {
        val item = Item(title = title, body = body, type = type)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            items.add(0, item)
            if (items.size > 50) items.removeAt(items.lastIndex)
        } else {
            mainHandler.post {
                items.add(0, item)
                if (items.size > 50) items.removeAt(items.lastIndex)
            }
        }
    }

    fun markAllRead() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val updated = items.map { it.copy(read = true) }
            items.clear()
            items.addAll(updated)
        } else {
            mainHandler.post {
                val updated = items.map { it.copy(read = true) }
                items.clear()
                items.addAll(updated)
            }
        }
    }

    fun clearAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            items.clear()
        } else {
            mainHandler.post { items.clear() }
        }
    }

    fun dismiss(id: Long) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            items.removeAll { it.id == id }
        } else {
            mainHandler.post { items.removeAll { it.id == id } }
        }
    }
}
