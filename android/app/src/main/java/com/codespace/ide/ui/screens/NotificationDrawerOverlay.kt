package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.NotificationStore
import java.util.concurrent.TimeUnit

// Legacy data class kept for callers that haven't been updated yet
internal data class NotifItem(val id: Long, val msg: String, val type: String)

@Composable
internal fun NotificationDrawerOverlay(
    // Legacy param ignored — real data comes from the global NotificationStore
    _notifList: List<NotifItem> = emptyList(),
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    // Observe the global store directly
    val storeItems by remember { derivedStateOf { NotificationStore.items.toList() } }

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
                .width(300.dp)
                .heightIn(max = 450.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            elevation = CardDefaults.cardElevation(8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.clickable(enabled = false) {}) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Notifications",
                            color = Color(0xFFCDD6F4),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        val unread = NotificationStore.unreadCount
                        if (unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .background(Color(0xFFf38ba8), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(unread.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (storeItems.isNotEmpty()) {
                        Text(
                            "Clear all",
                            color = Color(0xFF89B4FA),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                NotificationStore.clearAll()
                                onClear()
                            },
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF313244))

                if (storeItems.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No notifications", color = Color(0xFF6C7086), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(storeItems, key = { it.id }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (!item.read) Color(0x11FFFFFF) else Color.Transparent)
                                    .clickable { NotificationStore.dismiss(item.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val (iconVec, iconColor) = notifIcon(item.type)
                                Icon(
                                    iconVec, null,
                                    tint = iconColor,
                                    modifier = Modifier.size(15.dp).padding(top = 1.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFCDD6F4),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.body,
                                        fontSize = 11.sp,
                                        color = Color(0xFF9ca0b0),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        relativeTime(item.id),
                                        fontSize = 10.sp,
                                        color = Color(0xFF6C7086),
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close, null,
                                    tint = Color(0xFF555570),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { NotificationStore.dismiss(item.id) },
                                )
                            }
                            HorizontalDivider(color = Color(0xFF313244), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun notifIcon(type: NotificationStore.Type): Pair<ImageVector, Color> = when (type) {
    NotificationStore.Type.TERMINAL_ERROR  -> Icons.Default.Error       to Color(0xFFF38BA8)
    NotificationStore.Type.BUILD_STATUS    -> Icons.Default.Build        to Color(0xFFFAB387)
    NotificationStore.Type.BACKUP          -> Icons.Default.Save         to Color(0xFFA6E3A1)
    NotificationStore.Type.CONNECTOR       -> Icons.Default.Link         to Color(0xFF89DCEB)
    NotificationStore.Type.UBUNTU_STATUS   -> Icons.Default.Terminal     to Color(0xFF89B4FA)
    NotificationStore.Type.INFO            -> Icons.Default.Info         to Color(0xFF89B4FA)
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
