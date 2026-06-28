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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.ui.theme.IdeColors

private data class ChatMsg(val role: String, val text: String)

@Composable
internal fun CopilotChatPanelOverlay(
    colors: IdeColors,
    onClose: () -> Unit,
) {
    var chatMessages  by remember { mutableStateOf(listOf<ChatMsg>()) }
    var chatInput     by remember { mutableStateOf("") }
    var chatLoading   by remember { mutableStateOf(false) }
    val ollamaModels  = remember { listOf("llama3", "mistral", "codellama") }
    var selectedModel by remember { mutableStateOf("llama3") }
    var showModelMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xFF1E1E2E), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copilot Chat", color = Color(0xFFCDD6F4), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Text(
                            selectedModel,
                            color = Color(0xFF89B4FA),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { showModelMenu = true },
                        )
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            ollamaModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m, fontSize = 12.sp) },
                                    onClick = { selectedModel = m; showModelMenu = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close, null,
                        tint = Color(0xFF6C7086),
                        modifier = Modifier.size(16.dp).clickable { onClose() },
                    )
                }
            }
            Divider(color = Color(0xFF313244))
            // Messages
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                reverseLayout = true,
            ) {
                items(chatMessages.reversed()) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Box(
                            Modifier
                                .background(
                                    if (isUser) Color(0xFF7C3AED) else Color(0xFF313244),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp)
                                .widthIn(max = 260.dp)
                        ) {
                            Text(msg.text, color = Color(0xFFCDD6F4), fontSize = 12.sp)
                        }
                    }
                }
            }
            Divider(color = Color(0xFF313244))
            // Input
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color(0xFF313244), RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    placeholder = { Text("Ask Copilot...", fontSize = 12.sp, color = Color(0xFF6C7086)) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color(0xFFCDD6F4),
                        unfocusedTextColor = Color(0xFFCDD6F4),
                    ),
                    maxLines = 3,
                )
                if (chatLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        color = Color(0xFF7C3AED),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = {
                        if (chatInput.isNotBlank()) {
                            val msg = chatInput.trim()
                            chatMessages = chatMessages + ChatMsg("user", msg)
                            chatInput = ""
                            chatLoading = true
                            chatMessages = chatMessages + ChatMsg("assistant", "Processing: $msg")
                            chatLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Send, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
