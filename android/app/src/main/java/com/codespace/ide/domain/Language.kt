package com.codespace.ide.domain

/** Languages with first-class syntax highlighting support. */
enum class Language(val displayName: String, val extensions: List<String>) {
    JAVASCRIPT("JavaScript", listOf("js", "jsx", "mjs", "cjs")),
    TYPESCRIPT("TypeScript", listOf("ts", "tsx")),
    KOTLIN("Kotlin", listOf("kt", "kts")),
    PYTHON("Python", listOf("py", "pyw")),
    HTML("HTML", listOf("html", "htm")),
    CSS("CSS", listOf("css", "scss", "sass", "less")),
    JSON("JSON", listOf("json", "jsonc")),
    MARKDOWN("Markdown", listOf("md", "markdown")),
    JAVA("Java", listOf("java")),
    CPP("C++", listOf("cpp", "cc", "cxx", "hpp")),
    C("C", listOf("c", "h")),
    GO("Go", listOf("go")),
    RUST("Rust", listOf("rs")),
    PHP("PHP", listOf("php")),
    SHELL("Shell", listOf("sh", "bash", "zsh")),
    XML("XML", listOf("xml", "svg", "plist")),
    YAML("YAML", listOf("yaml", "yml")),
    TOML("TOML", listOf("toml")),
    VUE("Vue", listOf("vue")),
    SVELTE("Svelte", listOf("svelte")),
    CSHARP("C#", listOf("cs", "csx")),
    RUBY("Ruby", listOf("rb", "erb")),
    SWIFT("Swift", listOf("swift")),
    DART("Dart", listOf("dart")),
    LUA("Lua", listOf("lua")),
    SQL("SQL", listOf("sql")),
    POWERSHELL("PowerShell", listOf("ps1", "psm1")),
    SCALA("Scala", listOf("scala", "sbt")),
    R("R", listOf("r", "R")),
    PLAINTEXT("Plain Text", emptyList()),
    PLAIN("Plain Text", emptyList());

    companion object {
        fun fromPath(path: String): Language {
            val ext = path.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: PLAINTEXT
        }
    }
}
