package com.codespace.ide.editor

import androidx.compose.foundation.background
import org.json.JSONObject
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.codespace.ide.lsp.CompletionSource
import com.codespace.ide.lsp.RankedCompletionItem
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
import com.codespace.ide.lsp.LspCodeAction
import com.codespace.ide.ui.LocalEditorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material3.HorizontalDivider

private data class Completion(val label: String, val kind: CompletionKind, val insertText: String = label, val doc: String? = null)
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
        Completion("fun", CompletionKind.SNIPPET, "fun name(): Unit {\n    \n}", "Function declaration"),
        Completion("class", CompletionKind.SNIPPET, "class Name {\n    \n}", "Class declaration"),
        Completion("data class", CompletionKind.SNIPPET, "data class Name(val field: Type)", "Data class — auto-generates equals/hashCode/copy"),
        Completion("when", CompletionKind.SNIPPET, "when (expr) {\n    else -> {}\n}", "Exhaustive when expression"),
        Completion("if", CompletionKind.SNIPPET, "if (condition) {\n    \n}", "If statement"),
        Completion("for", CompletionKind.SNIPPET, "for (item in collection) {\n    \n}", "For-each loop"),
        Completion("object", CompletionKind.SNIPPET, "object Name {\n    \n}", "Singleton object"),
        Completion("companion object", CompletionKind.SNIPPET, "companion object {\n    \n}", "Companion object (static members)"),
        Completion("launch", CompletionKind.SNIPPET, "launch {\n    \n}", "Launch a coroutine"),
        Completion("LaunchedEffect", CompletionKind.SNIPPET, "LaunchedEffect(key) {\n    \n}", "Run suspend block scoped to composition"),
        Completion("remember", CompletionKind.SNIPPET, "remember { mutableStateOf() }", "Cache value across recompositions"),
        Completion("Composable", CompletionKind.SNIPPET, "@Composable\nfun Name() {\n    \n}", "Jetpack Compose function"),
    )
    Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
        Completion("function", CompletionKind.SNIPPET, "function name(params) {\n    \n}", "Function declaration"),
        Completion("const", CompletionKind.SNIPPET, "const name = value", "Immutable binding"),
        Completion("async function", CompletionKind.SNIPPET, "async function name() {\n    \n}", "Async function"),
        Completion("class", CompletionKind.SNIPPET, "class Name {\n  constructor() {\n    \n  }\n}", "Class declaration"),
        Completion("for...of", CompletionKind.SNIPPET, "for (const item of array) {\n    \n}", "Iterate over iterable"),
        Completion("try", CompletionKind.SNIPPET, "try {\n    \n} catch (err) {\n    \n}", "Try-catch"),
        Completion("Promise", CompletionKind.SNIPPET, "new Promise((resolve, reject) => {\n    \n})", "Create a Promise"),
        Completion("console.log", CompletionKind.SNIPPET, "console.log()", "Log to console"),
        Completion("=>", CompletionKind.SNIPPET, "(params) => {\n    \n}", "Arrow function"),
    )
    Language.PYTHON -> listOf(
        Completion("def", CompletionKind.SNIPPET, "def name(params):\n    ", "Function definition"),
        Completion("class", CompletionKind.SNIPPET, "class Name:\n    def __init__(self):\n        ", "Class with constructor"),
        Completion("if", CompletionKind.SNIPPET, "if condition:\n    ", "If statement"),
        Completion("for", CompletionKind.SNIPPET, "for item in collection:\n    ", "For loop"),
        Completion("with", CompletionKind.SNIPPET, "with open('file') as f:\n    ", "Context manager"),
        Completion("try", CompletionKind.SNIPPET, "try:\n    \nexcept Exception as e:\n    ", "Try-except"),
        Completion("lambda", CompletionKind.SNIPPET, "lambda x: x", "Anonymous function"),
        Completion("list comprehension", CompletionKind.SNIPPET, "[expr for item in iterable]", "List comprehension"),
        Completion("print", CompletionKind.SNIPPET, "print()", "Print to stdout"),
    )
    Language.JAVA -> listOf(
        Completion("public class", CompletionKind.SNIPPET, "public class Name {\n    \n}", "Public class"),
        Completion("public static void main", CompletionKind.SNIPPET, "public static void main(String[] args) {\n    \n}", "Main method"),
        Completion("for", CompletionKind.SNIPPET, "for (int i = 0; i < n; i++) {\n    \n}", "For loop"),
        Completion("try", CompletionKind.SNIPPET, "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", "Try-catch"),
        Completion("interface", CompletionKind.SNIPPET, "public interface Name {\n    \n}", "Interface"),
        Completion("@Override", CompletionKind.SNIPPET, "@Override\npublic void method() {\n    \n}", "Override annotation"),
    )
    Language.RUST -> listOf(
        Completion("fn", CompletionKind.SNIPPET, "fn name() -> () {\n    \n}", "Function declaration"),
        Completion("struct", CompletionKind.SNIPPET, "struct Name {\n    field: Type,\n}", "Struct definition"),
        Completion("impl", CompletionKind.SNIPPET, "impl Name {\n    fn method(&self) {\n        \n    }\n}", "Impl block"),
        Completion("match", CompletionKind.SNIPPET, "match value {\n    Some(x) => x,\n    None => todo!(),\n}", "Match expression"),
        Completion("let", CompletionKind.SNIPPET, "let name = value;", "Immutable binding"),
        Completion("let mut", CompletionKind.SNIPPET, "let mut name = value;", "Mutable binding"),
        Completion("for", CompletionKind.SNIPPET, "for item in collection {\n    \n}", "For loop"),
    )
    Language.GO -> listOf(
        Completion("func", CompletionKind.SNIPPET, "func name() {\n    \n}", "Function declaration"),
        Completion("struct", CompletionKind.SNIPPET, "type Name struct {\n    Field Type\n}", "Struct type"),
        Completion("interface", CompletionKind.SNIPPET, "type Name interface {\n    Method() ReturnType\n}", "Interface type"),
        Completion("for", CompletionKind.SNIPPET, "for i := 0; i < n; i++ {\n    \n}", "For loop"),
        Completion("goroutine", CompletionKind.SNIPPET, "go func() {\n    \n}()", "Anonymous goroutine"),
        Completion("defer", CompletionKind.SNIPPET, "defer func() {\n    \n}()", "Deferred cleanup"),
        Completion("err check", CompletionKind.SNIPPET, "if err != nil {\n    return err\n}", "Error check"),
    )
    else -> listOf(
        Completion("TODO", CompletionKind.SNIPPET, "TODO", "Mark as not yet implemented"),
        Completion("FIXME", CompletionKind.SNIPPET, "FIXME", "Mark as needing a fix"),
    )
}
private fun completionsFor(prefix: String, lang: Language): List<Completion> {
    if (prefix.length < 2) return emptyList()
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
    return (snips + kw + ty).distinctBy { it.label }.take(10)
}

private fun currentWord(text: String, cursor: Int): String {
    val end = cursor.coerceAtMost(text.length)
    var start = end
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    return text.substring(start, end)
}


/** P22-L: Peek Definition result — inline code preview without navigating away. */
data class PeekDefResult(val filePath: String, val line: Int, val lines: List<String>, val defLine: Int)

/** P20-A: Git blame info per line */
data class BlameLine(val author: String, val date: String, val shortSha: String)

@OptIn(ExperimentalFoundationApi::class)
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
    goToLineOpen: Boolean = false,
    onGoToLineClose: () -> Unit = {},
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
    /** P26-1: LSP document highlight — lines to highlight (0-based startLine, endLine pairs). */
    lspHighlightLines: List<Pair<Int, Int>> = emptyList(),
    /** P26-1: LSP document symbols — outline structure (JSONArray of DocumentSymbol). */
    lspDocumentSymbols: org.json.JSONArray? = null,
    /** P26-1: LSP folding ranges — pairs of (startLine, endLine) for LSP-based folding. */
    lspFoldingRanges: List<Pair<Int, Int>> = emptyList(),
    /** P26-1: LSP code lens — inline annotations (JSONArray of CodeLens). */
    lspCodeLenses: org.json.JSONArray? = null,
    /** P26-1: LSP inlay hints — inline type/parameter hints (JSONArray of InlayHint). */
    lspInlayHints: org.json.JSONArray? = null,
    /** P26-1: LSP document links — clickable links in comments (JSONArray of DocumentLink). */
    lspDocumentLinks: org.json.JSONArray? = null,
    /** P26-1: LSP Type Definition — called from context menu to peek type definition. */
    onLspTypeDefinition: (() -> Boolean)? = null,  // P37-3fix: returns true if LSP succeeded
    /** P26-1: LSP Implementation — called from context menu to find implementations. */
    onLspImplementation: (() -> Boolean)? = null,  // P37-3fix: returns true if LSP succeeded
    /** P26-1: LSP Selection Range — expand selection to semantic boundary (line, col). */
    onLspSelectionRange: ((Int, Int) -> org.json.JSONArray?)? = null,
    /** P26-1: LSP Prepare Rename — check if symbol at position can be renamed (line, col). */
    onLspPrepareRename: ((Int, Int) -> JSONObject?)? = null,
    /** P26-1: LSP Workspace Symbol — search symbols across workspace (query string). */
    /** P15-A: Fix with AI — called with a pre-formatted prompt when user taps "Fix with AI". */
    onAiFixRequest: ((String) -> Unit)? = null,
    /** P18-C: Project root path for cross-file rename. Null = single-file only. */
    projectRoot: String? = null,
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
    /** P24-1: LSP diagnostics as LintErrors — shown as squiggles on top of syntax highlighting */
    lspDiagnosticErrors: List<LintError> = emptyList(),
    /** P24-3: Find References — called with word at cursor, returns list of (filePath, line, snippet) */
    onFindReferences: ((String) -> List<Triple<String, Int, String>>)? = null,
    /** P24-3: Rename Symbol — called with (word, newName) to apply workspace rename */
    onRenameSymbol: ((String, String) -> Unit)? = null,
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
    onLspDefinition: (() -> Boolean)? = null,
) {
    val colors = LocalEditorColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    // FIX: Focus + keyboard management — the transparent overlay intercepts taps
    // before BasicTextField sees them, so we must explicitly request focus + show
    // keyboard on every tap. Without this, the keyboard never appears after the
    // overlay consumes the gesture (the #1 bug blocking all editing).
    val focusRequester = remember { FocusRequester() }
    // focusRequester is used by the floating LSP button to maintain focus on the editor
    // FIX(P38): Sync external content changes (e.g. format button, file reload)
    // to the internal TextFieldValue. Without this, updating the 'content'
    // parameter from outside (like the format button updating tabs[idx].content)
    // has no effect — the editor keeps showing the old text because 'remember'
    // only initializes once.
    LaunchedEffect(content) {
        if (value.text != content) {
            value = TextFieldValue(content, TextRange(content.length))
        }
    }
    val vScroll = rememberScrollState()
    // P26-1: Scroll to line when scrollToLine parameter changes
    val scrollDensity = androidx.compose.ui.platform.LocalDensity.current
    // PROBLEMS-TAB FIX: temporary gold highlight on the target line so the user can SEE
    // where the problem is after the bottom panel closes. Auto-clears after 2.5s.
    var highlightTargetLine by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToLine) {
        if (scrollToLine > 0) {
            val lineHeightPx = with(scrollDensity) { (fontSize * 1.25f).dp.toPx() }
            vScroll.animateScrollTo((scrollToLine * lineHeightPx).toInt())
            highlightTargetLine = scrollToLine
            kotlinx.coroutines.delay(2500)
            highlightTargetLine = 0
        }
    }
    val hScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showMinimapState by remember { mutableStateOf(showMinimap) }

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

    val _lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    // P15-C: Sticky scroll — derives the "current scope" line from the scroll position.
    // Uses the line height formula: lineIdx = scrollPx / (fontSize * 1.25f).
    // Finds the nearest non-blank, non-folded ancestor line above the visible top.
    val stickyLine: String? = remember(vScroll.value, rawLines, foldedLineIndices, fontSize) {
        if (rawLines.size < 3) return@remember null
        val lineHeightPx = fontSize * 1.25f
        val topLineIdx = (vScroll.value / lineHeightPx).toInt()
        // Walk upward from topLineIdx to find the nearest scope-opening line
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
    // P33-INTELLISENSE: Detect dot-triggered completions (e.g. "lines." "user.")
    val isDotTriggered = remember(value) {
        val cursor = value.selection.end.coerceAtMost(value.text.length)
        cursor > 0 && value.text.getOrElse(cursor - 1) { ' ' } == '.'
    }
    val completions = remember(prefix, language) { completionsFor(prefix, language) }
    var showCompletions by remember { mutableStateOf(false) }

    // P39: Lightbulb state — tracks code actions per line for gutter display
    var lightbulbLine by remember { mutableStateOf(-1) }
    var lightbulbActions by remember { mutableStateOf<List<com.codespace.ide.lsp.LspCodeAction>>(emptyList()) }
    var showLightbulbMenu by remember { mutableStateOf(false) }
    // P39: Async-fetch code actions when cursor moves to a new line (debounced 500ms)
    LaunchedEffect(value.selection.start) {
        if (lspCodeActionProvider != null) {
            kotlinx.coroutines.delay(500L)
            val cursorLine = value.text.take(value.selection.start).count { it == '\n' }
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

    // P22-H: LSP-backed completion
    var lspCompletions by remember { mutableStateOf<List<LspCompletionItem>>(emptyList()) }
    LaunchedEffect(prefix, isDotTriggered, value.selection.end) {
        if ((prefix.length >= 2 || isDotTriggered) && lspCompletionProvider != null) {
            kotlinx.coroutines.delay(150)  // shorter delay for dot trigger
            val cOff = value.selection.end
            val cLine = value.text.take(cOff).count { it == '\n' }
            val cLineStart = value.text.lastIndexOf('\n', (cOff - 1).coerceAtLeast(0)) + 1
            val cCol = cOff - cLineStart
            lspCompletions = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try { lspCompletionProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList() }
            }
        } else {
            lspCompletions = emptyList()
        }
    }
    // P41 Phase A: Use CompletionEngine for fuzzy matching + ranking
    val allCompletions = remember(completions, lspCompletions, prefix) {
        // Convert local completions to RankedCompletionItem
        val localRanked = completions.map { c ->
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
        // Convert LSP completions to RankedCompletionItem
        val lspRanked = lspCompletions.map { item ->
            RankedCompletionItem(
                label = item.label, kind = item.kind, detail = item.detail,
                insertText = item.insertText, source = CompletionSource.LSP,
            )
        }
        // Merge, deduplicate by label, rank with fuzzy matching
        val merged = (lspRanked + localRanked).distinctBy { it.label }
        val ranked = rank(merged, prefix, CompletionHistoryStore.mruMap(), CompletionHistoryStore.usageMap())
        // Map back to Completion for the existing dropdown UI
        ranked.take(15).map { rc ->
            val kind = when (rc.kind) {
                CompletionItemKind.SNIPPET -> CompletionKind.SNIPPET
                in 2..13 -> CompletionKind.TYPE
                22, 23 -> CompletionKind.TYPE
                else -> CompletionKind.KEYWORD
            }
            Completion(rc.label, kind, rc.insertText, rc.detail)
        }
    }
    // P41 Phase B: Load completion history once per file open
    LaunchedEffect(Unit) { CompletionHistoryStore.load(context) }

    LaunchedEffect(prefix, isDotTriggered, allCompletions) { showCompletions = (prefix.length >= 2 || isDotTriggered) && allCompletions.isNotEmpty() }

    // P15-D: Ghost text — shows the top IntelliSense completion as grey inline text
    // after 800ms idle with a non-empty prefix. Tab/→ accepts; any edit dismisses.
    var ghostText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prefix, isDotTriggered, allCompletions) {
        ghostText = null
        if ((prefix.length >= 2 || isDotTriggered) && allCompletions.isNotEmpty()) {
            kotlinx.coroutines.delay(800L)
            val top = allCompletions.firstOrNull()
            if (top != null) {
                // P41: Use fuzzy match for ghost text — show if it's a good match
                val score = fuzzyScore(prefix, top.label)
                if (score > 0f && top.insertText.startsWith(prefix, ignoreCase = true)) {
                    ghostText = top.insertText.removePrefix(prefix).lines().first()
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
    val activeSignature = remember(value.text, value.selection.end, language, lspSignatureHelpProvider) {
        // Quick guard: check if cursor is inside a function call before invoking LSP
        val textBeforeCursor = value.text.substring(0, value.selection.end.coerceAtMost(value.text.length))
        var depth = 0
        var insideCall = false
        for (ch in textBeforeCursor.reversed()) {
            when (ch) {
                ')' -> depth++
                '(' -> { if (depth == 0) { insideCall = true }; break }
            }
        }
        if (lspSignatureHelpProvider != null && insideCall) {
            val cLine = value.text.take(value.selection.end).count { it == '\n' }
            val cCol = value.selection.end - (value.text.lastIndexOf('\n', value.selection.end - 1) + 1)
            try { lspSignatureHelpProvider.invoke(cLine, cCol) } catch (_: Exception) { null }
                ?: SignatureHelpAnalyzer.findActiveCall(value.text, value.selection.end, language)
        } else if (insideCall) {
            SignatureHelpAnalyzer.findActiveCall(value.text, value.selection.end, language)
        } else {
            null
        }
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
    data class DefResult(val line: Int, val lineText: String)
    data class CrossFileDefResult(val name: String, val kind: String, val filePath: String, val line: Int, val fileName: String)
    var crossFileResults by remember { mutableStateOf<List<CrossFileDefResult>?>(null) }
    var gotoResults by remember { mutableStateOf<List<DefResult>?>(null) }
    // P22-L: Peek Definition result — inline code preview (class moved to top-level)
    var peekDefResult by remember { mutableStateOf<PeekDefResult?>(null) }
    // P38-FIX: Long-press trigger for auto-opening LSP menu
    var longPressTrigger by remember { mutableStateOf(0) }
    var peekUsedLsp by remember { mutableStateOf(false) }  // P37-3: track LSP vs fallback for peek
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
    var matchIndex by remember { mutableStateOf(0) }

    // ── Lint state ───────────────────────────────────────────────────────
    var lintErrors by remember { mutableStateOf<List<LintError>>(emptyList()) }
    LaunchedEffect(value.text, language) {
        kotlinx.coroutines.delay(500)   // debounce — only lint after 500 ms idle
        val localErrors = LintAnalyzer.analyze(value.text, language)
        // P24-1: merge LSP diagnostics as squiggles — deduplicate by start offset
        val combined = (localErrors + lspDiagnosticErrors).distinctBy { it.start }
        lintErrors = combined
    }

    // P24-1: Re-merge when LSP diagnostics arrive (server push)
    LaunchedEffect(lspDiagnosticErrors) {
        val localErrors = LintAnalyzer.analyze(value.text, language)
        lintErrors = (localErrors + lspDiagnosticErrors).distinctBy { it.start }
    }

    // ── P2-11 Inlay hints state ─────────────────────────────────────────
    var inlayHints by remember { mutableStateOf<List<InlayHint>>(emptyList()) }
    LaunchedEffect(value.text, language) {
        kotlinx.coroutines.delay(600)   // debounce — slightly after lint
        inlayHints = InlayHintAnalyzer.analyze(value.text, language)
    }

    // ── Multi-cursor state ───────────────────────────────────────────────
    var extraCursors by remember { mutableStateOf<List<Int>>(emptyList()) }
    // P22-K: Back press clears extra cursors (mobile equivalent of Escape)
    androidx.activity.compose.BackHandler(enabled = extraCursors.isNotEmpty()) {
        extraCursors = emptyList()
    }

    // ── Go to Line state ─────────────────────────────────────────────────
    var goToLineInput by remember { mutableStateOf("") }

    val matches = remember(value.text, findQuery, useRegex) {
        if (findQuery.isEmpty()) emptyList()
        else try {
            val pattern = if (useRegex) Regex(findQuery) else Regex(Regex.escape(findQuery))
            pattern.findAll(value.text).map { it.range }.toList()
        } catch (e: Exception) { emptyList() }
    }
    LaunchedEffect(matches.size, findQuery) {
        if (matchIndex >= matches.size) matchIndex = 0
    }

    // Bracket matching
    val _bracketMatch = remember(value) {
        val pos = value.selection.end
        if (pos == 0 || pos > value.text.length) null
        else {
            val before = if (pos > 0) value.text[pos - 1] else null
            val at = if (pos < value.text.length) value.text[pos] else null
            val bracket = before ?: at
            val bracketPos = if (before != null && (bracket == '(' || bracket == ')' || bracket == '[' || bracket == ']' || bracket == '{' || bracket == '}')) pos - 1
                          else if (at != null && (bracket == '(' || bracket == ')' || bracket == '[' || bracket == ']' || bracket == '{' || bracket == '}')) pos
                          else -1
            if (bracketPos >= 0) {
                val match = when (bracket) {
                    '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'
                    else -> null
                }
                if (match != null) {
                    val dir = if (bracket == '(' || bracket == '[' || bracket == '{') 1 else -1
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
        if (stickyLine != null) {
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
                .padding(end = if (showMinimapState) 62.dp else 4.dp, top = if (stickyLine != null) (fontSize * 1.4f).dp else 0.dp)
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
            Column(modifier = Modifier.padding(horizontal = 4.dp).width(72.dp)) {
                displayLines.forEach { (lineNum, _) ->
                    if (lineNum == -1) {
                        // Visual placeholder row in gutter
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height((fontSize * 1.25f).dp)
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
                            modifier = Modifier.height((fontSize * 1.25f).dp)
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
                                        color = Color(0xFF61AFEF),  // blue bookmark
                                        fontSize = (fontSize * 0.6f).sp,
                                    )
                                }
                            }
                            // P8-1 Breakpoint dot + tappable line number
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height((fontSize * 1.25f).dp)
                                    .clickable { onBreakpointToggle(lineNum) },
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (breakpointLines.contains(lineNum)) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFF5F5F))
                                    )
                                } else {
                                    Text(
                                        text = (lineNum + 1).toString(),
                                        color = if (bookmarkedLines.contains(lineNum))
                                            Color(0xFF61AFEF) else colors.gutter,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.25f).sp,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Editor surface
            Box(
                modifier = (if (wordWrap) Modifier else Modifier.horizontalScroll(hScroll))
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        ghostText = null  // P15-D: dismiss ghost on any keystroke
                        var updatedValue = newValue
                        // 1. Auto-close brackets & quotes
                        if (newValue.text.length == value.text.length + 1) {
                            val cursor = newValue.selection.end
                            if (cursor > 0 && cursor <= newValue.text.length) {
                                val insertedChar = newValue.text[cursor - 1]
                                val closer = when (insertedChar) {
                                    '(' -> ')'
                                    '[' -> ']'
                                    '{' -> '}'
                                    '"' -> '"'
                                    '\'' -> '\''
                                    else -> null
                                }
                                if (closer != null) {
                                    val leftText = newValue.text.substring(0, cursor)
                                    val rightText = newValue.text.substring(cursor)
                                    updatedValue = TextFieldValue(
                                        text = leftText + closer + rightText,
                                        selection = androidx.compose.ui.text.TextRange(cursor)
                                    )
                                }
                            }
                        }
                        
                        // Multi-cursor fan-out: replay same edit at each extra cursor
                        if (extraCursors.isNotEmpty()) {
                            val delta = updatedValue.text.length - value.text.length
                            if (delta != 0) {
                                val primaryAt = value.selection.start
                                val inserted = if (delta > 0) updatedValue.text.substring(primaryAt, primaryAt + delta) else ""
                                val deletedLen = if (delta < 0) -delta else 0
                                var composed = updatedValue.text
                                var shift = 0
                                val newExtras = mutableListOf<Int>()
                                for (ec in extraCursors.sorted()) {
                                    val pos = (ec + shift).coerceIn(0, composed.length)
                                    if (delta > 0) {
                                        composed = composed.substring(0, pos) + inserted + composed.substring(pos)
                                        shift += inserted.length
                                        newExtras.add(pos + inserted.length)
                                    } else {
                                        val from = (pos - deletedLen).coerceAtLeast(0)
                                        val to = pos.coerceAtMost(composed.length)
                                        if (from < to) {
                                            composed = composed.substring(0, from) + composed.substring(to)
                                            shift -= (to - from)
                                            newExtras.add(from)
                                        } else newExtras.add(from)
                                    }
                                }
                                extraCursors = newExtras
                                updatedValue = updatedValue.copy(text = composed)
                            }
                        }

                        value = updatedValue
                        onContentChange(updatedValue.text)
                        // P22-G: Report cursor position for LSP hover
                        val cOff = updatedValue.selection.end
                        val cLine = updatedValue.text.take(cOff).count { it == '\n' }
                        val cLineStart = updatedValue.text.lastIndexOf('\n', (cOff - 1).coerceAtLeast(0)) + 1
                        val cCol = cOff - cLineStart
                        onCursorChange?.invoke(cLine, cCol)
                    },
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            color = colors.text,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.25f).sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    ),
                    visualTransformation = SyntaxTransformation(language, colors, lintErrors, foldedLineIndices),
                    onTextLayout = { result -> textLayoutResult = result },
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .focusRequester(focusRequester)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    textLayoutResult?.let { layout ->
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        extraCursors = if (charOffset in extraCursors)
                                            extraCursors.filter { it != charOffset }
                                        else
                                            (extraCursors + charOffset).distinct().sorted()
                                    }
                                },
                                onLongPress = { offset ->
                                    // P38-FIX: Long-press selects the word and opens LSP menu
                                    textLayoutResult?.let { layout ->
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        // Find word boundaries at the long-pressed position
                                        val text = value.text
                                        var wordStart = charOffset
                                        var wordEnd = charOffset
                                        while (wordStart > 0 && (text[wordStart - 1].isLetterOrDigit() || text[wordStart - 1] == '_')) wordStart--
                                        while (wordEnd < text.length && (text[wordEnd].isLetterOrDigit() || text[wordEnd] == '_')) wordEnd++
                                        // Select the word (VS Code behavior)
                                        value = value.copy(selection = TextRange(wordStart, wordEnd))
                                        // Trigger the floating LSP menu to auto-open
                                        longPressTrigger++
                                    }
                                }
                            )
                        },
                )

            }
        }

        // ── P2-11 Inlay hint overlay ───────────────────────────────────
        if (showInlayHints && inlayHints.isNotEmpty()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val lineHeightDp = with(density) { fontSize.sp.toDp() }
            val gutterWidthDp = if (blameData != null) 72.dp + 120.dp else 72.dp
            inlayHints.forEach { hint ->
                val displayIdx = displayLines.indexOfFirst { it.first == hint.line }
                if (displayIdx < 0) return@forEach
                val yOffset = lineHeightDp * displayIdx
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

        // ── P20-A: Git Blame column overlay ──────────────────────────────────
        if (blameData != null && blameData.isNotEmpty()) {
            Box(
                Modifier
                    .padding(start = 72.dp)
                    .width(120.dp)
                    .fillMaxHeight()
                    .background(colors.gutter.copy(alpha = 0.3f))
            ) {
                Column(Modifier.verticalScroll(vScroll)) {
                    blameData.entries.sortedBy { it.key }.forEach { (_, blame) ->
                        Box(
                            Modifier.height(fontSize.dp * 1.25f).fillMaxWidth().padding(start = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                blame.author.take(12),
                                fontSize = 9.sp,
                                color = colors.gutter.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // ── P22-D: Merge conflict overlay ──────────────────────────────────
        if (conflictData != null && conflictData.isNotEmpty()) {
            val lineHeight = fontSize * 1.25f
            conflictData.forEach { hunk ->
                val oursHeight = (lineHeight * (hunk.separatorLine - hunk.startLine)).dp
                val theirsHeight = (lineHeight * (hunk.endLine - hunk.separatorLine)).dp
                val oursY = (lineHeight * (hunk.startLine + 1) - lineHeight).dp
                val theirsY = (lineHeight * (hunk.separatorLine + 1)).dp
                val barY = (lineHeight * hunk.startLine).dp

                // Ours section background (red tint)
                Box(
                    Modifier
                        .padding(start = 72.dp)
                        .fillMaxWidth()
                        .height(oursHeight)
                        .offset(y = oursY)
                        .background(Color(0x33FF6B6B))
                )
                // Theirs section background (green tint)
                Box(
                    Modifier
                        .padding(start = 72.dp)
                        .fillMaxWidth()
                        .height(theirsHeight)
                        .offset(y = theirsY)
                        .background(Color(0x334EC9B0))
                )
                // Resolve button bar at conflict start
                Box(
                    Modifier
                        .padding(start = 72.dp)
                        .offset(y = barY)
                        .zIndex(10f)
                ) {
                    Row(
                        Modifier.background(Color(0xFF1A1A2E)).padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("<<<", color = Color(0xFFE06C75), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(4.dp))
                        Text(hunk.oursBranch.take(10), color = Color(0xFFE06C75), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text("vs", color = Color(0xFF858585), fontSize = 8.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(hunk.theirsBranch.take(10), color = Color(0xFF4EC9B0), fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        // Ours button
                        Box(
                            Modifier.background(Color(0x66FF6B6B), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.OURS) }.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("Ours", color = Color.White, fontSize = 8.sp)
                        }
                        Spacer(Modifier.width(3.dp))
                        // Theirs button
                        Box(
                            Modifier.background(Color(0x664EC9B0), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.THEIRS) }.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("Theirs", color = Color.White, fontSize = 8.sp)
                        }
                        Spacer(Modifier.width(3.dp))
                        // Both button
                        Box(
                            Modifier.background(Color(0x66569CD6), RoundedCornerShape(3.dp)).clickable { onResolveConflict?.invoke(hunk, ConflictResolution.BOTH) }.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("Both", color = Color.White, fontSize = 8.sp)
                        }
                    }
                }
            }
        }

        // ── P2-3 Extra-cursor visual indicators ──────────────────────────────
        // BasicTextField exposes no TextLayoutResult here, so we approximate
        // positions using the same line-height formula as every other overlay
        // in this file: y = lineIdx * fontSize * 1.25f dp, x = 64dp + col * fontSize * 0.6f dp.
        if (extraCursors.isNotEmpty()) {
            val lineHeightPx = fontSize * 1.25f
            val charWidthPx  = fontSize * 0.6f
            val gutterDp     = 74f
            // BUG-3 FIX: subtract scroll offset
            val scrollOffsetPx = vScroll.value
            extraCursors.forEach { off ->
                val clamped  = off.coerceIn(0, value.text.length)
                val lineIdx  = value.text.take(clamped).count { it == '\n' }
                val lineStart = (value.text.lastIndexOf('\n', (clamped - 1).coerceAtLeast(0)) + 1)
                                    .coerceAtLeast(0)
                val col      = (clamped - lineStart).coerceAtLeast(0)
                val topDp    = lineIdx * lineHeightPx - scrollOffsetPx
                val startDp  = gutterDp + col * charWidthPx

                // 1. Subtle full-line background tint — gives line context at a glance
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = gutterDp.dp, top = topDp.dp)
                        .height(lineHeightPx.dp)
                        .background(Color(0xFFE5C07B).copy(alpha = 0.08f))
                        .zIndex(4f),
                )

                // 2. Thin amber cursor bar at the exact column position
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = startDp.dp, top = topDp.dp)
                        .width(2.dp)
                        .height(lineHeightPx.dp)
                        .background(Color(0xFFE5C07B))   // amber — distinct from primary cursor
                        .zIndex(5f),
                )
            }
        }

        // PROBLEMS-TAB FIX: Gold highlight on the problem target line (fades after 2.5s)
        if (highlightTargetLine > 0) {
            val lineHeightPxHl = fontSize * 1.25f
            val gutterDpHl = 74f
            val scrollOffsetPxHl = vScroll.value
            val topDpHl = (highlightTargetLine - 1) * lineHeightPxHl - scrollOffsetPxHl
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = gutterDpHl.dp, top = topDpHl.dp)
                    .height(lineHeightPxHl.dp)
                    .background(Color(0xFFFFD700).copy(alpha = 0.15f))
                    .zIndex(3.5f),
            )
            // Thin gold bar on the left edge of the highlighted line
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = gutterDpHl.dp, top = topDpHl.dp)
                    .width(3.dp)
                    .height(lineHeightPxHl.dp)
                    .background(Color(0xFFFFD700))
                    .zIndex(4.5f),
            )
        }

        // P26-1: LSP Document Highlight — subtle background tint on all occurrences
        if (lspHighlightLines.isNotEmpty()) {
            val lineHeightPxHighlight = fontSize * 1.25f
            val gutterDpHighlight = 74f
            // BUG-3 FIX: subtract scroll offset so highlights track the correct lines on scroll
            val scrollOffsetPx = vScroll.value
            lspHighlightLines.forEach { (startLine, endLine) ->
                val topDp = startLine * lineHeightPxHighlight - scrollOffsetPx
                val heightDp = (endLine - startLine + 1) * lineHeightPxHighlight
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

        // P26-1: LSP Code Lens — inline annotations at end of lines (e.g. "3 references")
        if (lspCodeLenses != null && lspCodeLenses!!.length() > 0) {
            val lineHeightPxCL = fontSize * 1.25f
            val gutterDpCL = 74f
            for (i in 0 until lspCodeLenses!!.length()) {
                val lens = lspCodeLenses!!.optJSONObject(i) ?: continue
                val range = lens.optJSONObject("range") ?: continue
                val startLine = range.optJSONObject("start")?.optInt("line", -1) ?: -1
                if (startLine < 0) continue
                val command = lens.optJSONObject("command")
                val title = command?.optString("title", "") ?: lens.optString("title", "")
                if (title.isBlank()) continue
                // BUG-3 FIX: subtract scroll offset
                val topDpCL = startLine * lineHeightPxCL - vScroll.value
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
                            .background(Color(0xFF1E1E1E).copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        // P26-1: LSP Inlay Hints — inline type/parameter hints within code
        if (lspInlayHints != null && lspInlayHints!!.length() > 0) {
            val lineHeightPxIH = fontSize * 1.25f
            val gutterDpIH = 74f
            val charWidthPx = fontSize * 0.6f
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
                val topDpIH = line * lineHeightPxIH
                val leftDpIH = gutterDpIH + character * charWidthPx
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
        if (lspDocumentLinks != null && lspDocumentLinks!!.length() > 0) {
            val lineHeightPxDL = fontSize * 1.25f
            val gutterDpDL = 74f
            val charWidthPxDL = fontSize * 0.6f
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
                val topDpDL = startLine * lineHeightPxDL
                val leftDpDL = gutterDpDL + startChar * charWidthPxDL
                val widthDp = (endChar - startChar) * charWidthPxDL
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
                    .padding(end = 74.dp, top = 4.dp)
                    .background(Color(0xFF007ACC), RoundedCornerShape(3.dp))
                    .clickable { extraCursors = emptyList() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .zIndex(10f),
            ) {
                Text("${extraCursors.size}× cursors ✕", color = Color(0xFFFFFFFF), fontSize = 10.sp)
            }
        }

        // ── Minimap toggle button + realistic minimap ──────────────────────────
        val textLines = remember(value.text) { value.text.split("\n") }
        val lineCountTotal = textLines.size

        // Scale: each minimap line = 2.dp when file is small, shrinks for large files
        val minimapLineHeightDp = when {
            lineCountTotal <= 300  -> 2.dp
            lineCountTotal <= 600  -> (600 / lineCountTotal).coerceAtLeast(1).dp
            lineCountTotal <= 2000 -> 1.dp
            else                   -> 0.5.dp
        }
        // Pre-compute px values for use inside pointerInput lambdas (non-composable)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val miniLineHeightPx = with(density) { minimapLineHeightDp.toPx() }
        val miniWidthPx = with(density) { 60.dp.toPx() }
        // Viewport position: where in the minimap the current view is
        val lineHeightPx = fontSize * 1.25f
        val viewportTopLine = (vScroll.value / lineHeightPx).toInt()
        val visibleLineCount = with(density) {
            ((maxOf(1, vScroll.viewportSize) / lineHeightPx).toInt())
        }

        // Toggle button — small icon at top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(6f)
                .padding(top = 2.dp, end = if (showMinimapState) 64.dp else 2.dp)
                .background(colors.background.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                .border(1.dp, colors.gutter.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .clickable { showMinimapState = !showMinimapState }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (showMinimapState) "▣" else "▢",
                color = colors.gutter,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Minimap panel — realistic with syntax colors + viewport indicator
        if (showMinimapState) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(60.dp)
                    .fillMaxHeight()
                    .background(colors.background.copy(alpha = 0.5f))
                    .zIndex(5f)
                    .pointerInput(lineCountTotal) {
                        detectTapGestures(
                            onTap = { offset ->
                                val clickedLine = (offset.y / miniLineHeightPx).toInt()
                                    .coerceIn(0, (lineCountTotal - 1).coerceAtLeast(0))
                                coroutineScope.launch {
                                    vScroll.animateScrollTo((clickedLine * lineHeightPx).toInt())
                                }
                            }
                        )
                    },
            ) {
                // Sync minimap scroll with editor scroll
                val miniScroll = rememberScrollState()
                LaunchedEffect(vScroll.value) {
                    val target = (viewportTopLine * miniLineHeightPx - miniWidthPx / 2)
                        .coerceAtLeast(0f).toInt()
                    miniScroll.scrollTo(target)
                }
                // Minimap lines with syntax-aware coloring
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(miniScroll)
                ) {

                    textLines.forEachIndexed { _, line ->
                        // Classify line for syntax color
                        val trimmed = line.trimStart()
                        val lineColor = when {
                            trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> colors.comment
                            trimmed.startsWith("\"") || trimmed.startsWith("'") -> colors.string
                            trimmed.startsWith("import ") || trimmed.startsWith("from ") || trimmed.startsWith("package ") -> colors.keyword
                            trimmed.startsWith("fun ") || trimmed.startsWith("def ") || trimmed.startsWith("func ") || trimmed.startsWith("void ") -> colors.function
                            trimmed.startsWith("class ") || trimmed.startsWith("interface ") || trimmed.startsWith("struct ") || trimmed.startsWith("enum ") -> colors.type
                            trimmed.isNotEmpty() && trimmed[0].isUpperCase() && !trimmed.contains("(") -> colors.type
                            trimmed.isEmpty() -> Color.Transparent
                            else -> colors.text
                        }
                        // Line density: how much of the line width is filled
                        val localDensity = (line.trimEnd().length.coerceAtMost(80)).toFloat() / 80f
                        val indent = line.length - line.trimStart().length
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(minimapLineHeightDp)
                                .padding(horizontal = 1.dp),
                        ) {
                            // Indent spacer
                            if (indent > 0) {
                                Spacer(Modifier.width((indent * 0.25f).coerceAtMost(20f).dp))
                            }
                            // Colored bar representing the code line
                            if (localDensity > 0f && lineColor != Color.Transparent) {
                                Box(
                                    Modifier
                                        .weight(localDensity.coerceIn(0.03f, 1f))
                                        .fillMaxHeight()
                                        .background(lineColor.copy(alpha = 0.5f))
                                )
                            }
                            if (localDensity < 1f) {
                                Spacer(Modifier.weight((1f - localDensity).coerceAtLeast(0f)))
                            }
                        }
                    }
                }

                // Viewport indicator rectangle — shows current scroll position
                val viewportTopPx = (viewportTopLine * miniLineHeightPx)
                val viewportHeightPx = (visibleLineCount * miniLineHeightPx).coerceAtLeast(20f)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(0, viewportTopPx.toInt()) }
                        .fillMaxWidth()
                        .height(with(density) { viewportHeightPx.toDp() })
                        .background(colors.currentLine.copy(alpha = 0.15f))
                        .border(1.dp, colors.gutter.copy(alpha = 0.3f))
                        .zIndex(4f)
                        .pointerInput(lineCountTotal) {
                            detectDragGestures(
                                onDragStart = { },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        val deltaLines = (dragAmount.y / miniLineHeightPx).toInt()
                                        val newScroll = (vScroll.value + (deltaLines * lineHeightPx).toInt())
                                            .coerceIn(0, vScroll.maxValue)
                                        vScroll.scrollTo(newScroll)
                                    }
                                }
                            )
                        },
                )
            }
        }

        // Indentation guides
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 72.dp)
                .zIndex(1f),
        ) {
            val maxIndent = remember(value.text) {
                value.text.split("\n").maxOfOrNull { line ->
                    (line.length - line.trimStart().length) / 2
                } ?: 0
            }
            for (indent in 1..minOf(maxIndent, 10)) {
                Box(Modifier.width(2.dp).fillMaxHeight().padding(end = 10.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.gutter.copy(alpha = 0.15f)))
                Spacer(Modifier.width(11.dp))
            }
        }

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
            if (selWord != null && selWord.length >= 2) {
                Popup(
                    alignment = androidx.compose.ui.Alignment.TopEnd,
                    offset = androidx.compose.ui.unit.IntOffset(0, 0),
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
                ) {
                    var showLspMenu by remember { mutableStateOf(false) }
                    // P38-FIX: Auto-open LSP menu when long-press triggers
                    LaunchedEffect(longPressTrigger) {
                        if (longPressTrigger > 0) showLspMenu = true
                    }
                    androidx.compose.material3.Surface(
                        color = Color(0xFF2D2D30),
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
                            modifier = Modifier.background(Color(0xFF252526))
                        ) {
                            // ── LSP Actions (compact scrollable dropdown) ──
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                            val selectedText = value.text.substring(
                                value.selection.start.coerceIn(0, value.text.length),
                                value.selection.end.coerceIn(0, value.text.length)
                            )
                            val word = selWord

                            // Fix with AI (if there's a nearby error)
                            val nearbyError = lintErrors.firstOrNull { err ->
                                val errLine = value.text.take(err.start).count { it == '\n' }
                                val selLine = value.text.take(value.selection.start).count { it == '\n' }
                                kotlin.math.abs(errLine - selLine) <= 2
                            }
                            if (nearbyError != null) {
                                val errLine = value.text.take(nearbyError.start).count { it == '\n' } + 1
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
                                        val cursorLine = value.text.take(value.selection.start).count { it == '\n' }
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
                                                        val cursorLine = value.text.take(value.selection.start).count { it == '\n' }
                                                        val lineStart = value.text.lastIndexOf('\n', value.selection.start - 1) + 1
                                                        val lineEnd = value.text.indexOf('\n', value.selection.start)
                                                        val lineText = value.text.substring(lineStart, if (lineEnd < 0) value.text.length else lineEnd)
                                                        val prompt = when (fix.kind) {
                                                            com.codespace.ide.lsp.CodeActionKind.AIExplain -> "Explain this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIGenerateDoc -> "Generate documentation for this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIGenerateTests -> "Generate unit tests for this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIOptimize -> "Optimize this code for better performance:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIRewrite -> "Rewrite this code for better clarity:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AISimplify -> "Simplify this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIAddComments -> "Add inline comments to this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIExplainError -> "Explain the error in this code:\n" + lineText
                                                            com.codespace.ide.lsp.CodeActionKind.AIImprovePerf -> "Suggest performance improvements for:\n" + lineText
                                                            else -> fix.title + ":\n" + lineText
                                                        }
                                                        onAiFixRequest!!.invoke(prompt)
                                                    } else if (fix.edit != null) {
                                                        try {
                                                            val newText = com.codespace.ide.lsp.applyWorkspaceEdit(
                                                                fix.edit, value.text, null
                                                            )
                                                            if (newText != null && newText != value.text) {
                                                                value = TextFieldValue(newText, TextRange(value.selection.start))
                                                                onContentChange(newText)
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
                                        Text("Expand Selection", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        if (onLspSelectionRange != null && expandSelectionDepth >= 0) {
                                            Text("L${expandSelectionDepth + 1}", color = Color(0xFF4EC9B0), fontSize = 10.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    if (onLspSelectionRange != null) {
                                        try {
                                            val cLine = value.text.take(value.selection.start).count { it == '\n' }
                                            val cCol = value.selection.start - value.text.lastIndexOf('\n', (value.selection.start - 1).coerceAtLeast(0)) - 1
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
                                                    val textLines = value.text.split("\n")
                                                    var startOff = (0 until sLine).sumOf { textLines[it].length + 1 } + sChar
                                                    var endOff = (0 until eLine).sumOf { textLines[it].length + 1 } + eChar
                                                    startOff = startOff.coerceIn(0, value.text.length)
                                                    endOff = endOff.coerceIn(0, value.text.length)
                                                    value = value.copy(selection = TextRange(startOff, endOff))
                                                }
                                            }
                                        } catch (_: Exception) { expandSelectionUsedLsp = false }
                                    } else {
                                        val pat = Regex("\\b" + Regex.escape(word) + "\\b")
                                        val match = pat.find(value.text, value.selection.start - 1) ?: pat.find(value.text)
                                        if (match != null) {
                                            value = value.copy(selection = TextRange(match.range.first, match.range.last + 1))
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
                                        Text("⇒", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Go to Definition", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    // BUG-4 FIX: Try LSP definition first (real semantic navigation),
                                    // fall back to regex pattern matching only if LSP fails/unavailable.
                                    var lspDefSucceeded = false
                                    if (onLspDefinition != null) {
                                        lspDefSucceeded = onLspDefinition!!.invoke()
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

                            // Peek Definition
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("👁", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Peek Definition", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    // BUG-4 FIX: Try LSP definition first for peek, fall back to regex
                                    var lspPeekSucceeded = false
                                    if (onLspDefinition != null) {
                                        lspPeekSucceeded = onLspDefinition!!.invoke()
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
                                            filePath = "(current)",
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
                                            Text("T", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                            Text("Go to Type Definition", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        typeDefUsedLsp = onLspTypeDefinition.invoke()
                                        showLspMenu = false
                                    }
                                )
                            }

                            // Find Implementations
                            if (onLspImplementation != null) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("I", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                            Text("Find Implementations", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        implUsedLsp = onLspImplementation.invoke()
                                        showLspMenu = false
                                    }
                                )
                            }

                            // Rename Symbol
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("✎", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Rename Symbol", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    if (onLspPrepareRename != null) {
                                        val pos = value.selection.start
                                        val cLine = value.text.take(pos).count { it == '\n' }
                                        val cLineStart = value.text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)) + 1
                                        val cCol = pos - cLineStart
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

                            // Select All Occurrences
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("■", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Select All Occurrences", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    val pat = Regex("\\b" + Regex.escape(word) + "\\b")
                                    val matches = pat.findAll(value.text).toList()
                                    if (matches.isNotEmpty()) {
                                        val first = matches.first().range.first
                                        val last = matches.last().range.last + 1
                                        value = value.copy(selection = TextRange(first, last))
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Select Next Occurrence
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("▸", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Select Next Occurrence", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    val pat = Regex("\\b" + Regex.escape(word) + "\\b")
                                    val currentPos = value.selection.end
                                    val nextMatch = pat.find(value.text, currentPos) ?: pat.find(value.text)
                                    if (nextMatch != null) {
                                        value = value.copy(selection = TextRange(nextMatch.range.first, nextMatch.range.last + 1))
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Find References
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("⊛", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Find References", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    if (onFindReferences != null) {
                                        findRefWord = word
                                        findRefLoading = true
                                        findRefResults = emptyList()
                                        findRefUsedLsp = false
                                        val refs = try { onFindReferences.invoke(word) } catch (_: Exception) { emptyList<Triple<String, Int, String>>() }
                                        findRefResults = refs
                                        findRefLoading = false
                                        findRefUsedLsp = onFindReferences != null && refs.isNotEmpty()
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Add Cursor Above
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("↑", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Add Cursor Above", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    val currentLine = value.text.take(value.selection.start).count { it == '\n' }
                                    if (currentLine > 0) {
                                        val prevLineStart = value.text.lastIndexOf('\n', value.text.lastIndexOf('\n', value.selection.start - 1) - 1) + 1
                                        extraCursors = (extraCursors + prevLineStart).distinct().sorted()
                                    }
                                    showLspMenu = false
                                }
                            )

                            // Add Cursor Below
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("↓", color = Color(0xFFD4D4D4), fontSize = 14.sp)
                                        Text("Add Cursor Below", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    val nextNewline = value.text.indexOf('\n', value.selection.end)
                                    if (nextNewline >= 0) {
                                        extraCursors = (extraCursors + nextNewline + 1).distinct().sorted()
                                    }
                                    showLspMenu = false
                                }
                            )
                            } // end scrollable Column
                        }
                    }
                }
            }
        }

        // ── P2-4 Go to Definition Results ──────────────────────────────────────────────────────
        if (gotoResults != null) {
            val results = gotoResults!!
            AlertDialog(
                onDismissRequest = { gotoResults = null },
                containerColor = Color(0xFF252526),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (results.isEmpty() && (crossFileResults == null || crossFileResults!!.isEmpty())) "Not found" else "Go to Definition",
                            color = Color(0xFFD4D4D4),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (!(results.isEmpty() && (crossFileResults == null || crossFileResults!!.isEmpty()))) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    .background(Color(0xFFCC7832))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("Fallback", color = Color(0xFF1E1E1E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                text = {
                    if (results.isEmpty()) {
                        Text(
                            "No declaration found in current file or project.",
                            color = Color(0xFF888888),
                            fontSize = 12.sp,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            results.forEach { def ->
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val localLineHeightPx = fontSize * 1.25f
                                            vScroll.animateScrollTo((def.line * localLineHeightPx).toInt())
                                        }
                                        gotoResults = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "Line ${def.line + 1}",
                                            color = Color(0xFF007ACC),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Text(
                                            def.lineText.take(60),
                                            color = Color(0xFFD4D4D4),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
;                            }
                            // P19-A: Cross-file results
                            if (crossFileResults != null && crossFileResults!!.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("In project", color = Color(0xFF888888), fontSize = 10.sp)
                                crossFileResults!!.forEach { cf ->
                                    TextButton(
                                        onClick = {
                                            onOpenFileAtLine?.invoke(cf.filePath, cf.line)
                                            gotoResults = null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(cf.kind, color = Color(0xFF569CD6), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
                                                Text(cf.name, color = Color(0xFFD4D4D4), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                            }
                                            Text("${cf.fileName}:${cf.line}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { gotoResults = null }) {
                        Text("Close", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                },
            )
        }

        // ── P22-L: Peek Definition overlay ───────────────────────────────────────
        if (peekDefResult != null) {
            val peek = peekDefResult!!
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.5f)
                    .zIndex(30f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF252526))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Peek Definition",
                            color = Color(0xFF4EC9B0),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        // P37-3: LSP/Fallback badge
                        Box(
                            Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                .background(if (peekUsedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (peekUsedLsp) "LSP" else "Fallback",
                                color = Color(0xFF1E1E1E),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val fileName = peek.filePath.substringAfterLast('/')
                        Text(
                            "$fileName:${peek.line + 1}",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { peekDefResult = null },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                        ) {
                            Text("X", color = Color(0xFF888888), fontSize = 16.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF333333))
                    // Code preview
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        peek.lines.forEachIndexed { idx, line ->
                            val isDefLine = idx == peek.defLine
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isDefLine) Color(0xFF007ACC).copy(alpha = 0.15f) else Color.Transparent)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    "" + (peek.line - peek.defLine + idx + 1),
                                    color = if (isDefLine) Color(0xFF007ACC) else Color(0xFF858585),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(36.dp),
                                )
                                Text(
                                    line.take(120),
                                    color = if (isDefLine) Color(0xFFD4D4D4) else Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    // Footer
                    HorizontalDivider(color = Color(0xFF333333))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { peekDefResult = null }) {
                            Text("Close", color = Color(0xFF888888), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                if (peek.filePath == filePath) {
                                    coroutineScope.launch {
                                        val localLineHeightPx = fontSize * 1.25f
                                        vScroll.animateScrollTo((peek.line * localLineHeightPx).toInt())
                                    }
                                } else {
                                    onOpenFileAtLine?.invoke(peek.filePath, peek.line + 1)
                                }
                                peekDefResult = null
                            }
                        ) {
                            Text("Go to Definition ->", color = Color(0xFF007ACC), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (renameDialogWord != null) {
            val wordToRename = renameDialogWord!!
            AlertDialog(
                onDismissRequest = { renameDialogWord = null },
                containerColor = Color(0xFF252526),
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
                                    color = Color(0xFF1E1E1E),
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
                                unfocusedBorderColor = Color(0xFF3C3C3C),
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
                                        val cLine = value.text.take(cOff).count { it == '\n' }
                                        val cLineStart = value.text.lastIndexOf('\n', (cOff - 1).coerceAtLeast(0)) + 1
                                        val cCol = cOff - cLineStart
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
                                        val cLine = value.text.take(cOff).count { it == '\n' }
                                        val cLineStart = value.text.lastIndexOf('\n', (cOff - 1).coerceAtLeast(0)) + 1
                                        val cCol = cOff - cLineStart
                                        // Try prepareRename first (some servers require it)
                                        val prep = try { LspManager.prepareRename(language, uri, cLine, cCol) } catch (_: Exception) { null }
                                        if (prep != null) {
                                            // Server confirmed this position is renameable
                                            val wsEdit = try { LspManager.rename(language, uri, cLine, cCol, newName) } catch (_: Exception) { null }
                                            if (wsEdit != null) {
                                                // Apply the workspace edit to current file
                                                val docChanges = wsEdit.optJSONArray("documentChanges")
                                                val changes = wsEdit.optJSONObject("changes")
                                                var newText = value.text
                                                var appliedAny = false
                                                if (docChanges != null) {
                                                    for (j in 0 until docChanges.length()) {
                                                        val dc = docChanges.optJSONObject(j) ?: continue
                                                        val editUri = dc.optString("uri", "")
                                                        // Only apply edits to current file inline; others written to disk
                                                        val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                        val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                        if (decodedPath == filePath) {
                                                            val textEdits = dc.optJSONArray("edits") ?: continue
                                                            val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                                                .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                                            val newTextLines = newText.split("\n").toMutableList()
                                                            for (te in edits) {
                                                                val rng = te.optJSONObject("range") ?: continue
                                                                val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                                                val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                                                val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                                                val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                                                val replacement = te.optString("newText", "")
                                                                if (sl == el && sl < newTextLines.size) {
                                                                    val line = newTextLines[sl]
                                                                    newTextLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                                                } else if (sl < newTextLines.size) {
                                                                    val before = newTextLines[sl].substring(0, sc.coerceAtMost(newTextLines[sl].length))
                                                                    val after = if (el < newTextLines.size) newTextLines[el].substring(ec.coerceAtMost(newTextLines[el].length)) else ""
                                                                    newTextLines[sl] = before + replacement + after
                                                                    if (sl + 1 <= el && el < newTextLines.size) {
                                                                        for (k in el downTo sl + 1) {
                                                                            if (k < newTextLines.size) newTextLines.removeAt(k)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            newText = newTextLines.joinToString("\n")
                                                            appliedAny = true
                                                        } else {
                                                            // Write edits to other files on disk
                                                            val textEdits = dc.optJSONArray("edits") ?: continue
                                                            try {
                                                                val targetText = java.io.File(decodedPath).readText()
                                                                val targetLines = targetText.split("\n").toMutableList()
                                                                val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                                                    .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                                                for (te in edits) {
                                                                    val rng = te.optJSONObject("range") ?: continue
                                                                    val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                                                    val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                                                    val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                                                    val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                                                    val replacement = te.optString("newText", "")
                                                                    if (sl == el && sl < targetLines.size) {
                                                                        val line = targetLines[sl]
                                                                        targetLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                                                    } else if (sl < targetLines.size) {
                                                                        val before = targetLines[sl].substring(0, sc.coerceAtMost(targetLines[sl].length))
                                                                        val after = if (el < targetLines.size) targetLines[el].substring(ec.coerceAtMost(targetLines[el].length)) else ""
                                                                        targetLines[sl] = before + replacement + after
                                                                        if (sl + 1 <= el && el < targetLines.size) {
                                                                            for (k in el downTo sl + 1) {
                                                                                if (k < targetLines.size) targetLines.removeAt(k)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                java.io.File(decodedPath).writeText(targetLines.joinToString("\n"))
                                                            } catch (_: Exception) {}
                                                        }
                                                    }
                                                } else if (changes != null) {
                                                    // Legacy "changes" format (not documentChanges)
                                                    val keys = changes.keys()
                                                    while (keys.hasNext()) {
                                                        val editUri = keys.next()
                                                        val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                        val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                        val textEdits = changes.optJSONArray(editUri) ?: continue
                                                        if (decodedPath == filePath) {
                                                            val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                                                .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                                            val newTextLines = newText.split("\n").toMutableList()
                                                            for (te in edits) {
                                                                val rng = te.optJSONObject("range") ?: continue
                                                                val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                                                val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                                                val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                                                val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                                                val replacement = te.optString("newText", "")
                                                                if (sl == el && sl < newTextLines.size) {
                                                                    val line = newTextLines[sl]
                                                                    newTextLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                                                } else if (sl < newTextLines.size) {
                                                                    val before = newTextLines[sl].substring(0, sc.coerceAtMost(newTextLines[sl].length))
                                                                    val after = if (el < newTextLines.size) newTextLines[el].substring(ec.coerceAtMost(newTextLines[el].length)) else ""
                                                                    newTextLines[sl] = before + replacement + after
                                                                    if (sl + 1 <= el && el < newTextLines.size) {
                                                                        for (k in el downTo sl + 1) {
                                                                            if (k < newTextLines.size) newTextLines.removeAt(k)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            newText = newTextLines.joinToString("\n")
                                                            appliedAny = true
                                                        }
                                                    }
                                                }
                                                if (appliedAny) {
                                                    value = TextFieldValue(
                                                        text = newText,
                                                        selection = value.selection,
                                                    )
                                                    onContentChange(newText)
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
                                    value = TextFieldValue(
                                        text = newText,
                                        selection = value.selection,
                                    )
                                    onContentChange(newText)
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
                        Text("Rename", color = Color(0xFFFFFFFF), fontSize = 12.sp)
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
                containerColor = Color(0xFF252526),
                title = { Text("Rename Preview", color = Color(0xFFD4D4D4), fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${renamePreviewFiles.size} file${if (renamePreviewFiles.size != 1) "s" else ""} affected",
                            color = Color(0xFF4EC9B0), fontSize = 12.sp)
                        HorizontalDivider(color = Color(0xFF3C3C3C), modifier = Modifier.padding(vertical = 4.dp))
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
                        var newText = value.text
                        var appliedAny = false
                        val docChanges = wsEdit.optJSONArray("documentChanges")
                        val changes = wsEdit.optJSONObject("changes")
                        if (docChanges != null) {
                            for (j in 0 until docChanges.length()) {
                                val dc = docChanges.optJSONObject(j) ?: continue
                                val editUri = dc.optString("uri", "")
                                val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                if (decodedPath == filePath) {
                                    val textEdits = dc.optJSONArray("edits") ?: continue
                                    val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                        .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                    val newTextLines = newText.split("\n").toMutableList()
                                    for (te in edits) {
                                        val rng = te.optJSONObject("range") ?: continue
                                        val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                        val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                        val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                        val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                        val replacement = te.optString("newText", "")
                                        if (sl == el && sl < newTextLines.size) {
                                            val line = newTextLines[sl]
                                            newTextLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                        } else if (sl < newTextLines.size) {
                                            val before = newTextLines[sl].substring(0, sc.coerceAtMost(newTextLines[sl].length))
                                            val after = if (el < newTextLines.size) newTextLines[el].substring(ec.coerceAtMost(newTextLines[el].length)) else ""
                                            newTextLines[sl] = before + replacement + after
                                            if (sl + 1 <= el && el < newTextLines.size) { for (k in el downTo sl + 1) { if (k < newTextLines.size) newTextLines.removeAt(k) } }
                                        }
                                    }
                                    newText = newTextLines.joinToString("\n")
                                    appliedAny = true
                                } else {
                                    val textEdits = dc.optJSONArray("edits") ?: continue
                                    try {
                                        val targetText = java.io.File(decodedPath).readText()
                                        val targetLines = targetText.split("\n").toMutableList()
                                        val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                            .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                        for (te in edits) {
                                            val rng = te.optJSONObject("range") ?: continue
                                            val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                            val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                            val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                            val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                            val replacement = te.optString("newText", "")
                                            if (sl == el && sl < targetLines.size) {
                                                val line = targetLines[sl]
                                                targetLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                            } else if (sl < targetLines.size) {
                                                val before = targetLines[sl].substring(0, sc.coerceAtMost(targetLines[sl].length))
                                                val after = if (el < targetLines.size) targetLines[el].substring(ec.coerceAtMost(targetLines[el].length)) else ""
                                                targetLines[sl] = before + replacement + after
                                                if (sl + 1 <= el && el < targetLines.size) { for (k in el downTo sl + 1) { if (k < targetLines.size) targetLines.removeAt(k) } }
                                            }
                                        }
                                        java.io.File(decodedPath).writeText(targetLines.joinToString("\n"))
                                    } catch (_: Exception) {}
                                }
                            }
                        } else if (changes != null) {
                            val keys = changes.keys()
                            while (keys.hasNext()) {
                                val editUri = keys.next()
                                val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                val decodedPath = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                val textEdits = changes.optJSONArray(editUri) ?: continue
                                if (decodedPath == filePath) {
                                    val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                        .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                    val newTextLines = newText.split("\n").toMutableList()
                                    for (te in edits) {
                                        val rng = te.optJSONObject("range") ?: continue
                                        val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                        val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                        val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                        val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                        val replacement = te.optString("newText", "")
                                        if (sl == el && sl < newTextLines.size) {
                                            val line = newTextLines[sl]
                                            newTextLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                        } else if (sl < newTextLines.size) {
                                            val before = newTextLines[sl].substring(0, sc.coerceAtMost(newTextLines[sl].length))
                                            val after = if (el < newTextLines.size) newTextLines[el].substring(ec.coerceAtMost(newTextLines[el].length)) else ""
                                            newTextLines[sl] = before + replacement + after
                                            if (sl + 1 <= el && el < newTextLines.size) { for (k in el downTo sl + 1) { if (k < newTextLines.size) newTextLines.removeAt(k) } }
                                        }
                                    }
                                    newText = newTextLines.joinToString("\n")
                                    appliedAny = true
                                } else {
                                    try {
                                        val targetText = java.io.File(decodedPath).readText()
                                        val targetLines = targetText.split("\n").toMutableList()
                                        val edits = (0 until textEdits.length()).map { textEdits.optJSONObject(it)!! }
                                            .sortedByDescending { it.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0 }
                                        for (te in edits) {
                                            val rng = te.optJSONObject("range") ?: continue
                                            val sl = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                                            val sc = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                                            val el = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                                            val ec = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                                            val replacement = te.optString("newText", "")
                                            if (sl == el && sl < targetLines.size) {
                                                val line = targetLines[sl]
                                                targetLines[sl] = line.substring(0, sc.coerceAtMost(line.length)) + replacement + line.substring(ec.coerceAtMost(line.length))
                                            } else if (sl < targetLines.size) {
                                                val before = targetLines[sl].substring(0, sc.coerceAtMost(targetLines[sl].length))
                                                val after = if (el < targetLines.size) targetLines[el].substring(ec.coerceAtMost(targetLines[el].length)) else ""
                                                targetLines[sl] = before + replacement + after
                                                if (sl + 1 <= el && el < targetLines.size) { for (k in el downTo sl + 1) { if (k < targetLines.size) targetLines.removeAt(k) } }
                                            }
                                        }
                                        java.io.File(decodedPath).writeText(targetLines.joinToString("\n"))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        if (appliedAny) {
                            value = TextFieldValue(newText, TextRange(value.selection.start))
                            onContentChange(newText)
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

        // ── P24-3: Find References Overlay ───────────────────────────────────────
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
                        Text(
                            "References: ${findRefWord}",
                            color = Color(0xFF9CDCFE), fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        // P37-3: LSP/Fallback badge
                        Box(
                            Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                .background(if (findRefUsedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (findRefUsedLsp) "LSP" else "Fallback",
                                color = Color(0xFF1E1E1E),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (findRefLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Color(0xFF007ACC))
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(onClick = { findRefWord = null; findRefResults = emptyList() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
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
                                        if (refPath == filePath) {
                                            coroutineScope.launch {
                                                val lineHeightPx = fontSize * 1.25f
                                                vScroll.animateScrollTo((refLine * lineHeightPx).toInt())
                                            }
                                        } else {
                                            onOpenFileAtLine?.invoke(refPath, refLine)
                                        }
                                        findRefWord = null
                                        findRefResults = emptyList()
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

        // ── Go to Line Bar ──────────────────────────────────────────────────
        if (goToLineOpen) {
            val lineCount2 = remember(value.text) { value.text.count { it == '\n' } + 1 }
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
                        if (v.all { it.isDigit() } || v.isEmpty()) goToLineInput = v
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
                            val target = goToLineInput.toIntOrNull() ?: return@KeyboardActions
                            val clamped = target.coerceIn(1, lineCount2)
                            val lines2 = value.text.split("\n")
                            val offset = lines2.take(clamped - 1).sumOf { it.length + 1 }
                            val safeOffset = offset.coerceAtMost(value.text.length)
                            value = value.copy(
                                selection = androidx.compose.ui.text.TextRange(safeOffset),
                            )
                            coroutineScope.launch {
                                val localLineHeightPx = fontSize * 2.0f
                                vScroll.animateScrollTo(((clamped - 1) * localLineHeightPx).toInt())
                            }
                            goToLineInput = ""
                            onGoToLineClose()
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
                IconButton(
                    onClick = { goToLineInput = ""; onGoToLineClose() },
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

        // ── Find & Replace Bar ───────────────────────────────────────────
        if (findReplaceOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xFF252526))
                    .border(1.dp, Color(0xFF3C3C3C))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .zIndex(20f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1 — Search
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val matchLabel = when {
                        findQuery.isEmpty() -> ""
                        matches.isEmpty() -> "No results"
                        else -> "${matchIndex + 1}/${matches.size}"
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it; matchIndex = 0 },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                if (findQuery.isEmpty()) Text(
                                    "Find",
                                    color = Color(0xFF666666),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                            .border(
                                1.dp,
                                if (findQuery.isNotEmpty() && matches.isEmpty()) Color(0xFFE51400)
                                else Color(0xFF3C3C3C),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                    Text(
                        matchLabel,
                        color = Color(0xFF888888),
                        fontSize = 10.sp,
                        modifier = Modifier.widthIn(min = 52.dp),
                    )
                    // Regex toggle
                    IconButton(
                        onClick = { useRegex = !useRegex },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Text(
                            ".*",
                            color = if (useRegex) Color(0xFF007ACC) else Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    // Prev
                    IconButton(
                        onClick = {
                            if (matches.isNotEmpty()) {
                                matchIndex = (matchIndex - 1 + matches.size) % matches.size
                                val range = matches[matchIndex]
                                value = value.copy(
                                    selection = androidx.compose.ui.text.TextRange(range.first, range.last + 1),
                                )
                            }
                        },
                        modifier = Modifier.size(28.dp),
                        enabled = matches.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp, null,
                            tint = if (matches.isNotEmpty()) Color(0xFFD4D4D4) else Color(0xFF555555),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // Next
                    IconButton(
                        onClick = {
                            if (matches.isNotEmpty()) {
                                matchIndex = (matchIndex + 1) % matches.size
                                val range = matches[matchIndex]
                                value = value.copy(
                                    selection = androidx.compose.ui.text.TextRange(range.first, range.last + 1),
                                )
                            }
                        },
                        modifier = Modifier.size(28.dp),
                        enabled = matches.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown, null,
                            tint = if (matches.isNotEmpty()) Color(0xFFD4D4D4) else Color(0xFF555555),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // Close
                    IconButton(
                        onClick = {
                            findQuery = ""
                            replaceQuery = ""
                            onFindReplaceClose()
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close, null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // Row 2 — Replace
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = replaceQuery,
                        onValueChange = { replaceQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                if (replaceQuery.isEmpty()) Text(
                                    "Replace",
                                    color = Color(0xFF666666),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(3.dp)),
                    )
                    // Replace current match
                    TextButton(
                        onClick = {
                            if (matches.isNotEmpty()) {
                                val range = matches[matchIndex]
                                val newText = value.text.substring(0, range.first) +
                                    replaceQuery +
                                    value.text.substring(range.last + 1)
                                val cursor = range.first + replaceQuery.length
                                value = TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(cursor),
                                )
                                onContentChange(newText)
                            }
                        },
                        enabled = matches.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "Replace",
                            color = if (matches.isNotEmpty()) Color(0xFF007ACC) else Color(0xFF555555),
                            fontSize = 11.sp,
                        )
                    }
                    // Replace all
                    TextButton(
                        onClick = {
                            if (findQuery.isNotEmpty() && matches.isNotEmpty()) {
                                val newText = try {
                                    val pattern = if (useRegex) Regex(findQuery)
                                                  else Regex(Regex.escape(findQuery))
                                    pattern.replace(value.text, replaceQuery)
                                } catch (e: Exception) { value.text }
                                value = TextFieldValue(text = newText)
                                onContentChange(newText)
                            }
                        },
                        enabled = matches.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "All",
                            color = if (matches.isNotEmpty()) Color(0xFF007ACC) else Color(0xFF555555),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // P39: Lightbulb indicator 💡 in the gutter shows when code actions are available
        if (lightbulbLine >= 0 && lspCodeActionProvider != null && !showCompletions) {
            val bulbTopDp = (lightbulbLine * fontSize * 1.25f) - vScroll.value
            val bulbHeight = fontSize * 1.25f
            // Only render if visible in viewport
            if (bulbTopDp >= 0 && bulbTopDp < (displayLines.size + 5) * bulbHeight) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 6.dp, top = bulbTopDp.dp)
                        .width(20.dp)
                        .height(bulbHeight.dp)
                        .clickable { showLightbulbMenu = true }
                        .zIndex(9f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💡",
                        fontSize = (fontSize * 0.65f).sp,
                    )
                }
            }
        }
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
                                    val cursorLine = value.text.take(value.selection.start).count { it == '\n' }
                                    val lineStart = value.text.lastIndexOf('\n', value.selection.start - 1) + 1
                                    val lineEnd = value.text.indexOf('\n', value.selection.start)
                                    val lineText = value.text.substring(lineStart, if (lineEnd < 0) value.text.length else lineEnd)
                                    val prompt = when (fix.kind) {
                                        com.codespace.ide.lsp.CodeActionKind.AIExplain -> "Explain this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIGenerateDoc -> "Generate documentation for this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIGenerateTests -> "Generate unit tests for this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIOptimize -> "Optimize this code for better performance:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIRewrite -> "Rewrite this code for better clarity:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AISimplify -> "Simplify this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIAddComments -> "Add inline comments to this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIExplainError -> "Explain the error in this code:\n" + lineText
                                        com.codespace.ide.lsp.CodeActionKind.AIImprovePerf -> "Suggest performance improvements for:\n" + lineText
                                        else -> fix.title + ":\n" + lineText
                                    }
                                    onAiFixRequest!!.invoke(prompt)
                                } else if (fix.edit != null) {
                                    try {
                                        val newText = com.codespace.ide.lsp.applyWorkspaceEdit(
                                            fix.edit, value.text, null
                                        )
                                        if (newText != null && newText != value.text) {
                                            value = TextFieldValue(newText, TextRange(value.selection.start))
                                            onContentChange(newText)
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
            val sig = activeSignature
            val cursorLineIdx = value.text.take(value.selection.end).count { it == '\n' }
            val popupLineIdx = (cursorLineIdx - 1).coerceAtLeast(0)
            // BUG-2 FIX: subtract scroll offset so the popup appears at the visible cursor position
            val popupTopDp = (popupLineIdx * fontSize * 1.25f) - vScroll.value
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 74.dp, top = popupTopDp.dp)
                    .widthIn(max = 320.dp)
                    .zIndex(10f)
                    .background(Color(0xFF252526), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = annotated,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD4D4D4),
                )
            }
        }

        // P15-D: Ghost text overlay — shown when not showing full dropdown
        if (ghostText != null && !showCompletions) {
            val ghost = ghostText!!
            val cursorLine = value.text.take(value.selection.end).count { it == '\n' }
            val cursorCol  = value.selection.end - (value.text.lastIndexOf('\n', value.selection.end - 1) + 1)
            val topDp  = cursorLine * fontSize * 1.25f
            val startDp = 64f + cursorCol * fontSize * 0.6f
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = startDp.dp, top = topDp.dp)
                    .zIndex(8f)
                    .clickable {
                        // Tap ghost text to accept it
                        val cursor = value.selection.end
                        val newText = value.text.substring(0, cursor) + ghost + value.text.substring(cursor)
                        value = TextFieldValue(
                            text = newText,
                            selection = androidx.compose.ui.text.TextRange(cursor + ghost.length),
                        )
                        onContentChange(newText)
                        // P41 Phase B: Record ghost text acceptance
                        val ghostLabel = allCompletions.firstOrNull()?.label
                        if (ghostLabel != null) {
                            CompletionHistoryStore.recordAccepted(ghostLabel, language.name, context)
                        }
                        ghostText = null
                    },
            ) {
                Text(
                    text = ghost,
                    color = Color(0xFF6A6A6A),   // dimmed — VS Code ghost text colour
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }

        // ── P38: Compact LSP Hover popup — 2-line preview, scrollable, expand + copy ──
        // Renders as a positioned overlay (NOT a Popup window) so it works in BOTH
        // portrait and landscape — Popup windows can get clipped/z-ordered in landscape.
        if (lspHoverContent != null && !showCompletions) {
            val hoverScrollState = rememberScrollState()
            var hoverExpanded by remember(lspHoverContent) { mutableStateOf(false) }
            val cursorLineIdxHover = value.text.take(value.selection.end).count { it == '\n' }
            val hoverTopDp = ((cursorLineIdxHover + 1) * fontSize * 1.25f) - vScroll.value
            // Only render if the popup would be within the visible viewport
            if (hoverTopDp > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 74.dp, top = hoverTopDp.dp)
                        .widthIn(max = 300.dp)
                        .zIndex(12f)
                        .background(Color(0xFF2D2D2D), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(6.dp)),
                ) {
                    Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
                        // Top row: expand + copy buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            // Expand/collapse button
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { hoverExpanded = !hoverExpanded },
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Text(
                                    text = if (hoverExpanded) "▾" else "▸",
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            // Copy to clipboard button
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(lspHoverContent ?: ""))
                                    },
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Text(
                                    text = "⧉",
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        // Content: 2 lines when collapsed, full when expanded
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .then(if (hoverExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(hoverScrollState) else Modifier)
                        ) {
                            Text(
                                text = lspHoverContent ?: "",
                                color = Color(0xFFCCCCCC),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = if (hoverExpanded) Int.MAX_VALUE else 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // IntelliSense dropdown — rendered in a Popup window so it's never clipped
        // by parent bounds, scroll offset, or the soft keyboard.
        if (showCompletions && allCompletions.isNotEmpty()) {
            val cursorLine = value.text.take(value.selection.end).count { it == '\n' }
            val lineHeightPx = fontSize * 1.25f
            // BUG-2 FIX: subtract scroll offset so dropdown appears at the visible cursor position
            val popupOffsetY = ((cursorLine + 1) * lineHeightPx - vScroll.value).roundToInt().coerceAtLeast(0)
            val popupOffsetX = with(androidx.compose.ui.platform.LocalDensity.current) { 74.dp.toPx() }.roundToInt()
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(popupOffsetX, popupOffsetY),
                properties = PopupProperties(focusable = false),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 260.dp)
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF252526), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp)),
                ) {
                    items(allCompletions) { comp ->
                    // Doc always visible below label — no per-item state (Compose rules)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cursor = value.selection.end
                                val text = value.text
                                val end = cursor.coerceAtMost(text.length)
                                var start = end
                                while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == ' ')) start--
                                var newText = text.substring(0, start) + comp.insertText + text.substring(end)
                                val newCursor = start + comp.insertText.length
                                // P22-J: Auto-import — fetch and apply missing import for the inserted symbol
                                if (lspImportProvider != null) {
                                    val cLine = text.take(cursor).count { it == '\n' }
                                    val cLineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)) + 1
                                    val cCol = cursor - cLineStart
                                    coroutineScope.launch {
                                        val imports = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            try { lspImportProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList() }
                                        }
                                        if (imports.isNotEmpty()) {
                                            val patched = applyImportEdits(newText, imports)
                                            value = TextFieldValue(
                                                text = patched,
                                                selection = androidx.compose.ui.text.TextRange(newCursor + (patched.length - newText.length)),
                                            )
                                            onContentChange(patched)
                                        } else {
                                            value = TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newCursor),
                                            )
                                            onContentChange(newText)
                                        }
                                    }
                                } else {
                                    value = TextFieldValue(
                                        text = newText,
                                        selection = androidx.compose.ui.text.TextRange(newCursor),
                                    )
                                    onContentChange(newText)
                                }
                                // P41 Phase B: Record accepted completion for MRU/usage ranking
                                CompletionHistoryStore.recordAccepted(comp.label, language.name, context)
                                showCompletions = false
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val (icon, tint) = when (comp.kind) {
                            CompletionKind.KEYWORD -> Pair(Icons.Default.Code, Color(0xFF569CD6))
                            CompletionKind.TYPE -> Pair(Icons.Default.TextFields, Color(0xFF4EC9B0))
                            CompletionKind.SNIPPET -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))
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
                            Text(labelAnnotated, color = Color(0xFFD4D4D4), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
                            if (comp.doc != null) {
                                Text(comp.doc, color = Color(0xFF888888), fontSize = 9.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(comp.kind.name.lowercase(), color = Color(0xFF808080), fontSize = 9.sp)
                    }
                }
                }
            }
        }
    }
}
