package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun androidx.compose.foundation.layout.BoxScope.GotoLineBar(
    goToLineOpen: Boolean,
    goToLineInput: String,
    onGoToLineInputChange: (String) -> Unit,
    text: String,
    fontSize: Int,
    vScrollValue: Int,
    onJumpToLine: (offset: Int, line: Int) -> Unit,
) {
    if (goToLineOpen) {
        val gotoLineMapper = remember(text) { PositionMapper(text) }
        val lineCount2 = gotoLineMapper.lineCount()  // Phase A
        Row(
            modifier = androidx.compose.ui.Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .border(1.dp, Color(0xFF3C3C3C))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .zIndex(21f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Go to line:",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            androidx.compose.foundation.text.BasicTextField(
                value = goToLineInput,
                onValueChange = { v ->
                    if (v.all { it.isDigit() } || v.isEmpty()) onGoToLineInputChange(v)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFFD4D4D4),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onGo = {
                        val target = goToLineInput.toIntOrNull()
                        android.util.Log.d("GotoLine", "IME Go key, input=" + goToLineInput + " target=" + target)
                        if (target == null) return@KeyboardActions
                        val clamped = target.coerceIn(1, lineCount2)
                        val safeOffset = gotoLineMapper.lineStart(clamped - 1).coerceAtMost(text.length)
                        onJumpToLine(safeOffset, clamped)
                    },
                ),
                decorationBox = { inner ->
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (goToLineInput.isEmpty()) Text(
                            "1 – $lineCount2",
                            color = Color(0xFF666666),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        inner()
                    }
                },
                modifier = androidx.compose.ui.Modifier
                    .width(100.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(3.dp)),
            )
            Text(
                "of $lineCount2",
                color = Color(0xFF888888),
                fontSize = 11.sp,
            )
            Spacer(modifier = androidx.compose.ui.Modifier.weight(1f))
            // FIX: Add a Go button so mobile users can trigger jump without IME Go key
            Box(
                modifier = androidx.compose.ui.Modifier
                    .background(Color(0xFF007ACC), RoundedCornerShape(4.dp))
                    .clickable {
                        val target = goToLineInput.toIntOrNull()
                        android.util.Log.d("GotoLine", "Go button clicked, input=" + goToLineInput + " target=" + target)
                        if (target != null && target > 0) {
                            val clamped = target.coerceIn(1, lineCount2)
                            val safeOffset = gotoLineMapper.lineStart(clamped - 1).coerceAtMost(text.length)
                            onJumpToLine(safeOffset, clamped)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Go", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(
                onClick = { onGoToLineInputChange(""); },
                modifier = androidx.compose.ui.Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close, null,
                    tint = Color(0xFF888888),
                    modifier = androidx.compose.ui.Modifier.size(16.dp),
                )
            }
        }
    }
}
