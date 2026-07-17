package com.codespace.ide.ui.panes

import com.codespace.ide.util.WorkspaceManager
import com.codespace.ide.debug.UniversalDebugManager
import com.codespace.ide.debug.DebugState
import com.codespace.ide.debug.DebugVariable
import com.codespace.ide.debug.DebugStackFrame
import com.codespace.ide.debug.DebugWatch
import com.codespace.ide.debug.DebugBreakpoint

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.documentfile.provider.DocumentFile
import android.content.ClipboardManager
import android.content.ClipData
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

private val BgColor      = Color(0xFFFFFFFF)
private val SelectedBg   = Color(0xFFCCE5FF)
private val TextColor    = Color(0xFF333333)
private val MutedColor   = Color(0xFF717171)
private val DividerColor = Color(0xFFE0E0E0)
private val FolderColor  = Color(0xFFDCB67A)
private val IconColor    = Color(0xFF007ACC)
private val BlueBtn      = Color(0xFF007ACC)

private const val PREFS_WORKSPACE = "workspace_prefs"
private const val KEY_WORKSPACE   = "workspace_path"
private const val KEY_WORKSPACE_ROOTS = "workspace_roots"

// Per-project workspace state isolation: every key is scoped by projectId so switching
// projects never leaks another project's last-browsed folder/roots (fixes HARD BATCH #1).
private fun saveWorkspacePath(context: Context, projectId: String, path: String) {
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .edit().putString("${KEY_WORKSPACE}_$projectId", path).apply()
}

private fun loadWorkspacePath(context: Context, projectId: String): String? =
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .getString("${KEY_WORKSPACE}_$projectId", null)

// ── Multi-root workspace support ──
private fun saveWorkspaceRoots(context: Context, projectId: String, roots: List<String>) {
    context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .edit().putString("${KEY_WORKSPACE_ROOTS}_$projectId", roots.joinToString("|||")).apply()
}

private fun loadWorkspaceRoots(context: Context, projectId: String): List<String> {
    val raw = context.getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
        .getString("${KEY_WORKSPACE_ROOTS}_$projectId", null) ?: return emptyList()
    return raw.split("|||").filter { it.isNotBlank() }
}

// ── Device quick-access folders ──
private val DEVICE_FOLDERS = listOf(
    "Pictures" to "/storage/emulated/0/Pictures",
    "DCIM (Camera)" to "/storage/emulated/0/DCIM",
    "Downloads" to "/storage/emulated/0/Download",
    "Documents" to "/storage/emulated/0/Documents",
    "Music" to "/storage/emulated/0/Music",
    "Movies" to "/storage/emulated/0/Movies",
)

private fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "tiff", "tif")
}

private fun isArchiveFile(name: String): Boolean {
    val ext = name.substringAfterLast(".", "").lowercase()
    return ext in listOf("zip", "apk", "jar", "aar", "rar", "7z", "tar", "gz", "bz2", "xz", "xapk", "apks")
}

private fun isPdfFile(name: String): Boolean = name.substringAfterLast(".", "").lowercase() == "pdf"

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) { null }
}

/** Copies a picked image Uri into targetDir, avoiding filename collisions. Returns the new File or null. */
private fun copyImageUriToFolder(context: Context, uri: Uri, targetDir: File): File? {
    return try {
        val rawName = queryDisplayName(context, uri) ?: "image_${System.currentTimeMillis()}.jpg"
        var dest = File(targetDir, rawName)
        if (dest.exists()) {
            val base = rawName.substringBeforeLast(".", rawName)
            val ext  = rawName.substringAfterLast(".", "")
            var i = 1
            while (dest.exists()) {
                dest = File(targetDir, if (ext.isNotEmpty()) "${base}_$i.$ext" else "${base}_$i")
                i++
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest
    } catch (e: Exception) { null }
}

private fun loadImageBitmap(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val file = File(path)
        if (!file.exists() || file.length() > 10 * 1024 * 1024) return null // 10MB limit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source).asImageBitmap()
        } else {
            @Suppress("DEPRECATION")
            BitmapFactory.decodeFile(path).asImageBitmap()
        }
    } catch (_: Exception) { null }
}

data class FsNode(
    val file: File,
    val depth: Int,
    val isExpanded: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerSidePanel(
    projectId: String,
    onOpenFile: (String) -> Unit,
    onFileRenamed: ((oldPath: String, newPath: String) -> Unit)? = null,
    onOpenFileAtLine: ((String, Int) -> Unit)? = null,
    onMoreMenu: () -> Unit,
    onOpenInTerminal: (String) -> Unit = {},
    openTabs: List<String> = emptyList(),
    activeFilePath: String? = null,
    onCloseTab: ((String) -> Unit)? = null,
    // fix/feature 2026-07-08: "Generate Image with AI Here" needs the user's Gemini key.
    tokenStore: com.codespace.ide.data.SecureTokenStore? = null,
    /** Breadcrumb navigation: when set, auto-expand and scroll to this directory path. */
    navigateToDir: String? = null,
) {
    val context = LocalContext.current
    // Rotation fix (#8): Compose Dialog/AlertDialog windows don't resize when the
    // Activity itself doesn't recreate on rotation (configChanges="orientation|screenSize"
    // in the manifest). Keying on orientation forces a full subtree rebuild so Android
    // creates a fresh, correctly-sized window (and fresh scroll state) every rotation.
    val orientation = LocalConfiguration.current.orientation

    var workspacePath by remember(projectId) {
        mutableStateOf(loadWorkspacePath(context, projectId))
    }
    val workspaceRoot = remember(workspacePath) {
        workspacePath?.let { File(it) }
    }

    // ── Multi-root workspace ──
    var workspaceRoots by remember {
        mutableStateOf(loadWorkspaceRoots(context, projectId))
    }
    var showDeviceFolders by remember { mutableStateOf(false) }

    // ── Image preview state ──
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var previewArchivePath by remember { mutableStateOf<String?>(null) }
    var previewPdfPath by remember { mutableStateOf<String?>(null) }
    var previewVideoPath by remember { mutableStateOf<String?>(null) }
    var previewAudioPath by remember { mutableStateOf<String?>(null) }
    var previewHexPath by remember { mutableStateOf<String?>(null) }
    var previewSqlitePath by remember { mutableStateOf<String?>(null) }
    var previewDexPath      by remember { mutableStateOf<String?>(null) }
    var previewElfPath      by remember { mutableStateOf<String?>(null) }
    // Phase 21 Step 4 — file info + fallback viewers
    var showFileInfoDialog    by remember { mutableStateOf(false) }
    var previewStringsPath    by remember { mutableStateOf<String?>(null) }
    var previewBinaryPath     by remember { mutableStateOf<String?>(null) }
    // Phase 21-X — APK analyzer, Smali viewer, Disassembly viewer
    var previewApkPath        by remember { mutableStateOf<String?>(null) }
    var previewSmaliPath      by remember { mutableStateOf<String?>(null) }
    var previewDisasmPath     by remember { mutableStateOf<String?>(null) }
    // Phase 21-X Step 4 — Entropy Heatmap, Network Viewer, AI Model Viewer
    var previewEntropyPath    by remember { mutableStateOf<String?>(null) }
    var previewNetworkPath    by remember { mutableStateOf<String?>(null) }
    var previewAiModelPath    by remember { mutableStateOf<String?>(null) }
    // Phase 21-X Step 9 — Android Runtime Viewer (OAT/VDEX/APEX)
    var previewArtPath        by remember { mutableStateOf<String?>(null) }
    val previewAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Track preview lifecycle
    LaunchedEffect(previewImagePath) {
        if (previewImagePath != null) {
            previewAlpha.snapTo(0f)
            previewAlpha.animateTo(1f, tween(200))
            // Auto-dismiss after 3 seconds if still showing
            delay(3000)
            previewAlpha.animateTo(0f, tween(400))
            previewImagePath = null
        }
    }

    val expanded      = remember { mutableStateMapOf<String, Boolean>() }
    val treeListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Breadcrumb navigation: when navigateToDir changes, expand all ancestor dirs
    // and scroll the tree to the target directory.
    LaunchedEffect(navigateToDir) {
        val targetPath = navigateToDir ?: return@LaunchedEffect
        // Build ancestry chain and expand each ancestor
        var dir = java.io.File(targetPath)
        while (dir.parent != null) {
            expanded[dir.absolutePath] = true
            dir = dir.parentFile ?: break
        }
        // Give the tree a frame to recompose, then scroll to the target item
        kotlinx.coroutines.delay(100)
        // nodes is recomputed from expanded — find the index of the target
        // We can't reference `nodes` here (it's computed below), so we do a
        // best-effort scroll by counting visible dirs above the target.
        val workspaceRoot = workspacePath?.let { java.io.File(it) } ?: return@LaunchedEffect
        var idx = 0
        fun walk(f: java.io.File, depth: Int) {
            idx++
            if (f.absolutePath == targetPath) return
            if (expanded[f.absolutePath] == true && f.isDirectory) {
                f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { child -> walk(child, depth + 1) }
            }
        }
        workspaceRoot.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { walk(it, 0) }
        treeListState.animateScrollToItem(idx.coerceAtLeast(0))
    }
    var selected      by remember { mutableStateOf<String?>(null) }
    var contextFile   by remember { mutableStateOf<File?>(null) }
    var showCtxMenu   by remember { mutableStateOf(false) }
    var showHistoryDialog  by remember { mutableStateOf(false) }
    var historyFile        by remember { mutableStateOf<File?>(null) }
    var historySnapshots   by remember { mutableStateOf<List<File>>(emptyList()) }
    var showTrashDialog    by remember { mutableStateOf(false) }
    var trashEntries       by remember { mutableStateOf<List<WorkspaceManager.TrashEntry>>(emptyList()) }
    // P17-B Compress
    var showCompressDialog by remember { mutableStateOf(false) }
    // P17-C Permissions
    var showPermDialog     by remember { mutableStateOf(false) }
    var showNewFile   by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showRename    by remember { mutableStateOf(false) }
    var showDelete    by remember { mutableStateOf(false) }
    var nameInput     by remember { mutableStateOf("") }
    var refresh       by remember { mutableStateOf(0) }
    var filterQuery   by remember { mutableStateOf("") }
    var sortByType    by remember { mutableStateOf(false) }
    var showOutline   by remember { mutableStateOf(false) }
    var clipboardFile by remember { mutableStateOf<File?>(null) }
    var clipboardCut  by remember { mutableStateOf(false) }
    var gitStatus     by remember { mutableStateOf<Map<String, Char>>(emptyMap()) }
    // Local-vs-GitHub badge (UI bucket #6) — null = no .git at all or a .git with no
    // "origin" remote configured (local-only project, e.g. the default /root/my-video).
    // Non-null = "owner/repo" parsed from .git/config's [remote "origin"] url, shown as a
    // small badge next to the folder name. Existing local projects are left untouched —
    // this only reads state, it never creates or forces a remote.
    var gitRemoteRepo by remember { mutableStateOf<String?>(null) }
    var pendingImageTargetDir by remember { mutableStateOf<File?>(null) }
    var importingImages by remember { mutableStateOf(false) }
    // "Generate Image with AI Here" (2026-07-08) — see ImageGenDialog.kt / ImageGenService.kt
    var pendingAiImageTargetDir by remember { mutableStateOf<File?>(null) }
    var showAiImageGen by remember { mutableStateOf(false) }
    var binaryDiffFileA   by remember { mutableStateOf<java.io.File?>(null) }
    var binaryDiffFileB   by remember { mutableStateOf<java.io.File?>(null) }

    // Folder picker launcher — adds to multi-root workspace
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Convert to real path
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val realPath = uri.path?.let { p ->
                val split = p.split(":")
                if (split.size >= 2) {
                    val type = split[0].substringAfterLast("/")
                    val rel  = split[1]
                    if (type == "primary") "/storage/emulated/0/$rel"
                    else "/storage/$type/$rel"
                } else null
            } ?: docFile?.name?.let { name -> "/storage/emulated/0/$name" }

            realPath?.let {
                workspacePath = it
                saveWorkspacePath(context, projectId, it)
                // Add to multi-root list (avoid duplicates)
                if (it !in workspaceRoots) {
                    workspaceRoots = workspaceRoots + it
                    saveWorkspaceRoots(context, projectId, workspaceRoots)
                }
                expanded.clear()
                refresh++
            }
        }
    }

    // Image picker — pick one or more images from device storage (Photos/Files) and
    // copy them into whichever folder was long-pressed ("Import Image(s) Here"), or the
    // project root if launched from the toolbar button.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val targetDir = pendingImageTargetDir ?: workspaceRoot
            if (targetDir != null) {
                importingImages = true
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        uris.forEach { uri -> copyImageUriToFolder(context, uri, targetDir) }
                    }
                    importingImages = false
                    refresh++
                }
            }
        }
        pendingImageTargetDir = null
    }

    // Create Document launcher — opens Android file picker to create a new file
    var pendingFileName by remember { mutableStateOf("") }
    var pendingTargetDir by remember { mutableStateOf<java.io.File?>(null) }
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Write empty content to the SAF uri
                context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf()) }
                // Use the captured target dir
                val targetDir = pendingTargetDir ?: workspaceRoot
                if (targetDir != null && pendingFileName.isNotBlank()) {
                    val localFile = java.io.File(targetDir, pendingFileName)
                    try {
                        if (!localFile.exists()) localFile.createNewFile()
                        refresh++
                        onOpenFile(localFile.absolutePath)
                    } catch (e: Exception) {
                        // fallback to SAF uri path
                        val realPath = uri.path?.let { p ->
                            val split = p.split(":")
                            if (split.size >= 2) "/storage/emulated/0/${split[1]}" else null
                        }
                        refresh++
                        onOpenFile(realPath ?: uri.toString())
                    }
                } else {
                    refresh++
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun buildNodes(dir: File, depth: Int): List<FsNode> {
        val nodes = mutableListOf<FsNode>()
        val isExp = expanded[dir.absolutePath] ?: false
        nodes.add(FsNode(dir, depth, isExp))
        if (isExp) {
            val children = dir.listFiles()
                ?.filter { !it.name.trimEnd().startsWith(".") }
                ?.filter { f ->
                    if (filterQuery.isBlank()) true
                    else f.name.contains(filterQuery, ignoreCase = true) ||
                         (f.isDirectory && f.walkTopDown().any { it.name.contains(filterQuery, ignoreCase = true) })
                }
                ?.sortedWith(
                    if (sortByType) compareByDescending<File> { it.isDirectory }.thenBy { it.extension }.thenBy { it.name }
                    else compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() }
                )
                ?: emptyList()
            children.forEach { child ->
                if (child.isDirectory) nodes.addAll(buildNodes(child, depth + 1))
                else nodes.add(FsNode(child, depth + 1))
            }
        }
        return nodes
    }

    // Read git status for badges
    LaunchedEffect(workspacePath, refresh) {
        val wsSnap = workspacePath  // capture to local val for smart cast
        if (wsSnap != null) {
            try {
                val gitDir = File(wsSnap, ".git")
                if (gitDir.exists()) {
                    val statusMap = mutableMapOf<String, Char>()
                    val guestPath = com.codespace.ide.terminal.ProotInstaller.hostToGuestPath(context, wsSnap)
                    val output = if (guestPath != null)
                        com.codespace.ide.terminal.ProotInstaller.execOnce(context, "cd '$guestPath' && git status --porcelain 2>/dev/null", timeoutSeconds = 15L)
                    else ""
                    for (line in output.lines()) {
                        if (line.length < 4) continue
                        val status = line[0]
                        val filePath = line.substring(3).trim()
                        val absPath = File(wsSnap, filePath).absolutePath
                        statusMap[absPath] = status
                    }
                    gitStatus = statusMap

                    // Parse .git/config for an "origin" remote pointing at GitHub — local-only
                    // projects (like the default /root/my-video) simply have no such remote and
                    // gitRemoteRepo stays null, so no badge is shown for them.
                    val configFile = File(gitDir, "config")
                    gitRemoteRepo = if (configFile.exists()) {
                        val cfg = configFile.readText()
                        val originBlock = Regex("""\[remote "origin"]([\s\S]*?)(\[|$)""").find(cfg)?.groupValues?.get(1)
                        val urlLine = originBlock?.let { Regex("""url\s*=\s*(.+)""").find(it)?.groupValues?.get(1)?.trim() }
                        urlLine?.let { url ->
                            Regex("""github\.com[:/]+([^/]+/[^/.\s]+)""").find(url)?.groupValues?.get(1)
                        }
                    } else null
                } else {
                    gitRemoteRepo = null
                }
            } catch (_: Exception) {
                gitRemoteRepo = null
            }
        } else {
            gitRemoteRepo = null
        }
    }

    val nodes = remember(workspacePath, expanded.toMap(), refresh, filterQuery, sortByType) {
        val root = workspaceRoot ?: return@remember emptyList()
        if (!root.exists()) return@remember emptyList()
        val children = root.listFiles()
            ?.filter { !it.name.trimEnd().startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() })
            ?: emptyList()
        children.flatMap { f ->
            if (f.isDirectory) buildNodes(f, 0)
            else listOf(FsNode(f, 0))
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor)) {

        // ── Header ───────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(35.dp)
                .background(Color(0xFFF3F3F3))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showOutline) "OUTLINE" else if (workspaceRoot != null) workspaceRoot.name.uppercase()
                else "EXPLORER",
                fontSize = 11.sp, color = MutedColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            // Local-vs-GitHub badge (UI bucket #6) — only rendered for the real explorer
            // header, not the Outline mode, and only takes space when there's something to show.
            if (!showOutline && workspaceRoot != null) {
                Spacer(Modifier.width(6.dp))
                if (gitRemoteRepo != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF2DA44E).copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Cloud, null, tint = Color(0xFF2DA44E), modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(gitRemoteRepo!!, fontSize = 9.sp, color = Color(0xFF2DA44E), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
                    }
                } else if (gitStatus.isNotEmpty() || File(workspaceRoot, ".git").exists()) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MutedColor.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CloudOff, null, tint = MutedColor, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Local", fontSize = 9.sp, color = MutedColor)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (workspaceRoot != null) {
                // New File
                Icon(Icons.Default.Add, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        contextFile = workspaceRoot; showNewFile = true; nameInput = ""
                    })
                Spacer(Modifier.width(6.dp))
                // New Folder
                Icon(Icons.Default.CreateNewFolder, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        contextFile = workspaceRoot; showNewFolder = true; nameInput = ""
                    })
                Spacer(Modifier.width(6.dp))
                // Import Image(s) — pick from device Photos/Files, copy into project root
                if (importingImages) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AddPhotoAlternate, null, tint = MutedColor,
                        modifier = Modifier.size(16.dp).clickable {
                            pendingImageTargetDir = workspaceRoot
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        })
                }
                Spacer(Modifier.width(6.dp))
                // Refresh
                Icon(Icons.Default.Refresh, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable { refresh++ })
                Spacer(Modifier.width(6.dp))
                // Collapse All
                Icon(Icons.Default.UnfoldLess, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        expanded.clear()
                        refresh++
                    })
                Spacer(Modifier.width(6.dp))
                // Sort toggle (N=name, T=type)
                Box(Modifier.clickable { sortByType = !sortByType }.padding(2.dp)) {
                    Text(if (sortByType) "T" else "N", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                // Outline toggle
                Icon(Icons.Default.List, null, tint = if (showOutline) IconColor else MutedColor,
                    modifier = Modifier.size(16.dp).clickable { showOutline = !showOutline })
                Spacer(Modifier.width(6.dp))
                // Add folder to workspace (multi-root)
                Icon(Icons.Default.Add, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        folderPicker.launch(null)
                    })
                Spacer(Modifier.width(6.dp))
                // Device folders quick toggle
                Icon(Icons.Default.PhoneAndroid, null, tint = if (showDeviceFolders) IconColor else MutedColor,
                    modifier = Modifier.size(16.dp).clickable { showDeviceFolders = !showDeviceFolders })
                Spacer(Modifier.width(6.dp))
                // Change folder
                Icon(Icons.Default.OpenInNew, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        folderPicker.launch(null)
                    })
            }
            Spacer(Modifier.width(8.dp))
        }
        HorizontalDivider(color = DividerColor)

        // Redundant "Filter files..." bar removed 2026-07-06 — the app already has a
        // dedicated Search pane (magnifying glass in the activity bar) for this. filterQuery
        // state is kept (harmless, unused input surface) in case a compact inline filter is
        // reintroduced later, but no UI row is rendered here anymore.

        // ── Device folders quick-access panel ──
        if (showDeviceFolders) {
            Column(Modifier.fillMaxWidth().background(Color(0xFFF8F8F8))) {
                Text("  Device Folders", fontSize = 10.sp, color = MutedColor,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                DEVICE_FOLDERS.forEach { (label, path) ->
                    val exists = File(path).exists()
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = exists) {
                                workspacePath = path
                                saveWorkspacePath(context, projectId, path)
                                if (path !in workspaceRoots) {
                                    workspaceRoots = workspaceRoots + path
                                    saveWorkspaceRoots(context, projectId, workspaceRoots)
                                }
                                showDeviceFolders = false
                                expanded.clear()
                                refresh++
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (label) {
                                "DCIM (Camera)" -> Icons.Default.PhotoCamera
                                "Downloads" -> Icons.Default.Download
                                "Documents" -> Icons.Default.Article
                                "Music" -> Icons.Default.MusicNote
                                "Movies" -> Icons.Default.Movie
                                else -> Icons.Default.Image
                            },
                            null, tint = if (exists) IconColor else Color(0xFFCCCCCC),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 11.sp,
                            color = if (exists) TextColor else Color(0xFFCCCCCC))
                    }
                }
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }
        }

        // ── Open Editors section ──────────────────────────────────────────
        if (workspaceRoot != null && openTabs.isNotEmpty()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(24.dp)
                        .background(Color(0xFFF0F0F0))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("OPEN EDITORS", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(openTabs.size.toString(), fontSize = 10.sp, color = MutedColor)
                }
                openTabs.forEach { tabPath ->
                    val isActive = tabPath == activeFilePath
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isActive) SelectedBg else Color.Transparent)
                            .clickable { onOpenFile(tabPath) }
                            .padding(16.dp, 4.dp, 8.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(fileIcon(tabPath.substringAfterLast("/")), null, tint = IconColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tabPath.substringAfterLast("/"),
                            fontSize = 12.sp, color = TextColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.Close, null, tint = MutedColor,
                            modifier = Modifier.size(14.dp).clickable { onCloseTab?.invoke(tabPath) })
                    }
                }
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }
        }

        // ── No workspace selected ─────────────────────────────────────────
        if (workspaceRoot == null) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Folder, null, tint = Color(0xFFDDDDDD),
                    modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No folder opened", fontSize = 14.sp, color = MutedColor,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Open a folder to start working", fontSize = 12.sp,
                    color = MutedColor)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { folderPicker.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueBtn),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Folder") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Quick pick: use /storage/emulated/0
                        workspacePath = "/storage/emulated/0"
                        saveWorkspacePath(context, projectId, "/storage/emulated/0")
                        if ("/storage/emulated/0" !in workspaceRoots) {
                            workspaceRoots = workspaceRoots + "/storage/emulated/0"
                            saveWorkspaceRoots(context, projectId, workspaceRoots)
                        }
                        refresh++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use Phone Storage") }
                Spacer(Modifier.height(12.dp))
                // ── Device folder quick-access ──
                Text("Quick Access", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                DEVICE_FOLDERS.forEach { (label, path) ->
                    val exists = File(path).exists()
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = exists) {
                                workspacePath = path
                                saveWorkspacePath(context, projectId, path)
                                if (path !in workspaceRoots) {
                                    workspaceRoots = workspaceRoots + path
                                    saveWorkspaceRoots(context, projectId, workspaceRoots)
                                }
                                refresh++
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (label) {
                                "DCIM (Camera)" -> Icons.Default.PhotoCamera
                                "Downloads" -> Icons.Default.Download
                                "Documents" -> Icons.Default.Article
                                "Music" -> Icons.Default.MusicNote
                                "Movies" -> Icons.Default.Movie
                                else -> Icons.Default.Image
                            },
                            null, tint = if (exists) IconColor else Color(0xFFCCCCCC),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 12.sp,
                            color = if (exists) TextColor else Color(0xFFCCCCCC))
                        Spacer(Modifier.weight(1f))
                        if (exists) {
                            Text(File(path).listFiles()?.size?.toString() ?: "0",
                                fontSize = 10.sp, color = MutedColor)
                        }
                    }
                }
            }
        } else {
            // ── File tree ─────────────────────────────────────────────────
            // Workspace root row
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF0F0F0))
                    .clickable {
                        expanded[workspaceRoot.absolutePath] =
                            !(expanded[workspaceRoot.absolutePath] ?: true)
                        refresh++
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, null,
                    tint = MutedColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Folder, null,
                    tint = FolderColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(workspaceRoot.name, fontSize = 13.sp,
                    color = TextColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth())
            }

            // ── Multi-root workspace folders ──
            if (workspaceRoots.size > 1 || (workspaceRoots.isNotEmpty() && workspaceRoots[0] != workspacePath)) {
                workspaceRoots.filter { it != workspacePath && File(it).exists() }.forEach { rootPath ->
                    val rootFile = File(rootPath)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFF0F0F0))
                            .clickable {
                                workspacePath = rootPath
                                saveWorkspacePath(context, projectId, rootPath)
                                expanded.clear()
                                refresh++
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, null, tint = FolderColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(rootFile.name, fontSize = 11.sp, color = MutedColor, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Close, null, tint = MutedColor,
                            modifier = Modifier.size(12.dp).clickable {
                                workspaceRoots = workspaceRoots - rootPath
                                saveWorkspaceRoots(context, projectId, workspaceRoots)
                            })
                    }
                }
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }

            LazyColumn(Modifier.fillMaxSize(), state = treeListState) {
                items(nodes) { node ->
                    val isSelected = selected == node.file.absolutePath
                    // Image preview state for this node
                    val isImage = !node.file.isDirectory && isImageFile(node.file.name)
                    val isArchive = !node.file.isDirectory && isArchiveFile(node.file.name)
                    val isPdf = !node.file.isDirectory && isPdfFile(node.file.name)
                    val isVideo = !node.file.isDirectory && isVideoFile(node.file.name)
                    val isAudio = !node.file.isDirectory && isAudioFile(node.file.name)
                    val isSqlite = !node.file.isDirectory && isSqliteFile(node.file.name)
                    val isDex    = !node.file.isDirectory && isDexFile(node.file.name)
                    val isElf    = !node.file.isDirectory && isElfFile(node.file.name)
                    val isHexBin = !node.file.isDirectory && !isSqlite && !isDex && !isElf && (isHexViewFile(node.file.name) || sniffLooksBinary(node.file.absolutePath))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) SelectedBg else BgColor)
                            .combinedClickable(
                                onClick = {
                                    selected = node.file.absolutePath
                                    if (node.file.isDirectory) {
                                        expanded[node.file.absolutePath] =
                                            !(expanded[node.file.absolutePath] ?: false)
                                        refresh++
                                    } else if (isImage) {
                                        // Images must never open in the text editor — show preview instead.
                                        previewImagePath = node.file.absolutePath
                                    } else if (isArchive) {
                                        // .zip/.apk are binary containers — browse them like ZArchiver/MT
                                        // Manager instead of dumping raw bytes into the text editor.
                                        previewArchivePath = node.file.absolutePath
                                    } else if (isPdf) {
                                        // PDFs are binary too — render with the native PdfRenderer viewer.
                                        previewPdfPath = node.file.absolutePath
                                    } else if (isVideo) {
                                        previewVideoPath = node.file.absolutePath
                                    } else if (isAudio) {
                                        previewAudioPath = node.file.absolutePath
                                    } else if (isSqlite) {
                                        previewSqlitePath = node.file.absolutePath
                                    } else if (isDex) {
                                        previewDexPath = node.file.absolutePath
                                    } else if (isElf) {
                                        previewElfPath = node.file.absolutePath
                                    } else if (isApkAnalyzable(node.file.name)) {
                                        previewApkPath = node.file.absolutePath
                                    } else if (isHexBin) {
                                        // Compiled binaries/DBs/fonts (or anything the NUL-byte sniff
                                        // catches) — hex dump instead of corrupting/crashing the text editor.
                                        previewHexPath = node.file.absolutePath
                                    } else {
                                        onOpenFile(node.file.absolutePath)
                                    }
                                },
                                onLongClick = {
                                    if (isImage) {
                                        // Show image preview popup
                                        previewImagePath = node.file.absolutePath
                                    }
                                    contextFile = node.file
                                    showCtxMenu = true
                                }
                            )
                            .padding(
                                start = (8 + node.depth * 14).dp,
                                top = 5.dp, bottom = 5.dp, end = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.file.isDirectory) {
                            Icon(
                                if (node.isExpanded) Icons.Default.KeyboardArrowDown
                                else Icons.Default.ChevronRight,
                                null, tint = MutedColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.Folder, null,
                                tint = FolderColor, modifier = Modifier.size(16.dp))
                        } else {
                            if (isImage) {
                                // Show small thumbnail for image files
                                val thumbBitmap = remember(node.file.absolutePath) {
                                    loadImageBitmap(node.file.absolutePath)
                                }
                                if (thumbBitmap != null) {
                                    Image(
                                        bitmap = thumbBitmap,
                                        contentDescription = node.file.name,
                                        modifier = Modifier.size(20.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(2.dp))
                                } else {
                                    Spacer(Modifier.width(18.dp))
                                    Icon(fileIcon(node.file.name), null,
                                        tint = IconColor, modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Spacer(Modifier.width(18.dp))
                                Icon(fileIcon(node.file.name), null,
                                    tint = fileIconColor(node.file.name), modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            node.file.name,
                            fontSize = 13.sp, color = TextColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Git status badge
                        val gitChar = gitStatus[node.file.absolutePath]
                        if (gitChar != null) {
                            Text(
                                gitChar.toString(),
                                fontSize = 11.sp,
                                color = when (gitChar) {
                                    'M' -> Color(0xFFE2C08D)
                                    'A' -> Color(0xFF73C991)
                                    'U' -> Color(0xFF73C991)
                                    'D' -> Color(0xFFF48771)
                                    else -> MutedColor
                                },
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
            }

            // ── Outline view ──────────────────────────────────────────────
            if (showOutline && activeFilePath != null) {
                HorizontalDivider(color = DividerColor)
                Row(
                    Modifier.fillMaxWidth().height(24.dp)
                        .background(Color(0xFFF0F0F0))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = MutedColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("OUTLINE", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(activeFilePath!!.substringAfterLast("/"), fontSize = 10.sp, color = MutedColor, maxLines = 1)
                }
                val outlineItems = remember(activeFilePath) {
                    if (activeFilePath != null) {
                        try {
                            val content = File(activeFilePath).readText()
                            val items = mutableListOf<Triple<String, Int, String>>()
                            val classRegex = Regex("^(class|object|data class|enum class|sealed class|interface)\\s+(\\w+)")
                            val funRegex = Regex("^\\s*(fun|private fun|public fun|internal fun)\\s+(\\w+)")
                            val varRegex = Regex("^\\s*(val|var|private val|public val|private var)\\s+(\\w+)")
                            content.lines().forEachIndexed { idx, line ->
                                val cMatch = classRegex.find(line.trim())
                                if (cMatch != null) { items.add(Triple(cMatch.groupValues[2], idx + 1, "class")); return@forEachIndexed }
                                val fMatch = funRegex.find(line.trim())
                                if (fMatch != null) { items.add(Triple(fMatch.groupValues[2], idx + 1, "fun")); return@forEachIndexed }
                                val vMatch = varRegex.find(line.trim())
                                if (vMatch != null) { items.add(Triple(vMatch.groupValues[2], idx + 1, "var")) }
                            }
                            items
                        } catch (_: Exception) { emptyList() }
                    } else emptyList()
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(outlineItems) { (name, line, kind) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val fp = activeFilePath ?: return@clickable
                                    onOpenFile(fp)
                                    onOpenFileAtLine?.invoke(fp, line)
                                }
                                .padding(16.dp, 3.dp, 8.dp, 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (kind) {
                                    "class" -> Icons.Default.Code
                                    "fun" -> Icons.Default.Functions
                                    else -> Icons.Default.TextFields
                                },
                                null, tint = IconColor, modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(name, fontSize = 12.sp, color = TextColor, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Text(":" + line, fontSize = 10.sp, color = MutedColor)
                        }
                    }
                }
            }
        }
    }

    // ── Context menu (long press) ─────────────────────────────────────────
    // P17-A: version history autosave — snapshot recently modified files every 30s
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            withContext(Dispatchers.IO) {
                val wsPath = loadWorkspacePath(context, projectId) ?: return@withContext
                val projectDir = File(wsPath)
                val vhRoot = File(projectDir, ".versionhistory")
                val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
                projectDir.walkTopDown()
                    .filter { it.isFile && !it.path.contains(".versionhistory") && !it.path.contains(".ide-trash") && it.lastModified() > cutoff && it.length() < 1_048_576L }
                    .take(20)
                    .forEach { file ->
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(Date())
                        val vhDir = File(vhRoot, file.name)
                        vhDir.mkdirs()
                        file.copyTo(File(vhDir, "$stamp.bak"), overwrite = true)
                        vhDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { old -> old.delete() }
                    }
            }
        }
    }

    if (showCtxMenu && contextFile != null) {
        val f = contextFile!!
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showCtxMenu = false },
            title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val isImg = isImageFile(f.name)
                    val isArch = isArchiveFile(f.name)
                    val isPdf = isPdfFile(f.name)
                    val isVid = isVideoFile(f.name)
                    val isAud = isAudioFile(f.name)
                    val isDex    = isDexFile(f.name)
                    val isElf    = isElfFile(f.name)
                    val isHexBin = !isDex && !isElf && (isHexViewFile(f.name) || sniffLooksBinary(f.absolutePath))
                    val hasPreview = isImg || isArch || isPdf || isVid || isAud || isHexBin
                    val hasPaste = clipboardFile != null
                    buildList {
                        add("Open" to Icons.Default.OpenInNew)
                        if (hasPreview) add("Preview" to Icons.Default.Image)
                        add("Rename" to Icons.Default.Edit)
                        add("Copy" to Icons.Default.ContentCopy)
                        add("Cut" to Icons.Default.ContentCut)
                        if (hasPaste) add("Paste" to Icons.Default.ContentPaste)
                        add("Duplicate" to Icons.Default.FileCopy)
                        add("Delete" to Icons.Default.Delete)
                        add("Copy Path" to Icons.Default.ContentCopy)
                        add("Share" to Icons.Default.Share)
                        add("Open in Terminal" to Icons.Default.Computer)
                        add("New File Here" to Icons.Default.Add)
                        add("New Folder Here" to Icons.Default.CreateNewFolder)
                        add("Import Image(s) Here" to Icons.Default.AddPhotoAlternate)
                        add("Generate Image with AI Here" to Icons.Default.AutoAwesome)
                        if (!f.isDirectory) add("Local History" to Icons.Default.History)
                        add("Restore from Trash" to Icons.Default.RestoreFromTrash)
                        add("Compress" to Icons.Default.FolderZip)
                        add("Permissions" to Icons.Default.Lock)
                        if (!f.isDirectory) add("File Info" to Icons.Default.Info)
                        if (!f.isDirectory && (isHexBin || isArch)) add("Open as Strings" to Icons.Default.FormatListBulleted)
                        if (!f.isDirectory && isHexBin) add("Open as Binary Inspector" to Icons.Default.BugReport)
                        if (!f.isDirectory && isElf) add("Disassembly" to Icons.Default.Terminal)
                            if (!f.isDirectory) add("Entropy Heatmap" to Icons.Default.Thermostat)
                            if (!f.isDirectory && isNetworkCapture(f.name)) add("Network Viewer" to Icons.Default.Wifi)
                            if (!f.isDirectory && isAiModel(f.name)) add("AI Model Viewer" to Icons.Default.Psychology)
                            if (!f.isDirectory && isAndroidRuntimeFile(f.name)) add("Runtime Viewer" to Icons.Default.Android)
                        if (!f.isDirectory) add("Binary Diff" to Icons.Default.CompareArrows)
                        if (!f.isDirectory && isApkAnalyzable(f.name)) add("APK Analyzer" to Icons.Default.Android)
                        if (!f.isDirectory && (isDex || isSmaliSource(f.name))) add("Open as Smali" to Icons.Default.Code)
                    }.forEach { (label, icon) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showCtxMenu = false
                                    when (label) {
                                        "Open"   -> if (f.isDirectory) { expanded[f.absolutePath] = true; refresh++ }
                                                   else if (isArch) previewArchivePath = f.absolutePath
                                                   else if (isPdf) previewPdfPath = f.absolutePath
                                                   else if (isVid) previewVideoPath = f.absolutePath
                                                   else if (isAud) previewAudioPath = f.absolutePath
                                                   else if (isDex) previewDexPath = f.absolutePath
                                                   else if (isElf) previewElfPath = f.absolutePath
                                                   else if (isHexBin) previewHexPath = f.absolutePath
                                                   else onOpenFile(f.absolutePath)
                                        "Preview" -> when {
                                            isImg -> { previewImagePath = f.absolutePath; showCtxMenu = false }
                                            isArch -> { previewArchivePath = f.absolutePath; showCtxMenu = false }
                                            isPdf -> { previewPdfPath = f.absolutePath; showCtxMenu = false }
                                            isVid -> { previewVideoPath = f.absolutePath; showCtxMenu = false }
                                            isAud -> { previewAudioPath = f.absolutePath; showCtxMenu = false }
                                            isApkAnalyzable(f.name) -> { previewApkPath = f.absolutePath; showCtxMenu = false }
                                            isNetworkCapture(f.name) -> { previewNetworkPath = f.absolutePath; showCtxMenu = false }
                                            isAiModel(f.name) -> { previewAiModelPath = f.absolutePath; showCtxMenu = false }
                                            isAndroidRuntimeFile(f.name) -> { previewArtPath = f.absolutePath; showCtxMenu = false }
                                            isDex -> { previewDexPath = f.absolutePath; showCtxMenu = false }
                                            isElf -> { previewElfPath = f.absolutePath; showCtxMenu = false }
                                            isHexBin -> { previewHexPath = f.absolutePath; showCtxMenu = false }
                                        }
                                        "Binary Diff" -> { binaryDiffFileA = f; binaryDiffFileB = null; showCtxMenu = false }
                                        "Rename" -> { nameInput = f.name; showRename = true }
                                        "Copy"   -> { clipboardFile = f; clipboardCut = false }
                                        "Cut"    -> { clipboardFile = f; clipboardCut = true }
                                        "Paste"  -> {
                                            val src = clipboardFile
                                            val targetDir = if (f.isDirectory) f else f.parentFile
                                            if (src != null && targetDir != null) {
                                                val dest = File(targetDir, src.name)
                                                if (clipboardCut) src.renameTo(dest)
                                                else src.copyTo(dest, overwrite = false)
                                                clipboardFile = null
                                                refresh++
                                            }
                                        }
                                        "Duplicate" -> {
                                            val targetDir = f.parentFile
                                            if (targetDir != null) {
                                                val dest = if (f.isDirectory) File(targetDir, f.name + "_copy")
                                                            else File(targetDir, f.nameWithoutExtension + "_copy." + f.extension)
                                                if (f.isDirectory) f.copyRecursively(dest, overwrite = false)
                                                else f.copyTo(dest, overwrite = false)
                                                refresh++
                                            }
                                        }
                                        "Delete" -> showDelete = true
                                        "New File Here" -> {
                                            contextFile = if (f.isDirectory) f else f.parentFile
                                            nameInput = ""; showNewFile = true
                                        }
                                        "New Folder Here" -> {
                                            contextFile = if (f.isDirectory) f else f.parentFile
                                            nameInput = ""; showNewFolder = true
                                        }
                                        "Import Image(s) Here" -> {
                                            pendingImageTargetDir = if (f.isDirectory) f else f.parentFile
                                            imagePickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                        "Generate Image with AI Here" -> {
                                            pendingAiImageTargetDir = if (f.isDirectory) f else f.parentFile
                                            showAiImageGen = true
                                        }
                                        "Local History" -> {
                                            historyFile = f
                                            val projectDir = run {
                                                var p = f.parentFile
                                                while (p != null && !File(p, ".git").exists() && p.parentFile?.name != "projects") p = p.parentFile
                                                p ?: f.parentFile
                                            }
                                            if (projectDir != null) {
                                                val vhDir = File(projectDir, ".versionhistory/${f.name}")
                                                historySnapshots = if (vhDir.exists())
                                                    (vhDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList())
                                                else emptyList()
                                            }
                                            showHistoryDialog = true
                                        }
                                        "Restore from Trash" -> {
                                            val projectDir = run {
                                                var p = if (f.isDirectory) f else f.parentFile
                                                while (p != null && !File(p, ".ide-trash").exists() && p.parentFile?.name != "projects") p = p.parentFile
                                                p
                                            }
                                            if (projectDir != null) {
                                                trashEntries = WorkspaceManager.listTrash(projectDir)
                                            }
                                            showTrashDialog = true
                                        }
                                        "Copy Path" -> {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(
                                                android.content.ClipData.newPlainText("path", f.absolutePath))
                                        }
                                        "Share" -> {
                                            val shareIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                type = "*/*"
                                                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share " + f.name))
                                        }
                                        "Open in Terminal" -> onOpenInTerminal(if (f.isDirectory) f.absolutePath else f.parent ?: f.absolutePath)
                                        "Compress" -> { showCompressDialog = true }
                                        "Permissions" -> { showPermDialog = true }
                                        "File Info" -> { showFileInfoDialog = true }
                                        "Open as Strings" -> { previewStringsPath = f.absolutePath }
                                        "Open as Binary Inspector" -> { previewBinaryPath = f.absolutePath }
                                        "Disassembly" -> { previewDisasmPath = f.absolutePath }
                                        "APK Analyzer" -> { previewApkPath = f.absolutePath }
                                        "Open as Smali" -> { previewSmaliPath = f.absolutePath }
                "Entropy Heatmap" -> { previewEntropyPath = f.absolutePath }
                "Network Viewer" -> { previewNetworkPath = f.absolutePath }
                "AI Model Viewer" -> { previewAiModelPath = f.absolutePath }
                "Runtime Viewer"  -> { previewArtPath = f.absolutePath }
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = MutedColor, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCtxMenu = false }) { Text("Close") }
            },
        )
        }
    }

    // ── P17-B: Compress dialog ──────────────────────────────────────────────
    if (showCompressDialog && contextFile != null) {
        val f = contextFile!!
        val defaultName = f.name.trimEnd('/') + ".zip"
        var zipName by remember(f.absolutePath) { mutableStateOf(defaultName) }
        var compressing by remember { mutableStateOf(false) }
        var compressDone by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!compressing) { showCompressDialog = false; compressDone = false } },
            title = { Text("Compress", fontSize = 14.sp) },
            text = {
                Column {
                    Text("Output filename:", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = zipName,
                        onValueChange = { zipName = it },
                        singleLine = true,
                        enabled = !compressing && !compressDone,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (compressDone) {
                        Spacer(Modifier.height(8.dp))
                        Text("Done: ${f.parent}/$zipName", fontSize = 11.sp, color = Color(0xFF81C784))
                    }
                }
            },
            confirmButton = {
                if (!compressDone) {
                    TextButton(enabled = !compressing && zipName.isNotBlank(), onClick = {
                        val outFile = File(f.parentFile ?: f, zipName.let { if (it.endsWith(".zip")) it else "$it.zip" })
                        compressing = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                java.util.zip.ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                                    fun addEntry(file: File, name: String) {
                                        if (file.isDirectory) {
                                            file.listFiles()?.forEach { child ->
                                                addEntry(child, "$name/${child.name}")
                                            }
                                        } else {
                                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                                            file.inputStream().use { it.copyTo(zos) }
                                            zos.closeEntry()
                                        }
                                    }
                                    addEntry(f, f.name)
                                }
                                withContext(Dispatchers.Main) { compressDone = true; compressing = false; refresh++ }
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) { compressing = false }
                            }
                        }
                    }) { Text(if (compressing) "Compressing..." else "Compress") }
                } else {
                    TextButton(onClick = { showCompressDialog = false; compressDone = false }) { Text("Done") }
                }
            },
            dismissButton = {
                if (!compressDone) TextButton(onClick = { showCompressDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── P17-C: File permissions dialog ────────────────────────────────────────
    if (showPermDialog && contextFile != null) {
        val f = contextFile!!
        val perms = remember(f.absolutePath) {
            mutableStateOf(
                Triple(
                    f.canRead(),
                    f.canWrite(),
                    f.canExecute(),
                )
            )
        }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Permissions — ${f.name}", fontSize = 13.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Owner: ${if (perms.value.first) "r" else "-"}${if (perms.value.second) "w" else "-"}${if (perms.value.third) "x" else "-"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = perms.value.first, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Readable", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = perms.value.second, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Writable", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = perms.value.third,
                            onCheckedChange = { checked ->
                                scope.launch(Dispatchers.IO) {
                                    f.setExecutable(checked, false)
                                    val updated = Triple(f.canRead(), f.canWrite(), f.canExecute())
                                    withContext(Dispatchers.Main) { perms.value = updated }
                                }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Executable", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Note: Android does not allow changing read/write permissions on app-owned files from the UI.", fontSize = 10.sp, color = Color(0xFF9E9E9E))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPermDialog = false }) { Text("Close") } },
        )
    }

    // ── Archive/APK browser (tap on .zip/.apk/.jar/.aar) ──
    if (previewArchivePath != null) {
        ArchiveViewerDialog(
            archivePath = previewArchivePath!!,
            onDismiss = { previewArchivePath = null },
        )
    }

    // ── PDF viewer (tap on .pdf) — native PdfRenderer, one page at a time ──
    if (previewPdfPath != null) {
        PdfViewerDialog(
            pdfPath = previewPdfPath!!,
            onDismiss = { previewPdfPath = null },
        )
    }

    // ── Video player (tap on .mp4/.webm/.mov/etc) — native VideoView, no ExoPlayer dep ──
    if (previewVideoPath != null) {
        VideoPlayerDialog(
            videoPath = previewVideoPath!!,
            onDismiss = { previewVideoPath = null },
        )
    }

    // ── Audio player (tap on .mp3/.wav/etc) — MediaPlayer + Compose transport controls ──
    if (previewAudioPath != null) {
        AudioPlayerDialog(
            audioPath = previewAudioPath!!,
            onDismiss = { previewAudioPath = null },
        )
    }

    // ── Hex viewer — compiled binaries/DBs/fonts/.dex, or anything the NUL-byte sniff
    // catches that wasn't already routed to a dedicated viewer above ──
    if (previewHexPath != null) {
        HexViewerDialog(
            filePath = previewHexPath!!,
            onDismiss = { previewHexPath = null },
        )
    }

    // ── SQLite database viewer ──
    if (previewSqlitePath != null) {
        SqliteViewerDialog(
            file = java.io.File(previewSqlitePath!!),
            onDismiss = { previewSqlitePath = null },
        )
    }

    // ── Image preview popup (long-press on image file) ──
    if (previewImagePath != null) {
        val imgBitmap = remember(previewImagePath) { loadImageBitmap(previewImagePath!!) }
        key(orientation) {
        Dialog(
            onDismissRequest = { scope.launch { previewAlpha.animateTo(0f, tween(200)); previewImagePath = null } },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight(0.6f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .alpha(previewAlpha.value)
                    .clickable { scope.launch { previewAlpha.animateTo(0f, tween(200)); previewImagePath = null } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (imgBitmap != null) {
                        Image(
                            bitmap = imgBitmap,
                            contentDescription = "Image preview",
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .fillMaxHeight(0.85f),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Default.Image, null, tint = Color(0xFF888888),
                            modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        File(previewImagePath!!).name,
                        fontSize = 12.sp, color = Color(0xFFCCCCCC),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${File(previewImagePath!!).length() / 1024} KB",
                        fontSize = 10.sp, color = Color(0xFF888888),
                    )
                }
            }
        }
        }
    }

    // ── Generate Image with AI dialog ──────────────────────────────────────
    if (showAiImageGen && pendingAiImageTargetDir != null) {
        AiImageGenDialog(
            targetDir = pendingAiImageTargetDir!!,
            apiKey = tokenStore?.aiKey("GEMINI"),
            onDismiss = { showAiImageGen = false; pendingAiImageTargetDir = null },
            onSaved = { refresh++ },
        )
    }

    // ── DEX Viewer (Phase 21-X Step 1) ─────────────────────────────────────
    if (previewDexPath != null) {
        DexViewerDialog(
            file = java.io.File(previewDexPath!!),
            onDismiss = { previewDexPath = null },
        )
    }

    // ── ELF Viewer (Phase 21-X Step 2) ─────────────────────────────────────
    if (previewElfPath != null) {
        ElfViewerDialog(
            file = java.io.File(previewElfPath!!),
            onDismiss = { previewElfPath = null },
        )
    }
    // Phase 21-X: APK Analyzer
    if (previewApkPath != null) {
        ApkAnalyzerDialog(
            file = java.io.File(previewApkPath!!),
            onDismiss = { previewApkPath = null },
        )
    }
    // Phase 21-X: Smali Viewer
    if (previewSmaliPath != null) {
        SmaliViewerDialog(
            file = java.io.File(previewSmaliPath!!),
            onDismiss = { previewSmaliPath = null },
        )
    }
    // Phase 21-X: Disassembly Viewer
    if (previewDisasmPath != null) {
        DisassemblyViewerDialog(
            file = java.io.File(previewDisasmPath!!),
            onDismiss = { previewDisasmPath = null },
        )
    }
    // Phase 21-X Step 4: Entropy Heatmap
    if (previewEntropyPath != null) {
        EntropyHeatmapDialog(
            file = java.io.File(previewEntropyPath!!),
            onDismiss = { previewEntropyPath = null },
        )
    }
    // Phase 21-X Step 4: Network Viewer (PCAP / HAR)
    if (previewNetworkPath != null) {
        NetworkViewerDialog(
            file = java.io.File(previewNetworkPath!!),
            onDismiss = { previewNetworkPath = null },
        )
    }
    // Phase 21-X Step 4: AI Model Viewer (GGUF / Safetensors / ONNX)
    if (previewAiModelPath != null) {
        AiModelViewerDialog(
            file = java.io.File(previewAiModelPath!!),
            onDismiss = { previewAiModelPath = null },
        )
    }
    // Phase 21-X Step 9: Android Runtime Viewer (OAT / VDEX / APEX)
    if (previewArtPath != null) {
        AndroidRuntimeViewerDialog(
            file = java.io.File(previewArtPath!!),
            onDismiss = { previewArtPath = null },
        )
    }
    // Phase 21-X-10: Binary Diff Viewer
    if (binaryDiffFileA != null) {
        BinaryDiffViewerDialog(
            fileA = binaryDiffFileA!!,
            fileB = binaryDiffFileB,
            onDismiss = { binaryDiffFileA = null; binaryDiffFileB = null },
        )
    }

    // ── File Info dialog (Phase 21 Step 4) ──────────────────────────────────
    if (showFileInfoDialog && contextFile != null) {
        FileInfoDialog(
            file = contextFile!!,
            onDismiss = { showFileInfoDialog = false },
            onOpenAsText = { file -> onOpenFile(file.absolutePath); showFileInfoDialog = false },
            onOpenAsHex = { file -> previewHexPath = file.absolutePath; showFileInfoDialog = false },
            onOpenAsStrings = { file -> previewStringsPath = file.absolutePath; showFileInfoDialog = false },
            onOpenAsBinary = { file -> previewBinaryPath = file.absolutePath; showFileInfoDialog = false },
        )
    }

    // ── Strings viewer (Phase 21 Step 4) ─────────────────────────────────
    if (previewStringsPath != null) {
        StringsViewerDialog(
            file = java.io.File(previewStringsPath!!),
            onDismiss = { previewStringsPath = null },
        )
    }

    // ── Binary Inspector (Phase 21 Step 4) ───────────────────────────────
    if (previewBinaryPath != null) {
        BinaryInspectorDialog(
            file = java.io.File(previewBinaryPath!!),
            onDismiss = { previewBinaryPath = null },
        )
    }

    // ── New File dialog ───────────────────────────────────────────────────
    if (showNewFile) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("New File") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("File name (e.g. main.py)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        val targetDir = contextFile?.let {
                            if (it.isDirectory) it else it.parentFile
                        } ?: workspaceRoot
                        if (targetDir != null) {
                            val newFile = java.io.File(targetDir, nameInput)
                            try {
                                newFile.createNewFile()
                                refresh++
                                onOpenFile(newFile.absolutePath)
                            } catch (_: Exception) {}
                        }
                        showNewFile = false
                        nameInput = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFile = false }) { Text("Cancel") }
            },
        )
        }
    }

    // ── New Folder dialog ─────────────────────────────────────────────────
    if (showNewFolder) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("Folder name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        val dir = contextFile?.let {
                            if (it.isDirectory) it else it.parentFile
                        } ?: workspaceRoot ?: return@Button
                        File(dir, nameInput).mkdirs()
                        refresh++
                    }
                    showNewFolder = false; nameInput = ""
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            },
        )
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    if (showRename && contextFile != null) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it },
                    label = { Text("New name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (nameInput.isNotBlank()) {
                        val oldPath = contextFile!!.absolutePath
                        val newFile = File(contextFile!!.parent, nameInput)
                        if (contextFile!!.renameTo(newFile)) {
                            onFileRenamed?.invoke(oldPath, newFile.absolutePath)
                        }
                        refresh++
                    }
                    showRename = false; nameInput = ""
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
        }
    }

    // ── Delete confirmation ───────────────────────────────────────────────
    // P17-A: Local version history dialog
    if (showHistoryDialog && historyFile != null) {
        val hFile = historyFile!!
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Local History — ${hFile.name}", fontSize = 13.sp) },
            text = {
                if (historySnapshots.isEmpty()) {
                    Text("No snapshots yet. Snapshots are saved every 30 seconds for recently modified files.", fontSize = 12.sp, color = MutedColor)
                } else {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        historySnapshots.forEach { snap ->
                            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(Date(snap.lastModified()))
                            val sizeKb = snap.length() / 1024
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ts, fontSize = 12.sp, color = TextColor)
                                    Text("${sizeKb}KB", fontSize = 10.sp, color = MutedColor)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { snap.copyTo(hFile, overwrite = true) }
                                        showHistoryDialog = false
                                        refresh++
                                    }
                                }) { Text("Restore", fontSize = 11.sp, color = IconColor) }
                            }
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Close") } }
        )
    }

    // P17-D: Trash browser dialog
    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text("Restore from Trash", fontSize = 13.sp) },
            text = {
                if (trashEntries.isEmpty()) {
                    Text("Trash is empty.", fontSize = 12.sp, color = MutedColor)
                } else {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        trashEntries.forEach { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.originalPath.substringAfterLast("/"), fontSize = 12.sp, color = TextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(Date(entry.deletedAtMs)),
                                        fontSize = 10.sp, color = MutedColor
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        findTrashProjectDir(contextFile)?.let { pd ->
                                            withContext(Dispatchers.IO) { WorkspaceManager.restoreFromTrash(pd, entry) }
                                            trashEntries = WorkspaceManager.listTrash(pd)
                                            refresh++
                                        }
                                    }
                                }) { Text("Restore", fontSize = 11.sp, color = IconColor) }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = {
                                    scope.launch {
                                        findTrashProjectDir(contextFile)?.let { pd ->
                                            withContext(Dispatchers.IO) { WorkspaceManager.purgeTrashEntry(pd, entry) }
                                            trashEntries = WorkspaceManager.listTrash(pd)
                                        }
                                    }
                                }) { Text("Delete", fontSize = 11.sp, color = Color(0xFFCC0000)) }
                            }
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTrashDialog = false }) { Text("Close") } }
        )
    }

    if (showDelete && contextFile != null) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${contextFile!!.name}?") },
            text  = { Text("File will be moved to .ide-trash/ and can be restored.") },
            confirmButton = {
                Button(
                    onClick = {
                        // P7-4 Trash: move to .ide-trash/ instead of permanent delete
                        val proj = contextFile!!.let { f ->
                            // Walk up to find the project root (contains .ide-trash or is filesDir/projects/*)
                            var p = f.parentFile
                            while (p != null && !File(p, ".ide-trash").exists() && p.parentFile?.name != "projects") {
                                p = p.parentFile
                            }
                            p ?: f.parentFile!!
                        }
                        WorkspaceManager.moveToTrash(proj, contextFile!!)
                        refresh++
                        showDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
        }
    }
}

private fun String.matchesSimpleGlob(pattern: String): Boolean {
    val regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
    return try { Regex(regex, RegexOption.IGNORE_CASE).matches(this) } catch (_: Exception) { this.contains(pattern, ignoreCase = true) }
}

fun fileIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Kotlin
        "kt", "kts" -> Icons.Default.Code
        // Java
        "java" -> Icons.Default.Coffee
        // Python
        "py", "pyw", "pyi" -> Icons.Default.Code
        // JavaScript / TypeScript
        "js", "mjs", "cjs" -> Icons.Default.Javascript
        "ts", "tsx", "jsx" -> Icons.Default.Javascript
        // Web
        "html", "htm" -> Icons.Default.Html
        "css", "scss", "sass", "less" -> Icons.Default.Css
        "xml", "svg" -> Icons.Default.Code
        // Data / Config
        "json" -> Icons.Default.DataObject
        "yaml", "yml", "toml", "ini", "cfg", "conf" -> Icons.Default.Settings
        "env" -> Icons.Default.Settings
        // Build
        "gradle", "gradlew" -> Icons.Default.Build
        // Markdown / Text
        "md", "markdown" -> Icons.Default.Article
        "txt", "log" -> Icons.Default.Article
        // Images
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico" -> Icons.Default.Image
        // Archives
        "zip", "apk", "jar", "aar", "tar", "gz", "7z", "rar" -> Icons.Default.FolderZip
        // Shell / Scripts
        "sh", "bash", "zsh", "fish" -> Icons.Default.Terminal
        // Binaries
        "dex", "so", "o", "a" -> Icons.Default.Memory
        "db", "sqlite", "sqlite3" -> Icons.Default.Storage
        // Documents
        "pdf" -> Icons.Default.PictureAsPdf
        // C/C++
        "c", "cpp", "cc", "cxx", "h", "hpp" -> Icons.Default.Code
        // Rust
        "rs" -> Icons.Default.Code
        // Go
        "go" -> Icons.Default.Code
        // Swift
        "swift" -> Icons.Default.Code
        // Dart
        "dart" -> Icons.Default.Code
        // Ruby
        "rb" -> Icons.Default.Code
        // PHP
        "php" -> Icons.Default.Code
        // Lua
        "lua" -> Icons.Default.Code
        // Git
        "gitignore", "gitattributes", "gitmodules" -> Icons.Default.AccountTree
        // Default
        else -> Icons.Default.InsertDriveFile
    }
}

/** P24: File type color — returns a tint color based on file extension for visual differentiation. */
fun fileIconColor(name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Kotlin — purple
        "kt", "kts" -> Color(0xFF7F52FF)
        // Java — orange/red
        "java" -> Color(0xFFE76F00)
        // Python — blue/yellow
        "py", "pyw", "pyi" -> Color(0xFF4584B6)
        // JavaScript — amber
        "js", "mjs", "cjs" -> Color(0xFFF0DB4F)
        // TypeScript — blue
        "ts", "tsx", "jsx" -> Color(0xFF3178C6)
        // Web — orange/red
        "html", "htm" -> Color(0xFFE44D26)
        "css", "scss", "sass", "less" -> Color(0xFF264DE4)
        "xml" -> Color(0xFF0066CC)
        // Data — amber
        "json" -> Color(0xFFCCA700)
        "yaml", "yml", "toml" -> Color(0xFFCB171E)
        // Build — green
        "gradle" -> Color(0xFF02303A)
        // Markdown — blue
        "md", "markdown" -> Color(0xFF4A90D9)
        // Images — pink
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico" -> Color(0xFFD63384)
        // Archives — brown
        "zip", "apk", "jar", "aar", "tar", "gz" -> Color(0xFF8B5E3C)
        // Shell — green
        "sh", "bash", "zsh" -> Color(0xFF4EAA25)
        // Binaries — dark gray
        "dex", "so", "o", "a" -> Color(0xFF555555)
        "db", "sqlite", "sqlite3" -> Color(0xFF00897B)
        // C/C++ — blue
        "c", "cpp", "cc", "cxx", "h", "hpp" -> Color(0xFF659AD2)
        // Rust — orange
        "rs" -> Color(0xFFDEA584)
        // Go — cyan
        "go" -> Color(0xFF00ADD8)
        // Swift — orange
        "swift" -> Color(0xFFFF6B35)
        // Dart — blue
        "dart" -> Color(0xFF0175C2)
        // Default
        else -> IconColor
    }
}

// ── Stub panels ──────────────────────────────────────────────────────────────
private data class SearchResult(val file: String, val lineNum: Int, val lineText: String, val matchRange: IntRange)

@Composable fun SearchPanel(projectId: String, onOpenFileAtLine: ((String, Int) -> Unit)? = null) {
    var searchQuery  by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var useRegex      by remember { mutableStateOf(false) }
    var matchWholeWord by remember { mutableStateOf(false) }
    var results       by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching     by remember { mutableStateOf(false) }
    var expandedFiles by remember { mutableStateOf(setOf<String>()) }
    var includePattern by remember { mutableStateOf("") }
    var excludePattern by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var totalReplaced by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun performSearch(query: String) {
        if (query.isBlank()) { results = emptyList(); return }
        searching = true
        scope.launch {
            val wsPath = loadWorkspacePath(context, projectId)
            val wsRoot = wsPath?.let { File(it) }
            val allResults = mutableListOf<SearchResult>()
            if (wsRoot != null && wsRoot.exists()) {
                val extensions = setOf("kt", "java", "xml", "gradle", "kts", "py", "js", "ts", "json", "md", "txt", "yml", "yaml", "sh", "html", "css")
                val maxFiles = 500
                var filesScanned = 0
                fun walk(dir: File) {
                    if (filesScanned >= maxFiles) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (filesScanned >= maxFiles) break
                        if (f.isDirectory) {
                            if (!f.name.startsWith(".") && f.name != "build" && f.name != "node_modules") {
                                walk(f)
                            }
                        } else if ((f.extension.lowercase() in extensions || f.extension.isEmpty()) &&
                                   (includePattern.isBlank() || f.name.matchesSimpleGlob(includePattern)) &&
                                   (excludePattern.isBlank() || !f.name.matchesSimpleGlob(excludePattern))) {
                            filesScanned++
                            try {
                                f.useLines { lines ->
                                    lines.forEachIndexed { idx, line ->
                                        val matched = if (useRegex) {
                                            try {
                                                val regex = if (caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
                                                regex.containsMatchIn(line)
                                            } catch (_: Exception) { false }
                                        } else if (matchWholeWord) {
                                            val regex = if (caseSensitive) Regex("\\b${Regex.escape(query)}\\b") else Regex("\\b${Regex.escape(query)}\\b", RegexOption.IGNORE_CASE)
                                            regex.containsMatchIn(line)
                                        } else if (caseSensitive) {
                                            line.contains(query)
                                        } else {
                                            line.contains(query, ignoreCase = true)
                                        }
                                        if (matched) {
                                            val matchStart = if (caseSensitive) line.indexOf(query) else line.indexOf(query, ignoreCase = true)
                                            if (matchStart >= 0) {
                                                allResults.add(SearchResult(f.absolutePath, idx + 1, line.trim(), matchStart..(matchStart + query.length - 1)))
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                walk(wsRoot)
            }
            results = allResults
            searching = false
        }
    }

    val grouped = results.groupBy { it.file }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // Search input
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Icon(Icons.Default.Close, "Clear", tint = MutedColor,
                        modifier = Modifier.size(16.dp).clickable { searchQuery = ""; results = emptyList() })
                }
            },
        )
        // Replace input
        OutlinedTextField(
            value = replaceQuery, onValueChange = { replaceQuery = it },
            label = { Text("Replace") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        // Toggle buttons — case sensitive, whole word, regex
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val toggles = listOf(
                "Aa" to caseSensitive,
                "\b" to matchWholeWord,
                ".*" to useRegex,
            )
            toggles.forEachIndexed { idx, (label, active) ->
                Box(
                    Modifier
                        .background(if (active) IconColor else Color.Transparent, RoundedCornerShape(3.dp))
                        .border(1.dp, if (active) IconColor else DividerColor, RoundedCornerShape(3.dp))
                        .clickable {
                            when (idx) {
                                0 -> caseSensitive = !caseSensitive
                                1 -> matchWholeWord = !matchWholeWord
                                2 -> useRegex = !useRegex
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (active) Color.White else MutedColor)
                }
            }
            Spacer(Modifier.fillMaxWidth())
            // Filter toggle (show/hide include/exclude)
            Icon(Icons.Default.FilterList, null, tint = if (showFilters) IconColor else MutedColor,
                modifier = Modifier.size(16.dp).clickable { showFilters = !showFilters })
            Spacer(Modifier.width(8.dp))
            // Search button
            if (searching) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = IconColor)
            } else {
                Text("Search", fontSize = 11.sp, color = IconColor, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { performSearch(searchQuery) }.padding(4.dp))
            }
            // Replace All button
            if (replaceQuery.isNotEmpty() && results.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text("Replace All", fontSize = 11.sp, color = Color(0xFFE53935), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        scope.launch {
                            val wsPath = loadWorkspacePath(context, projectId)
                            val wsRoot = wsPath?.let { File(it) }
                            if (wsRoot != null) {
                                var count = 0
                                results.groupBy { it.file }.forEach { (filePath, fileResults) ->
                                    try {
                                        val f = File(filePath)
                                        val content = f.readText()
                                        val newContent = if (useRegex) {
                                            try {
                                                val regex = if (caseSensitive) Regex(searchQuery) else Regex(searchQuery, RegexOption.IGNORE_CASE)
                                                regex.replace(content, replaceQuery).also { count += fileResults.size }
                                            } catch (_: Exception) { content }
                                        } else if (matchWholeWord) {
                                            val regex = if (caseSensitive) Regex("\\b" + Regex.escape(searchQuery) + "\\b")
                                                       else Regex("\\b" + Regex.escape(searchQuery) + "\\b", RegexOption.IGNORE_CASE)
                                            regex.replace(content, replaceQuery).also { count += fileResults.size }
                                        } else {
                                            content.replace(searchQuery, replaceQuery, !caseSensitive).also { count += fileResults.size }
                                        }
                                        f.writeText(newContent)
                                    } catch (_: Exception) {}
                                }
                                totalReplaced = count
                                performSearch(searchQuery)
                            }
                        }
                    }.padding(4.dp))
            }
        }

        // Include/Exclude filters
        if (showFilters) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = includePattern, onValueChange = { includePattern = it },
                    label = { Text("include", fontSize = 10.sp) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextColor),
                )
                OutlinedTextField(
                    value = excludePattern, onValueChange = { excludePattern = it },
                    label = { Text("exclude", fontSize = 10.sp) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextColor),
                )
            }
        }

        // Replace result indicator
        if (totalReplaced > 0) {
            Text("Replaced " + totalReplaced + " occurrences", fontSize = 11.sp, color = Color(0xFF73C991),
                modifier = Modifier.padding(top = 4.dp))
        }

        // Results count
        if (results.isNotEmpty()) {
            Text("${results.size} results in ${grouped.size} files",
                fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(top = 8.dp))
        }

        // Results list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
        ) {
            if (results.isEmpty() && !searching && searchQuery.isNotEmpty()) {
                item {
                    Text("No results found", fontSize = 13.sp, color = MutedColor,
                        modifier = Modifier.padding(top = 16.dp))
                }
            }
            grouped.forEach { (filePath, fileResults) ->
                val isExpanded = filePath in expandedFiles
                item(key = "header_$filePath") {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            expandedFiles = if (isExpanded) expandedFiles - filePath else expandedFiles + filePath
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            null, tint = MutedColor, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(fileIcon(filePath.substringAfterLast("/")), null, tint = fileIconColor(filePath.substringAfterLast("/")), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            filePath.substringAfterLast("/"),
                            fontSize = 12.sp, color = TextColor, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("${fileResults.size}", fontSize = 10.sp, color = MutedColor,
                            modifier = Modifier.background(DividerColor, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                if (isExpanded) {
                    items(fileResults, key = { r: SearchResult -> r.file + "_" + r.lineNum }) { result ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onOpenFileAtLine?.invoke(result.file, result.lineNum) }
                                .padding(start = 36.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            Text(result.lineNum.toString() + ": ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(result.lineText, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun GitSidePanel(projectId: String) { SourceControlPane(projectId) }

@Composable fun RunDebugPanel(onMoreMenu: () -> Unit) {
    // P23-2: Wired to UniversalDebugManager — real debug backend
    val udm = UniversalDebugManager
    var selectedConfig by remember { mutableStateOf("Kotlin Application") }
    var showConfigMenu by remember { mutableStateOf(false) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var sessionState by remember { mutableStateOf<DebugState>(DebugState.IDLE) }
    var variables by remember { mutableStateOf<List<DebugVariable>>(emptyList()) }
    var callStack by remember { mutableStateOf<List<DebugStackFrame>>(emptyList()) }
    var watchExprs by remember { mutableStateOf<List<DebugWatch>>(emptyList()) }
    var allBreakpoints by remember { mutableStateOf(udm.getAllBreakpoints()) }
    var showVariables by remember { mutableStateOf(true) }
    var showWatch by remember { mutableStateOf(false) }
    var showCallStack by remember { mutableStateOf(true) }
    var showBreakpoints by remember { mutableStateOf(true) }
    var watchInput by remember { mutableStateOf("") }
    var watchIdCounter by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        udm.onBreakpointsChanged = { allBreakpoints = udm.getAllBreakpoints() }
        udm.onSessionStateChanged = { session ->
            sessionState = session.state
            if (session.state == DebugState.STOPPED || session.state == DebugState.ERROR) {
                activeSessionId = null
                variables = emptyList()
                callStack = emptyList()
            }
        }
        udm.onPaused = { stack, vars ->
            callStack = stack
            variables = vars
        }
    }

    val isRunning = activeSessionId != null && sessionState != DebugState.STOPPED && sessionState != DebugState.ERROR

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RUN AND DEBUG", fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.fillMaxWidth())
            Icon(Icons.Default.MoreVert, "More", tint = MutedColor,
                modifier = Modifier.size(18.dp).clickable { onMoreMenu() })
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(onClick = { showConfigMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedConfig, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showConfigMenu, onDismissRequest = { showConfigMenu = false }) {
                    val configs = listOf("Kotlin Application", "Android App (Debug)", "Android App (Release)", "Gradle Build", "JUnit Tests", "Terminal Script")
                    configs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text(config, fontSize = 12.sp) },
                            onClick = { selectedConfig = config; showConfigMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!isRunning) {
                FilledIconButton(
                    onClick = {
                        activeSessionId = "manual"
                        sessionState = DebugState.RUNNING
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, "Run", tint = Color.White)
                }
            } else {
                FilledIconButton(
                    onClick = { activeSessionId?.let { udm.stepOver(it) } },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF007ACC)),
                ) {
                    Icon(Icons.Default.SkipNext, "Step Over", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = {
                        activeSessionId?.let { udm.stopSession(it) }
                        activeSessionId = null
                        sessionState = DebugState.IDLE
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935)),
                ) {
                    Icon(Icons.Default.Stop, "Stop", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(4.dp))

        if (isRunning) {
            val stateText = when (sessionState) {
                DebugState.RUNNING -> "Running"
                DebugState.PAUSED -> "Paused"
                DebugState.STARTING -> "Starting..."
                else -> "Unknown"
            }
            val stateColor = if (sessionState == DebugState.PAUSED) Color(0xFFCE9178) else Color(0xFF4EC9B0)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(stateColor, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(stateText, fontSize = 10.sp, color = stateColor, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(2.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { SectionHeader("VARIABLES", showVariables) { showVariables = !showVariables } }
            if (showVariables) {
                if (isRunning && variables.isNotEmpty()) {
                    items(variables) { v ->
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)) {
                            if (v.expandable) {
                                Icon(Icons.Default.KeyboardArrowRight, null, tint = MutedColor, modifier = Modifier.size(12.dp))
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(v.name, fontSize = 11.sp, color = IconColor, fontFamily = FontFamily.Monospace)
                            Text(": ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(v.type, fontSize = 11.sp, color = Color(0xFF569CD6), fontFamily = FontFamily.Monospace)
                            Text(" = ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(v.value, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    item { Text(if (!isRunning) "Not started" else "No variables", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                }
            }

            item { SectionHeader("WATCH", showWatch) { showWatch = !showWatch } }
            if (showWatch) {
                if (watchExprs.isNotEmpty()) {
                    items(watchExprs) { w ->
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Close, "Remove", tint = MutedColor, modifier = Modifier.size(12.dp).clickable {
                                watchExprs = watchExprs.filter { it.id != w.id }
                            })
                            Spacer(Modifier.width(4.dp))
                            Text(w.expression, fontSize = 11.sp, color = IconColor, fontFamily = FontFamily.Monospace)
                            Text(" = ", fontSize = 11.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                            Text(w.value, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    Row(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = watchInput,
                            onValueChange = { watchInput = it },
                            placeholder = { Text("Add expression...", fontSize = 11.sp, color = MutedColor) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextColor),
                        )
                        Icon(Icons.Default.Add, "Add", tint = IconColor, modifier = Modifier.size(14.dp).clickable {
                            if (watchInput.isNotBlank()) {
                                val value = activeSessionId?.let { udm.evaluateExpression(it, watchInput) } ?: "---"
                                watchExprs = watchExprs + DebugWatch(watchIdCounter++, watchInput.trim(), value)
                                watchInput = ""
                            }
                        })
                    }
                }
            }

            item { SectionHeader("CALL STACK", showCallStack) { showCallStack = !showCallStack } }
            if (showCallStack) {
                if (isRunning && callStack.isNotEmpty()) {
                    items(callStack) { frame ->
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)) {
                            Icon(Icons.Default.Code, null, tint = IconColor, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(frame.function, fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("  " + frame.file.substringAfterLast("/") + ":" + (frame.line + 1), fontSize = 10.sp, color = MutedColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    item { Text("Not paused", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                }
            }

            item { SectionHeader("BREAKPOINTS (" + allBreakpoints.size + ")", showBreakpoints) { showBreakpoints = !showBreakpoints } }
            if (showBreakpoints) {
                if (allBreakpoints.isEmpty()) {
                    item { Text("No breakpoints set", fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) }
                } else {
                    items(allBreakpoints) { bp ->
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RadioButtonChecked, "Breakpoint",
                                tint = if (bp.enabled) Color(0xFFE53935) else MutedColor,
                                modifier = Modifier.size(12.dp).clickable {
                                    udm.setBreakpointEnabled(bp.filePath, bp.line, !bp.enabled)
                                })
                            Spacer(Modifier.width(4.dp))
                            Text(bp.filePath.substringAfterLast("/") + ":" + (bp.line + 1), fontSize = 11.sp, color = TextColor, fontFamily = FontFamily.Monospace)
                            if (bp.condition != null) {
                                Text("  [" + bp.condition + "]", fontSize = 10.sp, color = Color(0xFFCE9178), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            if (bp.logMessage != null) {
                                Text("  log: " + bp.logMessage, fontSize = 10.sp, color = Color(0xFF4EC9B0), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.Close, "Remove", tint = MutedColor, modifier = Modifier.size(12.dp).clickable {
                                udm.removeBreakpoint(bp.filePath, bp.line)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            null, tint = MutedColor, modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 11.sp, color = MutedColor, fontWeight = FontWeight.Bold)
    }
}


private fun findTrashProjectDir(contextFile: java.io.File?): java.io.File? {
    var p = contextFile?.parentFile
    while (p != null) {
        if (java.io.File(p, ".ide-trash").exists()) return p
        if (p.parentFile?.name == "projects") return p
        p = p.parentFile
    }
    return null
}

