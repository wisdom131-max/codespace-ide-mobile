package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.LocalEditorColors

/**
 * Multi-feature code editor pane.
 *
 * - Monospace, syntax-highlighted via [SyntaxHighlighter] visual transformation.
 * - Line-number gutter.
 * - Horizontal + vertical scrolling for long lines / big files.
 * - Emits [onContentChange] for autosave + dirty tracking.
 *
 * On large files the host virtualizes by only feeding the visible viewport's text to
 * this composable; the buffer itself is a piece-table held in the ViewModel.
 */
@Composable
fun CodeEditor(
    content: String,
    language: Language,
    fontSize: Int = 13,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEditorColors.current
    // cursor stays put — value is internal state, not driven by content prop
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(vScroll)
    ) {
        // Gutter
        Column(modifier = Modifier.padding(horizontal = 8.dp).width(44.dp)) {
            for (line in 1..lineCount) {
                Text(
                    text = line.toString(),
                    color = colors.gutter,
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // Editor surface
        Box(modifier = Modifier.horizontalScroll(hScroll)) {
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    onContentChange(it.text)
                },
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = colors.text,
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                ),
                visualTransformation = SyntaxTransformation(language, colors),
                modifier = Modifier.padding(end = 24.dp),
            )
        }
    }
}
