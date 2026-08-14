package com.codespace.ide.data

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase N — Advanced Notification System
 *
 * Central notification authority. Single source of truth for all app notifications.
 * Replaces scattered Toast/Snackbar calls with a unified, priority-aware, deduplicating,
 * groupable, action-capable notification pipeline.
 *
 * Thread-safety: all mutations dispatched to main thread via Handler.post.
 * Anti-spam: rate limiting via deduplication keys and timestamps.
 */
object NotificationStore {

    // ── Severity (visual + routing) ──────────────────────────────────────────
    enum class Severity { INFO, SUCCESS, WARNING, ERROR, PROGRESS }

    // ── Priority (queue ordering, persistence, presentation weight) ───────────
    enum class Priority { LOW, NORMAL, HIGH, CRITICAL }

    // ── Source category ───────────────────────────────────────────────────────
    enum class Source {
        LSP, DAP, BUILD, TERMINAL, GIT, EXTENSIONS,
        WORKSPACE, AUTH, AI, SYSTEM,
        // Legacy compat
        BACKUP, CONNECTOR
    }

    // ── Notification lifecycle state ─────────────────────────────────────────
    enum class NotificationState { ACTIVE, READ, DISMISSED, COMPLETED, FAILED }

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

    // ── Notification action (clickable button on a notification) ──────────────
    data class NotificationAction(
        val id: String,           // stable action ID, e.g. "retry", "view_logs"
        val label: String,        // button text, e.g. "Retry", "View Logs"
        val destructive: Boolean = false,  // styling hint
    )

    // ── Progress info (for PROGRESS severity) ────────────────────────────────
    data class ProgressInfo(
        val indeterminate: Boolean = false,
        val current: Int = 0,       // current progress value (0..max)
        val max: Int = 100,         // max progress value
        val statusMessage: String? = null,  // "Downloading language server…"
    )

    // ── Error details (two-level: user message + technical) ───────────────────
    data class ErrorDetails(
        val userMessage: String,          // "Git push failed — Push was rejected by the remote."
        val technicalDetails: String? = null,  // command, exit code, stderr — never secrets
    )

    // ── Notification item ─────────────────────────────────────────────────────
    data class Item(
        val id: Long = nextId.getAndIncrement(),
        val title: String,
        val body: String,
        val severity: Severity = Severity.INFO,
        val source: Source = Source.SYSTEM,
        val priority: Priority = Priority.NORMAL,
        val state: NotificationState = NotificationState.ACTIVE,
        val read: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
        // Phase N: Grouping — notifications with the same groupKey are collapsed
        val groupKey: String? = null,
        // Phase N: Deduplication — same dedupKey + recent timestamp → update existing
        val deduplicationKey: String? = null,
        // Phase N: Count of deduplicated occurrences (shown as "(4)" in UI)
        val dedupCount: Int = 1,
        // Phase N: Action buttons
        val actions: List<NotificationAction> = emptyList(),
        // Phase N: Progress info for PROGRESS severity
        val progress: ProgressInfo? = null,
        // Phase N: Error details for ERROR severity
        val errorDetails: ErrorDetails? = null,
        // Phase N: Category for finer-grained filtering within a source
        val category: String? = null,
        // Phase N: Persistent notifications survive app restart (in-memory only for now)
        val persistent: Boolean = false,
        // Phase N: Auto-dismiss duration in ms (0 = no auto-dismiss)
        val autoDismissMs: Long = 0L,
        // Phase N: Metadata bag for action handlers
        val metadata: Map<String, String> = emptyMap(),
    )

    // P-NOTIF-RESTRUCTURE: Bell/panel position — 3 corners, matches Test 39 spec
    const val POS_BOTTOM_RIGHT = "bottom-right"
    const val POS_BOTTOM_LEFT = "bottom-left"
    const val POS_TOP_RIGHT = "top-right"
    val ALL_POSITIONS = listOf(POS_BOTTOM_RIGHT, POS_BOTTOM_LEFT, POS_TOP_RIGHT)

    // ── Settings (persisted via SharedPreferences, see init()/loadPersisted()) ─
    data class Settings(
        val enabled: Boolean = true,
        val showToast: Boolean = true,
        val toastDurationMs: Long = 3000L,
        val maxHistory: Int = 100,
        val doNotDisturb: Boolean = false,
        val soundEnabled: Boolean = true,
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
        // P-NOTIF-RESTRUCTURE: bell/panel corner — one of ALL_POSITIONS
        val bellPosition: String = POS_BOTTOM_RIGHT,
        // Phase N: Priority filter — minimum priority to show as toast/banner
        val minToastPriority: Priority = Priority.LOW,
        // Phase N: Rate limiting — max notifications with same dedupKey per second
        val rateLimitPerSecond: Int = 5,
    )

    // ── Action handler registry ───────────────────────────────────────────────
    /** Register a handler for an action ID. Called when user taps an action button. */
    private val actionHandlers = ConcurrentHashMap<String, (Item, NotificationAction) -> Unit>()
    fun registerActionHandler(actionId: String, handler: (Item, NotificationAction) -> Unit) {
        actionHandlers[actionId] = handler
    }
    fun unregisterActionHandler(actionId: String) {
        actionHandlers.remove(actionId)
    }
    fun executeAction(itemId: Long, actionId: String) {
        post {
            val item = items.find { it.id == itemId } ?: return@post
            val action = item.actions.find { it.id == actionId } ?: return@post
            val handler = actionHandlers[actionId]
            if (handler != null) {
                try { handler(item, action) } catch (e: Exception) {
                    android.util.Log.e("NotificationStore", "Action handler '$actionId' threw", e)
                }
            }
            // Mark as read after action execution
            val idx = items.indexOfFirst { it.id == itemId }
            if (idx >= 0) items[idx] = items[idx].copy(read = true, state = NotificationState.READ)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    val items = mutableStateListOf<Item>()

    /** Current settings — persisted to SharedPreferences on every change. */
    @Volatile var settings = Settings()
        private set

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    /** Call once from Application.onCreate() — enables persistence + notification sound. */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext!!.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
        loadPersisted()
    }

    private fun loadPersisted() {
        val p = prefs ?: return
        settings = Settings(
            enabled = p.getBoolean("enabled", true),
            doNotDisturb = p.getBoolean("dnd", false),
            soundEnabled = p.getBoolean("sound", true),
            bellPosition = p.getString("position", POS_BOTTOM_RIGHT)
                ?.let { legacyPositionMigration(it) } ?: POS_BOTTOM_RIGHT,
            // Phase N: Load per-source and per-severity filters
            showInfo = p.getBoolean("showInfo", true),
            showSuccess = p.getBoolean("showSuccess", true),
            showWarning = p.getBoolean("showWarning", true),
            showError = p.getBoolean("showError", true),
            showProgress = p.getBoolean("showProgress", true),
            srcLsp = p.getBoolean("srcLsp", true),
            srcGit = p.getBoolean("srcGit", true),
            srcBuild = p.getBoolean("srcBuild", true),
            srcTerminal = p.getBoolean("srcTerminal", true),
            srcDap = p.getBoolean("srcDap", true),
            srcAi = p.getBoolean("srcAi", true),
        )
        // Phase 9: Load notification history
        p.getString("history", null)?.let { deserializeHistory(it) }
    }

    private fun persist() {
        prefs?.edit()
            ?.putBoolean("enabled", settings.enabled)
            ?.putBoolean("dnd", settings.doNotDisturb)
            ?.putBoolean("sound", settings.soundEnabled)
            ?.putString("position", settings.bellPosition)
            // Phase N: Persist per-source and per-severity filters
            ?.putBoolean("showInfo", settings.showInfo)
            ?.putBoolean("showSuccess", settings.showSuccess)
            ?.putBoolean("showWarning", settings.showWarning)
            ?.putBoolean("showError", settings.showError)
            ?.putBoolean("showProgress", settings.showProgress)
            ?.putBoolean("srcLsp", settings.srcLsp)
            ?.putBoolean("srcGit", settings.srcGit)
            ?.putBoolean("srcBuild", settings.srcBuild)
            ?.putBoolean("srcTerminal", settings.srcTerminal)
            ?.putBoolean("srcDap", settings.srcDap)
            ?.putBoolean("srcAi", settings.srcAi)
            // Phase N: Persist notification history (last 50)
            ?.putString("history", serializeHistory())
            ?.apply()
    }

    // ── Phase 9: Notification history persistence ─────────────────────────────
    private fun serializeHistory(): String {
        val arr = JSONArray()
        items.take(50).forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("body", item.body)
            obj.put("severity", item.severity.name)
            obj.put("source", item.source.name)
            obj.put("priority", item.priority.name)
            obj.put("state", item.state.name)
            obj.put("read", item.read)
            obj.put("timestamp", item.timestamp)
            obj.put("groupKey", item.groupKey ?: JSONObject.NULL)
            obj.put("dedupCount", item.dedupCount)
            obj.put("category", item.category ?: JSONObject.NULL)
            obj.put("persistent", item.persistent)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeHistory(json: String) {
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val item = Item(
                    id = obj.optLong("id", nextId.getAndIncrement()),
                    title = obj.optString("title", ""),
                    body = obj.optString("body", ""),
                    severity = runCatching { Severity.valueOf(obj.optString("severity", "INFO")) }.getOrDefault(Severity.INFO),
                    source = runCatching { Source.valueOf(obj.optString("source", "SYSTEM")) }.getOrDefault(Source.SYSTEM),
                    priority = runCatching { Priority.valueOf(obj.optString("priority", "NORMAL")) }.getOrDefault(Priority.NORMAL),
                    state = runCatching { NotificationState.valueOf(obj.optString("state", "ACTIVE")) }.getOrDefault(NotificationState.ACTIVE),
                    read = obj.optBoolean("read", false),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    groupKey = if (obj.isNull("groupKey")) null else obj.optString("groupKey", null),
                    dedupCount = obj.optInt("dedupCount", 1),
                    category = if (obj.isNull("category")) null else obj.optString("category", null),
                    persistent = obj.optBoolean("persistent", false),
                )
                // Ensure nextId is ahead of any restored IDs
                if (item.id >= nextId.get()) nextId.set(item.id + 1)
                items.add(item)
            }
        } catch (e: Exception) {
            // Corrupted history — start fresh
        }
    }

    /** Old builds stored "top"/"bottom" — map to the new 3-corner scheme. */
    private fun legacyPositionMigration(pos: String): String = when (pos) {
        "top" -> POS_TOP_RIGHT
        "bottom" -> POS_BOTTOM_RIGHT
        POS_BOTTOM_RIGHT, POS_BOTTOM_LEFT, POS_TOP_RIGHT -> pos
        else -> POS_BOTTOM_RIGHT
    }

    val unreadCount: Int get() = items.count { !it.read }

    val hasError: Boolean get() = items.any { !it.read && it.severity == Severity.ERROR }
    val hasWarning: Boolean get() = items.any { !it.read && it.severity == Severity.WARNING }
    val hasInfo: Boolean get() = items.any { !it.read && (it.severity == Severity.INFO || it.severity == Severity.SUCCESS) }

    /** Bell color: gray (idle), red (errors), amber (warnings), blue (info) */
    val bellState: String get() = when {
        hasError   -> "error"
        hasWarning -> "warning"
        hasInfo    -> "info"
        else       -> "idle"
    }

    /** Toggle DND mode. Persisted. */
    fun toggleDoNotDisturb() {
        settings = settings.copy(doNotDisturb = !settings.doNotDisturb)
        persist()
    }

    /** Toggle the "anycode" master notifications switch. Persisted. */
    fun toggleAppNotifications() {
        settings = settings.copy(enabled = !settings.enabled)
        persist()
    }

    /** Toggle notification sound on/off. Persisted. */
    fun toggleSound() {
        settings = settings.copy(soundEnabled = !settings.soundEnabled)
        persist()
    }

    // ── Phase 10: Per-source and per-severity toggle methods ──────────────────
    fun toggleSeverityFilter(severity: Severity) {
        settings = when (severity) {
            Severity.INFO -> settings.copy(showInfo = !settings.showInfo)
            Severity.SUCCESS -> settings.copy(showSuccess = !settings.showSuccess)
            Severity.WARNING -> settings.copy(showWarning = !settings.showWarning)
            Severity.ERROR -> settings.copy(showError = !settings.showError)
            Severity.PROGRESS -> settings.copy(showProgress = !settings.showProgress)
        }
        persist()
    }

    fun toggleSourceFilter(source: Source) {
        settings = when (source) {
            Source.LSP -> settings.copy(srcLsp = !settings.srcLsp)
            Source.DAP -> settings.copy(srcDap = !settings.srcDap)
            Source.BUILD -> settings.copy(srcBuild = !settings.srcBuild)
            Source.TERMINAL -> settings.copy(srcTerminal = !settings.srcTerminal)
            Source.GIT -> settings.copy(srcGit = !settings.srcGit)
            Source.EXTENSIONS -> settings.copy(srcExtensions = !settings.srcExtensions)
            Source.WORKSPACE -> settings.copy(srcWorkspace = !settings.srcWorkspace)
            Source.AUTH -> settings.copy(srcAuth = !settings.srcAuth)
            Source.AI -> settings.copy(srcAi = !settings.srcAi)
            Source.SYSTEM -> settings.copy(srcSystem = !settings.srcSystem)
            Source.BACKUP -> settings.copy(srcBackup = !settings.srcBackup)
            Source.CONNECTOR -> settings.copy(srcConnector = !settings.srcConnector)
        }
        persist()
    }

    fun setMaxHistory(max: Int) {
        settings = settings.copy(maxHistory = max.coerceIn(10, 500))
        persist()
    }

    fun setToastDuration(ms: Long) {
        settings = settings.copy(toastDurationMs = ms.coerceIn(1000L, 10000L))
        persist()
    }

    // ── Phase 11-13: Integration helpers for build/debugger/terminal ──────────
    /**
     * Phase 11: Notify about build events.
     */
    fun notifyBuildEvent(
        title: String,
        body: String,
        isError: Boolean = false,
        progress: ProgressInfo? = null,
        actions: List<NotificationAction> = emptyList(),
    ) = add(
        title = title,
        body = body,
        severity = when {
            isError -> Severity.ERROR
            progress != null -> Severity.PROGRESS
            else -> Severity.SUCCESS
        },
        source = Source.BUILD,
        priority = if (isError) Priority.HIGH else Priority.NORMAL,
        progress = progress,
        actions = actions,
        deduplicationKey = "build:$title",
    )

    /**
     * Phase 12: Notify about debugger events.
     */
    fun notifyDebugEvent(
        title: String,
        body: String,
        isError: Boolean = false,
        actions: List<NotificationAction> = emptyList(),
    ) = add(
        title = title,
        body = body,
        severity = if (isError) Severity.ERROR else Severity.INFO,
        source = Source.DAP,
        priority = if (isError) Priority.HIGH else Priority.LOW,
        actions = actions,
        deduplicationKey = "dap:$title",
    )

    /**
     * Phase 13: Notify about terminal events.
     */
    fun notifyTerminalEvent(
        title: String,
        body: String,
        isError: Boolean = false,
    ) = add(
        title = title,
        body = body,
        severity = if (isError) Severity.WARNING else Severity.INFO,
        source = Source.TERMINAL,
        priority = if (isError) Priority.NORMAL else Priority.LOW,
        deduplicationKey = "term:$title",
    )

    /** Set bell/panel position — one of ALL_POSITIONS. Persisted. */
    fun setBellPosition(pos: String) {
        settings = settings.copy(bellPosition = if (pos in ALL_POSITIONS) pos else POS_BOTTOM_RIGHT)
        persist()
    }

    // ── Active toast for the in-app banner ────────────────────────────────────
    @Volatile var activeToast: Item? = null
        private set
    private val toastHandler = Handler(Looper.getMainLooper())
    private val clearToastRunnable = Runnable { activeToast = null; _toastListeners.forEach { it() } }
    private val _toastListeners = mutableListOf<() -> Unit>()
    fun addToastListener(l: () -> Unit) { _toastListeners.add(l) }
    fun removeToastListener(l: () -> Unit) { _toastListeners.remove(l) }

    // ── Rate limiting state ───────────────────────────────────────────────────
    private val dedupTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val dedupLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Add a notification with full Phase N features.
     *
     * Deduplication: if a notification with the same deduplicationKey exists and was
     * added within the last 5 seconds, the existing notification is updated (count++,
     * timestamp refreshed) instead of creating a new one.
     */
    @JvmOverloads
    fun add(
        title: String,
        body: String,
        severity: Severity = Severity.INFO,
        source: Source = Source.SYSTEM,
        priority: Priority = Priority.NORMAL,
        actions: List<NotificationAction> = emptyList(),
        groupKey: String? = null,
        deduplicationKey: String? = null,
        progress: ProgressInfo? = null,
        errorDetails: ErrorDetails? = null,
        category: String? = null,
        persistent: Boolean = false,
        autoDismissMs: Long = 0L,
        metadata: Map<String, String> = emptyMap(),
    ) {
        if (!settings.enabled) return
        if (!isSeverityAllowed(severity)) return
        if (!isSourceAllowed(source)) return

        // Phase N: Deduplication — update existing if same key within rate-limit window
        if (deduplicationKey != null) {
            val now = System.currentTimeMillis()
            synchronized(dedupLock) {
                // Check rate limiting
                val timestamps = dedupTimestamps.getOrPut(deduplicationKey) { mutableListOf() }
                timestamps.removeAll { now - it > 1000L } // within last 1s
                if (timestamps.size >= settings.rateLimitPerSecond) {
                    // Rate limited — update the most recent notification instead of creating new
                    val existing = items.firstOrNull { it.deduplicationKey == deduplicationKey }
                    if (existing != null) {
                        post {
                            val idx = items.indexOfFirst { it.id == existing.id }
                            if (idx >= 0) {
                                items[idx] = items[idx].copy(
                                    dedupCount = items[idx].dedupCount + 1,
                                    timestamp = now,
                                    body = body,
                                )
                            }
                        }
                        timestamps.add(now)
                        return
                    }
                }
                timestamps.add(now)

                // Also check for existing notification with same dedup key (within 5s)
                val existing = items.firstOrNull {
                    it.deduplicationKey == deduplicationKey && (now - it.timestamp) < 5000L
                }
                if (existing != null) {
                    post {
                        val idx = items.indexOfFirst { it.id == existing.id }
                        if (idx >= 0) {
                            items[idx] = items[idx].copy(
                                dedupCount = items[idx].dedupCount + 1,
                                timestamp = now,
                                body = body,
                            )
                        }
                    }
                    return
                }
            }
        }

        val item = Item(
            title = title,
            body = body,
            severity = severity,
            source = source,
            priority = priority,
            actions = actions,
            groupKey = groupKey,
            deduplicationKey = deduplicationKey,
            progress = progress,
            errorDetails = errorDetails,
            category = category,
            persistent = persistent,
            autoDismissMs = autoDismissMs,
            metadata = metadata,
        )
        post {
            items.add(0, item)
            if (items.size > settings.maxHistory) items.removeAt(items.lastIndex)

            // Fire toast if enabled (respect DND: errors always show, info/warning suppressed)
            val shouldShowToast = settings.showToast &&
                priority.ordinal >= settings.minToastPriority.ordinal &&
                !(settings.doNotDisturb && severity != Severity.ERROR)
            if (shouldShowToast) {
                activeToast = item
                _toastListeners.forEach { it() }
                toastHandler.removeCallbacks(clearToastRunnable)
                val duration = if (autoDismissMs > 0) autoDismissMs else settings.toastDurationMs
                toastHandler.postDelayed(clearToastRunnable, duration)
            }
            if (!(settings.doNotDisturb && severity != Severity.ERROR)) playSound()
            persist()
        }
    }

    /**
     * Update an existing progress notification in place.
     * Use for long-running operations: 10% → 25% → 50% → 75% → 100% is ONE notification.
     */
    fun updateProgress(
        notificationId: Long,
        current: Int,
        statusMessage: String? = null,
        severity: Severity? = null,
    ) {
        post {
            val idx = items.indexOfFirst { it.id == notificationId }
            if (idx >= 0) {
                val item = items[idx]
                items[idx] = item.copy(
                    progress = (item.progress ?: ProgressInfo()).copy(
                        current = current,
                        statusMessage = statusMessage ?: item.progress?.statusMessage,
                    ),
                    severity = severity ?: item.severity,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * Complete a progress notification — transition to completed/failed state.
     */
    @JvmOverloads
    fun completeProgress(
        notificationId: Long,
        success: Boolean = true,
        title: String? = null,
        body: String? = null,
    ) {
        post {
            val idx = items.indexOfFirst { it.id == notificationId }
            if (idx >= 0) {
                val item = items[idx]
                items[idx] = item.copy(
                    severity = if (success) Severity.SUCCESS else Severity.ERROR,
                    state = if (success) NotificationState.COMPLETED else NotificationState.FAILED,
                    progress = null,
                    title = title ?: item.title,
                    body = body ?: item.body,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * Start a progress notification and return its ID for later updates.
     */
    @JvmOverloads
    fun startProgress(
        title: String,
        body: String,
        source: Source = Source.SYSTEM,
        indeterminate: Boolean = true,
        current: Int = 0,
        max: Int = 100,
        statusMessage: String? = null,
        actions: List<NotificationAction> = emptyList(),
        deduplicationKey: String? = null,
    ): Long {
        val item = Item(
            title = title,
            body = body,
            severity = Severity.PROGRESS,
            source = source,
            priority = Priority.HIGH,
            progress = ProgressInfo(indeterminate, current, max, statusMessage),
            actions = actions,
            deduplicationKey = deduplicationKey,
        )
        // Add directly to get the ID
        if (!settings.enabled) return -1L
        post {
            items.add(0, item)
            if (items.size > settings.maxHistory) items.removeAt(items.lastIndex)
        }
        return item.id
    }

    /** P-NOTIF-RESTRUCTURE: Plays the system default notification sound (best-effort). */
    private fun playSound() {
        if (!settings.soundEnabled) return
        val ctx = appContext ?: return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.play()
        } catch (_: Exception) {
            // Best-effort — never crash the app over a missing/broken ringtone.
        }
    }

    /** Legacy compat — map old Type to Severity + Source */
    @Suppress("DEPRECATION")
    @Deprecated("Use add(title, body, severity, source) instead")
    fun add(title: String, body: String, type: Type) =
        add(title, body, type.toSeverity(), type.toSource())

    fun dismiss(id: Long) = post { items.removeAll { it.id == id }; persist() }

    fun markRead(id: Long) = post {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = items[idx].copy(read = true, state = NotificationState.READ)
    }

    fun markAllRead() = post {
        val updated = items.map { it.copy(read = true, state = NotificationState.READ) }
        items.clear()
        items.addAll(updated)
    }

    // ── Phase 8: Undo support ─────────────────────────────────────────────────
    private val undoStack = mutableListOf<Item>()
    private const val MAX_UNDO = 10

    /** Dismiss an item but keep it in the undo stack for potential restoration. */
    fun dismissWithUndo(id: Long) = post {
        val item = items.find { it.id == id }
        if (item != null) {
            undoStack.add(0, item)
            if (undoStack.size > MAX_UNDO) undoStack.removeAt(undoStack.size - 1)
            items.removeAll { it.id == id }
            _toastListeners.forEach { it() }
        }
    }

    /** Restore the most recently dismissed item. Returns true if an item was restored. */
    fun undoDismiss(): Boolean {
        if (undoStack.isEmpty()) return false
        val item = undoStack.removeAt(0)
        post {
            items.add(0, item.copy(read = false, state = NotificationState.ACTIVE))
            _toastListeners.forEach { it() }
        }
        return true
    }

    /** Clear all items but keep them in the undo stack. */
    fun clearAll() = post {
        undoStack.addAll(0, items.take(MAX_UNDO))
        if (undoStack.size > MAX_UNDO) undoStack.subList(MAX_UNDO, undoStack.size).clear()
        items.clear()
        _toastListeners.forEach { it() }
    }

    /** Clear only completed/failed/dismissed items (keep active errors). */
    fun clearResolved() = post {
        items.removeAll {
            it.state in setOf(NotificationState.COMPLETED, NotificationState.FAILED, NotificationState.DISMISSED)
        }
    }

    fun dismissToast() {
        toastHandler.removeCallbacks(clearToastRunnable)
        post { activeToast = null; _toastListeners.forEach { it() } }
    }

    // ── Filtered view ─────────────────────────────────────────────────────────
    fun filteredItems(
        severities: Set<Severity> = Severity.values().toSet(),
        sources: Set<Source> = Source.values().toSet(),
    ): List<Item> = items.filter { it.severity in severities && it.source in sources }

    /** Get grouped notifications — groupKey → list of items (first item is the group representative). */
    fun groupedItems(): List<Item> {
        val groups = mutableMapOf<String, MutableList<Item>>()
        val ungrouped = mutableListOf<Item>()
        for (item in items) {
            val gk = item.groupKey
            if (gk != null) {
                groups.getOrPut(gk) { mutableListOf() }.add(item)
            } else {
                ungrouped.add(item)
            }
        }
        // For grouped items, return the first with dedupCount = sum of group
        val result = mutableListOf<Item>()
        for ((_, groupItems) in groups) {
            val representative = groupItems.first()
            val totalCount = groupItems.sumOf { it.dedupCount }
            result.add(representative.copy(dedupCount = totalCount))
        }
        result.addAll(ungrouped)
        return result.sortedByDescending { it.priority.ordinal }
    }

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
