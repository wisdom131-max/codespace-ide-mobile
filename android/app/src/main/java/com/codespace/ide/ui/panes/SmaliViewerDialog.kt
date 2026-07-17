package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

// ─────────────────────────────────────────────────────────────────────────────
// Smali viewer — reads .smali files directly OR synthesizes pseudo-Smali
// disassembly from a DEX class list (the DEX class names are stored as the
// Smali path form: Lcom/example/Foo; → com/example/Foo.smali)
// ─────────────────────────────────────────────────────────────────────────────

private data class SmaliClass(
    val path: String,         // e.g. com/example/Foo.smali
    val className: String,    // e.g. com.example.Foo
    val content: String,      // raw smali text or synthetic stub
)

private data class SmaliLine(
    val lineNo: Int,
    val text: String,
    val kind: Kind,
) {
    enum class Kind { DIRECTIVE, OPCODE, LABEL, COMMENT, STRING, REGISTER, NORMAL }
}

// Very cheap tokeniser — no full Smali grammar, just enough for color hints
private fun tokeniseSmali(text: String): List<SmaliLine> {
    return text.lines().mapIndexed { idx, raw ->
        val trimmed = raw.trim()
        val kind = when {
            trimmed.startsWith("#")                  -> SmaliLine.Kind.COMMENT
            trimmed.startsWith(".")                  -> SmaliLine.Kind.DIRECTIVE
            trimmed.startsWith(":")                  -> SmaliLine.Kind.LABEL
            trimmed.matches(Regex("[a-z][-a-z/]+.*")) -> SmaliLine.Kind.OPCODE
            trimmed.startsWith("\"")                 -> SmaliLine.Kind.STRING
            else                                     -> SmaliLine.Kind.NORMAL
        }
        SmaliLine(idx + 1, raw, kind)
    }
}

// Read .smali files from either a directory tree or a .dex embedded in an APK
private suspend fun loadSmaliSource(source: File): List<SmaliClass> = withContext(Dispatchers.IO) {
    val result = mutableListOf<SmaliClass>()
    when {
        source.isDirectory -> {
            source.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".smali") }
                .take(500)
                .forEach { f ->
                    result.add(SmaliClass(
                        path = f.relativeTo(source).path,
                        className = f.relativeTo(source).path.removeSuffix(".smali").replace('/', '.'),
                        content = f.readText(),
                    ))
                }
        }
        source.name.endsWith(".smali") -> {
            result.add(SmaliClass(
                path = source.name,
                className = source.nameWithoutExtension.replace('/', '.'),
                content = source.readText(),
            ))
        }
        // .dex or anything else — synthesize stubs from the parsed DEX class list
        else -> {
            try {
                val dex = parseDexClassListForSmali(source)
                dex.forEach { (smaliPath, stub) ->
                    result.add(SmaliClass(
                        path = smaliPath,
                        className = smaliPath.removeSuffix(".smali").replace('/', '.'),
                        content = stub,
                    ))
                }
            } catch (_: Exception) {}
        }
    }
    result.sortedBy { it.path }
}

// Extract class list from DEX binary — produces a pseudo-Smali stub per class
private fun parseDexClassListForSmali(file: File): List<Pair<String, String>> {
    val bytes = file.readBytes()
    val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 112) return emptyList()
    // DEX header fields
    buf.position(32) // skip magic(8)+checksum(4)+sha1(20)
    val _fileSize    = buf.int
    val _headerSize  = buf.int
    val _endianTag   = buf.int
    val _linkSize    = buf.int
    val _linkOff     = buf.int
    val _mapOff      = buf.int
    val stringCount = buf.int
    val stringOff   = buf.int
    val typeCount   = buf.int
    val typeOff     = buf.int
    val _protoCount  = buf.int
    val _protoOff    = buf.int
    val _fieldCount  = buf.int
    val _fieldOff    = buf.int
    val _methodCount = buf.int
    val _methodOff   = buf.int
    val classCount  = buf.int
    val classOff    = buf.int

    fun readStringAt(idx: Int): String {
        if (idx < 0 || idx >= stringCount) return ""
        val offBuf = java.nio.ByteBuffer.wrap(bytes, stringOff + idx * 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val dataOff = offBuf.int
        if (dataOff < 0 || dataOff >= bytes.size) return ""
        var pos = dataOff
        // ULEB128 string length
        var shift = 0; var len = 0
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return try { String(bytes, pos, len.coerceAtMost(bytes.size - pos), Charsets.UTF_8) } catch (_: Exception) { "" }
    }

    fun readTypeStr(idx: Int): String {
        if (idx < 0 || idx >= typeCount) return ""
        val offBuf = java.nio.ByteBuffer.wrap(bytes, typeOff + idx * 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return readStringAt(offBuf.int)
    }

    val classes = mutableListOf<Pair<String, String>>()
    for (i in 0 until classCount.coerceAtMost(2000)) {
        val classDefOff = classOff + i * 32
        if (classDefOff + 32 > bytes.size) break
        val cBuf = java.nio.ByteBuffer.wrap(bytes, classDefOff, 32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val classTypeIdx = cBuf.int
        val accessFlags  = cBuf.int
        val superTypeIdx = cBuf.int

        val descriptor = readTypeStr(classTypeIdx)
        val superDesc  = readTypeStr(superTypeIdx)

        if (descriptor.isEmpty() || !descriptor.startsWith("L")) continue
        val smaliPath = descriptor.removePrefix("L").removeSuffix(";") + ".smali"
        val _className = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
        val superName = if (superTypeIdx != 0xFFFFFF.inv() && superDesc.isNotEmpty())
            superDesc.removePrefix("L").removeSuffix(";").replace('/', '.') else "java.lang.Object"

        val accessStr = buildString {
            if (accessFlags and 0x0001 != 0) append("public ")
            if (accessFlags and 0x0002 != 0) append("private ")
            if (accessFlags and 0x0004 != 0) append("protected ")
            if (accessFlags and 0x0008 != 0) append("static ")
            if (accessFlags and 0x0010 != 0) append("final ")
            if (accessFlags and 0x0200 != 0) append("interface ")
            if (accessFlags and 0x4000 != 0) append("annotation ")
            if (accessFlags and 0x4000 == 0 && accessFlags and 0x0200 == 0) append("class ")
        }.trim()

        val stub = buildString {
            appendLine("# Smali stub — synthesized from DEX class definition")
            appendLine("# Class: $descriptor")
            appendLine()
            appendLine(".class $accessStr $descriptor")
            appendLine(".super L${superName.replace('.', '/')};")
            appendLine()
            appendLine("# Methods and fields available in Binary Inspector / DEX Viewer")
            appendLine("# Access flags: 0x${accessFlags.toString(16)}")
        }
        classes.add(Pair(smaliPath, stub))
    }
    return classes
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val SBg     = Color(0xFF1E1E1E)
private val SCard   = Color(0xFF252526)
private val SText   = Color(0xFFD4D4D4)
private val SDim    = Color(0xFF808080)
private val SAccent = Color(0xFF007ACC)
private val SGreen  = Color(0xFF4EC9B0)
private val SYellow = Color(0xFFE5C07B)
private val SBlue   = Color(0xFF569CD6)
private val SPurple = Color(0xFFC586C0)
private val SDivider= Color(0xFF3C3C3C)
private val SComment= Color(0xFF6A9955)
private val SLabel  = Color(0xFFCE9178)

@Composable
fun SmaliViewerDialog(file: File, onDismiss: () -> Unit) {
    var classes    by remember { mutableStateOf<List<SmaliClass>>(emptyList()) }
    var loading    by remember { mutableStateOf(true) }
    var selected   by remember { mutableStateOf<SmaliClass?>(null) }
    var filter     by remember { mutableStateOf("") }
    val listState  = rememberLazyListState()

    LaunchedEffect(file.absolutePath) {
        loading = true
        classes = loadSmaliSource(file)
        loading = false
        if (classes.isNotEmpty()) selected = classes.first()
    }

    val filtered = remember(classes, filter) {
        if (filter.isBlank()) classes
        else classes.filter { it.className.contains(filter, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(SCard, RoundedCornerShape(8.dp))
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2D2D2D), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Smali Viewer", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(file.name, color = SText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = SDim, modifier = Modifier.size(16.dp))
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SAccent)
                        Spacer(Modifier.height(12.dp))
                        Text("Parsing Smali classes…", color = SDim, fontSize = 12.sp)
                    }
                }
                return@Column
            }

            if (classes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Smali classes found", color = SDim, fontSize = 12.sp)
                }
                return@Column
            }

            Row(Modifier.fillMaxSize()) {
                // Left pane — class list
                Column(
                    Modifier.width(180.dp).fillMaxHeight().background(SBg)
                ) {
                    // Filter
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth().padding(6.dp).height(40.dp),
                        placeholder = { Text("Filter…", fontSize = 10.sp, color = SDim) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SText),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SAccent, unfocusedBorderColor = SDivider,
                            focusedTextColor = SText, unfocusedTextColor = SText,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    Text("${filtered.size} classes", color = SDim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    HorizontalDivider(color = SDivider)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(filtered) { cls ->
                            val active = cls == selected
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (active) Color(0xFF094771) else Color.Transparent)
                                    .clickable { selected = cls }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                val parts = cls.className.split(".")
                                Text(
                                    parts.last(),
                                    color = if (active) SText else SGreen,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (parts.size > 1) {
                                    Text(
                                        parts.dropLast(1).joinToString("."),
                                        color = SDim,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = SDivider, thickness = 0.5.dp)
                        }
                    }
                }

                // Divider
                Box(Modifier.width(1.dp).fillMaxHeight().background(SDivider))

                // Right pane — content
                val cls = selected
                if (cls == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a class", color = SDim, fontSize = 12.sp)
                    }
                } else {
                    val smaliLines = remember(cls.content) { tokeniseSmali(cls.content) }
                    LazyColumn(
                        Modifier.fillMaxSize().background(SBg).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        items(smaliLines) { line ->
                            Row(Modifier.fillMaxWidth()) {
                                // Line number
                                Text(
                                    "%4d".format(line.lineNo),
                                    color = SDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(36.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                // Coloured content
                                val color = when (line.kind) {
                                    SmaliLine.Kind.DIRECTIVE -> SBlue
                                    SmaliLine.Kind.OPCODE    -> SText
                                    SmaliLine.Kind.LABEL     -> SYellow
                                    SmaliLine.Kind.COMMENT   -> SComment
                                    SmaliLine.Kind.STRING    -> SLabel
                                    SmaliLine.Kind.REGISTER  -> SPurple
                                    SmaliLine.Kind.NORMAL    -> SText
                                }
                                Text(
                                    line.text,
                                    color = color,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
