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

data class DisasmFunction(
    val name: String,
    val address: Long,
    val size: Long,
    val arch: String,
)

data class DisasmInstruction(
    val address: Long,
    val rawBytes: ByteArray,
    val mnemonic: String,
    val operands: String,
    val comment: String = "",
)

data class DisasmResult(
    val arch: String,
    val functions: List<DisasmFunction>,
    val instructions: List<DisasmInstruction>,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Pure-Kotlin ARM Thumb-2 / ARM32 opcode decoder (subset — enough for real UX)
// This is a best-effort decoder; unknown opcodes show raw bytes as ".byte"
// ─────────────────────────────────────────────────────────────────────────────

private object ArmThumbDecoder {

    private val THUMB_BRANCHES = setOf(0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8,
        0xD9, 0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF)

    private val COND = listOf("eq","ne","cs","cc","mi","pl","vs","vc","hi","ls","ge","lt","gt","le","","")

    /** Decode a Thumb-2 instruction stream. Returns a list of (offset, mnemonic, operands, rawBytes). */
    fun decode(bytes: ByteArray, baseAddr: Long, limit: Int = 1000): List<DisasmInstruction> {
        val out = mutableListOf<DisasmInstruction>()
        var i = 0
        var count = 0
        while (i + 1 < bytes.size && count < limit) {
            val hw1 = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            val addr = baseAddr + i

            // Check for 32-bit Thumb-2 (hw1[15:11] in {0b11101, 0b11110, 0b11111})
            val top5 = hw1 ushr 11
            val is32 = top5 in 0b11101..0b11111

            if (is32 && i + 3 < bytes.size) {
                val hw2 = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                val raw = bytes.copyOfRange(i, i + 4)
                val (mn, ops) = decodeThumb32(hw1, hw2, addr)
                out.add(DisasmInstruction(addr, raw, mn, ops))
                i += 4
            } else {
                val raw = bytes.copyOfRange(i, i + 2)
                val (mn, ops) = decodeThumb16(hw1, addr)
                out.add(DisasmInstruction(addr, raw, mn, ops))
                i += 2
            }
            count++
        }
        return out
    }

    private fun decodeThumb16(w: Int, pc: Long): Pair<String, String> {
        val op10 = w ushr 6
        return when {
            // PUSH / POP
            w and 0xFE00 == 0xB400 -> {
                val regs = (0..7).filter { w and (1 shl it) != 0 }.map { "r$it" }
                val lr   = if (w and 0x100 != 0) listOf("lr") else emptyList()
                "push" to "{${(regs + lr).joinToString(", ")}}"
            }
            w and 0xFE00 == 0xBC00 -> {
                val regs = (0..7).filter { w and (1 shl it) != 0 }.map { "r$it" }
                val pclr = if (w and 0x100 != 0) listOf("pc") else emptyList()
                "pop" to "{${(regs + pclr).joinToString(", ")}}"
            }
            // MOV imm8
            w and 0xF800 == 0x2000 -> {
                val rd  = (w ushr 8) and 0x7
                val imm = w and 0xFF
                "movs" to "r$rd, #$imm"
            }
            // ADD Rd, Rn, imm3
            w and 0xFE00 == 0x1C00 -> {
                val rd  = w and 7; val rn = (w ushr 3) and 7; val imm = (w ushr 6) and 7
                "adds" to "r$rd, r$rn, #$imm"
            }
            // LDR literal
            w and 0xF800 == 0x4800 -> {
                val rt  = (w ushr 8) and 7
                val imm = (w and 0xFF) shl 2
                val tgt = (pc and 0xFFFFFFFC.toLong()) + 4L + imm
                "ldr" to "r$rt, [pc, #$imm]  ; = 0x${tgt.toString(16)}"
            }
            // BX / BLX register
            w and 0xFF87 == 0x4700 -> {
                val rm = (w ushr 3) and 0xF
                "bx" to "r$rm"
            }
            w and 0xFF87 == 0x4780 -> {
                val rm = (w ushr 3) and 0xF
                "blx" to "r$rm"
            }
            // ADD reg
            w and 0xFF00 == 0x4400 -> {
                val dn = ((w ushr 7) and 1); val rm = (w ushr 3) and 0xF; val rdn = ((dn shl 3) or (w and 7))
                "add" to "r$rdn, r$rm"
            }
            // CMP imm8
            w and 0xF800 == 0x2800 -> {
                val rn  = (w ushr 8) and 7; val imm = w and 0xFF
                "cmp" to "r$rn, #$imm"
            }
            // B<cond> imm8
            w and 0xF000 == 0xD000 -> {
                val cond = (w ushr 8) and 0xF
                var imm  = (w and 0xFF).toByte().toInt() shl 1
                val tgt  = pc + 4 + imm
                "b${COND[cond]}" to "0x${tgt.toString(16)}"
            }
            // B unconditional imm11
            w and 0xF800 == 0xE000 -> {
                var imm = (w and 0x7FF)
                if (imm and 0x400 != 0) imm = imm or 0xFFFFF800.toInt()
                val tgt = pc + 4 + (imm shl 1)
                "b" to "0x${tgt.toString(16)}"
            }
            // STR Rt, [Rn, imm5]
            w and 0xF800 == 0x6000 -> {
                val rt  = w and 7; val rn = (w ushr 3) and 7; val imm = ((w ushr 6) and 0x1F) shl 2
                "str" to "r$rt, [r$rn, #$imm]"
            }
            // LDR Rt, [Rn, imm5]
            w and 0xF800 == 0x6800 -> {
                val rt  = w and 7; val rn = (w ushr 3) and 7; val imm = ((w ushr 6) and 0x1F) shl 2
                "ldr" to "r$rt, [r$rn, #$imm]"
            }
            // NOP
            w == 0xBF00 -> "nop" to ""
            // MOVS rd, rm (shift 0)
            w and 0xFFC0 == 0x0000 -> {
                val rd = w and 7; val rm = (w ushr 3) and 7
                "movs" to "r$rd, r$rm"
            }
            // LSLS rd, rm, imm5
            w and 0xF800 == 0x0000 -> {
                val rd = w and 7; val rm = (w ushr 3) and 7; val imm = (w ushr 6) and 0x1F
                "lsls" to "r$rd, r$rm, #$imm"
            }
            // SUB imm3
            w and 0xFE00 == 0x1E00 -> {
                val rd = w and 7; val rn = (w ushr 3) and 7; val imm = (w ushr 6) and 7
                "subs" to "r$rd, r$rn, #$imm"
            }
            // ANDS, ORRS, EORS, etc (DP ops)
            w and 0xFC00 == 0x4000 -> {
                val op  = (w ushr 6) and 0xF
                val rd  = w and 7; val rm = (w ushr 3) and 7
                val mn  = listOf("ands","eors","lsls","lsrs","asrs","adcs","sbcs","rors","tst","rsbs","cmp","cmn","orrs","muls","bics","mvns")[op]
                mn to "r$rd, r$rm"
            }
            else -> ".short" to "0x${w.toString(16).padStart(4, '0')}"
        }
    }

    private fun decodeThumb32(hw1: Int, hw2: Int, pc: Long): Pair<String, String> {
        val op1 = (hw1 ushr 11) and 3
        // BL imm24
        if (hw1 and 0xF800 == 0xF000 && hw2 and 0xD000 == 0xD000) {
            val s = (hw1 ushr 10) and 1
            val imm10 = hw1 and 0x3FF
            val j1 = (hw2 ushr 13) and 1; val j2 = (hw2 ushr 11) and 1
            val imm11 = hw2 and 0x7FF
            val i1 = if ((j1 xor s) == 0) 1 else 0; val i2 = if ((j2 xor s) == 0) 1 else 0
            val imm = ((s shl 24) or (i1 shl 23) or (i2 shl 22) or (imm10 shl 12) or (imm11 shl 1))
            val signExt = if (s != 0) imm.toLong() or 0xFE000000L.toLong() else imm.toLong()
            val tgt = pc + 4 + signExt
            return "bl" to "0x${tgt.toString(16)}"
        }
        // MOV imm16 (MOVW)
        if (hw1 and 0xFBF0 == 0xF240) {
            val rd = (hw2 ushr 8) and 0xF
            val imm = ((hw1 and 0xF) shl 12) or ((hw1 ushr 10 and 1) shl 11) or ((hw2 ushr 12 and 0x7) shl 8) or (hw2 and 0xFF)
            return "movw" to "r$rd, #$imm  ; 0x${imm.toString(16)}"
        }
        return ".word" to "0x${hw1.toString(16).padStart(4,'0')}${hw2.toString(16).padStart(4,'0')}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ELF → extract .text section → decode
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun disassembleElfFile(file: File): DisasmResult = withContext(Dispatchers.IO) {
    try {
        val bytes = file.readBytes()
        if (bytes.size < 52) return@withContext DisasmResult("?", emptyList(), emptyList(), "File too small")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // ELF ident
        val magic = bytes.slice(0..3).map { it.toInt() and 0xFF }
        if (magic != listOf(0x7F, 0x45, 0x4C, 0x46))
            return@withContext DisasmResult("?", emptyList(), emptyList(), "Not an ELF file")

        val elfClass = bytes[4].toInt()
        val is64 = elfClass == 2
        val arch = when (val em = buf.also { it.position(18) }.short.toInt() and 0xFFFF) {
            0x28  -> "ARM Thumb-2"
            0xB7  -> "AArch64"
            0x03  -> "x86"
            0x3E  -> "x86_64"
            0xF3  -> "RISC-V"
            else  -> "arch 0x${em.toString(16)}"
        }
        val isArm = arch == "ARM Thumb-2"

        // ELF32 section header table
        if (is64) return@withContext DisasmResult(arch, emptyList(), emptyList(), "ELF64 disassembly not yet supported (use ELF Viewer for headers/symbols)")

        buf.position(32); val shoff  = buf.int.toLong()
        buf.position(46); val shentsize = buf.short.toInt() and 0xFFFF
        buf.position(48); val shnum  = buf.short.toInt() and 0xFFFF
        buf.position(50); val shstrndx = buf.short.toInt() and 0xFFFF

        if (shoff == 0L || shnum == 0) return@withContext DisasmResult(arch, emptyList(), emptyList(), "No section headers")

        // Read shstrtab
        val shstrOff = (shoff + shstrndx.toLong() * shentsize).toInt()
        if (shstrOff + 40 > bytes.size) return@withContext DisasmResult(arch, emptyList(), emptyList(), "shstrtab out of range")
        val shstrSecOff = ByteBuffer.wrap(bytes, shstrOff + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val shstrSecSize= ByteBuffer.wrap(bytes, shstrOff + 20, 4).order(ByteOrder.LITTLE_ENDIAN).int

        fun secName(nameOff: Int): String {
            val abs = shstrSecOff + nameOff
            if (abs < 0 || abs >= bytes.size) return ""
            val end = bytes.indexOf(0, abs).let { if (it < 0) bytes.size else it }
            return String(bytes, abs, (end - abs).coerceAtLeast(0))
        }

        // Find .text and symbol sections
        data class SecInfo(val name: String, val off: Int, val size: Int, val addr: Long)
        val sections = (0 until shnum).mapNotNull { idx ->
            val secOff = (shoff + idx.toLong() * shentsize).toInt()
            if (secOff + 40 > bytes.size) return@mapNotNull null
            val nameOff = ByteBuffer.wrap(bytes, secOff, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val secType = ByteBuffer.wrap(bytes, secOff + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val secAddr = ByteBuffer.wrap(bytes, secOff + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val secFileOff = ByteBuffer.wrap(bytes, secOff + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val secSize = ByteBuffer.wrap(bytes, secOff + 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
            SecInfo(secName(nameOff), secFileOff, secSize, secAddr)
        }

        val textSec = sections.firstOrNull { it.name == ".text" }
            ?: sections.firstOrNull { it.name.startsWith(".text") }
            ?: return@withContext DisasmResult(arch, emptyList(), emptyList(), "No .text section found")

        val textBytes = bytes.copyOfRange(
            textSec.off.coerceIn(0, bytes.size),
            (textSec.off + textSec.size).coerceIn(0, bytes.size)
        )

        // Decode instructions
        val limit = 2000
        val instructions = if (isArm) {
            ArmThumbDecoder.decode(textBytes, textSec.addr, limit)
        } else {
            // For non-ARM: raw byte groups of 4 with ".word" labels
            (0 until textBytes.size / 4).take(limit).map { idx ->
                val off = idx * 4
                val raw = textBytes.copyOfRange(off, off + 4)
                val w = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int
                DisasmInstruction(textSec.addr + off, raw, ".word", "0x${w.toString(16).padStart(8,'0')}", "($arch — ARM decoder only)")
            }
        }

        // Build function list from .symtab
        val symtabSec = sections.firstOrNull { it.name == ".symtab" }
        val strtabSec = sections.firstOrNull { it.name == ".strtab" }
        val functions = if (symtabSec != null && strtabSec != null) {
            (0 until symtabSec.size / 16).mapNotNull { i ->
                val off = symtabSec.off + i * 16
                if (off + 16 > bytes.size) return@mapNotNull null
                val nameOff  = ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val addr     = ByteBuffer.wrap(bytes, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val size     = ByteBuffer.wrap(bytes, off + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                val info     = bytes[off + 12].toInt() and 0xFF
                val symType  = info and 0xF
                val symBind  = info ushr 4
                if (symType != 2 /* STT_FUNC */) return@mapNotNull null
                val nameAbs  = strtabSec.off + nameOff
                if (nameAbs < 0 || nameAbs >= bytes.size) return@mapNotNull null
                val nameEnd  = bytes.indexOf(0, nameAbs).let { if (it < 0) bytes.size else it }
                val symName  = String(bytes, nameAbs, (nameEnd - nameAbs).coerceAtLeast(0))
                if (symName.isBlank()) return@mapNotNull null
                DisasmFunction(symName, addr, size, arch)
            }.sortedBy { it.address }
        } else emptyList()

        DisasmResult(arch, functions, instructions)
    } catch (e: Exception) {
        DisasmResult("?", emptyList(), emptyList(), "Parse error: ${e.message}")
    }
}

// Helper: ByteArray.indexOf(byte, start)
private fun ByteArray.indexOf(b: Byte, start: Int): Int {
    for (i in start until size) if (this[i] == b) return i
    return -1
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val DBg      = Color(0xFF1E1E1E)
private val DCard    = Color(0xFF252526)
private val DText    = Color(0xFFD4D4D4)
private val DDim     = Color(0xFF808080)
private val DAccent  = Color(0xFF007ACC)
private val DGreen   = Color(0xFF4EC9B0)
private val DYellow  = Color(0xFFE5C07B)
private val DBlue    = Color(0xFF569CD6)
private val DPurple  = Color(0xFFC586C0)
private val DDivider = Color(0xFF3C3C3C)

@Composable
fun DisassemblyViewerDialog(file: File, onDismiss: () -> Unit) {
    var result      by remember { mutableStateOf<DisasmResult?>(null) }
    var loading     by remember { mutableStateOf(true) }
    var activeFunc  by remember { mutableStateOf<DisasmFunction?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val listState   = rememberLazyListState()

    LaunchedEffect(file.absolutePath) {
        loading = true
        result = disassembleElfFile(file)
        loading = false
    }

    val r = result

    // Compute visible instructions (filtered by function address range, or all)
    val visibleInstructions = remember(r, activeFunc, searchQuery) {
        if (r == null) return@remember emptyList()
        var insns = r.instructions
        if (activeFunc != null) {
            val fn = activeFunc!!
            insns = insns.filter { it.address >= fn.address && it.address < fn.address + fn.size.coerceAtLeast(16) }
        }
        if (searchQuery.isNotBlank()) {
            insns = insns.filter { it.mnemonic.contains(searchQuery, ignoreCase = true) || it.operands.contains(searchQuery, ignoreCase = true) }
        }
        insns
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).background(DCard, RoundedCornerShape(8.dp))
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2D2D2D), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Disassembly", color = DAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(file.name + (r?.let { " • ${it.arch}" } ?: ""), color = DText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = DDim, modifier = Modifier.size(16.dp))
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = DAccent)
                        Spacer(Modifier.height(12.dp))
                        Text("Disassembling…", color = DDim, fontSize = 12.sp)
                    }
                }
                return@Column
            }

            if (r?.error != null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Error: ${r.error}", color = Color(0xFFFF5F5F), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                return@Column
            }

            Row(Modifier.fillMaxSize()) {
                // Left: function list
                Column(Modifier.width(160.dp).fillMaxHeight().background(DBg)) {
                    Text("Functions (${r?.functions?.size ?: 0})", color = DDim, fontSize = 10.sp, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = DDivider)
                    // "All" option
                    Row(
                        Modifier.fillMaxWidth().background(if (activeFunc == null) Color(0xFF094771) else Color.Transparent)
                            .clickable { activeFunc = null }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("All instructions", color = if (activeFunc == null) DText else DDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = DDivider, thickness = 0.5.dp)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(r?.functions ?: emptyList()) { fn ->
                            val active = fn == activeFunc
                            Column(
                                Modifier.fillMaxWidth().background(if (active) Color(0xFF094771) else Color.Transparent)
                                    .clickable { activeFunc = fn }.padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Text(fn.name, color = if (active) DText else DGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("0x${fn.address.toString(16)} · ${fn.size}B", color = DDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            HorizontalDivider(color = DDivider, thickness = 0.3.dp)
                        }
                        if (r?.functions.isNullOrEmpty()) {
                            item { Text("No symbols", color = DDim, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                        }
                    }
                }

                Box(Modifier.width(1.dp).fillMaxHeight().background(DDivider))

                // Right: instruction listing
                Column(Modifier.fillMaxSize()) {
                    // Search bar
                    Row(
                        Modifier.fillMaxWidth().background(DCard).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null, tint = DDim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f).height(36.dp),
                            placeholder = { Text("Search mnemonic / operand…", fontSize = 10.sp, color = DDim) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DText),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DAccent, unfocusedBorderColor = DDivider,
                                focusedTextColor = DText, unfocusedTextColor = DText,
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${visibleInstructions.size} insns", color = DDim, fontSize = 10.sp)
                    }
                    HorizontalDivider(color = DDivider)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().background(DBg).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        items(visibleInstructions) { insn ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Address
                                Text(
                                    "0x${insn.address.toString(16).padStart(8, '0')}",
                                    color = DDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(80.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                // Raw bytes
                                Text(
                                    insn.rawBytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }.padEnd(12),
                                    color = Color(0xFF808080),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(80.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                // Mnemonic
                                val mnColor = when {
                                    insn.mnemonic.startsWith("b")  -> DYellow
                                    insn.mnemonic.startsWith("str") || insn.mnemonic.startsWith("ldr") -> DBlue
                                    insn.mnemonic.startsWith("push") || insn.mnemonic.startsWith("pop") -> DPurple
                                    insn.mnemonic.startsWith("mov") -> DGreen
                                    insn.mnemonic.startsWith(".") -> Color(0xFF888888)
                                    else -> DText
                                }
                                Text(
                                    insn.mnemonic.padEnd(8),
                                    color = mnColor,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(72.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                // Operands + comment
                                Text(
                                    insn.operands + if (insn.comment.isNotBlank()) "  ${insn.comment}" else "",
                                    color = if (insn.comment.isNotBlank()) Color(0xFF6A9955) else DText,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        if (visibleInstructions.isEmpty()) {
                            item { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("No instructions", color = DDim, fontSize = 12.sp) } }
                        }
                    }
                }
            }
        }
    }
}
