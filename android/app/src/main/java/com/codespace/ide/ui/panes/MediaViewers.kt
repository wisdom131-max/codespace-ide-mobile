package com.codespace.ide.ui.panes

import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile

// ─────────────────────────────────────────────────────────────────────────────
// File-type routing (hard-bucket #10) — every branch below exists specifically
// so binary files stop being dumped into the plain-text editor, which either
// renders garbage or risks an OOM/hang on larger binaries (whole file read into
// one Compose-editable String). Video/audio/fonts/archives/compiled binaries/DB
// files each get a real, purpose-built viewer instead.
// ─────────────────────────────────────────────────────────────────────────────
fun isVideoFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("mp4", "webm", "mov", "mkv", "m4v", "3gp", "avi", "flv")
}

fun isAudioFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "wma")
}

/** Compiled binaries, databases, fonts — no dedicated viewer built yet, so these get a raw
 * hex dump instead of silently corrupting/crashing the text editor. Explicitly includes
 * `.dex`, which the triage doc flagged as forcing an external download instead of opening
 * in-app (`.apk` already works fine via the archive browser for comparison). */
fun isHexViewFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf(
        "class", "o", "a", "bin", "dat", "exe", "dll",
        "img", "iso", "apkm", "p12", "pfx", "jks",
        "ttf", "otf", "woff", "woff2",
    )
}

/** SQLite database files — opened in SqliteViewerDialog instead of hex or text editor. */
fun isSqliteFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("db", "sqlite", "sqlite3")
}

/** DEX bytecode files — opened in DexViewerDialog (Phase 21-X). */
fun isDexFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("dex", "odex", "vdex")
}

/** ELF binary files — opened in ElfViewerDialog (Phase 21-X). */
fun isElfFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext == "so" || ext == "elf" || (ext.isEmpty() && !name.contains('.'))
}

fun isApkAnalyzable(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".apk") || n.endsWith(".xapk") || n.endsWith(".apks")
}

fun isSmaliSource(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".smali")
}

fun isDisassemblable(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".so") || n.endsWith(".elf") || n.endsWith(".o") || n.endsWith(".ko")
}

/** Safety net for anything NOT covered by the explicit lists above (image/archive/pdf/video/
 * audio/hex) — sniffs the first few KB for a NUL byte, which never legitimately appears in
 * text source files but is extremely common in arbitrary/unknown binary formats. Catches file
 * types nobody explicitly listed instead of letting them silently reach the text editor. */
fun sniffLooksBinary(path: String): Boolean = try {
    val file = File(path)
    if (!file.exists() || file.isDirectory) false
    else {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(raf.length(), 8192L).toInt()
            val buf = ByteArray(len)
            raf.readFully(buf)
            buf.any { it == 0.toByte() }
        }
    }
} catch (_: Exception) { false }

// ─────────────────────────────────────────────────────────────────────────────
// Video Player — native VideoView + MediaController (play/pause/seek/fullscreen
// scrub bar built in). No new Gradle dependency (no ExoPlayer) — keeps APK size
// and memory footprint down, matching this device's constraints.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VideoPlayerDialog(videoPath: String, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    // Rotation fix (#8): key on orientation so this fullscreen Dialog gets a fresh,
    // correctly-sized window on rotate instead of a stuck stale one (same pattern used
    // for ArchiveViewer/PreviewPane/ExplorerPane's context menu).
    val orientation = LocalConfiguration.current.orientation
    key(orientation) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    File(videoPath).name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                    modifier = Modifier.size(20.dp).clickable { onDismiss() })
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                if (error != null) {
                    Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnErrorListener { _, _, _ -> error = "Couldn't play this video — unsupported codec or corrupt file"; true }
                                try {
                                    setVideoURI(Uri.fromFile(File(videoPath)))
                                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                    setOnPreparedListener { it.isLooping = false; start() }
                                } catch (e: Exception) {
                                    error = "Couldn't open video: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Audio Player — MediaPlayer + a small Compose transport UI (play/pause, seek
// slider, elapsed/total time). Same pattern as VideoView: no new dependency.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioPlayerDialog(audioPath: String, onDismiss: () -> Unit) {
    val _context = LocalContext.current
    // Rotation fix (#8): see VideoPlayerDialog above for rationale.
    val orientation = LocalConfiguration.current.orientation
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var isPrepared by remember { mutableStateOf(false) }

    val player = remember {
        try {
            MediaPlayer().apply {
                setDataSource(audioPath)
                setOnPreparedListener { durationMs = it.duration; isPrepared = true }
                setOnErrorListener { _, _, _ -> error = "Couldn't play this audio file — unsupported codec or corrupt file"; true }
                setOnCompletionListener { isPlaying = false; positionMs = 0 }
                prepareAsync()
            }
        } catch (e: Exception) {
            error = "Couldn't open audio: ${e.message}"
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose { try { player?.release() } catch (_: Exception) {} }
    }

    // Poll playback position while playing — MediaPlayer has no position-changed callback.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try { positionMs = player?.currentPosition ?: 0 } catch (_: Exception) {}
            delay(300)
        }
    }

    fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    key(orientation) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth(0.9f).background(Color(0xFF1E1E1E), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    File(audioPath).name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                    modifier = Modifier.size(20.dp).clickable { onDismiss() })
            }
            Spacer(Modifier.height(16.dp))
            when {
                error != null -> Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                !isPrepared -> Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF569CD6))
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Color(0xFF569CD6),
                            modifier = Modifier.size(40.dp).clickable {
                                try {
                                    if (isPlaying) player?.pause() else player?.start()
                                    isPlaying = !isPlaying
                                } catch (_: Exception) {}
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Slider(
                                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                                onValueChange = { v ->
                                    positionMs = v.toInt()
                                    try { player?.seekTo(positionMs) } catch (_: Exception) {}
                                },
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF569CD6), activeTrackColor = Color(0xFF569CD6)),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fmt(positionMs), color = Color(0xFF888888), fontSize = 11.sp)
                                Text(fmt(durationMs), color = Color(0xFF888888), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hex Viewer — fallback for compiled binaries, databases, fonts, and anything the
// binary-sniff safety net catches. Reads a capped window (256KB) so opening a huge
// .so/.dex never risks OOM on this device — shows a "truncated" notice if the file
// is bigger than that window.
// ─────────────────────────────────────────────────────────────────────────────
private const val HEX_VIEW_CAP = 256 * 1024

@Composable
fun HexViewerDialog(filePath: String, onDismiss: () -> Unit) {
    // Rotation fix (#8): see VideoPlayerDialog above for rationale.
    val orientation = LocalConfiguration.current.orientation
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var totalSize by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        try {
            val file = File(filePath)
            totalSize = file.length()
            val readLen = minOf(totalSize, HEX_VIEW_CAP.toLong()).toInt()
            truncated = totalSize > readLen
            val buf = ByteArray(readLen)
            RandomAccessFile(file, "r").use { it.readFully(buf) }
            rows = buf.toList().chunked(16).mapIndexed { i, chunk ->
                val offset = (i * 16)
                val hex = chunk.joinToString(" ") { b -> "%02X".format(b) }.padEnd(47)
                val ascii = chunk.joinToString("") { b ->
                    val c = b.toInt().toChar()
                    if (c.code in 32..126) c.toString() else "."
                }
                "%08X  %s  %s".format(offset, hex, ascii)
            }
        } catch (e: Exception) {
            error = "Couldn't read file: ${e.message}"
        }
    }

    key(orientation) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        File(filePath).name, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("$totalSize bytes" + if (truncated) " (showing first $HEX_VIEW_CAP)" else "",
                        color = Color(0xFF888888), fontSize = 10.sp)
                }
                Icon(Icons.Default.Close, null, tint = Color(0xFFCCCCCC),
                    modifier = Modifier.size(20.dp).clickable { onDismiss() })
            }
            HorizontalDivider(color = Color(0xFF3A3A3A))
            Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
                when {
                    error != null -> Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                    rows.isEmpty() -> CircularProgressIndicator(color = Color(0xFF569CD6), modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        items(rows) { row ->
                            Text(row, color = Color(0xFF4EC9B0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
    }
}

fun isEntropyViewable(name: String): Boolean =
    !name.endsWith(".kt") && !name.endsWith(".java") && !name.endsWith(".md") &&
    !name.endsWith(".txt") && !name.endsWith(".xml") && !name.endsWith(".json") &&
    !name.endsWith(".yaml") && !name.endsWith(".yml") && !name.endsWith(".toml") &&
    !name.endsWith(".html") && !name.endsWith(".css") && !name.endsWith(".js")

fun isNetworkCapture(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".pcap") || low.endsWith(".pcapng") || low.endsWith(".cap") || low.endsWith(".har")
}

fun isAiModel(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".gguf") || low.endsWith(".safetensors") || low.endsWith(".onnx")
}

fun isAndroidRuntimeFile(name: String): Boolean {
    val low = name.lowercase()
    return low.endsWith(".oat") || low.endsWith(".odex") || low.endsWith(".vdex") ||
           low.endsWith(".apex") || low.endsWith(".capex") || low.endsWith(".art")
}

