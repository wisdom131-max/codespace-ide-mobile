package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.LocalEditorColors
import kotlinx.coroutines.launch

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
) {
    val colors = LocalEditorColors.current
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 2. Code folding state
    var foldedRanges by remember { mutableStateOf(setOf<Int>()) } // start line index (0-based)

    // Parse lines and folding
    val rawLines = remember(value.text) { value.text.split("\n") }
    
    // Determine which line indices are foldable
    val foldableLines = remember(rawLines) {
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

    // Determine the range of folded lines
    val foldedLineIndices = remember(foldedRanges, rawLines) {
        val set = mutableSetOf<Int>()
        for (startIdx in foldedRanges) {
            if (startIdx >= rawLines.size) continue
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

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    val prefix = remember(value) { currentWord(value.text, value.selection.end) }
    val completions = remember(prefix, language) { completionsFor(prefix, language) }
    var showCompletions by remember { mutableStateOf(false) }
    LaunchedEffect(prefix) { showCompletions = prefix.length >= 2 && completions.isNotEmpty() }

    // ── Rename Symbol state ────────────────────────────────────────────────
    var renameDialogWord by remember { mutableStateOf<String?>(null) }  // null = closed
    var renameNewName by remember { mutableStateOf("") }
    var renameCount by remember { mutableStateOf(0) }

    // Bracket matching
    val bracketMatch = remember(value) {
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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(end = 62.dp)
                .verticalScroll(vScroll)
        ) {
            // Gutter
            val savedLines = remember(savedContent) { savedContent.split("\n") }
            val currentLines = remember(value.text) { value.text.split("\n") }
            Column(modifier = Modifier.padding(horizontal = 4.dp).width(62.dp)) {
                displayLines.forEach { (lineNum, _) ->
                    if (lineNum == -1) {
                        // Visual placeholder row in gutter
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(fontSize.dp)
                        ) {
                            Spacer(Modifier.width(20.dp))
                            Text(
                                text = " ",
                                color = colors.gutter,
                                fontSize = fontSize.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        val isDirty = savedContent.isNotEmpty() && (
                            lineNum >= savedLines.size || (lineNum < currentLines.size && lineNum < savedLines.size && currentLines[lineNum] != savedLines[lineNum])
                        )
                        val isAdded = savedContent.isNotEmpty() && lineNum >= savedLines.size
                        val isFoldable = foldableLines.contains(lineNum)
                        val isFolded = foldedRanges.contains(lineNum)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(fontSize.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(fontSize.dp)
                                    .background(
                                        when {
                                            isAdded -> Color(0xFF4EC9B0)
                                            isDirty -> Color(0xFF569CD6)
                                            else    -> Color.Transparent
                                        }
                                    )
                            )
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
                            Text(
                                text = (lineNum + 1).toString(),
                                color = colors.gutter,
                                fontSize = fontSize.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            // Editor surface
            Box(
                modifier = (if (wordWrap) Modifier else Modifier.horizontalScroll(hScroll))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            // Long-press → Rename Symbol: extract word at cursor
                            val cursor = value.selection.end
                            val word = currentWord(value.text, cursor)
                            if (word.length >= 2) {
                                renameNewName = word
                                val pattern = Regex("""\b${Regex.escape(word)}\b""")
                                renameCount = pattern.findAll(value.text).count()
                                renameDialogWord = word
                            }
                        }
                    )
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
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
                        
                        value = updatedValue
                        onContentChange(updatedValue.text)
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

        // Minimap
        val textLines = remember(value.text) { value.text.split("\n") }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(60.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A))
                .zIndex(5f),
        ) {
            textLines.forEachIndexed { idx, line ->
                val density = (line.trimStart().length.coerceAtMost(80)).toFloat() / 80f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 2.dp)
                        .clickable {
                            // 3. Minimap click-to-navigate
                            coroutineScope.launch {
                                val lineHeightPx = fontSize * 1.5f * 2.0f // Simple scale factor for density
                                vScroll.animateScrollTo((idx * lineHeightPx).toInt())
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val indent = line.length - line.trimStart().length
                    Spacer(Modifier.width((indent * 0.3f).dp))
                    Box(
                        Modifier
                            .weight(density.coerceAtLeast(0.05f))
                            .fillMaxHeight()
                            .background(Color(0xFF3C3C3C))
                    )
                    Spacer(Modifier.weight((1f - density).coerceAtLeast(0.05f)))
                }
            }
        }

        // Indentation guides
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 62.dp)
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
                            "$renameCount occurrence${if (renameCount != 1) "s" else ""} of "$wordToRename"",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                        )
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
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val newName = renameNewName.trim()
                            if (newName.isNotEmpty() && newName != wordToRename) {
                                val pattern = Regex("""\b${Regex.escape(wordToRename)}\b""")
                                val newText = pattern.replace(value.text, newName)
                                value = TextFieldValue(
                                    text = newText,
                                    selection = value.selection,
                                )
                                onContentChange(newText)
                            }
                            renameDialogWord = null
                        },
                        enabled = renameNewName.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)),
                    ) {
                        Text("Rename", color = Color(0xFFFFFFFF), fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameDialogWord = null }) {
                        Text("Cancel", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                },
            )
        }

        // IntelliSense dropdown
        if (showCompletions && completions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 64.dp, top = ((value.text.take(value.selection.end).count { it == '\n' } + 1) * fontSize * 1.25f).dp)
                    .widthIn(min = 160.dp, max = 260.dp)
                    .heightIn(max = 200.dp)
                    .zIndex(10f)
                    .background(Color(0xFF252526), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp)),
            ) {
                items(completions) { comp ->
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
                                val newText = text.substring(0, start) + comp.insertText + text.substring(end)
                                val newCursor = start + comp.insertText.length
                                value = TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(newCursor),
                                )
                                onContentChange(newText)
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
                            Text(comp.label, color = Color(0xFFD4D4D4), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
                            if (comp.doc != null) {
                                Text(comp.doc, color = Color(0xFF888888), fontSize = 9.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        Text(comp.kind.name.lowercase(), color = Color(0xFF808080), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
