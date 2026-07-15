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
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

data class ElfHeader(
    val elfClass: String,       // ELF32 / ELF64
    val dataEncoding: String,   // Little-Endian / Big-Endian
    val elfVersion: Int,
    val osAbi: String,
    val fileType: String,       // DYN, EXEC, REL, CORE
    val machine: String,        // ARM64, ARM, x86, x86-64, …
    val entryPoint: String,     // hex
    val flags: String,
    val sectionCount: Int,
    val programHeaderCount: Int,
    val shStrNdx: Int,
)

data class ElfSection(
    val name: String,
    val type: String,
    val flags: String,
    val address: String,
    val offset: Long,
    val size: Long,
    val link: Int,
    val info: Int,
    val alignment: Long,
    val entSize: Long,
)

data class ElfSegment(
    val type: String,
    val flags: String,
    val offset: Long,
    val vaddr: String,
    val paddr: String,
    val fileSize: Long,
    val memSize: Long,
    val alignment: Long,
)

data class ElfSymbol(
    val name: String,
    val value: String,
    val size: Long,
    val type: String,
    val binding: String,
    val visibility: String,
    val sectionIndex: String,
    val isDynamic: Boolean,
)

data class ElfDynEntry(
    val tag: String,
    val value: String,
)

data class ElfParseResult(
    val header: ElfHeader,
    val sections: List<ElfSection>,
    val segments: List<ElfSegment>,
    val symbols: List<ElfSymbol>,
    val dynEntries: List<ElfDynEntry>,
    val neededLibs: List<String>,
    val soName: String,
    val rpath: String,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// ELF Binary Parser — supports ELF32 and ELF64, little-endian and big-endian
// ─────────────────────────────────────────────────────────────────────────────

private object ElfParser {

    private fun machineStr(m: Int) = when (m) {
        0x00 -> "None"; 0x02 -> "SPARC"; 0x03 -> "x86"
        0x08 -> "MIPS"; 0x14 -> "PowerPC"; 0x16 -> "S390"
        0x28 -> "ARM (32-bit)"; 0x2A -> "SuperH"
        0x32 -> "IA-64"; 0x3E -> "x86-64 (AMD64)"
        0x40 -> "AVR32"; 0x41 -> "Freescale eDSP"
        0xB7 -> "ARM64 (AArch64)"; 0xF3 -> "RISC-V"
        0xF7 -> "Berkeley Packet Filter"
        else -> "0x${m.toString(16).uppercase()}"
    }

    private fun fileTypeStr(t: Int) = when (t) {
        0 -> "None (ET_NONE)"; 1 -> "Relocatable (ET_REL)"
        2 -> "Executable (ET_EXEC)"; 3 -> "Shared Object (ET_DYN)"
        4 -> "Core (ET_CORE)"
        else -> "Unknown (0x${t.toString(16)})"
    }

    private fun osAbiStr(a: Int) = when (a) {
        0 -> "System V"; 1 -> "HP-UX"; 2 -> "NetBSD"
        3 -> "Linux"; 6 -> "Solaris"; 7 -> "AIX"
        8 -> "IRIX"; 9 -> "FreeBSD"; 12 -> "OpenBSD"
        64 -> "ARM EABI"; 97 -> "ARM"; 255 -> "Standalone"
        else -> "0x${a.toString(16)}"
    }

    private fun sectionTypeStr(t: Long) = when (t) {
        0L -> "NULL"; 1L -> "PROGBITS"; 2L -> "SYMTAB"
        3L -> "STRTAB"; 4L -> "RELA"; 5L -> "HASH"
        6L -> "DYNAMIC"; 7L -> "NOTE"; 8L -> "NOBITS"
        9L -> "REL"; 10L -> "SHLIB"; 11L -> "DYNSYM"
        14L -> "INIT_ARRAY"; 15L -> "FINI_ARRAY"
        16L -> "PREINIT_ARRAY"; 17L -> "GROUP"; 18L -> "SYMTAB_SHNDX"
        0x6ffffffdL -> "VERDEF"; 0x6ffffffeL -> "VERNEED"
        0x6fffffffL -> "VERSYM"; 0x70000001L -> "ARM_EXIDX"
        0x70000003L -> "ARM_ATTRIBUTES"
        else -> "0x${t.toString(16).uppercase()}"
    }

    private fun segTypeStr(t: Long) = when (t) {
        0L -> "NULL"; 1L -> "LOAD"; 2L -> "DYNAMIC"
        3L -> "INTERP"; 4L -> "NOTE"; 5L -> "SHLIB"
        6L -> "PHDR"; 7L -> "TLS"
        0x60000000L -> "LOOS"; 0x6474e550L -> "GNU_EH_FRAME"
        0x6474e551L -> "GNU_STACK"; 0x6474e552L -> "GNU_RELRO"
        0x6474e553L -> "GNU_PROPERTY"; 0x70000000L -> "ARM_ARCHEXT"
        0x70000001L -> "ARM_EXIDX"
        else -> "0x${t.toString(16).uppercase()}"
    }

    private fun symTypeStr(t: Int) = when (t and 0xF) {
        0 -> "NOTYPE"; 1 -> "OBJECT"; 2 -> "FUNC"
        3 -> "SECTION"; 4 -> "FILE"; 5 -> "COMMON"
        6 -> "TLS"; 10 -> "GNU_IFUNC"
        else -> "0x${(t and 0xF).toString(16)}"
    }

    private fun symBindStr(b: Int) = when (b shr 4) {
        0 -> "LOCAL"; 1 -> "GLOBAL"; 2 -> "WEAK"
        10 -> "GNU_UNIQUE"
        else -> "0x${(b shr 4).toString(16)}"
    }

    private fun symVisStr(v: Int) = when (v and 0x3) {
        0 -> "DEFAULT"; 1 -> "INTERNAL"; 2 -> "HIDDEN"; 3 -> "PROTECTED"
        else -> "?"
    }

    private fun symSectionStr(s: Int) = when (s) {
        0 -> "UND"; 0xFFF1 -> "ABS"; 0xFFF2 -> "COMMON"
        0xFFFF -> "XINDEX"
        else -> s.toString()
    }

    private fun segFlagsStr(f: Long): String {
        val r = if (f and 4L != 0L) "R" else "-"
        val w = if (f and 2L != 0L) "W" else "-"
        val x = if (f and 1L != 0L) "X" else "-"
        return "$r$w$x"
    }

    private fun sectionFlagsStr(f: Long): String {
        val parts = mutableListOf<String>()
        if (f and 0x1L != 0L) parts += "WRITE"
        if (f and 0x2L != 0L) parts += "ALLOC"
        if (f and 0x4L != 0L) parts += "EXECINSTR"
        if (f and 0x10L != 0L) parts += "MERGE"
        if (f and 0x20L != 0L) parts += "STRINGS"
        if (f and 0x40L != 0L) parts += "INFO_LINK"
        if (f and 0x80L != 0L) parts += "LINK_ORDER"
        if (f and 0x200L != 0L) parts += "GROUP"
        if (f and 0x400L != 0L) parts += "TLS"
        return if (parts.isEmpty()) "none" else parts.joinToString("|")
    }

    private fun dynTagStr(tag: Long) = when (tag) {
        0L -> "NULL"; 1L -> "NEEDED"; 2L -> "PLTRELSZ"
        3L -> "PLTGOT"; 4L -> "HASH"; 5L -> "STRTAB"
        6L -> "SYMTAB"; 7L -> "RELA"; 8L -> "RELASZ"
        9L -> "RELAENT"; 10L -> "STRSZ"; 11L -> "SYMENT"
        12L -> "INIT"; 13L -> "FINI"; 14L -> "SONAME"
        15L -> "RPATH"; 16L -> "SYMBOLIC"; 17L -> "REL"
        18L -> "RELSZ"; 19L -> "RELENT"; 20L -> "PLTREL"
        21L -> "DEBUG"; 22L -> "TEXTREL"; 23L -> "JMPREL"
        24L -> "BIND_NOW"; 25L -> "INIT_ARRAY"; 26L -> "FINI_ARRAY"
        27L -> "INIT_ARRAYSZ"; 28L -> "FINI_ARRAYSZ"
        29L -> "RUNPATH"; 30L -> "FLAGS"; 32L -> "PREINIT_ARRAY"
        33L -> "PREINIT_ARRAYSZ"; 0x6ffffef5L -> "GNU_HASH"
        0x6ffffff0L -> "VERSYM"; 0x6ffffffeL -> "VERNEED"
        0x6fffffffL -> "VERNEEDNUM"; 0x6ffffff9L -> "RELACOUNT"
        else -> "0x${tag.toString(16).uppercase()}"
    }

    // Read a null-terminated string from a byte array at offset
    private fun readString(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        val end = data.indexOf(0.toByte(), offset).let { if (it < 0) data.size else it }
        return try { String(data, offset, end - offset, Charsets.UTF_8) } catch (_: Exception) { "" }
    }

    fun parse(file: File): ElfParseResult {
        val bytes = try {
            if (file.length() > 128 * 1024 * 1024) return ElfParseResult(
                ElfHeader("", "", 0, "", "", "", "", "", 0, 0, 0),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", "",
                "File too large (>128MB)"
            )
            file.readBytes()
        } catch (e: Exception) {
            return ElfParseResult(
                ElfHeader("", "", 0, "", "", "", "", "", 0, 0, 0),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", "",
                "Read error: ${e.message}"
            )
        }

        if (bytes.size < 64) return ElfParseResult(
            ElfHeader("", "", 0, "", "", "", "", "", 0, 0, 0),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", "",
            "File too small to be ELF"
        )

        // Check magic: 0x7F 'E' 'L' 'F'
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 0x45.toByte() ||
            bytes[2] != 0x4C.toByte() || bytes[3] != 0x46.toByte()) {
            return ElfParseResult(
                ElfHeader("", "", 0, "", "", "", "", "", 0, 0, 0),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", "",
                "Not an ELF file (bad magic bytes)"
            )
        }

        val elfClass    = bytes[4].toInt()  // 1=32-bit, 2=64-bit
        val dataEnc     = bytes[5].toInt()  // 1=LE, 2=BE
        val elfVer      = bytes[6].toInt()
        val osAbi       = bytes[7].toInt()
        val order       = if (dataEnc == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val is64        = elfClass == 2

        fun buf(off: Int, len: Int) = ByteBuffer.wrap(bytes, off, len).order(order)
        fun u16(off: Int) = buf(off, 2).short.toInt() and 0xFFFF
        fun u32(off: Int) = buf(off, 4).int.toLong() and 0xFFFFFFFFL
        fun u64(off: Int) = buf(off, 8).long

        // ELF header fields
        val eType  = u16(16)
        val eMach  = u16(18)
        val eVer   = u32(20).toInt()

        val (eEntry, ePhOff, eShOff, eFlags, eEhSz, ePhEntSz, ePhNum, eShEntSz, eShNum, eShStrNdx) =
            if (is64) listOf(
                u64(24), u64(32), u64(40), u32(48).toLong(),
                u16(52).toLong(), u16(54).toLong(), u16(56).toLong(),
                u16(58).toLong(), u16(60).toLong(), u16(62).toLong()
            ) else listOf(
                u32(24), u32(28), u32(32), u32(36),
                u16(40).toLong(), u16(42).toLong(), u16(44).toLong(),
                u16(46).toLong(), u16(48).toLong(), u16(50).toLong()
            )

        val header = ElfHeader(
            elfClass       = if (is64) "ELF64" else "ELF32",
            dataEncoding   = if (dataEnc == 1) "Little-Endian (LSB)" else "Big-Endian (MSB)",
            elfVersion     = elfVer,
            osAbi          = osAbiStr(osAbi),
            fileType       = fileTypeStr(eType),
            machine        = machineStr(eMach),
            entryPoint     = "0x${eEntry.toString(16).uppercase()}",
            flags          = "0x${eFlags.toString(16).uppercase()}",
            sectionCount   = eShNum.toInt(),
            programHeaderCount = ePhNum.toInt(),
            shStrNdx       = eShStrNdx.toInt(),
        )

        // ── Section headers ──
        val sections = mutableListOf<ElfSection>()
        val shEntSz  = eShEntSz.toInt().let { if (it == 0) if (is64) 64 else 40 else it }
        val shOff    = eShOff

        // Load section name string table first
        val shStrBytes: ByteArray = if (eShStrNdx > 0 && eShStrNdx < eShNum) {
            val strShOff = (shOff + eShStrNdx * shEntSz).toInt()
            if (strShOff + shEntSz <= bytes.size) {
                val strSecOff = if (is64) u64(strShOff + 24) else u32(strShOff + 16)
                val strSecSz  = if (is64) u64(strShOff + 32) else u32(strShOff + 20)
                if (strSecOff > 0 && strSecOff + strSecSz <= bytes.size && strSecSz < 1024 * 1024) {
                    bytes.copyOfRange(strSecOff.toInt(), (strSecOff + strSecSz).toInt())
                } else ByteArray(0)
            } else ByteArray(0)
        } else ByteArray(0)

        try {
            for (i in 0 until minOf(eShNum.toInt(), 1000)) {
                val off = (shOff + i * shEntSz).toInt()
                if (off + shEntSz > bytes.size) break
                val nameIdx  = u32(off).toInt()
                val secType  = if (is64) u64(off + 4) else u32(off + 4)
                val secFlags = if (is64) u64(off + 8) else u32(off + 8)
                val secAddr  = if (is64) u64(off + 16) else u32(off + 12)
                val secOff   = if (is64) u64(off + 24) else u32(off + 16)
                val secSz    = if (is64) u64(off + 32) else u32(off + 20)
                val lnk      = if (is64) u32(off + 40).toInt() else u32(off + 24).toInt()
                val inf      = if (is64) u32(off + 44).toInt() else u32(off + 28).toInt()
                val align    = if (is64) u64(off + 48) else u32(off + 32)
                val entSz    = if (is64) u64(off + 56) else u32(off + 36)
                val name     = readString(shStrBytes, nameIdx)
                sections += ElfSection(
                    name = name,
                    type = sectionTypeStr(secType),
                    flags = sectionFlagsStr(secFlags),
                    address = "0x${secAddr.toString(16).uppercase()}",
                    offset = secOff,
                    size = secSz,
                    link = lnk,
                    info = inf,
                    alignment = align,
                    entSize = entSz,
                )
            }
        } catch (_: Exception) {}

        // ── Program headers (segments) ──
        val segments = mutableListOf<ElfSegment>()
        val phEntSz = ePhEntSz.toInt().let { if (it == 0) if (is64) 56 else 32 else it }
        val phOff   = ePhOff

        try {
            for (i in 0 until minOf(ePhNum.toInt(), 500)) {
                val off = (phOff + i * phEntSz).toInt()
                if (off + phEntSz > bytes.size) break
                val segType  = u32(off)
                val (segFlags, segOffset, segVaddr, segPaddr, segFileSz, segMemSz, segAlign) =
                    if (is64) listOf(
                        u32(off + 4), u64(off + 8), u64(off + 16),
                        u64(off + 24), u64(off + 32), u64(off + 40), u64(off + 48)
                    ) else listOf(
                        u32(off + 24), u32(off + 4), u32(off + 8),
                        u32(off + 12), u32(off + 16), u32(off + 20), u32(off + 28)
                    )
                segments += ElfSegment(
                    type = segTypeStr(segType),
                    flags = segFlagsStr(segFlags),
                    offset = segOffset,
                    vaddr = "0x${segVaddr.toString(16).uppercase()}",
                    paddr = "0x${segPaddr.toString(16).uppercase()}",
                    fileSize = segFileSz,
                    memSize = segMemSz,
                    alignment = segAlign,
                )
            }
        } catch (_: Exception) {}

        // ── String tables needed for symbol names ──
        // Find .strtab and .dynstr sections
        fun sectionData(secName: String): ByteArray? {
            val sec = sections.firstOrNull { it.name == secName } ?: return null
            if (sec.offset <= 0 || sec.size <= 0 || sec.offset + sec.size > bytes.size) return null
            if (sec.size > 8 * 1024 * 1024) return null // cap at 8MB
            return bytes.copyOfRange(sec.offset.toInt(), (sec.offset + sec.size).toInt())
        }

        val strtab  = sectionData(".strtab")
        val dynstr  = sectionData(".dynstr")

        // ── Symbols (.symtab + .dynsym) ──
        val symbols = mutableListOf<ElfSymbol>()
        val symEntSz = if (is64) 24 else 16

        fun parseSym(secName: String, strData: ByteArray?, isDynamic: Boolean) {
            val sec = sections.firstOrNull { it.name == secName } ?: return
            if (sec.offset <= 0 || sec.size <= 0) return
            val count = (sec.size / symEntSz).toInt()
            for (i in 0 until minOf(count, 50000)) {
                val off = (sec.offset + i * symEntSz).toInt()
                if (off + symEntSz > bytes.size) break
                val nameIdx: Int
                val value: Long
                val size: Long
                val info: Int
                val other: Int
                val shndx: Int
                if (is64) {
                    nameIdx = u32(off).toInt()
                    info    = bytes[off + 4].toInt() and 0xFF
                    other   = bytes[off + 5].toInt() and 0xFF
                    shndx   = u16(off + 6)
                    value   = u64(off + 8)
                    size    = u64(off + 16)
                } else {
                    nameIdx = u32(off).toInt()
                    value   = u32(off + 4)
                    size    = u32(off + 8)
                    info    = bytes[off + 12].toInt() and 0xFF
                    other   = bytes[off + 13].toInt() and 0xFF
                    shndx   = u16(off + 14)
                }
                val name = if (strData != null) readString(strData, nameIdx) else ""
                if (name.isEmpty() && value == 0L) continue
                symbols += ElfSymbol(
                    name       = name.ifEmpty { "<no name>" },
                    value      = "0x${value.toString(16).uppercase()}",
                    size       = size,
                    type       = symTypeStr(info),
                    binding    = symBindStr(info),
                    visibility = symVisStr(other),
                    sectionIndex = symSectionStr(shndx),
                    isDynamic  = isDynamic,
                )
            }
        }

        parseSym(".symtab", strtab, isDynamic = false)
        parseSym(".dynsym", dynstr, isDynamic = true)

        // ── Dynamic section ──
        val dynEntries  = mutableListOf<ElfDynEntry>()
        val neededLibs  = mutableListOf<String>()
        var soName      = ""
        var rpath       = ""
        val dynEntSz    = if (is64) 16 else 8

        val dynSec = sections.firstOrNull { it.name == ".dynamic" }
        if (dynSec != null && dynSec.offset > 0 && dynSec.size > 0) {
            val count = (dynSec.size / dynEntSz).toInt()
            for (i in 0 until minOf(count, 2000)) {
                val off = (dynSec.offset + i * dynEntSz).toInt()
                if (off + dynEntSz > bytes.size) break
                val tag = if (is64) u64(off) else u32(off)
                val valOrPtr = if (is64) u64(off + 8) else u32(off + 4)
                if (tag == 0L) break
                val tagStr = dynTagStr(tag)
                val valStr: String
                when (tag) {
                    1L  -> { // DT_NEEDED
                        val lib = if (dynstr != null) readString(dynstr, valOrPtr.toInt()) else "0x${valOrPtr.toString(16)}"
                        neededLibs += lib
                        valStr = lib
                    }
                    14L -> { // DT_SONAME
                        soName = if (dynstr != null) readString(dynstr, valOrPtr.toInt()) else "0x${valOrPtr.toString(16)}"
                        valStr = soName
                    }
                    15L, 29L -> { // DT_RPATH / DT_RUNPATH
                        val r = if (dynstr != null) readString(dynstr, valOrPtr.toInt()) else "0x${valOrPtr.toString(16)}"
                        if (rpath.isEmpty()) rpath = r
                        valStr = r
                    }
                    else -> valStr = "0x${valOrPtr.toString(16).uppercase()}"
                }
                dynEntries += ElfDynEntry(tagStr, valStr)
            }
        }

        return ElfParseResult(
            header = header,
            sections = sections,
            segments = segments,
            symbols = symbols,
            dynEntries = dynEntries,
            neededLibs = neededLibs,
            soName = soName,
            rpath = rpath,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val ELF_BG      = Color(0xFF1E1E1E)
private val ELF_SURFACE = Color(0xFF252526)
private val ELF_ACCENT  = Color(0xFF569CD6)
private val ELF_GREEN   = Color(0xFF4EC9B0)
private val ELF_YELLOW  = Color(0xFFDCDCAA)
private val ELF_MUTED   = Color(0xFF858585)
private val ELF_TEXT    = Color(0xFFD4D4D4)
private val ELF_RED     = Color(0xFFF44747)
private val ELF_ORANGE  = Color(0xFFCE9178)
private val ELF_PURPLE  = Color(0xFFC586C0)

@Composable
fun ElfViewerDialog(file: File, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<ElfParseResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    var symbolFilter by remember { mutableStateOf("") }
    var sectionFilter by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        loading = true
        result = withContext(Dispatchers.IO) { ElfParser.parse(file) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(10.dp),
            color = ELF_BG,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Title bar ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(ELF_SURFACE)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ELF_TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (result != null && !loading) {
                            val r = result!!
                            if (r.error == null) {
                                Text(
                                    "${r.header.elfClass} · ${r.header.machine} · ${r.header.fileType.substringBefore(" ")} · ${r.sections.size} sections · ${r.symbols.size} symbols",
                                    fontSize = 11.sp, color = ELF_MUTED,
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = ELF_MUTED)
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = ELF_ACCENT)
                            Text("Parsing ELF…", fontSize = 13.sp, color = ELF_MUTED)
                        }
                    }
                    return@Surface
                }

                val r = result
                if (r == null || r.error != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(r?.error ?: "Parse failed", color = ELF_RED, fontSize = 13.sp)
                    }
                    return@Surface
                }

                // ── Tab row ──
                val tabs = listOf("HEADER", "SECTIONS", "SEGMENTS", "SYMBOLS", "DYNAMIC")
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ELF_SURFACE,
                    contentColor = ELF_ACCENT,
                    edgePadding = 0.dp,
                ) {
                    tabs.forEachIndexed { idx, label ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        )
                    }
                }

                when (selectedTab) {
                    // ── HEADER ──
                    0 -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ElfInfoRow("Class", r.header.elfClass)
                        ElfInfoRow("Encoding", r.header.dataEncoding)
                        ElfInfoRow("Version", r.header.elfVersion.toString())
                        ElfInfoRow("OS/ABI", r.header.osAbi)
                        ElfInfoRow("File Type", r.header.fileType)
                        ElfInfoRow("Machine", r.header.machine)
                        ElfInfoRow("Entry Point", r.header.entryPoint)
                        ElfInfoRow("Flags", r.header.flags)
                        ElfInfoRow("Sections", r.header.sectionCount.toString())
                        ElfInfoRow("Segments", r.header.programHeaderCount.toString())
                        if (r.soName.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            ElfSectionHdr("SHARED LIBRARY INFO")
                            ElfInfoRow("SONAME", r.soName)
                        }
                        if (r.rpath.isNotEmpty()) ElfInfoRow("RPATH/RUNPATH", r.rpath)
                        if (r.neededLibs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            ElfSectionHdr("NEEDED LIBRARIES (${r.neededLibs.size})")
                            r.neededLibs.forEach { lib ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(ELF_SURFACE, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(lib, fontSize = 12.sp, color = ELF_ORANGE, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }

                    // ── SECTIONS ──
                    1 -> Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = sectionFilter,
                            onValueChange = { sectionFilter = it },
                            placeholder = { Text("Filter sections…", fontSize = 12.sp, color = ELF_MUTED) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = ELF_MUTED) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ELF_ACCENT, unfocusedBorderColor = ELF_MUTED,
                                focusedTextColor = ELF_TEXT, unfocusedTextColor = ELF_TEXT, cursorColor = ELF_ACCENT,
                            ),
                        )
                        val filtered = if (sectionFilter.isBlank()) r.sections
                                       else r.sections.filter { it.name.contains(sectionFilter, ignoreCase = true) || it.type.contains(sectionFilter, ignoreCase = true) }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered) { sec ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .background(ELF_SURFACE, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(sec.name.ifEmpty { "<unnamed>" }, fontSize = 13.sp, color = ELF_GREEN, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                        Text(sec.type, fontSize = 11.sp, color = ELF_YELLOW, fontFamily = FontFamily.Monospace)
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                        Text("offset=${sec.offset}  size=${"%,d".format(sec.size)}  addr=${sec.address}  align=${sec.alignment}", fontSize = 10.sp, color = ELF_MUTED, fontFamily = FontFamily.Monospace)
                                    }
                                    if (sec.flags != "none") {
                                        Text(sec.flags, fontSize = 10.sp, color = ELF_PURPLE)
                                    }
                                }
                            }
                        }
                    }

                    // ── SEGMENTS ──
                    2 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        item { Spacer(Modifier.height(8.dp)) }
                        items(r.segments) { seg ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(ELF_SURFACE, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(seg.type, fontSize = 13.sp, color = ELF_ACCENT, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                    Text(seg.flags, fontSize = 12.sp, color = when {
                                        seg.flags.contains("X") -> ELF_RED
                                        seg.flags.contains("W") -> ELF_YELLOW
                                        else -> ELF_MUTED
                                    }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    Text("offset=${seg.offset}  filesz=${"%,d".format(seg.fileSize)}  memsz=${"%,d".format(seg.memSize)}  align=${seg.alignment}", fontSize = 10.sp, color = ELF_MUTED, fontFamily = FontFamily.Monospace)
                                }
                                Text("vaddr=${seg.vaddr}  paddr=${seg.paddr}", fontSize = 10.sp, color = ELF_MUTED, fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (r.segments.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No program headers", color = ELF_MUTED, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // ── SYMBOLS ──
                    3 -> Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = symbolFilter,
                            onValueChange = { symbolFilter = it },
                            placeholder = { Text("Filter symbols…", fontSize = 12.sp, color = ELF_MUTED) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = ELF_MUTED) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ELF_ACCENT, unfocusedBorderColor = ELF_MUTED,
                                focusedTextColor = ELF_TEXT, unfocusedTextColor = ELF_TEXT, cursorColor = ELF_ACCENT,
                            ),
                        )
                        val filtered = r.symbols
                            .filter { symbolFilter.isBlank() || it.name.contains(symbolFilter, ignoreCase = true) }
                            .take(10000)
                        Text(
                            "  ${"%,d".format(filtered.size)} symbols (${r.symbols.count { it.isDynamic }} dynamic, ${r.symbols.count { !it.isDynamic }} static)${if (r.symbols.size > 10000) " — capped at 10,000" else ""}",
                            fontSize = 10.sp, color = ELF_MUTED, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered) { sym ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            sym.name,
                                            fontSize = 12.sp,
                                            color = when {
                                                sym.type == "FUNC" -> ELF_ACCENT
                                                sym.type == "OBJECT" -> ELF_GREEN
                                                else -> ELF_TEXT
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(sym.type, fontSize = 10.sp, color = ELF_MUTED)
                                            Text(sym.binding, fontSize = 10.sp, color = ELF_MUTED)
                                            Text("§${sym.sectionIndex}", fontSize = 10.sp, color = ELF_MUTED)
                                            if (sym.isDynamic) Text("DYN", fontSize = 10.sp, color = ELF_PURPLE)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(sym.value, fontSize = 10.sp, color = ELF_YELLOW, fontFamily = FontFamily.Monospace)
                                        if (sym.size > 0) Text("${sym.size}B", fontSize = 10.sp, color = ELF_MUTED)
                                    }
                                }
                                HorizontalDivider(color = ELF_SURFACE, thickness = 0.3.dp)
                            }
                            if (filtered.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        Text(if (symbolFilter.isBlank()) "No symbols found (stripped binary)" else "No symbols match \"$symbolFilter\"", color = ELF_MUTED, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── DYNAMIC ──
                    4 -> if (r.dynEntries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No .dynamic section found", color = ELF_MUTED, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            item { Spacer(Modifier.height(8.dp)) }
                            items(r.dynEntries) { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(ELF_SURFACE, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        entry.tag,
                                        fontSize = 11.sp,
                                        color = ELF_YELLOW,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(130.dp),
                                    )
                                    Text(
                                        entry.value,
                                        fontSize = 11.sp,
                                        color = when (entry.tag) {
                                            "NEEDED" -> ELF_ORANGE
                                            "SONAME" -> ELF_GREEN
                                            "RPATH", "RUNPATH" -> ELF_PURPLE
                                            else -> ELF_TEXT
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElfInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ELF_SURFACE, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = ELF_MUTED, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = ELF_TEXT, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ElfSectionHdr(label: String) {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = ELF_ACCENT,
        letterSpacing = 1.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
