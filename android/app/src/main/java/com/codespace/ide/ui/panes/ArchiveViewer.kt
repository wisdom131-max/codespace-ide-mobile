package com.codespace.ide.ui.panes

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codespace.ide.util.AxmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

// Same palette as ExplorerPane, kept local so this stays a self-contained, drop-in viewer.
private val ArchBg        = Color(0xFF1E1E1E)
private val ArchSurface   = Color(0xFF252526)
private val ArchText      = Color(0xFFD4D4D4)
private val ArchMuted     = Color(0xFF969696)
private val ArchFolder    = Color(0xFFDCB67A)
private val ArchAccent    = Color(0xFF007ACC)
private val ArchDivider   = Color(0xFF3C3C3C)

private const val MAX_TEXT_PREVIEW_BYTES = 5 * 1024 * 1024 // 5MB cap — stream, never load unbounded

private val TEXT_EXTENSIONS = setOf(
    "xml", "txt", "json", "mf", "properties", "gradle", "kt", "kts", "java",
    "smali", "cfg", "pro", "md", "yml", "yaml", "js", "ts", "html", "css",
)

data class ArchiveNode(
    val name: String,
    val fullPath: String,      // path inside the archive, e.g. "res/layout/main.xml"
    val isDirectory: Boolean,
    val size: Long,
    val depth: Int,
    val children: MutableList<ArchiveNode> = mutableListOf(),
)

private fun buildArchiveTree(zip: ZipFile): ArchiveNode {
    val root = ArchiveNode("/", "", isDirectory = true, size = 0, depth = -1)
    val dirIndex = HashMap<String, ArchiveNode>().apply { put("", root) }

    fun getOrCreateDir(path: String): ArchiveNode {
        dirIndex[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath == path) root else getOrCreateDir(parentPath)
        val name = path.substringAfterLast('/')
        val node = ArchiveNode(name, path, isDirectory = true, size = 0, depth = parent.depth + 1)
        parent.children.add(node)
        dirIndex[path] = node
        return node
    }

    val entries = zip.entries().toList()
    for (entry in entries) {
        val cleanPath = entry.name.trimEnd('/')
        if (entry.isDirectory) {
            getOrCreateDir(cleanPath)
        } else {
            val parentPath = cleanPath.substringBeforeLast('/', "")
            val parent = getOrCreateDir(parentPath)
            val name = cleanPath.substringAfterLast('/')
            parent.children.add(ArchiveNode(name, cleanPath, isDirectory = false, size = entry.size, depth = parent.depth + 1))
        }
    }
    fun sortRec(n: ArchiveNode) {
        n.children.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        n.children.forEach { if (it.isDirectory) sortRec(it) }
    }
    sortRec(root)
    return root
}

private fun flatten(node: ArchiveNode, expanded: Set<String>, out: MutableList<ArchiveNode>) {
    for (child in node.children) {
        out.add(child)
        if (child.isDirectory && expanded.contains(child.fullPath)) {
            flatten(child, expanded, out)
        }
    }
}

private fun iconFor(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        name == "AndroidManifest.xml" -> Icons.Default.Policy
        ext == "dex" -> Icons.Default.Code
        ext == "arsc" -> Icons.Default.Storage
        ext == "so" -> Icons.Default.Memory
        ext in setOf("png", "jpg", "jpeg", "webp") -> Icons.Default.Image
        ext in TEXT_EXTENSIONS -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

/**
 * Fullscreen archive/APK browser — opens .zip and .apk files like ZArchiver/MT Manager do:
 * browse the internal file tree, and for AndroidManifest.xml specifically, decode the
 * compiled binary XML into readable text (that decode step is the actual "disassemble" —
 * everything else in an APK, other than classes.dex and resources.arsc, is already plain
 * files once unzipped).
 */
@Composable
fun ArchiveViewerDialog(archivePath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var root by remember { mutableStateOf<ArchiveNode?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var viewingPath by remember { mutableStateOf<String?>(null) }
    var viewingTitle by remember { mutableStateOf("") }
    var viewingText by remember { mutableStateOf<String?>(null) }
    var viewingBinaryInfo by remember { mutableStateOf<ArchiveNode?>(null) }
    var isLoadingEntry by remember { mutableStateOf(false) }

    LaunchedEffect(archivePath) {
        withContext(Dispatchers.IO) {
            try {
                ZipFile(File(archivePath)).use { zip -> root = buildArchiveTree(zip) }
            } catch (e: Exception) {
                loadError = "Not a valid zip/apk archive: ${e.message}"
            }
        }
    }

    fun openEntry(node: ArchiveNode) {
        val ext = node.name.substringAfterLast('.', "").lowercase()
        val isManifest = node.name == "AndroidManifest.xml"
        if (!isManifest && ext !in TEXT_EXTENSIONS) {
            viewingBinaryInfo = node
            return
        }
        isLoadingEntry = true
        viewingPath = node.fullPath
        viewingTitle = node.name
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    ZipFile(File(archivePath)).use { zip ->
                        val entry = zip.getEntry(node.fullPath) ?: return@use "(entry not found)"
                        zip.getInputStream(entry).use { stream ->
                            if (isManifest) {
                                AxmlDecoder.decodeToXmlString(stream)
                            } else {
                                readTextStreaming(stream, MAX_TEXT_PREVIEW_BYTES)
                            }
                        }
                    }
                } catch (e: Exception) {
                    "(failed to read: ${e.message})"
                }
            }
            viewingText = text
            isLoadingEntry = false
        }
    }

    fun extractEntry(node: ArchiveNode) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val downloadDir = File("/storage/emulated/0/Download")
                    if (!downloadDir.exists()) downloadDir.mkdirs()
                    val outFile = File(downloadDir, node.name)
                    ZipFile(File(archivePath)).use { zip ->
                        val entry = zip.getEntry(node.fullPath) ?: return@use false
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                // Streamed copy — safe for large files like classes.dex on 3GB devices.
                                val buf = ByteArray(65536)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    true
                } catch (_: Exception) { false }
            }
            Toast.makeText(
                context,
                if (ok) "Extracted to Downloads/${node.name}" else "Extraction failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(ArchBg)) {
            // ── Title bar ──────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(ArchSurface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FolderZip, null, tint = ArchAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    File(archivePath).name, color = ArchText, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.Close, null, tint = ArchMuted,
                    modifier = Modifier.size(20.dp).clickable { onDismiss() })
            }
            HorizontalDivider(color = ArchDivider, thickness = 1.dp)

            when {
                loadError != null -> {
                    Text(loadError!!, color = Color(0xFFCC6666), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
                root == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ArchAccent, modifier = Modifier.size(28.dp))
                    }
                }
                else -> {
                    val flat = remember(root, expanded) {
                        mutableListOf<ArchiveNode>().also { flatten(root!!, expanded, it) }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(flat) { node ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (node.isDirectory) {
                                            expanded = if (expanded.contains(node.fullPath)) {
                                                expanded - node.fullPath
                                            } else expanded + node.fullPath
                                        } else {
                                            openEntry(node)
                                        }
                                    }
                                    .padding(start = (12 + node.depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (node.isDirectory) {
                                    Icon(
                                        if (expanded.contains(node.fullPath)) Icons.Default.KeyboardArrowDown
                                        else Icons.Default.ChevronRight,
                                        null, tint = ArchMuted, modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(Icons.Default.Folder, null, tint = ArchFolder, modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(iconFor(node.name), null, tint = ArchMuted, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    node.name, color = ArchText, fontSize = 13.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                if (!node.isDirectory) {
                                    Text(formatSize(node.size), color = ArchMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Decoded / raw text viewer overlay ─────────────────────────
        if (viewingPath != null) {
            Dialog(onDismissRequest = { viewingPath = null; viewingText = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(Modifier.fillMaxSize().background(ArchBg)) {
                    Row(
                        Modifier.fillMaxWidth().background(ArchSurface).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(viewingTitle, color = ArchText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Close, null, tint = ArchMuted,
                            modifier = Modifier.size(20.dp).clickable { viewingPath = null; viewingText = null })
                    }
                    HorizontalDivider(color = ArchDivider, thickness = 1.dp)
                    if (isLoadingEntry) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ArchAccent, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                viewingText ?: "",
                                color = ArchText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Binary entry info sheet ───────────────────────────────────
        if (viewingBinaryInfo != null) {
            val node = viewingBinaryInfo!!
            AlertDialog(
                onDismissRequest = { viewingBinaryInfo = null },
                containerColor = ArchSurface,
                title = { Text(node.name, color = ArchText, fontSize = 15.sp) },
                text = {
                    Column {
                        Text("Binary file — ${formatSize(node.size)}", color = ArchMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(node.fullPath, color = ArchMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { extractEntry(node); viewingBinaryInfo = null }) {
                        Text("Extract to Downloads", color = ArchAccent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewingBinaryInfo = null }) { Text("Close", color = ArchMuted) }
                },
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun readTextStreaming(input: java.io.InputStream, maxSize: Int): String {
    val buffer = java.io.ByteArrayOutputStream(minOf(maxSize, 65536))
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > maxSize) { buffer.write("\n\n… (truncated, file too large to preview)".toByteArray()); break }
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray().toString(Charsets.UTF_8)
}
