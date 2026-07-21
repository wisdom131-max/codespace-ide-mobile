package com.codespace.ide.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

/**
 * P34-NOTIF: App-wide notification store — single source of truth.
 *
 * Unified store replaces the dual-track system (local notifList in PSS + global store).
 * All callers must use NotificationStore.add(); do NOT maintain local notification lists.
 *
 * Thread-safety: all mutations dispatched to main thread via Handler.post.
 */
object NotificationStore {

    // ── Severity ─────────────────────────────────────────────────────────────
    enum class Severity { INFO, SUCCESS, WARNING, ERROR, PROGRESS }

    // ── Source category ───────────────────────────────────────────────────────
    enum class Source {
        LSP, DAP, BUILD, TERMINAL, GIT, EXTENSIONS,
        WORKSPACE, AUTH, AI, SYSTEM,
        // Legacy compat
        BACKUP, CONNECTOR
    }

    // ── Legacy Type compat shim (do not add new callers) ─────────────────────
    @Deprecated("Use Severity + Source instead")
    enum class Type {
        TERMINAL_ERROR, BUILD_STATUS, BACKUP, CONNECTOR, UBUNTU_STATUS, INFO;
        fun toSeverity(): Severity = when (this) {
            TERMINAL_ERROR -> Severity.ERROR
            BUILD_STATUS   -> Severity.INFO
            BACKUP         -> Severity.SUCCESS
            CONNECTOR      -> Severity.INFO
            UBUNTU_STATUS  -> Severity.SUCCESS
            INFO           -> Severity.INFO
        }
        fun toSource(): Source = when (this) {
            TERMINAL_ERROR -> Source.TERMINAL
            BUILD_STATUS   -> Source.BUILD
            BACKUP         -> Source.BACKUP
            CONNECTOR      -> Source.CONNECTOR
            UBUNTU_STATUS  -> Source.SYSTEM
            INFO           -> Source.SYSTEM
        }
    }

    // ── Notification item ─────────────────────────────────────────────────────
    data class Item(
        val id: Long = System.currentTimeMillis(),
        val title: String,
        val body: String,
        val severity: Severity = Severity.INFO,
        val source: Source = Source.SYSTEM,
        val read: Boolean = false,
    )

    // ── Settings (persisted via SharedPreferences — see NotificationSettings) ─
    data class Settings(
        val enabled: Boolean = true,
        val showToast: Boolean = true,
        val toastDurationMs: Long = 3000L,
        val maxHistory: Int = 100,
        // Severity filters — true = show
        val showInfo: Boolean = true,
        val showSuccess: Boolean = true,
        val showWarning: Boolean = true,
        val showError: Boolean = true,
        val showProgress: Boolean = true,
        // Source filters — true = show
        val srcLsp: Boolean = true,
        val srcDap: Boolean = true,
        val srcBuild: Boolean = true,
        val srcTerminal: Boolean = true,
        val srcGit: Boolean = true,
        val srcExtensions: Boolean = true,
        val srcWorkspace: Boolean = true,
        val srcAuth: Boolean = true,
        val srcAi: Boolean = true,
        val srcSystem: Boolean = true,
        val srcBackup: Boolean = true,
        val srcConnector: Boolean = true,
        // Bell position: "top" = top-bar (default), "bottom" = status-bar
        val bellPosition: String = "top",
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    val items = mutableStateListOf<Item>()

    /** Current settings — updated by NotificationSettingsStore */
    @Volatile var settings = Settings()

    val unreadCount: Int get() = items.count { !it.read }

    // ── Active toast for the in-app banner ────────────────────────────────────
    @Volatile var activeToast: Item? = null
        private set
    private val toastHandler = Handler(Looper.getMainLooper())
    private val clearToastRunnable = Runnable { activeToast = null; _toastListeners.forEach { it() } }
    private val _toastListeners = mutableListOf<() -> Unit>()
    fun addToastListener(l: () -> Unit) { _toastListeners.add(l) }
    fun removeToastListener(l: () -> Unit) { _toastListeners.remove(l) }

    // ── Public API ────────────────────────────────────────────────────────────

    fun add(
        title: String,
        body: String,
        severity: Severity = Severity.INFO,
        source: Source = Source.SYSTEM,
    ) {
        if (!settings.enabled) return
        if (!isSeverityAllowed(severity)) return
        if (!isSourceAllowed(source)) return

        val item = Item(title = title, body = body, severity = severity, source = source)
        post {
            items.add(0, item)
            if (items.size > settings.maxHistory) items.removeAt(items.lastIndex)
            // Fire toast if enabled
            if (settings.showToast) {
                activeToast = item
                _toastListeners.forEach { it() }
                toastHandler.removeCallbacks(clearToastRunnable)
                toastHandler.postDelayed(clearToastRunnable, settings.toastDurationMs)
            }
        }
    }

    /** Legacy compat — map old Type to Severity + Source */
    @Suppress("DEPRECATION")
    @Deprecated("Use add(title, body, severity, source) instead")
    fun add(title: String, body: String, type: Type) =
        add(title, body, type.toSeverity(), type.toSource())

    fun dismiss(id: Long) = post { items.removeAll { it.id == id } }

    fun markRead(id: Long) = post {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = items[idx].copy(read = true)
    }

    fun markAllRead() = post {
        val updated = items.map { it.copy(read = true) }
        items.clear()
        items.addAll(updated)
    }

    fun clearAll() = post { items.clear() }

    fun dismissToast() {
        toastHandler.removeCallbacks(clearToastRunnable)
        post { activeToast = null; _toastListeners.forEach { it() } }
    }

    // ── Filtered view ─────────────────────────────────────────────────────────
    fun filteredItems(
        severities: Set<Severity> = Severity.values().toSet(),
        sources: Set<Source> = Source.values().toSet(),
    ): List<Item> = items.filter { it.severity in severities && it.source in sources }

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun isSeverityAllowed(s: Severity): Boolean = when (s) {
        Severity.INFO     -> settings.showInfo
        Severity.SUCCESS  -> settings.showSuccess
        Severity.WARNING  -> settings.showWarning
        Severity.ERROR    -> settings.showError
        Severity.PROGRESS -> settings.showProgress
    }
    private fun isSourceAllowed(s: Source): Boolean = when (s) {
        Source.LSP        -> settings.srcLsp
        Source.DAP        -> settings.srcDap
        Source.BUILD      -> settings.srcBuild
        Source.TERMINAL   -> settings.srcTerminal
        Source.GIT        -> settings.srcGit
        Source.EXTENSIONS -> settings.srcExtensions
        Source.WORKSPACE  -> settings.srcWorkspace
        Source.AUTH       -> settings.srcAuth
        Source.AI         -> settings.srcAi
        Source.SYSTEM     -> settings.srcSystem
        Source.BACKUP     -> settings.srcBackup
        Source.CONNECTOR  -> settings.srcConnector
    }
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
