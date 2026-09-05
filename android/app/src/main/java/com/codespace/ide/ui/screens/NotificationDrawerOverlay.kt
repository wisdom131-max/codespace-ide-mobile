package com.codespace.ide.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.data.NotificationStore
import androidx.compose.ui.platform.LocalContext
import android.os.VibratorManager
import android.os.VibrationEffect
import androidx.compose.ui.res.painterResource
import com.codespace.ide.R
import java.util.concurrent.TimeUnit

// Legacy compat — kept so existing callers don't break
internal data class NotifItem(val id: Long, val msg: String, val type: String)

/**
 * P-NOTIF-UI-FIX: Theme-aware colors for the notification panel/toast.
 * Passed in from ProjectShellScreen's per-theme IdeColors so this panel matches
 * whichever editor theme is active (Dark Default, Dracula, Catppuccin, AMOLED, ...)
 * instead of a hardcoded purple/indigo palette.
 *
 * Defaults below match "Dark (Default)" purely as a compile-time fallback — real
 * call sites always pass the live theme's colors.
 */
internal data class NotifColors(
    val panelBg: Color = Color(0xFF252526),
    val border: Color = Color(0xFF454545),
    val text: Color = Color(0xFFCCCCCC),
    val textSecondary: Color = Color(0xFF969696),
    val chipBg: Color = Color(0xFF969696).copy(alpha = 0.12f),
    val accent: Color = Color(0xFF007ACC),
)

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

@Composable
internal fun NotificationBell(
    iconSize: Int = 22,
    onClick: () -> Unit,
) {
    val unread = remember { derivedStateOf { NotificationStore.unreadCount } }.value
    val dnd = remember { derivedStateOf { NotificationStore.settings.doNotDisturb } }.value

    val bellColor = when {
        dnd -> Color(0xFF7F849C).copy(alpha = 0.45f)
        else -> Color(0xFFFFFFFF).copy(alpha = 0.55f)
    }

    Box(
        Modifier.size((iconSize + 10).dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (dnd) R.drawable.ic_notification_bell_slash
                else R.drawable.ic_notification_bell
            ),
            null,
            tint = bellColor,
            modifier = Modifier.size(iconSize.dp),
        )
        // BUG-6 FIX: the indicator dot is ALWAYS a single fixed color — white,
        // matching the bell icon's own color. Severity filtering stays inside the
        // panel; the dot never changes color by severity again.
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(bellColor, CircleShape),
            )
        }
    }
}

// ── In-app Toast Banner (VS Code-style floating card, 3-corner anchored) ───────

@Composable
internal fun NotificationToastBanner(colors: NotifColors = NotifColors()) {
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
            Card(
                Modifier
                    .width(320.dp)
                    .clickable(enabled = false) {},
                colors = CardDefaults.cardColors(containerColor = colors.panelBg),
                elevation = CardDefaults.cardElevation(6.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, colors.border),
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
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            t.body,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (t.severity == NotificationStore.Severity.PROGRESS && t.progress != null) {
                            Spacer(Modifier.height(4.dp))
                            if (t.progress.indeterminate) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = colors.accent,
                                )
                            } else {
                                val progress = if (t.progress.max > 0) {
                                    t.progress.current.toFloat() / t.progress.max.toFloat()
                                } else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = colors.accent,
                                )
                            }
                            t.progress.statusMessage?.let {
                                Text(it, fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                            }
                        }
                        if (t.actions.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                t.actions.forEach { action ->
                                    Text(
                                        action.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (action.destructive) Color(0xFFF38BA8) else colors.accent,
                                        modifier = Modifier
                                            .background(
                                                if (action.destructive) Color(0xFFF38BA8).copy(alpha = 0.15f)
                                                else colors.accent.copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp),
                                            )
                                            .clickable { NotificationStore.executeAction(t.id, action.id) }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Notification Drawer ───────────────────────────────────────────────────────

@Composable
internal fun NotificationDrawerOverlay(
    @Suppress("UNUSED_PARAMETER") notifList: List<NotifItem> = emptyList(),
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShowCommands: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
    colors: NotifColors = NotifColors(),
) {
    val allItems by remember { derivedStateOf { NotificationStore.items.toList() } }
    val unread = NotificationStore.unreadCount
    val pos = NotificationStore.settings.bellPosition
    val isTop = pos == NotificationStore.POS_TOP_RIGHT
    val isLeft = pos == NotificationStore.POS_BOTTOM_LEFT

    var filterSeverity by remember { mutableStateOf<NotificationStore.Severity?>(null) }
    var filterSource by remember { mutableStateOf<NotificationStore.Source?>(null) }

    val displayItems = remember(allItems, filterSeverity, filterSource) {
        allItems.filter { item ->
            (filterSeverity == null || item.severity == filterSeverity) &&
            (filterSource == null || item.source == filterSource)
        }
    }

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
                .then(if (allItems.isNotEmpty()) Modifier.heightIn(max = 460.dp) else Modifier)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = colors.panelBg),
            elevation = CardDefaults.cardElevation(8.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column {
                DrawerHeader(
                    unread = unread,
                    colors = colors,
                    onClearAll = { NotificationStore.permanentlyDeleteAll(); onClear() },
                    onCollapse = onDismiss,
                )

                // BUG-2 FIX: VS Code's center hides the list entirely when empty —
                // a zero-notification panel is just the compact header bar above.
                // (The header itself already reads "No New Notifications".)
                if (allItems.isNotEmpty()) {
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                    NotifFilterDropdowns(
                        filterSeverity = filterSeverity,
                        filterSource = filterSource,
                        colors = colors,
                        onFilterSeverity = { filterSeverity = it },
                        onFilterSource = { filterSource = it },
                    )
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)

                    if (displayItems.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No matching notifications",
                                color = colors.textSecondary, fontSize = 11.sp,
                            )
                        }
                    } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(displayItems, key = { it.id }) { item ->
                            NotificationRow(
                                item = item,
                                colors = colors,
                                onErrorTap = {
                                    NotificationStore.markRead(item.id)
                                    onOpenProblems()
                                    onDismiss()
                                },
                            )
                            HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                        }
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
    colors: NotifColors,
    onClearAll: () -> Unit,
    onCollapse: () -> Unit,
) {
    var showDndMenu by remember { mutableStateOf(false) }
    var showPosMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
            color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ClearAll,
                contentDescription = "Permanently delete all notifications",
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp).clickable { onClearAll() },
            )
            Box {
                Icon(
                    if (dnd) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                    contentDescription = "Do Not Disturb options",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp).clickable { showDndMenu = true },
                )
                DropdownMenu(expanded = showDndMenu, onDismissRequest = { showDndMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (dnd) "Disable Do Not Disturb Mode" else "Enable Do Not Disturb Mode", fontSize = 12.sp) },
                        onClick = {
                            NotificationStore.toggleDoNotDisturb()
                            try {
                                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                                    ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                            } catch (e: Exception) { }
                            showDndMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("anycode", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (appEnabled) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = colors.accent)
                                }
                            }
                        },
                        onClick = { NotificationStore.toggleAppNotifications() },
                    )
                }
            }
            Box {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Move notification panel",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp).clickable { showPosMenu = true },
                )
                DropdownMenu(expanded = showPosMenu, onDismissRequest = { showPosMenu = false }) {
                    NotificationStore.ALL_POSITIONS.forEach { corner ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cornerLabel(corner), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    if (pos == corner) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = colors.accent)
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
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Close notifications",
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp).clickable { onCollapse() },
            )
        }
    }
}

@Composable
private fun NotifFilterDropdowns(
    filterSeverity: NotificationStore.Severity?,
    filterSource: NotificationStore.Source?,
    colors: NotifColors,
    onFilterSeverity: (NotificationStore.Severity?) -> Unit,
    onFilterSource: (NotificationStore.Source?) -> Unit,
) {
    // FIX-1: VS Code consolidation — one dropdown per filter dimension instead of a
    // chip row spanning the panel (VS Code renders notification actions via
    // DropdownMenuActionViewItem, notificationsViewer.ts). Filtering semantics
    // are unchanged: null = show all; re-selecting the active item clears it.
    val sources = remember {
        listOf(
            NotificationStore.Source.LSP, NotificationStore.Source.GIT,
            NotificationStore.Source.BUILD, NotificationStore.Source.TERMINAL,
            NotificationStore.Source.DAP, NotificationStore.Source.AI,
            NotificationStore.Source.SYSTEM,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Severity dropdown
        var sevMenu by remember { mutableStateOf(false) }
        Box {
            FilterMenuButton(
                label = if (filterSeverity == null) "All Severities"
                        else filterSeverity.name.lowercase().replaceFirstChar { it.uppercase() },
                active = filterSeverity != null,
                colors = colors,
                onClick = { sevMenu = true },
            )
            DropdownMenu(expanded = sevMenu, onDismissRequest = { sevMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All Severities", fontSize = 12.sp) },
                    onClick = { onFilterSeverity(null); sevMenu = false },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NotificationStore.Severity.values().forEach { sev ->
                    val (_, dotColor) = severityIcon(sev)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sev.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (filterSeverity == sev) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = colors.accent)
                                }
                            }
                        },
                        onClick = {
                            onFilterSeverity(if (filterSeverity == sev) null else sev)
                            sevMenu = false
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
        // Source dropdown
        var srcMenu by remember { mutableStateOf(false) }
        Box {
            FilterMenuButton(
                label = if (filterSource == null) "All Sources" else filterSource.name.lowercase(),
                active = filterSource != null,
                colors = colors,
                onClick = { srcMenu = true },
            )
            DropdownMenu(expanded = srcMenu, onDismissRequest = { srcMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All Sources", fontSize = 12.sp) },
                    onClick = { onFilterSource(null); srcMenu = false },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                sources.forEach { src ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Box(Modifier.size(6.dp).background(sourceColor(src), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(src.name.lowercase(), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (filterSource == src) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = colors.accent)
                                }
                            }
                        },
                        onClick = {
                            onFilterSource(if (filterSource == src) null else src)
                            srcMenu = false
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterMenuButton(
    label: String,
    active: Boolean,
    colors: NotifColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .background(
                if (active) colors.accent.copy(alpha = 0.16f) else colors.chipBg,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (active) colors.accent.copy(alpha = 0.7f) else colors.border,
                RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.FilterList, null, tint = if (active) colors.accent else colors.textSecondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (active) colors.accent else colors.textSecondary,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Default.KeyboardArrowDown, null, tint = if (active) colors.accent else colors.textSecondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun NotificationRow(item: NotificationStore.Item, colors: NotifColors, onErrorTap: () -> Unit) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val (iconVec, iconColor) = severityIcon(item.severity)
    // BUG-3 FIX (VS Code notificationsViewer.ts): a collapsed notification is ONE
    // compact single-line row; details render only when expanded.
    // SIZING NOTE (proportional, not pixel-copied): VS Code's compact row is 34px on
    // a ~390px vscode.dev mobile viewport (~8.7% of width). This device's dp width
    // (~390dp) matches that viewport, so 34dp is the PROPORTIONAL equivalent in OUR
    // sizing system — the reference screenshots already reflect narrow-width VS Code,
    // and these values track our own 320dp panel / 11sp row-font scale.
    val singleLine = if (item.body.isNotBlank() && item.body != item.title)
        "${item.title} - ${item.body}" else item.title
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .background(if (!item.read) colors.accent.copy(alpha = 0.06f) else Color.Transparent)
            .clickable {
                NotificationStore.markRead(item.id)
                if (item.severity == NotificationStore.Severity.ERROR) onErrorTap()
                else expanded = !expanded
            }
            .semantics {
                var desc = "${item.severity.name.lowercase()}: ${item.title}. ${item.body}"
                if (item.dedupCount > 1) desc = "$desc ${item.dedupCount} occurrences."
                if (item.actions.isNotEmpty()) desc = "$desc Actions: ${item.actions.joinToString { it.label }}."
                contentDescription = desc
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp)) {
            if (!item.read) {
                Box(Modifier.fillMaxSize().background(colors.accent, CircleShape))
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(iconVec, null, tint = iconColor, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (!expanded) {
                Text(
                    singleLine,
                    fontSize = 11.sp,
                    fontWeight = if (!item.read) FontWeight.Medium else FontWeight.Normal,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.dedupCount > 1) {
                    Text("(${item.dedupCount})", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFAB387))
                }
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
                    color = colors.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
            if (expanded && item.errorDetails != null) {
                Spacer(Modifier.height(4.dp))
                item.errorDetails.technicalDetails?.let { tech ->
                    Surface(
                        color = colors.panelBg,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tech, fontSize = 9.sp, color = colors.textSecondary, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip, modifier = Modifier.padding(6.dp))
                    }
                }
            }
            if (item.severity == NotificationStore.Severity.PROGRESS && item.progress != null) {
                Spacer(Modifier.height(4.dp))
                if (item.progress.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.accent)
                } else {
                    val progress = if (item.progress.max > 0) item.progress.current.toFloat() / item.progress.max.toFloat() else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.accent)
                }
                item.progress.statusMessage?.let { Text(it, fontSize = 9.sp, color = colors.textSecondary, maxLines = 1) }
            }
            if (item.actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.actions.forEach { action ->
                        Text(
                            action.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (action.destructive) Color(0xFFF38BA8) else colors.accent,
                            modifier = Modifier
                                .background(
                                    if (action.destructive) Color(0xFFF38BA8).copy(alpha = 0.15f) else colors.accent.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { NotificationStore.executeAction(item.id, action.id) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(relativeTime(item.timestamp), fontSize = 9.sp, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.Close, null, tint = colors.textSecondary, modifier = Modifier.size(13.dp).clickable { NotificationStore.dismiss(item.id) })
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
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
    }
}
