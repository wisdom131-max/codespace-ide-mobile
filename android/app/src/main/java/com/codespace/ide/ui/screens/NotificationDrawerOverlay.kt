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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class NotifItem(val id: Long, val msg: String, val type: String)

@Composable
internal fun NotificationDrawerOverlay(
    notifList: List<NotifItem>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
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
                .width(280.dp)
                .heightIn(max = 380.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            elevation = CardDefaults.cardElevation(8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.clickable(enabled = false) {}) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Notifications", color = Color(0xFFCDD6F4), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (notifList.isNotEmpty()) {
                        Text(
                            "Clear all",
                            color = Color(0xFF89B4FA),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onClear() }
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF313244))
                if (notifList.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No notifications", color = Color(0xFF6C7086), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(notifList) { notif ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val (iconVec, iconColor) = when (notif.type) {
                                    "error"   -> Icons.Default.Error to Color(0xFFF38BA8)
                                    "warning" -> Icons.Default.Warning to Color(0xFFFAB387)
                                    "success" -> Icons.Default.CheckCircle to Color(0xFFA6E3A1)
                                    else      -> Icons.Default.Info to Color(0xFF89B4FA)
                                }
                                Icon(iconVec, null, tint = iconColor, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    notif.msg,
                                    fontSize = 12.sp,
                                    color = Color(0xFFCDD6F4),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
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
