package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ask / Edit / Fix on a selection (2026-10-31, owner request).
 *
 * The bar floats near the selected line: below it when there is room, above it when the line sits
 * low in the editor, and always clamped inside the editor. "Edit" turns the bar into a one-line
 * field, so an instruction is typed without leaving the code you are looking at.
 *
 * Which of the three appears, and what each one asks for, lives in [SelectionActionSpec]. The
 * caller supplies [buildPrompt] so the prompt is built from the selection as it was at composition,
 * never from a selection that has since moved.
 */
/**
 * Everything the toolbar needs, captured once at the selection it belongs to. Keeping it in one
 * object is what lets the CodeEditor body mount the toolbar in four lines: that composable is at
 * its JVM method ceiling and cannot absorb a parameter list.
 */
internal class SelectionHost(
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val filePath: String?,
    val languageName: String,
    val fontSize: Float,
    val vScrollValue: Int,
    val errors: List<LintError>,
    val mapper: PositionMapper,
    val onPrompt: ((String) -> Unit)?,
) {
    val chars: Int get() = selEnd - selStart

    fun diagnostic(): LintError? = selectionDiagnostic(errors, mapper, selStart)

    fun prompt(action: SelectionAction, instruction: String): String = selectionPrompt(
        action = action,
        instruction = instruction,
        text = text,
        selectionStart = selStart,
        selectionEnd = selEnd,
        filePath = filePath,
        languageName = languageName,
        diagnostic = diagnostic(),
        positionMapper = mapper,
    )

    fun send(action: SelectionAction, instruction: String) {
        onPrompt?.invoke(prompt(action, instruction))
    }
}

@Composable
internal fun SelectionActionToolbar(
    host: SelectionHost,
    accent: Color = Color(0xFF4EC9B0),
    surface: Color = Color(0xFF1E1E1E),
    textColor: Color = Color(0xFFD4D4D4),
) {
    val fontSize = host.fontSize
    val vScrollValue = host.vScrollValue
    val entries = SelectionActionSpec.entries(host.diagnostic() != null, host.chars)
    if (entries.isEmpty()) return
    var editing by remember { mutableStateOf(false) }
    var instruction by remember { mutableStateOf("") }
    val lineHeightDp = fontSize * EditorMetrics.LINE_HEIGHT_MULTIPLIER

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val y = SelectionActionSpec.anchorY(
            selectionTopDp = host.mapper.offsetToLine(host.selStart) * lineHeightDp - vScrollValue,
            lineHeightDp = lineHeightDp,
            toolbarHeightDp = SelectionActionSpec.TOOLBAR_HEIGHT_DP,
            viewportHeightDp = maxHeight.value,
        )
        Box(
            Modifier
                .offset(x = 8.dp, y = y.dp)
                .widthIn(max = maxWidth - 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(surface)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (!editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    entries.forEach { entry ->
                        Text(
                            entry.label,
                            color = if (entry.enabled) textColor else Color(0xFF666666),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable(enabled = entry.enabled) {
                                    if (entry.action == SelectionAction.EDIT) {
                                        editing = true
                                    } else {
                                        host.send(entry.action, "")
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        singleLine = true,
                        textStyle = TextStyle(color = textColor, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (instruction.isEmpty()) {
                                Text(
                                    SelectionActionSpec.placeholder(),
                                    color = Color(0xFF858585),
                                    fontSize = 13.sp,
                                )
                            }
                            inner()
                        },
                    )
                    Text(
                        "Send",
                        color = accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                host.send(SelectionAction.EDIT, instruction)
                                instruction = ""
                                editing = false
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        "\u00D7",
                        color = Color(0xFF858585),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable { instruction = ""; editing = false }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** The nearest lint error within two lines of the selection, or null. */
internal fun selectionDiagnostic(
    errors: List<LintError>,
    mapper: PositionMapper,
    offset: Int,
): LintError? {
    val line = mapper.offsetToLine(offset)
    return errors.firstOrNull { kotlin.math.abs(mapper.offsetToLine(it.start) - line) <= 2 }
}

/** One call builds the prompt for whichever action the user tapped. */
internal fun selectionPrompt(
    action: SelectionAction,
    instruction: String,
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    filePath: String?,
    languageName: String,
    diagnostic: LintError?,
    positionMapper: PositionMapper,
): String = SelectionActionSpec.prompt(
    action = action,
    instruction = instruction,
    ctx = EditorActionMenuSpec.contextFor(
        text = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        fileName = filePath,
        languageName = languageName,
        diagnosticMessage = diagnostic?.message,
        diagnosticLine = diagnostic?.let { positionMapper.offsetToLine(it.start) + 1 },
    ),
)
