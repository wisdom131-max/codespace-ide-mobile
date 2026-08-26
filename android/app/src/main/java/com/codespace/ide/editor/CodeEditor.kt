package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import org.json.JSONObject
import com.codespace.ide.diagnostics.AppOutputLog
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspCompletionItem
import com.codespace.ide.lsp.CallHierarchyItem
import com.codespace.ide.lsp.IncomingCall
import com.codespace.ide.lsp.OutgoingCall
import com.codespace.ide.lsp.TypeHierarchyItem
import com.codespace.ide.lsp.CompletionSource
import com.codespace.ide.lsp.RankedCompletionItem
import com.codespace.ide.lsp.parseSnippet
import com.codespace.ide.lsp.SnippetContext
import com.codespace.ide.lsp.activeStop
import com.codespace.ide.lsp.createSnippetSession
import com.codespace.ide.lsp.SnippetSession
import com.codespace.ide.lsp.activeStopRange
import com.codespace.ide.lsp.advance
import com.codespace.ide.lsp.retreat
import com.codespace.ide.lsp.containsCursor
import com.codespace.ide.lsp.shiftAfterEdit
import com.codespace.ide.lsp.applyActiveStopTransform
import com.codespace.ide.editor.PathCompletionProvider
import com.codespace.ide.editor.PeekCodeWidget
import com.codespace.ide.editor.PeekReferencesWidget
import com.codespace.ide.editor.PeekResult
import com.codespace.ide.editor.PeekRefsResult
import com.codespace.ide.lsp.rank
import com.codespace.ide.lsp.fuzzyScore
import com.codespace.ide.lsp.fuzzyMatchIndices
import com.codespace.ide.lsp.CompletionItemKind
import com.codespace.ide.lsp.CompletionHistoryStore
import com.codespace.ide.editor.SignatureInfo
import com.codespace.ide.lsp.LspManager
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import com.codespace.ide.lsp.ImportEdit
import com.codespace.ide.lsp.applyImportEdits
import com.codespace.ide.lsp.applyLspTextEdits
import com.codespace.ide.lsp.LspCodeAction
import com.codespace.ide.ui.LocalEditorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.codespace.ide.editor.ui.HoverPopup
import com.codespace.ide.editor.ui.DiagnosticTooltip
import com.codespace.ide.editor.decorations.BlockLineOverlay

/** Standard gutter width in dp — ALL overlays must use this constant to stay aligned with the text.
 *  Previously: hardcoded values of 64f, 66.dp, 72.dp, 74f, 74.dp, 80f were used inconsistently,
 *  causing overlays (highlights, cursors, squiggles, popups) to be misaligned with the text by 2-8dp. */
private const val GUTTER_WIDTH = EditorMetrics.GUTTER_WIDTH_DP  // Phase E: single source of truth

/** Feature toggles for editor overlays — pass from EditorPane to enable/disable individual features. */
data class EditorFeatureToggles(
    val showCodeLens: Boolean = true,
    val showLspHighlights: Boolean = true,
    val showErrorLens: Boolean = true,
    val showColorSwatches: Boolean = true,
    val showDocumentLinks: Boolean = true,
    val showStickyScroll: Boolean = true,
    val showGhostText: Boolean = true,
    val showInlayHints: Boolean = true,
    val showMergeConflicts: Boolean = true,
    val showMinimap: Boolean = true,
    val showWordWrap: Boolean = false,
)

private data class Completion(
    val label: String,
    val kind: CompletionKind,
    val insertText: String = label,
    val doc: String? = null,
    // P41-D: Auto-import edits attached by LSP server (JSON string of TextEdit[] array)
    val additionalTextEditsJson: String? = null,
    // P41-D: Range-based replacement edit from LSP (JSON object)
    val textEditJson: String? = null,
    // P41-J: Source attribution for badge display
    val source: CompletionSource = CompletionSource.BUFFER,
    // P41-J: Deprecation flag from LSP tags
    val isDeprecated: Boolean = false,
    // P41-H: Raw LSP CompletionItemKind (1-25) for kind-specific icons. 0 = non-LSP (use kind fallback).
    val lspKind: Int = 0,
    // P41-I: LSP insertTextFormat (1=PlainText, 2=Snippet). When 2, insertText has $1/$0 syntax.
    val insertTextFormat: Int = 1,
    // Phase U-4: LSP command to execute after applying completion (JSON string)
    val command: String? = null,
    // Phase U-5: LSP commitCharacters — chars that commit the selected completion when typed
    val commitCharacters: List<Char> = emptyList(),
)
private enum class CompletionKind { KEYWORD, TYPE, SNIPPET }

// ── Hover docs for common symbols ──────────────────────────────────────────
private val HOVER_DOCS: Map<String, String> = mapOf(
    // Kotlin
    "fun" to "Declares a function. Usage: fun name(params): ReturnType { }",
    "val" to "Declares an immutable (read-only) property or local variable.",
    "var" to "Declares a mutable property or local variable.",
    "suspend" to "Marks a function as a coroutine — can call other suspend functions and delay without blocking a thread.",
    "companion" to "A companion object is a singleton tied to a class, similar to static members in Java.",
    "data" to "Data class — auto-generates equals(), hashCode(), copy(), and toString() based on constructor params.",
    "sealed" to "Sealed class — all subclasses must be defined in the same file. Useful for exhaustive when expressions.",
    "inline" to "Inline function — the compiler copies the function body at every call site, avoiding lambda object allocation.",
    "reified" to "Used with inline functions to access the actual type T at runtime (e.g. obj is T).",
    "by" to "Delegation — implements an interface or property by delegating to another object.",
    "lateinit" to "Declares a non-null var that will be initialized later. Throws UninitializedPropertyAccessException if accessed before init.",
    "override" to "Overrides an open or abstract member from a superclass or interface.",
    "object" to "Declares a singleton or anonymous object. companion object { } acts as a class-level namespace.",
    // Kotlin stdlib / common
    "launch" to "Starts a new coroutine in a CoroutineScope. Returns a Job — non-blocking fire-and-forget.",
    "collect" to "Terminal operator for a Flow — suspends and collects each emitted value.",
    "remember" to "Caches a value across recompositions. Recomputed only when keys change.",
    "rememberSaveable" to "Like remember but also survives process death and configuration changes (saved to Bundle).",
    "mutableStateOf" to "Creates a mutable State<T> that triggers recomposition when its value changes.",
    "LaunchedEffect" to "Runs a suspend block in a coroutine scoped to the composition. Restarts when keys change.",
    "derivedStateOf" to "Creates a State whose value is derived from other States — only recomposes when the result changes.",
    // JavaScript / TypeScript
    "const" to "Declares a block-scoped constant. The binding cannot be reassigned (but object contents can still mutate).",
    "let" to "Declares a block-scoped variable that can be reassigned.",
    "async" to "Marks a function as asynchronous — it always returns a Promise.",
    "await" to "Pauses execution inside an async function until the Promise resolves.",
    "typeof" to "Returns a string indicating the type of the operand: 'string', 'number', 'boolean', 'object', 'undefined', 'function', 'symbol'.",
    "instanceof" to "Tests whether an object has the prototype of a constructor in its prototype chain.",
    "Promise" to "Represents an eventual (async) result. States: pending → fulfilled | rejected.",
    // Python
    "def" to "Defines a function. Usage: def name(params): body",
    "self" to "Refers to the current instance inside a class method. Must be the first parameter by convention.",
    "yield" to "Turns a function into a generator. Each yield produces a value without ending the function.",
    "lambda" to "Creates an anonymous function. Usage: lambda x, y: x + y",
    "with" to "Context manager — calls __enter__ at start and __exit__ at end, even if an exception is raised.",
    "None" to "Python's null value. Equivalent to null in other languages. Type: NoneType.",
    // Rust
    "fn" to "Declares a function. Usage: fn name(params) -> ReturnType { }",
    "mut" to "Makes a binding mutable. In Rust, variables are immutable by default.",
    "impl" to "Implements methods or traits for a type.",
    "trait" to "Defines shared behavior — similar to interfaces in other languages.",
    "match" to "Pattern-matching expression. Must be exhaustive (all cases handled).",
    "Option" to "Rust's null-safe type. Either Some(value) or None.",
    "Result" to "Error-handling type. Either Ok(value) or Err(error).",
    "Vec" to "A growable heap-allocated array. Similar to ArrayList in Java.",
    // Go
    "defer" to "Defers execution of a function call until the surrounding function returns. Called in LIFO order.",
    "goroutine" to "A lightweight thread managed by the Go runtime. Launched with the 'go' keyword.",
    "chan" to "A typed channel for communication between goroutines. Send: ch <- v. Receive: v := <-ch.",
    "nil" to "Go's zero value for pointers, slices, maps, channels, functions, and interface types.",
    // Common
    "return" to "Exits the current function and optionally returns a value to the caller.",
    "import" to "Brings external modules or packages into the current file's scope.",
    "class" to "Blueprint for creating objects. Encapsulates data (fields) and behaviour (methods).",
    "interface" to "Defines a contract — a set of methods that implementing classes must provide.",
    "null" to "Represents the absence of a value. Many languages have null-safety features to avoid null pointer errors.",
    "true" to "Boolean literal representing the logical true value.",
    "false" to "Boolean literal representing the logical false value.",
    "this" to "Refers to the current object instance inside a class method.",
    "static" to "Belongs to the class itself rather than any instance. Shared across all instances.",
    "void" to "Indicates a function returns no value.",
    "new" to "Allocates a new object instance on the heap and calls its constructor.",
    "throw" to "Raises an exception, transferring control to the nearest matching catch block.",
    "try" to "Wraps code that might throw an exception. Pairs with catch and/or finally.",
    "catch" to "Handles exceptions thrown in the corresponding try block.",
    "finally" to "Block that always runs after try/catch, regardless of whether an exception was thrown.",
    "for" to "Loop construct. Common forms: for (init; condition; step), for (item in collection), for...of, for...in.",
    "while" to "Loops while the condition is true. Checks condition before each iteration.",
    "break" to "Exits the innermost loop or switch statement immediately.",
    "continue" to "Skips the rest of the current loop iteration and starts the next one.",
    "switch" to "Multi-way branch — compares a value against multiple cases and executes the matching branch.",
)

private fun hoverDocFor(word: String): String? = HOVER_DOCS[word]

// ── Language-aware snippets with insert text ───────────────────────────────
private fun snippetsFor(lang: Language): List<Completion> = when (lang) {
    Language.KOTLIN -> listOf(
        Completion("fun", CompletionKind.SNIPPET, "fun \${1:name}(): Unit {\n    \$0\n}", "Function declaration", insertTextFormat = 2),
        Completion("class", CompletionKind.SNIPPET, "class \${1:Name} {\n    \$0\n}", "Class declaration", insertTextFormat = 2),
        Completion("data class", CompletionKind.SNIPPET, "data class \${1:Name}(val \${2:field}: \${3:Type})", "Data class — auto-generates equals/hashCode/copy", insertTextFormat = 2),
        Completion("when", CompletionKind.SNIPPET, "when (\${1:expr}) {\n    else -> {\$0}\n}", "Exhaustive when expression", insertTextFormat = 2),
        Completion("if", CompletionKind.SNIPPET, "if (\${1:condition}) {\n    \$0\n}", "If statement", insertTextFormat = 2),
        Completion("for", CompletionKind.SNIPPET, "for (\${1:item} in \${2:collection}) {\n    \$0\n}", "For-each loop", insertTextFormat = 2),
        Completion("object", CompletionKind.SNIPPET, "object \${1:Name} {\n    \$0\n}", "Singleton object", insertTextFormat = 2),
        Completion("companion object", CompletionKind.SNIPPET, "companion object {\n    \$0\n}", "Companion object (static members)", insertTextFormat = 2),
        Completion("launch", CompletionKind.SNIPPET, "launch {\n    \$0\n}", "Launch a coroutine", insertTextFormat = 2),
        Completion("LaunchedEffect", CompletionKind.SNIPPET, "LaunchedEffect(\${1:key}) {\n    \$0\n}", "Run suspend block scoped to composition", insertTextFormat = 2),
        Completion("remember", CompletionKind.SNIPPET, "remember { mutableStateOf(\$0) }", "Cache value across recompositions", insertTextFormat = 2),
        Completion("Composable", CompletionKind.SNIPPET, "@Composable\nfun \${1:Name}() {\n    \$0\n}", "Jetpack Compose function", insertTextFormat = 2),
    )
    Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
        Completion("function", CompletionKind.SNIPPET, "function \${1:name}(\${2:params}) {\n    \$0\n}", "Function declaration", insertTextFormat = 2),
        Completion("const", CompletionKind.SNIPPET, "const \${1:name} = \${2:value}", "Immutable binding", insertTextFormat = 2),
        Completion("async function", CompletionKind.SNIPPET, "async function \${1:name}() {\n    \$0\n}", "Async function", insertTextFormat = 2),
        Completion("class", CompletionKind.SNIPPET, "class \${1:Name} {\n  constructor() {\n    \$0\n  }\n}", "Class declaration", insertTextFormat = 2),
        Completion("for...of", CompletionKind.SNIPPET, "for (const \${1:item} of \${2:array}) {\n    \$0\n}", "Iterate over iterable", insertTextFormat = 2),
        Completion("try", CompletionKind.SNIPPET, "try {\n    \$0\n} catch (err) {\n    \n}", "Try-catch", insertTextFormat = 2),
        Completion("Promise", CompletionKind.SNIPPET, "new Promise((resolve, reject) => {\n    \$0\n})", "Create a Promise", insertTextFormat = 2),
        Completion("console.log", CompletionKind.SNIPPET, "console.log(\$0)", "Log to console", insertTextFormat = 2),
        Completion("=>", CompletionKind.SNIPPET, "(\${1:params}) => {\n    \$0\n}", "Arrow function", insertTextFormat = 2),
    )
    Language.PYTHON -> listOf(
        Completion("def", CompletionKind.SNIPPET, "def \${1:name}(\${2:params}):\n    \$0", "Function definition", insertTextFormat = 2),
        Completion("class", CompletionKind.SNIPPET, "class \${1:Name}:\n    def __init__(self):\n        \$0", "Class with constructor", insertTextFormat = 2),
        Completion("if", CompletionKind.SNIPPET, "if \${1:condition}:\n    \$0", "If statement", insertTextFormat = 2),
        Completion("for", CompletionKind.SNIPPET, "for \${1:item} in \${2:collection}:\n    \$0", "For loop", insertTextFormat = 2),
        Completion("with", CompletionKind.SNIPPET, "with open('\${1:file}') as \${2:f}:\n    \$0", "Context manager", insertTextFormat = 2),
        Completion("try", CompletionKind.SNIPPET, "try:\n    \$0\nexcept Exception as e:\n    ", "Try-except", insertTextFormat = 2),
        Completion("lambda", CompletionKind.SNIPPET, "lambda \${1:x}: \$0", "Anonymous function", insertTextFormat = 2),
        Completion("list comprehension", CompletionKind.SNIPPET, "[\${1:expr} for \${2:item} in \${3:iterable}]", "List comprehension", insertTextFormat = 2),
        Completion("print", CompletionKind.SNIPPET, "print(\$0)", "Print to stdout", insertTextFormat = 2),
    )
    Language.JAVA -> listOf(
        Completion("public class", CompletionKind.SNIPPET, "public class \${1:Name} {\n    \$0\n}", "Public class", insertTextFormat = 2),
        Completion("public static void main", CompletionKind.SNIPPET, "public static void main(String[] args) {\n    \$0\n}", "Main method", insertTextFormat = 2),
        Completion("for", CompletionKind.SNIPPET, "for (int \${1:i} = 0; \${1:i} < \${2:n}; \${1:i}++) {\n    \$0\n}", "For loop", insertTextFormat = 2),
        Completion("try", CompletionKind.SNIPPET, "try {\n    \$0\n} catch (Exception e) {\n    e.printStackTrace();\n}", "Try-catch", insertTextFormat = 2),
        Completion("interface", CompletionKind.SNIPPET, "public interface \${1:Name} {\n    \$0\n}", "Interface", insertTextFormat = 2),
        Completion("@Override", CompletionKind.SNIPPET, "@Override\npublic void \${1:method}() {\n    \$0\n}", "Override annotation", insertTextFormat = 2),
    )
    Language.RUST -> listOf(
        Completion("fn", CompletionKind.SNIPPET, "fn \${1:name}() -> () {\n    \$0\n}", "Function declaration", insertTextFormat = 2),
        Completion("struct", CompletionKind.SNIPPET, "struct \${1:Name} {\n    \${2:field}: \${3:Type},\n}", "Struct definition", insertTextFormat = 2),
        Completion("impl", CompletionKind.SNIPPET, "impl \${1:Name} {\n    fn \${2:method}(&self) {\n        \$0\n    }\n}", "Impl block", insertTextFormat = 2),
        Completion("match", CompletionKind.SNIPPET, "match \${1:value} {\n    Some(x) => x,\n    None => todo!(),\n}", "Match expression", insertTextFormat = 2),
        Completion("let", CompletionKind.SNIPPET, "let \${1:name} = \${2:value};", "Immutable binding", insertTextFormat = 2),
        Completion("let mut", CompletionKind.SNIPPET, "let mut \${1:name} = \${2:value};", "Mutable binding", insertTextFormat = 2),
        Completion("for", CompletionKind.SNIPPET, "for \${1:item} in \${2:collection} {\n    \$0\n}", "For loop", insertTextFormat = 2),
    )
    Language.GO -> listOf(
        Completion("func", CompletionKind.SNIPPET, "func \${1:name}() {\n    \$0\n}", "Function declaration", insertTextFormat = 2),
        Completion("struct", CompletionKind.SNIPPET, "type \${1:Name} struct {\n    \${2:Field} \${3:Type}\n}", "Struct type", insertTextFormat = 2),
        Completion("interface", CompletionKind.SNIPPET, "type \${1:Name} interface {\n    \${2:Method}() \${3:ReturnType}\n}", "Interface type", insertTextFormat = 2),
        Completion("for", CompletionKind.SNIPPET, "for i := 0; i < \${1:n}; i++ {\n    \$0\n}", "For loop", insertTextFormat = 2),
        Completion("goroutine", CompletionKind.SNIPPET, "go func() {\n    \$0\n}()", "Anonymous goroutine", insertTextFormat = 2),
        Completion("defer", CompletionKind.SNIPPET, "defer func() {\n    \$0\n}()", "Deferred cleanup", insertTextFormat = 2),
        Completion("err check", CompletionKind.SNIPPET, "if err != nil {\n    return \$0err\n}", "Error check", insertTextFormat = 2),
    )
    else -> listOf(
        Completion("TODO", CompletionKind.SNIPPET, "TODO", "Mark as not yet implemented"),
        Completion("FIXME", CompletionKind.SNIPPET, "FIXME", "Mark as needing a fix"),
    )
}
private fun completionsFor(prefix: String, lang: Language): List<Completion> {
    if (prefix.isEmpty()) return emptyList()
    val spec = LanguageSpecs.forLanguage(lang)
    val p = prefix.lowercase()
    val kw = spec.keywords.filter { it.startsWith(p) }.sorted().map {
        Completion(it, CompletionKind.KEYWORD, it, hoverDocFor(it))
    }
    val ty = spec.types.filter { it.lowercase().startsWith(p) }.sorted().map {
        Completion(it, CompletionKind.TYPE, it, hoverDocFor(it))
    }
    val snips = snippetsFor(lang).filter {
        it.label.lowercase().startsWith(p) || it.insertText.lowercase().startsWith(p)
    }
    // C13: Stdlib completions — builtins, modules, and dot-qualified members
    val stdlib = StdlibCompletions.completionsFor(prefix, lang).map { (label, doc) ->
        Completion(label, CompletionKind.KEYWORD, label, doc)
    }
    return (snips + stdlib + kw + ty).distinctBy { it.label }.take(60)
}

private fun currentWord(text: String, cursor: Int): String {
    val pos = cursor.coerceIn(0, text.length)
    // Scan backward from cursor to find word start
    var start = pos
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    // P46-A8: Also scan forward so the word is found when cursor is at its start
    // (e.g. after Select Next Occurrence moves selection to next match start)
    var end = pos
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
    return text.substring(start, end)
}

// Phase U-8: Convert LSP Position (line, character) to text offset
private fun lspPositionToOffset(pos: org.json.JSONObject?, text: String): Int {
    if (pos == null) return 0
    val line = pos.optInt("line", 0)
    val char = pos.optInt("character", 0)
    var offset = 0
    var currentLine = 0
    while (currentLine < line && offset < text.length) {
        if (text[offset] == '\n') currentLine++
        offset++
    }
    return (offset + char).coerceIn(0, text.length)
}


/** P22-L: Peek Definition result — inline code preview without navigating away. */
data class PeekDefResult(val filePath: String, val line: Int, val lines: List<String>, val defLine: Int)

/** P20-A: Git blame info per line */
data class BlameLine(val author: String, val date: String, val shortSha: String)

@OptIn(ExperimentalFoundationApi::class)
// P2-4: Definition result types (moved to file level for composable extraction)
data class DefResult(val line: Int, val lineText: String)
data class CrossFileDefResult(val name: String, val kind: String, val filePath: String, val line: Int, val fileName: String)
/** Phase A: Now uses PositionMapper for O(log n) lookup. */
private fun offsetToLineChar(text: String, offset: Int): Pair<Int, Int> {
    val pos = PositionMapper(text).offsetToPosition(offset)
    return Pair(pos.line, pos.column)
}

/** Phase R: Apply LSP TextEdit[] to document content. */
private fun applyLspEdits(text: String, edits: org.json.JSONArray): String {
    var result = text
    // Apply edits in reverse order to preserve offsets
    val editList = (0 until edits.length()).map { i ->
        edits.getJSONObject(i)
    }.sortedByDescending { edit ->
        val range = edit.getJSONObject("range")
        range.getJSONObject("start").getInt("line")
    }
    for (edit in editList) {
        try {
            val range = edit.getJSONObject("range")
            val startLine = range.getJSONObject("start").getInt("line")
            val startChar = range.getJSONObject("start").getInt("character")
            val endLine = range.getJSONObject("end").getInt("line")
            val endChar = range.getJSONObject("end").getInt("character")
            val newText = edit.optString("newText", "")
            // Convert line/char to offsets
            var startOffset = 0
            var currentLine = 0
            var currentChar = 0
            for (i in result.indices) {
                if (currentLine == startLine && currentChar == startChar) { startOffset = i; break }
                if (result[i] == '\n') { currentLine++; currentChar = 0 } else { currentChar++ }
                startOffset = i + 1
            }
            var endOffset = startOffset
            currentLine = startLine; currentChar = startChar
            for (i in startOffset until result.length) {
                if (currentLine == endLine && currentChar == endChar) { endOffset = i; break }
                if (result[i] == '\n') { currentLine++; currentChar = 0 } else { currentChar++ }
                endOffset = i + 1
            }
            result = result.substring(0, startOffset) + newText + result.substring(endOffset)
        } catch (_: Exception) { }
    }
    return result
}

private suspend fun doFormatSelection(
    fullText: String,
    selStart: Int,
    selEnd: Int,
    language: com.codespace.ide.domain.Language,
    filePath: String?,
    context: android.content.Context,
): Triple<String, Int, Int>? {
    val selectedText = fullText.substring(selStart, selEnd)

    // Try LSP range formatting first
    if (LspManager.isServerRunning(language) && filePath != null) {
        try {
            val uri = LspManager.fileUriFromHostPath(context, filePath)
            if (uri != null) {
                val startPair = offsetToLineChar(fullText, minOf(selStart, selEnd))
                val endPair = offsetToLineChar(fullText, maxOf(selStart, selEnd))
                val edits = LspManager.getRangeFormatting(
                    language, uri,
                    startPair.first, startPair.second,
                    endPair.first, endPair.second,
                )
                if (edits != null && edits.length() > 0) {
                    val newContent = applyLspEdits(fullText, edits)
                    if (newContent != fullText) {
                        return Triple(newContent, selStart, selEnd)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    // Fall back to built-in indent normalization
    val formatted = FormatterConfig.fallbackFormat(selectedText)
    if (formatted != selectedText) {
        val newText = fullText.substring(0, selStart) + formatted + fullText.substring(selEnd)
        val newSelEnd = selStart + formatted.length
        return Triple(newText, selStart, newSelEnd)
    }

    return null
}


@Composable
fun CodeEditor(
    content: String,
    language: Language,
    fontSize: Int = 13,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    savedContent: String = "",
    wordWrap: Boolean = false,
    scrollToLine: Int = 0,
    findReplaceOpen: Boolean = false,
    onFindReplaceClose: () -> Unit = {},
    /** External find query from the top find bar (white bar in ProjectShellScreen).
     *  When non-null, syncs to internal findQuery so the top bar drives match highlighting. */
    externalFindQuery: String? = null,
    /** When true, forces find mode active (driven by top find bar toggle). */
    externalFindBarOpen: Boolean = false,
    /** External find options from the top find bar. -1 = don't override internal state. */
    externalCaseSensitive: Boolean? = null,
    externalWholeWord: Boolean? = null,
    externalUseRegex: Boolean? = null,
    /** External match index from the top find bar for next/prev navigation. -1 = don't override. */
    externalFindMatchIndex: Int = -1,
    goToLineOpen: Boolean = false,
    onGoToLineClose: () -> Unit = {},
    /** R3-A: Ctrl+F opens find/replace from keyboard */
    onFindReplaceOpen: () -> Unit = {},
    /** R3-A: Ctrl+G opens go-to-line from keyboard */
    onGoToLineOpen: () -> Unit = {},
    /** R3-A: Ctrl+S saves the current file */
    onSave: (() -> Unit)? = null,
    /** P2-9 Bookmarks: initial set of bookmarked line indices (0-based). */
    initialBookmarks: Set<Int> = emptySet(),
    /** P2-9 Bookmarks: called whenever the bookmark set changes. */
    onBookmarksChange: ((Set<Int>) -> Unit)? = null,
    /** P2-11 Inlay hints: show/hide the inline type/param hint overlay. */
    showInlayHints: Boolean = true,
    /** P8-1 Breakpoints: set of breakpoint line indices (0-based). */
    breakpointLines: Set<Int> = emptySet(),
    /** P8-1 Breakpoints: called when user taps a line number to toggle a breakpoint. */
    onBreakpointToggle: (Int) -> Unit = {},
    /** P54: Current debug line (1-based) for yellow arrow indicator in gutter. 0 = none. */
    debugCurrentLine: Int = 0,
    /** P41-W: LSP semantic token ranges — overlaid on regex highlighting */
    semanticTokens: List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange> = emptyList(),
    /** P26-1: LSP document highlight — lines to highlight (0-based startLine, endLine pairs). */
    lspHighlightLines: List<Pair<Int, Int>> = emptyList(),
    /** P26-1: LSP document symbols — outline structure (JSONArray of DocumentSymbol). */
    lspDocumentSymbols: org.json.JSONArray? = null,
    /** P26-1: LSP folding ranges — pairs of (startLine, endLine) for LSP-based folding. */
    lspFoldingRanges: List<Pair<Int, Int>> = emptyList(),
    /** P26-1: LSP code lens — inline annotations (JSONArray of CodeLens). */
    lspCodeLenses: org.json.JSONArray? = null,
    lspDocumentColors: org.json.JSONArray? = null, // P41-K: Color swatches
    /** P41-N: CodeLens click handler — receives the raw lens JSON for command execution. */
    onCodeLensClick: ((org.json.JSONObject) -> Unit)? = null,
    /** P26-1: LSP inlay hints — inline type/parameter hints (JSONArray of InlayHint). */
    lspInlayHints: org.json.JSONArray? = null,
    /** P26-1: LSP document links — clickable links in comments (JSONArray of DocumentLink). */
    lspDocumentLinks: org.json.JSONArray? = null,
    /** P26-1: LSP Type Definition — called from context menu to peek type definition. */
    onLspTypeDefinition: (() -> Boolean)? = null,  // P37-3fix: returns true if LSP succeeded
    /** P26-1: LSP Implementation — called from context menu to find implementations. */
    onLspImplementation: ((Int, Int) -> Boolean)? = null,  // P37-3fix: returns true if LSP succeeded
    /** P26-1: LSP Selection Range — expand selection to semantic boundary (line, col). */
    onLspSelectionRange: ((Int, Int) -> org.json.JSONArray?)? = null,
    /** P26-1: LSP Prepare Rename — check if symbol at position can be renamed (line, col). */
    onLspPrepareRename: ((Int, Int) -> JSONObject?)? = null,
    /** P26-1: LSP Workspace Symbol — search symbols across workspace (query string). */
    /** P15-A: Fix with AI — called with a pre-formatted prompt when user taps "Fix with AI". */
    onAiFixRequest: ((String) -> Unit)? = null,
    /** P41-E: AI ghost text request — returns multi-line code continuation or null.
     *  Called with (contextBefore, contextAfter, language) after 600ms idle.
     *  The result is shown as dimmed ghost text that the user can accept (Tab) or dismiss. */
    onAiGhostTextRequest: ((contextBefore: String, contextAfter: String, language: String) -> String?)? = null,
    /** P18-C: Project root path for cross-file rename. Null = single-file only. */
    projectRoot: String? = null,
    /** P41-G: Current file path — for path completion context detection */
    currentFilePath: String? = null,
    /** P19-A: Cross-file Go-to-Definition — opens file at line. */
    onOpenFileAtLine: ((String, Int) -> Unit)? = null,
    /** P20-A: Git blame data — when non-null, shows author+date column next to line numbers */
    blameData: Map<Int, BlameLine>? = null,
    /** P22-D: Merge conflict hunks — when non-null, shows colored backgrounds + resolve buttons per hunk */
    conflictData: List<ConflictHunk>? = null,
    /** P22-D: Called when user resolves a conflict hunk */
    onResolveConflict: ((ConflictHunk, ConflictResolution) -> Unit)? = null,
    /** P22-G: Reports cursor position (0-based line, 0-based column) for LSP hover */
    onCursorChange: ((Int, Int) -> Unit)? = null,
    /** P22-H: LSP-backed completion provider — returns LSP completion items for a position */
    lspCompletionProvider: ((line: Int, col: Int) -> List<LspCompletionItem>)? = null,
    /** P22-J: LSP-backed auto-import provider — returns ImportEdits for current cursor position */
    lspImportProvider: ((line: Int, col: Int) -> List<ImportEdit>)? = null,
    // Phase U-4: Executor for LSP workspace/executeCommand (called after completion accept)
    lspCommandExecutor: ((command: String, arguments: org.json.JSONArray?) -> Unit)? = null,
    /** P41-F: Workspace symbol provider — returns workspace/symbol results for cross-file completion */
    lspWorkspaceSymbolProvider: ((query: String) -> List<LspCompletionItem>)? = null,
    /** P41-K: LSP completion resolver — lazily resolves documentation/detail for a highlighted item */
    lspCompletionResolver: ((item: LspCompletionItem) -> LspCompletionItem?)? = null,
    /** P41-K: LSP request cancellation — sends $/cancelRequest for a stale request ID */
    lspCancellationProvider: ((Long) -> Unit)? = null,
    /** P41-K: LSP request ID provider — returns current pending request ID for cancellation tracking */
    lspRequestIdProvider: (() -> Long)? = null,
    /** P24-1: LSP diagnostics as LintErrors — shown as squiggles on top of syntax highlighting */
    lspDiagnosticErrors: List<LintError> = emptyList(),
    toggles: EditorFeatureToggles = EditorFeatureToggles(),
    /** P24-3: Find References — called with word at cursor, returns list of (filePath, line, snippet) */
    onFindReferences: ((String, Int, Int) -> List<Triple<String, Int, String>>)? = null,
    /** P24-3: Rename Symbol — called with (word, newName) to apply workspace rename */
    onRenameSymbol: ((String, String) -> Unit)? = null,
    /** P41-M: Call hierarchy — prepares call hierarchy at cursor position (returns raw JSON array) */
    onPrepareCallHierarchy: ((line: Int, col: Int) -> List<CallHierarchyItem>?)? = null,
    /** P41-M: Call hierarchy — fetches incoming calls for a call item */
    onCallHierarchyIncoming: ((CallHierarchyItem) -> List<IncomingCall>)? = null,
    /** P41-M: Call hierarchy — fetches outgoing calls for a call item */
    onCallHierarchyOutgoing: ((CallHierarchyItem) -> List<OutgoingCall>)? = null,
    /** P41-M: Type hierarchy — prepares type hierarchy at cursor position (returns raw JSON array) */
    onPrepareTypeHierarchy: ((line: Int, col: Int) -> List<TypeHierarchyItem>?)? = null,
    /** P41-M: Type hierarchy — fetches supertypes for a type item */
    onTypeHierarchySupertypes: ((TypeHierarchyItem) -> List<TypeHierarchyItem>)? = null,
    /** P41-M: Type hierarchy — fetches subtypes for a type item */
    onTypeHierarchySubtypes: ((TypeHierarchyItem) -> List<TypeHierarchyItem>)? = null,
    /** Minimap: initial visibility, can be toggled via dropdown in the editor toolbar */
    showMinimap: Boolean = true,
    /** P24: LSP code actions provider — returns quick fixes for a line */
lspCodeActionProvider: ((line: Int) -> List<LspCodeAction>)? = null,
/** P22-L: Current file path for LSP definition and peek definition */
    filePath: String = "",
    /** P25-LSP: LSP-backed signature help — returns signature info from the language server */
    lspSignatureHelpProvider: ((line: Int, col: Int) -> SignatureInfo?)? = null,
    /** P38: LSP hover content — raw text from LSP hover, rendered as compact popup */
    lspHoverContent: String? = null,
    /** P38: LSP Go-to-Definition — returns true if LSP succeeded (falls back to regex if false/null) */
    onLspDefinition: ((Int, Int) -> Boolean)? = null,  // TEST-11-FIX: now passes (line, col) so LSP gets current cursor, not stale state
    /** P41-O5: LSP Go to Declaration — semantic navigation to declaration (e.g. header file) */
    /** P41-I: Source Actions — Organize Imports, Remove Unused, Fix All. Called with the CodeActionKind string. */
    onSourceAction: ((kind: String) -> Unit)? = null,
    onLspDeclaration: ((Int, Int) -> Boolean)? = null,  // TEST-11-FIX: same fix for declaration
    /** Keyboard toolbar insert handler — registers a function that inserts text at cursor position.
     *  Called once during composition. The registered function handles:
     *  - "Tab" → triggers snippet expansion or inserts \t at cursor
     *  - "Esc" → dismisses completions, snippet sessions, and popups
     *  - Any other string → inserts at cursor position (like typing it on a real keyboard) */
    onInsertHandler: (((String) -> Unit) -> Unit)? = null,
    /** Phase R: Format Selection trigger — when incremented, formats the selected text range. */
    formatSelectionTrigger: Int = 0,
    /** Pinch-to-zoom: called with new font size when user pinches on the editor. */
    onFontSizeChange: ((Int) -> Unit)? = null,
) {
    val colors = LocalEditorColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    // Phase X-2: EditorEvent — tags the source of every value change.
    // Only UserTyping/UserCursorMove/UserSelection have trigger authority.
    var editorEvent by remember { mutableStateOf<EditorEvent>(EditorEvent.InitialCursorPlacement(0)) }
    // Phase D: Consolidated LSP request gen counters (saves bytecode vs 8 individual remembers)
    val lspGens = remember { LspRequestGens() }
    // FIX: Focus + keyboard management — the transparent overlay intercepts taps
    // before BasicTextField sees them, so we must explicitly request focus + show
    // keyboard on every tap. Without this, the keyboard never appears after the
    // overlay consumes the gesture (the #1 bug blocking all editing).
    val focusRequester = remember { FocusRequester() }
    // focusRequester is used by the floating LSP button to maintain focus on the editor

    val vScroll = rememberScrollState()
    // P26-1: Scroll to line when scrollToLine parameter changes
    val scrollDensity = androidx.compose.ui.platform.LocalDensity.current
    // Phase E: Centralized editor metrics — replaces 30+ hard-coded fontSize * 1.25f / 72f / 0.6f locations
    val editorMetrics = rememberEditorMetrics(fontSize)
    // P50-FIX: Density-corrected line height — matches BasicTextField's sp-based lineHeight.
    // Without this, gutter rows (.dp) and text lines (.sp) drift apart when fontScale != 1.0.
    // Phase E: lineHeightDp now derived from editorMetrics (was: fontSize * 1.25f)
    val lineHeightDp = editorMetrics.lineHeightDp
    // P50-FIX: vScroll.value is in PIXELS — convert to dp before mixing with dp-based math.
    // Without this conversion, overlays (squiggles, highlights, cursors) drift to wrong lines.
    val vScrollDp = with(scrollDensity) { vScroll.value.toDp() }.value
    // Phase A: Canonical position mapper — single source of truth for offset <-> (line, column)
    val positionMapper = remember(value.text) { PositionMapper(value.text) }
    // PROBLEMS-TAB FIX: temporary gold highlight on the target line so the user can SEE
    // where the problem is after the bottom panel closes. Auto-clears after 2.5s.
    var highlightTargetLine by remember { mutableStateOf(0) }
    var highlightBlinkStart by remember { mutableStateOf(0L) }
    var blinkTick by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    // DEBUG: Visual indicator for Go to Line and Multi-cursor
    var debugJumpMsg by remember { mutableStateOf("") }
    var debugDoubleTapMsg by remember { mutableStateOf("") }
    // Blink animation: tick every 150ms while highlight is active
    LaunchedEffect(highlightBlinkStart) {
        if (highlightBlinkStart > 0) {
            while (System.currentTimeMillis() - highlightBlinkStart < 6000) {
                blinkTick++
                kotlinx.coroutines.delay(150)
            }
        }
    }
    val hScroll = rememberScrollState()
    // Reactive minimap visibility from FeatureToggleStore — toggling in Settings updates immediately
    // EDITOR-FIX: Clamp scroll positions when font size changes — prevents stuck scroll at stale boundaries
    LaunchedEffect(fontSize) {
        // Clamp horizontal scroll against new max value (content width changed with font size)
        if (hScroll.value > hScroll.maxValue) {
            hScroll.scrollTo(hScroll.maxValue)
        }
        // Clamp vertical scroll against new max value
        if (vScroll.value > vScroll.maxValue) {
            vScroll.scrollTo(vScroll.maxValue)
        }
    }
    var showMinimapState by FeatureToggleStore.state("minimap")

    // 2. Code folding state
    var foldedRanges by remember { mutableStateOf(setOf<Int>()) } // start line index (0-based)
    // P2-9 Bookmarks
    var bookmarkedLines by remember { mutableStateOf(initialBookmarks) }

    // Parse lines and folding
    // Notify parent when bookmarks change
    LaunchedEffect(bookmarkedLines) { onBookmarksChange?.invoke(bookmarkedLines) }

    val rawLines = remember(value.text) { value.text.split("\n") }
    
    // Determine which line indices are foldable
    // P26-1: Use LSP folding ranges when available (more accurate), fall back to regex
    val foldableLines = remember(rawLines, lspFoldingRanges) {
        if (lspFoldingRanges.isNotEmpty()) {
            // LSP folding ranges: List<Pair<Int,Int>> — (startLine, endLine) 0-based
            lspFoldingRanges.map { it.first }.toSet()
        } else {
            // Regex fallback: lines ending with { ( [ : or having indent increase on next line
            val set = mutableSetOf<Int>()
            for (i in rawLines.indices) {
                val line = rawLines[i].trimEnd()
                if (line.endsWith("{") || line.endsWith("(") || line.endsWith("[") || line.endsWith(":")) {
                    set.add(i)
                } else if (i < rawLines.lastIndex) {
                    val currentIndent = rawLines[i].length - rawLines[i].trimStart().length
                    val nextIndent = rawLines[i + 1].length - rawLines[i + 1].trimStart().length
                    if (nextIndent > currentIndent && rawLines[i + 1].trim().isNotEmpty()) {
                        set.add(i)
                    }
                }
            }
            set
        }
    }

    // Determine the range of folded lines
    // P26-1: Use LSP folding ranges for precise fold boundaries when available
    val foldedLineIndices = remember(foldedRanges, rawLines, lspFoldingRanges) {
        val set = mutableSetOf<Int>()
        // Build a map of fold start -> end from LSP ranges
        val lspEndMap = if (lspFoldingRanges.isNotEmpty()) {
            lspFoldingRanges.associate { it.first to it.second }
        } else null
        for (startIdx in foldedRanges) {
            if (startIdx >= rawLines.size) continue
            if (lspEndMap != null) {
                // LSP mode: use precise end line from LSP
                val endIdx = lspEndMap[startIdx] ?: startIdx
                for (j in (startIdx + 1)..endIdx) {
                    set.add(j)
                }
            } else {
                // Regex fallback: indent-based fold boundary detection
                val startIndent = rawLines[startIdx].length - rawLines[startIdx].trimStart().length
                var j = startIdx + 1
                while (j < rawLines.size) {
                    val lineTrimmed = rawLines[j].trim()
                    if (lineTrimmed.isEmpty()) {
                        set.add(j)
                        j++
                        continue
                    }
                    val indent = rawLines[j].length - rawLines[j].trimStart().length
                    if (indent > startIndent) {
                        set.add(j)
                        j++
                    } else {
                        break
                    }
                }
            }
        }
        set
    }

    // Line list to display in the gutter & editor
    val displayLines = remember(rawLines, foldedLineIndices) {
        val list = mutableListOf<Pair<Int, String>>() // Pair of (original 0-based line index, content)
        var i = 0
        while (i < rawLines.size) {
            if (foldedLineIndices.contains(i)) {
                // If this line is folded, skip it. If the previous wasn't folded or was the fold start, we can add a visual placeholder.
                // We add exactly one placeholder for a contiguous block of folded lines.
                val prevFolded = i > 0 && foldedLineIndices.contains(i - 1)
                if (!prevFolded) {
                    list.add(Pair(-1, "···"))
                }
                i++
            } else {
                list.add(Pair(i, rawLines[i]))
                i++
            }
        }
        list
    }


    // Phase F+G: Extracted to EditorDecorations.kt to stay under 64KB bytecode limit
    val (decorationStore, visualLineMapper) = rememberDecorationSetup(
        text = value.text,
        foldedLineIndices = foldedLineIndices,
        wordWrap = wordWrap,
        fontSize = fontSize,
        semanticTokens = semanticTokens,
        bookmarkedLines = bookmarkedLines,
        foldedRanges = foldedRanges,
        lspFoldingRanges = lspFoldingRanges,
    )
    // R3-C: Cause-tagged selection event helpers — atomically set value + editorEvent + log.
    // Every programmatic value mutation should go through these instead of raw value = ...
    fun programmaticCursorMove(offset: Int, reason: String) {
        val safe = offset.coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(safe))
        editorEvent = EditorEvent.ProgrammaticCursorMove(safe, reason)
        AppOutputLog.log("PROGRAMMATIC_CURSOR_MOVE: $reason -> offset $safe", "lsp")
    }
    fun programmaticTextChange(newText: String, selection: TextRange, reason: String) {
        decorationStore.shiftOnEdit(value.text, newText)
        val safeSel = TextRange(selection.start.coerceIn(0, newText.length), selection.end.coerceIn(0, newText.length))
        value = TextFieldValue(newText, safeSel)
        editorEvent = EditorEvent.ProgrammaticTextChange(newText, safeSel.end)
        onContentChange(newText)
        AppOutputLog.log("PROGRAMMATIC_TEXT_CHANGE: $reason", "lsp")
    }

    // FIX(P38): Sync external content changes (e.g. format button, file reload)
    // to the internal TextFieldValue. Without this, updating the 'content'
    // parameter from outside (like the format button updating tabs[idx].content)
    // has no effect — the editor keeps showing the old text because 'remember'
    // only initializes once.
    LaunchedEffect(content) {
        if (value.text != content) {
            programmaticCursorMove(content.length, "content_reload")
        }
    }
    // Phase R: Format Selection — format the selected text range when triggered
    LaunchedEffect(formatSelectionTrigger) {
        if (formatSelectionTrigger > 0 && value.selection.start != value.selection.end) {
            val result = doFormatSelection(
                fullText = value.text,
                selStart = value.selection.start.coerceIn(0, value.text.length),
                selEnd = value.selection.end.coerceIn(0, value.text.length),
                language = language,
                filePath = currentFilePath,
                context = context,
            )
            if (result != null) {
                programmaticTextChange(result.first, TextRange(result.second, result.third), "format_selection")
            }
        }
    }

    LaunchedEffect(scrollToLine) {
        if (scrollToLine > 0) {
            val lineHeightPx = editorMetrics.lineHeightPx
            val scrollTarget = ((scrollToLine - 1) * lineHeightPx).toInt()
            vScroll.animateScrollTo(scrollTarget.coerceAtMost(vScroll.maxValue))
            highlightTargetLine = scrollToLine
            highlightBlinkStart = System.currentTimeMillis()
            // Test 33/40 fix: Also move the cursor to the target line so that
            // clicking an error or outline entry positions the cursor there,
            // not just scrolling to it.
            // Phase A: Use positionMapper for O(1) offset lookup (was: manual loop)
            val targetLineIdx = scrollToLine - 1  // convert 1-based to 0-based
            if (targetLineIdx >= 0) {
                val clampedOffset = positionMapper.lineStart(targetLineIdx)
                programmaticCursorMove(clampedOffset, "scroll_to_line")
            }
            // Use coroutineScope so highlight cleanup survives scrollToLine being reset to 0
            coroutineScope.launch {
                kotlinx.coroutines.delay(6000)
                highlightTargetLine = 0
                highlightBlinkStart = 0L
            }
        }
    }
    val _lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    // C-5 FIX: Cached newline offsets for O(log n) line lookup instead of O(n) take().count()
    // Updated whenever the text changes. Used by completion, signature help, hover, and
    // diagnostic overlays to avoid scanning the entire text on every keystroke for large files.
    val newlineOffsets: List<Int> = remember(value.text) {
        val list = mutableListOf<Int>()
        val len = value.text.length
        var i = 0
        while (i < len) {
            if (value.text[i] == '\n') list.add(i)
            i++
        }
        list
    }

    // Phase A: Canonical position mapper — the single source of truth for
    // offset <-> (line, column) conversions. Replaces 36+ inline calculations
    // throughout this file that used take().count(), lastIndexOf(), split(), etc.

    /** Phase A: Delegates to PositionMapper — the canonical path. */
    fun lineFromOffset(offset: Int): Int = positionMapper.offsetToLine(offset)

    // P15-C: Sticky scroll — derives the "current scope" line from the scroll position.
    // Uses the line height formula: lineIdx = scrollPx / (fontSize * 1.25f).
    // Finds the nearest non-blank, non-folded ancestor line above the visible top.
    val stickyLine: String? = remember(vScroll.value, rawLines, foldedLineIndices, fontSize, lspDocumentSymbols) {
        if (rawLines.size < 3) return@remember null
        val lineHeightPx = editorMetrics.lineHeightPx
        val topLineIdx = (vScroll.value / lineHeightPx).toInt()
        // R3-5: Use LSP document symbols for sticky scroll (falls back to heuristic)
        if (lspDocumentSymbols != null && lspDocumentSymbols.length() > 0) {
            val stickySymbol = findStickySymbolFromLSP(lspDocumentSymbols, topLineIdx, rawLines)
            if (stickySymbol != null) return@remember stickySymbol
        }
        // Fallback: heuristic — walk upward to find nearest scope-opening line
        var i = (topLineIdx - 1).coerceIn(0, rawLines.lastIndex)
        while (i >= 0) {
            if (!foldedLineIndices.contains(i)) {
                val trimmed = rawLines[i].trimEnd()
                if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith(":")) {
                    return@remember rawLines[i].take(80)
                }
            }
            i--
        }
        null
    }

    val prefix = remember(value) { currentWord(value.text, value.selection.end) }
    // P33-INTELLISENSE: Detect dot context (e.g. "lines." "user.")
    // Phase X-4: Renamed from isDotContext → isDotContext (pure state, NOT a trigger).
    // dotWasTyped (below) is the event-derived trigger that only fires when the user
    // actually types a dot — not when a file opens or cursor moves to an existing dot.
    val isDotContext = remember(value) {
        val cursor = value.selection.end.coerceAtMost(value.text.length)
        cursor > 0 && value.text.getOrElse(cursor - 1) { ' ' } == '.'
    }
    // Phase X-4: "user just typed a dot" — only true on UserTyping events where the
    // inserted character was a dot. Distinguishes from isDotContext which is true
    // whenever the cursor happens to be after a dot (file open, cursor move, etc.).
    val dotWasTyped = editorEvent is EditorEvent.UserTyping && isDotContext
    val completions = remember(prefix, language) { completionsFor(prefix, language) }
    var showCompletions by remember { mutableStateOf(false) }
    // NEW (2026-08-10): Resizable completion popup — drag bottom edge to grow/shrink, like VS Code.
    // Extra height added on top of the default max height (220dp), clamped to available screen space.
    var completionPopupExtraHeightDp by remember { mutableStateOf(0f) }
    // P41-I: Active snippet edit session — when non-null, Tab/Shift+Tab cycles tab-stops
    var snippetSession by remember { mutableStateOf<SnippetSession?>(null) }
    var showSnippetChoices by remember { mutableStateOf(false) }  // P41-I: choice dropdown visibility
    // P41-J: Filter chip state — null = show all, non-null = filter by source
    var completionFilter by remember { mutableStateOf<CompletionSource?>(null) }
    // P41-J: Sticky selection — remember last highlighted label
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    // P41-K: In-memory resolve cache — avoids re-resolving already-resolved items
    var resolveCache by remember { mutableStateOf<Map<String, LspCompletionItem>>(emptyMap()) }
    var lastResolvedLabel by remember { mutableStateOf<String?>(null) }
    // P41-K: Track LSP request ID for cancellation
    var lspRequestId by remember { mutableStateOf<Long>(-1L) }
    // P41-M: Call Hierarchy state
    var showCallHierarchy by remember { mutableStateOf(false) }
    var callHierarchyRoot by remember { mutableStateOf<CallHierarchyItem?>(null) }
    var callHierarchyIncoming by remember { mutableStateOf<List<IncomingCall>>(emptyList()) }
    var callHierarchyOutgoing by remember { mutableStateOf<List<OutgoingCall>>(emptyList()) }
    // P41-M: Type Hierarchy state
    var showTypeHierarchy by remember { mutableStateOf(false) }
    var typeHierarchyRoot by remember { mutableStateOf<TypeHierarchyItem?>(null) }
    var typeHierarchySupertypes by remember { mutableStateOf<List<TypeHierarchyItem>>(emptyList()) }
    var typeHierarchySubtypes by remember { mutableStateOf<List<TypeHierarchyItem>>(emptyList()) }
    // P41-J: Detail panel — track the highlighted item's full doc
    var detailDoc by remember { mutableStateOf<String?>(null) }
    var detailDetail by remember { mutableStateOf<String?>(null) }
    var detailLabel by remember { mutableStateOf<String?>(null) }

    // P39: Lightbulb state — tracks code actions per line for gutter display
    var lightbulbLine by remember { mutableStateOf(-1) }
    var lightbulbActions by remember { mutableStateOf<List<com.codespace.ide.lsp.LspCodeAction>>(emptyList()) }
    var showLightbulbMenu by remember { mutableStateOf(false) }
    // P39/X-9: Async-fetch code actions when cursor moves to a new line (debounced 500ms)
    // Phase X-9: Gate on editorEvent — do not trigger on file open/switch/programmatic.
    LaunchedEffect(value.selection.start, editorEvent) {
        if (lspCodeActionProvider != null) {
            if (!editorEvent.shouldTriggerCodeActions) return@LaunchedEffect
            kotlinx.coroutines.delay(300L)
            val cursorLine = positionMapper.offsetToLine(value.selection.start)
            try {
                val actions = lspCodeActionProvider.invoke(cursorLine)
                if (actions.isNotEmpty()) {
                    lightbulbLine = cursorLine
                    lightbulbActions = actions
                } else {
                    lightbulbLine = -1
                    lightbulbActions = emptyList()
                }
            } catch (_: Exception) {
                lightbulbLine = -1
                lightbulbActions = emptyList()
            }
        } else {
            lightbulbLine = -1
            lightbulbActions = emptyList()
        }
    }

    // P41-G: Path Completion — detect if cursor is inside a path-like string (import/from/require)
    // When path context is active, ONLY show path completions (no keyword mixing)
    val pathContext = remember(value.text, value.selection.end) {
        if (prefix.isNotEmpty()) {
            PathCompletionProvider.detectPathContext(
                text = value.text,
                cursor = value.selection.end,
                language = language,
                currentFilePath = currentFilePath,
                projectRoot = projectRoot,
            )
        } else null
    }
    var pathCompletions by remember { mutableStateOf<List<com.codespace.ide.lsp.LspCompletionItem>>(emptyList()) }

    // P41-V: Context-aware completion detection
    val completionContext = remember(value.text, value.selection.end, language) {
        com.codespace.ide.lsp.CompletionContextDetector.detect(
            text = value.text,
            cursor = value.selection.end,
            language = language,
        )
    }
    LaunchedEffect(pathContext) {
        if (pathContext != null) {
            pathCompletions = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    if (pathContext.isModule) {
                        PathCompletionProvider.listNodeModules(projectRoot, pathContext.prefix)
                    } else {
                        PathCompletionProvider.listPathCompletions(pathContext)
                    }
                } catch (_: Exception) { emptyList() }
            }
        } else {
            pathCompletions = emptyList()
        }
    }


    // P22-H: LSP-backed completion (P41-K: parallel fetch + request cancellation)
    var lspCompletions by remember { mutableStateOf<List<LspCompletionItem>>(emptyList()) }
    // Smart completion: track whether LSP has successfully responded for this session
    var lspHasResponded by remember { mutableStateOf(false) }
    // Smart completion: track whether the current LSP request timed out
    var lspTimedOut by remember { mutableStateOf(false) }
    // smartCompletion defined here so lspCompletionLoading can reference it
    val smartCompletion = ProjectSettingsStore.smartCompletionEnabled.value
    // Loading indicator: true while waiting for LSP completion response
    val lspCompletionLoading by remember { derivedStateOf { smartCompletion && !lspHasResponded && !lspTimedOut } }
    // P41-F: Workspace symbol completions (fetched in parallel with LSP — see below)
    var workspaceCompletions by remember { mutableStateOf<List<com.codespace.ide.lsp.LspCompletionItem>>(emptyList()) }

    // R3-LSP: Recovery watcher — when LspManager reports a server transitioned to READY
    // (after being UNHEALTHY/RESTARTING), reset the completion fallback flags so the
    // next completion request tries LSP first again. The 5-second timeout still applies
    // as the guard — this just prevents the fallback from being permanent for the session.
    LaunchedEffect(language) {
        var lastSeen = 0
        while (true) {
            kotlinx.coroutines.delay(2000) // poll every 2s
            val current = com.codespace.ide.lsp.LspManager.lspRecoveryCounter
            if (current > lastSeen) {
                lastSeen = current
                // LSP server recovered — reset fallback so next request tries LSP first
                if (lspTimedOut || !lspHasResponded) {
                    lspTimedOut = false
                    lspHasResponded = false
                    lspCompletions = emptyList()
                    workspaceCompletions = emptyList()
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] recovery triggered, resetting fallback flags (counter=$current)", "lsp")
                }
            }
        }
    }
    // P41-Q: Completion caching — cache LSP results to avoid redundant requests when prefix extends
    var cachedLspPrefix by remember { mutableStateOf("") }
    var cachedLspResults by remember { mutableStateOf<List<LspCompletionItem>>(emptyList()) }
    var cachedLspCursorLine by remember { mutableStateOf(-1) }
    // Phase X-3: Completion trigger gated by editorEvent — only UserTyping can trigger.
    // Context detection (prefix, isDotContext, completionContext) still recomputes freely
    // (pure, cheap) — they feed display/filtering. But the LSP request only fires when
    // the user actually typed something, not on file open, cursor move, or selection.
    LaunchedEffect(prefix, isDotContext, value.selection.end, pathContext, editorEvent) {
        // P41-G: Skip LSP completions when path context is active
        if (pathContext != null) { lspCompletions = emptyList(); workspaceCompletions = emptyList(); return@LaunchedEffect }

        // Phase X-3: Block completion on non-user events
        if (!editorEvent.shouldTriggerCompletion) {
            AppOutputLog.log("[EDITOR] COMPLETION_TRIGGER blocked=${editorEvent.logTag}", "lsp")
            return@LaunchedEffect
        }
        AppOutputLog.log("[EDITOR] COMPLETION_TRIGGER allowed=true", "lsp")
        if (isDotContext) { AppOutputLog.log("[EDITOR] DOT_CONTEXT=true", "lsp") }
        if (dotWasTyped) { AppOutputLog.log("[EDITOR] DOT_TYPED=true", "lsp") }

        if ((prefix.length >= 2 || isDotContext || (completionContext.context == com.codespace.ide.lsp.CompletionContextDetector.CompletionContext.IMPORT_CONTEXT && prefix.length >= 1)) && lspCompletionProvider != null) {  // TEST-14-FIX: also trigger LSP for 1-char import context
            kotlinx.coroutines.delay(150)  // debounce
            kotlinx.coroutines.delay(70)   // R3-A: show delay — reduce flicker on rapid typing

            // P41-K: Cancel any previous in-flight completion request before sending new one
            // Phase X-8: Also increment generation counter for stale-response protection
            lspGens.completion++
            val myCompGen = lspGens.completion
            val myCompServerGen = com.codespace.ide.lsp.LspManager.getServerGeneration(language)
            if (lspRequestId >= 0 && lspCancellationProvider != null) {
                try { lspCancellationProvider.invoke(lspRequestId) } catch (_: Exception) {}
                lspRequestId = -1L
            }

            val cOff = value.selection.end
            val cPos = positionMapper.offsetToPosition(cOff)
            val cLine = cPos.line
            val cCol = cPos.column

            // P41-K: Track request ID for cancellation
            if (lspRequestIdProvider != null) {
                try { lspRequestId = lspRequestIdProvider.invoke() } catch (_: Exception) {}
            }
            // Phase X-4/X-13: Log triggerKind for dot-triggered completion
            if (dotWasTyped) {
                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] COMPLETION triggerKind=2 triggerCharacter=.", "lsp")
            } else {
                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] COMPLETION triggerKind=1 (Invoked)", "lsp")
            }

            // Smart completion: LSP first with 5s timeout, then regex fallback
            if (smartCompletion) {
                val wasInFallback = lspTimedOut || !lspHasResponded
                lspTimedOut = false
                val results = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        val lsp = try { lspCompletionProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                        val ws = if (lspWorkspaceSymbolProvider != null && prefix.length >= 3) {
                            try { lspWorkspaceSymbolProvider.invoke(prefix).take(50) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                        } else emptyList()
                        Pair(lsp, ws)
                    }
                }
                if (results != null) {
                    // Phase X-8: Stale check — discard if a newer completion request was made
                    if (myCompGen != lspGens.completion) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale request-gen for completion", "lsp")
                        return@LaunchedEffect
                    }
                    if (myCompServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale generation for completion", "lsp")
                        return@LaunchedEffect
                    }
                    lspHasResponded = true
                    lspCompletions = results.first
                    workspaceCompletions = results.second
                    // R3-LSP: Log restoration when LSP responds after being in fallback
                    if (wasInFallback) {
                        com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] completion restored after recovery", "lsp")
                    }
                } else {
                    // LSP timed out — keep showing local completions as fallback
                    lspTimedOut = true
                    lspHasResponded = false // R3-LSP: clear stale "responded" state on timeout
                    lspCompletions = emptyList()
                    workspaceCompletions = emptyList()
                    com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] completion timed out, using regex fallback", "lsp")
                }
            } else {
                // Legacy behavior: fetch LSP without timeout, show alongside local
                val results = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val lsp = try { lspCompletionProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                    val ws = if (lspWorkspaceSymbolProvider != null && prefix.length >= 3) {
                        try { lspWorkspaceSymbolProvider.invoke(prefix).take(50) } catch (_: Exception) { emptyList<LspCompletionItem>() }
                    } else emptyList()
                    Pair(lsp, ws)
                }
                // Phase X-8: Stale check for legacy path too
                if (myCompGen != lspGens.completion) {
                    com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale request-gen for completion", "lsp")
                    return@LaunchedEffect
                }
                if (myCompServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
                    com.codespace.ide.diagnostics.AppOutputLog.log("LSP result discarded: stale generation for completion", "lsp")
                    return@LaunchedEffect
                }
                lspCompletions = results.first
                workspaceCompletions = results.second
            }
        } else {
            lspCompletions = emptyList()
            workspaceCompletions = emptyList()
        }
    }
    // P41 Phase A: Use CompletionEngine for fuzzy matching + ranking
    val allCompletions = remember(completions, lspCompletions, workspaceCompletions, pathCompletions, pathContext, prefix, completionContext, smartCompletion, lspHasResponded, lspTimedOut) {
        // P41-V: Context-aware filtering
        // In member-access or after-keyword context, suppress keyword/buffer completions
        val suppressKeywords = completionContext.lspOnly
        // Smart completion: when LSP has responded, suppress local/regex completions
        // But if LSP timed out or hasn't responded yet, show local as fallback
        val suppressLocalSmart = smartCompletion && lspHasResponded && lspCompletions.isNotEmpty()
        // Convert local completions to RankedCompletionItem (filtered by context)
        val localRanked = (if (suppressKeywords || suppressLocalSmart) emptyList() else completions).map { c ->
            val kind = when (c.kind) {
                CompletionKind.SNIPPET -> CompletionItemKind.SNIPPET
                CompletionKind.TYPE -> CompletionItemKind.CLASS
                CompletionKind.KEYWORD -> CompletionItemKind.KEYWORD
            }
            RankedCompletionItem(
                label = c.label, kind = kind, detail = c.doc,
                insertText = c.insertText, source = if (c.kind == CompletionKind.SNIPPET) CompletionSource.SNIPPET else CompletionSource.BUFFER,
            )
        }
        // P41-G: If path context is active, ONLY show path completions (skip keywords/LSP)
        if (pathContext != null && pathCompletions.isNotEmpty()) {
            val pathRanked = pathCompletions.map { item ->
                RankedCompletionItem(
                    label = item.label, kind = item.kind, detail = item.detail,
                    insertText = item.insertText, source = CompletionSource.PATH,
                )
            }
            // Path completions don't need fuzzy ranking — already filtered by prefix
            return@remember pathRanked.take(50).map { rc ->
                Completion(rc.label, CompletionKind.KEYWORD, rc.insertText, rc.detail,
                    source = rc.source, lspKind = rc.kind)
            }
        }
        // Convert LSP completions to RankedCompletionItem (P41-D: include additionalTextEdits for auto-import)
        val lspRanked = lspCompletions.map { item ->
            RankedCompletionItem(
                label = item.label, kind = item.kind, detail = item.detail,
                insertText = item.insertText, source = CompletionSource.LSP,
                additionalTextEditsJson = item.additionalTextEditsJson,
                textEditJson = item.textEditJson,
                insertTextFormat = item.insertTextFormat,
                sortTextFromServer = item.sortText,
                filterText = item.filterText,
                command = item.command,
                commitCharacters = item.commitCharacters,
            )
        }
        // P41-F: Convert workspace symbol completions to RankedCompletionItem
        val workspaceRanked = workspaceCompletions.map { item ->
            RankedCompletionItem(
                label = item.label, kind = item.kind, detail = item.detail,
                insertText = item.insertText, source = CompletionSource.WORKSPACE,
            )
        }
        // Merge, deduplicate by (label, kind, detail) — Phase U-7
        // Don't drop two semantically different items that happen to share the same label
        // LSP first (highest priority), then local, then workspace (lower priority, cross-file)
        val seen = mutableSetOf<Triple<String, Int, String?>>()
        val merged = (lspRanked + localRanked + workspaceRanked).filter { item ->
            val key = Triple(item.label, item.kind, item.detail)
            if (key in seen) false else { seen.add(key); true }
        }
        var ranked = rank(merged, prefix, CompletionHistoryStore.mruMap(), CompletionHistoryStore.usageMap())
        // P41-V: Context-aware kind boosting
        if (completionContext.boostKind > 0 || completionContext.nonMatchKindPenalty > 0f) {
            ranked = ranked.map { item ->
                val kindBoost = if (item.kind == completionContext.boostKind) 15f else 0f
                val kindPenalty = if (item.kind != completionContext.boostKind && completionContext.nonMatchKindPenalty > 0f) -completionContext.nonMatchKindPenalty else 0f
                item.copy(score = item.score + kindBoost + kindPenalty)
            }.sortedByDescending { it.score }
        }
        // vscode.dev Test #6: Keywords ranked ABOVE variables/imports when matching prefix
        // (matches VS Code behavior where keywords appear first in general context)
        if (!completionContext.lspOnly && !isDotContext) {
            ranked = ranked.map { item ->
                if (item.kind == CompletionItemKind.KEYWORD) item.copy(score = item.score + 8f) else item
            }.sortedByDescending { it.score }
        }
        // P41-V: In lspOnly context (member access, after keyword), suppress non-LSP items
        if (completionContext.lspOnly) {
            ranked = ranked.filter { it.source == com.codespace.ide.lsp.CompletionSource.LSP || it.source == com.codespace.ide.lsp.CompletionSource.AI }
        }
        // Map back to Completion for the existing dropdown UI
        // D3/D1-EXPANSION: raised from 15 to 60 to match VS Code parity (scrollable list, not truncated)
        ranked.take(60).map { rc ->
            val kind = when (rc.kind) {
                CompletionItemKind.SNIPPET -> CompletionKind.SNIPPET
                in 2..13 -> CompletionKind.TYPE
                22, 23 -> CompletionKind.TYPE
                else -> CompletionKind.KEYWORD
            }
            // P41-D: Pass through auto-import edits + textEdit for apply-on-accept
            // Phase U-4/U-5: Pass through command + commitCharacters
            Completion(rc.label, kind, rc.insertText, rc.detail,
                additionalTextEditsJson = rc.additionalTextEditsJson,
                textEditJson = rc.textEditJson,
                source = rc.source,
                isDeprecated = rc.isDeprecated,
                lspKind = rc.kind,
                insertTextFormat = rc.insertTextFormat,
                command = rc.command,
                commitCharacters = rc.commitCharacters)
        }
    }
    // P41 Phase B: Load completion history once per file open
    LaunchedEffect(Unit) { CompletionHistoryStore.load(context) }

    LaunchedEffect(prefix, isDotContext, allCompletions, pathContext, completionContext, editorEvent) {
        // Phase X-3: Suppress completions on non-user events
        if (!editorEvent.shouldTriggerCompletion) {
            showCompletions = false
            completionFilter = null; selectedLabel = null; detailDoc = null; detailDetail = null; detailLabel = null
            return@LaunchedEffect
        }
        // P41-V: Context-aware suppression
        if (!completionContext.shouldShowCompletions) {
            showCompletions = false
        } else if (pathContext != null) {
            // P41-G: Path completions show even with 1-char prefix
            showCompletions = allCompletions.isNotEmpty()
        } else {
            showCompletions = (prefix.length >= 1 || isDotContext || completionContext.context == com.codespace.ide.lsp.CompletionContextDetector.CompletionContext.MEMBER_ACCESS || completionContext.context == com.codespace.ide.lsp.CompletionContextDetector.CompletionContext.IMPORT_CONTEXT) && allCompletions.isNotEmpty()
        }
        if (!showCompletions) { completionFilter = null; selectedLabel = null; detailDoc = null; detailDetail = null; detailLabel = null }
    }

    // P41-K: Lazy resolve — when user highlights an LSP item, resolve its full docs/detail (150ms debounce)
    LaunchedEffect(selectedLabel, showCompletions) {
        if (!showCompletions || selectedLabel == null) { lastResolvedLabel = null; return@LaunchedEffect }
        // Only resolve LSP-sourced items not already in cache
        if (resolveCache.containsKey(selectedLabel)) { return@LaunchedEffect }
        if (lspCompletionResolver == null) { return@LaunchedEffect }

        // Find the LSP item matching the selected label
        val lspItem = lspCompletions.find { it.label == selectedLabel } ?: return@LaunchedEffect

        kotlinx.coroutines.delay(150)  // debounce — only resolve after user pauses on an item
        val resolved = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try { lspCompletionResolver.invoke(lspItem) } catch (_: Exception) { null }
        }
        if (resolved != null) {
            resolveCache = resolveCache + (selectedLabel!! to resolved)
            lastResolvedLabel = selectedLabel
            // Update detail panel if still showing this item
            if (selectedLabel == resolved.label && resolved.documentation?.isNotBlank() == true) {
                detailDoc = resolved.documentation
            }
        }
    }

    // P41-E: Multi-line ghost text — shows top completion OR AI suggestion as dimmed text
    // Source 1: IntelliSense (fuzzy-matched top completion, single line from insertText)
    // Source 2: AI ghost text (multi-line code continuation, 600ms debounce)
    var ghostText by remember { mutableStateOf<String?>(null) }
    var ghostTextLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var ghostTextIsAi by remember { mutableStateOf(false) }

    // IntelliSense ghost text (existing behavior, 800ms debounce)
    LaunchedEffect(prefix, isDotContext, allCompletions, completionContext) {
        ghostText = null
        ghostTextLines = emptyList()
        ghostTextIsAi = false
        // P41-V: Suppress ghost text in string/comment context
        if (!completionContext.shouldShowCompletions) return@LaunchedEffect
        if ((prefix.length >= 2 || isDotContext) && allCompletions.isNotEmpty()) {
            kotlinx.coroutines.delay(800L)
            val top = allCompletions.firstOrNull()
            if (top != null) {
                val score = fuzzyScore(prefix, top.label)
                if (score > 0f && top.insertText.startsWith(prefix, ignoreCase = true)) {
                    val remainder = top.insertText.removePrefix(prefix)
                    ghostText = remainder.lines().firstOrNull() ?: ""
                    ghostTextLines = remainder.lines()
                    ghostTextIsAi = false
                }
            }
        }
    }

    // P41-E/P41-L: AI ghost text — debounced 600ms idle after typing stops
    // Only fires when there's NO IntelliSense ghost text already showing
    // P41-L: Context-aware prompt framing — detects cursor context and appends a hint
    LaunchedEffect(value.text, value.selection.end) {
        if (toggles.showGhostText && onAiGhostTextRequest != null && ghostText == null) {
            kotlinx.coroutines.delay(600L)
            val cursor = value.selection.end
            if (cursor == value.selection.start && cursor > 0) {
                val text = value.text
                val contextBefore = text.substring(0, cursor)
                val contextAfter = text.substring(cursor)

                // P41-L: Detect cursor context for prompt framing
                val currentLine = text.substring(0, cursor).substringAfterLast('\n')
                val lastNonWhitespaceBefore = contextBefore.trimEnd().lastOrNull()
                val contextHint = when {
                    // File scope: cursor is at a top-level position (no indentation, after blank line or at start)
                    currentLine.isBlank() && (contextBefore.isBlank() || contextBefore.trimEnd().endsWith('\n')) -> {
                        "FILE_SCOPE"
                    }
                    // After a closing brace — likely starting a new block/function
                    lastNonWhitespaceBefore == '}' || lastNonWhitespaceBefore == ')' -> {
                        "AFTER_BLOCK_CLOSE"
                    }
                    // Mid-statement: there's content on the line before the cursor
                    currentLine.isNotBlank() -> {
                        "MID_STATEMENT"
                    }
                    // Default: inside a block but on a new line
                    else -> "NEW_LINE_IN_BLOCK"
                }

                val aiResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // P41-L: Pass context hint as 4th parameter via a wrapper
                        // The existing onAiGhostTextRequest signature takes (contextBefore, contextAfter, language)
                        // We embed the context hint in contextBefore as a trailing comment line
                        val hintPrefix = when (contextHint) {
                            "FILE_SCOPE" -> "// [AI_CONTEXT: FILE_SCOPE — predict next top-level declaration]"
                            "AFTER_BLOCK_CLOSE" -> "// [AI_CONTEXT: AFTER_BLOCK_CLOSE — predict next statement/block]"
                            "MID_STATEMENT" -> "// [AI_CONTEXT: MID_STATEMENT — complete the current statement]"
                            else -> "// [AI_CONTEXT: NEW_LINE_IN_BLOCK — predict next statement inside block]"
                        }
                        onAiGhostTextRequest.invoke(contextBefore + "\n" + hintPrefix, contextAfter, language.name)
                    }
                    catch (_: Exception) { null }
                }
                if (aiResult != null && aiResult.isNotBlank()) {
                    // P41-L: Strip any context hint comment that the AI might echo back
                    val cleanedResult = aiResult.lines().filterNot {
                        it.contains("[AI_CONTEXT:")
                    }.joinToString("\n")
                    val finalResult = cleanedResult.ifBlank { aiResult }
                    ghostText = finalResult.lines().firstOrNull() ?: ""
                    ghostTextLines = finalResult.lines()
                    ghostTextIsAi = true
                }
            }
        }
    }

    // ── P2-12 / P25-LSP Parameter hints / signature help ────────────────────
    // Prefer LSP signature help when available (knows ALL functions in the codebase),
    // fall back to the local curated signature DB (SignatureHelpAnalyzer) otherwise.
    // P35 FIX: Pre-check if cursor is inside parentheses before calling LSP.
    // The old code called the LSP provider on every text change — even when the cursor
    // wasn't inside a function call — wasting CPU and spamming the LSP server.
    // Quick local check: scan backwards for unmatched '(' before the cursor.
    // Phase X-6: Split signature help into pure detection + async LSP request.
    // OLD: blocking LSP call inside remember() — ran on main thread during recomposition.
    // NEW: remember() only computes isInsideCall (pure boolean). LSP call is in LaunchedEffect.
    val isInsideCall = remember(value.text, value.selection.end) {
        val textBeforeCursor = value.text.substring(0, value.selection.end.coerceAtMost(value.text.length))
        var depth = 0
        var inside = false
        for (ch in textBeforeCursor.reversed()) {
            when (ch) {
                ')' -> depth++
                '(' -> { if (depth == 0) { inside = true }; break }
            }
        }
        inside
    }
    var activeSignature by remember { mutableStateOf<SignatureInfo?>(null) }
    // Phase X-6: Async LSP request — only on user events with trigger authority.
    LaunchedEffect(isInsideCall, value.selection.end, language, editorEvent) {
        if (!isInsideCall) {
            activeSignature = null
            return@LaunchedEffect
        }
        // Block on non-user events (file open, switch, programmatic)
        if (!editorEvent.shouldTriggerSignatureHelp) {
            // Still compute local signature analysis (pure, no LSP)
            activeSignature = SignatureHelpAnalyzer.findActiveCall(value.text, value.selection.end, language)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(200) // debounce
        // Phase X-8: Stale response protection
        lspGens.signatureHelp++
        val myGen = lspGens.signatureHelp
        val mySigServerGen = com.codespace.ide.lsp.LspManager.getServerGeneration(language)
        val cOff = value.selection.end
        val cPos = positionMapper.offsetToPosition(cOff)
        val cLine = cPos.line
        val cCol = cPos.column
        val result = if (lspSignatureHelpProvider != null) {
            try { lspSignatureHelpProvider.invoke(cLine, cCol) } catch (_: Exception) { null }
                ?: SignatureHelpAnalyzer.findActiveCall(value.text, cOff, language)
        } else {
            SignatureHelpAnalyzer.findActiveCall(value.text, cOff, language)
        }
        // Stale check: if generation changed while we were waiting, discard
        if (myGen != lspGens.signatureHelp) {
            AppOutputLog.log("LSP result discarded: stale request-gen for signature-help", "lsp")
            return@LaunchedEffect
        }
        if (mySigServerGen != com.codespace.ide.lsp.LspManager.getServerGeneration(language)) {
            AppOutputLog.log("LSP result discarded: stale generation for signature-help", "lsp")
            return@LaunchedEffect
        }
        activeSignature = result
    }

    // P41-R: Overload navigation — track which signature overload is selected
    var overloadIndex by remember { mutableStateOf(0) }

    // Reset overload index when the active function call context changes
    LaunchedEffect(activeSignature?.name) {
        overloadIndex = 0
    }

    // ── Find References state ─────────────────────────────────────────────
    var findRefWord by remember { mutableStateOf<String?>(null) }
    var findRefResults by remember { mutableStateOf<List<Triple<String, Int, String>>>(emptyList()) }
    var findRefLoading by remember { mutableStateOf(false) }

    // ── Rename Symbol state ────────────────────────────────────────────────
    var renameDialogWord by remember { mutableStateOf<String?>(null) }  // null = closed
    var renameNewName by remember { mutableStateOf("") }
    var renameCount by remember { mutableStateOf(0) }
    // P18-C — Cross-file rename
    var renameProjectWide by remember { mutableStateOf(false) }
    var renameCrossFileCount by remember { mutableStateOf(0) }
    var renameInProgress by remember { mutableStateOf(false) }
    var renameUsedLsp by remember { mutableStateOf(false) }
    // P39-FULL: Rename preview state
    var renamePreviewEdit by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var renamePreviewFiles by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    // ── P2-4 Go to Definition state ──────────────────────────────────────────────────────
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var crossFileResults by remember { mutableStateOf<List<CrossFileDefResult>?>(null) }
    var gotoResults by remember { mutableStateOf<List<DefResult>?>(null) }
    // P22-L: Peek Definition result — inline code preview (class moved to top-level)
    var peekDefResult by remember { mutableStateOf<PeekDefResult?>(null) }

    // R2-1/R2-2: Undo/redo manager — snapshot-based O(1) undo/redo stack.
    // MUST be declared before the keyboard toolbar handler (LaunchedEffect below)
    // which references snapshotUndo and undoRedoInProgress for toolbar undo/redo.
    val snapshotUndo = remember { com.codespace.ide.editor.undo.SnapshotUndoManager() }
    var undoRedoInProgress by remember { mutableStateOf(false) }

    // ── Keyboard toolbar insert handler ──────────────────────────────────────
    // Registers a function that the coding toolbar (Tab, Esc, {, }, etc.) can call
    // to insert text at the cursor position, as if the user typed it on a real keyboard.
    // P-EXTRAKEYS: Use rememberUpdatedState so the insert handler lambda always
    // calls the LATEST onContentChange — even though LaunchedEffect(Unit) only runs
    // once. Without this, switching tabs could cause the handler to call a stale
    // onContentChange that updates the wrong tab, and LaunchedEffect(content) would
    // then reset value back to the old text, making the inserted character vanish.
    val currentOnContentChange by rememberUpdatedState(onContentChange)
    val currentOnInsertHandler by rememberUpdatedState(onInsertHandler)
    // ── Multi-cursor state ───────────────────────────────────────────────
    // Moved here (before LaunchedEffect) so the Esc key handler can reference it.
    var extraCursors by remember { mutableStateOf<List<Int>>(emptyList()) }
    // P22-K: Back press clears extra cursors (mobile equivalent of Escape)
    androidx.activity.compose.BackHandler(enabled = extraCursors.isNotEmpty()) {
        extraCursors = emptyList()
    }
    LaunchedEffect(Unit) {
        currentOnInsertHandler?.invoke { text ->
            // P-EXTRAKEYS: Ensure editor has focus so BasicTextField processes the
            // programmatic value update. Without focus, BasicTextField in Compose
            // 1.6.x may not render programmatic value changes.
            if (text != "Esc") {
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            when (text) {
                "\u21A9", "\u21AA" -> {
                    handleToolbarUndoRedo(
                        key = text,
                        snapshotUndo = snapshotUndo,
                        value = value,
                        extraCursors = extraCursors,
                        onUndoRedoStart = { undoRedoInProgress = true },
                        onUndoRedoEnd = { undoRedoInProgress = false },
                        onTextChange = { newText, sel, reason -> programmaticTextChange(newText, sel, reason) },
                        onExtraCursorsChange = { extraCursors = it }
                    )
                }
                "Esc" -> {
                    snippetSession = null
                    showSnippetChoices = false
                    showCompletions = false
                    showCallHierarchy = false
                    showTypeHierarchy = false
                    overloadIndex = 0
                    findRefWord = null
                    peekDefResult = null
                    // FIX: Esc must also clear multi-cursors (mobile equivalent of Escape)
                    extraCursors = emptyList()
                }
                "Tab" -> {
                    if (snippetSession != null) {
                        val session = snippetSession!!
                        val next = session.advance()
                        if (next != null) {
                            snippetSession = next
                            showSnippetChoices = next.activeStop()?.choices?.isNotEmpty() == true
                            val stopRange = next.activeStopRange()
                            if (stopRange != null) {
                                programmaticCursorMove(stopRange.first, "snippet_tab_stop")
                            }
                        } else {
                            programmaticCursorMove(session.finalCursorOffset, "snippet_final")
                            snippetSession = null
                        }
                    } else if (!showCompletions) {
                        // P-EXTRAKEYS: Respect selection like a laptop Tab key.
                        // Multi-line selection → indent all lines; single-line → replace selection.
                        val selStart = value.selection.min
                        val selEnd = value.selection.max
                        if (selStart != selEnd) {
                            val multiResult = applyMultiLineIndent(value, positionMapper)
                            if (multiResult != null) {
                                value = multiResult.first
                                currentOnContentChange(multiResult.second)
                            } else {
                                val singleResult = applySingleLineTab(value)
                                if (singleResult != null) {
                                    value = singleResult.first
                                    currentOnContentChange(singleResult.second)
                                }
                            }
                        } else {
                            val cursor = selStart
                            var wordStart = cursor
                            while (wordStart > 0 && (value.text[wordStart - 1].isLetterOrDigit() || value.text[wordStart - 1] == '_')) {
                                wordStart--
                            }
                            val singleWord = value.text.substring(wordStart, cursor)
                            var twoWordStart = wordStart
                            if (wordStart > 0 && value.text[wordStart - 1] == ' ') {
                                var ws = wordStart - 1
                                while (ws > 0 && value.text[ws - 1] == ' ') ws--
                                val prevWordEnd = ws
                                if (prevWordEnd > 0 && (value.text[prevWordEnd - 1].isLetterOrDigit() || value.text[prevWordEnd - 1] == '_')) {
                                    var prevWordStart = prevWordEnd
                                    while (prevWordStart > 0 && (value.text[prevWordStart - 1].isLetterOrDigit() || value.text[prevWordStart - 1] == '_')) prevWordStart--
                                    twoWordStart = prevWordStart
                                }
                            }
                            val twoWord = if (twoWordStart < wordStart) value.text.substring(twoWordStart, cursor) else singleWord
                            if (singleWord.isNotEmpty()) {
                                val localSnippets = snippetsFor(language)
                                val matched = localSnippets.firstOrNull { it.label == twoWord }
                                    ?: localSnippets.firstOrNull { it.label == singleWord }
                                    ?: localSnippets.firstOrNull { it.label.startsWith(singleWord) && singleWord.length >= 3 }
                                if (matched != null) {
                                    val expandStart = if (matched.label == twoWord) twoWordStart else wordStart
                                    val snippetText = matched.insertText
                                    if (matched.insertTextFormat == 2) {
                                        val parsed = parseSnippet(snippetText, SnippetContext(
                                            lineNumber = positionMapper.offsetToLine(expandStart) + 1,
                                            lineIndex = positionMapper.offsetToLine(expandStart),
                                            currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(expandStart)),
                                            selectedText = "",
                                        ))
                                        val cleanedText = parsed.cleanedText
                                        val finalText = value.text.substring(0, expandStart) + cleanedText + value.text.substring(cursor)
                                        val session = createSnippetSession(expandStart, parsed)
                                        snippetSession = session
                                        showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                        val firstStop = session.tabStops.firstOrNull()
                                        val selRange = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                            TextRange(firstStop.startOffset, firstStop.endOffset)
                                        } else {
                                            TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                        }
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, finalText, extraCursors)
                                        programmaticTextChange(finalText, selRange, "snippet_multicursor")
                                    } else {
                                        val newText = value.text.substring(0, expandStart) + snippetText + value.text.substring(cursor)
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(expandStart, expandStart, snippetText.length)), "snippet_expand")
                                    }
                                } else {
                                    val newText = value.text.substring(0, cursor) + "\t" + value.text.substring(cursor)
                                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                    programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(cursor, cursor, 1)), "tab_insert_snippet")
                                }
                            } else {
                                val newText = value.text.substring(0, cursor) + "\t" + value.text.substring(cursor)
                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(cursor, cursor, 1)), "tab_insert")
                            }
                        }
                    }
                }
                else -> {
                    // P-EXTRAKEYS: Act like a laptop key — insert at cursor, replace selection.
                    // selStart/selEnd handle both collapsed cursor (no selection) and
                    // active text selection (replace the selected text with the typed key).
                    val selStart = value.selection.min
                    val selEnd = value.selection.max
                    // P-BRACKET: Auto-close brackets/quotes from extra keys toolbar
                    // (Keyboard input goes through onValueChange which already auto-closes,
                    // but extra keys toolbar inserts directly here — add the closing pair)
                    val closing = BracketPairConfig.getCloser(language, text.firstOrNull() ?: ' ')?.toString()
                    if (closing != null) {
                        val newText = value.text.substring(0, selStart) + text + closing + value.text.substring(selEnd)
                        // Place cursor between the pair (e.g. between ( and ))
                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                        programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(selStart, selStart, 1)), "auto_close_pair")
                    } else {
                        val newText = value.text.substring(0, selStart) + text + value.text.substring(selEnd)
                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                        programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(selStart, selStart, text.length)), "toolbar_insert")
                    }
                }
            }
        }
    }
    // P38-FIX: Long-press trigger for auto-opening LSP menu
    var longPressTrigger by remember { mutableStateOf(0) }
    var peekUsedLsp by remember { mutableStateOf(false) }  // P37-3: track LSP vs fallback for peek
    // P41-H: Peek References + Peek Declaration state
    var peekRefsResult by remember { mutableStateOf<PeekRefsResult?>(null) }
    var peekDeclResult by remember { mutableStateOf<PeekResult?>(null) }
    var findRefUsedLsp by remember { mutableStateOf(false) }  // P37-3: track LSP vs fallback for find references
    var typeDefUsedLsp by remember { mutableStateOf(false) }  // P37-3: track LSP vs fallback for type definition
    var implUsedLsp by remember { mutableStateOf(false) }  // P37-3: track LSP vs fallback for find implementations

    // ── Selection Range (Expand Selection) state ──────────────────────────
    var expandSelectionRanges by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var expandSelectionDepth by remember { mutableStateOf(-1) }
    var expandSelectionUsedLsp by remember { mutableStateOf(false) }

    // ── Find & Replace state ────────────────────────────────────────────
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var preserveCase by remember { mutableStateOf(false) }
    var matchIndex by remember { mutableStateOf(0) }

    // Sync external find bar (top white bar) to internal find state — must come AFTER the
    // vars above are declared (Kotlin local properties must be declared before use).
    LaunchedEffect(externalFindQuery) {
        if (externalFindQuery != null && externalFindQuery != findQuery) {
            findQuery = externalFindQuery
            matchIndex = 0
        }
    }
    LaunchedEffect(externalCaseSensitive) {
        if (externalCaseSensitive != null) caseSensitive = externalCaseSensitive
    }
    LaunchedEffect(externalWholeWord) {
        if (externalWholeWord != null) wholeWord = externalWholeWord
    }
    LaunchedEffect(externalUseRegex) {
        if (externalUseRegex != null) useRegex = externalUseRegex
    }
    // Sync external match index (next/prev from top find bar) to internal matchIndex
    LaunchedEffect(externalFindMatchIndex) {
        if (externalFindMatchIndex >= 0 && externalFindMatchIndex != matchIndex) {
            matchIndex = externalFindMatchIndex
        }
    }

    // ── Lint state ───────────────────────────────────────────────────────
    var lintErrors by remember { mutableStateOf<List<LintError>>(emptyList()) }
    // R3-4: Diagnostic tooltip state
    var showDiagnosticTooltip by remember { mutableStateOf(false) }
    var diagnosticTooltipLine by remember { mutableStateOf(-1) }
    LaunchedEffect(value.text, language) {
        kotlinx.coroutines.delay(500)   // debounce — only lint after 500 ms idle
        val localErrors = LintAnalyzer.analyze(value.text, language)
        // P24-1: merge LSP diagnostics as squiggles — deduplicate by composite key (start, end, message)
        val combined = (localErrors + lspDiagnosticErrors).distinctBy { Triple(it.start, it.end, it.message) }.sortedWith(compareBy({ it.start }, { it.severity }, { it.code ?: "" }))
        lintErrors = combined
    }

    // P24-1: Re-merge when LSP diagnostics arrive (server push)
    LaunchedEffect(lspDiagnosticErrors) {
        val localErrors = LintAnalyzer.analyze(value.text, language)
        lintErrors = (localErrors + lspDiagnosticErrors).distinctBy { Triple(it.start, it.end, it.message) }.sortedWith(compareBy({ it.start }, { it.severity }, { it.code ?: "" }))
    }
    // Phase F: Sync lintErrors to decoration store
    LaunchedEffect(lintErrors) { decorationStore.updateDiagnostics(lintErrors) }

    // ── P2-11 Inlay hints state ─────────────────────────────────────────
    var inlayHints by remember { mutableStateOf<List<InlayHint>>(emptyList()) }
    LaunchedEffect(value.text, language) {
        kotlinx.coroutines.delay(600)   // debounce — slightly after lint
        inlayHints = InlayHintAnalyzer.analyze(value.text, language)
    }


    // ── Go to Line state ─────────────────────────────────────────────────
    var goToLineInput by remember { mutableStateOf("") }

    // R1-2: Background-thread search with 300ms debounce — prevents jank on large files
    var matches by remember { mutableStateOf(emptyList<IntRange>()) }
    LaunchedEffect(value.text, findQuery, useRegex, caseSensitive, wholeWord) {
        if (findQuery.isEmpty()) { matches = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val result = withContext(Dispatchers.Default) {
            try {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val rawPattern = if (useRegex) findQuery else Regex.escape(findQuery)
                val finalPattern = if (wholeWord && !useRegex) "\b${rawPattern}\b" else rawPattern
                Regex(finalPattern, opts).findAll(value.text).map { it.range }.toList()
            } catch (e: Exception) { emptyList() }
        }
        matches = result
    }
    LaunchedEffect(matches.size, findQuery) {
        if (matchIndex >= matches.size) matchIndex = 0
    }
    // R3-B/D4: Scroll to current match when matchIndex changes (works for both find bars)
    LaunchedEffect(matchIndex, matches, externalFindBarOpen, findReplaceOpen) {
        if ((externalFindBarOpen || findReplaceOpen) && matches.isNotEmpty() && matchIndex < matches.size) {
            val matchStart = matches[matchIndex].first
            val targetLine = positionMapper.offsetToLine(matchStart)
            val lineHeightPx = editorMetrics.lineHeightPx
            vScroll.animateScrollTo((targetLine * lineHeightPx).toInt())
        }
    }

    // Bracket matching
    val _bracketMatch = remember(value) {
        val pos = value.selection.end
        if (pos == 0 || pos > value.text.length) null
        else {
            val before = if (pos > 0) value.text[pos - 1] else null
            val at = if (pos < value.text.length) value.text[pos] else null
            val bracket = before ?: at
            val allBrackets = BracketPairConfig.getAllBracketChars(language)
            val bracketPos = if (before != null && bracket in allBrackets) pos - 1
                          else if (at != null && bracket in allBrackets) pos
                          else -1
            if (bracketPos >= 0 && bracket != null) {
                val match = BracketPairConfig.getMatchingBracket(language, bracket)
                if (match != null) {
                    val dir = if (BracketPairConfig.isOpener(language, bracket)) 1 else -1
                    var depth = 0
                    var i = bracketPos
                    var found = -1
                    while (i >= 0 && i < value.text.length) {
                        val c = value.text[i]
                        if (c == bracket) depth++
                        else if (c == match) {
                            depth--
                            if (depth == 0) { found = i; break }
                        }
                        i += dir
                    }
                    if (found >= 0) Pair(bracketPos, found) else null
                } else null
            } else null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // P15-C: Sticky scroll header — shows current scope line pinned at top
        // R3-A: Disable sticky scroll when word wrap is enabled — line positions are unreliable
        if (toggles.showStickyScroll && stickyLine != null && !wordWrap) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(20f)
                    .background(Color(0xEE1A1A1A))
                    .padding(start = 66.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stickyLine,
                    color = Color(0xFF888888),
                    fontSize = (fontSize - 1).sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(end = if (showMinimapState) 62.dp else 4.dp, top = if (stickyLine != null) with(scrollDensity) { (fontSize * 1.4f).sp.toDp() } else 0.dp)
                .verticalScroll(vScroll)
        ) {
            // Gutter
            val savedLines = remember(savedContent) { savedContent.split("\n") }
            val currentLines = remember(value.text) { value.text.split("\n") }
            // P2-6 — real LCS git diff (replaces simple isDirty/isAdded heuristic)
            val gitDiff = remember(savedContent, value.text) {
                if (savedContent.isEmpty()) null
                else GitDiffAnalyzer.diff(currentLines, savedLines)
            }
            
            // P50-VIRT: Gutter virtualization — only render visible lines to handle infinite files without lag.
            // Previously the gutter rendered ALL lines as composables, causing OOM and jank on 1000+ line files.
            val viewportHeightPx = vScroll.viewportSize.toFloat().coerceAtLeast(1f)
            val visibleCount = ((viewportHeightPx / with(scrollDensity) { lineHeightDp.toPx() }).toInt() + 8) // +8 buffer for smooth scroll
            val topVisibleIdx = (vScroll.value / with(scrollDensity) { lineHeightDp.toPx() }).toInt().coerceAtLeast(0)
            val bottomVisibleIdx = (topVisibleIdx + visibleCount).coerceAtMost(visualLineMapper.visualLineCount)
            val topSpacerLines = topVisibleIdx.coerceAtLeast(0)
            val bottomSpacerLines = (visualLineMapper.visualLineCount - bottomVisibleIdx).coerceAtLeast(0)

            Column(modifier = Modifier.padding(horizontal = 4.dp).width(72.dp)) {
                // P50-VIRT: Spacer for lines above viewport — avoids composing off-screen rows
                if (topSpacerLines > 0) {
                    Spacer(Modifier.height((topSpacerLines * lineHeightDp.value).dp))
                }
                displayLines.subList(topVisibleIdx.coerceAtMost(visualLineMapper.visualLineCount), bottomVisibleIdx).forEachIndexed { vi, (lineNum, _) ->
                    if (lineNum == -1) {
                        // Visual placeholder row in gutter
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(lineHeightDp)
                        ) {
                            Spacer(Modifier.width(20.dp))
                            Text(
                                text = " ",
                                color = colors.gutter,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.25f).sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        val diffStatus = gitDiff?.lineStatus?.getOrNull(lineNum) ?: DiffStatus.UNCHANGED
                        val hasDeletedBefore = gitDiff?.deletedBeforeLines?.contains(lineNum) == true
                        val isFoldable = foldableLines.contains(lineNum)
                        val isFolded = foldedRanges.contains(lineNum)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(lineHeightDp)
                        ) {
                            // P2-6 diff gutter bar + deletion triangle
                            Column(modifier = Modifier.width(3.dp)) {
                                // Deletion triangle: small red ▼ rendered at top of this row
                                // when saved lines were deleted just before this current line
                                if (hasDeletedBefore) {
                                    Text(
                                        text = "▶",
                                        color = Color(0xFFE06C75),
                                        fontSize = (fontSize * 0.55f).sp,
                                        lineHeight = (fontSize * 0.6f).sp,
                                        modifier = Modifier.width(3.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .weight(1f, fill = false)
                                        .defaultMinSize(minHeight = fontSize.dp)
                                        .background(
                                            when (diffStatus) {
                                                DiffStatus.ADDED    -> Color(0xFF4EC9B0) // green
                                                DiffStatus.MODIFIED -> Color(0xFFE5C07B) // yellow
                                                DiffStatus.DELETED_BEFORE -> Color(0xFFE06C75) // red (fallback)
                                                DiffStatus.UNCHANGED -> Color.Transparent
                                            }
                                        )
                                )
                            }
                            Spacer(Modifier.width(1.dp))
                            // Gutter fold chevron icon (▼ when expanded, ▶ when folded)
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .fillMaxHeight()
                                    .clickable(enabled = isFoldable) {
                                        foldedRanges = if (isFolded) {
                                            foldedRanges - lineNum
                                        } else {
                                            foldedRanges + lineNum
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isFoldable) {
                                    Text(
                                        text = if (isFolded) "▶" else "▼",
                                        color = colors.gutter,
                                        fontSize = (fontSize - 3).sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            // P2-9 Bookmark dot (◆) — tappable to toggle
                            Box(
                                modifier = Modifier
                                    .size(fontSize.dp)
                                    .clickable {
                                        bookmarkedLines = if (bookmarkedLines.contains(lineNum))
                                            bookmarkedLines - lineNum
                                        else
                                            bookmarkedLines + lineNum
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (bookmarkedLines.contains(lineNum)) {
                                    Text(
                                        text = "◆",
                                        color = colors.keyword,  // P50-FIX: theme-aware bookmark color (was hardcoded 0xFF61AFEF)
                                        fontSize = (fontSize * 0.6f).sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            // P8-1 Breakpoint dot + tappable line number (VS Code style: show both)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(lineHeightDp)
                                    .clickable { onBreakpointToggle(lineNum) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                // P54: Debug current-line indicator — yellow arrow (▶) in gutter
                                if (debugCurrentLine > 0 && lineNum == debugCurrentLine - 1) {
                                    Text(
                                        text = "→",
                                        color = Color(0xFFCCA700),
                                        fontSize = (fontSize * 0.8f).sp,
                                    )
                                    Spacer(Modifier.width(2.dp))
                                }
                                if (breakpointLines.contains(lineNum)) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE51400))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = (lineNum + 1).toString(),
                                    color = if (debugCurrentLine > 0 && lineNum == debugCurrentLine - 1)
                                        Color(0xFFCCA700)  // P54: yellow highlight on current debug line
                                    else if (bookmarkedLines.contains(lineNum))
                                        colors.keyword else colors.gutter,  // P50-FIX: theme-aware bookmark color
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.25f).sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                        }
                    }
                }
                // P50-VIRT: Spacer for lines below viewport
                if (bottomSpacerLines > 0) {
                    Spacer(Modifier.height((bottomSpacerLines * lineHeightDp.value).dp))
                }
            }
            // Editor surface
            // HSCROLL-FIX: When not word-wrapping, calculate the max line width from
            // the text layout result so the editor surface can be wider than the viewport
            // and horizontal scrolling actually works. Without this, BasicTextField
            // fills the available width and long lines are clipped at the right edge.
            val maxLineWidth = com.codespace.ide.editor.EditorLayoutHelper.calcMaxLineWidth(wordWrap, textLayoutResult)
            val editorWidthModifier = com.codespace.ide.editor.EditorLayoutHelper.buildEditorWidthModifier(
                wordWrap, maxLineWidth, hScroll
            )
            Box(
                modifier = editorWidthModifier
            ) {
                // R1-1: Pre-compute syntax highlighting on background thread for large files.
                // Small files (<500 lines) use synchronous path. Staleness protection:
                // precomputedForText tracks exactly which text was highlighted; if the
                // user keeps typing during the 100ms debounce, the VisualTransformation
                // will NOT apply the stale highlight (it checks precomputedForText == text.text).
                var precomputedHighlight by remember { mutableStateOf<androidx.compose.ui.text.AnnotatedString?>(null) }
                var precomputedForText by remember { mutableStateOf("") }
                val textLineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
                LaunchedEffect(value.text, language, colors) {
                    if (textLineCount < 200) return@LaunchedEffect
                    delay(100)
                    val result = withContext(Dispatchers.Default) {
                        SyntaxHighlighter.highlight(value.text, language, colors)
                    }
                    precomputedForText = value.text
                    precomputedHighlight = result
                }
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        ghostText = null; ghostTextLines = emptyList(); ghostTextIsAi = false  // P41-E: dismiss ghost on any keystroke
                        // Phase X-2: Tag the event source
                        val insertedChars = newValue.text.length - value.text.length
                        // IME-FIX: Skip undo snapshots while IME is composing text
                        // (composing region active). Gboard's glide typing and autocorrect
                        // send intermediate values; pushing each floods the undo stack.
                        // The final committed value (composing cleared) triggers the push.
                        val comp = newValue.composition
                        val isComposing = comp != null && comp.start >= 0
                        if (newValue.text != value.text) {
                            editorEvent = EditorEvent.UserTyping(newValue.text, newValue.selection.end, value.text, value.selection.end)
                            // Change 4: O(1) snapshot undo - push full snapshot (coalesced)
                            if (!undoRedoInProgress && !isComposing) {
                                snapshotUndo.push(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(
                                    newValue.text, newValue.selection, extraCursors
                                ))
                            }
                        } else if (newValue.selection != value.selection) {
                            editorEvent = EditorEvent.UserSelection(newValue.selection.start, newValue.selection.end)
                        }
                        // IME-FIX: When IME commits text (transition from composing to
                        // not composing with text change), the push() above already
                        // captures the committed state since !isComposing is now true.
                        // No extra pushForce needed — push() handles it via coalescing.
                        // Phase U-5: Check if typed char should commit the selected completion
                        var updatedValue = newValue
                        val commitCharMatch = if (showCompletions && selectedLabel != null && newValue.text.length == value.text.length + 1) {
                            val typedChar = newValue.text.getOrNull(newValue.selection.end - 1)
                            val selectedComp = allCompletions.getOrNull(
                                allCompletions.indexOfFirst { it.label == selectedLabel }
                            )
                            if (typedChar != null && selectedComp != null && selectedComp.commitCharacters.contains(typedChar)) {
                                // Commit the selected completion, then insert the typed char
                                val cursor = value.selection.end
                                val text = value.text
                                val end = cursor.coerceAtMost(text.length)
                                var start = end
                                while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
                                val insertTxt = selectedComp.insertText
                                val committedText = text.substring(0, start) + insertTxt + typedChar.toString() + text.substring(end)
                                val committedCursor = start + insertTxt.length + 1
                                programmaticTextChange(committedText, TextRange(committedCursor), "completion_commit_char")
                                CompletionHistoryStore.recordAccepted(selectedComp.label, language.name, context)
                                showCompletions = false
                                selectedLabel = null
                                completionFilter = null
                                true
                            } else false
                        } else false
                        if (!commitCharMatch) {
                        // R2-5: Surround selection — when text is selected and user types
                        // a bracket/quote, wrap the selection instead of replacing it.
                        if (newValue.text.length == value.text.length + 1 &&
                            value.selection.start != value.selection.end) {
                            val typedChar = newValue.text.getOrNull(newValue.selection.end - 1)
                            val bpPair = BracketPairConfig.getPairByOpen(language, typedChar ?: ' ')
                            val openChar = bpPair?.open
                            val closeChar = bpPair?.close
                            if (openChar != null && closeChar != null && bpPair.surround) {
                                val selStart = value.selection.start
                                val selEnd = value.selection.end
                                val selectedText = value.text.substring(selStart, selEnd)
                                val before = value.text.substring(0, selStart)
                                val after = value.text.substring(selEnd)
                                val wrappedText = before + openChar + selectedText + closeChar + after
                                updatedValue = TextFieldValue(
                                    text = wrappedText,
                                    selection = TextRange(positionMapper.shiftOnInsert(selStart, selStart, 1), positionMapper.shiftOnInsert(selEnd, selStart, 1))
                                )
                            }
                        }
                        // 1. Auto-close brackets & quotes
                        if (newValue.text.length == value.text.length + 1 &&
                            updatedValue === newValue) {
                            val cursor = newValue.selection.end
                            if (cursor > 0 && cursor <= newValue.text.length) {
                                val insertedChar = newValue.text[cursor - 1]
                                val closer = BracketPairConfig.getCloser(language, insertedChar)
                                if (closer != null) {
                                    // R2-3: Skip-over if the next char is already the closer
                                    if (cursor < newValue.text.length && newValue.text[cursor] == closer) {
                                        updatedValue = TextFieldValue(
                                            text = newValue.text,
                                            selection = androidx.compose.ui.text.TextRange(positionMapper.shiftOnInsert(cursor, cursor, 1))
                                        )
                                    } else {
                                        // R2-4: Don't auto-close brackets inside strings
                                        // (quotes are always allowed to close strings)
                                        // Known limitation: does not account for comments (# or //)
                                        val inString = isInsideStringValue(newValue.text, cursor)
                                        if (!inString || insertedChar == '"' || insertedChar == '\'') {
                                            val leftText = newValue.text.substring(0, cursor)
                                            val rightText = newValue.text.substring(cursor)
                                            updatedValue = TextFieldValue(
                                                text = leftText + closer + rightText,
                                                selection = androidx.compose.ui.text.TextRange(cursor)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        // P41-O4: Auto-indent on Enter — copy previous line's leading whitespace
                        if (newValue.text.length > value.text.length &&
                            newValue.text.length - value.text.length >= 2) {
                            val inserted = newValue.text.substring(
                                value.selection.start.coerceIn(0, newValue.text.length),
                                (value.selection.start + (newValue.text.length - value.text.length)).coerceIn(0, newValue.text.length)
                            )
                            if (inserted == "\n" || inserted.contains("\n")) {
                                val cursor = newValue.selection.end.coerceIn(0, newValue.text.length)
                                // Build a fresh mapper from newValue — positionMapper is stale (keyed on old value.text)
                                val newMapper = PositionMapper(newValue.text)
                                val cursorLine = newMapper.offsetToLine(cursor)
                                val lineStart = newMapper.lineStart(cursorLine).coerceIn(0, newValue.text.length)
                                val currentLine = newValue.text.substring(lineStart, cursor)
                                // Find previous line's indentation
                                val prevLineText = if (cursorLine > 0) {
                                    val prevLineStart = newMapper.lineStart(cursorLine - 1).coerceIn(0, newValue.text.length)
                                    val prevLineEnd = (lineStart - 1).coerceAtLeast(prevLineStart)
                                    newValue.text.substring(prevLineStart, prevLineEnd)
                                } else {
                                    ""
                                }
                                val indent = prevLineText.takeWhile { it == ' ' || it == '\t' }
                                // Smart Enter: auto-indent + auto-close after { [ ( and Python :
                                val trimmedPrev = prevLineText.trimEnd()
                                val endsWithBrace = trimmedPrev.endsWith("{")
                                val endsWithBracket = trimmedPrev.endsWith("[")
                                val endsWithParen = trimmedPrev.endsWith("(")
                                val endsWithColon = trimmedPrev.endsWith(":") && language == com.codespace.ide.domain.Language.PYTHON
                                val needsExtraIndent = endsWithBrace || endsWithBracket || endsWithParen || endsWithColon
                                val extraIndent = if (needsExtraIndent) "    " else ""
                                val fullIndent = indent + extraIndent
                                // Smart Enter: if prevLine ends with an unmatched opener, add closing bracket below
                                val smartCloserChar = when {
                                    endsWithBrace -> BracketPairConfig.getCloser(language, '{')
                                    endsWithBracket -> BracketPairConfig.getCloser(language, '[')
                                    endsWithParen -> BracketPairConfig.getCloser(language, '(')
                                    else -> null
                                }
                                val closer = smartCloserChar?.toString()
                                if (closer != null && fullIndent.isNotEmpty()) {
                                    // Insert: indent + extraIndent + newline + indent + closer
                                    val insertPos = lineStart + currentLine.length
                                    val blockText = fullIndent + "\n" + indent + closer
                                    val newText = newValue.text.substring(0, insertPos) + blockText + newValue.text.substring(insertPos)
                                    updatedValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(insertPos + fullIndent.length)
                                    )
                                } else if (fullIndent.isNotEmpty()) {
                                    val insertPos = lineStart + currentLine.length
                                    val newText = newValue.text.substring(0, insertPos) + fullIndent + newValue.text.substring(insertPos)
                                    updatedValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(insertPos + fullIndent.length)
                                    )
                                }
                            }
                        }
                        // Multi-cursor fan-out: replay same edit at each extra cursor
                        // CRASH/BUG-FIX: properly account for primary edit position relative
                        // to each extra cursor. Old code used ec+shift without adjusting
                        // for the primary insertion/deletion happening before the cursor,
                        // causing text to be inserted at wrong positions and cursors to jump.
                        // BUG-FIX (Test 51): a cursor could end up added at the EXACT same
                        // offset as the primary/real cursor (e.g. double-tapping right where the
                        // caret already sits). If left in extraCursors, the fan-out below replays
                        // the SAME edit a second time at that spot — the primary cursor's own line
                        // gets the typed text TWICE while every genuinely distinct extra cursor
                        // works correctly. Strip any such duplicate before fanning out.
                        if (value.selection.start in extraCursors) {
                            extraCursors = extraCursors.filter { it != value.selection.start }
                        }
                        // Phase V-FIX (Test 51): Also remove any extra cursor that, after the
                        // primary edit, would land at the SAME position as the new primary
                        // cursor. Without this, two cursors at adjacent offsets on line 1
                        // (e.g. offsets 0 and 1) both insert at the same spot after the
                        // primary edit — doubling every typed character on that line.
                        val newPrimaryPos = updatedValue.selection.start
                        extraCursors = extraCursors.filter { ec ->
                            val adjusted = if (ec < value.selection.start) ec else ec + (updatedValue.text.length - value.text.length)
                            adjusted != newPrimaryPos
                        }
                        if (extraCursors.isNotEmpty()) {
                            val delta = updatedValue.text.length - value.text.length
                            if (delta != 0) {
                                val primaryAt = value.selection.start
                                val inserted: String = if (delta > 0) {
                                    updatedValue.text.substring(primaryAt, (primaryAt + delta).coerceAtMost(updatedValue.text.length))
                                } else ""
                                val deletedLen = if (delta < 0) -delta else 0
                                var composed = updatedValue.text
                                val newExtras = mutableListOf<Int>()
                                // The primary cursor's position in composed (after primary edit)
                                val primaryNewPos = updatedValue.selection.start
                                var fanShift = 0  // cumulative shift from fan-out edits
                                var primaryAdjust = 0  // shift to apply to primary cursor
                                for (ec in extraCursors.sorted()) {
                                    // Adjust extra cursor for the PRIMARY edit:
                                    // If primary insertion was before this cursor, it's now at ec+delta
                                    val primaryShift = if (delta > 0) {
                                        positionMapper.shiftOnInsert(ec, primaryAt, delta) - ec
                                    } else {
                                        val delStart = (primaryAt - deletedLen).coerceAtLeast(0)
                                        positionMapper.shiftOnDelete(ec, delStart, deletedLen) - ec
                                    }
                                    val pos = (ec + primaryShift + fanShift).coerceIn(0, composed.length)
                                    if (delta > 0) {
                                        composed = composed.substring(0, pos) + inserted + composed.substring(pos)
                                        fanShift += inserted.length
                                        newExtras.add(pos + inserted.length)
                                        // If this fan-out insertion was before the primary cursor, shift it
                                        if (pos < primaryNewPos) primaryAdjust += inserted.length
                                    } else {
                                        val from = (pos - deletedLen).coerceAtLeast(0)
                                        val to = pos.coerceAtMost(composed.length)
                                        if (from < to) {
                                            composed = composed.substring(0, from) + composed.substring(to)
                                            fanShift -= (to - from)
                                            newExtras.add(from)
                                            if (to <= primaryNewPos) primaryAdjust -= (to - from)
                                        } else {
                                            newExtras.add(from)
                                        }
                                    }
                                }
                                extraCursors = newExtras
                                val primStart = (updatedValue.selection.start + primaryAdjust).coerceIn(0, composed.length)
                                val primEnd = (updatedValue.selection.end + primaryAdjust).coerceIn(0, composed.length)
                                updatedValue = updatedValue.copy(text = composed, selection = TextRange(primStart, primEnd))
                            }
                        }

                        // P41-I: If snippet session is active, handle text edits within tab-stops
                        if (snippetSession != null) {
                            val session = snippetSession!!
                            val activeStop = session.activeStop()
                            if (activeStop != null) {
                                val oldStopLen = activeStop.endOffset - activeStop.startOffset
                                // Find the text at the active stop's range in the new text
                                val stopStart = (activeStop.startOffset).coerceIn(0, updatedValue.text.length)
                                val stopEnd = (activeStop.endOffset).coerceIn(0, updatedValue.text.length)
                                val newStopLen = if (stopStart <= stopEnd) stopEnd - stopStart else 0
                                // Recompute based on actual text length difference
                                val textDelta = updatedValue.text.length - value.text.length
                                if (textDelta != 0) {
                                    val oldLen = activeStop.endOffset - activeStop.startOffset
                                    val newLen = oldLen + textDelta
                                    snippetSession = session.shiftAfterEdit(activeStop, oldLen, newLen)
                                }
                            }
                            // Check if cursor left the snippet span
                            if (!session.containsCursor(updatedValue.selection.end)) {
                                snippetSession = null
                                showSnippetChoices = false
                            } else {
                                // Update choice dropdown visibility based on active stop
                                showSnippetChoices = snippetSession?.activeStop()?.choices?.isNotEmpty() == true
                            }
                        }
                        // R1-3: Shift decoration positions to prevent stale diagnostics/highlights
                        if (value.text != updatedValue.text) {
                            decorationStore.shiftOnEdit(value.text, updatedValue.text)
                        }
                        value = updatedValue
                        onContentChange(updatedValue.text)
                        // P22-G: Report cursor position for LSP hover
                        val cOff = updatedValue.selection.end
                        val cPos = positionMapper.offsetToPosition(cOff)
                        val cLine = cPos.line
                        val cCol = cPos.column
                        onCursorChange?.invoke(cLine, cCol)
                        }
                    },
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            color = colors.text,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.25f).sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    ),
                    // P-CURSOR: Animated cursor brush based on In-Project Settings > Text Editor > Cursor Blinking
                    cursorBrush = animatedCursorBrush(colors.cursor),
                    visualTransformation = run {
                        val incrHighlighter = remember { IncrementalHighlighter() }
                        remember(language, colors, lintErrors, foldedLineIndices, semanticTokens, precomputedHighlight, precomputedForText) {
                            SyntaxTransformation(
                                language = language,
                                colors = colors,
                                lintErrors = lintErrors,
                                foldedLineIndices = foldedLineIndices,
                                semanticTokens = semanticTokens,
                                precomputedHighlight = precomputedHighlight,
                                precomputedForText = precomputedForText,
                                incrementalHighlighter = incrHighlighter,
                            )
                        }
                    },
                    onTextLayout = { result -> textLayoutResult = result },
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .padding(end = 24.dp)
                        .focusRequester(focusRequester)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    // DEBUG: Show that onDoubleTap was called
                                    android.util.Log.d("MultiCursor", "onDoubleTap fired at " + offset.toString())
                                    debugDoubleTapMsg = "DBLTAP offset=" + offset.toString()
                                    // Double tap — add/remove extra cursor at this position
                                    textLayoutResult?.let { layout ->
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        extraCursors = if (charOffset == value.selection.start) {
                                            extraCursors
                                        } else if (charOffset in extraCursors)
                                            extraCursors.filter { it != charOffset }
                                        else
                                            (extraCursors + charOffset).distinct().sorted()
                                    }
                                },
                                onTap = { offset ->
                                    // Single tap — place cursor at tap position + request focus
                                    textLayoutResult?.let { layout ->
                                        val pos = layout.getOffsetForPosition(offset)
                                        value = value.copy(selection = TextRange(pos))
                                        editorEvent = EditorEvent.UserCursorMove(pos)
                                        val cPos = positionMapper.offsetToPosition(pos)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        onCursorChange?.invoke(cLine, cCol)
                                        // R3-4: Check if tapped line has diagnostics
                                        val hasDiagnostic = lintErrors.any { err ->
                                            val errLine = value.text.substring(0, err.start.coerceIn(0, value.text.length)).count { it == '\n' }
                                            errLine == cLine
                                        }
                                        if (hasDiagnostic) {
                                            showDiagnosticTooltip = !showDiagnosticTooltip
                                            diagnosticTooltipLine = cLine
                                        } else {
                                            showDiagnosticTooltip = false
                                        }
                                    }
                                    try { focusRequester.requestFocus() } catch (_: IllegalArgumentException) {}
                                },
                                onLongPress = { offset ->
                                    // P38-FIX: Long-press selects the word and opens LSP menu
                                    textLayoutResult?.let { layout ->
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        // Word boundary detection: camelCase, snake_case, kebab-case, dot notation
                                        val text = value.text
                                        val (wordStart, wordEnd) = WordBoundary.findWordBoundaries(text, charOffset)
                                        // Select the word (VS Code behavior)
                                        value = value.copy(selection = TextRange(wordStart, wordEnd))
                                        // Phase X-5/X-10: Tag as user selection + fire onCursorChange
                                        editorEvent = EditorEvent.UserSelection(wordStart, wordEnd)
                                        val cPos = positionMapper.offsetToPosition(wordEnd)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        onCursorChange?.invoke(cLine, cCol)
                                        // Trigger the floating LSP menu to auto-open
                                        longPressTrigger++
                                    }
                                }
                            )
                        }
                        // Pinch-to-zoom: separate pointerInput so it doesn't conflict with detectTapGestures
                        .pointerInput(fontSize, onFontSizeChange) {
                            var accumulatedZoom = 1f
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f && onFontSizeChange != null) {
                                    accumulatedZoom *= zoom
                                    val newSize = (fontSize * accumulatedZoom).roundToInt().coerceIn(8, 32)
                                    if (newSize != fontSize) {
                                        onFontSizeChange.invoke(newSize)
                                        accumulatedZoom = newSize.toFloat() / fontSize
                                    }
                                }
                            }
                        }
                        // P41-I: Intercept Tab/Shift+Tab for snippet tab-stop navigation
                        // P46-A5: Also intercept Tab to expand local snippets when no session is active
                        .onPreviewKeyEvent { event ->
                            // R3-A: TAB to accept completion when popup is visible (before snippet expansion)
                            if (showCompletions && event.key == Key.Tab && event.type == KeyEventType.KeyDown &&
                                !event.nativeKeyEvent.isShiftPressed && snippetSession == null &&
                                allCompletions.isNotEmpty()) {
                                // Find the selected completion (by selectedLabel, or default to first)
                                val comp = if (selectedLabel != null) {
                                    allCompletions.find { it.label == selectedLabel } ?: allCompletions[0]
                                } else {
                                    allCompletions[0]
                                }
                                val text = value.text
                                val cursor = value.selection.end
                                // Compute prefix the same way as the rest of the code
                                var wordStart = cursor
                                while (wordStart > 0 && (text[wordStart - 1].isLetterOrDigit() || text[wordStart - 1] == '_' || text[wordStart - 1] == '.')) {
                                    wordStart--
                                }
                                val start = wordStart
                                val end = cursor
                                val insertText = comp.insertText
                                val newText = text.substring(0, start) + insertText + text.substring(end)
                                val newCursor = start + insertText.length
                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                programmaticTextChange(newText, TextRange(newCursor), "completion_commit")
                                CompletionHistoryStore.recordAccepted(comp.label, language.name, context)
                                showCompletions = false
                                selectedLabel = null
                                completionFilter = null
                                true
                            }
                            // P46-A5: Tab expansion for local snippets (when no snippet session active)
                            else if (snippetSession == null && event.key == Key.Tab && event.type == KeyEventType.KeyDown &&
                                !event.nativeKeyEvent.isShiftPressed && !showCompletions) {
                                val cursor = value.selection.end
                                // P46-A5: Find the word (or two-word combo) before the cursor for snippet expansion
                                var wordStart = cursor
                                while (wordStart > 0 && (value.text[wordStart - 1].isLetterOrDigit() || value.text[wordStart - 1] == '_')) {
                                    wordStart--
                                }
                                val singleWord = value.text.substring(wordStart, cursor)
                                // Check for two-word triggers like "data class", "let mut"
                                var twoWordStart = wordStart
                                if (wordStart > 0 && value.text[wordStart - 1] == ' ') {
                                    var ws = wordStart - 1
                                    while (ws > 0 && value.text[ws - 1] == ' ') ws--
                                    var prevWordEnd = ws
                                    if (prevWordEnd > 0 && (value.text[prevWordEnd - 1].isLetterOrDigit() || value.text[prevWordEnd - 1] == '_')) {
                        var prevWordStart = prevWordEnd
                        while (prevWordStart > 0 && (value.text[prevWordStart - 1].isLetterOrDigit() || value.text[prevWordStart - 1] == '_')) prevWordStart--
                        twoWordStart = prevWordStart
                    }
                                }
                                val twoWord = if (twoWordStart < wordStart) value.text.substring(twoWordStart, cursor) else singleWord
                                if (singleWord.isNotEmpty()) {
                                    val localSnippets = snippetsFor(language)
                                    // Try two-word exact match first, then single word
                                    val matched = localSnippets.firstOrNull { it.label == twoWord }
                                        ?: localSnippets.firstOrNull { it.label == singleWord }
                                        ?: localSnippets.firstOrNull { it.label.startsWith(singleWord) && singleWord.length >= 3 }
                                    if (matched != null) {
                                        val expandStart = if (matched.label == twoWord) twoWordStart else wordStart
                                        val matchedLabel = matched
                                        // Expand the snippet: replace the word with insertText
                                        val snippetText = matched.insertText
                                        val newText = value.text.substring(0, expandStart) + snippetText + value.text.substring(cursor)
                                        // For insertTextFormat == 2 (snippet syntax), parse and create a session
                                        if (matched.insertTextFormat == 2) {
                                            val parsed = parseSnippet(snippetText, SnippetContext(
                                                lineNumber = positionMapper.offsetToLine(expandStart) + 1,
                                                lineIndex = positionMapper.offsetToLine(expandStart),
                                                currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(expandStart)),
                                                selectedText = "",
                                            ))
                                            val cleanedText = parsed.cleanedText
                                            val finalText = value.text.substring(0, expandStart) + cleanedText + value.text.substring(cursor)
                                            val session = createSnippetSession(expandStart, parsed)
                                            snippetSession = session
                                            showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                            val firstStop = session.tabStops.firstOrNull()
                                            val selRange = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                TextRange(firstStop.startOffset, firstStop.endOffset)
                                            } else {
                                                TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                            }
                                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, finalText, extraCursors)
                                            programmaticTextChange(finalText, selRange ?: TextRange(0), "snippet_multicursor_transform")
                                        } else {
                                            // Plain text snippet — place cursor at end of inserted text
                                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                            programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(expandStart, expandStart, snippetText.length)), "snippet_plain_text")
                                        }
                                        true // consume the Tab key
                                    } else {
                                        false // no snippet match, let Tab work normally
                                    }
                                } else {
                                    false
                                }
                            } else if (snippetSession != null && event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                                val session = snippetSession!!
                                val isShift = event.nativeKeyEvent.isShiftPressed
                                if (isShift) {
                                    // Shift+Tab — go to previous tab-stop
                                    // Apply transform to current stop's text before leaving it
                                    val (transformedTextPrev, transformedSessionPrev) = session.applyActiveStopTransform(value.text)
                                    val sessionToRetreat = if (transformedTextPrev != value.text) {
                                        value = value.copy(text = transformedTextPrev)
                                        onContentChange(transformedTextPrev)
                                        transformedSessionPrev
                                    } else {
                                        session
                                    }
                                    val prev = sessionToRetreat.retreat()
                                    if (prev != null) {
                                        snippetSession = prev
                                        showSnippetChoices = prev.activeStop()?.choices?.isNotEmpty() == true
                                        val stopRange = prev.activeStopRange()
                                        if (stopRange != null) {
                                            value = value.copy(
                                                selection = TextRange(stopRange.first, stopRange.last + 1)
                                            )
                                            editorEvent = EditorEvent.ProgrammaticCursorMove(stopRange.first, "snippet_prev_stop")
                                        }
                                    } else {
                                        // At first stop — exit snippet mode
                                        snippetSession = null
                                    }
                                } else {
                                    // Tab — go to next tab-stop
                                    // Apply transform to current stop's text before leaving it
                                    val (transformedText, transformedSession) = session.applyActiveStopTransform(value.text)
                                    val sessionToAdvance = if (transformedText != value.text) {
                                        value = value.copy(text = transformedText)
                                        onContentChange(transformedText)
                                        transformedSession
                                    } else {
                                        session
                                    }
                                    val next = sessionToAdvance.advance()
                                    if (next != null) {
                                        snippetSession = next
                                        showSnippetChoices = next.activeStop()?.choices?.isNotEmpty() == true
                                        val stopRange = next.activeStopRange()
                                        if (stopRange != null) {
                                            value = value.copy(
                                                selection = TextRange(stopRange.first, stopRange.last + 1)
                                            )
                                            editorEvent = EditorEvent.ProgrammaticCursorMove(stopRange.first, "snippet_next_stop")
                                        }
                                    } else {
                                        // Last stop — move to final cursor ($0) and exit
                                        value = value.copy(
                                            selection = TextRange(sessionToAdvance.finalCursorOffset)
                                        )
                                        editorEvent = EditorEvent.ProgrammaticCursorMove(sessionToAdvance.finalCursorOffset, "snippet_final")
                                        snippetSession = null
                                    }
                                }
                                true // consume the Tab key
                            } else if (snippetSession != null && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                // Escape — exit snippet mode without advancing
                                snippetSession = null
                                showSnippetChoices = false
                                true
                            } else if (snippetSession == null && !showCompletions &&
                                       event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                                // Tab / Shift+Tab with multi-line selection — indent / unindent
                                val isShift = event.nativeKeyEvent.isShiftPressed
                                val selStart = value.selection.start
                                val selEnd = value.selection.end
                                val startLine = positionMapper.offsetToLine(selStart)
                                val endLine = positionMapper.offsetToLine(selEnd)
                                if (selStart != selEnd && endLine > startLine) {
                                    val lines = value.text.split("\n")
                                    val indentUnit = "    " // 4 spaces
                                    if (isShift) {
                                        // Unindent: remove one indent level from each selected line
                                        val firstLineOffset = positionMapper.lineStart(startLine)
                                        val newText = StringBuilder(value.text.substring(0, firstLineOffset))
                                        var totalRemoved = 0
                                        var firstLineRemoved = 0
                                        for (lineIdx in startLine..endLine) {
                                            val line = lines[lineIdx]
                                            val removed = if (line.startsWith(indentUnit)) {
                                                indentUnit.length
                                            } else if (line.startsWith("\t")) {
                                                1
                                            } else {
                                                0
                                            }
                                            val newLine = line.substring(removed)
                                            newText.append(newLine)
                                            if (lineIdx < endLine) newText.append("\n")
                                            totalRemoved += removed
                                            if (lineIdx == startLine) firstLineRemoved = removed
                                        }
                                        newText.append(value.text.substring((positionMapper.lineStart(endLine) + lines[endLine].length + 1).coerceAtMost(value.text.length)))
                                        val finalText = newText.toString()
                                        val newStart = (selStart - firstLineRemoved).coerceAtLeast(positionMapper.lineStart(startLine))
                                        val newEnd = (selEnd - totalRemoved).coerceAtLeast(newStart)
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, finalText, extraCursors)
                                        programmaticTextChange(finalText, TextRange(newStart, newEnd), "delete_lines")
                                        true
                                    } else {
                                        // Indent: add one indent level to each selected line
                                        val firstLineOffset = positionMapper.lineStart(startLine)
                                        val newText = StringBuilder(value.text.substring(0, firstLineOffset))
                                        var totalAdded = 0
                                        var firstLineAdded = 0
                                        for (lineIdx in startLine..endLine) {
                                            newText.append(indentUnit)
                                            newText.append(lines[lineIdx])
                                            if (lineIdx < endLine) newText.append("\n")
                                            totalAdded += indentUnit.length
                                            if (lineIdx == startLine) firstLineAdded = indentUnit.length
                                        }
                                        newText.append(value.text.substring((positionMapper.lineStart(endLine) + lines[endLine].length + 1).coerceAtMost(value.text.length)))
                                        val finalText = newText.toString()
                                        val newStart = selStart + firstLineAdded
                                        val newEnd = selEnd + totalAdded
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, finalText, extraCursors)
                                        programmaticTextChange(finalText, TextRange(newStart, newEnd), "duplicate_lines")
                                        true
                                    }
                                } else {
                                    false
                                }
                            } else if (activeSignature != null && activeSignature!!.allSignatures.size > 1 &&
                                       event.type == KeyEventType.KeyDown) {
                                // P41-OV: Up/Down arrow to cycle through signature overloads
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        overloadIndex = (overloadIndex - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        val max = activeSignature!!.allSignatures.size - 1
                                        overloadIndex = (overloadIndex + 1).coerceAtMost(max)
                                        true
                                    }
                                    else -> false
                                }
                            } else if (event.type == KeyEventType.KeyDown &&
                                       (event.nativeKeyEvent.isCtrlPressed || event.nativeKeyEvent.isMetaPressed)) {
                                // KeyBindingRegistry-driven dispatch (replaces hardcoded when{} checks)
                                val kbAction = KeyBindingRegistry.match(
                                    event.key,
                                    ctrl = true,
                                    shift = event.nativeKeyEvent.isShiftPressed,
                                    alt = false
                                )
                                when (kbAction) {
                                    EditorAction.UNDO -> {
                                        if (snapshotUndo.canUndo()) {
                                            undoRedoInProgress = true
                                            val current = com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(
                                                value.text, value.selection, extraCursors
                                            )
                                            val prev = snapshotUndo.undo(current)
                                            if (prev != null) {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, prev.text, prev.extraCursors)
                                                programmaticTextChange(prev.text, prev.selection, "undo")
                                            }
                                            undoRedoInProgress = false
                                        }
                                        true
                                    }
                                    EditorAction.REDO -> {
                                        if (snapshotUndo.canRedo()) {
                                            undoRedoInProgress = true
                                            val current = com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(
                                                value.text, value.selection, extraCursors
                                            )
                                            val next = snapshotUndo.redo(current)
                                            if (next != null) {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, next.text, next.extraCursors)
                                                programmaticTextChange(next.text, next.selection, "redo")
                                            }
                                            undoRedoInProgress = false
                                        }
                                        true
                                    }
                                    EditorAction.DUPLICATE_LINE -> {
                                        val cursor = value.selection.end
                                        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                        val lineEnd = value.text.indexOf('\n', cursor)
                                        val endIdx = if (lineEnd == -1) value.text.length else lineEnd
                                        val currentLine = value.text.substring(lineStart, endIdx)
                                        val insertText = currentLine + if (lineEnd == -1) "\n" else ""
                                        val newText = value.text.substring(0, endIdx) + insertText + value.text.substring(endIdx)
                                        undoRedoInProgress = true
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(endIdx, endIdx, insertText.length)), "duplicate_line")
                                        snapshotUndo.pushForce(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(newText, TextRange(positionMapper.shiftOnInsert(endIdx, endIdx, insertText.length)), extraCursors))
                                        undoRedoInProgress = false
                                        true
                                    }
                                    EditorAction.COMMENT_TOGGLE -> {
                                        val cursor = value.selection.end
                                        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                        val lineEnd = value.text.indexOf('\n', cursor).let { if (it == -1) value.text.length else it }
                                        val lineText = value.text.substring(lineStart, lineEnd)
                                        val commentPrefix = when (language) {
                                            Language.PYTHON, Language.SHELL -> "# "
                                            Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT,
                                            Language.TYPESCRIPT, Language.GO, Language.RUST,
                                            Language.CPP, Language.C -> "// "
                                            Language.HTML, Language.XML -> "<!-- "
                                            Language.CSS -> "/* "
                                            else -> "// "
                                        }
                                        val commentTrim = commentPrefix.trim()
                                        val newText: String
                                        val newCursor: Int
                                        if (lineText.trimStart().startsWith(commentTrim)) {
                                            val cidx = lineText.indexOf(commentTrim)
                                            newText = value.text.substring(0, lineStart) + lineText.removeRange(cidx, cidx + commentTrim.length) + value.text.substring(lineEnd)
                                            newCursor = (cursor - commentTrim.length).coerceAtLeast(lineStart)
                                        } else {
                                            newText = value.text.substring(0, lineStart) + commentPrefix + lineText + value.text.substring(lineEnd)
                                            newCursor = cursor + commentPrefix.length
                                        }
                                        undoRedoInProgress = true
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(newCursor), "comment_toggle")
                                        snapshotUndo.pushForce(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(newText, TextRange(newCursor), extraCursors))
                                        undoRedoInProgress = false
                                        true
                                    }
                                    EditorAction.DELETE_LINE -> {
                                        val cursor = value.selection.end
                                        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                        val lineEnd = value.text.indexOf('\n', cursor).let { if (it == -1) value.text.length else it + 1 }
                                        val deletedText = value.text.substring(lineStart, lineEnd)
                                        val newText = value.text.removeRange(lineStart, lineEnd)
                                        undoRedoInProgress = true
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(lineStart), "delete_line")
                                        snapshotUndo.pushForce(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(newText, TextRange(lineStart), extraCursors))
                                        undoRedoInProgress = false
                                        true
                                    }
                                    EditorAction.FIND -> {
                                        onFindReplaceOpen()
                                        true
                                    }
                                    EditorAction.GO_TO_LINE -> {
                                        onGoToLineOpen()
                                        true
                                    }
                                    EditorAction.SAVE -> {
                                        onSave?.invoke()
                                        true
                                    }
                                    else -> false
                                }
                            } else if (event.type == KeyEventType.KeyDown &&
                                       event.nativeKeyEvent.isAltPressed) {
                                // Alt+Up/Down via KeyBindingRegistry
                                val altAction = KeyBindingRegistry.match(
                                    event.key,
                                    ctrl = false,
                                    shift = false,
                                    alt = true
                                )
                                when (altAction) {
                                    EditorAction.MOVE_LINE_UP -> {
                                        val cursor = value.selection.end
                                        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                        val lineEnd = value.text.indexOf('\n', cursor).let { if (it == -1) value.text.length else it }
                                        if (lineStart == 0) return@onPreviewKeyEvent true
                                        val prevLineEnd = lineStart - 1
                                        val prevLineStart = value.text.lastIndexOf('\n', (prevLineEnd - 1).coerceAtLeast(0)) + 1
                                        val currentLine = value.text.substring(lineStart, lineEnd)
                                        val prevLine = value.text.substring(prevLineStart, prevLineEnd)
                                        val newText = value.text.substring(0, prevLineStart) + currentLine + "\n" + prevLine + value.text.substring(lineEnd)
                                        undoRedoInProgress = true
                                        val newCursor = prevLineStart + currentLine.length
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(newCursor), "move_line_up")
                                        snapshotUndo.pushForce(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(newText, TextRange(newCursor), extraCursors))
                                        undoRedoInProgress = false
                                        true
                                    }
                                    EditorAction.MOVE_LINE_DOWN -> {
                                        val cursor = value.selection.end
                                        val lineStart = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                        val lineEnd = value.text.indexOf('\n', cursor).let { if (it == -1) value.text.length else it }
                                        val nextLineStart = lineEnd + 1
                                        val nextLineEnd = value.text.indexOf('\n', nextLineStart).let { if (it == -1) value.text.length else it }
                                        if (nextLineStart > value.text.length) return@onPreviewKeyEvent true
                                        val currentLine = value.text.substring(lineStart, lineEnd)
                                        val nextLine = value.text.substring(nextLineStart, nextLineEnd)
                                        val newText = value.text.substring(0, lineStart) + nextLine + "\n" + currentLine + value.text.substring(nextLineEnd)
                                        undoRedoInProgress = true
                                        val newCursor = lineStart + nextLine.length
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, TextRange(newCursor), "move_line_down")
                                        snapshotUndo.pushForce(com.codespace.ide.editor.undo.SnapshotUndoManager.TextSnapshot(newText, TextRange(newCursor), extraCursors))
                                        undoRedoInProgress = false
                                        true
                                    }
                                    else -> false
                                }

                            } else {
                                false // let BasicTextField handle normally
                            }
                        }
                        // P-CURSOR: Custom cursor overlay extracted to separate function
                        .then(cursorOverlayModifier(
                            textLayoutResult = textLayoutResult,
                            selection = value.selection,
                            cursorColor = colors.cursor,
                        ))
                        .then(wordHighlightModifier(
                            textLayoutResult = textLayoutResult,
                            text = value.text,
                            selection = value.selection,
                            highlightColor = colors.selection.copy(alpha = 0.3f),
                        ))
                        .then(bracketMatchModifier(
                            textLayoutResult = textLayoutResult,
                            bracketMatch = _bracketMatch,
                            highlightColor = colors.selection.copy(alpha = 0.4f),
                        ))
                        .then(customCursorInteractionModifier(
                            textLayoutResult = textLayoutResult,
                            onCursorMoved = { pos ->
                                value = value.copy(selection = TextRange(pos))
                                // Phase X-2: Tag as user cursor move (keyboard arrows)
                                editorEvent = EditorEvent.UserCursorMove(pos)
                            },
                            onTap = {
                                // STABILITY-FIX: requestFocus() can throw
                                // "ActiveParent with no focused child" if another
                                // dialog/field released focus in the same frame —
                                // known Compose Foundation focus-system race.
                                try { focusRequester.requestFocus() } catch (_: IllegalArgumentException) {}
                            },
                        )),
                )

            }
        }

        // ── P2-11 Inlay hint overlay ───────────────────────────────────
        if (showInlayHints && inlayHints.isNotEmpty()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val lineHeightDpInlay = lineHeightDp  // use the shared density-corrected value
            val gutterWidthDp = if (blameData != null) 72.dp + 120.dp else 72.dp
            val layoutInlay = textLayoutResult
            inlayHints.forEach { hint ->
                val displayIdx = visualLineMapper.docToVisualLine(hint.line)
                if (displayIdx < 0) return@forEach
                val yOffset = if (layoutInlay != null && displayIdx < layoutInlay.lineCount) {
                    ((layoutInlay.getLineTop(displayIdx) - vScroll.value) / density.density).dp
                } else {
                    lineHeightDpInlay * displayIdx - vScrollDp.dp
                }
                val hintColor = when (hint.kind) {
                    InlayHint.Kind.TYPE   -> androidx.compose.ui.graphics.Color(0xFF888888)
                    InlayHint.Kind.RETURN -> androidx.compose.ui.graphics.Color(0xFF7A9EC2)
                    InlayHint.Kind.PARAM  -> androidx.compose.ui.graphics.Color(0xFFB5A05A)
                }
                Box(
                    modifier = Modifier
                        .padding(start = gutterWidthDp)
                        .offset(y = yOffset)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = hint.label,
                        color = hintColor,
                        fontSize = (fontSize - 2).coerceAtLeast(8).sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(
                                color = colors.background.copy(alpha = 0.75f),
                                shape = RoundedCornerShape(2.dp),
                            )
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }

        // R3-4: Diagnostic tooltip popup
        if (showDiagnosticTooltip && diagnosticTooltipLine >= 0) {
            val tooltipErrors = lintErrors.filter { err ->
                val errLine = value.text.substring(0, err.start.coerceIn(0, value.text.length)).count { it == '\n' }
                errLine == diagnosticTooltipLine
            }
            if (tooltipErrors.isNotEmpty()) {
                val layoutDiag = textLayoutResult
                val tooltipTop = if (layoutDiag != null && (diagnosticTooltipLine + 1) < layoutDiag.lineCount) {
                    ((layoutDiag.getLineTop(diagnosticTooltipLine + 1) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    (diagnosticTooltipLine * lineHeightDp.value - vScrollDp + lineHeightDp.value).coerceAtLeast(0f)
                }
                DiagnosticTooltip(
                    errors = tooltipErrors,
                    topDp = tooltipTop,
                    onStartDp = GUTTER_WIDTH + 4f,
                    onDismiss = { showDiagnosticTooltip = false },
                )
            }
        }

        BlameLineOverlay(blameData, lineHeightDp, colors, vScroll)

        MergeConflictOverlay(toggles, conflictData, lineHeightDp, onResolveConflict)

        // R3-2: Indent guide overlay
        val visibleStartLine = (vScrollDp / lineHeightDp.value).toInt().coerceAtLeast(0)
        val visibleEndLine = visibleStartLine + (LocalConfiguration.current.screenHeightDp / lineHeightDp.value).toInt() + 5
        BlockLineOverlay(value.text, vScrollDp, lineHeightDp.value, fontSize, GUTTER_WIDTH.toFloat(), 4, visibleStartLine, visibleEndLine, colors, textLayoutResult, visualLineMapper, vScroll.value)

        SearchMatchOverlay(findReplaceOpen || externalFindBarOpen, matches, matchIndex, lineHeightDp, fontSize, GUTTER_WIDTH, vScrollDp, value, positionMapper, textLayoutResult, vScroll.value)

        ExtraCursorOverlay(extraCursors, lineHeightDp, fontSize, GUTTER_WIDTH, vScrollDp, value, positionMapper, colors, textLayoutResult, vScroll.value)

        // P54: Debug current-line background highlight (yellow tint, like VS Code)
        if (debugCurrentLine > 0) {
            val topDbg = ((debugCurrentLine - 1) * lineHeightDp.value - vScrollDp).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = GUTTER_WIDTH.dp, top = topDbg.dp)
                    .height(lineHeightDp)
                    .background(Color(0xFFCCA700).copy(alpha = 0.12f))
                    .zIndex(2.5f),
            )
        }
        // BLINKING highlight on the target line — blinks for 6s then fades
        if (highlightTargetLine > 0) {
            // Read blinkTick to trigger recomposition for blink animation
            @Suppress("UNUSED_VARIABLE") val tick = blinkTick
            val lineHeightPxHl = lineHeightDp.value
            val gutterDpHl = GUTTER_WIDTH
            val scrollOffsetPxHl = vScrollDp
            val layoutHl = textLayoutResult
            val visualLineHl = visualLineMapper.docToVisualLine(highlightTargetLine - 1)
            val topDpHl = if (layoutHl != null && visualLineHl < layoutHl.lineCount) {
                ((layoutHl.getLineTop(visualLineHl) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
            } else {
                ((highlightTargetLine - 1) * lineHeightPxHl - scrollOffsetPxHl).coerceAtLeast(0f)
            }
            // Compute blink alpha from elapsed time
            val blinkElapsed = if (highlightBlinkStart > 0) (System.currentTimeMillis() - highlightBlinkStart) / 1000f else 0f
            val isBlinking = blinkElapsed < 6f
            val phase = (blinkElapsed * 1000f) % 600f / 600f
            val blinkAlpha = if (isBlinking) {
                if (phase < 0.5f) 0.45f - (phase * 2f * 0.35f) else 0.10f + ((phase - 0.5f) * 2f * 0.35f)
            } else 0.12f
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .offset(x = gutterDpHl.dp, y = topDpHl.dp)
                    .height(lineHeightDp)
                    .background(Color(0xFFFFD700).copy(alpha = blinkAlpha))
                    .zIndex(3.5f),
            )
            // Thin gold bar on the left edge of the highlighted line
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = gutterDpHl.dp, y = topDpHl.dp)
                    .width(3.dp)
                    .height(lineHeightDp)
                    .background(Color(0xFFFFD700).copy(alpha = if (isBlinking) 0.9f else 0.4f))
                    .zIndex(4.5f),
            )
        }

        // P26-1: LSP Document Highlight — subtle background tint on all occurrences
        if (toggles.showLspHighlights && lspHighlightLines.isNotEmpty()) {
            val lineHeightPxHighlight = lineHeightDp.value  // P50-FIX: density-corrected line height
            val gutterDpHighlight = GUTTER_WIDTH
            // BUG-3 FIX: subtract scroll offset so highlights track the correct lines on scroll
            val scrollOffsetPx = vScrollDp
            val layoutDH = textLayoutResult
            lspHighlightLines.forEach { (startLine, endLine) ->
                val visualStartDH = visualLineMapper.docToVisualLine(startLine)
                val visualEndDH = visualLineMapper.docToVisualLine(endLine)
                val topDp = if (layoutDH != null && visualStartDH < layoutDH.lineCount) {
                    ((layoutDH.getLineTop(visualStartDH) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    (startLine * lineHeightPxHighlight - scrollOffsetPx).coerceAtLeast(0f)
                }
                val heightDp = if (layoutDH != null && visualEndDH < layoutDH.lineCount && visualEndDH >= visualStartDH) {
                    ((layoutDH.getLineBottom(visualEndDH) - layoutDH.getLineTop(visualStartDH)) / androidx.compose.ui.platform.LocalDensity.current.density).coerceAtLeast(0f)
                } else {
                    ((endLine - startLine + 1) * lineHeightPxHighlight).coerceAtLeast(0f)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = gutterDpHighlight.dp, top = topDp.dp)
                        .height(heightDp.dp)
                        .background(Color(0xFF569CD6).copy(alpha = 0.12f))
                        .zIndex(3f),
                )
            }
        }

        // P41-K: Color swatches — inline color indicators from LSP documentColor
        if (toggles.showColorSwatches && lspDocumentColors != null && lspDocumentColors!!.length() > 0) {
            val lineHeightPxCS = lineHeightDp.value  // P50-FIX: density-corrected line height
            val gutterDpCS = GUTTER_WIDTH
            val charWidthPxCS = editorMetrics.charWidthPx  // Phase E
            for (ci in 0 until lspDocumentColors!!.length()) {
                val colorInfo = lspDocumentColors!!.optJSONObject(ci) ?: continue
                val range = colorInfo.optJSONObject("range") ?: continue
                val startLine = range.optJSONObject("start")?.optInt("line", -1) ?: -1
                val startChar = range.optJSONObject("start")?.optInt("character", 0) ?: 0
                if (startLine < 0) continue
                val color = colorInfo.optJSONObject("color") ?: continue
                val r = color.optDouble("red", 0.0)
                val g = color.optDouble("green", 0.0)
                val b = color.optDouble("blue", 0.0)
                val a = color.optDouble("alpha", 1.0)
                val swatchColor = Color(
                    red = r.toFloat(),
                    green = g.toFloat(),
                    blue = b.toFloat(),
                    alpha = a.toFloat()
                )
                val layoutCS = textLayoutResult
                val visualLineCS = visualLineMapper.docToVisualLine(startLine)
                val startOffsetCS = positionMapper.lineColumnToOffset(startLine, startChar)
                val swatchTopDp = if (layoutCS != null && visualLineCS < layoutCS.lineCount) {
                    ((layoutCS.getLineTop(visualLineCS) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    startLine * lineHeightPxCS - vScrollDp
                }
                val swatchLeftDp = if (layoutCS != null) {
                    val safeStartOffsetCS = startOffsetCS.coerceIn(0, layoutCS.layoutInput.text.length)
                    (layoutCS.getHorizontalPosition(safeStartOffsetCS, true) / androidx.compose.ui.platform.LocalDensity.current.density) + gutterDpCS - 4f
                } else {
                    gutterDpCS + (startChar * charWidthPxCS) - 4f
                }
                if (swatchTopDp >= 0 && swatchTopDp < (visualLineMapper.visualLineCount + 5) * lineHeightPxCS) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = swatchLeftDp.dp, top = swatchTopDp.dp)
                            .size(10.dp)
                            .background(swatchColor, RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFF555555), RoundedCornerShape(2.dp))
                            .zIndex(4f)
                    )
                }
            }
        }

        // P41-O1: Error Lens — inline error text at end of code lines (VS Code-style)
        // EDITOR-FIX: Extracted to ErrorLensOverlay.kt with same-line diagnostic stacking
        ErrorLensOverlay(
            showErrorLens = toggles.showErrorLens,
            lintErrors = lintErrors,
            hasCompletions = showCompletions,
            value = value,
            lineHeightDp = lineHeightDp,
            vScrollDp = vScrollDp,
            fontSize = fontSize,
            GUTTER_WIDTH = GUTTER_WIDTH,
            displayLineCount = visualLineMapper.visualLineCount,
            textLayoutResult = textLayoutResult,
            positionMapper = positionMapper,
        )
        // P26-1: LSP Code Lens — inline annotations at end of lines (e.g. "3 references")
        if (toggles.showCodeLens && lspCodeLenses != null && lspCodeLenses!!.length() > 0) {
            val lineHeightPxCL = lineHeightDp.value  // P50-FIX: density-corrected line height
            val gutterDpCL = GUTTER_WIDTH
            for (i in 0 until lspCodeLenses!!.length()) {
                val lens = lspCodeLenses!!.optJSONObject(i) ?: continue
                val range = lens.optJSONObject("range") ?: continue
                val startLine = range.optJSONObject("start")?.optInt("line", -1) ?: -1
                if (startLine < 0) continue
                val command = lens.optJSONObject("command")
                val title = command?.optString("title", "") ?: lens.optString("title", "")
                if (title.isBlank()) continue
                // BUG-3 FIX: subtract scroll offset
                val layoutCL = textLayoutResult
                val visualLineCL = visualLineMapper.docToVisualLine(startLine)
                val topDpCL = if (layoutCL != null && visualLineCL < layoutCL.lineCount) {
                    ((layoutCL.getLineTop(visualLineCL) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    (startLine * lineHeightPxCL - vScrollDp).coerceAtLeast(0f)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = (topDpCL + 1).dp)
                        .zIndex(4f),
                ) {
                    Text(
                        text = title,
                        color = Color(0xFF4EC9B0),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .background(colors.background.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .then(
                                if (onCodeLensClick != null && command != null) {
                                    Modifier.clickable { onCodeLensClick(lens) }
                                } else Modifier
                            )
                    )
                }
            }
        }

        // P26-1: LSP Inlay Hints — inline type/parameter hints within code
        if (toggles.showInlayHints && lspInlayHints != null && lspInlayHints!!.length() > 0) {
            val lineHeightPxIH = lineHeightDp.value  // P50-FIX: density-corrected line height
            val gutterDpIH = GUTTER_WIDTH
            val charWidthPx = editorMetrics.charWidthPx  // Phase E
            for (i in 0 until lspInlayHints!!.length()) {
                val hint = lspInlayHints!!.optJSONObject(i) ?: continue
                val position = hint.optJSONObject("position") ?: continue
                val line = position.optInt("line", -1)
                val character = position.optInt("character", -1)
                if (line < 0 || character < 0) continue
                val label = when (val l = hint.opt("label")) {
                    is String -> l
                    is org.json.JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until l.length()) {
                            val part = l.optJSONObject(j)?.optString("value", "") ?: ""
                            sb.append(part)
                        }
                        sb.toString()
                    }
                    else -> ""
                }
                if (label.isBlank()) continue
                val layoutLsp = textLayoutResult
                val topDpIH = if (layoutLsp != null && line < layoutLsp.lineCount) {
                    ((layoutLsp.getLineTop(line) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    (line * lineHeightPxIH - vScrollDp).coerceAtLeast(0f)
                }
                val leftDpIH = if (layoutLsp != null) {
                    val lineStartOff = positionMapper.lineStart(line)
                    val charOffset = (lineStartOff + character).coerceIn(0, layoutLsp.layoutInput.text.length)
                    (layoutLsp.getHorizontalPosition(charOffset, true) / androidx.compose.ui.platform.LocalDensity.current.density) + gutterDpIH
                } else {
                    gutterDpIH + character * charWidthPx
                }
                val paddingLeft = if (hint.optBoolean("paddingLeft", false)) 2f else 0f
                val paddingRight = if (hint.optBoolean("paddingRight", false)) 2f else 0f
                Text(
                    text = label,
                    color = Color(0xFF9C9C9C),
                    fontSize = (fontSize - 2).sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = (leftDpIH + paddingLeft).dp, top = (topDpIH + 2).dp)
                        .zIndex(4f)
                )
            }
        }

        // P26-1: LSP Document Links — clickable underlined links in comments
        if (toggles.showDocumentLinks && lspDocumentLinks != null && lspDocumentLinks!!.length() > 0) {
            val lineHeightPxDL = lineHeightDp.value  // P50-FIX: density-corrected line height
            val gutterDpDL = GUTTER_WIDTH
            val charWidthPxDL = editorMetrics.charWidthPx  // Phase E
            for (i in 0 until lspDocumentLinks!!.length()) {
                val link = lspDocumentLinks!!.optJSONObject(i) ?: continue
                val range = link.optJSONObject("range") ?: continue
                val startLine = range.optJSONObject("start")?.optInt("line", -1) ?: -1
                val startChar = range.optJSONObject("start")?.optInt("character", -1) ?: -1
                val endChar = range.optJSONObject("end")?.optInt("character", -1) ?: -1
                if (startLine < 0 || startChar < 0) continue
                val target = link.optString("target", "")
                val tooltip = link.optString("tooltip", target)
                if (target.isBlank()) continue
                val layoutDL = textLayoutResult
                val visualLineDL = visualLineMapper.docToVisualLine(startLine)
                val startOffsetDL = positionMapper.lineColumnToOffset(startLine, startChar)
                val endOffsetDL = positionMapper.lineColumnToOffset(startLine, endChar)
                val topDpDL = if (layoutDL != null && visualLineDL < layoutDL.lineCount) {
                    ((layoutDL.getLineTop(visualLineDL) - vScroll.value).coerceAtLeast(0f)) / androidx.compose.ui.platform.LocalDensity.current.density
                } else {
                    (startLine * lineHeightPxDL - vScrollDp).coerceAtLeast(0f)
                }
                val safeStartOffsetDL = startOffsetDL.coerceIn(0, layoutDL?.layoutInput?.text?.length ?: 0)
                val safeEndOffsetDL = endOffsetDL.coerceIn(0, layoutDL?.layoutInput?.text?.length ?: 0)
                val leftDpDL = if (layoutDL != null) {
                    (layoutDL.getHorizontalPosition(safeStartOffsetDL, true) / androidx.compose.ui.platform.LocalDensity.current.density) + gutterDpDL
                } else {
                    gutterDpDL + startChar * charWidthPxDL
                }
                val widthDp = if (layoutDL != null && safeEndOffsetDL > safeStartOffsetDL) {
                    ((layoutDL.getHorizontalPosition(safeEndOffsetDL, true) - layoutDL.getHorizontalPosition(safeStartOffsetDL, true)) / androidx.compose.ui.platform.LocalDensity.current.density).coerceAtLeast(20f)
                } else {
                    (endChar - startChar) * charWidthPxDL
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = leftDpDL.dp, top = topDpDL.dp)
                        .width(widthDp.dp.coerceAtLeast(20.dp))
                        .height((fontSize + 2).dp)
                        .clickable {
                            // Open link in browser (handled by caller)
                            onOpenFileAtLine?.invoke(target, 0)
                        }
                        .zIndex(5f),
                ) {
                    Text(
                        text = tooltip.take(40),
                        color = Color(0xFF569CD6),
                        fontSize = (fontSize - 1).sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }

        // Extra-cursor clear chip
        if (extraCursors.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = GUTTER_WIDTH.dp, top = 4.dp)
                    .background(Color(0xFF007ACC), RoundedCornerShape(3.dp))
                    .clickable { extraCursors = emptyList() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .zIndex(10f),
            ) {
                Text("${extraCursors.size}× cursors ✕", color = colors.text, fontSize = 10.sp)
            }
        }

        // Minimap + overview ruler + indentation guides (extracted to MinimapSection.kt)
        MinimapSection(
            value = value,
            lineHeightDp = lineHeightDp,
            vScroll = vScroll,
            colors = colors,
            showMinimapState = showMinimapState,
            onToggleMinimap = { showMinimapState = !showMinimapState },
            positionMapper = positionMapper,
            lintErrors = lintErrors,
            coroutineScope = coroutineScope,
        )
        
        // ── Rename Symbol Dialog ──────────────────────────────────────────────
        // ── P2-4 Context Action Sheet ──────────────────────────────────────────────────────


        // ── Floating LSP action button ──────────────────────────────────────────────────────
        // Shows a small "..." button when text is selected, opening a compact popup
        // with LSP-powered actions (Go to Definition, Rename, etc.) — NOT a full-screen dialog.
        // Native Copy/Cut/Paste/Select All come from BasicTextField's built-in selection toolbar.
        // P38-FIX: Show ⋮ button on TAP (not just selection) — cursor on a word is enough
        if (!findReplaceOpen && !goToLineOpen) {
            val selWord = remember(value.selection.start, value.selection.end) {
                currentWord(value.text, value.selection.start)
            }
            if (selWord != null && selWord.length >= 1) {
                Popup(
                    alignment = androidx.compose.ui.Alignment.TopEnd,
                    offset = androidx.compose.ui.unit.IntOffset(0, 0),
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
            // Touch consumption handled by popup content
                ) {
                    var showLspMenu by remember { mutableStateOf(false) }
                    // P38-FIX: Auto-open LSP menu when long-press triggers
                    LaunchedEffect(longPressTrigger) {
                        if (longPressTrigger > 0) showLspMenu = true
                    }
                    androidx.compose.material3.Surface(
                        color = colors.background,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { showLspMenu = true },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Code actions",
                                tint = Color(0xFF4EC9B0),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showLspMenu,
                            onDismissRequest = { showLspMenu = false },
                            modifier = Modifier.background(colors.background)
                        ) {
                            // DONE-CURSOR-2: Compacted popup menu — constrained width + denser layout
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                            val selectedText = value.text.substring(
                                value.selection.start.coerceIn(0, value.text.length),
                                value.selection.end.coerceIn(0, value.text.length)
                            )
                            val word = selWord

                            // Fix with AI (if there's a nearby error)
                            val nearbyError = lintErrors.firstOrNull { err ->
                                val errLine = positionMapper.offsetToLine(err.start)
                                val selLine = positionMapper.offsetToLine(value.selection.start)
                                kotlin.math.abs(errLine - selLine) <= 2
                            }
                            if (nearbyError != null) {
                                val errLine = positionMapper.offsetToLine(nearbyError.start) + 1
                                DropdownMenuItem(
                                    text = { Text("⚡ Fix with AI", color = Color(0xFF4EC9B0), fontSize = 13.sp) },
                                    onClick = {
                                        onAiFixRequest?.invoke(
                                            "Fix this ${language.name} error on line $errLine:\n" +
                                            "Code: `$selectedText`\n" +
                                            "Error: ${nearbyError.message}\n" +
                                            "Full context:\n${value.text.lines().drop((errLine - 3).coerceAtLeast(0)).take(10).joinToString("\n")}"
                                        )
                                        showLspMenu = false
                                    }
                                )
                            }

                            // P39: Code Actions from LSP (categorized by kind)
                            if (lspCodeActionProvider != null) {
                                val allActions: List<com.codespace.ide.lsp.LspCodeAction> =
                                    remember(value.selection.start) {
                                        val cursorLine = positionMapper.offsetToLine(value.selection.start)
                                        try { lspCodeActionProvider.invoke(cursorLine) }
                                        catch (_: Exception) { emptyList() }
                                    }
                                if (allActions.isNotEmpty()) {
                                    val categorized = com.codespace.ide.lsp.categorizeCodeActions(allActions)
                                    categorized.forEach { (groupLabel, actions) ->
                                        // Group header
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    groupLabel.uppercase(),
                                                    color = Color(0xFF858585),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        actions.forEach { fix ->
                                            val icon = com.codespace.ide.lsp.CodeActionKind.icon(fix.kind)
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(icon, fontSize = 12.sp)
                                                        Text(
                                                            fix.title,
                                                            color = if (fix.disabled != null) Color(0xFF666666)
                                                                   else if (fix.isPreferred) Color(0xFFFFD700)
                                                                   else Color(0xFFD4D4D4),
                                                            fontSize = 13.sp,
                                                            fontWeight = if (fix.isPreferred) FontWeight.Bold else FontWeight.Normal,
                                                        )
                                                    }
                                                },
                                                enabled = fix.disabled == null,
                                                onClick = {
                                                    // P39: Handle AI actions via onAiFixRequest
                                                    if (fix.kind != null && fix.kind.startsWith("ai.") && onAiFixRequest != null) {
                                                        // P41-O: Use full selection if available, else current line
                                                        val hasSelection = value.selection.start != value.selection.end
                                                        val selText = if (hasSelection) {
                                                            value.text.substring(
                                                                value.selection.start.coerceIn(0, value.text.length),
                                                                value.selection.end.coerceIn(0, value.text.length)
                                                            )
                                                        } else {
                                                            val lineStart = positionMapper.lineStart(positionMapper.offsetToLine(value.selection.start))
                                                            val lineEnd = value.text.indexOf('\n', value.selection.start)
                                                            value.text.substring(lineStart, if (lineEnd < 0) value.text.length else lineEnd)
                                                        }
                                                        // P41-O: Project-aware context — include file name, language, imports
                                                        val fileName = filePath?.substringAfterLast('/') ?: "untitled"
                                                        val langName = language.displayName
                                                        val imports = value.text.lines().take(30).filter {
                                                            it.trim().startsWith("import ") || it.trim().startsWith("from ") || it.trim().startsWith("package ") || it.trim().startsWith("#include")
                                                        }.joinToString("\n")
                                                        val contextHeader = "File: $fileName ($langName)\n" +
                                                            (if (imports.isNotEmpty()) "Imports:\n$imports\n" else "") +
                                                            "Selection (${if (hasSelection) "selected text" else "current line"}):\n"
                                                        // P41-O: For Explain Error, include the diagnostic message at cursor
                                                        val diagAtCursor = if (fix.kind == com.codespace.ide.lsp.CodeActionKind.AIExplainError) {
                                                            val cursorOff = value.selection.start.coerceIn(0, value.text.length)
                                                            val matchingErr = lintErrors.firstOrNull { err ->
                                                                err.start <= cursorOff && err.end >= cursorOff
                                                            }
                                                            if (matchingErr != null) "Error: ${matchingErr.message}${if (matchingErr.code != null) " [${matchingErr.code}]" else ""}\n" else ""
                                                        } else ""
                                                        val prompt = when (fix.kind) {
                                                            com.codespace.ide.lsp.CodeActionKind.AIExplain -> contextHeader + "Explain this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIGenerateDoc -> contextHeader + "Generate documentation for this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIGenerateTests -> contextHeader + "Generate unit tests for this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIOptimize -> contextHeader + "Optimize this code for better performance:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIRewrite -> contextHeader + "Rewrite this code for better clarity:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AISimplify -> contextHeader + "Simplify this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIRefactor -> contextHeader + "Refactor this code for better structure and readability:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIAddComments -> contextHeader + "Add inline comments to this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIExplainError -> contextHeader + diagAtCursor + "Explain the error in this code:\n" + selText
                                                            com.codespace.ide.lsp.CodeActionKind.AIImprovePerf -> contextHeader + "Suggest performance improvements for:\n" + selText
                                                            else -> contextHeader + fix.title + ":\n" + selText
                                                        }
                                                        onAiFixRequest!!.invoke(prompt)
                                                    } else if (fix.edit != null) {
                                                        try {
                                                            val newText = com.codespace.ide.lsp.applyWorkspaceEdit(
                                                                fix.edit, value.text, null
                                                            )
                                                            if (newText != null && newText != value.text) {
                                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                                programmaticTextChange(newText, TextRange(value.selection.start), "ai_suggestion_apply")
                                                            }
                                                        } catch (_: Exception) {}
                                                    } else if (fix.command != null) {
                                                        // P39-FULL: Handle command-based code actions
                                                        try {
                                                            val cmdJson = org.json.JSONObject(fix.command)
                                                            val cmdName = cmdJson.optString("command", "")
                                                            val cmdArgs = cmdJson.optJSONArray("arguments")
                                                            if (cmdName.isNotEmpty()) {
                                                                LspManager.executeCommand(language, cmdName, cmdArgs)
                                                            }
                                                        } catch (_: Exception) {}
                                                    }
                                                    showLspMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Expand Selection
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⤢", color = if (onLspSelectionRange != null) Color(0xFF4EC9B0) else Color(0xFF808080), fontSize = 14.sp)
                                        Text("Expand Selection", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        if (onLspSelectionRange != null && expandSelectionDepth >= 0) {
                                            Text("L${expandSelectionDepth + 1}", color = Color(0xFF4EC9B0), fontSize = 10.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    if (onLspSelectionRange != null) {
                                        try {
                                            val cPos = positionMapper.offsetToPosition(value.selection.start)
                                            val cLine = cPos.line
                                            val cCol = cPos.column
                                            val resp = onLspSelectionRange.invoke(cLine, cCol)
                                            if (resp != null && resp.length() > 0) {
                                                val ranges = (0 until resp.length()).map { resp.optJSONObject(it)!! }
                                                expandSelectionRanges = ranges
                                                val targetDepth = if (expandSelectionDepth < 0) 0 else expandSelectionDepth + 1
                                                expandSelectionDepth = targetDepth.coerceAtMost(ranges.size - 1)
                                                expandSelectionUsedLsp = true
                                                if (targetDepth < ranges.size) {
                                                    val r = ranges[targetDepth]
                                                    val sLine = r.optJSONObject("start")?.optInt("line", 0) ?: 0
                                                    val sChar = r.optJSONObject("start")?.optInt("character", 0) ?: 0
                                                    val eLine = r.optJSONObject("end")?.optInt("line", 0) ?: 0
                                                    val eChar = r.optJSONObject("end")?.optInt("character", 0) ?: 0
                                                    var startOff = positionMapper.lspToOffset(sLine, sChar)
                                                    var endOff = positionMapper.lspToOffset(eLine, eChar)
                                                    startOff = startOff.coerceIn(0, value.text.length)
                                                    endOff = endOff.coerceIn(0, value.text.length)
                                                    programmaticCursorMove(startOff, "lsp_selection_range")
                                                }
                                            }
                                        } catch (_: Exception) { expandSelectionUsedLsp = false }
                                    } else {
                                        val pat = Regex("\\b" + Regex.escape(word) + "\\b")
                                        val match = pat.find(value.text, value.selection.start - 1) ?: pat.find(value.text)
                                        if (match != null) {
                                            programmaticCursorMove(match.range.first, "expand_selection_word")
                                        }
                                        expandSelectionUsedLsp = false
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Go to Definition
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⇒", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Go to Definition", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    // BUG-4 FIX: Try LSP definition first (real semantic navigation),
                                    // fall back to regex pattern matching only if LSP fails/unavailable.
                                    // TEST-11-FIX: Pass current cursor position from selection, not stale lspCursorLine/Col
                                    var lspDefSucceeded = false
                                    if (onLspDefinition != null) {
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        lspDefSucceeded = onLspDefinition!!.invoke(cLine, cCol)
                                    }
                                    if (!lspDefSucceeded) {
                                    val lines = value.text.split("\n")
                                    val kw = "(?:fun|class|object|interface|val|var|const val|def|function|const|let|type|struct|enum|trait|impl)"
                                    val declPat = Regex(kw + "\\s+" + Regex.escape(word) + "\\b")
                                    val found = lines.mapIndexedNotNull { idx, ln ->
                                        if (declPat.containsMatchIn(ln)) DefResult(idx, ln.trim()) else null
                                    }
                                    gotoResults = found
                                    crossFileResults = if (projectRoot != null) {
                                        FileIndexer.search(word).filter { it.kind in listOf("class", "function", "interface", "enum", "object") }.take(10)
                                            .map { CrossFileDefResult(it.name, it.kind, it.filePath, it.line, it.fileName) }
                                    } else null
                                    } // end if (!lspDefSucceeded)
                                    showLspMenu = false
                                }
                            )

                            // P41-O5: Go to Declaration (LSP + regex fallback)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⇒", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Go to Declaration", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    var lspDeclSucceeded = false
                                    if (onLspDeclaration != null) {
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        lspDeclSucceeded = onLspDeclaration!!.invoke(cLine, cCol)
                                    }
                                    if (!lspDeclSucceeded) {
                                        val lines = value.text.split("\n")
                                        val kw = "(?:fun|class|object|interface|val|var|const val|def|function|const|let|type|struct|enum|trait|impl)"
                                        val declPat = Regex(kw + "\\s+" + Regex.escape(word) + "\\b")
                                        val found = lines.mapIndexedNotNull { idx, ln ->
                                            if (declPat.containsMatchIn(ln)) DefResult(idx, ln.trim()) else null
                                        }
                                        gotoResults = found
                                        crossFileResults = if (projectRoot != null) {
                                            FileIndexer.search(word).filter { it.kind in listOf("class", "function", "interface", "enum", "object") }.take(10)
                                                .map { CrossFileDefResult(it.name, it.kind, it.filePath, it.line, it.fileName) }
                                        } else null
                                    }
                                    showLspMenu = false
                                }
                            )
                            // Peek Definition
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("👁", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Peek Definition", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    // BUG-4 FIX: Try LSP definition first for peek, fall back to regex
                                    // TEST-11-FIX: Pass current cursor position from selection
                                    var lspPeekSucceeded = false
                                    if (onLspDefinition != null) {
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        lspPeekSucceeded = onLspDefinition!!.invoke(cLine, cCol)
                                    }
                                    if (!lspPeekSucceeded) {
                                    val lines = value.text.split("\n")
                                    val kw = "(?:fun|class|object|interface|val|var|const val|def|function|const|let|type|struct|enum|trait|impl)"
                                    val declPat = Regex(kw + "\\s+" + Regex.escape(word) + "\\b")
                                    val found = lines.mapIndexedNotNull { idx, ln ->
                                        if (declPat.containsMatchIn(ln)) DefResult(idx, ln.trim()) else null
                                    }
                                    if (found.isNotEmpty()) {
                                        val f = found.first()
                                        peekDefResult = PeekDefResult(
                                            filePath = filePath ?: "(current)",
                                            line = f.line,
                                            lines = lines,
                                            defLine = f.line
                                        )
                                    }
                                    } // end if (!lspPeekSucceeded)
                                    showLspMenu = false
                                }
                            )

                            // Go to Type Definition
                            if (onLspTypeDefinition != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("T", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Go to Type Definition", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        typeDefUsedLsp = onLspTypeDefinition.invoke()
                                        showLspMenu = false
                                    }
                                )
                            }

                            // Find Implementations (LSP + regex fallback)
                            if (onLspImplementation != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("I", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Find Implementations", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        implUsedLsp = onLspImplementation.invoke(cLine, cCol)
                                        if (!implUsedLsp) {
                                            val lines = value.text.split("\n")
                                            val implPat = Regex("(?:class|object|struct|impl|enum)\\s+" + Regex.escape(word) + "\\b")
                                            val found = lines.mapIndexedNotNull { idx, ln ->
                                                if (implPat.containsMatchIn(ln)) DefResult(idx, ln.trim()) else null
                                            }
                                            gotoResults = found
                                        }
                                        showLspMenu = false
                                    }
                                )
                            }

                            // Rename Symbol
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("✎", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Rename Symbol", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    if (onLspPrepareRename != null) {
                                        val pos = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(pos)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        val renameInfo = try { onLspPrepareRename.invoke(cLine, cCol) } catch (_: Exception) { null }
                                        val placeholder = renameInfo?.optString("placeholder", "") ?: word
                                        renameDialogWord = word
                                        renameNewName = placeholder
                                        renameUsedLsp = true
                                    } else {
                                        renameDialogWord = word
                                        renameNewName = word
                                        renameUsedLsp = false
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Select All Occurrences — add cursor at every match (VSCode Ctrl+Shift+L)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("■", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Select All Occurrences", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    showLspMenu = false
                                    val pat = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE)
                                    val matches = pat.findAll(value.text).toList()
                                    if (matches.isNotEmpty()) {
                                        // Primary cursor goes to first match
                                        val first = matches.first().range.first
                                        val firstEnd = matches.first().range.last + 1
                                        programmaticCursorMove(first, "select_all_occurrences")
                                        // Extra cursors at all subsequent matches
                                        extraCursors = matches.drop(1).map { it.range.first }.distinct().sorted()
                                        // Scroll to make the first match visible
                                        val matchLine = value.text.substring(0, first).count { it == '\n' }
                                        coroutineScope.launch {
                                            val lhPx = editorMetrics.lineHeightPx
                                            vScroll.animateScrollTo((matchLine * lhPx).toInt())
                                        }
                                    }
                                }
                            )

                            // Select Next Occurrence — add cursor at current match, move to next (VSCode Ctrl+D)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("▸", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Select Next Occurrence", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    showLspMenu = false
                                    val pat = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE)
                                    val currentPos = value.selection.end
                                    val nextMatch = pat.find(value.text, currentPos) ?: pat.find(value.text)
                                    if (nextMatch != null) {
                                        // Add cursor at current selection start before moving
                                        val currentStart = value.selection.start
                                        if (currentStart !in extraCursors) {
                                            extraCursors = (extraCursors + currentStart).distinct().sorted()
                                        }
                                        programmaticCursorMove(nextMatch.range.first, "find_next_occurrence")
                                        // Scroll to make the next occurrence visible
                                        val matchLine = value.text.substring(0, nextMatch.range.first).count { it == '\n' }
                                        coroutineScope.launch {
                                            val lhPx = editorMetrics.lineHeightPx
                                            vScroll.animateScrollTo((matchLine * lhPx).toInt())
                                        }
                                    }
                                }
                            )

                            // Find References
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⊛", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Find References", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    if (onFindReferences != null) {
                                        findRefWord = word
                                        findRefLoading = true
                                        findRefResults = emptyList()
                                        findRefUsedLsp = false
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        val refs = try { onFindReferences.invoke(word, cLine, cCol) } catch (_: Exception) { emptyList<Triple<String, Int, String>>() }
                                        findRefResults = refs
                                        findRefLoading = false
                                        findRefUsedLsp = onFindReferences != null && refs.isNotEmpty()
                                    }
                                    showLspMenu = false
                                }
                            )
                            // P41-H: Peek References — inline overlay
                            if (onFindReferences != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("⊞", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Peek References", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        val cOff2 = value.selection.start
                                        val cPos2 = positionMapper.offsetToPosition(value.selection.start)
                                        val cLine2 = cPos2.line
                                        val cCol2 = cPos2.column
                                        val refs = try { onFindReferences.invoke(word, cLine2, cCol2) } catch (_: Exception) { emptyList<Triple<String, Int, String>>() }
                                        peekRefsResult = PeekRefsResult(word, refs, refs.isNotEmpty())
                                        showLspMenu = false
                                    }
                                )
                            }
                            // P41-H: Peek Declaration — inline overlay
                            if (onLspDeclaration != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("⊞", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Peek Declaration", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        // Use declaration LSP call — reuses the peek definition overlay
                                        // TEST-11-FIX: Pass current cursor position
                                        val cOff = value.selection.start
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        val declSucceeded = onLspDeclaration!!.invoke(cLine, cCol)
                                        if (!declSucceeded) {
                                            // Fallback: show message
                                            peekDeclResult = PeekResult(
                                                title = "Peek Declaration",
                                                filePath = filePath ?: "",
                                                line = 0,
                                                lines = listOf("// No declaration found for '$word'"),
                                                defLine = 0,
                                                usedLsp = false,
                                            )
                                        }
                                        showLspMenu = false
                                    }
                                )
                            }

                            // P41-M: Call Hierarchy
                            if (onPrepareCallHierarchy != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("→", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Call Hierarchy", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        val cOff = value.selection.end
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        val items = try { onPrepareCallHierarchy.invoke(cLine, cCol) } catch (_: Exception) { null }
                                        if (items != null && items.isNotEmpty()) {
                                            callHierarchyRoot = items.first()
                                            showCallHierarchy = true
                                            // Fetch incoming + outgoing in parallel
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val incoming = try { onCallHierarchyIncoming?.invoke(items.first()) ?: emptyList() } catch (_: Exception) { emptyList() }
                                                val outgoing = try { onCallHierarchyOutgoing?.invoke(items.first()) ?: emptyList() } catch (_: Exception) { emptyList() }
                                                withContext(Dispatchers.Main) {
                                                    callHierarchyIncoming = incoming
                                                    callHierarchyOutgoing = outgoing
                                                }
                                            }
                                        }
                                        showLspMenu = false
                                    }
                                )
                            }

                            // P41-M: Type Hierarchy
                            if (onPrepareTypeHierarchy != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("≡", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                            Text("Type Hierarchy", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        val cOff = value.selection.end
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        val items = try { onPrepareTypeHierarchy.invoke(cLine, cCol) } catch (_: Exception) { null }
                                        if (items != null && items.isNotEmpty()) {
                                            typeHierarchyRoot = items.first()
                                            showTypeHierarchy = true
                                            // Fetch supertypes + subtypes in parallel
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val supers = try { onTypeHierarchySupertypes?.invoke(items.first()) ?: emptyList() } catch (_: Exception) { emptyList() }
                                                val subs = try { onTypeHierarchySubtypes?.invoke(items.first()) ?: emptyList() } catch (_: Exception) { emptyList() }
                                                withContext(Dispatchers.Main) {
                                                    typeHierarchySupertypes = supers
                                                    typeHierarchySubtypes = subs
                                                }
                                            }
                                        }
                                        showLspMenu = false
                                    }
                                )
                            }

                            // Add Cursor Above — same column on previous line (VSCode Ctrl+Alt+Up)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("↑", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Add Cursor Above", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    val text = value.text
                                    val cursorPos = value.selection.end
                                    val currentLineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursorPos))
                                    val column = cursorPos - currentLineStart
                                    if (currentLineStart > 0) {
                                        val prevLineEnd = currentLineStart - 1
                                        val prevLineStart = positionMapper.lineStart(positionMapper.offsetToLine(prevLineEnd) - 1) + 1
                                        val prevLineLen = prevLineEnd - prevLineStart
                                        val prevCursor = (prevLineStart + column).coerceIn(prevLineStart, prevLineStart + prevLineLen)
                                        extraCursors = (extraCursors + prevCursor).distinct().sorted()
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Add Cursor Below — same column on next line (VSCode Ctrl+Alt+Down)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("↓", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Add Cursor Below", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    val text = value.text
                                    val cursorPos = value.selection.end
                                    val currentLineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursorPos))
                                    val column = cursorPos - currentLineStart
                                    val nextNewline = text.indexOf('\n', cursorPos)
                                    if (nextNewline >= 0) {
                                        val nextLineStart = nextNewline + 1
                                        val nextLineEnd = text.indexOf('\n', nextLineStart)
                                        val nextLineLen = if (nextLineEnd >= 0) nextLineEnd - nextLineStart else text.length - nextLineStart
                                        val nextCursor = (nextLineStart + column).coerceIn(nextLineStart, nextLineStart + nextLineLen)
                                        extraCursors = (extraCursors + nextCursor).distinct().sorted()
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Cursors on All Lines Below — add cursor at same column on every line below (VSCode Ctrl+Alt+Shift+Down)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⤓", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Cursors on All Lines Below", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    val text = value.text
                                    val cursorPos = value.selection.end
                                    val currentLineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursorPos))
                                    val column = cursorPos - currentLineStart
                                    val newCursors = mutableListOf<Int>()
                                    var searchFrom = cursorPos
                                    while (true) {
                                        val nextNewline = text.indexOf('\n', searchFrom)
                                        if (nextNewline < 0) break
                                        val nextLineStart = nextNewline + 1
                                        val nextLineEnd = text.indexOf('\n', nextLineStart)
                                        val nextLineLen = if (nextLineEnd >= 0) nextLineEnd - nextLineStart else text.length - nextLineStart
                                        val nextCursor = (nextLineStart + column).coerceIn(nextLineStart, nextLineStart + nextLineLen)
                                        newCursors.add(nextCursor)
                                        searchFrom = nextLineStart
                                    }
                                    extraCursors = (extraCursors + newCursors).distinct().sorted()
                                    showLspMenu = false
                                }
                            )

                            // Cursors on All Lines Above — add cursor at same column on every line above
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⤒", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        Text("Cursors on All Lines Above", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    val text = value.text
                                    val cursorPos = value.selection.end
                                    val currentLineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursorPos))
                                    val column = cursorPos - currentLineStart
                                    val newCursors = mutableListOf<Int>()
                                    var lineEnd = currentLineStart - 1
                                    while (lineEnd > 0) {
                                        val prevLineStart = positionMapper.lineStart(positionMapper.offsetToLine(lineEnd) - 1) + 1
                                        val prevLineLen = lineEnd - prevLineStart
                                        val prevCursor = (prevLineStart + column).coerceIn(prevLineStart, prevLineStart + prevLineLen)
                                        newCursors.add(prevCursor)
                                        lineEnd = prevLineStart - 1
                                        if (prevLineStart == 0) break
                                    }
                                    extraCursors = (extraCursors + newCursors).distinct().sorted()
                                    showLspMenu = false
                                }
                            )
                            // P41-I/U: Source Actions — with built-in fallback (no LSP needed)
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⟐", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                            Text("Organize Imports", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = {
                                // P41-U: Try LSP first, fall back to built-in
                                if (onSourceAction != null && com.codespace.ide.lsp.LspManager.isServerRunning(language)) {
                                    onSourceAction!!.invoke("source.organizeImports")
                                } else {
                                    val result = com.codespace.ide.editor.BuiltinSourceActions.organizeImports(value.text, language)
                                    if (result != null) {
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, result, extraCursors)
                                        programmaticTextChange(result, value.selection, "organize_imports")
                                    }
                                }
                                showLspMenu = false
                            }
                            )
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⟇", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                            Text("Remove Unused Imports", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = {
                                // P41-U: Try LSP first, fall back to built-in
                                if (onSourceAction != null && com.codespace.ide.lsp.LspManager.isServerRunning(language)) {
                                    onSourceAction!!.invoke("source.removeUnused")
                                } else {
                                    val result = com.codespace.ide.editor.BuiltinSourceActions.removeUnusedImports(value.text, language)
                                    if (result != null) {
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, result, extraCursors)
                                        programmaticTextChange(result, value.selection, "remove_unused_imports")
                                    }
                                }
                                showLspMenu = false
                            }
                            )
                            // P41-U: Remove Unused Code (built-in, no LSP needed)
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⊘", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                            Text("Remove Unused Code", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = {
                                val result = com.codespace.ide.editor.BuiltinSourceActions.removeUnusedCode(value.text, language)
                                if (result != null) {
                                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, result, extraCursors)
                                    programmaticTextChange(result, value.selection, "external_format")
                                }
                                showLspMenu = false
                            }
                            )
                            if (onSourceAction != null) {
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("✦", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                            Text("Fix All", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = { onSourceAction!!.invoke("source.fixAll"); showLspMenu = false }
                            )
                            }

                            // P41-T: Refactor submenu (extracted to RefactorMenu.kt)
                            RefactorSubmenu(
                                onSourceAction = onSourceAction,
                                onAiFixRequest = onAiFixRequest,
                                onDismiss = { showLspMenu = false }
                            )

                            // P41-L: Code Generation
                            if (onSourceAction != null) {
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("G", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Generate Constructor", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = { onSourceAction!!.invoke("source.generate.constructor"); showLspMenu = false }
                            )
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("G", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Generate Getters/Setters", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = { onSourceAction!!.invoke("source.generate.accessors"); showLspMenu = false }
                            )
                            DropdownMenuItem(
                            text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("G", color = Color(0xFF4EC9B0), fontSize = 14.sp)
                            Text("Implement Interface", color = Color(0xFFD4D4D4), fontSize = 12.sp)
                            }
                            },
                            onClick = { onSourceAction!!.invoke("source.generate.implement"); showLspMenu = false }
                            )
                            }
                            } // end scrollable Column
                        }
                    }
                }
            }
        }

        // ── P2-4 Go to Definition Results ──────────────────────────────────────────────────────
        if (gotoResults != null) {
            GotoDefinitionDialog(
                results = gotoResults!!,
                crossFileResults = crossFileResults,
                onDismiss = { gotoResults = null },
                onScrollToLine = { line ->
                    coroutineScope.launch {
                        val localLineHeightPx = editorMetrics.lineHeightPx
                        vScroll.animateScrollTo((line * localLineHeightPx).toInt())
                    }
                },
                onOpenFileAtLine = { path, line -> onOpenFileAtLine?.invoke(path, line) },
            )
        }

        // ── P22-L: Peek Definition overlay ───────────────────────────────────────
        // P41-H: Peek overlays (extracted to PeekWidget.kt to avoid 64KB limit)
        if (peekDefResult != null) {
            PeekCodeWidget(
                result = PeekResult(
                    title = "Peek Definition",
                    filePath = peekDefResult!!.filePath,
                    line = peekDefResult!!.line,
                    lines = peekDefResult!!.lines,
                    defLine = peekDefResult!!.defLine,
                    usedLsp = peekUsedLsp,
                ),
                currentFilePath = filePath,
                onNavigate = { fp, ln ->
                    if (fp == filePath) {
                        // TEST-62-FIX: Also set highlight + cursor, not just scroll
                        coroutineScope.launch {
                            val localLineHeightPx = editorMetrics.lineHeightPx
                            vScroll.animateScrollTo((ln * localLineHeightPx).toInt())
                        }
                        highlightTargetLine = ln + 1
                        // Place cursor at start of the reference line
                        val targetLineIdx = ln
                        if (targetLineIdx >= 0) {
                            var lineStart = 0
                            var linesFound = 0
                            val text = value.text
                            for (i in text.indices) {
                                if (text[i] == '\n') {
                                    linesFound++
                                    if (linesFound == targetLineIdx) {
                                        lineStart = i + 1
                                        break
                                    }
                                }
                            }
                            if (linesFound < targetLineIdx) lineStart = text.length
                            programmaticCursorMove(lineStart.coerceIn(0, text.length), "go_to_line_nav")
                        }
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(2500)
                            highlightTargetLine = 0
                        }
                    } else {
                        onOpenFileAtLine?.invoke(fp, ln)
                    }
                },
                onClose = { peekDefResult = null },
            )
        }
        // P41-H: Peek References overlay
        if (peekRefsResult != null) {
            PeekReferencesWidget(
                result = peekRefsResult!!,
                currentFilePath = filePath,
                onNavigate = { fp, ln ->
                    if (fp == filePath) {
                        // TEST-62-FIX: Also set highlight + cursor, not just scroll
                        coroutineScope.launch {
                            val localLineHeightPx = editorMetrics.lineHeightPx
                            vScroll.animateScrollTo((ln * localLineHeightPx).toInt())
                        }
                        highlightTargetLine = ln + 1
                        // Place cursor at start of the reference line
                        val targetLineIdx = ln
                        if (targetLineIdx >= 0) {
                            var lineStart = 0
                            var linesFound = 0
                            val text = value.text
                            for (i in text.indices) {
                                if (text[i] == '\n') {
                                    linesFound++
                                    if (linesFound == targetLineIdx) {
                                        lineStart = i + 1
                                        break
                                    }
                                }
                            }
                            if (linesFound < targetLineIdx) lineStart = text.length
                            programmaticCursorMove(lineStart.coerceIn(0, text.length), "go_to_line_nav")
                        }
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(2500)
                            highlightTargetLine = 0
                        }
                    } else {
                        onOpenFileAtLine?.invoke(fp, ln)
                    }
                },
                onClose = { peekRefsResult = null },
            )
        }
        // P41-H: Peek Declaration overlay
        if (peekDeclResult != null) {
            PeekCodeWidget(
                result = peekDeclResult!!,
                currentFilePath = filePath,
                onNavigate = { fp, ln ->
                    if (fp == filePath) {
                        // TEST-62-FIX: Also set highlight + cursor, not just scroll
                        coroutineScope.launch {
                            val localLineHeightPx = editorMetrics.lineHeightPx
                            vScroll.animateScrollTo((ln * localLineHeightPx).toInt())
                        }
                        highlightTargetLine = ln + 1
                        // Place cursor at start of the reference line
                        val targetLineIdx = ln
                        if (targetLineIdx >= 0) {
                            var lineStart = 0
                            var linesFound = 0
                            val text = value.text
                            for (i in text.indices) {
                                if (text[i] == '\n') {
                                    linesFound++
                                    if (linesFound == targetLineIdx) {
                                        lineStart = i + 1
                                        break
                                    }
                                }
                            }
                            if (linesFound < targetLineIdx) lineStart = text.length
                            programmaticCursorMove(lineStart.coerceIn(0, text.length), "go_to_line_nav")
                        }
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(2500)
                            highlightTargetLine = 0
                        }
                    } else {
                        onOpenFileAtLine?.invoke(fp, ln)
                    }
                },
                onClose = { peekDeclResult = null },
            )
        }

        if (renameDialogWord != null) {
            val wordToRename = renameDialogWord!!
            AlertDialog(
                onDismissRequest = { renameDialogWord = null },
                containerColor = colors.background,
                title = {
                    Text(
                        "Rename Symbol",
                        color = Color(0xFFD4D4D4),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "$renameCount occurrence${if (renameCount != 1) "s" else ""} of '$wordToRename'" +
                            (if (renameProjectWide && renameCrossFileCount > 0) " + $renameCrossFileCount in other files" else "") +
                            (if (renameUsedLsp) " [LSP]" else " [regex]"),
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                        )
                        // P37-3fix: Badge reflects ACTUAL outcome (renameUsedLsp), not pre-flight check
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    .background(if (renameUsedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (renameUsedLsp) "LSP" else "Fallback",
                                    color = colors.background,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                if (renameUsedLsp) "Renamed via LSP (workspace-aware)" else "Regex replace in current file only",
                                color = Color(0xFF888888),
                                fontSize = 10.sp,
                            )
                        }
                        OutlinedTextField(
                            value = renameNewName,
                            onValueChange = { renameNewName = it },
                            singleLine = true,
                            label = { Text("New name", color = Color(0xFF888888), fontSize = 11.sp) },
                            textStyle = TextStyle(
                                color = Color(0xFFD4D4D4),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF007ACC),
                                unfocusedBorderColor = colors.gutter.copy(alpha = 0.3f),
                                cursorColor = Color(0xFF007ACC),
                            ),
                        )
                        if (projectRoot != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { renameProjectWide = !renameProjectWide },
                            ) {
                                Checkbox(
                                    checked = renameProjectWide,
                                    onCheckedChange = { renameProjectWide = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF007ACC)),
                                )
                                Text(
                                    "Rename in all project files",
                                    color = Color(0xFFD4D4D4),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (renameInProgress) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF007ACC),
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // P39-FULL: Preview button
                        if (LspManager.isServerRunning(language) && filePath.startsWith("/")) {
                            TextButton(onClick = {
                                val newName = renameNewName.trim()
                                if (newName.isNotEmpty() && newName != wordToRename) {
                                    val ctx = context
                                    val uri = LspManager.fileUriFromHostPath(ctx, filePath)
                                    if (uri != null) {
                                        val cOff = value.selection.end
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        try {
                                            val wsEdit = LspManager.rename(language, uri, cLine, cCol, newName)
                                            if (wsEdit != null) {
                                                val files = mutableListOf<Pair<String, Int>>()
                                                val docChanges = wsEdit.optJSONArray("documentChanges")
                                                val changes = wsEdit.optJSONObject("changes")
                                                if (docChanges != null) {
                                                    for (j in 0 until docChanges.length()) {
                                                        val dc = docChanges.optJSONObject(j) ?: continue
                                                        val editUri = dc.optString("uri", "")
                                                        val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                        val decoded = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                        val editCount = dc.optJSONArray("edits")?.length() ?: 0
                                                        files.add(decoded.substringAfterLast("/") to editCount)
                                                    }
                                                } else if (changes != null) {
                                                    val keys = changes.keys()
                                                    while (keys.hasNext()) {
                                                        val editUri = keys.next()
                                                        val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                        val decoded = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                        val editCount = changes.optJSONArray(editUri)?.length() ?: 0
                                                        files.add(decoded.substringAfterLast("/") to editCount)
                                                    }
                                                }
                                                renamePreviewEdit = wsEdit
                                                renamePreviewFiles = files
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }) {
                                Text("Preview", color = Color(0xFF4EC9B0), fontSize = 12.sp)
                            }
                        }
                        Button(
                        onClick = {
                            val newName = renameNewName.trim()
                            if (newName.isNotEmpty() && newName != wordToRename) {
                                // P37-1: Try LSP rename first, fall back to regex only if LSP unavailable
                                var lspSucceeded = false
                                if (LspManager.isServerRunning(language) && filePath.startsWith("/")) {
                                    val ctx = context
                                    val uri = LspManager.fileUriFromHostPath(ctx, filePath)
                                    if (uri != null) {
                                        val cOff = value.selection.end
                                        val cPos = positionMapper.offsetToPosition(cOff)
                                        val cLine = cPos.line
                                        val cCol = cPos.column
                                        // Try prepareRename first (some servers require it)
                                        val prep = try { LspManager.prepareRename(language, uri, cLine, cCol) } catch (_: Exception) { null }
                                        if (prep != null) {
                                            // Server confirmed this position is renameable
                                            val wsEdit = try { LspManager.rename(language, uri, cLine, cCol, newName) } catch (_: Exception) { null }
                                            if (wsEdit != null) {
                                                val (newText, appliedAny) = com.codespace.ide.lsp.applyWorkspaceEditToFilesystem(wsEdit, value.text, filePath)
                                                if (appliedAny) {
                                                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                    programmaticTextChange(newText, value.selection, "snippet_apply")
                                                    lspSucceeded = true
                                                    renameUsedLsp = true
                                                    // Notify EditorPane's onRenameSymbol callback (for any side effects)
                                                    onRenameSymbol?.invoke(wordToRename, newName)
                                                }
                                            }
                                        }
                                    }
                                }
                                // FALLBACK: regex find-replace only if LSP didn't succeed
                                if (!lspSucceeded) {
                                    renameUsedLsp = false
                                    val pattern = Regex("""\b${Regex.escape(wordToRename)}\b""")
                                    val newText = pattern.replace(value.text, newName)
                                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                    programmaticTextChange(newText, value.selection, "rename_refactor")
                                    // P18-C: Cross-file rename (regex fallback)
                                    if (renameProjectWide && projectRoot != null) {
                                        renameInProgress = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val root = File(projectRoot)
                                            var totalCrossFile = 0
                                            root.walkTopDown()
                                                .filter { it.isFile && !it.path.contains("/.git/") && !it.path.contains("/build/") && !it.path.contains("/node_modules/") && !it.path.contains("/.gradle/") }
                                                .forEach { file ->
                                                    try {
                                                        val text = file.readText()
                                                        if (pattern.containsMatchIn(text)) {
                                                            val updated = pattern.replace(text, newName)
                                                            file.writeText(updated)
                                                            totalCrossFile += pattern.findAll(text).count()
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            renameCrossFileCount = totalCrossFile
                                            renameInProgress = false
                                        }
                                    }
                                }
                            }
                            renameDialogWord = null
                        },
                        enabled = renameNewName.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)),
                    ) {
                        Text("Rename", color = colors.text, fontSize = 12.sp)
                    }
                    }  // close Row
                },
                dismissButton = {
                    TextButton(onClick = { renameDialogWord = null }) {
                        Text("Cancel", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                },
            )
        }

        // P39-FULL: Rename Preview dialog — shows affected files before applying
        if (renamePreviewEdit != null) {
            AlertDialog(
                onDismissRequest = { renamePreviewEdit = null; renamePreviewFiles = emptyList() },
                containerColor = colors.background,
                title = { Text("Rename Preview", color = Color(0xFFD4D4D4), fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${renamePreviewFiles.size} file${if (renamePreviewFiles.size != 1) "s" else ""} affected",
                            color = Color(0xFF4EC9B0), fontSize = 12.sp)
                        HorizontalDivider(color = colors.gutter.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                        renamePreviewFiles.forEach { (fileName, editCount) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("📄", fontSize = 10.sp)
                                Text(fileName, color = Color(0xFFD4D4D4), fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("$editCount edit${if (editCount != 1) "s" else ""}", color = Color(0xFF888888), fontSize = 10.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        // Apply the previewed rename edit directly
                        val wsEdit = renamePreviewEdit!!
                        val (newText, appliedAny) = com.codespace.ide.lsp.applyWorkspaceEditToFilesystem(wsEdit, value.text, filePath)
                        if (appliedAny) {
                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                            programmaticTextChange(newText, TextRange(value.selection.start), "snippet_applied")
                        }
                        renamePreviewEdit = null; renamePreviewFiles = emptyList(); renameDialogWord = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))) {
                        Text("Apply", color = Color.White, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renamePreviewEdit = null; renamePreviewFiles = emptyList() }) {
                        Text("Cancel", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                },
            )
        }

        // ── P24-3: Bottom Panels (Find References + Call/Type Hierarchy) ────────────────────────────
        BottomPanels(
            findRefWord = findRefWord,
            findRefLoading = findRefLoading,
            findRefUsedLsp = findRefUsedLsp,
            findRefResults = findRefResults,
            onDismissFindRef = { findRefWord = null; findRefResults = emptyList() },
            onScrollToLine = { line ->
                coroutineScope.launch {
                    val lineHeightPx = lineHeightDp.value  // P50-FIX: density-corrected line height
                    vScroll.animateScrollTo((line * lineHeightPx).toInt())
                }
            },
            filePath = filePath,
            onOpenFileAtLine = { path, line -> onOpenFileAtLine?.invoke(path, line) },
            showCallHierarchy = showCallHierarchy,
            callHierarchyRoot = callHierarchyRoot,
            callHierarchyIncoming = callHierarchyIncoming,
            callHierarchyOutgoing = callHierarchyOutgoing,
            onDismissCallHierarchy = {
                showCallHierarchy = false
                callHierarchyRoot = null
                callHierarchyIncoming = emptyList()
                callHierarchyOutgoing = emptyList()
            },
            showTypeHierarchy = showTypeHierarchy,
            typeHierarchyRoot = typeHierarchyRoot,
            typeHierarchySupertypes = typeHierarchySupertypes,
            typeHierarchySubtypes = typeHierarchySubtypes,
            onDismissTypeHierarchy = {
                showTypeHierarchy = false
                typeHierarchyRoot = null
                typeHierarchySupertypes = emptyList()
                typeHierarchySubtypes = emptyList()
            },
        )

        GotoLineBar(
            goToLineOpen = goToLineOpen,
            goToLineInput = goToLineInput,
            onGoToLineInputChange = { goToLineInput = it },
            text = value.text,
            fontSize = fontSize,
            vScrollValue = vScroll.value,
            onJumpToLine = { offset, line ->
                // DEBUG: Show that onJumpToLine was called
                debugJumpMsg = "JUMP line=$line offset=$offset"
                programmaticCursorMove(offset, "jump_to_line")
                // Phase V-FIX (Test 53): Highlight the target line so the user can
                // SEE where they jumped — same mechanism as scrollToLine.
                highlightTargetLine = line
                highlightBlinkStart = System.currentTimeMillis()
                coroutineScope.launch {
                    val localLineHeightPx = editorMetrics.lineHeightPx
                    val scrollTarget = ((line - 1) * localLineHeightPx).toInt()
                    val maxScroll = vScroll.maxValue
                    vScroll.animateScrollTo(scrollTarget.coerceAtMost(maxScroll))
                    // Auto-clear highlight after 6s (blink animation)
                    kotlinx.coroutines.delay(6000)
                    highlightTargetLine = 0
                    highlightBlinkStart = 0L
                }
                goToLineInput = ""
                onGoToLineClose()
            },
        )

        // DEBUG: Visual indicators
        if (debugJumpMsg.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 60.dp, top = 4.dp)
                    .background(Color(0xFFFF0000).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .zIndex(100f),
            ) {
                Text(debugJumpMsg, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            LaunchedEffect(debugJumpMsg) {
                kotlinx.coroutines.delay(3000)
                debugJumpMsg = ""
            }
        }
        if (debugDoubleTapMsg.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 60.dp, top = 30.dp)
                    .background(Color(0xFF00AA00).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .zIndex(100f),
            ) {
                Text(debugDoubleTapMsg, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            LaunchedEffect(debugDoubleTapMsg) {
                kotlinx.coroutines.delay(3000)
                debugDoubleTapMsg = ""
            }
        }
        FindReplaceBar(
            findReplaceOpen = findReplaceOpen,
            findQuery = findQuery,
            onFindQueryChange = { findQuery = it; matchIndex = 0 },
            replaceQuery = replaceQuery,
            onReplaceQueryChange = { replaceQuery = it },
            useRegex = useRegex,
            onToggleRegex = { useRegex = !useRegex },
            caseSensitive = caseSensitive,
            onToggleCaseSensitive = { caseSensitive = !caseSensitive },
            wholeWord = wholeWord,
            onToggleWholeWord = { wholeWord = !wholeWord },
            preserveCase = preserveCase,
            onTogglePreserveCase = { preserveCase = !preserveCase },
            matches = matches,
            matchIndex = matchIndex,
            onMatchIndexChange = { matchIndex = it },
            text = value.text,
            onTextChange = { newText, cursor ->
                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                programmaticTextChange(newText, TextRange(cursor), "external_text_change")
            },
            onSelectRange = { start, end ->
                programmaticCursorMove(start, "select_range")
            },
            onFindReplaceClose = {
                findQuery = ""
                replaceQuery = ""
                onFindReplaceClose()
            },
        )

        LightbulbIndicator(
            lightbulbLine = lightbulbLine,
            lspCodeActionProvider = lspCodeActionProvider,
            showCompletions = showCompletions,
            fontSize = fontSize,
            vScrollValue = vScroll.value,
            displayLinesSize = visualLineMapper.visualLineCount,
            showLightbulbMenu = showLightbulbMenu,
            onShowLightbulbMenu = { showLightbulbMenu = it },
            textLayoutResult = textLayoutResult,
        )
        // P39: Lightbulb menu categorized action menu triggered by tapping the bulb
        DropdownMenu(
            expanded = showLightbulbMenu,
            onDismissRequest = { showLightbulbMenu = false },
        ) {
            if (lightbulbActions.isNotEmpty()) {
                val categorized = com.codespace.ide.lsp.categorizeCodeActions(lightbulbActions)
                categorized.forEach { (groupLabel, actions) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                groupLabel.uppercase(),
                                color = Color(0xFF858585),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    actions.forEach { fix ->
                        val icon = com.codespace.ide.lsp.CodeActionKind.icon(fix.kind)
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(icon, fontSize = 12.sp)
                                    Text(
                                        fix.title,
                                        color = if (fix.disabled != null) Color(0xFF666666)
                                               else if (fix.isPreferred) Color(0xFFFFD700)
                                               else Color(0xFFD4D4D4),
                                        fontSize = 13.sp,
                                        fontWeight = if (fix.isPreferred) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            },
                            enabled = fix.disabled == null,
                            onClick = {
                                // P39: Handle AI actions via onAiFixRequest
                                if (fix.kind != null && fix.kind.startsWith("ai.") && onAiFixRequest != null) {
                                    // P41-O: Use full selection if available, else current line
                                    val hasSelection2 = value.selection.start != value.selection.end
                                    val selText2 = if (hasSelection2) {
                                        value.text.substring(
                                            value.selection.start.coerceIn(0, value.text.length),
                                            value.selection.end.coerceIn(0, value.text.length)
                                        )
                                    } else {
                                        val lineStart2 = positionMapper.lineStart(positionMapper.offsetToLine(value.selection.start))
                                        val lineEnd2 = value.text.indexOf('\n', value.selection.start)
                                        value.text.substring(lineStart2, if (lineEnd2 < 0) value.text.length else lineEnd2)
                                    }
                                    // P41-O: Project-aware context
                                    val fileName2 = filePath?.substringAfterLast('/') ?: "untitled"
                                    val langName2 = language.displayName
                                    val imports2 = value.text.lines().take(30).filter {
                                        it.trim().startsWith("import ") || it.trim().startsWith("from ") || it.trim().startsWith("package ") || it.trim().startsWith("#include")
                                    }.joinToString("\n")
                                    val contextHeader2 = "File: $fileName2 ($langName2)\n" +
                                        (if (imports2.isNotEmpty()) "Imports:\n$imports2\n" else "") +
                                        "Selection (${if (hasSelection2) "selected text" else "current line"}):\n"
                                    val diagAtCursor2 = if (fix.kind == com.codespace.ide.lsp.CodeActionKind.AIExplainError) {
                                        val cursorOff2 = value.selection.start.coerceIn(0, value.text.length)
                                        val matchingErr2 = lintErrors.firstOrNull { err ->
                                            err.start <= cursorOff2 && err.end >= cursorOff2
                                        }
                                        if (matchingErr2 != null) "Error: ${matchingErr2.message}${if (matchingErr2.code != null) " [${matchingErr2.code}]" else ""}\n" else ""
                                    } else ""
                                    val prompt = when (fix.kind) {
                                        com.codespace.ide.lsp.CodeActionKind.AIExplain -> contextHeader2 + "Explain this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIGenerateDoc -> contextHeader2 + "Generate documentation for this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIGenerateTests -> contextHeader2 + "Generate unit tests for this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIOptimize -> contextHeader2 + "Optimize this code for better performance:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIRewrite -> contextHeader2 + "Rewrite this code for better clarity:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AISimplify -> contextHeader2 + "Simplify this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIRefactor -> contextHeader2 + "Refactor this code for better structure and readability:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIAddComments -> contextHeader2 + "Add inline comments to this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIExplainError -> contextHeader2 + diagAtCursor2 + "Explain the error in this code:\n" + selText2
                                        com.codespace.ide.lsp.CodeActionKind.AIImprovePerf -> contextHeader2 + "Suggest performance improvements for:\n" + selText2
                                        else -> contextHeader2 + fix.title + ":\n" + selText2
                                    }
                                    onAiFixRequest!!.invoke(prompt)
                                } else if (fix.edit != null) {
                                    try {
                                        val newText = com.codespace.ide.lsp.applyWorkspaceEdit(
                                            fix.edit, value.text, null
                                        )
                                        if (newText != null && newText != value.text) {
                                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                            programmaticTextChange(newText, TextRange(value.selection.start), "ai_apply")
                                        }
                                    } catch (_: Exception) {}
                                } else if (fix.command != null) {
                                    // P39-FULL: Handle code actions that return a command (not edit)
                                    try {
                                        val cmdJson = org.json.JSONObject(fix.command)
                                        val cmdName = cmdJson.optString("command", "")
                                        val cmdArgs = cmdJson.optJSONArray("arguments")
                                        if (cmdName.isNotEmpty()) {
                                            LspManager.executeCommand(language, cmdName, cmdArgs)
                                        }
                                    } catch (_: Exception) {}
                                }
                                showLightbulbMenu = false
                            }
                        )
                    }
                }
            } else {
                DropdownMenuItem(
                    text = { Text("No actions available", color = Color(0xFF666666), fontSize = 13.sp) },
                    onClick = { showLightbulbMenu = false },
                )
            }
        }

        // P2-12 Signature help popup — shown above the current line, one line up so it
        // doesn't cover what's being typed. Hidden while the autocomplete dropdown is open
        // to avoid stacking two popups on the same spot.
        if (!showCompletions && activeSignature != null) {
            val sig = activeSignature!!
            val cursorLineIdx = positionMapper.offsetToLine(value.selection.end)
            val popupLineIdx = (cursorLineIdx - 1).coerceAtLeast(0)
            // BUG-2 FIX: subtract scroll offset so the popup appears at the visible cursor position
            val popupTopDp = ((popupLineIdx * lineHeightDp.value) - vScrollDp).coerceAtLeast(0f)
            val annotated = remember(sig) {
                buildAnnotatedString {
                    append(sig.name)
                    append("(")
                    sig.params.forEachIndexed { idx, param ->
                        if (idx > 0) append(", ")
                        if (idx == sig.activeParam) {
                            withStyle(SpanStyle(color = Color(0xFF4EC9B0), fontWeight = FontWeight.Bold)) {
                                append(param)
                            }
                        } else {
                            append(param)
                        }
                    }
                    append(")")
                    if (sig.returnType != null) {
                        withStyle(SpanStyle(color = Color(0xFF808080))) { append(": ${sig.returnType}") }
                    }
                }
            }
            var sigExpanded by remember { mutableStateOf(false) }
            val sigScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
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
                            Text(text = if (sigExpanded) "▾" else "▸", color = Color(0xFF888888), fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(2.dp))
                        Box(modifier = Modifier.size(20.dp).clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(sig.name))
                            }, contentAlignment = Alignment.Center) {
                            Text(text = "⏉", color = Color(0xFF888888), fontSize = 11.sp)
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

        // P41-E: Multi-line ghost text overlay (extracted to separate composable to avoid method-too-large)
        if (toggles.showGhostText && ghostText != null && !showCompletions) {
            GhostTextOverlay(
                ghostText = ghostText!!,
                ghostTextLines = ghostTextLines,
                ghostTextIsAi = ghostTextIsAi,
                cursorPos = value.selection.end,
                text = value.text,
                fontSize = fontSize.toFloat(),
                vScrollValue = vScroll.value,
                languageName = language.name,
                context = context,
                onAcceptFull = { fullText ->
                    val cursor = value.selection.end
                    val newText = value.text.substring(0, cursor) + fullText + value.text.substring(cursor)
                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                    programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(cursor, cursor, fullText.length)), "ghost_text_accept_full")
                    if (!ghostTextIsAi) {
                        val ghostLabel = allCompletions.firstOrNull()?.label
                        if (ghostLabel != null) CompletionHistoryStore.recordAccepted(ghostLabel, language.name, context)
                    }
                    ghostText = null; ghostTextLines = emptyList(); ghostTextIsAi = false
                },
                onAcceptWord = { word, remainingLines ->
                    val cursor = value.selection.end
                    val newText = value.text.substring(0, cursor) + word + value.text.substring(cursor)
                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                    programmaticTextChange(newText, TextRange(positionMapper.shiftOnInsert(cursor, cursor, word.length)), "ghost_text_accept_word")
                    ghostTextLines = remainingLines
                    ghostText = remainingLines.firstOrNull() ?: ""
                },
                onDismiss = { ghostText = null; ghostTextLines = emptyList(); ghostTextIsAi = false },
                textLayoutResult = textLayoutResult
            )
        }

        HoverPopup(
            lspHoverContent = lspHoverContent,
            showCompletions = showCompletions,
            fontSize = fontSize,
            vScrollValue = vScroll.value,
            cursorOffset = value.selection.end,
            text = value.text,
            clipboardManager = clipboardManager,
            textLayoutResult = textLayoutResult,
        )

        // P41-I: Snippet choice dropdown — appears when active tab-stop has choices (${1|a,b,c|})
        if (snippetSession != null && showSnippetChoices) {
            val session = snippetSession!!
            val activeStop = session.activeStop()
            if (activeStop != null && activeStop.choices.isNotEmpty()) {
                val cursorLine = positionMapper.offsetToLine(activeStop.startOffset)
                val lineHeightPxPopup = editorMetrics.lineHeightPx
                val visualLineSP = visualLineMapper.docToVisualLine(cursorLine)
                val layoutSP = textLayoutResult
                val popupOffsetY = if (layoutSP != null && visualLineSP < layoutSP.lineCount) {
                    (layoutSP.getLineBottom(visualLineSP) - vScroll.value).roundToInt().coerceAtLeast(0)
                } else {
                    ((cursorLine + 1) * lineHeightPxPopup - vScroll.value).roundToInt().coerceAtLeast(0)
                }
                val popupOffsetX = with(androidx.compose.ui.platform.LocalDensity.current) { GUTTER_WIDTH.dp.toPx() }.roundToInt()
                Popup(
                    alignment = Alignment.TopStart,
                    offset = androidx.compose.ui.unit.IntOffset(popupOffsetX, popupOffsetY + editorMetrics.lineHeightPx.roundToInt()),
                ) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.width(180.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = colors.background,
                        shadowElevation = 4.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.function.copy(alpha = 0.6f)),
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
                        ) {
                            activeStop.choices.forEachIndexed { idx, choice ->
                                val isSelected = idx == 0 // First choice is highlighted as default
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Replace the active tab-stop text with the chosen value
                                            val text = value.text
                                            val stopStart = activeStop.startOffset
                                            val stopEnd = activeStop.endOffset
                                            val newText = text.substring(0, stopStart) + choice + text.substring(stopEnd)
                                            val newLen = choice.length
                                            val oldLen = stopEnd - stopStart
                                            // Update session offsets
                                            snippetSession = session.shiftAfterEdit(activeStop, oldLen, newLen)
                                            // Update editor value
                                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                            programmaticTextChange(newText, TextRange(stopStart, stopStart + newLen), "snippet_tab_stop_update")
                                            showSnippetChoices = false
                                        }
                                        .background(if (isSelected) colors.function.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = choice,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSelected) colors.function else colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // IntelliSense dropdown — rendered in a Popup window so it's never clipped
        // by parent bounds, scroll offset, or the soft keyboard.
        // KEYBOARD FIX: detect IME height and clamp popup so it never covers the keyboard.
        val imeHeightPx = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
        val imeHeightDpVal = with(androidx.compose.ui.platform.LocalDensity.current) { imeHeightPx.toDp() }.value.toInt().toInt()
        val availableHeightDp = LocalConfiguration.current.screenHeightDp - imeHeightDpVal
        // Completion loading indicator — shows when LSP is still fetching
        if (lspCompletionLoading && !showCompletions && prefix.isNotEmpty()) {
            val cursorLine = positionMapper.offsetToLine(value.selection.end)
            val lineHeightPx = with(scrollDensity) { lineHeightDp.toPx() }
            val cursorCol = positionMapper.offsetToPosition(value.selection.end).column
            val cursorOff = value.selection.end
            val visualLineLI = visualLineMapper.docToVisualLine(cursorLine)
            val layoutLI = textLayoutResult
            val screenDensity = androidx.compose.ui.platform.LocalDensity.current
            val screenWidthPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
            val safeCursorOffLI = cursorOff.coerceIn(0, layoutLI?.layoutInput?.text?.length ?: 0)
            var popupOffsetX = if (layoutLI != null) {
                (with(screenDensity) { GUTTER_WIDTH.dp.toPx() } + layoutLI.getHorizontalPosition(safeCursorOffLI, true)).roundToInt()
            } else {
                val charWidthPx = editorMetrics.charWidthPx
                (with(screenDensity) { GUTTER_WIDTH.dp.toPx() } + cursorCol * charWidthPx).roundToInt()
            }
            val popupWidthPx = with(screenDensity) { 120.dp.toPx() }
            if (popupOffsetX + popupWidthPx > screenWidthPx) {
                popupOffsetX = (screenWidthPx - popupWidthPx).roundToInt().coerceAtLeast(0)
            }
            var popupOffsetY = if (layoutLI != null && visualLineLI < layoutLI.lineCount) {
                (layoutLI.getLineBottom(visualLineLI) - vScroll.value).roundToInt().coerceAtLeast(0)
            } else {
                ((cursorLine + 1) * lineHeightPx - vScroll.value).roundToInt().coerceAtLeast(0)
            }
            val screenHeightPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
            val popupMaxHeightPx = with(screenDensity) { 220.dp.toPx() }
            if (popupOffsetY + popupMaxHeightPx > screenHeightPx) {
                popupOffsetY = if (layoutLI != null && visualLineLI < layoutLI.lineCount) {
                    (layoutLI.getLineTop(visualLineLI) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
                } else {
                    ((cursorLine * lineHeightPx) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
                }
            }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(popupOffsetX, popupOffsetY),
                properties = PopupProperties(focusable = false),
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(min = 80.dp, max = 120.dp)
                        .background(colors.background, RoundedCornerShape(6.dp))
                        .border(1.dp, colors.function.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.function,
                    )
                    Text(
                        text = "Loading...",
                        fontSize = 11.sp,
                        color = colors.text.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (showCompletions && allCompletions.isNotEmpty()) {
            val cursorLine = positionMapper.offsetToLine(value.selection.end)
            val lineHeightPx = with(scrollDensity) { lineHeightDp.toPx() }
            val cursorCol = positionMapper.offsetToPosition(value.selection.end).column
            val cursorOff = value.selection.end
            val visualLineCP = visualLineMapper.docToVisualLine(cursorLine)
            val layoutCP = textLayoutResult
            val screenDensity = androidx.compose.ui.platform.LocalDensity.current
            val screenWidthPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
            val popupWidthPx = with(screenDensity) { 280.dp.toPx() }
            val safeCursorOffCP = cursorOff.coerceIn(0, layoutCP?.layoutInput?.text?.length ?: 0)
            var popupOffsetX = if (layoutCP != null) {
                (with(screenDensity) { GUTTER_WIDTH.dp.toPx() } + layoutCP.getHorizontalPosition(safeCursorOffCP, true)).roundToInt()
            } else {
                val charWidthPx = editorMetrics.charWidthPx
                (with(screenDensity) { GUTTER_WIDTH.dp.toPx() } + cursorCol * charWidthPx).roundToInt()
            }
            if (popupOffsetX + popupWidthPx > screenWidthPx) {
                popupOffsetX = (screenWidthPx - popupWidthPx).roundToInt().coerceAtLeast(0)
            }
            val screenHeightPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
            val popupMaxHeightPx = with(screenDensity) { 220.dp.toPx() }
            var popupOffsetY = if (layoutCP != null && visualLineCP < layoutCP.lineCount) {
                (layoutCP.getLineBottom(visualLineCP) - vScroll.value).roundToInt().coerceAtLeast(0)
            } else {
                ((cursorLine + 1) * lineHeightPx - vScroll.value).roundToInt().coerceAtLeast(0)
            }
            if (popupOffsetY + popupMaxHeightPx > screenHeightPx) {
                popupOffsetY = if (layoutCP != null && visualLineCP < layoutCP.lineCount) {
                    (layoutCP.getLineTop(visualLineCP) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
                } else {
                    ((cursorLine * lineHeightPx) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
                }
            }
            
            // P41-J: Apply filter if active
            val filteredCompletions = if (completionFilter != null) {
                allCompletions.filter { it.source == completionFilter }
            } else {
                allCompletions
            }
            // P41-J: Available sources for filter chips
            val availableSources = allCompletions.map { it.source }.distinct()
            
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(popupOffsetX, popupOffsetY),
                properties = PopupProperties(focusable = false),
            ) {
                // NEW (2026-08-10): Resizable popup — base max height + user-dragged extra height,
                // clamped so it never exceeds available screen space above the keyboard.
                val basePopupMaxDp = if (availableHeightDp > 200) 220f else (availableHeightDp * 0.4f).coerceAtLeast(120f)
                val popupMaxDp = (basePopupMaxDp + completionPopupExtraHeightDp)
                    .coerceIn(120f, availableHeightDp.toFloat().coerceAtLeast(120f))
                Column(
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 280.dp)
                        .heightIn(max = popupMaxDp.dp)
                        .background(colors.background, RoundedCornerShape(6.dp))
                        .border(1.dp, colors.function.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .clickable { } // consume touches to prevent touch-through to editor
                ) {
                    // P41-J: Filter chips row
                    if (availableSources.size > 1) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // "All" chip
                            item {
                                FilterChip(
                                    label = "All",
                                    isActive = completionFilter == null,
                                    color = Color(0xFF888888),
                                    onClick = { completionFilter = null; selectedLabel = null }
                                )
                            }
                            // Source-specific chips
                            items(availableSources) { src ->
                                val (chipLabel, chipColor) = when (src) {
                                    CompletionSource.LSP -> "LSP" to Color(0xFF4EC9B0)
                                    CompletionSource.BUFFER -> "Buf" to Color(0xFF888888)
                                    CompletionSource.SNIPPET -> "Snip" to Color(0xFFDCDCAA)
                                    CompletionSource.WORKSPACE -> "Wksp" to Color(0xFF4DA6FF)
                                    CompletionSource.AI -> "AI" to Color(0xFFC586C0)
                                    CompletionSource.PATH -> "Path" to Color(0xFF9CDCFE)
                                }
                                FilterChip(
                                    label = chipLabel,
                                    isActive = completionFilter == src,
                                    color = chipColor,
                                    onClick = { completionFilter = if (completionFilter == src) null else src; selectedLabel = null }
                                )
                            }
                        }
                    }
                    
                    // P41-J: Sticky selection — find index of previously selected label
                    val initialIndex = if (selectedLabel != null) {
                        filteredCompletions.indexOfFirst { it.label == selectedLabel }.coerceAtLeast(0)
                    } else 0
                    
                    // P41-J: Detail panel — update doc for highlighted item
                    LaunchedEffect(initialIndex, filteredCompletions) {
                        if (initialIndex < filteredCompletions.size) {
                            val highlighted = filteredCompletions[initialIndex]
                            detailDoc = highlighted.doc
                            detailLabel = highlighted.label
                        } else {
                            detailDoc = null
                            detailLabel = null
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                    ) {
                        itemsIndexed(filteredCompletions) { idx, comp ->
                        // Doc always visible below label — no per-item state (Compose rules)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (idx == initialIndex) Color(0xFF04395E) else Color.Transparent)
                                .clickable {
                                    val cursor = value.selection.end
                                    val text = value.text
                                    val end = cursor.coerceAtMost(text.length)
                                    var start = end
                                    // Fix: don't cross spaces — "import o" should only replace "o", not "import o"
                                    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
                                    
                                    // P41-D: Check for LSP additionalTextEdits (auto-import) attached to this completion
                                    val hasAdditionalEdits = !comp.additionalTextEditsJson.isNullOrBlank()
                                    
                                    if (hasAdditionalEdits) {
                                        // P41-D: Apply additionalTextEdits (imports) FIRST, then insert completion text
                                        // LSP spec: additionalTextEdits are applied before the main edit
                                        coroutineScope.launch {
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    val editsArray = org.json.JSONArray(comp.additionalTextEditsJson)
                                                    // Apply additional edits to the full text first
                                                    val textWithImports = applyLspTextEdits(text, editsArray)
                                                    // Then insert completion text at cursor position
                                                    // (adjust cursor position if edits were above it)
                                                    val cursorOffset = textWithImports.length - text.length
                                                    val adjustedStart = start + cursorOffset
                                                    val adjustedEnd = end + cursorOffset
                                                    // P41-I: If snippet, parse and replace insertText with cleaned version
                                                    val (textToInsert, snippetParsed) = if (comp.insertTextFormat == 2) {
                                                        val parsed = parseSnippet(comp.insertText, SnippetContext(
                                                            lineNumber = positionMapper.offsetToLine(start) + 1,
                                                            lineIndex = positionMapper.offsetToLine(start),
                                                            currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                                            selectedText = if (start != end) value.text.substring(start, end) else "",
                                                        ))
                                                        Pair(parsed.cleanedText, parsed)
                                                    } else {
                                                        Pair(comp.insertText, null)
                                                    }
                                                    val finalText = textWithImports.substring(0, adjustedStart) + textToInsert + textWithImports.substring(adjustedEnd.coerceAtMost(textWithImports.length))
                                                    val finalCursor = if (snippetParsed != null) {
                                                        val session = createSnippetSession(adjustedStart, snippetParsed)
                                                        snippetSession = session
                                                        showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                        val firstStop = session.tabStops.firstOrNull()
                                                        if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                            firstStop.startOffset
                                                        } else {
                                                            firstStop?.startOffset ?: session.finalCursorOffset
                                                        }
                                                    } else {
                                                        adjustedStart + textToInsert.length
                                                    }
                                                    Pair(finalText, finalCursor)
                                                    Pair(finalText, finalCursor)
                                                } catch (_: Exception) {
                                                    // Fallback: plain insert without auto-import
                                                    val newText = text.substring(0, start) + comp.insertText + text.substring(end)
                                                    Pair(newText, start + comp.insertText.length)
                                                }
                                            }
                                            // P41-I: If snippet, select first tab-stop default text
                                            val selRange = if (snippetSession != null) {
                                                val session = snippetSession!!
                                                val firstStop = session.tabStops.firstOrNull()
                                                if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                    androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                } else {
                                                    androidx.compose.ui.text.TextRange(result.second)
                                                }
                                            } else {
                                                androidx.compose.ui.text.TextRange(result.second)
                                            }
                                            extraCursors = EditShiftHelper.shiftExtraCursors(value.text, result.first, extraCursors)
                                            programmaticTextChange(result.first, selRange, "format_result")
                                        }
                                    } else {
                                        // P41-I: Handle snippet insertTextFormat == 2
                                        val (rawInsert, snipParsed) = if (comp.insertTextFormat == 2) {
                                            val parsed = parseSnippet(comp.insertText, SnippetContext(
                                                lineNumber = positionMapper.offsetToLine(start) + 1,
                                                lineIndex = positionMapper.offsetToLine(start),
                                                currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                                selectedText = if (start != end) value.text.substring(start, end) else "",
                                            ))
                                            Pair(parsed.cleanedText, parsed)
                                        } else {
                                            Pair(comp.insertText, null)
                                        }
                                        var newText = text.substring(0, start) + rawInsert + text.substring(end)
                                        var newCursor = start + rawInsert.length
                                        // P22-J: Fall back to lspImportProvider for auto-import via code actions
                                        if (lspImportProvider != null) {
                                            val cPos = positionMapper.offsetToPosition(cursor)
                                            val cLine = cPos.line
                                            val cCol = cPos.column
                                            coroutineScope.launch {
                                                val imports = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    try { lspImportProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList() }
                                                }
                                                if (imports.isNotEmpty()) {
                                                    val patched = applyImportEdits(newText, imports)
                                                    val importDelta = patched.length - newText.length
                                                    if (snipParsed != null) {
                                                        // P41-I: Snippet mode after import
                                                        val session = createSnippetSession(start + importDelta, snipParsed)
                                                        snippetSession = session
                                                        showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                        val firstStop = session.tabStops.firstOrNull()
                                                        val sel = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                            androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                        } else {
                                                            androidx.compose.ui.text.TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                                        }
                                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, patched, extraCursors)
                                                        programmaticTextChange(patched, sel, "auto_import_patched")
                                                    } else {
                                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, patched, extraCursors)
                                                        programmaticTextChange(patched, androidx.compose.ui.text.TextRange(newCursor + importDelta), "auto_import_delta")
                                                    }
                                                    onContentChange(patched)
                                                } else {
                                                    if (snipParsed != null) {
                                                        // P41-I: Snippet mode, no imports needed
                                                        val session = createSnippetSession(start, snipParsed)
                                                        snippetSession = session
                                                        showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                        val firstStop = session.tabStops.firstOrNull()
                                                        val sel = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                            androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                        } else {
                                                            androidx.compose.ui.text.TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                                        }
                                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                        programmaticTextChange(newText, sel, "ai_fix_applied")
                                                    } else {
                                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                        programmaticTextChange(newText, androidx.compose.ui.text.TextRange(newCursor), "ai_fix_newcursor")
                                                    }
                                                    onContentChange(newText)
                                                }
                                            }
                                        } else {
                                            // P41-I: If this is a snippet (insertTextFormat == 2), parse and enter snippet mode
                                            if (comp.insertTextFormat == 2) {
                                                val parsed = parseSnippet(comp.insertText, SnippetContext(
                                                fileName = "",
                                                lineNumber = positionMapper.offsetToLine(start) + 1,
                                                lineIndex = positionMapper.offsetToLine(start),
                                                currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                                selectedText = if (start != end) value.text.substring(start, end) else "",
                                            ))
                                                val snippetText = parsed.cleanedText
                                                newText = text.substring(0, start) + snippetText + text.substring(end)
                                                val session = createSnippetSession(start, parsed)
                                                snippetSession = session
                                                showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                // Place cursor at first tab-stop, or final cursor if no stops
                                                val firstStop = session.tabStops.firstOrNull()
                                                val cursorPos = if (firstStop != null) {
                                                    firstStop.startOffset
                                                } else {
                                                    session.finalCursorOffset
                                                }
                                                // If first stop has default text, select it
                                                val selectionRange = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                    androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                } else {
                                                    androidx.compose.ui.text.TextRange(cursorPos)
                                                }
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                programmaticTextChange(newText, selectionRange, "ai_result")
                                            } else {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                programmaticTextChange(newText, androidx.compose.ui.text.TextRange(newCursor), "ai_result_cursor")
                                            }
                                        }
                                    }
                                    // P41 Phase B: Record accepted completion for MRU/usage ranking
                                    CompletionHistoryStore.recordAccepted(comp.label, language.name, context)
                                    showCompletions = false
                                    selectedLabel = null
                                    completionFilter = null
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // P41-H: Full LSP CompletionItemKind icon mapping (1-25)
                            val (icon, tint) = if (comp.lspKind > 0) {
                                lspCompletionIcon(comp.lspKind)
                            } else {
                                when (comp.kind) {
                                    CompletionKind.KEYWORD -> Pair(Icons.Default.Code, Color(0xFF569CD6))
                                    CompletionKind.TYPE -> Pair(Icons.Default.TextFields, Color(0xFF4EC9B0))
                                    CompletionKind.SNIPPET -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))
                                }
                            }
                            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                // P41 Phase C: Highlight fuzzy-matched characters in the label
                                val matchIndices = fuzzyMatchIndices(prefix, comp.label)
                                val labelAnnotated = if (matchIndices.isNotEmpty()) {
                                    buildAnnotatedString {
                                        for ((idx, ch) in comp.label.withIndex()) {
                                            if (idx in matchIndices) {
                                                append(AnnotatedString(
                                                    ch.toString(),
                                                    SpanStyle(
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF4DA6FF),
                                                    )
                                                ))
                                            } else {
                                                append(ch)
                                            }
                                        }
                                    }
                                } else {
                                    AnnotatedString(comp.label)
                                }
                                // P41-J: Deprecation indicator — strike-through for deprecated items
                                if (comp.isDeprecated) {
                                    Text(
                                        labelAnnotated,
                                        color = Color(0xFF888888),
                                        fontSize = (fontSize - 1).sp,
                                        fontFamily = FontFamily.Monospace,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                    )
                                } else {
                                    Text(labelAnnotated, color = Color(0xFFD4D4D4), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
                                }
                                if (comp.doc != null) {
                                    Text(comp.doc, color = Color(0xFF888888), fontSize = 9.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // P41-J: Source badge — small colored label
                            val (badgeText, badgeColor) = when (comp.source) {
                                CompletionSource.LSP -> "LSP" to Color(0xFF4EC9B0)
                                CompletionSource.BUFFER -> "Buf" to Color(0xFF888888)
                                CompletionSource.SNIPPET -> "Snip" to Color(0xFFDCDCAA)
                                CompletionSource.WORKSPACE -> "Wksp" to Color(0xFF4DA6FF)
                                CompletionSource.AI -> "AI" to Color(0xFFC586C0)
                                CompletionSource.PATH -> "Path" to Color(0xFF9CDCFE)
                            }
                            Text(badgeText, color = badgeColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                        }
                    }
                    
                    // P41-L: "?" Explain affordance for AI-sourced completions
                    if (initialIndex < filteredCompletions.size) {
                        val highlighted = filteredCompletions[initialIndex]
                        if (highlighted.source == CompletionSource.AI && onAiFixRequest != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = "? Explain",
                                    color = Color(0xFFC586C0),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clickable {
                                            val cursor = value.selection.end
                                            val text = value.text
                                            val lineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursor))
                                            val lineEnd = text.indexOf('\n', cursor)
                                            val lineText = text.substring(lineStart, if (lineEnd < 0) text.length else lineEnd)
                                            val prompt = "Explain why you suggested \"" + highlighted.label + "\" here.\n" +
                                                "Current line: " + lineText + "\n" +
                                                "File type: " + language.name
                                            onAiFixRequest?.invoke(prompt)
                                            showCompletions = false
                                        },
                                )
                            }
                        }
                    }
                    // P41-J: Detail panel — modern: expand + copy + scroll (matches HoverPopup)
                    var detailExpanded by remember { mutableStateOf(false) }
                    val detailScrollState = rememberScrollState()
                    if (detailDoc != null && detailDoc!!.isNotBlank()) {
                        HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 1.dp)
                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252526))
                            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Box(modifier = Modifier.size(20.dp).clickable { detailExpanded = !detailExpanded },
                                    contentAlignment = Alignment.Center) {
                                    Text(text = if (detailExpanded) "▾" else "▸", color = Color(0xFF888888), fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(2.dp))
                                Box(modifier = Modifier.size(20.dp).clickable {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(detailDoc ?: ""))
                                    }, contentAlignment = Alignment.Center) {
                                    Text(text = "⏉", color = Color(0xFF888888), fontSize = 11.sp)
                                }
                            }
                            Box(modifier = Modifier.padding(horizontal = 4.dp)
                                .then(if (detailExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(detailScrollState) else Modifier.heightIn(max = 60.dp))) {
                                Column {
                                    if (detailLabel != null) {
                                        Text(text = detailLabel!!, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF569CD6), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(text = detailDoc!!, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC))
                                }
                            }
                        }
                    }
                    // NEW (2026-08-10): Drag handle to resize the popup — drag down to grow, up to shrink.
                    // Matches VS Code's resizable IntelliSense widget seen in vscode.dev testing.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(Color(0xFF2D2D2D))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val dragDp = with(scrollDensity) { dragAmount.y.toDp().value }
                                    completionPopupExtraHeightDp = (completionPopupExtraHeightDp + dragDp)
                                        .coerceIn(0f, 400f)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(3.dp)
                                .background(Color(0xFF5A5A5A), RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

// P41-E: Ghost text overlay composable — extracted from main CodeEditor to avoid method-too-large
// P41-J: Filter chip composable for completion dropdown
@Composable
private fun FilterChip(
    label: String,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (isActive) color.copy(alpha = 0.25f) else Color(0xFF333333),
                RoundedCornerShape(3.dp)
            )
            .border(
                1.dp,
                if (isActive) color else Color(0xFF444444),
                RoundedCornerShape(3.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = if (isActive) color else Color(0xFF888888),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// P41-H: Full LSP CompletionItemKind (1-25) icon + color mapping.
// Colors follow VS Code's theme: https://code.visualstudio.com/docs/languages/identifiers
private fun lspCompletionIcon(kind: Int): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {
    return when (kind) {
        1   -> Pair(Icons.Default.TextFields, Color(0xFFCCCCCC))    // Text — gray
        2   -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))    // Method — yellow
        3   -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))    // Function — yellow
        4   -> Pair(Icons.Default.Build, Color(0xFFB8D7A3))         // Constructor — light green
        5   -> Pair(Icons.Default.DataObject, Color(0xFF9CDCFE))   // Field — light blue
        6   -> Pair(Icons.Default.DataObject, Color(0xFF9CDCFE))   // Variable — light blue
        7   -> Pair(Icons.Default.Extension, Color(0xFF4EC9B0))    // Class — teal
        8   -> Pair(Icons.Default.Extension, Color(0xFFB8D7A3))    // Interface — light green
        9   -> Pair(Icons.Default.Public, Color(0xFFCE9178))       // Module — orange
        10  -> Pair(Icons.Default.Tune, Color(0xFF9CDCFE))         // Property — light blue
        11  -> Pair(Icons.Default.Public, Color(0xFFCE9178))       // Unit — orange
        12  -> Pair(Icons.Default.Star, Color(0xFF569CD6))        // Value — blue
        13  -> Pair(Icons.Default.List, Color(0xFF4EC9B0))        // Enum — teal
        14  -> Pair(Icons.Default.Code, Color(0xFF569CD6))       // Keyword — blue
        15  -> Pair(Icons.Default.AutoAwesome, Color(0xFFDCDCAA)) // Snippet — yellow
        16  -> Pair(Icons.Default.ColorLens, Color(0xFFCE9178))   // Color — orange
        17  -> Pair(Icons.Default.Description, Color(0xFF9CDCFE)) // File — light blue
        18  -> Pair(Icons.Default.Link, Color(0xFFCCCCCC))        // Reference — gray
        19  -> Pair(Icons.Default.Folder, Color(0xFFDCB67A))       // Folder — gold
        20  -> Pair(Icons.Default.Label, Color(0xFF4EC9B0))       // EnumMember — teal
        21  -> Pair(Icons.Default.Star, Color(0xFF4FC1FF))        // Constant — bright blue
        22  -> Pair(Icons.Default.Extension, Color(0xFF4EC9B0))  // Struct — teal
        23  -> Pair(Icons.Default.Event, Color(0xFFB8D7A3))       // Event — light green
        24  -> Pair(Icons.Default.Calculate, Color(0xFF569CD6))   // Operator — blue
        25  -> Pair(Icons.Default.TextFields, Color(0xFF4EC9B0))  // TypeParameter — teal
        else -> Pair(Icons.Default.Code, Color(0xFFCCCCCC))       // Unknown — gray
    }
}



@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomPanels(
    findRefWord: String?,
    findRefLoading: Boolean,
    findRefUsedLsp: Boolean,
    findRefResults: List<Triple<String, Int, String>>,
    onDismissFindRef: () -> Unit,
    onScrollToLine: (Int) -> Unit,
    filePath: String?,
    onOpenFileAtLine: (String, Int) -> Unit,
    showCallHierarchy: Boolean,
    callHierarchyRoot: CallHierarchyItem?,
    callHierarchyIncoming: List<com.codespace.ide.lsp.IncomingCall>,
    callHierarchyOutgoing: List<com.codespace.ide.lsp.OutgoingCall>,
    onDismissCallHierarchy: () -> Unit,
    onDismissTypeHierarchy: () -> Unit,
    showTypeHierarchy: Boolean,
    typeHierarchyRoot: TypeHierarchyItem?,
    typeHierarchySupertypes: List<com.codespace.ide.lsp.TypeHierarchyItem>,
    typeHierarchySubtypes: List<com.codespace.ide.lsp.TypeHierarchyItem>,
) {
    val colors = LocalEditorColors.current
    // ── P24-3: Find References Overlay ──
    if (findRefWord != null) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .zIndex(28f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF252526))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("References: ${findRefWord}", color = Color(0xFF9CDCFE), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Box(
                        Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            .background(if (findRefUsedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(if (findRefUsedLsp) "LSP" else "Fallback", color = colors.background, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (findRefLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Color(0xFF007ACC))
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismissFindRef, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("✕", color = Color(0xFF888888), fontSize = 14.sp)
                    }
                }
                Divider(color = Color(0xFF333333))
                if (!findRefLoading && findRefResults.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                        Text("No references found for '${findRefWord}'.", color = Color(0xFF888888), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        items(findRefResults) { (refPath, refLine, snippet) ->
                            val fileName = refPath.substringAfterLast('/')
                            TextButton(
                                onClick = {
                                    if (refPath == filePath) { onScrollToLine(refLine) }
                                    else { onOpenFileAtLine(refPath, refLine) }
                                    onDismissFindRef()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(fileName, color = Color(0xFF4EC9B0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                        Text(":${refLine + 1}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Text(snippet.trim().take(100), color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Divider(color = Color(0xFF2A2A2A))
                        }
                    }
                }
            }
        }
    }
    // ── P41-M: Call Hierarchy Panel ──
    if (showCallHierarchy && callHierarchyRoot != null) {
        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f).zIndex(29f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Call Hierarchy", color = Color(0xFF4EC9B0), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismissCallHierarchy, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("✕", color = Color(0xFF888888), fontSize = 14.sp)
                    }
                }
                Divider(color = Color(0xFF333333))
                CallHierarchyPanel(
                    rootItem = callHierarchyRoot!!,
                    incomingCalls = callHierarchyIncoming,
                    outgoingCalls = callHierarchyOutgoing,
                    onNavigate = { uri, line, _ ->
                        val path = uri.removePrefix("file://")
                        if (path == filePath || path.endsWith(filePath ?: "")) { onScrollToLine(line) }
                        else { onOpenFileAtLine(path, line) }
                    },
                )
            }
        }
    }
    // ── P41-M: Type Hierarchy Panel ──
    if (showTypeHierarchy && typeHierarchyRoot != null) {
        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f).zIndex(30f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Type Hierarchy", color = Color(0xFF4EC9B0), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismissTypeHierarchy, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                        Text("✕", color = Color(0xFF888888), fontSize = 14.sp)
                    }
                }
                Divider(color = Color(0xFF333333))
                TypeHierarchyPanel(
                    rootItem = typeHierarchyRoot!!,
                    supertypes = typeHierarchySupertypes,
                    subtypes = typeHierarchySubtypes,
                    onNavigate = { uri, line, _ ->
                        val path = uri.removePrefix("file://")
                        if (path == filePath || path.endsWith(filePath ?: "")) { onScrollToLine(line) }
                        else { onOpenFileAtLine(path, line) }
                    },
                )
            }
        }
    }
}



// P-CURSOR: Animated cursor brush composable — handles different blink styles
// Test 30 fix: SOLID and EXPAND previously set alpha=0f (invisible) expecting a
// custom drawWithContent cursor that was never implemented. Now SOLID uses the
// base color at full opacity (always visible, no blink). EXPAND animates between
// full and reduced alpha with a slight pulse, simulating block expand/contract.
// Uses LaunchedEffect + kotlinx.delay to avoid animation-core dependency issues.
@Composable
private fun animatedCursorBrush(baseColor: Color): Brush {
    val style = ProjectSettingsStore.cursorBlinkStyle.value
    return when (style) {
        CursorBlinkStyle.BLINK -> androidx.compose.ui.graphics.SolidColor(baseColor)
        CursorBlinkStyle.SOLID -> androidx.compose.ui.graphics.SolidColor(baseColor)
        CursorBlinkStyle.PHASE -> {
            var alpha by remember { mutableStateOf(1f) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(600)
                    alpha = if (alpha > 0.6f) 0.3f else 1f
                }
            }
            androidx.compose.ui.graphics.SolidColor(baseColor.copy(alpha = alpha))
        }
        CursorBlinkStyle.SMOOTH -> {
            var alpha by remember { mutableStateOf(1f) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(400)
                    alpha = if (alpha > 0.7f) 0.5f else 1f
                }
            }
            androidx.compose.ui.graphics.SolidColor(baseColor.copy(alpha = alpha))
        }
        CursorBlinkStyle.EXPAND -> {
            // Expand: pulse between full opacity and ~40% to simulate
            // block-style expand/contract animation
            var alpha by remember { mutableStateOf(1f) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(300)
                    alpha = if (alpha > 0.7f) 0.4f else 1f
                }
            }
            androidx.compose.ui.graphics.SolidColor(baseColor.copy(alpha = alpha))
        }
    }
}

/**
 * R2-4: Heuristic check for whether cursor is inside a string literal.
 * Used to suppress auto-close brackets inside strings.
 *
 * Known limitation: Does not account for comments (e.g., `#` or `//` before a
 * quote would misread it as being inside a string). This is acceptable as a
 * first pass — auto-close inside comments is harmless (the closer is inserted
 * but rarely causes issues in comment text).
 */
private fun isInsideStringValue(text: String, cursor: Int): Boolean {
    var i = 0
    var inString = false
    var inChar = false
    var stringChar = '"'
    while (i < cursor && i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            i += 2
            continue
        }
        val isDouble = c == '"'
        val isSingle = c == '\''
        if (!inString && !inChar && (isDouble || isSingle)) {
            inString = isDouble
            inChar = isSingle
            stringChar = c
        } else if (inString && c == stringChar) {
            inString = false
        } else if (inChar && c == stringChar) {
            inChar = false
        }
        i++
    }
    return inString || inChar
}

/**
 * R3-5: Find the sticky scroll symbol from LSP document symbols.
 * Returns the name of the deepest symbol whose range contains the current top line.
 * Uses LSP range (0-based lines) to determine which scope contains the visible top.
 */
private fun findStickySymbolFromLSP(
    symbols: org.json.JSONArray,
    topLineIdx: Int,
    rawLines: List<String>,
): String? {
    var bestSymbol: String? = null
    var bestDepth = -1

    fun searchSymbol(sym: org.json.JSONObject, depth: Int) {
        val name = sym.optString("name", "")
        if (name.isBlank()) return
        val range = sym.optJSONObject("range") ?: return
        val startLine = range.optJSONObject("start")?.optInt("line", 0) ?: 0
        val endLine = range.optJSONObject("end")?.optInt("line", 0) ?: Int.MAX_VALUE
        // Check if topLineIdx is within this symbol's range
        if (topLineIdx in startLine..endLine) {
            // This symbol contains the top line — prefer deeper (more specific) symbols
            if (depth > bestDepth) {
                bestDepth = depth
                bestSymbol = name
            }
            // Search children for more specific containing symbols
            val children = sym.optJSONArray("children")
            if (children != null) {
                for (i in 0 until children.length()) {
                    val child = children.optJSONObject(i) ?: continue
                    searchSymbol(child, depth + 1)
                }
            }
        }
    }

    for (i in 0 until symbols.length()) {
        val sym = symbols.optJSONObject(i) ?: continue
        searchSymbol(sym, 0)
    }
    return bestSymbol
}
