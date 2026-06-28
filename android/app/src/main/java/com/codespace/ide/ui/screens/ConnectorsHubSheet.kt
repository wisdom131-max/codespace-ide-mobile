package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.ui.theme.IdeColors

@Composable
internal fun ConnectorsHubSheet(
    colors: IdeColors,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable { onDismiss() }) {
Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable { showConnectorsSheet = false }) {
    Card(
        Modifier.align(Alignment.BottomStart)
            .padding(bottom = 0.dp)
            .fillMaxWidth()
            .clickable(onClick = {}), // eat clicks so card doesn't dismiss
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MenuBg),
        elevation = CardDefaults.cardElevation(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Handle bar
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                .background(Color(0xFF555555), RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(12.dp))
            Text("Connectors", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MenuText)
            Spacer(Modifier.height(4.dp))
            Text("Sign in and manage services", fontSize = 12.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(16.dp))

            // GitHub
            ConnectorRow(
                icon = Icons.Default.Code,
                name = "GitHub",
                subtitle = "Clone, push, pull, PRs",
                color = Color(0xFF6E40C9),
                menuText = MenuText,
                onClick = {
                    showConnectorsSheet = false
                    showNotification("GitHub — use Source Control panel (branch icon)", "info")
                }
            )
            Spacer(Modifier.height(8.dp))
            // SSH
            ConnectorRow(
                icon = Icons.Default.Computer,
                name = "SSH",
                subtitle = "Remote server access",
                color = Color(0xFF0097A7),
                menuText = MenuText,
                onClick = {
                    showConnectorsSheet = false
                    activePanel = SidePanel.EXTENSIONS
                }
            )
            Spacer(Modifier.height(8.dp))
            // AI Keys
            ConnectorRow(
                icon = Icons.Default.SmartToy,
                name = "AI Providers",
                subtitle = "OpenAI, Anthropic, Gemini keys",
                color = Color(0xFF7B1FA2),
                menuText = MenuText,
                onClick = {
                    showConnectorsSheet = false
                    showNotification("Set AI keys in Settings → AI Config", "info")
                }
            )
            Spacer(Modifier.height(8.dp))
            // Services
            ConnectorRow(
                icon = Icons.Default.Cloud,
                name = "Services",
                subtitle = "Vercel, Netlify, Firebase, Docker",
                color = Color(0xFF1565C0),
                menuText = MenuText,
                onClick = {
                    showConnectorsSheet = false
                    showNotification("Service connectors coming soon", "info")
                }
            )
            Spacer(Modifier.height(16.dp))
            // Manage Accounts
            Row(Modifier.fillMaxWidth()
                .background(Color(0xFF007ACC), RoundedCornerShape(8.dp))
                .clickable { showConnectorsSheet = false; showNotification("Manage Accounts → Settings", "info") }
                .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AccountBox, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Manage Accounts", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
}
    }
}
