package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

data class DexHeader(
    val magic: String,
    val version: String,
    val checksum: String,
    val fileSize: Long,
    val headerSize: Int,
    val endianTag: String,
    val stringIdsSize: Int,
    val typeIdsSize: Int,
    val protoIdsSize: Int,
    val fieldIdsSize: Int,
    val methodIdsSize: Int,
    val classDefsSize: Int,
    val dataSize: Int,
    val linkSize: Int,
)

data class DexClass(
    val className: String,          // Lcom/example/Foo; → com.example.Foo
    val superClass: String,
    val accessFlags: Int,
    val sourceFile: String,
    val interfaces: List<String>,
    val methods: List<DexMethod>,
    val fields: List<DexField>,
)

data class DexMethod(
    val name: String,
    val descriptor: String,         // e.g. (ILjava/lang/String;)V
    val accessFlags: Int,
    val isVirtual: Boolean,
)

data class DexField(
    val name: String,
    val type: String,
    val accessFlags: Int,
    val isStatic: Boolean,
)

data class DexParseResult(
    val header: DexHeader,
    val strings: List<String>,
    val types: List<String>,
    val classes: List<DexClass>,
    val methodCount: Int,
    val fieldCount: Int,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// DEX Binary Parser (pure Kotlin, no external deps)
// ─────────────────────────────────────────────────────────────────────────────

private object DexParser {

    private fun accessStr(flags: Int, isMethod: Boolean): String {
        val parts = mutableListOf<String>()
        if (flags and 0x0001 != 0) parts += "public"
        if (flags and 0x0002 != 0) parts += "private"
        if (flags and 0x0004 != 0) parts += "protected"
        if (flags and 0x0008 != 0) parts += "static"
        if (flags and 0x0010 != 0) parts += "final"
        if (flags and 0x0020 != 0) parts += if (isMethod) "synchronized" else "volatile"
        if (flags and 0x0040 != 0) parts += if (isMethod) "bridge" else "transient"
        if (flags and 0x0080 != 0) parts += if (isMethod) "varargs" else ""
        if (flags and 0x0100 != 0) parts += "native"
        if (flags and 0x0200 != 0) parts += "interface"
        if (flags and 0x0400 != 0) parts += "abstract"
        if (flags and 0x0800 != 0) parts += "strictfp"
        if (flags and 0x1000 != 0) parts += "synthetic"
        if (flags and 0x2000 != 0) parts += "annotation"
        if (flags and 0x4000 != 0) parts += "enum"
        if (flags and 0x10000 != 0) parts += "constructor"
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun typeDesc(d: String): String {
        if (d.isEmpty()) return ""
        return when {
            d.startsWith("L") && d.endsWith(";") ->
                d.drop(1).dropLast(1).replace('/', '.')
            d.startsWith("[") -> typeDesc(d.drop(1)) + "[]"
            else -> when (d) {
                "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"
                "S" -> "short"; "C" -> "char"; "I" -> "int"
                "J" -> "long"; "F" -> "float"; "D" -> "double"
                else -> d
            }
        }
    }

    // Read ULEB128 from buf at pos, returns (value, bytesRead)
    private fun readUleb128(buf: ByteArray, pos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var cur = pos
        while (cur < buf.size) {
            val b = buf[cur].toInt() and 0xFF
            cur++
            result = result or ((b and 0x7F) shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        return Pair(result, cur - pos)
    }

    fun parse(file: File): DexParseResult {
        val bytes = try {
            if (file.length() > 64 * 1024 * 1024) return DexParseResult(
                DexHeader("", "", "", 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0),
                emptyList(), emptyList(), emptyList(), 0, 0,
                "File too large to parse in-memory (>64MB)"
            )
            file.readBytes()
        } catch (e: Exception) {
            return DexParseResult(
                DexHeader("", "", "", 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0),
                emptyList(), emptyList(), emptyList(), 0, 0, "Read error: ${e.message}"
            )
        }

        if (bytes.size < 112) return DexParseResult(
            DexHeader("", "", "", 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0),
            emptyList(), emptyList(), emptyList(), 0, 0, "File too small to be a valid DEX"
        )

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Magic: 8 bytes "dex\n035\0"
        val magicBytes = ByteArray(4).also { buf.get(it) }
        val magic = String(magicBytes).trimEnd('\n', '\r', '\u0000')
        val versionBytes = ByteArray(4).also { buf.get(it) }
        val version = String(versionBytes).trimEnd('\n', '\r', '\u0000')

        val checksum = buf.int.toUInt().toString(16).padStart(8, '0')
        buf.position(buf.position() + 20) // skip SHA-1 signature (20 bytes)
        val fileSize = buf.int.toUInt().toLong()
        val headerSize = buf.int
        val endianTag = buf.int.toUInt().toString(16)
        val linkSize = buf.int
        buf.int // linkOff
        val _mapOff = buf.int
        val stringIdsSize = buf.int
        val stringIdsOff = buf.int
        val typeIdsSize = buf.int
        val typeIdsOff = buf.int
        val protoIdsSize = buf.int
        val protoIdsOff = buf.int
        val fieldIdsSize = buf.int
        val fieldIdsOff = buf.int
        val methodIdsSize = buf.int
        val methodIdsOff = buf.int
        val classDefsSize = buf.int
        val classDefsOff = buf.int
        val dataSize = buf.int

        val header = DexHeader(
            magic = magic,
            version = version,
            checksum = "0x$checksum",
            fileSize = fileSize,
            headerSize = headerSize,
            endianTag = if (endianTag == "12345678") "Little-Endian" else "Big-Endian",
            stringIdsSize = stringIdsSize,
            typeIdsSize = typeIdsSize,
            protoIdsSize = protoIdsSize,
            fieldIdsSize = fieldIdsSize,
            methodIdsSize = methodIdsSize,
            classDefsSize = classDefsSize,
            dataSize = dataSize,
            linkSize = linkSize,
        )

        // Parse string pool
        val strings = mutableListOf<String>()
        try {
            for (i in 0 until minOf(stringIdsSize, 50000)) {
                val off = stringIdsOff + i * 4
                if (off + 4 > bytes.size) break
                val strDataOff = ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (strDataOff < 0 || strDataOff >= bytes.size) { strings += ""; continue }
                // ULEB128 length prefix, then MUTF-8 bytes
                val (strLen, bytesRead) = readUleb128(bytes, strDataOff)
                val strStart = strDataOff + bytesRead
                if (strStart + strLen > bytes.size || strLen < 0 || strLen > 65535) { strings += ""; continue }
                strings += try { String(bytes, strStart, strLen, Charsets.UTF_8) } catch (_: Exception) { "" }
            }
        } catch (_: Exception) {}

        // Parse type IDs → type descriptor strings
        val types = mutableListOf<String>()
        try {
            for (i in 0 until minOf(typeIdsSize, 50000)) {
                val off = typeIdsOff + i * 4
                if (off + 4 > bytes.size) break
                val strIdx = ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
                types += if (strIdx >= 0 && strIdx < strings.size) strings[strIdx] else ""
            }
        } catch (_: Exception) {}

        // Parse field IDs: classIdx(2), typeIdx(2), nameIdx(4)
        data class FieldId(val classIdx: Int, val typeIdx: Int, val nameIdx: Int)
        val fieldIds = mutableListOf<FieldId>()
        try {
            for (i in 0 until minOf(fieldIdsSize, 100000)) {
                val off = fieldIdsOff + i * 8
                if (off + 8 > bytes.size) break
                val b = ByteBuffer.wrap(bytes, off, 8).order(ByteOrder.LITTLE_ENDIAN)
                fieldIds += FieldId(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.int)
            }
        } catch (_: Exception) {}

        // Parse method IDs: classIdx(2), protoIdx(2), nameIdx(4)
        data class MethodId(val classIdx: Int, val protoIdx: Int, val nameIdx: Int)
        val methodIds = mutableListOf<MethodId>()
        try {
            for (i in 0 until minOf(methodIdsSize, 100000)) {
                val off = methodIdsOff + i * 12
                if (off + 12 > bytes.size) break
                val b = ByteBuffer.wrap(bytes, off, 12).order(ByteOrder.LITTLE_ENDIAN)
                val classIdx = b.short.toInt() and 0xFFFF
                val protoIdx = b.short.toInt() and 0xFFFF
                val nameIdx = b.int
                methodIds += MethodId(classIdx, protoIdx, nameIdx)
            }
        } catch (_: Exception) {}

        // Parse proto IDs for return+param descriptors: shortyIdx(4), returnTypeIdx(4), parametersOff(4)
        data class ProtoId(val shortyIdx: Int, val returnTypeIdx: Int, val parametersOff: Int)
        val protoIds = mutableListOf<ProtoId>()
        try {
            for (i in 0 until minOf(protoIdsSize, 100000)) {
                val off = protoIdsOff + i * 12
                if (off + 12 > bytes.size) break
                val b = ByteBuffer.wrap(bytes, off, 12).order(ByteOrder.LITTLE_ENDIAN)
                protoIds += ProtoId(b.int, b.int, b.int)
            }
        } catch (_: Exception) {}

        // Parse class definitions
        val classes = mutableListOf<DexClass>()
        try {
            for (i in 0 until minOf(classDefsSize, 10000)) {
                val off = classDefsOff + i * 32
                if (off + 32 > bytes.size) break
                val b = ByteBuffer.wrap(bytes, off, 32).order(ByteOrder.LITTLE_ENDIAN)
                val classIdx        = b.int
                val accessFlags     = b.int
                val superclassIdx   = b.int
                val interfacesOff   = b.int
                val sourceFileIdx   = b.int
                val _annotationsOff  = b.int
                val classDataOff    = b.int
                val _staticValuesOff = b.int

                val rawClassName  = if (classIdx in types.indices) types[classIdx] else "?"
                val className     = typeDesc(rawClassName)
                val superClass    = if (superclassIdx == -1 || superclassIdx !in types.indices) "Object"
                                    else typeDesc(types[superclassIdx])
                val sourceFile    = if (sourceFileIdx == -1 || sourceFileIdx !in strings.indices) ""
                                    else strings[sourceFileIdx]

                // Parse interfaces list (type_list)
                val interfaces = mutableListOf<String>()
                if (interfacesOff > 0 && interfacesOff + 4 <= bytes.size) {
                    val iSize = ByteBuffer.wrap(bytes, interfacesOff, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    for (j in 0 until minOf(iSize, 100)) {
                        val iOff = interfacesOff + 4 + j * 2
                        if (iOff + 2 > bytes.size) break
                        val typeIdx = ByteBuffer.wrap(bytes, iOff, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        if (typeIdx in types.indices) interfaces += typeDesc(types[typeIdx])
                    }
                }

                // Parse class_data_item (encoded_fields + encoded_methods via ULEB128)
                val methods = mutableListOf<DexMethod>()
                val fields  = mutableListOf<DexField>()
                if (classDataOff > 0 && classDataOff < bytes.size) {
                    var cursor = classDataOff
                    fun next(): Pair<Int, Int> = readUleb128(bytes, cursor).also { cursor += it.second }

                    val (staticFieldsSize, _)   = next()
                    val (instanceFieldsSize, _)  = next()
                    val (directMethodsSize, _)   = next()
                    val (virtualMethodsSize, _)   = next()

                    // Fields
                    var fieldIdx = 0
                    for (isStatic in listOf(true, false)) {
                        val count = if (isStatic) staticFieldsSize else instanceFieldsSize
                        fieldIdx
                        for (j in 0 until minOf(count, 2000)) {
                            val (idxDiff, _) = next(); fieldIdx += idxDiff
                            val (aFlags, _)  = next()
                            if (fieldIdx in fieldIds.indices) {
                                val fid = fieldIds[fieldIdx]
                                val fName = if (fid.nameIdx in strings.indices) strings[fid.nameIdx] else "?"
                                val fType = if (fid.typeIdx in types.indices) typeDesc(types[fid.typeIdx]) else "?"
                                fields += DexField(fName, fType, aFlags, isStatic)
                            }
                        }
                    }

                    // Methods
                    var methodIdx = 0
                    for (isVirtual in listOf(false, true)) {
                        val count = if (!isVirtual) directMethodsSize else virtualMethodsSize
                        methodIdx
                        for (j in 0 until minOf(count, 2000)) {
                            val (idxDiff, _) = next(); methodIdx += idxDiff
                            val (aFlags, _)  = next()
                            val (codeOff, _) = next()
                            if (methodIdx in methodIds.indices) {
                                val mid = methodIds[methodIdx]
                                val mName = if (mid.nameIdx in strings.indices) strings[mid.nameIdx] else "?"
                                val descriptor = if (mid.protoIdx in protoIds.indices) {
                                    val proto = protoIds[mid.protoIdx]
                                    val ret = if (proto.returnTypeIdx in types.indices) typeDesc(types[proto.returnTypeIdx]) else "?"
                                    // params from type_list at parametersOff
                                    val params = mutableListOf<String>()
                                    val pOff = proto.parametersOff
                                    if (pOff > 0 && pOff + 4 <= bytes.size) {
                                        val pSize = ByteBuffer.wrap(bytes, pOff, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                        for (k in 0 until minOf(pSize, 20)) {
                                            val kOff = pOff + 4 + k * 2
                                            if (kOff + 2 > bytes.size) break
                                            val tIdx = ByteBuffer.wrap(bytes, kOff, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                                            params += if (tIdx in types.indices) typeDesc(types[tIdx]) else "?"
                                        }
                                    }
                                    "(${params.joinToString(", ")}): $ret"
                                } else "(?)"
                                methods += DexMethod(mName, descriptor, aFlags, isVirtual)
                            }
                        }
                    }
                }

                classes += DexClass(className, superClass, accessFlags, sourceFile, interfaces, methods, fields)
            }
        } catch (_: Exception) {}

        return DexParseResult(
            header = header,
            strings = strings,
            types = types,
            classes = classes,
            methodCount = methodIds.size,
            fieldCount = fieldIds.size,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val BG = Color(0xFF1E1E1E)
private val SURFACE = Color(0xFF252526)
private val ACCENT = Color(0xFF569CD6)
private val GREEN = Color(0xFF4EC9B0)
private val YELLOW = Color(0xFFDCDCAA)
private val MUTED = Color(0xFF858585)
private val TEXT = Color(0xFFD4D4D4)
private val RED = Color(0xFFF44747)
private val ORANGE = Color(0xFFCE9178)

@Composable
fun DexViewerDialog(file: File, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<DexParseResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    var classFilter by remember { mutableStateOf("") }
    var stringFilter by remember { mutableStateOf("") }
    var selectedClass by remember { mutableStateOf<DexClass?>(null) }

    LaunchedEffect(file) {
        loading = true
        result = withContext(Dispatchers.IO) { DexParser.parse(file) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(10.dp),
            color = BG,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Title bar ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SURFACE)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (result != null && !loading) {
                            Text(
                                "${result!!.header.classDefsSize} classes · ${result!!.methodCount} methods · ${result!!.fieldCount} fields · ${result!!.header.stringIdsSize} strings",
                                fontSize = 11.sp, color = MUTED,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = MUTED)
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = ACCENT)
                            Text("Parsing DEX…", fontSize = 13.sp, color = MUTED)
                        }
                    }
                    return@Surface
                }

                val r = result
                if (r == null || r.error != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(r?.error ?: "Parse failed", color = RED, fontSize = 13.sp)
                    }
                    return@Surface
                }

                // ── Tab row ──
                val tabs = listOf("HEADER", "CLASSES", "STRINGS", "TYPES")
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SURFACE,
                    contentColor = ACCENT,
                    edgePadding = 0.dp,
                ) {
                    tabs.forEachIndexed { idx, label ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx; selectedClass = null },
                            text = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        )
                    }
                }

                // ── Tab content ──
                when (selectedTab) {
                    // ── HEADER ──
                    0 -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            DexInfoRow("Magic", r.header.magic)
                            DexInfoRow("DEX Version", r.header.version)
                            DexInfoRow("Checksum", r.header.checksum)
                            DexInfoRow("File Size", "${"%,d".format(r.header.fileSize)} bytes (${r.header.fileSize / 1024} KB)")
                            DexInfoRow("Header Size", "${r.header.headerSize} bytes")
                            DexInfoRow("Endianness", r.header.endianTag)
                            Spacer(Modifier.height(8.dp))
                            DexSectionHeader("ID POOL COUNTS")
                            DexInfoRow("String IDs", "${"%,d".format(r.header.stringIdsSize)}")
                            DexInfoRow("Type IDs", "${"%,d".format(r.header.typeIdsSize)}")
                            DexInfoRow("Proto IDs", "${"%,d".format(r.header.protoIdsSize)}")
                            DexInfoRow("Field IDs", "${"%,d".format(r.header.fieldIdsSize)}")
                            DexInfoRow("Method IDs", "${"%,d".format(r.header.methodIdsSize)}")
                            DexInfoRow("Class Defs", "${"%,d".format(r.header.classDefsSize)}")
                            DexInfoRow("Data Size", "${"%,d".format(r.header.dataSize)} bytes")
                            DexInfoRow("Link Size", "${r.header.linkSize}")
                        }
                    }

                    // ── CLASSES ──
                    1 -> {
                        if (selectedClass != null) {
                            // Class detail view
                            val cls = selectedClass!!
                            Column(Modifier.fillMaxSize()) {
                                // Back bar
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(SURFACE)
                                        .clickable { selectedClass = null }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("← ", color = ACCENT, fontSize = 13.sp)
                                    Text("Back to class list", color = ACCENT, fontSize = 13.sp)
                                }
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    DexSectionHeader("CLASS")
                                    DexInfoRow("Name", cls.className)
                                    DexInfoRow("Super", cls.superClass)
                                    DexInfoRow("Source", cls.sourceFile.ifEmpty { "unknown" })
                                    DexInfoRow("Access", accessFlagsStr(cls.accessFlags, false))
                                    if (cls.interfaces.isNotEmpty()) {
                                        DexInfoRow("Interfaces", cls.interfaces.joinToString(", "))
                                    }

                                    if (cls.fields.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        DexSectionHeader("FIELDS (${cls.fields.size})")
                                        cls.fields.forEach { f ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 3.dp)
                                                    .horizontalScroll(rememberScrollState()),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    "${accessFlagsStr(f.accessFlags, false)} ${f.type} ${f.name}",
                                                    fontSize = 12.sp,
                                                    color = if (f.isStatic) YELLOW else TEXT,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }

                                    if (cls.methods.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        DexSectionHeader("METHODS (${cls.methods.size})")
                                        cls.methods.forEach { m ->
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                                    .background(SURFACE, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                val flags = accessFlagsStr(m.accessFlags, true)
                                                if (flags.isNotEmpty()) {
                                                    Text(flags, fontSize = 10.sp, color = MUTED)
                                                }
                                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                                    Text(
                                                        m.name,
                                                        fontSize = 12.sp,
                                                        color = if (m.isVirtual) GREEN else ACCENT,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Text(
                                                        m.descriptor,
                                                        fontSize = 12.sp,
                                                        color = TEXT,
                                                        fontFamily = FontFamily.Monospace,
                                                    )
                                                }
                                                if (m.isVirtual) {
                                                    Text("virtual", fontSize = 10.sp, color = MUTED)
                                                } else {
                                                    Text("direct", fontSize = 10.sp, color = MUTED)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Class list
                            Column(Modifier.fillMaxSize()) {
                                OutlinedTextField(
                                    value = classFilter,
                                    onValueChange = { classFilter = it },
                                    placeholder = { Text("Filter classes…", fontSize = 12.sp, color = MUTED) },
                                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MUTED) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ACCENT,
                                        unfocusedBorderColor = MUTED,
                                        focusedTextColor = TEXT,
                                        unfocusedTextColor = TEXT,
                                        cursorColor = ACCENT,
                                    ),
                                )
                                val filtered = if (classFilter.isBlank()) r.classes
                                               else r.classes.filter { it.className.contains(classFilter, ignoreCase = true) }
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(filtered) { cls ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedClass = cls }
                                                .padding(horizontal = 16.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    cls.className.substringAfterLast('.'),
                                                    fontSize = 13.sp,
                                                    color = GREEN,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    cls.className.substringBeforeLast('.', ""),
                                                    fontSize = 11.sp,
                                                    color = MUTED,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("${cls.methods.size}m ${cls.fields.size}f", fontSize = 10.sp, color = MUTED)
                                                if (cls.superClass != "Object") {
                                                    Text(": ${cls.superClass.substringAfterLast('.')}", fontSize = 10.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = SURFACE, thickness = 0.5.dp)
                                    }
                                    if (filtered.isEmpty()) {
                                        item {
                                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                                Text("No classes match \"$classFilter\"", color = MUTED, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── STRINGS ──
                    2 -> {
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = stringFilter,
                                onValueChange = { stringFilter = it },
                                placeholder = { Text("Filter string pool…", fontSize = 12.sp, color = MUTED) },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = MUTED) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ACCENT,
                                    unfocusedBorderColor = MUTED,
                                    focusedTextColor = TEXT,
                                    unfocusedTextColor = TEXT,
                                    cursorColor = ACCENT,
                                ),
                            )
                            val filteredStrings = r.strings
                                .filter { it.isNotBlank() && (stringFilter.isBlank() || it.contains(stringFilter, ignoreCase = true)) }
                                .take(5000)
                            Text(
                                "  ${"%,d".format(filteredStrings.size)} strings ${if (r.strings.size > 5000) "(capped at 5,000)" else ""}",
                                fontSize = 10.sp, color = MUTED, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(filteredStrings) { s ->
                                    Text(
                                        s,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 3.dp),
                                        fontSize = 12.sp,
                                        color = ORANGE,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    HorizontalDivider(color = SURFACE, thickness = 0.3.dp)
                                }
                            }
                        }
                    }

                    // ── TYPES ──
                    3 -> {
                        val filteredTypes = r.types.filter { it.isNotBlank() }
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            item {
                                Text(
                                    "${"%,d".format(filteredTypes.size)} type descriptors",
                                    fontSize = 10.sp, color = MUTED,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(filteredTypes) { t ->
                                val display = if (t.startsWith("L") && t.endsWith(";"))
                                    t.drop(1).dropLast(1).replace('/', '.')
                                else t
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        display,
                                        fontSize = 12.sp,
                                        color = if (t.startsWith("L")) ACCENT else YELLOW,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider(color = SURFACE, thickness = 0.3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun accessFlagsStr(flags: Int, isMethod: Boolean): String {
    val parts = mutableListOf<String>()
    if (flags and 0x0001 != 0) parts += "public"
    if (flags and 0x0002 != 0) parts += "private"
    if (flags and 0x0004 != 0) parts += "protected"
    if (flags and 0x0008 != 0) parts += "static"
    if (flags and 0x0010 != 0) parts += "final"
    if (flags and 0x0020 != 0) parts += if (isMethod) "synchronized" else "volatile"
    if (flags and 0x0100 != 0) parts += "native"
    if (flags and 0x0200 != 0) parts += "interface"
    if (flags and 0x0400 != 0) parts += "abstract"
    if (flags and 0x1000 != 0) parts += "synthetic"
    if (flags and 0x4000 != 0) parts += "enum"
    if (flags and 0x10000 != 0) parts += "constructor"
    return parts.joinToString(" ")
}

@Composable
private fun DexInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SURFACE, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = MUTED, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = TEXT, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DexSectionHeader(label: String) {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = ACCENT,
        letterSpacing = 1.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
