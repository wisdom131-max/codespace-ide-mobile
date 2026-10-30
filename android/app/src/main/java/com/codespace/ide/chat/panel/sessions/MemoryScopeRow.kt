package com.codespace.ide.chat.panel.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.MemoryScope
import com.codespace.ide.chat.panel.ChatSettingsPrefs
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * M5 — the memory scope control, where the sessions live.
 *
 * It is a labelled row rather than an icon because it changes what the agent can REMEMBER:
 * the current value is always spelled out, and tapping cycles to the other scope and persists it
 * immediately. Sessions are the natural home: the setting is about how far a session can see.
 */
@Composable
internal fun MemoryScopeRow(colors: ChatColors) {
    var scope by remember { mutableStateOf(MemoryScope.parse(ChatSettingsPrefs.memoryScopeId())) }
    Column(
        Modifier.fillMaxWidth()
            .padding(top = ChatTokens.GAP_TIGHT.dp)
            .clip(RoundedCornerShape(ChatTokens.RADIUS_PANEL.dp))
            .background(colors.surfaceAlt)
            .clickable {
                val next = scope.next()
                scope = next
                ChatSettingsPrefs.setMemoryScopeId(next.id)
            }
            .padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp, vertical = ChatTokens.PAD_VERTICAL.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Memory scope",
                color = colors.text,
                fontSize = ChatTokens.FONT_SMALL.sp,
                modifier = Modifier.weight(1f),
            )
            Text("Change", color = colors.accent, fontSize = ChatTokens.FONT_MICRO.sp)
        }
        Text(
            scope.label + " — " + when (scope) {
                MemoryScope.CURRENT_SESSION -> "the agent can only read memories saved in this session."
                MemoryScope.ALL_SESSIONS -> "the agent can read memories saved in any session."
            },
            color = colors.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
        )
    }
}
