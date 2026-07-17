package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.Language
import androidx.compose.material.icons.automirrored.filled.*

/** A parsed symbol from the current file */
data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val line: Int,          // 1-based line number
    val indent: Int = 0,   // nesting depth for tree indentation
)

enum class SymbolKind { CLASS, FUNCTION, VARIABLE, INTERFACE, PROPERTY, ENUM, MODULE, CONSTANT }

/**
 * Parse symbols from source text. Lightweight regex-based — no AST, runs on device.
 * Covers Kotlin, Java, Python, JS/TS, C/C++, Go, Rust, PHP.
 */
object SymbolParser {
    fun parse(text: String, language: Language): List<CodeSymbol> {
        val lines = text.lines()
        val symbols = mutableListOf<CodeSymbol>()

        // Precompiled patterns
        val pyClass = Regex("""^\s*class\s+([a-zA-Z0-9_]+)""")
        val pyDef = Regex("""^\s*def\s+([a-zA-Z0-9_]+)\s*\(""")

        val ktJavaClass = Regex("""\b(?:class|object)\s+([a-zA-Z0-9_]+)""")
        val ktJavaInterface = Regex("""\binterface\s+([a-zA-Z0-9_]+)""")
        val ktJavaEnum = Regex("""\benum\s+class\s+([a-zA-Z0-9_]+)""")
        val ktFun = Regex("""\bfun\s+([a-zA-Z0-9_]+)\s*\(""")
        val javaMethod = Regex("""\b(?:public|protected|private|static|\s)+\s+[\w<>]+\s+([a-zA-Z0-9_]+)\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?\s*\{""")
        val ktValVar = Regex("""\b(?:val|var|const\s+val)\s+([a-zA-Z0-9_]+)""")

        val jsTsClass = Regex("""\bclass\s+([a-zA-Z0-9_]+)""")
        val jsTsInterface = Regex("""\b(?:interface|type)\s+([a-zA-Z0-9_]+)""")
        val jsTsEnum = Regex("""\benum\s+([a-zA-Z0-9_]+)""")
        val jsTsFunc = Regex("""\bfunction\s+([a-zA-Z0-9_]+)\s*\(""")
        val jsTsVar = Regex("""\b(?:const|let|var)\s+([a-zA-Z0-9_]+)\b""")

        val goFunc = Regex("""\bfunc\s+([a-zA-Z0-9_]+)\s*\(""")
        val goFuncMethod = Regex("""\bfunc\s*\([^)]+\)\s*([a-zA-Z0-9_]+)\s*\(""")
        val goStruct = Regex("""\btype\s+([a-zA-Z0-9_]+)\s+struct\b""")
        val goInterface = Regex("""\btype\s+([a-zA-Z0-9_]+)\s+interface\b""")

        val rustFn = Regex("""\bfn\s+([a-zA-Z0-9_]+)\s*\(""")
        val rustStruct = Regex("""\bstruct\s+([a-zA-Z0-9_]+)""")
        val rustImpl = Regex("""\bimpl(?:\s+<[^>]+>)?\s+([a-zA-Z0-9_]+)""")
        val rustTrait = Regex("""\btrait\s+([a-zA-Z0-9_]+)""")
        val rustEnum = Regex("""\benum\s+([a-zA-Z0-9_]+)""")

        val cppMethod = Regex("""^\s*(?:[a-zA-Z0-9_<>]+\s+)+([a-zA-Z0-9_]+)\s*\([^)]*\)\s*(?:const)?\s*\{?""")

        val phpFunc = Regex("""\bfunction\s+([a-zA-Z0-9_]+)\s*\(""")
        val phpClass = Regex("""\bclass\s+([a-zA-Z0-9_]+)""")

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("#")) {
                return@forEachIndexed
            }

            // Estimate nesting indent by counting leading spaces / 4
            val leadingSpaces = line.takeWhile { it == ' ' }.length
            val indent = leadingSpaces / 4

            when (language) {
                Language.PYTHON -> {
                    val classMatch = pyClass.find(line)
                    if (classMatch != null) {
                        symbols.add(CodeSymbol(classMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                    } else {
                        val defMatch = pyDef.find(line)
                        if (defMatch != null) {
                            symbols.add(CodeSymbol(defMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                        }
                    }
                }
                Language.KOTLIN, Language.JAVA -> {
                    val enumMatch = ktJavaEnum.find(line)
                    if (enumMatch != null) {
                        symbols.add(CodeSymbol(enumMatch.groupValues[1], SymbolKind.ENUM, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val interfaceMatch = ktJavaInterface.find(line)
                    if (interfaceMatch != null) {
                        symbols.add(CodeSymbol(interfaceMatch.groupValues[1], SymbolKind.INTERFACE, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val classMatch = ktJavaClass.find(line)
                    if (classMatch != null) {
                        symbols.add(CodeSymbol(classMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val funMatch = ktFun.find(line)
                    if (funMatch != null) {
                        symbols.add(CodeSymbol(funMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                        return@forEachIndexed
                    }
                    if (language == Language.JAVA) {
                        val javaMatch = javaMethod.find(line)
                        if (javaMatch != null) {
                            symbols.add(CodeSymbol(javaMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                            return@forEachIndexed
                        }
                    }
                    val valVarMatch = ktValVar.find(line)
                    if (valVarMatch != null) {
                        symbols.add(CodeSymbol(valVarMatch.groupValues[1], SymbolKind.VARIABLE, lineNumber, indent))
                    }
                }
                Language.TYPESCRIPT, Language.JAVASCRIPT -> {
                    val classMatch = jsTsClass.find(line)
                    if (classMatch != null) {
                        symbols.add(CodeSymbol(classMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val interfaceMatch = jsTsInterface.find(line)
                    if (interfaceMatch != null) {
                        symbols.add(CodeSymbol(interfaceMatch.groupValues[1], SymbolKind.INTERFACE, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val enumMatch = jsTsEnum.find(line)
                    if (enumMatch != null) {
                        symbols.add(CodeSymbol(enumMatch.groupValues[1], SymbolKind.ENUM, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val funcMatch = jsTsFunc.find(line)
                    if (funcMatch != null) {
                        symbols.add(CodeSymbol(funcMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val varMatch = jsTsVar.find(line)
                    if (varMatch != null) {
                        symbols.add(CodeSymbol(varMatch.groupValues[1], SymbolKind.VARIABLE, lineNumber, indent))
                    }
                }
                Language.GO -> {
                    val structMatch = goStruct.find(line)
                    if (structMatch != null) {
                        symbols.add(CodeSymbol(structMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val interfaceMatch = goInterface.find(line)
                    if (interfaceMatch != null) {
                        symbols.add(CodeSymbol(interfaceMatch.groupValues[1], SymbolKind.INTERFACE, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val funcMatch = goFunc.find(line)
                    if (funcMatch != null) {
                        symbols.add(CodeSymbol(funcMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val methodMatch = goFuncMethod.find(line)
                    if (methodMatch != null) {
                        symbols.add(CodeSymbol(methodMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                    }
                }
                Language.RUST -> {
                    val structMatch = rustStruct.find(line)
                    if (structMatch != null) {
                        symbols.add(CodeSymbol(structMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val traitMatch = rustTrait.find(line)
                    if (traitMatch != null) {
                        symbols.add(CodeSymbol(traitMatch.groupValues[1], SymbolKind.INTERFACE, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val implMatch = rustImpl.find(line)
                    if (implMatch != null) {
                        symbols.add(CodeSymbol(implMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val enumMatch = rustEnum.find(line)
                    if (enumMatch != null) {
                        symbols.add(CodeSymbol(enumMatch.groupValues[1], SymbolKind.ENUM, lineNumber, indent))
                        return@forEachIndexed
                    }
                    val fnMatch = rustFn.find(line)
                    if (fnMatch != null) {
                        symbols.add(CodeSymbol(fnMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                    }
                }
                Language.C, Language.CPP -> {
                    val match = cppMethod.find(line)
                    if (match != null) {
                        val name = match.groupValues[1]
                        if (name !in listOf("if", "for", "while", "switch", "return")) {
                            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNumber, indent))
                        }
                    }
                }
                Language.PHP -> {
                    val classMatch = phpClass.find(line)
                    if (classMatch != null) {
                        symbols.add(CodeSymbol(classMatch.groupValues[1], SymbolKind.CLASS, lineNumber, indent))
                    } else {
                        val funcMatch = phpFunc.find(line)
                        if (funcMatch != null) {
                            symbols.add(CodeSymbol(funcMatch.groupValues[1], SymbolKind.FUNCTION, lineNumber, indent))
                        }
                    }
                }
                else -> { /* No-op for HTML, CSS, JSON, Markdown, XML, Plaintext */ }
            }
        }
        return symbols
    }
}

/**
 * Outline panel composable — shows symbol tree, tapping a symbol calls onJumpToLine.
 * Also exports a BreadcrumbBar composable for the top of the editor.
 */
@Composable
fun OutlinePanel(
    text: String,
    language: Language,
    currentLine: Int = 1,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val symbols = remember(text, language) {
        SymbolParser.parse(text, language)
    }

    // Find the active symbol closest to currentLine (the last symbol starting at or before currentLine)
    val activeSymbol = remember(symbols, currentLine) {
        symbols.filter { it.line <= currentLine }.maxByOrNull { it.line }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "OUTLINE",
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (symbols.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No symbols found",
                    color = Color(0xFF858585),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(symbols) { symbol ->
                    val isSelected = symbol == activeSymbol
                    val backgroundColor = if (isSelected) Color(0xFF2A2D2E) else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(backgroundColor)
                            .clickable { onJumpToLine(symbol.line) }
                            .padding(vertical = 6.dp, horizontal = 16.dp)
                            .padding(start = (symbol.indent * 12).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = when (symbol.kind) {
                            SymbolKind.CLASS -> Icons.Default.AccountTree
                            SymbolKind.FUNCTION -> Icons.Default.Functions
                            SymbolKind.VARIABLE -> Icons.Default.Code
                            SymbolKind.INTERFACE -> Icons.Default.Hub
                            SymbolKind.PROPERTY -> Icons.Default.Build
                            SymbolKind.ENUM -> Icons.AutoMirrored.Filled.List
                            SymbolKind.MODULE -> Icons.Default.ViewModule
                            SymbolKind.CONSTANT -> Icons.Default.Lock
                        }

                        val iconColor = when (symbol.kind) {
                            SymbolKind.CLASS -> Color(0xFF3584E4)       // Blue
                            SymbolKind.FUNCTION -> Color(0xFFE5A50A)    // Yellow
                            SymbolKind.VARIABLE -> Color(0xFF26A269)    // Green
                            SymbolKind.INTERFACE -> Color(0xFF12A5C9)   // Cyan-blue
                            else -> Color(0xFF858585)
                        }

                        Icon(
                            imageVector = icon,
                            contentDescription = symbol.kind.name,
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = symbol.name,
                            color = if (isSelected) Color.White else Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable  
fun BreadcrumbBar(
    text: String,
    language: Language,
    currentLine: Int,
    filePath: String,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val symbols = remember(text, language) {
        SymbolParser.parse(text, language)
    }

    // Parse filename from path
    val fileName = remember(filePath) {
        filePath.substringAfterLast('/')
    }

    // Last CLASS/INTERFACE symbol at or before currentLine
    val currentClassSymbol = remember(symbols, currentLine) {
        symbols.filter { 
            it.line <= currentLine && (it.kind == SymbolKind.CLASS || it.kind == SymbolKind.INTERFACE || it.kind == SymbolKind.ENUM) 
        }.maxByOrNull { it.line }
    }

    // Last FUNCTION symbol at or before currentLine
    val currentFunctionSymbol = remember(symbols, currentLine) {
        symbols.filter { 
            it.line <= currentLine && it.kind == SymbolKind.FUNCTION 
        }.maxByOrNull { it.line }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 24.dp)
            .background(Color(0xFF1E1E1E))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Filename
        Text(
            text = fileName,
            color = Color(0xFF858585),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                // Clicking filename jumps to line 1 or start of file
                onJumpToLine(1)
            }
        )

        if (currentClassSymbol != null) {
            Text(
                text = " > ",
                color = Color(0xFF858585),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = currentClassSymbol.name,
                color = if (currentFunctionSymbol == null) Color(0xFFCCCCCC) else Color(0xFF858585),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable {
                    onJumpToLine(currentClassSymbol.line)
                }
            )
        }

        if (currentFunctionSymbol != null) {
            Text(
                text = " > ",
                color = Color(0xFF858585),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = currentFunctionSymbol.name,
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable {
                    onJumpToLine(currentFunctionSymbol.line)
                }
            )
        }
    }
}
