package com.codespace.ide.chat.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatTokens

/**
 * M4 — the empty conversation surface, in the reference's composition:
 *
 *   mark  ->  welcome heading  ->  supporting guidance, vertically centred above the composer.
 *
 * Branding: the mark is this product's own launcher/splash mark (`ic_splash_logo`), NOT the large
 * VS Code editor watermark, which the design explicitly rules out for the chat mark.
 *
 * Text: the reference's heading names a feature this app does not have ("Build with Agent" is
 * Copilot's wording), so the same slot carries VN Code's own sentence instead of borrowed
 * branding. No link or button is drawn here, because nothing in this state has a real action to
 * offer: an unexplained control would be exactly the kind of placeholder this rebuild removes.
 */
@Composable
internal fun ChatEmptyWelcome(colors: ChatColors) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ChatTokens.PAD_HORIZONTAL.dp * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = com.codespace.ide.R.drawable.ic_splash_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Start a conversation",
            color = colors.text,
            fontSize = ChatTokens.FONT_BODY.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask about this project, or describe what you want to build. " +
                "AI responses may be inaccurate.",
            color = colors.textSecondary,
            fontSize = ChatTokens.FONT_SMALL.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Chat is scoped to this session. Switch sessions from the Sessions control.",
            color = colors.textSecondary,
            fontSize = ChatTokens.FONT_MICRO.sp,
            textAlign = TextAlign.Center,
        )
    }
}
