package com.codespace.ide.editor

import com.codespace.ide.domain.Language

data class LanguageSpec(
    val keywords: Set<String>,
    val types: Set<String>,
    val lineComment: String?,
    val blockCommentStart: String?,
    val blockCommentEnd: String?,
    val stringDelimiters: Set<Char> = setOf('"', '\''),
)

/** Keyword/type tables for the built-in highlighter fallback. */
object LanguageSpecs {

    private val C_LIKE_COMMENTS = Triple("//", "/*", "*/")
    private val BACKTICK_STRING_DELIMS = setOf('"', '\'', '`')

    private fun spec(
        keywords: Set<String>,
        types: Set<String> = emptySet(),
        comments: Triple<String, String, String>? = C_LIKE_COMMENTS,
    ) = LanguageSpec(keywords, types, comments?.first, comments?.second, comments?.third)

    fun forLanguage(lang: Language): LanguageSpec = when (lang) {
        Language.JAVASCRIPT, Language.TYPESCRIPT -> LanguageSpec(
            keywords = setOf(
                "const", "let", "var", "function", "return", "if", "else", "for", "while",
                "do", "switch", "case", "break", "continue", "class", "extends", "new",
                "import", "export", "default", "from", "async", "await", "try", "catch",
                "finally", "throw", "typeof", "instanceof", "this", "super", "yield",
                "interface", "type", "enum", "implements", "public", "private", "readonly",
                "static", "as", "in", "of", "null", "undefined", "true", "false", "void",
            ),
            types = setOf("string", "number", "boolean", "any", "unknown", "never", "object", "Promise", "Array"),
            lineComment = "//",
            blockCommentStart = "/*",
            blockCommentEnd = "*/",
            stringDelimiters = BACKTICK_STRING_DELIMS,
        )
        Language.PYTHON -> spec(
            keywords = setOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "break",
                "continue", "import", "from", "as", "try", "except", "finally", "raise",
                "with", "lambda", "yield", "global", "nonlocal", "pass", "and", "or", "not",
                "is", "in", "None", "True", "False", "async", "await", "self",
            ),
            types = setOf("int", "str", "float", "bool", "list", "dict", "set", "tuple", "bytes"),
            comments = Triple("#", "\"\"\"", "\"\"\""),
        )
        Language.KOTLIN -> spec(
            keywords = setOf(
                "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
                "return", "if", "else", "when", "for", "while", "do", "break", "continue",
                "import", "package", "try", "catch", "finally", "throw", "this", "super",
                "companion", "override", "private", "public", "protected", "internal",
                "abstract", "open", "final", "lateinit", "by", "in", "is", "as",
                "null", "true", "false", "suspend", "inline", "reified",
                "typealias", "init", "constructor",
            ),
            types = setOf("Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short",
                "String", "Unit", "Any", "Nothing", "List", "Map", "Set", "Array", "Pair"),
        )
        Language.JAVA -> spec(
            keywords = setOf(
                "public", "private", "protected", "class", "interface", "extends",
                "implements", "static", "final", "void", "return", "if", "else", "for",
                "while", "do", "switch", "case", "break", "continue", "new", "import",
                "package", "try", "catch", "finally", "throw", "throws", "this", "super",
                "abstract", "enum", "instanceof", "null", "true", "false",
            ),
            types = setOf("int", "long", "double", "float", "boolean", "char", "byte", "short", "String"),
        )
        Language.CPP -> spec(
            keywords = setOf(
                "int", "char", "float", "double", "void", "bool", "long", "short", "unsigned",
                "signed", "const", "static", "return", "if", "else", "for", "while", "do",
                "switch", "case", "break", "continue", "struct", "class", "public", "private",
                "protected", "namespace", "using", "template", "typename", "new", "delete",
                "nullptr", "true", "false", "auto", "include", "define",
            ),
        )
        Language.C -> spec(
            keywords = setOf(
                "int", "char", "float", "double", "void", "long", "short", "unsigned",
                "signed", "const", "static", "return", "if", "else", "for", "while", "do",
                "switch", "case", "break", "continue", "struct", "union", "enum", "typedef",
                "sizeof", "include", "define", "ifndef", "ifdef", "endif", "NULL",
            ),
        )
        Language.GO -> spec(
            keywords = setOf(
                "func", "package", "import", "var", "const", "type", "struct", "interface",
                "return", "if", "else", "for", "range", "switch", "case", "break", "continue",
                "go", "defer", "chan", "select", "map", "nil", "true", "false", "make", "new",
            ),
            types = setOf("int", "int64", "string", "bool", "float64", "byte", "rune", "error"),
        )
        Language.RUST -> spec(
            keywords = setOf(
                "fn", "let", "mut", "const", "struct", "enum", "impl", "trait", "pub", "use",
                "mod", "return", "if", "else", "for", "while", "loop", "match", "break",
                "continue", "self", "Self", "async", "await", "move", "ref", "where", "as",
                "true", "false", "Some", "None", "Ok", "Err",
            ),
            types = setOf("i32", "i64", "u32", "u64", "usize", "f64", "bool", "str", "String", "Vec", "Option", "Result"),
        )
        Language.PHP -> LanguageSpec(
            keywords = setOf(
                "function", "class", "public", "private", "protected", "static", "return",
                "if", "else", "elseif", "for", "foreach", "while", "do", "switch", "case",
                "break", "continue", "new", "echo", "print", "use", "namespace", "try",
                "catch", "finally", "throw", "extends", "implements", "interface", "trait",
                "this", "null", "true", "false", "const", "var", "as",
            ),
            types = emptySet(),
            lineComment = "//",
            blockCommentStart = "/*",
            blockCommentEnd = "*/",
            stringDelimiters = BACKTICK_STRING_DELIMS,
        )
        Language.SHELL -> LanguageSpec(
            keywords = setOf(
                "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while",
                "case", "esac", "function", "return", "exit", "echo", "export", "source",
                "local", "readonly", "shift", "trap", "eval", "exec", "alias", "unset",
                "set", "break", "continue", "true", "false",
            ),
            types = emptySet(),
            lineComment = "#",
            blockCommentStart = null,
            blockCommentEnd = null,
            stringDelimiters = BACKTICK_STRING_DELIMS,
        )
        Language.XML -> LanguageSpec(
            keywords = emptySet(),
            types = emptySet(),
            lineComment = null,
            blockCommentStart = "<!--",
            blockCommentEnd = "-->",
        )
        Language.HTML -> LanguageSpec(
            keywords = emptySet(),
            types = emptySet(),
            lineComment = null,
            blockCommentStart = "<!--",
            blockCommentEnd = "-->",
        )
        Language.CSS -> LanguageSpec(
            keywords = emptySet(),
            types = emptySet(),
            lineComment = null,
            blockCommentStart = "/*",
            blockCommentEnd = "*/",
        )
        Language.JSON -> spec(
            keywords = setOf("true", "false", "null"),
            comments = null,
        )
        Language.MARKDOWN -> spec(keywords = emptySet(), comments = null)
        Language.PLAIN, Language.PLAINTEXT -> spec(keywords = emptySet(), comments = null)
        else -> spec(keywords = emptySet(), comments = null)
    }
}
