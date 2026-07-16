package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Wifi
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
import org.json.JSONArray
import org.json.JSONObject

// ── Network Viewer: PCAP + HAR ─────────────────────────────────────────────────
// HAR: JSON-based HTTP Archive parsed via org.json (bundled in Android).
// PCAP: raw binary packet capture parsed manually (pcap global header + packet records).
// Displays packet/request list with detail panel.

// ── Shared models ──────────────────────────────────────────────────────────────
private data class NetEntry(
    val index: Int,
    val label: String,       // e.g. "GET /api/foo" or "TCP 192.168.1.1:443"
    val detail: String,      // full text detail block
    val status: String,      // "200", "POST", "TCP", etc.
    val size: Long,
    val time: String         // timestamp or duration
)

// ── HAR Parser ────────────────────────────────────────────────────────────────
private fun parseHar(file: java.io.File): List<NetEntry> {
    val text = file.readText(Charsets.UTF_8)
    val root = JSONObject(text)
    val entries = root.optJSONObject("log")?.optJSONArray("entries") ?: return emptyList()
    val result = mutableListOf<NetEntry>()
    for (i in 0 until entries.length()) {
        val e = entries.getJSONObject(i)
        val req   = e.optJSONObject("request") ?: JSONObject()
        val resp  = e.optJSONObject("response") ?: JSONObject()
        val method = req.optString("method", "?")
        val url    = req.optString("url", "")
        val status = resp.optInt("status", 0)
        val bodySize = resp.optLong("bodySize", 0L)
        val time   = "%.1fms".format(e.optDouble("time", 0.0))
        val started = e.optString("startedDateTime", "")

        val sb = StringBuilder()
        sb.appendLine("=== REQUEST ===")
        sb.appendLine("$method $url")
        sb.appendLine("Started: $started")
        val reqHeaders = req.optJSONArray("headers")
        if (reqHeaders != null) {
            sb.appendLine("\nHeaders:")
            for (h in 0 until reqHeaders.length()) {
                val hdr = reqHeaders.getJSONObject(h)
                sb.appendLine("  ${hdr.optString("name")}: ${hdr.optString("value")}")
            }
        }
        val postData = req.optJSONObject("postData")
        if (postData != null) {
            sb.appendLine("\nBody:")
            sb.appendLine("  ${postData.optString("text", "").take(500)}")
        }
        sb.appendLine("\n=== RESPONSE ===")
        sb.appendLine("Status: $status ${resp.optString("statusText", "")}")
        sb.appendLine("Body size: $bodySize bytes")
        val respHeaders = resp.optJSONArray("headers")
        if (respHeaders != null) {
            sb.appendLine("\nHeaders:")
            for (h in 0 until respHeaders.length()) {
                val hdr = respHeaders.getJSONObject(h)
                sb.appendLine("  ${hdr.optString("name")}: ${hdr.optString("value")}")
            }
        }
        val content = resp.optJSONObject("content")
        if (content != null) {
            val txt = content.optString("text", "")
            if (txt.isNotBlank()) {
                sb.appendLine("\nResponse body (preview):")
                sb.appendLine(txt.take(1000))
            }
        }
        val path = url.substringAfter("://").substringAfter("/", "").let { "/$it" }.take(60)
        result += NetEntry(
            index = i,
            label = "$method $path",
            detail = sb.toString(),
            status = if (status > 0) "$status" else method,
            size = bodySize,
            time = time
        )
    }
    return result
}

// ── PCAP Parser ───────────────────────────────────────────────────────────────
private fun parsePcap(file: java.io.File): List<NetEntry> {
    val bytes = file.readBytes()
    if (bytes.size < 24) return emptyList()

    // Global header: magic, major, minor, thiszone, sigfigs, snaplen, network
    val magic = ((bytes[0].toInt() and 0xFF)) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
    val littleEndian = magic == 0xa1b2c3d4.toInt() || magic == 0xa1b23c4d.toInt()
    val nanoSecond   = magic == 0xa1b23c4d.toInt() || magic == 0x4d3cb2a1.toInt()

    fun readU32(pos: Int): Long {
        return if (littleEndian) {
            ((bytes[pos].toLong() and 0xFF)) or
            ((bytes[pos+1].toLong() and 0xFF) shl 8) or
            ((bytes[pos+2].toLong() and 0xFF) shl 16) or
            ((bytes[pos+3].toLong() and 0xFF) shl 24)
        } else {
            ((bytes[pos].toLong() and 0xFF) shl 24) or
            ((bytes[pos+1].toLong() and 0xFF) shl 16) or
            ((bytes[pos+2].toLong() and 0xFF) shl 8) or
            (bytes[pos+3].toLong() and 0xFF)
        }
    }

    val linkType = readU32(20).toInt()
    val result = mutableListOf<NetEntry>()
    var pos = 24
    var idx = 0

    while (pos + 16 <= bytes.size && idx < 2000) {
        val tsSec  = readU32(pos)
        val tsUsec = readU32(pos + 4)
        val inclLen = readU32(pos + 8).toInt()
        val origLen = readU32(pos + 12)
        pos += 16
        if (pos + inclLen > bytes.size) break

        val pkt = bytes.copyOfRange(pos, pos + inclLen)
        pos += inclLen

        // Basic Ethernet II decode (linkType=1)
        val sb = StringBuilder()
        var label = "Packet #${idx+1}"
        var proto = "RAW"
        val ts = "$tsSec.%06d".format(if (nanoSecond) tsUsec/1000 else tsUsec)

        sb.appendLine("Timestamp: $ts")
        sb.appendLine("Captured: $inclLen / Original: $origLen bytes")
        sb.appendLine("Link type: $linkType")

        if (linkType == 1 && pkt.size >= 14) {
            val ethType = ((pkt[12].toInt() and 0xFF) shl 8) or (pkt[13].toInt() and 0xFF)
            val srcMac = pkt.slice(6..11).joinToString(":") { "%02x".format(it) }
            val dstMac = pkt.slice(0..5).joinToString(":") { "%02x".format(it) }
            sb.appendLine("\n=== ETHERNET ===")
            sb.appendLine("Src MAC: $srcMac")
            sb.appendLine("Dst MAC: $dstMac")
            sb.appendLine("EtherType: 0x%04X".format(ethType))

            var ipOffset = 14
            if (ethType == 0x0800 && pkt.size > ipOffset + 20) {
                // IPv4
                val ihl = (pkt[ipOffset].toInt() and 0x0F) * 4
                val totalLen = ((pkt[ipOffset+2].toInt() and 0xFF) shl 8) or (pkt[ipOffset+3].toInt() and 0xFF)
                val ttl = pkt[ipOffset+8].toInt() and 0xFF
                val ipProto = pkt[ipOffset+9].toInt() and 0xFF
                val srcIp = (ipOffset..ipOffset+3).map { "${pkt[it+12].toInt() and 0xFF}" }.joinToString(".")
                val dstIp = (ipOffset..ipOffset+3).map { "${pkt[it+16].toInt() and 0xFF}" }.joinToString(".")
                sb.appendLine("\n=== IPv4 ===")
                sb.appendLine("Src: $srcIp  Dst: $dstIp")
                sb.appendLine("Protocol: $ipProto  TTL: $ttl  Len: $totalLen")
                proto = "IPv4"

                val tOffset = ipOffset + ihl
                when (ipProto) {
                    6 -> { // TCP
                        if (pkt.size > tOffset + 4) {
                            val sPort = ((pkt[tOffset].toInt() and 0xFF) shl 8) or (pkt[tOffset+1].toInt() and 0xFF)
                            val dPort = ((pkt[tOffset+2].toInt() and 0xFF) shl 8) or (pkt[tOffset+3].toInt() and 0xFF)
                            sb.appendLine("\n=== TCP ===")
                            sb.appendLine("Src port: $sPort  Dst port: $dPort")
                            label = "TCP $srcIp:$sPort → $dstIp:$dPort"
                            proto = "TCP"
                        }
                    }
                    17 -> { // UDP
                        if (pkt.size > tOffset + 4) {
                            val sPort = ((pkt[tOffset].toInt() and 0xFF) shl 8) or (pkt[tOffset+1].toInt() and 0xFF)
                            val dPort = ((pkt[tOffset+2].toInt() and 0xFF) shl 8) or (pkt[tOffset+3].toInt() and 0xFF)
                            sb.appendLine("\n=== UDP ===")
                            sb.appendLine("Src port: $sPort  Dst port: $dPort")
                            label = "UDP $srcIp:$sPort → $dstIp:$dPort"
                            proto = "UDP"
                        }
                    }
                    1 -> { label = "ICMP $srcIp → $dstIp"; proto = "ICMP" }
                }
            }
        }
        // Raw hex preview (first 64 bytes)
        sb.appendLine("\n=== RAW HEX (first 64 bytes) ===")
        pkt.take(64).chunked(16).forEach { row ->
            sb.appendLine(row.joinToString(" ") { "%02x".format(it) })
        }

        result += NetEntry(
            index = idx,
            label = label,
            detail = sb.toString(),
            status = proto,
            size = inclLen.toLong(),
            time = ts
        )
        idx++
    }
    return result
}

// ── Composable ────────────────────────────────────────────────────────────────
@Composable
fun NetworkViewerDialog(file: java.io.File, onDismiss: () -> Unit) {
    val bg = Color(0xFF1E1E1E); val surface = Color(0xFF252526)
    val border = Color(0xFF3C3C3C); val textColor = Color(0xFFD4D4D4)
    val muted = Color(0xFF858585); val accent = Color(0xFF569CD6)

    val isHar = file.name.endsWith(".har", ignoreCase = true)
    var entries  by remember { mutableStateOf<List<NetEntry>>(emptyList()) }
    var loading  by remember { mutableStateOf(true) }
    var errMsg   by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NetEntry?>(null) }
    var filter   by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                entries = if (isHar) parseHar(file) else parsePcap(file)
            } catch (ex: Exception) {
                errMsg = ex.message ?: "Parse failed"
            } finally {
                loading = false
            }
        }
    }

    val filtered = remember(entries, filter) {
        if (filter.isBlank()) entries
        else entries.filter { it.label.contains(filter, ignoreCase = true) || it.status.contains(filter, ignoreCase = true) }
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
                    Icon(Icons.Default.Wifi, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isHar) "HAR Viewer" else "PCAP Viewer",
                        color = textColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("· ${file.name}", color = muted, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = muted, modifier = Modifier.size(16.dp))
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                    errMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Error: $errMsg", color = Color(0xFFF44747), fontSize = 13.sp)
                    }
                    else -> Row(Modifier.fillMaxSize()) {
                        // Left: list
                        Column(Modifier.width(300.dp).fillMaxHeight()) {
                            // Filter
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                placeholder = { Text("Filter…", fontSize = 12.sp, color = muted) },
                                modifier = Modifier.fillMaxWidth().padding(8.dp).height(40.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = surface,
                                    focusedContainerColor = surface,
                                    unfocusedBorderColor = border,
                                    focusedBorderColor = accent,
                                    unfocusedTextColor = textColor,
                                    focusedTextColor = textColor
                                )
                            )
                            Text("${filtered.size} entries", color = muted, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                            Divider(color = border)
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(filtered) { entry ->
                                    val isSelected = selected == entry
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(if (isSelected) Color(0xFF094771) else Color.Transparent)
                                            .clickable { selected = entry }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Status badge
                                        val statusColor = when {
                                            entry.status.startsWith("2") -> Color(0xFF4CAF50)
                                            entry.status.startsWith("3") -> Color(0xFFFF9800)
                                            entry.status.startsWith("4") || entry.status.startsWith("5") -> Color(0xFFF44336)
                                            entry.status == "TCP" -> Color(0xFF42A5F5)
                                            entry.status == "UDP" -> Color(0xFF66BB6A)
                                            else -> muted
                                        }
                                        Box(
                                            Modifier.width(42.dp).background(statusColor.copy(alpha = 0.15f),
                                                RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(entry.status, color = statusColor, fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.label, color = textColor, fontSize = 12.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${entry.size}B  ${entry.time}", color = muted, fontSize = 10.sp)
                                        }
                                    }
                                    Divider(color = border.copy(alpha = 0.4f))
                                }
                            }
                        }
                        // Divider
                        Box(Modifier.width(1.dp).fillMaxHeight().background(border))
                        // Right: detail
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (selected == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Select an entry to inspect", color = muted, fontSize = 13.sp)
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(16.dp)
                                ) {
                                    Text(selected!!.detail,
                                        color = textColor, fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
