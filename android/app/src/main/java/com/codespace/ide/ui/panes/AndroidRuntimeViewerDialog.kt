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
import androidx.compose.material.icons.filled.Android
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

// ── Android Runtime Format Viewer: OAT · VDEX · APEX ─────────────────────────
// Pure-Kotlin binary parsers for Android runtime formats.
// OAT: ART ahead-of-time compiled file (ELF wrapper with .rodata OAT magic)
// VDEX: Verified DEX container (VDEX magic + header + embedded DEX files)
// APEX: Android Package (ZIP-based with apex_manifest.pb + apex_payload.img)

// ── Shared models ─────────────────────────────────────────────────────────────
private enum class ArtFormat { OAT, VDEX, APEX, UNKNOWN }

private data class ArtSection(val name: String, val value: String, val sub: List<Pair<String,String>> = emptyList())

private data class ArtFileInfo(
    val format: ArtFormat,
    val formatLabel: String,
    val version: String,
    val architecture: String,
    val sections: List<ArtSection>,
    val dexFiles: List<DexEntry>,
    val rawHeader: List<Pair<String,String>>
)

private data class DexEntry(val name: String, val size: Int, val offset: Int, val checksum: Long)

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun ByteArray.u32le(off: Int): Long =
    ((this[off].toLong() and 0xFF)) or
    ((this[off+1].toLong() and 0xFF) shl 8) or
    ((this[off+2].toLong() and 0xFF) shl 16) or
    ((this[off+3].toLong() and 0xFF) shl 24)

private fun ByteArray.u16le(off: Int): Int =
    ((this[off].toInt() and 0xFF)) or ((this[off+1].toInt() and 0xFF) shl 8)

private fun ByteArray.asciiStr(off: Int, maxLen: Int = 64): String {
    val sb = StringBuilder()
    var i = off
    while (i < size && i < off + maxLen && this[i] != 0.toByte()) {
        sb.append(this[i].toInt().and(0xFF).toChar())
        i++
    }
    return sb.toString()
}

private fun isaName(isa: Int): String = when (isa) {
    0 -> "None"; 1 -> "ARM"; 2 -> "ARM64"; 3 -> "Thumb2"
    4 -> "X86"; 5 -> "X86_64"; 6 -> "MIPS"; 7 -> "MIPS64"
    else -> "ISA($isa)"
}

// ── OAT Parser ────────────────────────────────────────────────────────────────
// OAT is an ELF file. The OAT header lives in the .rodata section.
// Magic: "oat\n" followed by 4-byte version string.
// We find .rodata by scanning ELF section headers.
private fun parseOat(bytes: ByteArray): ArtFileInfo {
    // First verify ELF magic
    require(bytes.size >= 4 && bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()) { "Not an ELF file" }

    val is64  = bytes[4] == 2.toByte()
    val isLE  = bytes[5] == 1.toByte()

    fun u32(off: Int): Long = if (isLE) bytes.u32le(off) else
        ((bytes[off].toLong() and 0xFF) shl 24) or ((bytes[off+1].toLong() and 0xFF) shl 16) or
        ((bytes[off+2].toLong() and 0xFF) shl 8) or (bytes[off+3].toLong() and 0xFF)

    // ELF header offsets
    val shOff: Long; val shEntSize: Int; val shNum: Int; val shStrIdx: Int
    if (is64) {
        shOff     = (bytes.u32le(40) or (bytes.u32le(44) shl 32))
        shEntSize = bytes.u16le(58); shNum = bytes.u16le(60); shStrIdx = bytes.u16le(62)
    } else {
        shOff     = u32(32); shEntSize = bytes.u16le(46); shNum = bytes.u16le(48); shStrIdx = bytes.u16le(50)
    }

    // Find .rodata section — first find the section-name string table offset
    var rodataOffset = -1L; @Suppress("unused") var rodataSize = 0L
    val strtabEntry = (shOff + shStrIdx.toLong() * shEntSize).toInt()
    val strtabOff: Long = if (strtabEntry + shEntSize <= bytes.size) {
        if (is64) bytes.u32le(strtabEntry + 24) else u32(strtabEntry + 16)
    } else 0L

    // Scan sections for .rodata by name
    for (i in 0 until minOf(shNum, 64)) {
        val secBase = (shOff + i.toLong() * shEntSize).toInt()
        if (secBase + shEntSize > bytes.size) break
        val nameOff = u32(secBase).toInt()
        val nameIdx = (strtabOff + nameOff).toInt().coerceIn(0, bytes.size - 1)
        val secName = bytes.asciiStr(nameIdx, 32)
        if (secName == ".rodata") {
            rodataOffset = if (is64) bytes.u32le(secBase + 24) else u32(secBase + 16)
            rodataSize   = if (is64) bytes.u32le(secBase + 32) else u32(secBase + 20)
            break
        }
    }

    // If ELF scan failed, fall back: search for oat\n magic directly
    if (rodataOffset < 0) {
        for (i in 0 until bytes.size - 4) {
            if (bytes[i] == 'o'.code.toByte() && bytes[i+1] == 'a'.code.toByte() &&
                bytes[i+2] == 't'.code.toByte() && bytes[i+3] == '\n'.code.toByte()) {
                rodataOffset = i.toLong(); rodataSize = (bytes.size - i).toLong(); break
            }
        }
    }

    require(rodataOffset >= 0) { "OAT header not found in ELF" }

    val ro = rodataOffset.toInt()
    // OAT header structure (version-dependent, using common subset):
    // magic[4] = "oat\n"
    // version[4] = e.g. "183\0"
    // adler32_checksum[4]
    // instruction_set[4]
    // instruction_set_features_bitmap[4]
    // dex_file_count[4]
    // oat_dex_files_offset[4]  (v131+)
    // executable_offset[4]
    // interpreter_to_interpreter_bridge_offset[4]
    // ... etc

    val magic   = bytes.asciiStr(ro, 4)
    require(magic == "oat\n" || magic.startsWith("oat")) { "OAT magic not found at offset $ro" }
    val version = bytes.asciiStr(ro + 4, 4).trim('\u0000')
    val checksum = bytes.u32le(ro + 8)
    val isa     = bytes.u32le(ro + 12).toInt()
    val isaBitmap = bytes.u32le(ro + 16)
    val dexCount  = bytes.u32le(ro + 20).toInt()
    val execOffset = bytes.u32le(ro + 28)

    val rawHeader = listOf(
        "magic"             to "oat\\n",
        "version"           to version,
        "adler32_checksum"  to "0x%08X".format(checksum),
        "instruction_set"   to isaName(isa),
        "isa_features"      to "0x%08X".format(isaBitmap),
        "dex_file_count"    to dexCount.toString(),
        "executable_offset" to "0x%08X".format(execOffset)
    )

    // Parse dex file entries (after fixed header — size varies by version)
    // Simplified: try to read dex file names from the oat_dex_files region
    val dexEntries = mutableListOf<DexEntry>()
    try {
        var dexPtr = ro + 36  // approximate start of dex entries for modern OAT
        repeat(dexCount.coerceAtMost(50)) {
            if (dexPtr + 8 > bytes.size) return@repeat
            val nameLen = bytes.u32le(dexPtr).toInt(); dexPtr += 4
            if (nameLen <= 0 || nameLen > 512 || dexPtr + nameLen > bytes.size) return@repeat
            val name = String(bytes, dexPtr, nameLen, Charsets.UTF_8).trimEnd('\u0000')
            dexPtr += nameLen
            if (dexPtr + 20 > bytes.size) return@repeat
            val dexOffset  = bytes.u32le(dexPtr).toInt(); dexPtr += 4
            val dexChecksum = bytes.u32le(dexPtr); dexPtr += 4
            val classCount = bytes.u32le(dexPtr).toInt(); dexPtr += 4
            // skip type_lookup_table_offset + dex_sections_layout_offset + lookup_table_offset + class_offsets_pointer + dex_cache_arrays_offset
            dexPtr += 20
            dexEntries += DexEntry(name.ifBlank { "dex[$it]" }, classCount, dexOffset, dexChecksum)
        }
    } catch (_: Exception) {}

    val sections = listOf(
        ArtSection("Format", "OAT (ART Ahead-of-Time)"),
        ArtSection("Version", version),
        ArtSection("Architecture", isaName(isa)),
        ArtSection("DEX files", dexCount.toString()),
        ArtSection("Executable offset", "0x%08X".format(execOffset)),
        ArtSection("Checksum", "0x%08X".format(checksum)),
    )

    return ArtFileInfo(
        format = ArtFormat.OAT,
        formatLabel = "OAT v$version",
        version = version,
        architecture = isaName(isa),
        sections = sections,
        dexFiles = dexEntries,
        rawHeader = rawHeader
    )
}

// ── VDEX Parser ───────────────────────────────────────────────────────────────
// VDEX magic: "vdex" followed by 4-char version
// Header (v10+): magic[4] + version[4] + dex_section_size[4] + verifier_deps_size[4] +
//                bootclasspath_checksums_size[4] + class_loader_context_size[4] + checksum[4]
// Embedded DEX files follow the header.
private fun parseVdex(bytes: ByteArray): ArtFileInfo {
    require(bytes.size >= 8) { "File too small" }
    val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
    require(magic == "vdex") { "Not a VDEX file (magic=$magic)" }
    val version = String(bytes, 4, 4, Charsets.ISO_8859_1).trim('\u0000')
    val _versionNum = version.trimEnd('\u0000').toIntOrNull() ?: 0

    // Header fields vary by version — parse common subset
    var off = 8
    val dexSectionSize   = if (bytes.size > off + 4) bytes.u32le(off).also { off += 4 } else 0L
    val verifierDepsSize = if (bytes.size > off + 4) bytes.u32le(off).also { off += 4 } else 0L
    val bootCpSize       = if (bytes.size > off + 4) bytes.u32le(off).also { off += 4 } else 0L
    val classLoaderCtxSz = if (bytes.size > off + 4) bytes.u32le(off).also { off += 4 } else 0L
    val checksum         = if (bytes.size > off + 4) bytes.u32le(off).also { off += 4 } else 0L

    // Find embedded DEX files (magic: "dex\n")
    val dexEntries = mutableListOf<DexEntry>()
    var scanPos = off
    while (scanPos < bytes.size - 8) {
        if (bytes[scanPos]     == 'd'.code.toByte() &&
            bytes[scanPos + 1] == 'e'.code.toByte() &&
            bytes[scanPos + 2] == 'x'.code.toByte() &&
            bytes[scanPos + 3] == '\n'.code.toByte()) {
            // DEX header: magic[8] + checksum[4] + sha1[20] + file_size[4] + ...
            val dexSize   = if (scanPos + 36 < bytes.size) bytes.u32le(scanPos + 32).toInt() else 0
            val dexCheck  = if (scanPos + 12 < bytes.size) bytes.u32le(scanPos + 8) else 0L
            dexEntries += DexEntry(
                name = "embedded_dex_${dexEntries.size}",
                size = dexSize,
                offset = scanPos,
                checksum = dexCheck
            )
            // Skip to next DEX (at least dexSize bytes forward, min 1)
            scanPos += maxOf(dexSize, 1)
        } else {
            scanPos++
        }
    }

    val rawHeader = listOf(
        "magic"                  to "vdex",
        "version"                to version,
        "dex_section_size"       to "$dexSectionSize",
        "verifier_deps_size"     to "$verifierDepsSize",
        "bootclasspath_checksums_size" to "$bootCpSize",
        "class_loader_context_size" to "$classLoaderCtxSz",
        "checksum"               to "0x%08X".format(checksum),
        "embedded_dex_count"     to dexEntries.size.toString()
    )

    val sections = listOf(
        ArtSection("Format", "VDEX (Verified DEX)"),
        ArtSection("Version", version),
        ArtSection("DEX section size", "$dexSectionSize bytes"),
        ArtSection("Verifier deps size", "$verifierDepsSize bytes"),
        ArtSection("Embedded DEX files", dexEntries.size.toString()),
        ArtSection("Checksum", "0x%08X".format(checksum)),
    )

    return ArtFileInfo(
        format = ArtFormat.VDEX,
        formatLabel = "VDEX v$version",
        version = version,
        architecture = "N/A",
        sections = sections,
        dexFiles = dexEntries,
        rawHeader = rawHeader
    )
}

// ── APEX Parser ───────────────────────────────────────────────────────────────
// APEX is a ZIP file containing:
//   apex_manifest.json or apex_manifest.pb
//   apex_payload.img  (ext4/erofs filesystem image)
//   AndroidManifest.xml (binary AXML)
// We use ZipFile to read the manifest and list contents.
private fun parseApex(file: java.io.File): ArtFileInfo {
    val entries = mutableListOf<Pair<String, String>>()
    @Suppress("unused") var manifestText = ""
    var apexName = ""; var apexVersion = ""; var targetSdk = ""

    try {
        ZipFile(file).use { zip ->
            val allEntries = zip.entries().toList()
            for (e in allEntries) {
                entries += e.name to if (e.size >= 0) formatSz(e.size) else "?"
            }
            // Read apex_manifest.json if present
            val manifestJson = zip.getEntry("apex_manifest.json")
            if (manifestJson != null) {
                val text = zip.getInputStream(manifestJson).bufferedReader().readText()
                manifestText = text.take(2000)
                // Parse key fields manually (avoid full JSON dependency overhead)
                val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(text)
                val verMatch  = Regex(""""version"\s*:\s*(\d+)""").find(text)
                val sdkMatch  = Regex(""""targetApexVersion"\s*:\s*(\d+)""").find(text)
                apexName    = nameMatch?.groupValues?.getOrNull(1) ?: ""
                apexVersion = verMatch?.groupValues?.getOrNull(1) ?: ""
                targetSdk   = sdkMatch?.groupValues?.getOrNull(1) ?: ""
            }
        }
    } catch (ex: Exception) {
        throw Exception("APEX parse failed: ${ex.message}")
    }

    // Categorise entries
    val imgEntries = entries.filter { it.first.endsWith(".img") || it.first.endsWith(".img.gz") }
    val libEntries = entries.filter { it.first.contains("/lib/") || it.first.contains("/lib64/") }
    val binEntries = entries.filter { it.first.startsWith("bin/") }
    val _etcEntries = entries.filter { it.first.startsWith("etc/") }

    val rawHeader = listOfNotNull(
        "format"          to "APEX",
        "name"            to apexName.ifBlank { file.nameWithoutExtension },
        "version"         to apexVersion.ifBlank { "unknown" },
        "target_sdk"      to targetSdk.ifBlank { "unknown" },
        "total_entries"   to entries.size.toString(),
        "payload_images"  to imgEntries.size.toString(),
        "native_libs"     to libEntries.size.toString(),
        "binaries"        to binEntries.size.toString(),
    )

    val sections = listOf(
        ArtSection("Format", "APEX (Android Package Extension)"),
        ArtSection("Name", apexName.ifBlank { file.nameWithoutExtension }),
        ArtSection("Version", apexVersion.ifBlank { "unknown" }),
        ArtSection("Total entries", entries.size.toString()),
        ArtSection("Payload images", imgEntries.joinToString(", ") { it.first }.ifBlank { "none" }),
        ArtSection("Native libraries", libEntries.size.toString()),
        ArtSection("Binaries", binEntries.size.toString()),
    )

    // Convert all entries to DexEntry list for the "Files" tab
    val fileList = entries.mapIndexed { i, (name, _) ->
        DexEntry(name = name, size = 0, offset = i, checksum = 0L)
    }

    return ArtFileInfo(
        format = ArtFormat.APEX,
        formatLabel = "APEX",
        version = apexVersion.ifBlank { "unknown" },
        architecture = "multi-arch",
        sections = sections,
        dexFiles = fileList,
        rawHeader = rawHeader
    )
}

private fun formatSz(b: Long): String = when {
    b >= 1_048_576L -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1_024L     -> "%.1f KB".format(b / 1_024.0)
    else            -> "$b B"
}

// ── Detect format ─────────────────────────────────────────────────────────────
private fun detectArtFormat(file: java.io.File): ArtFormat {
    val name = file.name.lowercase()
    if (name.endsWith(".apex") || name.endsWith(".capex")) return ArtFormat.APEX
    if (name.endsWith(".vdex")) return ArtFormat.VDEX
    if (name.endsWith(".oat") || name.endsWith(".odex") || name.endsWith(".art")) return ArtFormat.OAT
    // Byte-level detection
    val header = ByteArray(8).also {
        try { file.inputStream().use { s -> s.read(it) } } catch (_: Exception) {}
    }
    return when {
        header.size >= 4 && String(header, 0, 4, Charsets.ISO_8859_1) == "vdex" -> ArtFormat.VDEX
        header.size >= 4 && (String(header, 0, 4, Charsets.ISO_8859_1) == "oat\n" ||
            (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte())) -> ArtFormat.OAT
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> ArtFormat.APEX
        else -> ArtFormat.UNKNOWN
    }
}

// ── Composable ────────────────────────────────────────────────────────────────
@Composable
fun AndroidRuntimeViewerDialog(file: java.io.File, onDismiss: () -> Unit) {
    val bg      = Color(0xFF1E1E1E); val surface = Color(0xFF252526)
    val border  = Color(0xFF3C3C3C); val textColor = Color(0xFFD4D4D4)
    val muted   = Color(0xFF858585); val accent  = Color(0xFFA8CC8C)
    val keyColor = Color(0xFF9CDCFE); val valColor = Color(0xFFCE9178)

    var info    by remember { mutableStateOf<ArtFileInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errMsg  by remember { mutableStateOf<String?>(null) }
    var tab     by remember { mutableStateOf(0) }
    var filter  by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val fmt = detectArtFormat(file)
                val bytes = if (fmt != ArtFormat.APEX) file.readBytes() else ByteArray(0)
                info = when (fmt) {
                    ArtFormat.OAT  -> parseOat(bytes)
                    ArtFormat.VDEX -> parseVdex(bytes)
                    ArtFormat.APEX -> parseApex(file)
                    ArtFormat.UNKNOWN -> throw Exception("Unknown format: ${file.name}")
                }
            } catch (ex: Exception) {
                errMsg = ex.message ?: "Parse failed"
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = bg, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize()) {
                // Title bar
                Row(
                    Modifier.fillMaxWidth().background(surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Android, null, tint = accent,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = info?.formatLabel ?: "Android Runtime Viewer",
                        color = textColor, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("· ${file.name}", color = muted, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = muted,
                            modifier = Modifier.size(16.dp))
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent)
                            Spacer(Modifier.height(12.dp))
                            Text("Parsing ${file.name}…", color = muted, fontSize = 13.sp)
                        }
                    }
                    errMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)) {
                            Text("Parse Error", color = Color(0xFFF44747),
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(errMsg!!, color = muted, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    info != null -> {
                        val d = info!!
                        val tabs = when (d.format) {
                            ArtFormat.APEX -> listOf("Overview", "Files", "Raw Header")
                            else -> listOf("Overview", "DEX Files", "Raw Header")
                        }
                        TabRow(selectedTabIndex = tab, containerColor = surface,
                            contentColor = textColor) {
                            tabs.forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i },
                                    text = { Text(t, fontSize = 12.sp) })
                            }
                        }

                        when (tab) {
                            // ── Overview ──────────────────────────────────────
                            0 -> Column(
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                // Format badge
                                Box(
                                    Modifier.background(accent.copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(d.formatLabel, color = accent,
                                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(16.dp))
                                // File info
                                ArtKvRow("File", file.name, keyColor, valColor)
                                ArtKvRow("Size", formatSz(file.length()), keyColor, valColor)
                                ArtKvRow("Path", file.absolutePath, keyColor, valColor)
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = border)
                                Spacer(Modifier.height(12.dp))
                                // Sections
                                d.sections.forEach { sec ->
                                    ArtKvRow(sec.name, sec.value, keyColor, valColor)
                                }
                            }

                            // ── DEX Files / APEX Files ─────────────────────────
                            1 -> Column(Modifier.fillMaxSize()) {
                                OutlinedTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    placeholder = { Text("Filter…", fontSize = 12.sp, color = muted) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(8.dp).height(42.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedContainerColor = surface,
                                        focusedContainerColor = surface,
                                        unfocusedBorderColor = border,
                                        focusedBorderColor = accent,
                                        unfocusedTextColor = textColor,
                                        focusedTextColor = textColor
                                    )
                                )
                                val filtered = if (filter.isBlank()) d.dexFiles
                                    else d.dexFiles.filter { it.name.contains(filter, ignoreCase = true) }
                                Text("${filtered.size} entries", color = muted, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                                HorizontalDivider(color = border)
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(filtered) { entry ->
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 5.dp)
                                        ) {
                                            Text(entry.name, color = textColor, fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (d.format != ArtFormat.APEX) {
                                                Row(Modifier.fillMaxWidth()) {
                                                    Text("offset: 0x%08X".format(entry.offset),
                                                        color = muted, fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.weight(1f))
                                                    if (entry.checksum != 0L)
                                                        Text("crc: 0x%08X".format(entry.checksum),
                                                            color = muted, fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = border.copy(alpha = 0.4f))
                                    }
                                }
                            }

                            // ── Raw Header ────────────────────────────────────
                            2 -> LazyColumn(
                                Modifier.fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                item {
                                    Text("Raw Header Fields", color = muted,
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp))
                                }
                                items(d.rawHeader) { (k, v) ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                        Text(k, color = keyColor, fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.width(240.dp),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(v, color = valColor, fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider(color = border.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtKvRow(key: String, value: String, keyColor: Color, valColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, color = keyColor, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(200.dp), maxLines = 1)
        Text(value.ifBlank { "—" }, color = valColor, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f), maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = Color(0xFF3C3C3C).copy(alpha = 0.4f))
}
