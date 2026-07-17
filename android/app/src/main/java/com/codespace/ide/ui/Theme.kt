package com.codespace.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class EditorColors(
    val background: Color, val gutter: Color, val text: Color,
    val keyword: Color, val string: Color, val number: Color,
    val comment: Color, val function: Color, val type: Color,
    val variable: Color, val operator: Color, val selection: Color,
    val currentLine: Color,
)

val LocalEditorColors = staticCompositionLocalOf { DraculaEditorColors }
val LocalThemeName    = staticCompositionLocalOf { "Dark (Default)" }

// ── Editor color palettes ────────────────────────────────────────────────────

val DarkEditorColors = EditorColors(
    background = Color(0xFF1E1E1E), gutter = Color(0xFF858585),
    text = Color(0xFFD4D4D4), keyword = Color(0xFF569CD6),
    string = Color(0xFFCE9178), number = Color(0xFFB5CEA8),
    comment = Color(0xFF6A9955), function = Color(0xFFDCDCAA),
    type = Color(0xFF4EC9B0), variable = Color(0xFF9CDCFE),
    operator = Color(0xFFD4D4D4), selection = Color(0x4089B4FA),
    currentLine = Color(0xFF2A2A2A),
)

val LightEditorColors = EditorColors(
    background = Color(0xFFFFFFFF), gutter = Color(0xFF9CA0B0),
    text = Color(0xFF000000), keyword = Color(0xFF0000FF),
    string = Color(0xFFA31515), number = Color(0xFF098658),
    comment = Color(0xFF008000), function = Color(0xFF795E26),
    type = Color(0xFF267F99), variable = Color(0xFF001080),
    operator = Color(0xFF000000), selection = Color(0x401E66F5),
    currentLine = Color(0xFFEEF1FB),
)

val DraculaEditorColors = EditorColors(
    background = Color(0xFF282A36), gutter = Color(0xFF6272A4),
    text = Color(0xFFF8F8F2), keyword = Color(0xFFFF79C6),
    string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
    comment = Color(0xFF6272A4), function = Color(0xFF50FA7B),
    type = Color(0xFF8BE9FD), variable = Color(0xFFFFB86C),
    operator = Color(0xFFFF79C6), selection = Color(0x44BD93F9),
    currentLine = Color(0xFF44475A),
)

val AmoledEditorColors = EditorColors(
    background = Color(0xFF000000), gutter = Color(0xFF444444),
    text = Color(0xFFEEEEEE), keyword = Color(0xFFFF79C6),
    string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
    comment = Color(0xFF555555), function = Color(0xFF50FA7B),
    type = Color(0xFF8BE9FD), variable = Color(0xFFFFB86C),
    operator = Color(0xFFFF79C6), selection = Color(0x44BD93F9),
    currentLine = Color(0xFF111111),
)

val MonokaiEditorColors = EditorColors(
    background = Color(0xFF272822), gutter = Color(0xFF75715E),
    text = Color(0xFFF8F8F2), keyword = Color(0xFFF92672),
    string = Color(0xFFE6DB74), number = Color(0xFFAE81FF),
    comment = Color(0xFF75715E), function = Color(0xFFA6E22E),
    type = Color(0xFF66D9E8), variable = Color(0xFFF8F8F2),
    operator = Color(0xFFF92672), selection = Color(0x44A6E22E),
    currentLine = Color(0xFF3E3D32),
)

val NordEditorColors = EditorColors(
    background = Color(0xFF2E3440), gutter = Color(0xFF4C566A),
    text = Color(0xFFECEFF4), keyword = Color(0xFF81A1C1),
    string = Color(0xFFA3BE8C), number = Color(0xFFB48EAD),
    comment = Color(0xFF4C566A), function = Color(0xFF88C0D0),
    type = Color(0xFF8FBCBB), variable = Color(0xFFD8DEE9),
    operator = Color(0xFF81A1C1), selection = Color(0x4481A1C1),
    currentLine = Color(0xFF3B4252),
)

val TokyoNightEditorColors = EditorColors(
    background = Color(0xFF1A1B26), gutter = Color(0xFF565F89),
    text = Color(0xFFC0CAF5), keyword = Color(0xFF9D7CD8),
    string = Color(0xFF9ECE6A), number = Color(0xFFFF9E64),
    comment = Color(0xFF565F89), function = Color(0xFF7DCFFF),
    type = Color(0xFF2AC3DE), variable = Color(0xFFC0CAF5),
    operator = Color(0xFF89DDFF), selection = Color(0x449D7CD8),
    currentLine = Color(0xFF24283B),
)

val OneDarkEditorColors = EditorColors(
    background = Color(0xFF282C34), gutter = Color(0xFF5C6370),
    text = Color(0xFFABB2BF), keyword = Color(0xFFC678DD),
    string = Color(0xFF98C379), number = Color(0xFFD19A66),
    comment = Color(0xFF5C6370), function = Color(0xFF61AFEF),
    type = Color(0xFFE5C07B), variable = Color(0xFFE06C75),
    operator = Color(0xFF56B6C2), selection = Color(0x44528BFF),
    currentLine = Color(0xFF2C313A),
)

val GithubDarkEditorColors = EditorColors(
    background = Color(0xFF0D1117), gutter = Color(0xFF484F58),
    text = Color(0xFFE6EDF3), keyword = Color(0xFFFF7B72),
    string = Color(0xFFA5D6FF), number = Color(0xFFF2CC60),
    comment = Color(0xFF8B949E), function = Color(0xFFD2A8FF),
    type = Color(0xFFFFA657), variable = Color(0xFFE6EDF3),
    operator = Color(0xFFFF7B72), selection = Color(0x443B5070),
    currentLine = Color(0xFF161B22),
)

val GithubLightEditorColors = EditorColors(
    background = Color(0xFFFFFFFF), gutter = Color(0xFF8C959F),
    text = Color(0xFF1F2328), keyword = Color(0xFFCF222E),
    string = Color(0xFF0A3069), number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781), function = Color(0xFF8250DF),
    type = Color(0xFFCF222E), variable = Color(0xFF1F2328),
    operator = Color(0xFFCF222E), selection = Color(0x440550AE),
    currentLine = Color(0xFFF6F8FA),
)

val EyeCareEditorColors = EditorColors(
    background = Color(0xFFF5F0E8), gutter = Color(0xFF9C8F7A),
    text = Color(0xFF3C3328), keyword = Color(0xFF7A4F3A),
    string = Color(0xFF5A7A3A), number = Color(0xFF7A6A3A),
    comment = Color(0xFF9C8F7A), function = Color(0xFF3A5A7A),
    type = Color(0xFF7A3A5A), variable = Color(0xFF3C3328),
    operator = Color(0xFF7A4F3A), selection = Color(0x443A5A7A),
    currentLine = Color(0xFFEDE8DF),
)
val CatppuccinEditorColors = EditorColors(
    background = Color(0xFF1E1E2E), gutter = Color(0xFF6C7086),
    text = Color(0xFFCDD6F4), keyword = Color(0xFFCBA6F7),
    string = Color(0xFFA6E3A1), number = Color(0xFFFAB387),
    comment = Color(0xFF6C7086), function = Color(0xFF89B4FA),
    type = Color(0xFFF9E2AF), variable = Color(0xFFF38BA8),
    operator = Color(0xFF89DCEB), selection = Color(0x4089B4FA),
    currentLine = Color(0xFF313244),
)

// ── Material color schemes ───────────────────────────────────────────────────

fun themeColors(name: String) = when (name) {
    "Dark (Default)"  -> darkColorScheme(primary = Color(0xFF569CD6), background = Color(0xFF1E1E1E), surface = Color(0xFF252526), onBackground = Color(0xFFD4D4D4), onSurface = Color(0xFFD4D4D4))
    "Dark Modern"     -> darkColorScheme(primary = Color(0xFF569CD6), background = Color(0xFF1E1E1E), surface = Color(0xFF252526), onBackground = Color(0xFFD4D4D4), onSurface = Color(0xFFD4D4D4))
    "Dracula"         -> darkColorScheme(primary = Color(0xFFBD93F9), background = Color(0xFF282A36), surface = Color(0xFF44475A), onBackground = Color(0xFFF8F8F2), onSurface = Color(0xFFF8F8F2))
    "AMOLED Black"    -> darkColorScheme(primary = Color(0xFFFF79C6), background = Color(0xFF000000), surface = Color(0xFF111111), onBackground = Color(0xFFEEEEEE), onSurface = Color(0xFFEEEEEE))
    "Monokai"         -> darkColorScheme(primary = Color(0xFFA6E22E), background = Color(0xFF272822), surface = Color(0xFF3E3D32), onBackground = Color(0xFFF8F8F2), onSurface = Color(0xFFF8F8F2))
    "One Dark Pro"    -> darkColorScheme(primary = Color(0xFF61AFEF), background = Color(0xFF282C34), surface = Color(0xFF2C313A), onBackground = Color(0xFFABB2BF), onSurface = Color(0xFFABB2BF))
    "GitHub Dark"     -> darkColorScheme(primary = Color(0xFFD2A8FF), background = Color(0xFF0D1117), surface = Color(0xFF161B22), onBackground = Color(0xFFE6EDF3), onSurface = Color(0xFFE6EDF3))
    "Tokyo Night"     -> darkColorScheme(primary = Color(0xFF7DCFFF), background = Color(0xFF1A1B26), surface = Color(0xFF24283B), onBackground = Color(0xFFC0CAF5), onSurface = Color(0xFFC0CAF5))
    "Nord"            -> darkColorScheme(primary = Color(0xFF88C0D0), background = Color(0xFF2E3440), surface = Color(0xFF3B4252), onBackground = Color(0xFFECEFF4), onSurface = Color(0xFFECEFF4))
    "Catppuccin"      -> darkColorScheme(primary = Color(0xFF89B4FA), background = Color(0xFF1E1E2E), surface = Color(0xFF181825), onBackground = Color(0xFFCDD6F4), onSurface = Color(0xFFCDD6F4))
    "Light (Default)" -> lightColorScheme(primary = Color(0xFF0000FF), background = Color(0xFFFFFFFF), surface = Color(0xFFF3F3F3), onBackground = Color(0xFF000000), onSurface = Color(0xFF000000))
    "Light Modern"    -> lightColorScheme(primary = Color(0xFF005FB8), background = Color(0xFFFFFFFF), surface = Color(0xFFF3F3F3), onBackground = Color(0xFF1F1F1F), onSurface = Color(0xFF1F1F1F))
    "GitHub Light"    -> lightColorScheme(primary = Color(0xFF0550AE), background = Color(0xFFFFFFFF), surface = Color(0xFFF6F8FA), onBackground = Color(0xFF1F2328), onSurface = Color(0xFF1F2328))
    "Quiet Light"     -> lightColorScheme(primary = Color(0xFF4078F2), background = Color(0xFFF5F5F5), surface = Color(0xFFEAEAEA), onBackground = Color(0xFF333333), onSurface = Color(0xFF333333))
    "Solarized Light" -> lightColorScheme(primary = Color(0xFF268BD2), background = Color(0xFFFDF6E3), surface = Color(0xFFEEE8D5), onBackground = Color(0xFF657B83), onSurface = Color(0xFF657B83))
    "Eye Care"        -> lightColorScheme(primary = Color(0xFF7A4F3A), background = Color(0xFFF5F0E8), surface = Color(0xFFEDE8DF), onBackground = Color(0xFF3C3328), onSurface = Color(0xFF3C3328))
    else              -> darkColorScheme(primary = Color(0xFF569CD6), background = Color(0xFF1E1E1E), surface = Color(0xFF252526), onBackground = Color(0xFFD4D4D4), onSurface = Color(0xFFD4D4D4))
}

fun themeEditorColors(name: String) = when (name) {
    "Dark (Default)", "Dark Modern" -> DarkEditorColors
    "Dracula"         -> DraculaEditorColors
    "AMOLED Black"    -> AmoledEditorColors
    "Monokai"         -> MonokaiEditorColors
    "One Dark Pro"    -> OneDarkEditorColors
    "GitHub Dark"     -> GithubDarkEditorColors
    "Tokyo Night"     -> TokyoNightEditorColors
    "Nord"            -> NordEditorColors
    "Eye Care"        -> EyeCareEditorColors
    "Catppuccin"      -> CatppuccinEditorColors
    "Light (Default)", "Light Modern" -> LightEditorColors
    "GitHub Light"    -> GithubLightEditorColors
    "Quiet Light", "Solarized Light"  -> LightEditorColors
    else              -> DarkEditorColors
}

@Composable
fun CodeSpaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeName: String = if (darkTheme) "Dark (Default)" else "Light (Default)",
    content: @Composable () -> Unit,
) {
    val colors      = themeColors(themeName)
    val editorColors = themeEditorColors(themeName)
    CompositionLocalProvider(
        LocalEditorColors provides editorColors,
        LocalThemeName    provides themeName,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
