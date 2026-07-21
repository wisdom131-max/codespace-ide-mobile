package com.codespace.ide.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

// ── VS Code-style Notification Bell ────────────────────────────────────────────

/**
 * P35-NOTIF: VS Code-style notification bell with color states + badge count.
 * Bell color: gray (idle), red (errors), amber (warnings), blue (info)
 * Badge: unread count (hidden when 0)
 *
 * @param onClick Called when bell is tapped (opens notification center)
 * @param modifier Layout modifier
 */
@Composable
internal fun NotificationBell(
    iconSize: Int = 20,
    onClick: () -> Unit,
) {
    val unread = remember { derivedStateOf { NotificationStore.unreadCount } }.value
    val bellState = remember { derivedStateOf { NotificationStore.bellState } }.value
    val dnd = remember { derivedStateOf { NotificationStore.settings.doNotDisturb } }.value

    // P35-NOTIF: VS Code bell colors:
    // DND = dimmed gray, error = soft red, warning = amber, info = blue, idle = gray
    val bellColor = when {
        dnd -> Color(0xFF7F849C).copy(alpha = 0.5f)  // dimmed when DND on
        bellState == "error" -> Color(0xFFF38BA8)    // soft red
        bellState == "warning" -> Color(0xFFFAB387)   // amber
        bellState == "info" -> Color(0xFF89B4FA)      // blue
        else -> Color(0xFF7F849C)                      // gray (idle)
    }

    Box(
        Modifier.size((iconSize + 8).dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Notifications, null, tint = bellColor, modifier = Modifier.size(iconSize.dp))
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(if (bellState == "error") Color(0xFFF38BA8) else Color(0xFF89B4FA), CircleShape)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else if (unread > 9) "9+" else unread.toString(),
                    color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── In-app Toast Banner ───────────────────────────────────────────────────────

/**
 * P34-NOTIF: VS Code-style in-app toast banner.
 * Appears at the bottom-right of the screen, auto-dismisses after toastDurationMs.
 * Call from the root scaffold — NOT inside a scroll container.
 */
@Composable
internal fun NotificationToastBanner() {
    // Re-render when activeToast changes by polling (simple approach — no Flow needed)
    var toast by remember { mutableStateOf(NotificationStore.activeToast) }
    // Poll via LaunchedEffect — re-reads every 100ms
    LaunchedEffect(Unit) {
        while (true) {
            toast = NotificationStore.activeToast
            kotlinx.coroutines.delay(100)
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .zIndex(100f),
    ) {
        val t = toast ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D3F), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, color) = severityIcon(t.severity)
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (t.title.isNotBlank() && t.title != t.body) {
                    Text(t.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFCDD6F4), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(t.body, fontSize = 11.sp, color = Color(0xFF9CA0B0), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Close, null,
                tint = Color(0xFF555570),
                modifier = Modifier.size(13.dp).clickable { NotificationStore.dismissToast() },
            )
        }
    }
}

// ── Notification Drawer ───────────────────────────────────────────────────────

/**
 * P34-NOTIF: Full VS Code-style notification drawer.
 * Reads from NotificationStore (single source of truth).
 * Supports: filter by severity/source, mark-all-read, dismiss individual, clear all.
 */
@Composable
internal fun NotificationDrawerOverlay(
    @Suppress("UNUSED_PARAMETER") notifList: List<NotifItem> = emptyList(), // legacy param — ignored
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val allItems by remember { derivedStateOf { NotificationStore.items.toList() } }
    val unread = NotificationStore.unreadCount

    // Filter state
    var filterSeverity by remember { mutableStateOf<NotificationStore.Severity?>(null) }
    var filterSource   by remember { mutableStateOf<NotificationStore.Source?>(null) }

    val displayItems = remember(allItems, filterSeverity, filterSource) {
        allItems.filter { item ->
            (filterSeverity == null || item.severity == filterSeverity) &&
            (filterSource == null   || item.source   == filterSource)
        }
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
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 4.dp)
                .width(320.dp)
                .heightIn(max = 520.dp)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            elevation = CardDefaults.cardElevation(8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                // ── Header ──────────────────────────────────────────────────
                DrawerHeader(
                    unread = unread,
                    hasItems = allItems.isNotEmpty(),
                    doNotDisturb = NotificationStore.settings.doNotDisturb,
                    bellPosition = NotificationStore.settings.bellPosition,
                    onMarkAllRead = { NotificationStore.markAllRead() },
                    onClearAll = { NotificationStore.clearAll(); onClear() },
                    onToggleDnd = { NotificationStore.toggleDoNotDisturb() },
                    onToggleBellPosition = {
                        val newPos = if (NotificationStore.settings.bellPosition == "top") "bottom" else "top"
                        NotificationStore.setBellPosition(newPos)
                    },
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
                            if (allItems.isEmpty()) "No notifications" else "No matching notifications",
                            color = Color(0xFF6C7086), fontSize = 12.sp,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(displayItems, key = { it.id }) { item ->
                            NotificationRow(item)
                            HorizontalDivider(color = Color(0xFF313244), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(
    unread: Int,
    hasItems: Boolean,
    doNotDisturb: Boolean,
    onMarkAllRead: () -> Unit,
    onClearAll: () -> Unit,
    onToggleDnd: () -> Unit,
    onToggleBellPosition: () -> Unit,
    bellPosition: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", color = Color(0xFFCDD6F4), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (unread > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.background(Color(0xFFE57373), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(if (unread > 99) "99+" else unread.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // P35-NOTIF: DND toggle (bell with slash icon)
            Icon(
                if (doNotDisturb) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                contentDescription = if (doNotDisturb) "Turn off Do Not Disturb" else "Turn on Do Not Disturb",
                tint = if (doNotDisturb) Color(0xFFF38BA8) else Color(0xFF89B4FA),
                modifier = Modifier.size(15.dp).clickable { onToggleDnd() },
            )
            // P35-NOTIF: Bell position toggle (top/bottom)
            Icon(
                if (bellPosition == "bottom") Icons.Default.VerticalAlignTop else Icons.Default.VerticalAlignBottom,
                contentDescription = "Move bell to ${if (bellPosition == "bottom") "top bar" else "status bar"}",
                tint = Color(0xFF7F849C),
                modifier = Modifier.size(15.dp).clickable { onToggleBellPosition() },
            )
            if (hasItems) {
                Text("Clear", color = Color(0xFF89B4FA), fontSize = 11.sp,
                    modifier = Modifier.clickable { onClearAll() })
            }
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

@Composable
private fun NotificationRow(item: NotificationStore.Item) {
    val (iconVec, iconColor) = severityIcon(item.severity)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (!item.read) Color(0x0DFFFFFF) else Color.Transparent)
            .clickable { NotificationStore.markRead(item.id) }
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
                Text(item.body, fontSize = 10.sp, color = Color(0xFF9CA0B0), maxLines = 2, overflow = TextOverflow.Ellipsis)
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

// ── Bell icon composable — usable in both top-bar and status-bar ──────────────

/**
 * P34-NOTIF: Reusable bell icon that works in top-bar or status-bar.
 * Shows unread count badge. Bell color softened from full red to amber/orange for errors.
 */
@Composable
internal fun NotificationBell(
    iconSize: Int = 20,
    onClick: () -> Unit,
) {
    val unread = remember { derivedStateOf { NotificationStore.unreadCount } }.value
    val hasError = remember { derivedStateOf {
        NotificationStore.items.any { it.severity == NotificationStore.Severity.ERROR && !it.read }
    }}.value
    val hasWarning = remember { derivedStateOf {
        NotificationStore.items.any { it.severity == NotificationStore.Severity.WARNING && !it.read }
    }}.value

    // Color: error → soft red (E57373 not F44336), warning → amber, unread → blue, idle → grey
    val bellColor = when {
        hasError   -> Color(0xFFE57373) // softer red
        hasWarning -> Color(0xFFFFB74D) // amber
        unread > 0 -> Color(0xFF89B4FA) // blue
        else       -> Color(0xFF6C7086) // idle grey
    }

    Box(
        Modifier.size((iconSize + 8).dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Notifications, null, tint = bellColor, modifier = Modifier.size(iconSize.dp))
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .background(if (hasError) Color(0xFFE57373) else Color(0xFF89B4FA), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else if (unread > 9) "9+" else unread.toString(),
                    color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
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
