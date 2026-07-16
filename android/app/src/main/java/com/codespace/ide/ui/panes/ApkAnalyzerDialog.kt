package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import java.io.InputStream
import java.util.zip.ZipFile

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

data class ApkManifestInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: String,
    val targetSdk: String,
    val compileSdk: String,
    val applicationLabel: String,
    val mainActivity: String?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val features: List<String>,
    val rawXml: String,
)

data class ApkEntry(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val category: String,
)

data class ApkAnalysis(
    val fileName: String,
    val fileSize: Long,
    val manifest: ApkManifestInfo?,
    val entries: List<ApkEntry>,
    val dexFiles: List<String>,
    val nativeLibs: List<String>,
    val resourceFiles: Int,
    val assetFiles: Int,
    val signingInfo: String,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Binary XML (AXML) decoder — parses Android's compiled binary XML format
// ─────────────────────────────────────────────────────────────────────────────

private object AxmlDecoder {
    // Chunk types
    private const val CHUNK_AXML      = 0x00080003
    private const val CHUNK_STRINGS   = 0x001C0001
    private const val CHUNK_XML_START = 0x00100102
    private const val CHUNK_XML_END   = 0x00100103
    private const val CHUNK_XML_ATTR  = 0x00100104
    private const val CHUNK_NS_START  = 0x00100100
    private const val CHUNK_NS_END    = 0x00100101
    private const val CHUNK_RES_IDS   = 0x00080180

    fun decode(bytes: ByteArray): String = try {
        val sb = StringBuilder()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        if (buf.remaining() < 8) return "(binary XML — too small)"
        val fileType = buf.int
        if (fileType != CHUNK_AXML) return "(not binary XML: type=0x${fileType.toString(16)})"
        val fileSize = buf.int

        // String pool
        var strings = listOf<String>()
        val savedPos = buf.position()
        if (buf.remaining() >= 8) {
            val chunkType = buf.int
            if (chunkType == CHUNK_STRINGS) {
                val chunkSize = buf.int
                val strCount  = buf.int
                val styleCount= buf.int
                val flags     = buf.int
                val strStart  = buf.int
                val styStart  = buf.int
                val offsets = IntArray(strCount) { buf.int }
                val poolStart = buf.position()
                val poolData  = ByteArray(chunkSize - (poolStart - savedPos))
                    .also { buf.get(it) }
                val isUtf8 = (flags and 0x100) != 0
                strings = offsets.map { off ->
                    try {
                        if (isUtf8) {
                            val lenBytes = poolData[off].toInt() and 0xFF
                            val start = off + if (lenBytes < 0x80) 2 else 4
                            String(poolData, start, poolData.drop(start).takeWhile { it != 0.toByte() }.size, Charsets.UTF_8)
                        } else {
                            val lenChars = (poolData[off].toInt() and 0xFF) or ((poolData[off + 1].toInt() and 0xFF) shl 8)
                            val start = off + 2
                            String(poolData, start, lenChars * 2, Charsets.UTF_16LE)
                        }
                    } catch (_: Exception) { "" }
                }
            }
        }

        // Second pass — parse XML events
        fun str(idx: Int) = if (idx >= 0 && idx < strings.size) strings[idx] else ""

        // Reset to after the file header and re-scan all chunks
        buf.position(8)
        var depth = 0
        val indent = "  "

        while (buf.remaining() >= 8) {
            val chunkType = buf.int
            val chunkSize = buf.int
            if (chunkSize < 8) break

            when (chunkType) {
                CHUNK_STRINGS, CHUNK_RES_IDS -> {
                    val skip = (chunkSize - 8).coerceAtLeast(0)
                    if (buf.remaining() >= skip) buf.position(buf.position() + skip)
                }
                CHUNK_NS_START, CHUNK_NS_END -> {
                    if (buf.remaining() >= chunkSize - 8)
                        buf.position(buf.position() + chunkSize - 8)
                }
                CHUNK_XML_START -> {
                    if (buf.remaining() < chunkSize - 8) break
                    buf.int  // lineNumber
                    buf.int  // 0xFFFFFFFF
                    val nsIdx  = buf.int
                    val nameIdx= buf.int
                    val attrStart = buf.short.toInt() and 0xFFFF
                    val attrSize  = buf.short.toInt() and 0xFFFF
                    val attrCount = buf.short.toInt() and 0xFFFF
                    buf.short  // idIndex
                    buf.short  // classIndex
                    buf.short  // styleIndex

                    val elemName = str(nameIdx)
                    sb.append(indent.repeat(depth))
                    sb.append("<$elemName")
                    repeat(attrCount) {
                        if (buf.remaining() < 20) return@repeat
                        val attrNsIdx   = buf.int
                        val attrNameIdx = buf.int
                        val attrRawIdx  = buf.int
                        val attrValueType = buf.int ushr 24
                        val attrValueData = buf.int
                        val attrName = str(attrNameIdx)
                        val attrVal  = when (attrValueType) {
                            0x03 -> str(attrRawIdx)          // string ref
                            0x10 -> attrValueData.toString() // integer
                            0x12 -> if (attrValueData != 0) "true" else "false" // bool
                            else -> "0x${attrValueData.toString(16)}"
                        }
                        sb.append("\n${indent.repeat(depth + 1)}$attrName=\"$attrVal\"")
                    }
                    sb.append(">\n")
                    depth++
                }
                CHUNK_XML_END -> {
                    if (buf.remaining() < chunkSize - 8) break
                    buf.int  // lineNumber
                    buf.int  // 0xFFFFFFFF
                    val nsIdx  = buf.int
                    val nameIdx= buf.int
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append("${indent.repeat(depth)}</${str(nameIdx)}>\n")
                }
                else -> {
                    val skip = (chunkSize - 8).coerceAtLeast(0)
                    if (buf.remaining() >= skip) buf.position(buf.position() + skip)
                    else break
                }
            }
        }
        sb.toString().ifBlank { "(empty XML)" }
    } catch (e: Exception) {
        "(AXML decode error: ${e.message})"
    }

    fun extractManifestValues(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fun attr(name: String): String? {
            val re = Regex("""${Regex.escape(name)}="([^"]*)"""")
            return re.find(xml)?.groupValues?.get(1)
        }
        map["package"]        = attr("package") ?: ""
        map["versionName"]    = attr("versionName") ?: attr("android:versionName") ?: ""
        map["versionCode"]    = attr("versionCode") ?: attr("android:versionCode") ?: ""
        map["minSdkVersion"]  = attr("minSdkVersion") ?: attr("android:minSdkVersion") ?: ""
        map["targetSdkVersion"] = attr("targetSdkVersion") ?: attr("android:targetSdkVersion") ?: ""
        map["compileSdkVersion"] = attr("compileSdkVersion") ?: ""
        map["label"]          = attr("label") ?: attr("android:label") ?: ""
        return map
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// APK parser
// ─────────────────────────────────────────────────────────────────────────────

private fun analyzeApk(file: File): ApkAnalysis {
    return try {
        val zip = ZipFile(file)
        val entries = zip.entries().asSequence().map { entry ->
            val cat = when {
                entry.name.startsWith("classes") && entry.name.endsWith(".dex") -> "DEX"
                entry.name.startsWith("lib/") -> "Native"
                entry.name.startsWith("res/") -> "Resource"
                entry.name.startsWith("assets/") -> "Asset"
                entry.name == "AndroidManifest.xml" -> "Manifest"
                entry.name.startsWith("META-INF/") -> "Signing"
                entry.name.endsWith(".so") -> "Native"
                else -> "Other"
            }
            ApkEntry(entry.name, entry.size, entry.compressedSize, cat)
        }.toList()

        val dexFiles = entries.filter { it.category == "DEX" }.map { it.path }
        val nativeLibs = entries.filter { it.category == "Native" }.map { it.path }
        val resourceFiles = entries.count { it.category == "Resource" }
        val assetFiles = entries.count { it.category == "Asset" }

        // Read signing info from META-INF
        val sigEntry = zip.entries().asSequence().firstOrNull { it.name.startsWith("META-INF/") && it.name.endsWith(".RSA") }
        val signingInfo = if (sigEntry != null) "V1 (JAR) signed — ${sigEntry.name}" else "META-INF present"

        // Parse AndroidManifest.xml (binary AXML)
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
        val manifest = if (manifestEntry != null) {
            val bytes = zip.getInputStream(manifestEntry).readBytes()
            val xml = AxmlDecoder.decode(bytes)
            val vals = AxmlDecoder.extractManifestValues(xml)

            // Extract permissions, activities, services, receivers, providers, features
            val permRegex = Regex("""<uses-permission[^>]*name="([^"]+)"""")
            val actRegex  = Regex("""<activity[^>]*name="([^"]+)"""")
            val svcRegex  = Regex("""<service[^>]*name="([^"]+)"""")
            val rcvRegex  = Regex("""<receiver[^>]*name="([^"]+)"""")
            val pvdRegex  = Regex("""<provider[^>]*name="([^"]+)"""")
            val ftRegex   = Regex("""<uses-feature[^>]*name="([^"]+)"""")
            val mainActRegex = Regex("""<action[^>]*name="android\.intent\.action\.MAIN"[\s\S]{0,200}?<activity[^>]*name="([^"]+)"""")

            val permissions = permRegex.findAll(xml).map { it.groupValues[1] }.toList()
            val activities  = actRegex.findAll(xml).map { it.groupValues[1] }.toList()
            val services    = svcRegex.findAll(xml).map { it.groupValues[1] }.toList()
            val receivers   = rcvRegex.findAll(xml).map { it.groupValues[1] }.toList()
            val providers   = pvdRegex.findAll(xml).map { it.groupValues[1] }.toList()
            val features    = ftRegex.findAll(xml).map { it.groupValues[1] }.toList()
            // Try to find main activity from category LAUNCHER
            val mainAct = activities.firstOrNull() // best-effort

            ApkManifestInfo(
                packageName     = vals["package"] ?: "unknown",
                versionName     = vals["versionName"] ?: "?",
                versionCode     = vals["versionCode"] ?: "?",
                minSdk          = vals["minSdkVersion"] ?: "?",
                targetSdk       = vals["targetSdkVersion"] ?: "?",
                compileSdk      = vals["compileSdkVersion"] ?: "?",
                applicationLabel= vals["label"] ?: "?",
                mainActivity    = mainAct,
                permissions     = permissions,
                activities      = activities,
                services        = services,
                receivers       = receivers,
                providers       = providers,
                features        = features,
                rawXml          = xml,
            )
        } else null

        zip.close()

        ApkAnalysis(
            fileName      = file.name,
            fileSize      = file.length(),
            manifest      = manifest,
            entries       = entries,
            dexFiles      = dexFiles,
            nativeLibs    = nativeLibs,
            resourceFiles = resourceFiles,
            assetFiles    = assetFiles,
            signingInfo   = signingInfo,
        )
    } catch (e: Exception) {
        ApkAnalysis(
            fileName = file.name, fileSize = file.length(),
            manifest = null, entries = emptyList(),
            dexFiles = emptyList(), nativeLibs = emptyList(),
            resourceFiles = 0, assetFiles = 0,
            signingInfo = "?", error = e.message,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val ABg     = Color(0xFF1E1E1E)
private val ACard   = Color(0xFF252526)
private val AText   = Color(0xFFD4D4D4)
private val ADim    = Color(0xFF808080)
private val AAccent = Color(0xFF007ACC)
private val AGreen  = Color(0xFF4EC9B0)
private val AYellow = Color(0xFFE5C07B)
private val ARed    = Color(0xFFFF5F5F)
private val ADivider= Color(0xFF3C3C3C)

@Composable
fun ApkAnalyzerDialog(file: File, onDismiss: () -> Unit) {
    var analysis by remember { mutableStateOf<ApkAnalysis?>(null) }
    var loading  by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf("Overview") }

    LaunchedEffect(file.absolutePath) {
        loading = true
        analysis = withContext(Dispatchers.IO) { analyzeApk(file) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(ACard, RoundedCornerShape(8.dp))
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2D2D2D), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("APK Analyzer", color = AAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(file.name, color = AText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (analysis != null) {
                    Text(
                        formatSize(analysis!!.fileSize),
                        color = ADim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = ADim, modifier = Modifier.size(16.dp))
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AAccent)
                        Spacer(Modifier.height(12.dp))
                        Text("Analyzing APK…", color = ADim, fontSize = 12.sp)
                    }
                }
                return@Column
            }

            val a = analysis!!

            if (a.error != null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Error: ${a.error}", color = ARed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                return@Column
            }

            // ── Tab bar ──────────────────────────────────────────────────────
            val tabs = listOf("Overview", "Manifest", "Permissions", "Components", "Files")
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2D2D2D)).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val active = tab == activeTab
                    Box(
                        Modifier
                            .clickable { activeTab = tab }
                            .background(if (active) ACard else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(tab, color = if (active) AAccent else ADim, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            HorizontalDivider(color = ADivider)

            // ── Tab content ──────────────────────────────────────────────────
            LazyColumn(Modifier.fillMaxSize().background(ABg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (activeTab) {
                    "Overview" -> {
                        val m = a.manifest
                        item { SectionHeader("Package") }
                        if (m != null) {
                            item { KVRow("Package",      m.packageName) }
                            item { KVRow("Version",      "${m.versionName} (${m.versionCode})") }
                            item { KVRow("Min SDK",      m.minSdk) }
                            item { KVRow("Target SDK",   m.targetSdk) }
                            item { KVRow("Label",        m.applicationLabel) }
                            item { KVRow("Main Activity",m.mainActivity ?: "?") }
                        }
                        item { Spacer(Modifier.height(12.dp)); SectionHeader("Composition") }
                        item { KVRow("DEX files",     a.dexFiles.size.toString()) }
                        item { KVRow("Native libs",   a.nativeLibs.size.toString()) }
                        item { KVRow("Resources",     a.resourceFiles.toString()) }
                        item { KVRow("Assets",        a.assetFiles.toString()) }
                        item { KVRow("Total entries", a.entries.size.toString()) }
                        item { Spacer(Modifier.height(12.dp)); SectionHeader("Signing") }
                        item { KVRow("Signature", a.signingInfo) }
                        if (a.nativeLibs.isNotEmpty()) {
                            item { Spacer(Modifier.height(12.dp)); SectionHeader("Native Libraries (${a.nativeLibs.size})") }
                            items(a.nativeLibs) { lib -> MonoRow(lib) }
                        }
                    }
                    "Manifest" -> {
                        item {
                            val xml = a.manifest?.rawXml ?: "(no manifest)"
                            Text(
                                text = xml,
                                color = AText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                    "Permissions" -> {
                        val perms = a.manifest?.permissions ?: emptyList()
                        if (perms.isEmpty()) {
                            item { Text("No permissions declared", color = ADim, fontSize = 12.sp) }
                        } else {
                            item { SectionHeader("Permissions (${perms.size})") }
                            items(perms) { perm ->
                                val isDangerous = perm.contains("CAMERA") || perm.contains("LOCATION") ||
                                    perm.contains("CONTACTS") || perm.contains("CALL") ||
                                    perm.contains("SMS") || perm.contains("STORAGE") ||
                                    perm.contains("MICROPHONE") || perm.contains("RECORD")
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(if (isDangerous) ARed else AGreen, RoundedCornerShape(50)))
                                    Spacer(Modifier.width(8.dp))
                                    Text(perm.removePrefix("android.permission."), color = if (isDangerous) ARed else AText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider(color = ADivider, thickness = 0.5.dp)
                            }
                        }
                    }
                    "Components" -> {
                        val m = a.manifest
                        if (m != null) {
                            if (m.activities.isNotEmpty()) {
                                item { SectionHeader("Activities (${m.activities.size})") }
                                items(m.activities) { MonoRow(it) }
                            }
                            if (m.services.isNotEmpty()) {
                                item { Spacer(Modifier.height(8.dp)); SectionHeader("Services (${m.services.size})") }
                                items(m.services) { MonoRow(it) }
                            }
                            if (m.receivers.isNotEmpty()) {
                                item { Spacer(Modifier.height(8.dp)); SectionHeader("Receivers (${m.receivers.size})") }
                                items(m.receivers) { MonoRow(it) }
                            }
                            if (m.providers.isNotEmpty()) {
                                item { Spacer(Modifier.height(8.dp)); SectionHeader("Providers (${m.providers.size})") }
                                items(m.providers) { MonoRow(it) }
                            }
                            if (m.features.isNotEmpty()) {
                                item { Spacer(Modifier.height(8.dp)); SectionHeader("Features (${m.features.size})") }
                                items(m.features) { MonoRow(it) }
                            }
                        } else {
                            item { Text("No manifest parsed", color = ADim, fontSize = 12.sp) }
                        }
                    }
                    "Files" -> {
                        val grouped = a.entries.groupBy { it.category }.entries.sortedBy { it.key }
                        grouped.forEach { (cat, catEntries) ->
                            item {
                                Spacer(Modifier.height(8.dp))
                                SectionHeader("$cat (${catEntries.size})")
                            }
                            items(catEntries.take(200)) { entry ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.path, color = AText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatSize(entry.size), color = ADim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                HorizontalDivider(color = ADivider, thickness = 0.3.dp)
                            }
                            if (catEntries.size > 200) {
                                item { Text("…and ${catEntries.size - 200} more", color = ADim, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helper composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable private fun SectionHeader(title: String) {
    Text(title, color = Color(0xFF007ACC), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable private fun KVRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = Color(0xFF808080), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(120.dp))
        Text(value, color = Color(0xFFD4D4D4), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 2)
    }
    HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 0.5.dp)
}

@Composable private fun MonoRow(text: String) {
    Text(text, color = Color(0xFFD4D4D4), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 0.3.dp)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
    else                -> "$bytes B"
}
