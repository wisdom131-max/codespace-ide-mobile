package com.codespace.ide.chat.panel.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.panel.ChatSessionManager
import com.codespace.ide.chat.session.SessionDelete
import com.codespace.ide.chat.session.SessionFilter
import com.codespace.ide.chat.session.SessionIndex
import com.codespace.ide.chat.session.SessionIndexRead
import com.codespace.ide.chat.session.SessionMeta
import com.codespace.ide.chat.session.SessionQuery
import com.codespace.ide.chat.session.SessionSignals
import com.codespace.ide.chat.session.SessionSort
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M2 — THE SESSIONS PANEL.
 *
 * Every control here is backed by the M0/M1 model: "New Session" writes a real index record,
 * a row tap really switches the active conversation, rename/archive/delete really write, and
 * the search, sort and filters are views over the stored metadata (they never write).
 *
 * Nothing is shown that cannot work. The two filters whose data has no producer in VN Code
 * (External, Failed) are listed with their reason and cannot be selected, rather than being
 * removed from a control inventory the reference defines.
 */
@Composable
internal fun SessionsPanel(
    projectId: String,
    manager: ChatSessionManager,
    activeId: String,
    onSwitched: (String) -> Unit,
    onBackToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ChatTokens.LocalChatColors.current
    var query by remember(projectId) { mutableStateOf(SessionQuery()) }
    var revision by remember(projectId) { mutableStateOf(0) }
    var showSearch by remember(projectId) { mutableStateOf(false) }
    var showFilter by remember(projectId) { mutableStateOf(false) }
    var showSort by remember(projectId) { mutableStateOf(false) }
    var showOverflow by remember(projectId) { mutableStateOf(false) }
    var actionsFor by remember(projectId) { mutableStateOf<SessionRowUi?>(null) }
    var renameFor by remember(projectId) { mutableStateOf<SessionRowUi?>(null) }
    var deleteFor by remember(projectId) { mutableStateOf<SessionRowUi?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var tails by remember(projectId) { mutableStateOf<Map<String, StoredMessage?>>(emptyMap()) }

    val read = remember(projectId, revision) { manager.sessionIndex(projectId) }
    val index = when (read) {
        is SessionIndexRead.Ok -> read.index
        is SessionIndexRead.Unreadable -> SessionIndex()
    }
    val readProblem = (read as? SessionIndexRead.Unreadable)?.reason
    val stagedWaiting = remember(projectId, revision) {
        com.codespace.ide.chat.PendingChangesStore.pendingFor(projectId).isNotEmpty()
    }
    val signalsFor: (SessionMeta) -> SessionSignals = { meta ->
        val tail = tails[meta.id]
        SessionSignals(
            hasMessages = tail != null,
            lastReplyComplete = tail != null && tail.role == "assistant" && tail.complete,
            // Only the live session can be "In Progress": a stored one cannot stream while the
            // app is closed, and claiming otherwise would be a stored lie.
            streamingNow = manager.isStreaming(projectId, meta.id),
            needsAttention = meta.id == activeId && stagedWaiting,
        )
    }
    // Re-read whenever the table or this panel's revision changes: the file is tiny and a
    // stale pin set would draw the marker on the wrong row.
    val pins = remember(projectId, revision) { manager.pinnedSessions(projectId) }
    val rows = SessionsViewLogic.rows(
        index.sessions, query, signalsFor, System.currentTimeMillis(), activeId, pins,
    )

    // Previews come from the transcripts on disk, read OFF the main thread: a row must never
    // block composition on file I/O, and a failed read simply means no preview.
    val idsKey = index.sessions.joinToString(",") { it.id }
    LaunchedEffect(projectId, idsKey, revision) {
        val loaded = withContext(Dispatchers.IO) {
            index.sessions.associate { it.id to manager.transcriptTail(projectId, it.id) }
        }
        tails = loaded
    }

    BoxWithConstraints(modifier.fillMaxSize().background(c.bg)) {
        val widthDp = maxWidth.value
        val header = SessionsPanelSpec.headerForWidth(widthDp)
        Column(Modifier.fillMaxSize().padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp)) {
            SessionsHeaderRow(
                spec = header,
                badge = SessionsPanelSpec.filterBadge(SessionsViewLogic.activeFilterCount(query)),
                colors = c,
                searchOpen = showSearch,
                onBack = onBackToChat,
                onSearch = { showSearch = !showSearch },
                onRefresh = {
                    revision++
                    notice = null
                },
                onFilter = { showFilter = true },
                onOverflow = { showOverflow = true },
            )
            if (SessionsPanelSpec.showsNewSessionButton(widthDp)) {
                NewSessionButton(c) {
                    when (val created = manager.createSession(projectId)) {
                        is com.codespace.ide.chat.session.SessionCreate.Created -> {
                            query = SessionQuery()
                            showSearch = false
                            revision++
                            onSwitched(created.meta.id)
                        }
                        is com.codespace.ide.chat.session.SessionCreate.Failed ->
                            notice = created.reason
                    }
                }
            }
            if (showSearch) {
                SessionSearchField(
                    value = query.search.orEmpty(),
                    colors = c,
                    filterActive = SessionsViewLogic.activeFilterCount(query) > 0,
                    onChange = { query = SessionsViewLogic.withSearch(query, it) },
                    onToggleFilter = { showFilter = true },
                    onClose = {
                        showSearch = false
                        query = SessionsViewLogic.withSearch(query, "")
                    },
                )
            }
            val problem = notice ?: readProblem
            if (problem != null) PanelNotice(problem, c)
            SessionsViewLogic.viewSummary(query)?.let { SummaryLine(it, c) }
            // M5: memory scope lives with the sessions, where its meaning is visible.
            MemoryScopeRow(c)
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) {
                    SessionsEmptyState(
                        message = SessionsViewLogic.emptyMessage(index.sessions.size, query),
                        colors = c,
                        canCreate = SessionsPanelSpec.showsNewSessionButton(widthDp),
                        onCreate = {
                            (manager.createSession(projectId) as? com.codespace.ide.chat.session.SessionCreate.Created)
                                ?.let { created ->
                                    revision++
                                    onSwitched(created.meta.id)
                                }
                        },
                    )
                } else {
                    SessionList(
                        rows = rows,
                        tails = tails,
                        colors = c,
                        onOpen = { row ->
                            val failure = manager.switchTo(projectId, row.id)
                            if (failure == null) {
                                manager.markRead(projectId, row.id)
                                revision++
                                onSwitched(row.id)
                            } else {
                                notice = failure
                            }
                        },
                        onLongPress = { actionsFor = it },
                    )
                }
            }
        }
    }

    actionsFor?.let { row ->
        RowActionsDialog(
            row = row,
            colors = c,
            onPick = { action ->
                actionsFor = null
                when (action) {
                    SessionRowAction.PIN -> {
                        notice = manager.setPinned(projectId, row.id, true)
                        revision++
                    }
                    SessionRowAction.UNPIN -> {
                        notice = manager.setPinned(projectId, row.id, false)
                        revision++
                    }
                    SessionRowAction.RENAME -> renameFor = row
                    SessionRowAction.ARCHIVE -> {
                        notice = manager.archiveSession(projectId, row.id, true).also { if (it == null) revision++ }
                    }
                    SessionRowAction.UNARCHIVE -> {
                        notice = manager.archiveSession(projectId, row.id, false).also { if (it == null) revision++ }
                    }
                    SessionRowAction.DELETE -> deleteFor = row
                }
            },
            onDismiss = { actionsFor = null },
        )
    }
    renameFor?.let { row ->
        RenameDialog(
            initial = row.title,
            colors = c,
            onConfirm = { title ->
                notice = manager.renameSession(projectId, row.id, title)
                if (notice == null) revision++
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { row ->
        ConfirmDialog(
            title = SessionsPanelSpec.DELETE_TITLE,
            body = row.title,
            confirm = SessionsPanelSpec.DELETE_CONFIRM,
            confirmIsDestructive = true,
            colors = c,
            onConfirm = {
                when (val result = manager.deleteSession(projectId, row.id)) {
                    is SessionDelete.Failed -> notice = result.reason
                    is SessionDelete.Deleted -> {
                        revision++
                        // A failed byte delete is NOT a success: say so rather than let the user
                        // find out after a restart.
                        if (!result.transcriptRemoved) notice = result.note
                    }
                }
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )
    }
    if (showFilter) {
        FilterDialog(
            query = query,
            colors = c,
            onToggle = { query = SessionsViewLogic.toggleFilter(query, it) },
            onDismiss = { showFilter = false },
        )
    }
    if (showSort) {
        SortDialog(
            current = query.sort,
            colors = c,
            onPick = {
                query = SessionsViewLogic.withSort(query, it)
                showSort = false
            },
            onDismiss = { showSort = false },
        )
    }
    if (showOverflow) {
        SimpleMenuDialog(
            title = SessionsPanelSpec.TITLE,
            items = listOf(SessionsPanelSpec.SORT_TITLE, SessionsPanelSpec.FILTER_TITLE),
            colors = c,
            onPick = { i ->
                showOverflow = false
                if (i == 0) showSort = true else showFilter = true
            },
            onDismiss = { showOverflow = false },
        )
    }
}

@Composable
private fun SessionsHeaderRow(
    spec: SessionsHeaderSpec,
    badge: String?,
    colors: ChatColors,
    searchOpen: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onFilter: () -> Unit,
    onOverflow: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = ChatTokens.PAD_VERTICAL.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderAction("←", SessionsPanelSpec.BACK, colors, onBack)
        Text(
            SessionsPanelSpec.TITLE,
            color = colors.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            modifier = Modifier.weight(1f).padding(start = ChatTokens.GAP.dp),
        )
        if (SessionsHeaderAction.REFRESH in spec.inline) HeaderAction("Refresh", "Refresh sessions", colors, onRefresh)
        HeaderAction(
            label = if (searchOpen) "Search ✕" else "Search",
            description = SessionsPanelSpec.SEARCH_HINT,
            colors = colors,
            onClick = onSearch,
            highlighted = searchOpen,
        )
        if (SessionsHeaderAction.FILTER in spec.inline) {
            HeaderAction("Filter" + (badge?.let { "  " + it } ?: ""), "Filter and sort", colors, onFilter, highlighted = badge != null)
        }
        if (spec.needsOverflowButton) HeaderAction("⋯", "More actions", colors, onOverflow)
    }
}

@Composable
private fun HeaderAction(
    label: String,
    description: String,
    colors: ChatColors,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        Modifier.heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .then(if (highlighted) Modifier.background(colors.surfaceAlt) else Modifier)
            .combinedClickableSafe(onClick)
            .padding(horizontal = ChatTokens.CHIP_PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) colors.accent else colors.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            maxLines = 1,
        )
    }
    // description is carried for accessibility services via the row's semantics in a later pass
}

private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun NewSessionButton(colors: ChatColors, onClick: () -> Unit) {
    // Item 2: the button used to paint a full FONT_BODY label inside 10 dp of vertical padding
    // inside a 40 dp box, which read as a slab next to the model picker and the header chips.
    // Now the OUTER box keeps the 40 dp touch target and the accent pill inside it is compact,
    // so density improves without giving up the tap area.
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
            .clickableBox(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = ChatTokens.NEW_SESSION_VISUAL_HEIGHT.dp)
                .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                SessionsPanelSpec.NEW_SESSION,
                color = colors.bg,
                fontSize = ChatTokens.FONT_SMALL.sp,
            )
        }
    }
}

private fun Modifier.clickableBox(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun SessionSearchField(
    value: String,
    colors: ChatColors,
    filterActive: Boolean,
    onChange: (String) -> Unit,
    onToggleFilter: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(SessionsPanelSpec.SEARCH_HINT, color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = colors.text,
                    fontSize = ChatTokens.FONT_BODY.sp,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HeaderAction("≡", "Filter", colors, onToggleFilter, highlighted = filterActive)
        HeaderAction("✕", "Close search", colors, onClose)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(
    rows: List<SessionRowUi>,
    tails: Map<String, StoredMessage?>,
    colors: ChatColors,
    onOpen: (SessionRowUi) -> Unit,
    onLongPress: (SessionRowUi) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        rows.forEach { row ->
            SessionRowItem(
                row = row,
                preview = previewOf(tails[row.id]),
                colors = colors,
                onClick = { onOpen(row) },
                onLongClick = { onLongPress(row) },
            )
        }
    }
}

/** First non-blank line of the last message, clipped. Empty when there is nothing to say. */
internal fun previewOf(tail: StoredMessage?): String {
    val raw = tail?.text?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= 96) line else line.take(95) + "…"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(
    row: SessionRowUi,
    preview: String,
    colors: ChatColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = SessionsPanelSpec.ROW_MIN_DP.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .then(if (row.isActive) Modifier.background(colors.surfaceAlt) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.pinned) {
                Text(
                    "\u25C6",
                    color = colors.accent,
                    fontSize = ChatTokens.FONT_MICRO.sp,
                )
                Spacer(Modifier.width(ChatTokens.GAP_TIGHT.dp))
            }
            Text(
                row.title,
                color = colors.text,
                fontSize = ChatTokens.FONT_BODY.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(ChatTokens.GAP.dp))
            Text(row.timeLabel, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.statusLabel != null) {
                StatusDot(row.statusLabel, colors)
                Spacer(Modifier.width(ChatTokens.GAP_TIGHT.dp))
                Text(row.statusLabel, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp, maxLines = 1)
                Spacer(Modifier.width(ChatTokens.GAP_TIGHT.dp))
            }
            if (row.archived) {
                Text(SessionFilter.ARCHIVED.label, color = colors.textSecondary, fontSize = ChatTokens.FONT_MICRO.sp)
                Spacer(Modifier.width(ChatTokens.GAP_TIGHT.dp))
            }
            if (row.unread) {
                Text("●", color = colors.accent, fontSize = ChatTokens.FONT_MICRO.sp)
                Spacer(Modifier.width(ChatTokens.GAP_TIGHT.dp))
            }
            Text(
                preview,
                color = colors.textSecondary,
                fontSize = ChatTokens.FONT_MICRO.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusDot(label: String, colors: ChatColors) {
    val color = when (label) {
        SessionFilter.ATTENTION_NEEDED.label -> colors.warning
        SessionFilter.IN_PROGRESS.label -> colors.accent
        else -> colors.success
    }
    Box(Modifier.size(ChatTokens.GAP_TIGHT.dp).clip(RoundedCornerShape(99.dp)).background(color))
}

@Composable
private fun SessionsEmptyState(
    message: String,
    colors: ChatColors,
    canCreate: Boolean,
    onCreate: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(ChatTokens.PAD_HORIZONTAL.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = colors.textSecondary, fontSize = ChatTokens.FONT_SMALL.sp)
        if (canCreate) {
            Spacer(Modifier.heightIn(min = ChatTokens.GAP.dp))
            Box(
                Modifier.heightIn(min = SessionsPanelSpec.TOUCH_TARGET_DP.dp)
                    .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                    .clickableBox(onCreate)
                    .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
            ) { Text(SessionsPanelSpec.NEW_SESSION, color = colors.accent, fontSize = ChatTokens.FONT_SMALL.sp) }
        }
    }
}

@Composable
private fun PanelNotice(text: String, colors: ChatColors) {
    Box(
        Modifier.fillMaxWidth()
            .padding(top = ChatTokens.GAP_TIGHT.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
            .background(colors.warningBg)
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.CHIP_PAD_VERTICAL.dp),
    ) { Text(text, color = colors.text, fontSize = ChatTokens.FONT_MICRO.sp) }
}

@Composable
private fun SummaryLine(text: String, colors: ChatColors) {
    Text(
        text,
        color = colors.textSecondary,
        fontSize = ChatTokens.FONT_MICRO.sp,
        modifier = Modifier.padding(top = ChatTokens.CHIP_PAD_VERTICAL.dp),
    )
}
