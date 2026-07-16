package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
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
import org.json.JSONObject
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ── AI Model Viewer: GGUF · Safetensors · ONNX ────────────────────────────────
// Pure-Kotlin binary metadata parsers for the three most common AI model formats.
// No native libraries required — reads only the header/metadata sections.

// ── Shared model ──────────────────────────────────────────────────────────────
private enum class ModelFormat { GGUF, SAFETENSORS, ONNX, UNKNOWN }

private data class ModelMeta(
    val format: ModelFormat,
    val version: String,
    val architecture: String,
    val quantization: String,
    val parameters: String,
    val contextLength: String,
    val embeddingLength: String,
    val headCount: String,
    val layerCount: String,
    val vocabSize: String,
    val fileSize: String,
    val extraMeta: List<Pair<String, String>>   // key → value
)

// ── GGUF Parser ───────────────────────────────────────────────────────────────
// GGUF v1/v2/v3 header: magic(4) + version(4) + tensor_count(8) + kv_count(8)
// then KV pairs: key_len(8) + key + value_type(4) + value
private fun parseGguf(file: java.io.File): ModelMeta {
    RandomAccessFile(file, "r").use { raf ->
        val buf4 = ByteArray(4); val buf8 = ByteArray(8)

        fun readU32(): Long {
            raf.readFully(buf4)
            return ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        }
        fun readU64(): Long {
            raf.readFully(buf8)
            return ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).long
        }
        fun readStr(): String {
            val len = readU64()
            if (len <= 0 || len > 1_000_000) return "<skipped:len=$len>"
            val b = ByteArray(len.toInt()); raf.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        // Magic
        raf.readFully(buf4)
        val magic = String(buf4, Charsets.ISO_8859_1)
        require(magic == "GGUF") { "Not a GGUF file" }
        val version = readU32()
        val tensorCount = readU64()
        val kvCount = readU64()

        val meta = mutableMapOf<String, String>()
        var parsedKv = 0
        try {
            repeat(kvCount.toInt().coerceAtMost(300)) {
                val key = readStr()
                val vtype = readU32().toInt()
                val value: String = when (vtype) {
                    0 -> raf.readByte().toString()              // UINT8
                    1 -> raf.readByte().toString()              // INT8
                    2 -> { raf.readFully(ByteArray(2)); "<u16>" } // UINT16
                    3 -> { raf.readFully(ByteArray(2)); "<i16>" } // INT16
                    4 -> readU32().toString()                   // UINT32
                    5 -> {                                       // INT32
                        raf.readFully(buf4)
                        ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int.toString()
                    }
                    6 -> {                                       // FLOAT32
                        raf.readFully(buf4)
                        ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).float.toString()
                    }
                    7 -> {                                       // BOOL
                        raf.readByte().let { if (it != 0.toByte()) "true" else "false" }
                    }
                    8 -> readStr()                               // STRING
                    9 -> {                                       // ARRAY — skip
                        val arrType = readU32().toInt()
                        val arrLen = readU64()
                        // Skip array: approximate byte size per type
                        val elemSize = when (arrType) {
                            0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L
                            10, 11, 12 -> 8L
                            8 -> { // string array — can't skip cheaply, abort
                                return@repeat
                            }
                            else -> 4L
                        }
                        raf.seek(raf.filePointer + arrLen * elemSize)
                        "<array[$arrLen]>"
                    }
                    10 -> readU64().toString()                   // UINT64
                    11 -> readU64().toString()                   // INT64
                    12 -> {                                      // FLOAT64
                        raf.readFully(buf8)
                        ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).double.toString()
                    }
                    else -> { return@repeat }
                }
                meta[key] = value
                parsedKv++
            }
        } catch (_: Exception) {}

        val arch = meta["general.architecture"] ?: meta.keys.firstOrNull { it.endsWith(".architecture") }?.let { meta[it] } ?: "unknown"
        val quant = meta["general.quantization_version"] ?: meta.keys.firstOrNull { it.contains("quantiz") }?.let { meta[it] } ?: "unknown"
        val ctx  = meta["$arch.context_length"] ?: meta["llama.context_length"] ?: "unknown"
        val embd = meta["$arch.embedding_length"] ?: meta["llama.embedding_length"] ?: "unknown"
        val heads = meta["$arch.attention.head_count"] ?: meta["llama.attention.head_count"] ?: "unknown"
        val layers = meta["$arch.block_count"] ?: meta["llama.block_count"] ?: "unknown"
        val vocab = meta["tokenizer.ggml.token_count"] ?: meta["general.vocab_size"] ?: "unknown"
        val fileSizeFmt = formatBytes(file.length())

        val extra = meta.entries.sortedBy { it.key }
            .map { (k, v) -> k to v.take(120) }

        return ModelMeta(
            format = ModelFormat.GGUF,
            version = "GGUF v$version",
            architecture = arch,
            quantization = quant,
            parameters = "${tensorCount} tensors",
            contextLength = ctx,
            embeddingLength = embd,
            headCount = heads,
            layerCount = layers,
            vocabSize = vocab,
            fileSize = fileSizeFmt,
            extraMeta = extra
        )
    }
}

// ── Safetensors Parser ────────────────────────────────────────────────────────
// Header: first 8 bytes = header_size (u64 LE), then UTF-8 JSON of that size
private fun parseSafetensors(file: java.io.File): ModelMeta {
    RandomAccessFile(file, "r").use { raf ->
        val buf8 = ByteArray(8); raf.readFully(buf8)
        val headerSize = ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).long
        require(headerSize in 1..10_000_000) { "Invalid header size: $headerSize" }
        val jsonBytes = ByteArray(headerSize.toInt()); raf.readFully(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

        val meta = json.optJSONObject("__metadata__")
        val format = meta?.optString("format", "pt") ?: "pt"
        val dtype  = meta?.optString("dtype", "unknown") ?: "unknown"

        // Count tensors and gather dtype distribution
        val keys = json.keys().asSequence().filter { it != "__metadata__" }.toList()
        val dtypeCount = mutableMapOf<String, Int>()
        var totalParams = 0L
        for (k in keys) {
            val t = json.optJSONObject(k) ?: continue
            val d = t.optString("dtype", "?")
            dtypeCount[d] = (dtypeCount[d] ?: 0) + 1
            val shape = t.optJSONArray("shape")
            if (shape != null) {
                var prod = 1L
                for (i in 0 until shape.length()) prod *= shape.getLong(i)
                totalParams += prod
            }
        }
        val dtypeStr = dtypeCount.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}×${it.value}" }

        val extraMeta = mutableListOf<Pair<String, String>>()
        if (meta != null) {
            for (k in meta.keys()) {
                extraMeta += k to meta.optString(k, "").take(120)
            }
        }
        extraMeta += "Tensor count" to keys.size.toString()
        extraMeta += "Dtype distribution" to dtypeStr
        extraMeta += "Total parameters (approx)" to formatNumber(totalParams)

        return ModelMeta(
            format = ModelFormat.SAFETENSORS,
            version = "Safetensors (format=$format)",
            architecture = meta?.optString("architecture", "unknown") ?: "unknown",
            quantization = dtype,
            parameters = "${keys.size} tensors (~${formatNumber(totalParams)} params)",
            contextLength = meta?.optString("max_seq_len", "unknown") ?: "unknown",
            embeddingLength = meta?.optString("hidden_size", "unknown") ?: "unknown",
            headCount = meta?.optString("num_attention_heads", "unknown") ?: "unknown",
            layerCount = meta?.optString("num_hidden_layers", "unknown") ?: "unknown",
            vocabSize = meta?.optString("vocab_size", "unknown") ?: "unknown",
            fileSize = formatBytes(file.length()),
            extraMeta = extraMeta
        )
    }
}

// ── ONNX Parser ───────────────────────────────────────────────────────────────
// ONNX is protobuf3. We parse just enough of the wire format to extract
// ModelProto fields: ir_version(field1), opset_import(field6), graph(field7),
// domain(field2), model_version(field5), doc_string(field8), metadata_props(field14).
private fun parsePbVarInt(buf: ByteArray, pos: Int): Pair<Long, Int> {
    var result = 0L; var shift = 0; var p = pos
    while (p < buf.size) {
        val b = buf[p++].toLong() and 0xFF
        result = result or ((b and 0x7F) shl shift)
        shift += 7
        if (b and 0x80 == 0L) break
    }
    return result to p
}

private fun parseOnnx(file: java.io.File): ModelMeta {
    val bytes = file.readBytes().let { if (it.size > 2_000_000) it.copyOfRange(0, 2_000_000) else it }
    var pos = 0
    var irVersion = "unknown"; var domain = ""; var modelVersion = "unknown"
    var docString = ""; var graphName = ""; var nodeCount = 0
    val opsets = mutableListOf<String>()
    val metadata = mutableListOf<Pair<String, String>>()

    fun skipLen(start: Int, len: Int): Int = start + len

    try {
        while (pos < bytes.size) {
            val (tag, p1) = parsePbVarInt(bytes, pos); pos = p1
            val fieldNum = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when (wireType) {
                0 -> { // varint
                    val (v, p2) = parsePbVarInt(bytes, pos); pos = p2
                    if (fieldNum == 1) irVersion = "IR v$v"
                    if (fieldNum == 5) modelVersion = v.toString()
                }
                2 -> { // length-delimited
                    val (len, p2) = parsePbVarInt(bytes, pos); pos = p2
                    val end = (pos + len.toInt()).coerceAtMost(bytes.size)
                    val data = bytes.copyOfRange(pos, end)
                    when (fieldNum) {
                        2 -> domain = String(data, Charsets.UTF_8)
                        8 -> docString = String(data, Charsets.UTF_8).take(200)
                        6 -> { // opset_import (sub-message: domain=field1, version=field2)
                            var sp = 0
                            var opDomain = "ai.onnx"; var opVer = 0L
                            while (sp < data.size) {
                                val (st, sp2) = parsePbVarInt(data, sp); sp = sp2
                                val sf = (st shr 3).toInt(); val sw = (st and 0x7).toInt()
                                if (sw == 0) { val (v, sp3) = parsePbVarInt(data, sp); sp = sp3; if (sf == 2) opVer = v }
                                else if (sw == 2) { val (l, sp3) = parsePbVarInt(data, sp); sp = sp3
                                    if (sf == 1) opDomain = String(data.copyOfRange(sp, (sp + l.toInt()).coerceAtMost(data.size)), Charsets.UTF_8)
                                    sp += l.toInt() }
                                else break
                            }
                            opsets += "$opDomain v$opVer"
                        }
                        7 -> { // graph (sub-message: name=field1, node[]=field1)
                            var sp = 0
                            while (sp < data.size) {
                                val (st, sp2) = parsePbVarInt(data, sp); sp = sp2
                                val sf = (st shr 3).toInt(); val sw = (st and 0x7).toInt()
                                if (sw == 0) { parsePbVarInt(data, sp).let { sp = it.second } }
                                else if (sw == 2) {
                                    val (l, sp3) = parsePbVarInt(data, sp); sp = sp3
                                    if (sf == 1) nodeCount++  // node
                                    else if (sf == 2 && graphName.isEmpty()) graphName = String(data.copyOfRange(sp, (sp + l.toInt()).coerceAtMost(data.size)), Charsets.UTF_8)
                                    sp += l.toInt()
                                } else break
                            }
                        }
                        14 -> { // metadata_props (StringStringEntryProto: key=1, value=2)
                            var sp = 0; var mk = ""; var mv = ""
                            while (sp < data.size) {
                                val (st, sp2) = parsePbVarInt(data, sp); sp = sp2
                                val sf = (st shr 3).toInt(); val sw = (st and 0x7).toInt()
                                if (sw == 2) { val (l, sp3) = parsePbVarInt(data, sp); sp = sp3
                                    val s = String(data.copyOfRange(sp, (sp + l.toInt()).coerceAtMost(data.size)), Charsets.UTF_8)
                                    if (sf == 1) mk = s else if (sf == 2) mv = s
                                    sp += l.toInt()
                                } else break
                            }
                            if (mk.isNotBlank()) metadata += mk to mv.take(120)
                        }
                        else -> { pos = skipLen(pos, len.toInt()) }
                    }
                    if (fieldNum != 7) pos = end  // already advanced for graph
                }
                5 -> { pos += 4 }  // 32-bit
                1 -> { pos += 8 }  // 64-bit
                else -> break
            }
        }
    } catch (_: Exception) {}

    val extra = metadata.toMutableList()
    if (docString.isNotBlank()) extra += "doc_string" to docString
    if (domain.isNotBlank()) extra += "domain" to domain
    extra += "opset_imports" to opsets.joinToString(", ")
    extra += "graph_name" to graphName.ifBlank { "unnamed" }
    extra += "node_count (approx)" to nodeCount.toString()

    return ModelMeta(
        format = ModelFormat.ONNX,
        version = "$irVersion  model_version=$modelVersion",
        architecture = domain.ifBlank { "ONNX" },
        quantization = "unknown",
        parameters = "$nodeCount nodes (approx)",
        contextLength = "N/A",
        embeddingLength = metadata.firstOrNull { it.first.contains("hidden") }?.second ?: "unknown",
        headCount = "unknown",
        layerCount = "unknown",
        vocabSize = "unknown",
        fileSize = formatBytes(file.length()),
        extraMeta = extra
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private fun formatBytes(b: Long): String = when {
    b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576L     -> "%.2f MB".format(b / 1_048_576.0)
    b >= 1_024L         -> "%.2f KB".format(b / 1_024.0)
    else                -> "$b B"
}
private fun formatNumber(n: Long): String = when {
    n >= 1_000_000_000L -> "%.2fB".format(n / 1_000_000_000.0)
    n >= 1_000_000L     -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000L         -> "%.2fK".format(n / 1_000.0)
    else                -> n.toString()
}

// ── Composable ────────────────────────────────────────────────────────────────
@Composable
fun AiModelViewerDialog(file: java.io.File, onDismiss: () -> Unit) {
    val bg = Color(0xFF1E1E1E); val surface = Color(0xFF252526)
    val border = Color(0xFF3C3C3C); val textColor = Color(0xFFD4D4D4)
    val muted = Color(0xFF858585); val accent = Color(0xFF9CDCFE)
    val green = Color(0xFF4EC9B0)

    var meta    by remember { mutableStateOf<ModelMeta?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errMsg  by remember { mutableStateOf<String?>(null) }
    var tab     by remember { mutableStateOf(0) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val name = file.name.lowercase()
                meta = when {
                    name.endsWith(".gguf") -> parseGguf(file)
                    name.endsWith(".safetensors") -> parseSafetensors(file)
                    name.endsWith(".onnx") -> parseOnnx(file)
                    else -> throw Exception("Unsupported format: ${file.name}")
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
                    Icon(Icons.Default.Psychology, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("AI Model Viewer", color = textColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("· ${file.name}", color = muted, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = muted, modifier = Modifier.size(16.dp))
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent)
                            Spacer(Modifier.height(12.dp))
                            Text("Parsing model metadata…", color = muted, fontSize = 13.sp)
                        }
                    }
                    errMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Error: $errMsg", color = Color(0xFFF44747), fontSize = 13.sp)
                    }
                    meta != null -> {
                        val m = meta!!
                        // Tabs
                        val tabs = listOf("Summary", "All Metadata")
                        TabRow(selectedTabIndex = tab, containerColor = surface,
                            contentColor = textColor) {
                            tabs.forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i },
                                    text = { Text(t, fontSize = 12.sp) })
                            }
                        }

                        when (tab) {
                            0 -> {
                                // Summary
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(16.dp)) {
                                    // Format badge
                                    Box(
                                        Modifier.background(accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(m.format.name, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    SummaryGrid(
                                        listOf(
                                            "Version" to m.version,
                                            "Architecture" to m.architecture,
                                            "Quantization" to m.quantization,
                                            "Parameters" to m.parameters,
                                            "Context Length" to m.contextLength,
                                            "Embedding Length" to m.embeddingLength,
                                            "Head Count" to m.headCount,
                                            "Layer Count" to m.layerCount,
                                            "Vocab Size" to m.vocabSize,
                                            "File Size" to m.fileSize,
                                        ),
                                        textColor = textColor, muted = muted, accent = accent
                                    )
                                }
                            }
                            1 -> {
                                // All metadata as key-value table
                                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    item {
                                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                            Text("Key", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f))
                                            Text("Value", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1.5f))
                                        }
                                        Divider(color = border)
                                    }
                                    items(m.extraMeta) { (k, v) ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                            Text(k, color = green, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(1f), maxLines = 2,
                                                overflow = TextOverflow.Ellipsis)
                                            Text(v, color = textColor, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(1.5f), maxLines = 3,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                        Divider(color = border.copy(alpha = 0.4f))
                                    }
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
private fun SummaryGrid(
    entries: List<Pair<String, String>>,
    textColor: Color, muted: Color, accent: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = muted, fontSize = 12.sp, modifier = Modifier.width(160.dp))
                Text(
                    value.ifBlank { "—" },
                    color = if (value == "unknown" || value.isBlank()) muted else textColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
            Divider(color = Color(0xFF3C3C3C).copy(alpha = 0.5f))
        }
    }
}
