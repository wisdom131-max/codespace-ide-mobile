package com.codespace.ide.editor

import com.codespace.ide.ui.EditorColors
import androidx.compose.material3.Text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
internal fun SignatureHelpPopup(
    activeSignature: SignatureInfo,
    positionMapper: PositionMapper,
    value: TextFieldValue,
    lineHeightDp: Dp,
    vScrollDp: Float,
    colors: EditorColors,
    clipboardManager: ClipboardManager,
) {
    val cursorLineIdx = positionMapper.offsetToLine(value.selection.end)
    val popupLineIdx = (cursorLineIdx - 1).coerceAtLeast(0)
    val popupTopDp = ((popupLineIdx * lineHeightDp.value) - vScrollDp).coerceAtLeast(0f)
    val annotated = remember(activeSignature) {
        buildAnnotatedString {
            append(activeSignature.name)
            append("(")
            activeSignature.params.forEachIndexed { idx, param ->
                if (idx > 0) append(", ")
                if (idx == activeSignature.activeParam) {
                    withStyle(SpanStyle(color = Color(0xFF4EC9B0), fontWeight = FontWeight.Bold)) {
                        append(param)
                    }
                } else {
                    append(param)
                }
            }
            append(")")
            if (activeSignature.returnType != null) {
                withStyle(SpanStyle(color = Color(0xFF808080))) { append(": ${activeSignature.returnType}") }
            }
        }
    }
    var sigExpanded by remember { mutableStateOf(false) }
    val sigScrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .padding(start = GUTTER_WIDTH.dp, top = popupTopDp.dp)
            .widthIn(max = 320.dp)
            .zIndex(10f)
            .background(colors.background, RoundedCornerShape(6.dp))
            .border(1.dp, colors.function.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(modifier = Modifier.size(20.dp).clickable { sigExpanded = !sigExpanded },
                    contentAlignment = Alignment.Center) {
                    Text(text = if (sigExpanded) "\u25BE" else "\u25B8", color = Color(0xFF888888), fontSize = 11.sp)
                }
                Spacer(Modifier.width(2.dp))
                Box(modifier = Modifier.size(20.dp).clickable {
                        clipboardManager.setText(AnnotatedString(activeSignature.name))
                    }, contentAlignment = Alignment.Center) {
                    Text(text = "\u23C9", color = Color(0xFF888888), fontSize = 11.sp)
                }
            }
            Box(modifier = Modifier.padding(horizontal = 4.dp)
                .then(if (sigExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(sigScrollState) else Modifier)
            ) {
                Text(text = annotated, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD4D4D4))
            }
        }
    }
}
