package com.codespace.ide.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.data.NotificationStore
import java.util.concurrent.TimeUnit

// Legacy compat — kept so existing callers don't break
internal data class NotifItem(val id: Long, val msg: String, val type: String)

// ── Position helpers (Test 39: 3-corner bell/panel positioning) ────────────────

private fun cornerAlignment(pos: String): Alignment = when (pos) {
    NotificationStore.POS_TOP_RIGHT    -> Alignment.TopEnd
    NotificationStore.POS_BOTTOM_LEFT  -> Alignment.BottomStart
    else /* BOTTOM_RIGHT */             -> Alignment.BottomEnd
}

private fun cornerLabel(pos: String): String = when (pos) {
    NotificationStore.POS_TOP_RIGHT    -> "Top Right"
    NotificationStore.POS_BOTTOM_LEFT  -> "Bottom Left"
    else                                -> "Bottom Right"
}

// ── VS Code-style Notification Bell ────────────────────────────────────────────

/**
 * P-NOTIF-RESTRUCTURE (Test 39): VS Code-style notification bell.
 * - Bigger, translucent outline icon (status bar color shows through).
 * - Unread indicator is a small round DOT, not a numeric badge (VS Code parity).
 * - Swaps to a "slash" bell + dims when Do Not Disturb is on.
 *
 * @param onClick Called when bell is tapped (opens notification center)
 */
@Composable
internal fun NotificationBell(
    iconSize: Int = 22,
    onClick: () -> Unit,
) {
    val unread = remember { derivedStateOf { NotificationStore.unreadCount } }.value
    val bellState = remember { derivedStateOf { NotificationStore.bellState } }.value
    val dnd = remember { derivedStateOf { NotificationStore.settings.doNotDisturb } }.value

    // P35-NOTIF: VS Code bell colors — translucent so the bar color shows through.
    val bellColor = when {
        dnd -> Color(0xFF7F849C).copy(alpha = 0.45f)   // dimmed when DND on
        bellState == "error"   -> Color(0xFFF38BA8).copy(alpha = 0.9f)
        bellState == "warning" -> Color(0xFFFAB387).copy(alpha = 0.9f)
        bellState == "info"    -> Color(0xFF89B4FA).copy(alpha = 0.9f)
        else                    -> Color(0xFFFFFFFF).copy(alpha = 0.55f) // translucent white, idle
    }

    Box(
        Modifier.size((iconSize + 10).dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (dnd) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
            null,
            tint = bellColor,
            modifier = Modifier.size(iconSize.dp),
        )
        // Round dot indicator — VS Code style, no numbers.
        if (unread > 0 && !dnd) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(
                        if (bellState == "error") Color(0xFFF38BA8) else Color(0xFF89B4FA),
                        CircleShape,
                    ),
            )
        }
    }
}

// ── In-app Toast Banner (VS Code-style floating card, 3-corner anchored) ───────

/**
 * P-NOTIF-RESTRUCTURE: VS Code-style notification toast.
 * Appears as a compact floating card anchored to whichever corner is selected
 * in NotificationStore.settings.bellPosition (bottom-right / bottom-left / top-right).
 * Card is ~320dp wide, rounded corners, subtle border, shadow — NOT full-width.
 * Auto-dismisses after toastDurationMs.
 */
@Composable
internal fun NotificationToastBanner() {
    var toast by remember { mutableStateOf(NotificationStore.activeToast) }
    LaunchedEffect(Unit) {
        while (true) {
            toast = NotificationStore.activeToast
            kotlinx.coroutines.delay(100)
        }
    }
    val pos = NotificationStore.settings.bellPosition
    val isTop = pos == NotificationStore.POS_TOP_RIGHT
    val isLeft = pos == NotificationStore.POS_BOTTOM_LEFT

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f),
        contentAlignment = cornerAlignment(pos),
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = if (isTop) slideInVertically { -it } + fadeIn() else slideInVertically { it } + fadeIn(),
            exit = if (isTop) slideOutVertically { -it } + fadeOut() else slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .padding(
                    top = if (isTop) 28.dp else 0.dp,
                    bottom = if (isTop) 0.dp else 28.dp,
                    start = if (isLeft) 8.dp else 0.dp,
                    end = if (isLeft) 0.dp else 8.dp,
                ),
        ) {
            val t = toast ?: return@AnimatedVisibility
            // VS Code-style compact card — 320dp wide, rounded, border, shadow
            Card(
                Modifier
                    .width(320.dp)
                    .clickable(enabled = false) {},
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                elevation = CardDefaults.cardElevation(6.dp),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF313244)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val (icon, color) = severityIcon(t.severity)
                    Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (t.title.isNotBlank() && t.title != t.body) {
                            Text(
                                t.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFCDD6F4),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            t.body,
                            fontSize = 11.sp,
                            color = Color(0xFF9CA0B0),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Close, null,
                        tint = Color(0xFF6C7086),
                        modifier = Modifier.size(14.dp).clickable { NotificationStore.dismissToast() },
                    )
                }
            }
        }
    }
}

// ── Notification Drawer ───────────────────────────────────────────────────────

/**
 * P-NOTIF-RESTRUCTURE (Test 39): Full VS Code-style notification drawer.
 * Reads from NotificationStore (single source of truth).
 * Header row = title + 4 icons: Clear All, DND menu, Reposition menu, Collapse chevron.
 * Tapping an ERROR row jumps straight to the Problems panel (onOpenProblems).
 * Tapping any other row expands it in place to show the full message.
 */
@Composable
internal fun NotificationDrawerOverlay(
    @Suppress("UNUSED_PARAMETER") notifList: List<NotifItem> = emptyList(), // legacy param — ignored
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShowCommands: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
) {
    val allItems by remember { derivedStateOf { NotificationStore.items.toList() } }
    val unread = NotificationStore.unreadCount
    val pos = NotificationStore.settings.bellPosition
    val isTop = pos == NotificationStore.POS_TOP_RIGHT
    val isLeft = pos == NotificationStore.POS_BOTTOM_LEFT

    // Filter state
    var filterSeverity by remember { mutableStateOf<NotificationStore.Severity?>(null) }

    val displayItems = remember(allItems, filterSeverity) {
        allItems.filter { item -> filterSeverity == null || item.severity == filterSeverity }
    }

    // Mark all read when drawer opens
    LaunchedEffect(Unit) { NotificationStore.markAllRead() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x44000000))
            .clickable { onDismiss() }
    ) {
        Card(
            Modifier
                .align(cornerAlignment(pos))
                .padding(
                    top = if (isTop) 28.dp else 0.dp,
                    bottom = if (isTop) 0.dp else 28.dp,
                    start = if (isLeft) 4.dp else 0.dp,
                    end = if (isLeft) 0.dp else 4.dp,
                )
                .width(320.dp)
                .heightIn(max = 520.dp)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            elevation = CardDefaults.cardElevation(8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                // ── Header: title + 4 action icons ─────────────────────────
                DrawerHeader(
                    unread = unread,
                    onClearAll = { NotificationStore.clearAll(); onClear() },
                    onCollapse = onDismiss,
                )
                HorizontalDivider(color = Color(0xFF313244))

                // ── Severity filter chips ────────────────────────────────────
                if (allItems.isNotEmpty()) {
                    FilterChipsRow(filterSeverity) { filterSeverity = if (filterSeverity == it) null else it }
                    HorizontalDivider(color = Color(0xFF313244), thickness = 0.5.dp)
                }

                // ── List ─────────────────────────────────────────────────────
                if (displayItems.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (allItems.isEmpty()) "No New Notifications" else "No matching notifications",
                            color = Color(0xFF6C7086), fontSize = 12.sp,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(displayItems, key = { it.id }) { item ->
                            NotificationRow(
                                item = item,
                                onErrorTap = {
                                    NotificationStore.markRead(item.id)
                                    onOpenProblems()
                                    onDismiss()
                                },
                            )
                            HorizontalDivider(color = Color(0xFF313244), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * P-NOTIF-RESTRUCTURE: Notification panel header.
 * Title on the left ("No New Notifications" / "N New Notifications").
 * Four icons on the right, in VS Code order:
 *   1. Clear All          — clears every notification
 *   2. Do Not Disturb     — opens a small menu (Disable/Enable DND + "anycode" toggle)
 *   3. Reposition         — opens a small menu (Bottom Right / Bottom Left / Top Right)
 *   4. Collapse (chevron) — closes the panel (tapping the bell again also closes it)
 */
@Composable
private fun DrawerHeader(
    unread: Int,
    onClearAll: () -> Unit,
    onCollapse: () -> Unit,
) {
    var showDndMenu by remember { mutableStateOf(false) }
    var showPosMenu by remember { mutableStateOf(false) }
    val dnd = NotificationStore.settings.doNotDisturb
    val appEnabled = NotificationStore.settings.enabled
    val pos = NotificationStore.settings.bellPosition

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (unread > 0) "$unread New Notification${if (unread != 1) "s" else ""}" else "No New Notifications",
            color = Color(0xFFCDD6F4), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 1. Clear All
            Icon(
                Icons.Default.ClearAll,
                contentDescription = "Clear all notifications",
                tint = Color(0xFF9CA0B0),
                modifier = Modifier.size(16.dp).clickable { onClearAll() },
            )

            // 2. Do Not Disturb — opens small menu
            Box {
                Icon(
                    if (dnd) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                    contentDescription = "Do Not Disturb options",
                    tint = if (dnd) Color(0xFFF38BA8) else Color(0xFF9CA0B0),
                    modifier = Modifier.size(16.dp).clickable { showDndMenu = true },
                )
                DropdownMenu(expanded = showDndMenu, onDismissRequest = { showDndMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (dnd) "Disable Do Not Disturb Mode" else "Enable Do Not Disturb Mode",
                                fontSize = 12.sp,
                            )
                        },
                        onClick = {
                            NotificationStore.toggleDoNotDisturb()
                            showDndMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("anycode", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (appEnabled) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = Color(0xFF89B4FA))
                                }
                            }
                        },
                        // P-NOTIF-RESTRUCTURE: master app-notifications toggle. Menu stays open
                        // so the checkmark state is visible immediately after tapping.
                        onClick = { NotificationStore.toggleAppNotifications() },
                    )
                }
            }

            // 3. Reposition — opens small menu with 3 corners
            Box {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Move notification panel",
                    tint = Color(0xFF9CA0B0),
                    modifier = Modifier.size(16.dp).clickable { showPosMenu = true },
                )
                DropdownMenu(expanded = showPosMenu, onDismissRequest = { showPosMenu = false }) {
                    NotificationStore.ALL_POSITIONS.forEach { corner ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cornerLabel(corner), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    if (pos == corner) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = Color(0xFF89B4FA))
                                    }
                                }
                            },
                            onClick = {
                                NotificationStore.setBellPosition(corner)
                                showPosMenu = false
                            },
                        )
                    }
                }
            }

            // 4. Collapse chevron — closes the panel
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Close notifications",
                tint = Color(0xFF9CA0B0),
                modifier = Modifier.size(18.dp).clickable { onCollapse() },
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    activeFilter: NotificationStore.Severity?,
    onFilter: (NotificationStore.Severity) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NotificationStore.Severity.values().forEach { sev ->
            val (_, color) = severityIcon(sev)
            val active = activeFilter == sev
            Box(
                Modifier
                    .background(
                        if (active) color.copy(alpha = 0.25f) else Color(0xFF313244),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onFilter(sev) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    sev.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontSize = 9.sp,
                    color = if (active) color else Color(0xFF9CA0B0),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * P-NOTIF-RESTRUCTURE: A single notification row.
 * - ERROR severity rows jump to the Problems panel on tap (onErrorTap).
 * - All other rows expand in place on tap to show the FULL body text
 *   (previously truncated to 2 lines — Christie's reported bug).
 */
@Composable
private fun NotificationRow(item: NotificationStore.Item, onErrorTap: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val (iconVec, iconColor) = severityIcon(item.severity)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (!item.read) Color(0x0DFFFFFF) else Color.Transparent)
            .clickable {
                NotificationStore.markRead(item.id)
                if (item.severity == NotificationStore.Severity.ERROR) onErrorTap()
                else expanded = !expanded
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread dot
        Box(Modifier.size(6.dp).padding(top = 4.dp)) {
            if (!item.read) {
                Box(Modifier.fillMaxSize().background(Color(0xFF89B4FA), CircleShape))
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(iconVec, null, tint = iconColor, modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFCDD6F4),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Source tag
                Box(
                    Modifier.background(sourceColor(item.source).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(item.source.name.lowercase(), fontSize = 8.sp, color = sourceColor(item.source))
                }
            }
            if (item.body.isNotBlank() && item.body != item.title) {
                Text(
                    item.body,
                    fontSize = 10.sp,
                    color = Color(0xFF9CA0B0),
                    // P-NOTIF-RESTRUCTURE: full text on expand, was hard-capped at 2 lines
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
            Text(relativeTime(item.id), fontSize = 9.sp, color = Color(0xFF6C7086))
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.Close, null,
            tint = Color(0xFF555570),
            modifier = Modifier.size(13.dp).clickable { NotificationStore.dismiss(item.id) },
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun severityIcon(severity: NotificationStore.Severity): Pair<ImageVector, Color> = when (severity) {
    NotificationStore.Severity.ERROR    -> Icons.Default.Error          to Color(0xFFE57373)
    NotificationStore.Severity.WARNING  -> Icons.Default.Warning        to Color(0xFFFFB74D)
    NotificationStore.Severity.SUCCESS  -> Icons.Default.CheckCircle    to Color(0xFFA6E3A1)
    NotificationStore.Severity.PROGRESS -> Icons.Default.HourglassTop   to Color(0xFF89DCEB)
    NotificationStore.Severity.INFO     -> Icons.Default.Info           to Color(0xFF89B4FA)
}

private fun sourceColor(source: NotificationStore.Source): Color = when (source) {
    NotificationStore.Source.LSP        -> Color(0xFF89B4FA)
    NotificationStore.Source.DAP        -> Color(0xFFCBA6F7)
    NotificationStore.Source.BUILD      -> Color(0xFFFAB387)
    NotificationStore.Source.TERMINAL   -> Color(0xFFA6E3A1)
    NotificationStore.Source.GIT        -> Color(0xFFF38BA8)
    NotificationStore.Source.EXTENSIONS -> Color(0xFF89DCEB)
    NotificationStore.Source.WORKSPACE  -> Color(0xFFCDD6F4)
    NotificationStore.Source.AUTH       -> Color(0xFFFFB74D)
    NotificationStore.Source.AI         -> Color(0xFFCBA6F7)
    NotificationStore.Source.SYSTEM     -> Color(0xFF6C7086)
    NotificationStore.Source.BACKUP     -> Color(0xFFA6E3A1)
    NotificationStore.Source.CONNECTOR  -> Color(0xFF89DCEB)
}

private fun relativeTime(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(1)  -> "just now"
        diff < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
    }
}
