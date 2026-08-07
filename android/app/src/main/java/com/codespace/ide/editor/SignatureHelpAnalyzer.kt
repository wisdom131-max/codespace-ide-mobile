package com.codespace.ide.editor

import com.codespace.ide.domain.Language

/**
 * P2-12 Parameter hints / signature help.
 *
 * When the cursor sits inside an open `functionName(...)` call, shows the
 * function's parameter list with the currently-active parameter highlighted
 * — the classic "signature help" popup found in every desktop IDE.
 *
 * No AST / language server here (mobile-only, offline) — this is a small
 * curated signature database for common stdlib / framework calls, paired
 * with a lightweight backward paren/comma scanner to figure out (a) which
 * call the cursor is inside and (b) which parameter index is active.
 * Unknown function names simply produce no popup — same "no fabrication"
 * philosophy as InlayHintAnalyzer.
 */
data class SignatureInfo(
    val name: String,
    val params: List<String>,
    val returnType: String?,
    val activeParam: Int,
    val allSignatures: List<SignatureInfo> = emptyList(),  // P41-OV: all overloads from LSP
)

object SignatureHelpAnalyzer {

    private fun signaturesFor(lang: Language): Map<String, Pair<List<String>, String?>> = when (lang) {
        Language.KOTLIN -> mapOf(
            "listOf" to (listOf("vararg elements: T") to "List<T>"),
            "mutableListOf" to (listOf("vararg elements: T") to "MutableList<T>"),
            "mapOf" to (listOf("vararg pairs: Pair<K, V>") to "Map<K, V>"),
            "mutableMapOf" to (listOf("vararg pairs: Pair<K, V>") to "MutableMap<K, V>"),
            "setOf" to (listOf("vararg elements: T") to "Set<T>"),
            "filter" to (listOf("predicate: (T) -> Boolean") to "List<T>"),
            "map" to (listOf("transform: (T) -> R") to "List<R>"),
            "forEach" to (listOf("action: (T) -> Unit") to "Unit"),
            "let" to (listOf("block: (T) -> R") to "R"),
            "apply" to (listOf("block: T.() -> Unit") to "T"),
            "also" to (listOf("block: (T) -> Unit") to "T"),
            "run" to (listOf("block: () -> R") to "R"),
            "require" to (listOf("value: Boolean", "lazyMessage: () -> Any = ...") to "Unit"),
            "check" to (listOf("value: Boolean", "lazyMessage: () -> Any = ...") to "Unit"),
            "coerceIn" to (listOf("minimumValue: T", "maximumValue: T") to "T"),
            "coerceAtLeast" to (listOf("minimumValue: T") to "T"),
            "coerceAtMost" to (listOf("maximumValue: T") to "T"),
            "substring" to (listOf("startIndex: Int", "endIndex: Int = length") to "String"),
            "indexOf" to (listOf("element: T", "startIndex: Int = 0") to "Int"),
            "replace" to (listOf("oldValue: String", "newValue: String") to "String"),
            "split" to (listOf("vararg delimiters: String") to "List<String>"),
            "delay" to (listOf("timeMillis: Long") to "Unit"),
            "launch" to (listOf("context: CoroutineContext = EmptyCoroutineContext", "block: suspend CoroutineScope.() -> Unit") to "Job"),
            "mutableStateOf" to (listOf("value: T") to "MutableState<T>"),
            "remember" to (listOf("calculation: () -> T") to "T"),
            "LaunchedEffect" to (listOf("key1: Any?", "block: suspend CoroutineScope.() -> Unit") to "Unit"),
            "Text" to (listOf("text: String", "modifier: Modifier = Modifier", "color: Color = Color.Unspecified", "fontSize: TextUnit = TextUnit.Unspecified") to "Unit"),
            "Box" to (listOf("modifier: Modifier = Modifier", "contentAlignment: Alignment = Alignment.TopStart") to "Unit"),
            "Column" to (listOf("modifier: Modifier = Modifier", "verticalArrangement: Arrangement.Vertical = Arrangement.Top") to "Unit"),
            "Row" to (listOf("modifier: Modifier = Modifier", "horizontalArrangement: Arrangement.Horizontal = Arrangement.Start") to "Unit"),
            "padding" to (listOf("horizontal: Dp = 0.dp", "vertical: Dp = 0.dp") to "Modifier"),
        )
        Language.JAVASCRIPT, Language.TYPESCRIPT -> mapOf(
            "setTimeout" to (listOf("callback: () => void", "delayMs: number") to "number"),
            "setInterval" to (listOf("callback: () => void", "delayMs: number") to "number"),
            "addEventListener" to (listOf("type: string", "listener: (event: Event) => void") to "void"),
            "removeEventListener" to (listOf("type: string", "listener: (event: Event) => void") to "void"),
            "fetch" to (listOf("url: string", "options?: RequestInit") to "Promise<Response>"),
            "map" to (listOf("callback: (value, index, array) => any") to "Array"),
            "filter" to (listOf("callback: (value, index, array) => boolean") to "Array"),
            "reduce" to (listOf("callback: (acc, value, index, array) => any", "initialValue?: any") to "any"),
            "forEach" to (listOf("callback: (value, index, array) => void") to "void"),
            "JSON.stringify" to (listOf("value: any", "replacer?: (key, value) => any", "space?: number | string") to "string"),
            "JSON.parse" to (listOf("text: string", "reviver?: (key, value) => any") to "any"),
            "Object.keys" to (listOf("obj: object") to "string[]"),
            "Object.assign" to (listOf("target: object", "...sources: object[]") to "object"),
            "Array.from" to (listOf("arrayLike: ArrayLike", "mapFn?: (v, i) => any") to "Array"),
            "querySelector" to (listOf("selector: string") to "Element | null"),
            "useState" to (listOf("initialValue: T") to "[T, (T) => void]"),
            "useEffect" to (listOf("effect: () => void | (() => void)", "deps?: any[]") to "void"),
        )
        Language.PYTHON -> mapOf(
            "range" to (listOf("start: int", "stop: int", "step: int = 1") to "range"),
            "print" to (listOf("*values", "sep: str = ' '", "end: str = '\\\\n'") to "None"),
            "open" to (listOf("file: str", "mode: str = 'r'") to "TextIOWrapper"),
            "len" to (listOf("obj") to "int"),
            "sorted" to (listOf("iterable", "key=None", "reverse=False") to "list"),
            "enumerate" to (listOf("iterable", "start=0") to "enumerate"),
            "zip" to (listOf("*iterables") to "zip"),
            "map" to (listOf("function", "iterable") to "map"),
            "filter" to (listOf("function", "iterable") to "filter"),
            "isinstance" to (listOf("obj", "classinfo") to "bool"),
            "getattr" to (listOf("obj", "name: str", "default=None") to "Any"),
            "int" to (listOf("x", "base: int = 10") to "int"),
            "round" to (listOf("number", "ndigits=None") to "int | float"),
        )
        Language.JAVA -> mapOf(
            "System.out.println" to (listOf("x") to "void"),
            "Arrays.asList" to (listOf("...a: T[]") to "List<T>"),
            "Collections.sort" to (listOf("list: List<T>") to "void"),
            "String.format" to (listOf("format: String", "...args: Object[]") to "String"),
            "Integer.parseInt" to (listOf("s: String") to "int"),
            "Objects.requireNonNull" to (listOf("obj: T", "message: String") to "T"),
        )
        Language.RUST -> mapOf(
            "println!" to (listOf("format: &str", "...args") to "()"),
            "format!" to (listOf("format: &str", "...args") to "String"),
            "Vec::with_capacity" to (listOf("capacity: usize") to "Vec<T>"),
            "String::from" to (listOf("s: &str") to "String"),
            "Some" to (listOf("value: T") to "Option<T>"),
        )
        Language.GO -> mapOf(
            "fmt.Println" to (listOf("...a: any") to "(int, error)"),
            "fmt.Printf" to (listOf("format: string", "...a: any") to "(int, error)"),
            "fmt.Sprintf" to (listOf("format: string", "...a: any") to "string"),
            "make" to (listOf("t: Type", "size: int") to "Type"),
            "append" to (listOf("slice: []T", "...elems: T") to "[]T"),
            "len" to (listOf("v") to "int"),
        )
        else -> emptyMap()
    }

    /**
     * Scans backward from [cursor] to find the nearest enclosing, still-open
     * function call. Returns null if the cursor isn't inside a recognized call.
     */
    fun findActiveCall(text: String, cursor: Int, lang: Language): SignatureInfo? {
        val sigs = signaturesFor(lang)
        if (sigs.isEmpty()) return null

        val limit = (cursor - 400).coerceAtLeast(0)  // don't scan unboundedly far back
        var i = (cursor - 1).coerceAtMost(text.length - 1)
        var depth = 0
        var paramIndex = 0
        var inString = false
        var stringChar = ' '

        while (i >= limit) {
            val c = text[i]
            if (inString) {
                if (c == stringChar && (i == 0 || text[i - 1] != '\\')) inString = false
                i--
                continue
            }
            when (c) {
                '"', '\'' -> { inString = true; stringChar = c }
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        var end = i
                        while (end > 0 && text[end - 1] == ' ') end--
                        var start = end
                        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '.')) start--
                        val rawName = text.substring(start, end)
                        if (rawName.isEmpty()) return null
                        val sig = sigs[rawName] ?: sigs[rawName.substringAfterLast('.')] ?: return null
                        val clampedIdx = paramIndex.coerceAtMost((sig.first.size - 1).coerceAtLeast(0))
                        return SignatureInfo(rawName, sig.first, sig.second, clampedIdx)
                    } else {
                        depth--
                    }
                }
                ',' -> if (depth == 0) paramIndex++
                '{', '}', ';' -> if (depth == 0) return null  // crossed a statement/block boundary
            }
            i--
        }
        return null
    }
}
