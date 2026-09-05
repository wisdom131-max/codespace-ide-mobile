package com.codespace.ide.ui.panes

import com.codespace.ide.domain.Language
import com.codespace.ide.util.WorkspaceManager
import com.codespace.ide.util.WorkspaceRootsStore
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
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.data.NotificationStore
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
import androidx.compose.material.icons.automirrored.filled.*

private val BgColor      = Color(0xFF1E1E1E)
private val SelectedBg   = Color(0xFF37373D)
private val TextColor    = Color(0xFFD4D4D4)
private val MutedColor   = Color(0xFF858585)
private val DividerColor = Color(0xFF2D2D30)
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

// BUG-FIX (Test 49): Find in Files query used to live only in Compose `remember` state,
// which is discarded the moment the Search panel is closed (its `when` branch unmounts
// it). Reopening always started blank. Persist the last query per project so it's
// pre-filled again — same pattern as the workspace-path memory above.
private const val PREFS_SEARCH_HISTORY = "codespace_search_history"
private const val KEY_LAST_SEARCH = "last_search_query"

private fun saveLastSearchQuery(context: Context, projectId: String, query: String) {
    context.getSharedPreferences(PREFS_SEARCH_HISTORY, Context.MODE_PRIVATE)
        .edit().putString("${KEY_LAST_SEARCH}_$projectId", query).apply()
}

private fun loadLastSearchQuery(context: Context, projectId: String): String =
    context.getSharedPreferences(PREFS_SEARCH_HISTORY, Context.MODE_PRIVATE)
        .getString("${KEY_LAST_SEARCH}_$projectId", "") ?: ""

// ── Multi-root workspace support ──
private fun saveWorkspaceRoots(context: Context, projectId: String, roots: List<String>) {
    WorkspaceRootsStore.saveRoots(context, projectId, roots)
}

private fun loadWorkspaceRoots(context: Context, projectId: String): List<String> =
    WorkspaceRootsStore.loadRoots(context, projectId)

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
    /** MULTI-ROOT (Part B): fired when a NEW workspace root is added (folder picker,
     * device-folders quick picks). Shell uses it to notify running LSP servers via
     * workspace/didChangeWorkspaceFolders "added" (deduped against roots already
     * sent at initialize). */
    onWorkspaceRootAdded: ((String) -> Unit)? = null,
    /** MULTI-ROOT (Part B): fired when a workspace root is removed via the root
     * switcher's close icon. Shell closes open tabs from that root and notifies LSP
     * servers via "removed" AFTER EditorPane has sent per-file didClose. */
    onWorkspaceRootRemoved: ((String) -> Unit)? = null,
    openTabs: List<String> = emptyList(),
    activeFilePath: String? = null,
    onCloseTab: ((String) -> Unit)? = null,
    // fix/feature 2026-07-08: "Generate Image with AI Here" needs the user's Gemini key.
    tokenStore: com.codespace.ide.data.SecureTokenStore? = null,
    /** Initial workspace path from the project's pathOrUrl. Used when no saved workspace path exists. */
    initialWorkspacePath: String? = null,
    /** Breadcrumb navigation: when set, auto-expand and scroll to this directory path. */
    navigateToDir: String? = null,
    /** Reveal a file in the tree: expands all parent dirs and scrolls to it. Used when a tab is closed so the file remains findable. */
    revealFilePath: String? = null,
    /** Counter that increments each time a file is revealed, ensuring the LaunchedEffect re-fires even for the same file path. */
    revealFileTrigger: Int = 0,
    /** Notification callback for file operations (errors, success). */
    onShowNotification: ((String, String) -> Unit)? = null,
    /** [REPO-OPEN] Part 2 item 4: bumped by the shell when a repo cloned from
     *  the Source Control pane was appended to the persisted workspace roots
     *  from outside this composable — triggers a reload of the saved roots so
     *  the new repo appears in the tree without a manual refresh. */
    rootsRefreshKey: Int = 0,
    /** External trigger: when set to a non-null value, opens the New File dialog. */
    triggerNewFile: Any? = null,
    /** External trigger: when set to a non-null value, opens the New Folder dialog. */
    triggerNewFolder: Any? = null,
    /** Theme-aware panel background for section headers (from ideColors). */
    panelBg: Color = Color(0xFF252526),
    /** External refresh trigger: when this changes, the explorer re-scans the file tree.
     *  Used by terminal to notify explorer of file system changes (e.g. after `echo > file.txt`). */
    externalRefreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    // Rotation fix (#8): Compose Dialog/AlertDialog windows don't resize when the
    // Activity itself doesn't recreate on rotation (configChanges="orientation|screenSize"
    // in the manifest). Keying on orientation forces a full subtree rebuild so Android
    // creates a fresh, correctly-sized window (and fresh scroll state) every rotation.
    val orientation = LocalConfiguration.current.orientation

    var workspacePath by remember(projectId) {
        mutableStateOf(loadWorkspacePath(context, projectId) ?: initialWorkspacePath)
    }
    val workspaceRoot = remember(workspacePath) {
        workspacePath?.let { File(it) }
    }

    // ── Multi-root workspace ──
    var workspaceRoots by remember {
        mutableStateOf(loadWorkspaceRoots(context, projectId))
    }
    // [REPO-OPEN] Part 2 item 4: external append (GitHub clone from SCM pane).
    LaunchedEffect(rootsRefreshKey) {
        if (rootsRefreshKey > 0) {
            val reloaded = loadWorkspaceRoots(context, projectId)
            if (reloaded != workspaceRoots) workspaceRoots = reloaded
        }
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
    // Phase 21-X Step 10 — Binary Diff Viewer
    var diffFileA             by remember { mutableStateOf<java.io.File?>(null) }
    var diffFileB             by remember { mutableStateOf<java.io.File?>(null) }
    var showDiffViewer        by remember { mutableStateOf(false) }
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
        val localWorkspaceRoot = workspacePath?.let { java.io.File(it) } ?: return@LaunchedEffect
        var idx = 0
        fun walk(f: java.io.File, depth: Int) {
            idx++
            if (f.absolutePath == targetPath) return
            if (expanded[f.absolutePath] == true && f.isDirectory) {
                f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { child -> walk(child, depth + 1) }
            }
        }
        localWorkspaceRoot.listFiles()
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
    var trashProjectDir    by remember { mutableStateOf<File?>(null) }
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
    // External refresh (e.g. from terminal file system changes)
    LaunchedEffect(externalRefreshTrigger) {
        if (externalRefreshTrigger > 0) refresh++
    }
    // Test 78 fix: When a file tab is closed, reveal the file in the workspace tree
    // by expanding all ancestor directories and scrolling to it. This ensures the
    // file is findable in the Explorer after its tab is removed from OPEN EDITORS.
    LaunchedEffect(revealFileTrigger) {
        val targetPath = revealFilePath ?: return@LaunchedEffect
        val targetFile = java.io.File(targetPath)
        if (!targetFile.exists()) return@LaunchedEffect
        // Expand all ancestor directories so the file becomes visible in the tree
        var dir = targetFile.parentFile
        while (dir != null) {
            expanded[dir.absolutePath] = true
            dir = dir.parentFile
        }
        refresh++
        // Wait for recomposition, then scroll to the file's position in the tree.
        // Use a retry loop: on slow devices the first recomposition may not be ready yet.
        val localWorkspaceRoot = workspacePath?.let { java.io.File(it) } ?: return@LaunchedEffect
        var found = false
        var foundIdx = 0
        repeat(3) { attempt ->
            if (found) return@repeat
            kotlinx.coroutines.delay(if (attempt == 0) 150L else 100L)
            var idx = 0
            fun walk(f: java.io.File, depth: Int) {
                if (found) return
                idx++
                if (f.absolutePath == targetPath) { found = true; foundIdx = idx; return }
                if (expanded[f.absolutePath] == true && f.isDirectory) {
                    f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?.forEach { child -> if (!found) walk(child, depth + 1) }
                }
            }
            localWorkspaceRoot.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { if (!found) walk(it, 0) }
        }
        if (found) {
            treeListState.animateScrollToItem((foundIdx - 1).coerceAtLeast(0))
            // Highlight the file so the user can spot it
            selected = targetPath
        }
    }
    var filterQuery   by remember { mutableStateOf("") }
    var sortByType    by remember { mutableStateOf(false) }
    var sortMode      by remember { mutableStateOf(0) } // 0=Name, 1=Date, 2=Size, 3=Type
    var multiSelectMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var showOutline   by remember { mutableStateOf(false) }
    var showTimeline  by remember { mutableStateOf(false) }
    // P42: Explorer restructure — independent collapsible sections (VS Code style)
    var openEditorsExpanded by remember { mutableStateOf(true) }
    var workspaceExpanded   by remember { mutableStateOf(true) }
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
    // External trigger: open New File dialog (from 3-dot overflow menu / command palette)
    LaunchedEffect(triggerNewFile) {
        if (triggerNewFile is Int && triggerNewFile > 0) {
            contextFile = workspaceRoot
            nameInput = ""
            showNewFile = true
        }
    }
    // External trigger: open New Folder dialog (from 3-dot overflow menu / command palette)
    LaunchedEffect(triggerNewFolder) {
        if (triggerNewFolder is Int && triggerNewFolder > 0) {
            contextFile = workspaceRoot
            nameInput = ""
            showNewFolder = true
        }
    }
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
                    onWorkspaceRootAdded?.invoke(it)
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
    val _createFileLauncher = rememberLauncherForActivityResult(
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
                    when (sortMode) {
                        1 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() }.thenBy { it.name }
                        2 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.length() }.thenBy { it.name }
                        3 -> compareByDescending<File> { it.isDirectory }.thenBy { it.extension }.thenBy { it.name }
                        else -> compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() }
                    }
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
                    if (guestPath != null) {
                        // P-SCM-8: Use GitCommandExecutor (centralized safe.directory + structured result)
                        val result = com.codespace.ide.scm.GitCommandExecutor.run(
                            context, listOf("status", "--porcelain"), guestPath, timeoutSeconds = 15L
                        )
                        if (result is com.codespace.ide.scm.GitResult.Ok) {
                            for (line in result.lines) {
                                val fs = com.codespace.ide.scm.ScmFileStatus.parse(line) ?: continue
                                val absPath = File(wsSnap, fs.path).absolutePath
                                // Use the first non-space change code for the badge (staged takes priority)
                                val code = if (fs.stagedChange != com.codespace.ide.scm.FileChange.UNMODIFIED
                                    && fs.stagedChange != com.codespace.ide.scm.FileChange.UNTRACKED) {
                                    fs.stagedChange
                                } else {
                                    fs.workingChange
                                }
                                statusMap[absPath] = when (code) {
                                    com.codespace.ide.scm.FileChange.MODIFIED -> 'M'
                                    com.codespace.ide.scm.FileChange.ADDED -> 'A'
                                    com.codespace.ide.scm.FileChange.DELETED -> 'D'
                                    com.codespace.ide.scm.FileChange.RENAMED -> 'R'
                                    com.codespace.ide.scm.FileChange.COPIED -> 'C'
                                    com.codespace.ide.scm.FileChange.UPDATED -> 'U'
                                    com.codespace.ide.scm.FileChange.UNTRACKED -> 'U'
                                    else -> ' '
                                }
                            }
                        }
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

    val nodes = remember(workspacePath, expanded.toMap(), refresh, filterQuery, sortMode) {
        val root = workspaceRoot ?: return@remember emptyList()
        if (!root.exists()) return@remember emptyList()
        val children = root.listFiles()
            ?.filter { !it.name.trimEnd().startsWith(".") }
            ?.sortedWith(
                when (sortMode) {
                    1 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() }.thenBy { it.name }
                    2 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.length() }.thenBy { it.name }
                    3 -> compareByDescending<File> { it.isDirectory }.thenBy { it.extension }.thenBy { it.name }
                    else -> compareByDescending<File> { it.isDirectory }.thenBy { it.name.trimEnd() }
                }
            )
            ?: emptyList()
        children.flatMap { f ->
            if (f.isDirectory) buildNodes(f, 0)
            else listOf(FsNode(f, 0))
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor)) {

        // ── Header: EXPLORER title bar (VS Code style) — title + "…" overflow only.
        // All folder-toolbar icons and the git badge now live on the workspace section's
        // own header below, matching real VS Code (the EXPLORER title bar itself is bare).
        Row(
            Modifier.fillMaxWidth().height(35.dp)
                .background(panelBg)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EXPLORER",
                fontSize = 11.sp, color = MutedColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.MoreVert, null, tint = MutedColor,
                modifier = Modifier.size(18.dp).clickable { onMoreMenu() })
        }
        HorizontalDivider(color = DividerColor)

        // Redundant "Filter files..." bar removed 2026-07-06 — the app already has a
        // dedicated Search pane (magnifying glass in the activity bar) for this. filterQuery
        // state is kept (harmless, unused input surface) in case a compact inline filter is
        // reintroduced later, but no UI row is rendered here anymore.

        // ── OPEN EDITORS section (collapsible, VS Code style — always visible,
        // even with 0 open tabs or no folder opened) ───────────────────────────
        Column {
            Row(
                Modifier.fillMaxWidth().height(24.dp)
                    .background(Color(0xFF252526))
                    .clickable { openEditorsExpanded = !openEditorsExpanded }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (openEditorsExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    null, tint = MutedColor, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("OPEN EDITORS", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (openTabs.isNotEmpty()) {
                    Text(openTabs.size.toString(), fontSize = 10.sp, color = MutedColor)
                }
            }
            if (openEditorsExpanded) {
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
            }
            HorizontalDivider(color = DividerColor, thickness = 1.dp)
        }

        // ── WORKSPACE / FOLDER section header (collapsible) — shows the folder name
        // (or "NO FOLDER OPENED"). Everything below (toolbar, device panel, empty-state
        // or tree) is placed as DIRECT children of the outer Column — not nested in an
        // extra wrapper — so the tree LazyColumn below can legally use Modifier.weight(1f)
        // and OUTLINE/TIMELINE stay visible underneath it instead of being pushed off. ──
        Row(
            Modifier.fillMaxWidth().height(24.dp)
                .background(Color(0xFF252526))
                .clickable { workspaceExpanded = !workspaceExpanded }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (workspaceExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                null, tint = MutedColor, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (workspaceRoot != null) workspaceRoot.name.uppercase() else "NO FOLDER OPENED",
                fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            // Local-vs-GitHub badge (UI bucket #6) — moved here from the old top
            // EXPLORER bar so it sits next to the folder name it actually describes.
            if (workspaceRoot != null) {
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
        }
        // ── Multi-select action bar ──
        if (multiSelectMode) {
            Row(
                Modifier.fillMaxWidth().height(32.dp)
                    .background(Color(0xFF252526))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selectedFiles.size} selected", fontSize = 11.sp, color = Color(0xFF007ACC), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    workspaceRoot?.let { root ->
                        fun collectFiles(f: File) {
                            if (!f.isDirectory) selectedFiles.add(f.absolutePath)
                            if (expanded[f.absolutePath] == true && f.isDirectory) {
                                f.listFiles()?.forEach { collectFiles(it) }
                            }
                        }
                        root.listFiles()?.forEach { collectFiles(it) }
                    }
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("All", fontSize = 11.sp, color = Color(0xFF007ACC))
                }
                TextButton(onClick = {
                    selectedFiles.forEach { path ->
                        if (!File(path).isDirectory) onOpenFile(path)
                    }
                    multiSelectMode = false
                    selectedFiles.clear()
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("Open", fontSize = 11.sp, color = Color(0xFF007ACC))
                }
                Icon(Icons.Default.ContentCopy, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        if (selectedFiles.size == 1) {
                            clipboardFile = File(selectedFiles.first())
                            clipboardCut = false
                        }
                        multiSelectMode = false
                        selectedFiles.clear()
                        onShowNotification?.invoke("Copied", "success")
                    })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Delete, null, tint = Color(0xFFF48771),
                    modifier = Modifier.size(16.dp).clickable {
                        selectedFiles.forEach { path ->
                            val f = File(path)
                            if (f.isDirectory) f.deleteRecursively() else f.delete()
                        }
                        selectedFiles.clear()
                        multiSelectMode = false
                        refresh++
                        onShowNotification?.invoke("Deleted files", "success")
                    })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Close, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable {
                        multiSelectMode = false
                        selectedFiles.clear()
                    })
            }
        }

        // ── Folder toolbar — clean VS Code-style with 3-dot overflow ──
        if (workspaceRoot != null && workspaceExpanded) {
            var showFolderOverflow by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().height(28.dp)
                    .background(panelBg)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
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
                // Refresh
                Icon(Icons.Default.Refresh, null, tint = MutedColor,
                    modifier = Modifier.size(16.dp).clickable { refresh++ })
                Spacer(Modifier.width(6.dp))
                // 3-dot overflow menu
                Box {
                    Icon(Icons.Default.MoreVert, null, tint = MutedColor,
                        modifier = Modifier.size(16.dp).clickable { showFolderOverflow = true })
                    DropdownMenu(expanded = showFolderOverflow, onDismissRequest = { showFolderOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text(if (multiSelectMode) "✓ Multi-select Mode" else "Multi-select Mode", fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; multiSelectMode = !multiSelectMode; if (!multiSelectMode) selectedFiles.clear() }
                        )
                        DropdownMenuItem(
                            text = { Text("Sort by: " + when (sortMode) { 0 -> "Name"; 1 -> "Date"; 2 -> "Size"; else -> "Type" }, fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; sortMode = (sortMode + 1) % 4 }
                        )
                        DropdownMenuItem(
                            text = { Text("Expand All", fontSize = 12.sp) },
                            onClick = {
                                showFolderOverflow = false
                                workspaceRoot?.let { root ->
                                    fun expandAll(f: File) {
                                        if (f.isDirectory) {
                                            expanded[f.absolutePath] = true
                                            f.listFiles()?.forEach { expandAll(it) }
                                        }
                                    }
                                    expandAll(root)
                                }
                                refresh++
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Collapse All", fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; expanded.clear(); refresh++ }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Import Images", fontSize = 12.sp) },
                            onClick = {
                                showFolderOverflow = false
                                pendingImageTargetDir = workspaceRoot
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add Folder to Workspace", fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; folderPicker.launch(null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (showDeviceFolders) "✓ Device Folders" else "Device Folders", fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; showDeviceFolders = !showDeviceFolders }
                        )
                        DropdownMenuItem(
                            text = { Text("Change Folder", fontSize = 12.sp) },
                            onClick = { showFolderOverflow = false; folderPicker.launch(null) }
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
        }

        // ── Device folders quick-access panel ──
        if (showDeviceFolders && workspaceExpanded) {
            Column(Modifier.fillMaxWidth().background(panelBg)) {
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
                                    onWorkspaceRootAdded?.invoke(path)
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
                                "Documents" -> Icons.AutoMirrored.Filled.Article
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

        if (workspaceExpanded) {
            if (workspaceRoot == null) {
                // ── No workspace selected — weight(1f) so it (like the tree below)
                // pushes OUTLINE/TIMELINE towards the bottom of the panel, matching
                // VS Code's layout when no folder is open. ──────────────────────
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFDDDDDD),
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("You have not yet opened a folder.", fontSize = 12.sp,
                        color = MutedColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                                onWorkspaceRootAdded?.invoke("/storage/emulated/0")
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
                                        onWorkspaceRootAdded?.invoke(path)
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
                                    "Documents" -> Icons.AutoMirrored.Filled.Article
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
                        .background(Color(0xFF252526))
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
                                .background(Color(0xFF252526))
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
                                    onWorkspaceRootRemoved?.invoke(rootPath)
                                })
                        }
                    }
                    HorizontalDivider(color = DividerColor, thickness = 1.dp)
                }

                // weight(1f): this LazyColumn is a DIRECT child of the outer explorer
                // Column, so it legally claims the remaining flexible space, scrolls
                // its own content (treeListState — unchanged, so the breadcrumb
                // scroll-to-directory math above still lines up with index 0 = first
                // node), and leaves OUTLINE/TIMELINE visible underneath it.
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = treeListState) {
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
                        val isMultiSelected = selectedFiles.contains(node.file.absolutePath)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        isMultiSelected -> SelectedBg
                                        isSelected -> SelectedBg
                                        else -> BgColor
                                    }
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (multiSelectMode) {
                                            val path = node.file.absolutePath
                                            if (selectedFiles.contains(path)) selectedFiles.remove(path)
                                            else selectedFiles.add(path)
                                            return@combinedClickable
                                        }
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
                                        if (multiSelectMode) {
                                            multiSelectMode = false
                                            selectedFiles.clear()
                                        } else {
                                            if (isImage) {
                                                previewImagePath = node.file.absolutePath
                                            }
                                            contextFile = node.file
                                            showCtxMenu = true
                                        }
                                    }
                                )
                                .padding(
                                    start = (8 + node.depth * 14).dp,
                                    top = 5.dp, bottom = 5.dp, end = 8.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiSelectMode) {
                                Checkbox(
                                    checked = isMultiSelected,
                                    onCheckedChange = { checked ->
                                        val path = node.file.absolutePath
                                        if (checked) selectedFiles.add(path)
                                        else selectedFiles.remove(path)
                                    },
                                    modifier = Modifier.size(20.dp),
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF007ACC),
                                        uncheckedColor = MutedColor,
                                    ),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
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
            }
        }
        HorizontalDivider(color = DividerColor, thickness = 1.dp)

        // ── OUTLINE section (collapsible, real LSP documentSymbol) — always visible,
        // independent of whether a folder is open (matches VS Code's Explorer layout). ──
        Column {
            Row(
                Modifier.fillMaxWidth().height(24.dp)
                    .background(Color(0xFF252526))
                    .clickable { showOutline = !showOutline }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showOutline) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    null, tint = MutedColor, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("OUTLINE", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (activeFilePath != null) {
                    Text(activeFilePath.substringAfterLast("/"), fontSize = 10.sp, color = MutedColor, maxLines = 1)
                }
            }
            if (showOutline) {
                if (activeFilePath != null) {
                    // Use the real LSP-backed OutlinePanel instead of inline regex
                    OutlinePanel(
                        filePath = activeFilePath,
                        onNavigate = { line ->
                            onOpenFileAtLine?.invoke(activeFilePath, line)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    )
                } else {
                    Text(
                        "No editor is currently active.",
                        fontSize = 11.sp, color = MutedColor,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            HorizontalDivider(color = DividerColor, thickness = 1.dp)
        }

        // ── TIMELINE section (collapsible, git log for active file) — always visible,
        // independent of whether a folder is open (matches VS Code's Explorer layout). ──
        Column {
            Row(
                Modifier.fillMaxWidth().height(24.dp)
                    .background(Color(0xFF252526))
                    .clickable { showTimeline = !showTimeline }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showTimeline) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    null, tint = MutedColor, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("TIMELINE", fontSize = 10.sp, color = MutedColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (activeFilePath != null) {
                    Text(activeFilePath.substringAfterLast("/"), fontSize = 10.sp, color = MutedColor, maxLines = 1)
                }
            }
            if (showTimeline) {
                if (activeFilePath != null) {
                    TimelinePanel(
                        filePath = activeFilePath,
                        projectDir = workspaceRoot,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    )
                } else {
                    Text(
                        "No editor is currently active.",
                        fontSize = 11.sp, color = MutedColor,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

    }

    // ── Context menu (long press) ─────────────────────────────────────────
    // P17-A: version history autosave — snapshot recently modified files every 20s
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
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
                        add("Open" to Icons.AutoMirrored.Filled.OpenInNew)
                        if (hasPreview) add("Preview" to Icons.Default.Image)
                        add("Rename" to Icons.Default.Edit)
                        add("Copy" to Icons.Default.ContentCopy)
                        add("Cut" to Icons.Default.ContentCut)
                        if (hasPaste) add("Paste" to Icons.Default.ContentPaste)
                        add("Duplicate" to Icons.Default.FileCopy)
                        if (workspaceRoot != null && f.parentFile?.absolutePath != workspaceRoot.absolutePath) {
                            add("Move to Root Folder" to Icons.Default.ArrowUpward)
                        }
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
                        if (!f.isDirectory && isArch && (f.name.lowercase().endsWith(".zip") || f.name.lowercase().endsWith(".jar"))) add("Extract Here" to Icons.Default.FolderOpen)
                        if (!f.isDirectory && (isHexBin || isElf || isArch)) add("Open as Text" to Icons.Default.Description)
                        add("Permissions" to Icons.Default.Lock)
                        if (!f.isDirectory) add("File Info" to Icons.Default.Info)
                        if (!f.isDirectory && (isHexBin || isArch)) add("Open as Strings" to Icons.AutoMirrored.Filled.FormatListBulleted)
                        if (!f.isDirectory && isHexBin) add("Open as Binary Inspector" to Icons.Default.BugReport)
                        if (!f.isDirectory && isElf) add("Disassembly" to Icons.Default.Terminal)
                            if (!f.isDirectory) add("Entropy Heatmap" to Icons.Default.Thermostat)
                            if (!f.isDirectory && isNetworkCapture(f.name)) add("Network Viewer" to Icons.Default.Wifi)
                            if (!f.isDirectory && isAiModel(f.name)) add("AI Model Viewer" to Icons.Default.Psychology)
                            if (!f.isDirectory && isAndroidRuntimeFile(f.name)) add("Runtime Viewer" to Icons.Default.Android)
                            if (!f.isDirectory) {
                                val aLabel = if (diffFileA == null) "Diff: Set File A" else "Diff: Set File A (${diffFileA!!.name})"
                                add(aLabel to Icons.Default.CompareArrows)
                                if (diffFileA != null && diffFileA!!.absolutePath != f.absolutePath)
                                    add("Diff: Set File B" to Icons.Default.CompareArrows)
                            }
                        if (!f.isDirectory) add("Binary Diff" to Icons.AutoMirrored.Filled.CompareArrows)
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
                                        "Move to Root Folder" -> {
                                            val root = workspaceRoot
                                            if (root != null && f.parentFile != null && f.parentFile!!.absolutePath != root.absolutePath) {
                                                val dest = File(root, f.name)
                                                if (dest.exists()) {
                                                    onShowNotification?.invoke("A file named \'${f.name}\' already exists in the root folder", "error")
                                                } else {
                                                    val moved = f.renameTo(dest)
                                                    if (moved) {
                                                        refresh++
                                                        selected = dest.absolutePath
                                                        onShowNotification?.invoke("Moved \'${f.name}\' to root folder", "success")
                                                    } else {
                                                        onShowNotification?.invoke("Failed to move \'${f.name}\'", "error")
                                                    }
                                                }
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
                                                trashProjectDir = projectDir
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
                                        "Extract Here" -> {
                                            showCtxMenu = false
                                            val outDir = File(f.parentFile ?: File(f.parent ?: "/"), f.nameWithoutExtension)
                                            if (!outDir.exists()) outDir.mkdirs()
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zis ->
                                                        var entry = zis.nextEntry
                                                        while (entry != null) {
                                                            val outFile = File(outDir, entry.name)
                                                            val outPath = outFile.canonicalPath
                                                            val dirPath = outDir.canonicalPath
                                                            if (!outPath.startsWith(dirPath)) {
                                                                entry = zis.nextEntry
                                                                continue
                                                            }
                                                            if (entry.isDirectory) {
                                                                outFile.mkdirs()
                                                            } else {
                                                                outFile.parentFile?.mkdirs()
                                                                outFile.outputStream().buffered().use { os ->
                                                                    val buf = ByteArray(8192)
                                                                    var n = zis.read(buf)
                                                                    while (n > 0) { os.write(buf, 0, n); n = zis.read(buf) }
                                                                }
                                                            }
                                                            zis.closeEntry()
                                                            entry = zis.nextEntry
                                                        }
                                                    }
                                                    refresh++
                                                    // TEST-43-FIX: Show success notification so user knows extraction worked
                                                    AppOutputLog.log("[Extract] Successfully extracted to ${outDir.absolutePath}", "terminal")
                                                    NotificationStore.add("${f.name} extracted", "Extracted to ${outDir.name}/")
                                                } catch (e: Exception) {
                                                    AppOutputLog.log("[Extract] Error: ${e.message}", "terminal")
                                                    NotificationStore.add("Extract failed", e.message ?: "Unknown error", NotificationStore.Severity.ERROR, NotificationStore.Source.SYSTEM)
                                                }
                                            }
                                        }
                                        "Open as Text" -> { onOpenFile(f.absolutePath); showCtxMenu = false }
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
                "Diff: Set File A" -> { diffFileA = f; if (diffFileA != null && diffFileB != null) showDiffViewer = true }
                "Diff: Set File B" -> { diffFileB = f; if (diffFileA != null && diffFileB != null) showDiffViewer = true }
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
        val localScope = rememberCoroutineScope()
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
        val localScope = rememberCoroutineScope()
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
    // Phase 21-X Step 10: Binary Diff Viewer
    if (showDiffViewer) {
        BinaryDiffViewerDialog(
            fileA = diffFileA,
            fileB = diffFileB,
            onDismiss = { showDiffViewer = false },
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
                    value = nameInput, onValueChange = { nameInput = it.replace("`", "").replace("\u0000", "") },
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
                                // P-FC1: Pre-check write permission before attempting creation
                                if (!targetDir.canWrite()) {
                                    // Directory not writable — try to fix permissions or use app-private storage
                                    if (!targetDir.exists()) targetDir.mkdirs()
                                    if (!targetDir.canWrite()) {
                                        // Fall back to app-private external storage
                                        val fallbackDir = context.getExternalFilesDir(null)
                                        if (fallbackDir != null && fallbackDir.canWrite()) {
                                            val fallbackFile = java.io.File(fallbackDir, nameInput)
                                            fallbackFile.parentFile?.mkdirs()
                                            fallbackFile.createNewFile()
                                            refresh++
                                            onShowNotification?.invoke("Created in app storage: ${fallbackFile.name} (original location was read-only)", "info")
                                            onOpenFile(fallbackFile.absolutePath)
                                        } else {
                                            onShowNotification?.invoke("Cannot write to this folder. Grant 'All files access' permission in Settings.", "error")
                                        }
                                        showNewFile = false
                                        nameInput = ""
                                        return@Button
                                    }
                                }
                                newFile.parentFile?.mkdirs()
                                // P-FC1: Try createNewFile first, then writeText as fallback
                                var created = false
                                try {
                                    created = newFile.createNewFile()
                                    if (!created && !newFile.exists()) {
                                        newFile.writeText("")
                                        created = newFile.exists()
                                    }
                                } catch (secEx: SecurityException) {
                                    // Android security exception — try writeText
                                    newFile.writeText("")
                                    created = newFile.exists()
                                }
                                if (created && newFile.exists()) {
                                    refresh++
                                    onShowNotification?.invoke("Created: ${newFile.name}", "success")
                                    onOpenFile(newFile.absolutePath)
                                } else if (newFile.exists()) {
                                    // File already existed — just open it
                                    refresh++
                                    onShowNotification?.invoke("Opened existing file: ${newFile.name}", "info")
                                    onOpenFile(newFile.absolutePath)
                                } else {
                                    // Last resort: use app-private storage
                                    val fallbackDir = context.getExternalFilesDir(null)
                                    if (fallbackDir != null) {
                                        val fallbackFile = java.io.File(fallbackDir, nameInput)
                                        fallbackFile.createNewFile()
                                        refresh++
                                        onShowNotification?.invoke("Created in app storage: ${fallbackFile.name} (original location was read-only)", "info")
                                        onOpenFile(fallbackFile.absolutePath)
                                    } else {
                                        onShowNotification?.invoke("Failed to create file: permission denied. Grant 'All files access' in Settings.", "error")
                                    }
                                }
                            } catch (e: Exception) {
                                // Fallback: try writing empty content
                                try {
                                    newFile.writeText("")
                                    refresh++
                                    onShowNotification?.invoke("Created: ${newFile.name}", "success")
                                    onOpenFile(newFile.absolutePath)
                                } catch (e2: Exception) {
                                    // Final fallback: app-private storage
                                    val fallbackDir = context.getExternalFilesDir(null)
                                    if (fallbackDir != null) {
                                        val fallbackFile = java.io.File(fallbackDir, nameInput)
                                        fallbackFile.createNewFile()
                                        refresh++
                                        onShowNotification?.invoke("Created in app storage: ${fallbackFile.name} (original location was read-only)", "info")
                                        onOpenFile(fallbackFile.absolutePath)
                                    } else {
                                        onShowNotification?.invoke("Failed to create file: ${e2.message}. Grant 'All files access' in Settings.", "error")
                                    }
                                }
                            }
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
                    value = nameInput, onValueChange = { nameInput = it.replace("`", "").replace("\u0000", "") },
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
                        try {
                            // P-FC1: Pre-check write permission
                            if (!dir.canWrite() && !dir.mkdirs()) {
                                onShowNotification?.invoke("Cannot write to this folder. Grant 'All files access' in Settings.", "error")
                                showNewFolder = false; nameInput = "" 
                            }
                            File(dir, nameInput).mkdirs()
                            refresh++
                            onShowNotification?.invoke("Created folder: ${nameInput}/", "success")
                        } catch (e: Exception) {
                            onShowNotification?.invoke("Failed to create folder: ${e.message}. Grant 'All files access' in Settings.", "error")
                        }
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
                    value = nameInput, onValueChange = { nameInput = it.replace("`", "").replace("\u0000", "") },
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
                                        trashProjectDir?.let { pd ->
                                            withContext(Dispatchers.IO) { WorkspaceManager.restoreFromTrash(pd, entry) }
                                            withContext(Dispatchers.IO) { trashEntries = WorkspaceManager.listTrash(pd) }
                                            refresh++
                                        }
                                    }
                                }) { Text("Restore", fontSize = 11.sp, color = IconColor) }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = {
                                    scope.launch {
                                        trashProjectDir?.let { pd ->
                                            withContext(Dispatchers.IO) {
                                                WorkspaceManager.purgeTrashEntry(pd, entry)
                                                trashEntries = WorkspaceManager.listTrash(pd)
                                            }
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
    // P30: Special full-name matching first (Dockerfile, Makefile, LICENSE, README, etc.)
    return when (name.lowercase()) {
        "dockerfile", ".dockerfile"        -> Icons.Default.Build
        "makefile", "gnumakefile"          -> Icons.Default.Build
        "cmakelists.txt"                   -> Icons.Default.Build
        "procfile"                         -> Icons.Default.Terminal
        "license", "licence",
        "license.txt", "licence.txt",
        "license.md", "licence.md"         -> Icons.Default.Description
        "readme", "readme.txt",
        "readme.md", "readme.rst"          -> Icons.AutoMirrored.Filled.Article
        "package.json",
        "package-lock.json",
        "yarn.lock", "pnpm-lock.yaml",
        "bun.lockb"                        -> Icons.Default.DataObject
        "tsconfig.json",
        "jsconfig.json"                    -> Icons.Default.Settings
        ".editorconfig", ".prettierrc",
        ".prettierrc.json", ".prettierrc.yaml",
        ".eslintrc", ".eslintrc.js",
        ".eslintrc.json", ".eslintrc.yaml",
        ".stylelintrc", ".huskyrc",
        ".lintstagedrc"                    -> Icons.Default.Settings
        ".babelrc", ".babelrc.js",
        ".babelrc.json", "babel.config.js",
        "babel.config.ts"                  -> Icons.Default.Build
        ".gitignore", ".gitattributes",
        ".gitmodules"                      -> Icons.Default.AccountTree
        ".env", ".env.local",
        ".env.development", ".env.production",
        ".env.test", ".env.example"        -> Icons.Default.Lock
        ".nvmrc", ".node-version"          -> Icons.Default.Settings
        "gemfile", "gemfile.lock"          -> Icons.Default.Code
        "podfile", "podfile.lock"          -> Icons.Default.Build
        "pubspec.yaml", "pubspec.lock"     -> Icons.Default.DataObject
        "cargo.toml", "cargo.lock"         -> Icons.Default.Build
        "go.mod", "go.sum"                 -> Icons.Default.Build
        "requirements.txt", "pipfile",
        "pipfile.lock", "pyproject.toml",
        "setup.py", "setup.cfg"            -> Icons.Default.Settings
        "dockerfile.dev",
        "docker-compose.yml",
        "docker-compose.yaml"              -> Icons.Default.Build
        else -> {
            // Extension-based matching
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                // Kotlin
                "kt", "kts"                       -> Icons.Default.Code
                // Java
                "java"                            -> Icons.Default.Coffee
                // Python
                "py", "pyw", "pyi"                -> Icons.Default.Code
                // JavaScript
                "js", "mjs", "cjs"                -> Icons.Default.Javascript
                // TypeScript
                "ts", "d.ts"                      -> Icons.Default.Javascript
                // JSX / TSX
                "jsx", "tsx"                      -> Icons.Default.Javascript
                // Vue / Svelte / Astro / Angular — component files
                "vue", "svelte", "astro",
                "component.ts", "component.html"  -> Icons.Default.Extension
                // Web markup
                "html", "htm", "xhtml"            -> Icons.Default.Html
                "css"                             -> Icons.Default.Css
                "scss", "sass", "less", "styl"    -> Icons.Default.Css
                // XML / SVG
                "xml", "xsl", "xslt"              -> Icons.Default.Code
                "svg"                             -> Icons.Default.Image
                // Data / Config
                "json", "jsonc", "json5"          -> Icons.Default.DataObject
                "yaml", "yml"                     -> Icons.Default.Settings
                "toml", "ini", "cfg", "conf",
                "config", "properties"            -> Icons.Default.Settings
                // GraphQL
                "graphql", "gql"                  -> Icons.Default.AccountTree
                // SQL / Database
                "sql", "mysql", "psql",
                "sqlite", "sqlite3", "db"         -> Icons.Default.Storage
                "csv", "tsv"                      -> Icons.Default.DataObject
                // Prisma / ORM
                "prisma"                          -> Icons.Default.Storage
                // Proto
                "proto"                           -> Icons.Default.Code
                // Build / Gradle
                "gradle", "gradlew"               -> Icons.Default.Build
                "pro"                             -> Icons.Default.Build
                // Markdown — distinctive document icon (VS Code style)
                "md", "markdown", "mdx"           -> Icons.Default.Description
                // Docs / Text
                "rst", "adoc", "asciidoc"         -> Icons.AutoMirrored.Filled.Article
                "txt", "log", "out"               -> Icons.AutoMirrored.Filled.Article
                // Images
                "png", "jpg", "jpeg", "gif",
                "bmp", "webp", "ico", "tiff",
                "tif", "heic", "heif"             -> Icons.Default.Image
                // Vector / Design
                "psd", "ai", "sketch", "fig",
                "xd"                              -> Icons.Default.Image
                // Video
                "mp4", "mov", "avi", "mkv",
                "webm", "flv", "m4v", "3gp"      -> Icons.Default.Movie
                // Audio
                "mp3", "wav", "flac", "ogg",
                "m4a", "aac", "opus", "wma"       -> Icons.Default.MusicNote
                // Archives
                "zip", "tar", "gz", "bz2",
                "xz", "7z", "rar", "tgz"         -> Icons.Default.FolderZip
                "apk", "aab", "apks", "xapk"     -> Icons.Default.PhoneAndroid
                "jar", "aar", "war", "ear"        -> Icons.Default.FolderZip
                // Shell / Scripts
                "sh", "bash", "zsh", "fish",
                "ps1", "psm1", "bat", "cmd"       -> Icons.Default.Terminal
                // Binaries / Native
                "dex", "so", "o", "a", "lib",
                "dll", "exe", "bin"               -> Icons.Default.Memory
                // Certs / Keys
                "pem", "cert", "crt", "cer",
                "key", "p12", "pfx", "jks",
                "keystore"                        -> Icons.Default.Lock
                // Documents
                "pdf"                             -> Icons.Default.PictureAsPdf
                "doc", "docx", "odt"              -> Icons.Default.Description
                "xls", "xlsx", "ods"              -> Icons.Default.DataObject
                "ppt", "pptx", "odp"              -> Icons.Default.Description
                // Fonts
                "ttf", "otf", "woff", "woff2",
                "eot"                             -> Icons.Default.TextFields
                // C / C++
                "c", "cpp", "cc", "cxx",
                "h", "hpp", "hxx"                 -> Icons.Default.Code
                // Rust
                "rs"                              -> Icons.Default.Code
                // Go
                "go"                              -> Icons.Default.Code
                // Swift
                "swift"                           -> Icons.Default.Code
                // Dart
                "dart"                            -> Icons.Default.Code
                // Ruby
                "rb", "rake", "gemspec"           -> Icons.Default.Code
                // PHP
                "php", "phtml"                    -> Icons.Default.Code
                // Lua
                "lua"                             -> Icons.Default.Code
                // C#
                "cs", "csx"                       -> Icons.Default.Code
                // F#
                "fs", "fsx", "fsi"                -> Icons.Default.Code
                // Scala
                "scala", "sc"                     -> Icons.Default.Code
                // Haskell / Elm / Clojure / Erlang / Elixir / Julia
                "hs", "lhs"                       -> Icons.Default.Functions
                "elm"                             -> Icons.Default.Functions
                "clj", "cljs", "cljc"             -> Icons.Default.Functions
                "ex", "exs"                       -> Icons.Default.Code
                "erl", "hrl"                      -> Icons.Default.Code
                "jl"                              -> Icons.Default.Functions
                // R
                "r", "rmd", "rnw"                 -> Icons.Default.Functions
                // CoffeeScript
                "coffee", "litcoffee"             -> Icons.Default.Code
                // Perl
                "pl", "pm", "t"                   -> Icons.Default.Code
                // WASM
                "wasm", "wat"                     -> Icons.Default.Memory
                // Git
                "gitignore", "gitattributes",
                "gitmodules"                      -> Icons.Default.AccountTree
                // Default
                else                              -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
        }
    }
}

/** P30: File type color — full-name + extension matching for maximum visual coverage. */
fun fileIconColor(name: String): Color {
    // Special full-name colours first
    val nameLower = name.lowercase()
    val specialColor = when (nameLower) {
        "dockerfile", ".dockerfile",
        "docker-compose.yml", "docker-compose.yaml",
        "dockerfile.dev"                            -> Color(0xFF1D63ED) // Docker blue
        "makefile", "gnumakefile",
        "cmakelists.txt"                            -> Color(0xFF6C9EF8)
        "procfile"                                  -> Color(0xFF4EAA25)
        "license", "licence",
        "license.txt", "licence.txt",
        "license.md", "licence.md"                  -> Color(0xFFFFA500)
        "readme", "readme.txt",
        "readme.md", "readme.rst"                   -> Color(0xFF4A90D9)
        "package.json", "package-lock.json",
        "yarn.lock", "pnpm-lock.yaml",
        "bun.lockb"                                 -> Color(0xFFCB3837) // npm red
        "tsconfig.json", "jsconfig.json"            -> Color(0xFF3178C6)
        ".editorconfig", ".prettierrc",
        ".prettierrc.json", ".prettierrc.yaml",
        ".eslintrc", ".eslintrc.js",
        ".eslintrc.json", ".eslintrc.yaml",
        ".stylelintrc", ".huskyrc",
        ".lintstagedrc"                             -> Color(0xFF4B32C3)
        ".babelrc", ".babelrc.js",
        ".babelrc.json", "babel.config.js",
        "babel.config.ts"                           -> Color(0xFFF5DA55) // Babel yellow
        ".gitignore", ".gitattributes",
        ".gitmodules"                               -> Color(0xFFF14E32) // Git orange-red
        ".env", ".env.local",
        ".env.development", ".env.production",
        ".env.test", ".env.example"                 -> Color(0xFFFFD700)
        "pubspec.yaml", "pubspec.lock"              -> Color(0xFF0175C2) // Dart blue
        "cargo.toml", "cargo.lock"                  -> Color(0xFFDEA584) // Rust orange
        "go.mod", "go.sum"                          -> Color(0xFF00ADD8) // Go cyan
        "gemfile", "gemfile.lock"                   -> Color(0xFFCC342D) // Ruby red
        "podfile", "podfile.lock"                   -> Color(0xFFEF5B25) // CocoaPods orange
        "requirements.txt", "pipfile",
        "pipfile.lock", "pyproject.toml",
        "setup.py", "setup.cfg"                     -> Color(0xFF4584B6) // Python blue
        else                                        -> null
    }
    if (specialColor != null) return specialColor

    // Extension-based colours
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // Kotlin — purple
        "kt", "kts"                                 -> Color(0xFF7F52FF)
        // Java — orange
        "java"                                      -> Color(0xFFE76F00)
        // Python — blue
        "py", "pyw", "pyi"                          -> Color(0xFF4584B6)
        // JavaScript — amber
        "js", "mjs", "cjs"                          -> Color(0xFFF0DB4F)
        // TypeScript — blue
        "ts", "d.ts"                                -> Color(0xFF3178C6)
        // JSX / TSX — cyan-blue
        "jsx", "tsx"                                -> Color(0xFF61DAFB)
        // Vue — green
        "vue"                                       -> Color(0xFF42B883)
        // Svelte — orange
        "svelte"                                    -> Color(0xFFFF3E00)
        // Astro — purple
        "astro"                                     -> Color(0xFFFF5D01)
        // HTML — orange
        "html", "htm", "xhtml"                      -> Color(0xFFE44D26)
        // CSS
        "css"                                       -> Color(0xFF264DE4)
        "scss", "sass"                              -> Color(0xFFCC6699)
        "less"                                      -> Color(0xFF1D365D)
        "styl"                                      -> Color(0xFF4EAA25)
        // XML
        "xml", "xsl", "xslt"                        -> Color(0xFF0066CC)
        // SVG — green
        "svg"                                       -> Color(0xFFFFB13B)
        // JSON — amber
        "json", "jsonc", "json5"                    -> Color(0xFFCCA700)
        // YAML — red
        "yaml", "yml"                               -> Color(0xFFCB171E)
        "toml"                                      -> Color(0xFF9C4221)
        "ini", "cfg", "conf", "config",
        "properties"                                -> Color(0xFF6C9EF8)
        // GraphQL — pink
        "graphql", "gql"                            -> Color(0xFFE10098)
        // SQL — teal
        "sql", "mysql", "psql"                      -> Color(0xFF00897B)
        "sqlite", "sqlite3", "db"                   -> Color(0xFF00897B)
        "csv", "tsv"                                -> Color(0xFF4CAF50)
        // Prisma — dark teal
        "prisma"                                    -> Color(0xFF2D3748)
        // Proto — blue-gray
        "proto"                                     -> Color(0xFF4285F4)
        // Gradle
        "gradle", "gradlew"                         -> Color(0xFF02303A)
        // Markdown — markdown brand blue
        "md", "markdown", "mdx"                     -> Color(0xFF4A90D9)
        "rst", "adoc"                               -> Color(0xFF6C9EF8)
        // Text
        "txt", "log", "out"                         -> Color(0xFF9E9E9E)
        // Images — pink
        "png", "jpg", "jpeg", "gif",
        "bmp", "webp", "ico", "tiff",
        "tif", "heic", "heif"                       -> Color(0xFFD63384)
        "psd", "ai", "sketch", "fig", "xd"          -> Color(0xFF0FA3B1)
        // Video — deep purple
        "mp4", "mov", "avi", "mkv",
        "webm", "flv", "m4v", "3gp"                -> Color(0xFF7B1FA2)
        // Audio — indigo
        "mp3", "wav", "flac", "ogg",
        "m4a", "aac", "opus", "wma"                -> Color(0xFF3F51B5)
        // Archives — brown
        "zip", "tar", "gz", "bz2",
        "xz", "7z", "rar", "tgz"                   -> Color(0xFF8B5E3C)
        "apk", "aab", "apks", "xapk"               -> Color(0xFF3DDC84) // Android green
        "jar", "aar", "war", "ear"                  -> Color(0xFFE76F00)
        // Shell — green
        "sh", "bash", "zsh", "fish"                 -> Color(0xFF4EAA25)
        "ps1", "psm1", "bat", "cmd"                 -> Color(0xFF012456) // PowerShell blue
        // Binaries — gray
        "dex", "so", "o", "a",
        "lib", "dll", "exe", "bin"                  -> Color(0xFF555555)
        // Certs / Keys — gold
        "pem", "cert", "crt", "cer",
        "key", "p12", "pfx", "jks",
        "keystore"                                  -> Color(0xFFFFD700)
        // PDF — red
        "pdf"                                       -> Color(0xFFE53935)
        // Docs
        "doc", "docx", "odt"                        -> Color(0xFF1E88E5)
        "xls", "xlsx", "ods"                        -> Color(0xFF43A047)
        "ppt", "pptx", "odp"                        -> Color(0xFFE53935)
        // Fonts — pink-purple
        "ttf", "otf", "woff", "woff2", "eot"        -> Color(0xFFAB47BC)
        // C / C++ — blue
        "c", "cpp", "cc", "cxx",
        "h", "hpp", "hxx"                           -> Color(0xFF659AD2)
        // Rust — orange
        "rs"                                        -> Color(0xFFDEA584)
        // Go — cyan
        "go"                                        -> Color(0xFF00ADD8)
        // Swift — orange
        "swift"                                     -> Color(0xFFFF6B35)
        // Dart — blue
        "dart"                                      -> Color(0xFF0175C2)
        // Ruby — red
        "rb", "rake", "gemspec"                     -> Color(0xFFCC342D)
        // PHP — purple
        "php", "phtml"                              -> Color(0xFF8892BF)
        // Lua — blue
        "lua"                                       -> Color(0xFF000080)
        // C# — purple
        "cs", "csx"                                 -> Color(0xFF9B4993)
        // F# — blue
        "fs", "fsx", "fsi"                          -> Color(0xFF378BBA)
        // Scala — red
        "scala", "sc"                               -> Color(0xFFDE3423)
        // Haskell — purple
        "hs", "lhs"                                 -> Color(0xFF5E5086)
        // Elm — green-teal
        "elm"                                       -> Color(0xFF60B5CC)
        // Clojure — green
        "clj", "cljs", "cljc"                       -> Color(0xFF5881D8)
        // Elixir — purple
        "ex", "exs"                                 -> Color(0xFF9B30FF)
        // Erlang — red
        "erl", "hrl"                                -> Color(0xFFB83998)
        // Julia — purple
        "jl"                                        -> Color(0xFF9558B2)
        // R — blue
        "r", "rmd", "rnw"                           -> Color(0xFF276DC2)
        // CoffeeScript — brown
        "coffee", "litcoffee"                       -> Color(0xFF3E2723)
        // Perl — blue
        "pl", "pm", "t"                             -> Color(0xFF0298C3)
        // WASM — purple
        "wasm", "wat"                               -> Color(0xFF654FF0)
        // Git
        "gitignore", "gitattributes",
        "gitmodules"                                -> Color(0xFFF14E32)
        // Default
        else -> IconColor
    }
}

// ── Stub panels ──────────────────────────────────────────────────────────────
private data class SearchResult(val file: String, val lineNum: Int, val lineText: String, val matchRange: IntRange)

@Composable fun SearchPanel(projectId: String, onOpenFileAtLine: ((String, Int) -> Unit)? = null) {
    val context = LocalContext.current
    // Test 49 fix: pre-fill with the last query searched in this project.
    var searchQuery  by remember { mutableStateOf(loadLastSearchQuery(context, projectId)) }
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
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(Unit) {
        // STABILITY-FIX: requestFocus() can throw "ActiveParent with no focused
        // child" — known Compose Foundation focus-system race when another
        // field/dialog released focus in the same frame this one requests it.
        try { focusRequester.requestFocus() } catch (_: IllegalArgumentException) {}
        keyboardController?.show()
        // Test 49 fix: searchQuery is pre-filled from the last search (see
        // loadLastSearchQuery below) — the debounced LaunchedEffect(searchQuery, ...)
        // further down already auto-runs performSearch() on initial composition when
        // it's non-blank, so results are ready without any extra call here.
    }

    fun performSearch(query: String) {
        if (query.isBlank()) { results = emptyList(); return }
        searching = true
        scope.launch {
            // Phase V-FIX (Tests 47/48): Fall back to default project dir when
            // workspace path isn't saved in prefs — same pattern as SourceControlPane.
            val wsPath = loadWorkspacePath(context, projectId)
                ?: com.codespace.ide.util.ProjectPathResolver.resolveProjectRoot(context, projectId)
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

    // BUG-FIX (Tests 47/48): search only ran when the user tapped the small "Search"
    // text link, which is easy to miss — typing a query and expecting real-time
    // results (like every other search box in the app) silently produced "No results
    // found" because performSearch() was simply never invoked. Auto-run it (debounced)
    // whenever the query or any filter/toggle changes, same as VS Code's Find in Files.
    LaunchedEffect(searchQuery, caseSensitive, useRegex, matchWholeWord, includePattern, excludePattern) {
        if (searchQuery.isBlank()) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        saveLastSearchQuery(context, projectId, searchQuery)
        performSearch(searchQuery)
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
                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
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

@Composable fun GitSidePanel(
    projectId: String,
    onRepoCloned: ((com.codespace.ide.domain.Project) -> Unit)? = null,
) { SourceControlPane(projectId, onRepoCloned) }

@Composable fun RunDebugPanel(
    onMoreMenu: () -> Unit,
    activeFilePath: String = "",
    onJumpToSource: (file: String, line: Int) -> Unit = { _, _ -> },
    onRunFile: (() -> Unit)? = null,
) {
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
    // P27-AUDIT: Full DAP variable expansion — maps variable key to child variables
    val expandedVarChildren = remember { mutableStateMapOf<String, List<DebugVariable>>() }
    var watchInput by remember { mutableStateOf("") }
    var watchIdCounter by remember { mutableStateOf(0) }
    var debugInput by remember { mutableStateOf("") }

    val bpListener: () -> Unit = { allBreakpoints = udm.getAllBreakpoints() }
    val stateListener: (com.codespace.ide.debug.DebugSession) -> Unit = { session ->
        sessionState = session.state
        // P27-7: Sync activeSessionId with UDM — single source of truth
        when (session.state) {
            DebugState.STOPPED, DebugState.ERROR, DebugState.FAILED, DebugState.CRASHED -> {
                if (activeSessionId == session.id) {
                    activeSessionId = null
                }
                variables = emptyList()
                callStack = emptyList()
            }
            DebugState.RUNNING, DebugState.PAUSED -> {
                if (activeSessionId == null) {
                    activeSessionId = session.id
                }
            }
            else -> {}
        }
    }
    val pausedListener: (List<DebugStackFrame>, List<DebugVariable>) -> Unit = { stack, vars ->
        callStack = stack
        variables = vars
        // P26-1c: Live watch — re-evaluate all watch expressions on each pause
        if (activeSessionId != null && watchExprs.isNotEmpty()) {
            val sid = activeSessionId!!
            watchExprs = watchExprs.map { w ->
                val newVal = udm.evaluateExpression(sid, w.expression) ?: "—"
                w.copy(value = newVal)
            }
        }
    }
    LaunchedEffect(Unit) {
        udm.addOnBreakpointsChangedListener(bpListener)
        udm.addOnSessionStateChangedListener(stateListener)
        udm.addOnPausedListener(pausedListener)
    }
    DisposableEffect(Unit) {
        onDispose {
            udm.removeOnBreakpointsChangedListener(bpListener)
            udm.removeOnSessionStateChangedListener(stateListener)
            udm.removeOnPausedListener(pausedListener)
        }
    }

    val isRunning = activeSessionId != null && sessionState != DebugState.STOPPED && sessionState != DebugState.ERROR && sessionState != DebugState.FAILED && sessionState != DebugState.CRASHED

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
                    // P27-9: Real debug configurations from DebugConfiguration
                    val configs = com.codespace.ide.debug.DebugConfiguration.defaults.map { it.name }
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
                // BUG-6 FIX: "Run File" button — executes the file without debugger (no breakpoints/pdb).
                // Sends a simple run command to the terminal tab (e.g. python3 main.py).
                if (onRunFile != null) {
                    FilledIconButton(
                        onClick = { onRunFile!!.invoke() },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF388A34)),
                    ) {
                        Icon(Icons.Default.PlayArrow, "Run File", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Debug button — starts a debug session with breakpoints (pdb/gdb/lldb)
                FilledIconButton(
                    onClick = {
                        // P25-DEBUG: Start a REAL debug session via UDM with the active file path.
                        // Detect language from the file extension if possible.
                        val udm2 = UniversalDebugManager
                        val dbgLang = if (activeFilePath.isNotBlank()) {
                            com.codespace.ide.domain.Language.fromPath(activeFilePath)
                        } else {
                            // P27-9: Derive language from DebugConfiguration type
                            val config = com.codespace.ide.debug.DebugConfiguration.defaults.find { it.name == selectedConfig }
                            when (config?.type) {
                                "python" -> com.codespace.ide.domain.Language.PYTHON
                                "node" -> com.codespace.ide.domain.Language.JAVASCRIPT
                                "terminal" -> com.codespace.ide.domain.Language.SHELL
                                else -> Language.KOTLIN
                            }
                        }
                        val sessionId = udm2.startDebug(dbgLang, activeFilePath, null)
                        if (sessionId != null) {
                            activeSessionId = sessionId
                            sessionState = DebugState.STARTING
                        }
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF007ACC)),
                ) {
                    Icon(Icons.Default.BugReport, "Debug", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                // P26-1: Full debug controls — Continue/Pause, Step Over, Step Into, Step Out, Stop
                FilledIconButton(
                    onClick = { activeSessionId?.let {
                        if (sessionState == DebugState.PAUSED) udm.resumeSession(it) else udm.pauseSession(it)
                    } },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF007ACC)),
                ) {
                    Icon(
                        if (sessionState == DebugState.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (sessionState == DebugState.PAUSED) "Continue" else "Pause",
                        tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = { activeSessionId?.let { udm.stepOver(it) } },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF3C3C3C)),
                ) {
                    Icon(Icons.Default.SkipNext, "Step Over", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = { activeSessionId?.let { udm.stepInto(it) } },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF3C3C3C)),
                ) {
                    Icon(Icons.Default.ArrowDownward, "Step Into", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = { activeSessionId?.let { udm.stepOut(it) } },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF3C3C3C)),
                ) {
                    Icon(Icons.Default.ArrowUpward, "Step Out", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                // P27-AUDIT: Restart button — wired to udm.restartSession()
                FilledIconButton(
                    onClick = {
                        activeSessionId?.let { sid ->
                            udm.restartSession(sid)
                            sessionState = DebugState.STARTING
                        }
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF388A34)),
                ) {
                    Icon(Icons.Default.Refresh, "Restart", tint = Color.White, modifier = Modifier.size(18.dp))
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
                        val varKey = v.name
                        val isExpanded = expandedVarChildren.containsKey(varKey)
                        Column {
                            Row(
                                Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp)
                                    .then(if (v.expandable) Modifier.clickable {
                                        if (isExpanded) {
                                            expandedVarChildren.remove(varKey)
                                        } else {
                                            val sid = activeSessionId
                                            if (sid != null && v.variablesReference > 0) {
                                                val children = udm.getChildVariables(sid, v.variablesReference)
                                                expandedVarChildren[varKey] = children
                                            }
                                        }
                                    } else Modifier),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (v.expandable) {
                                    Icon(
                                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        if (isExpanded) "Collapse" else "Expand",
                                        tint = MutedColor, modifier = Modifier.size(12.dp),
                                    )
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
                            // P27-AUDIT: Render child variables recursively (depth 1)
                            if (isExpanded) {
                                val children = expandedVarChildren[varKey] ?: emptyList()
                                children.forEach { child ->
                                    Row(Modifier.padding(start = 48.dp, top = 1.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (child.expandable) {
                                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MutedColor, modifier = Modifier.size(10.dp))
                                        } else {
                                            Spacer(Modifier.width(14.dp))
                                        }
                                        Spacer(Modifier.width(3.dp))
                                        Text(child.name, fontSize = 10.sp, color = IconColor, fontFamily = FontFamily.Monospace)
                                        Text(": ", fontSize = 10.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                                        Text(child.type, fontSize = 10.sp, color = Color(0xFF569CD6), fontFamily = FontFamily.Monospace)
                                        Text(" = ", fontSize = 10.sp, color = MutedColor, fontFamily = FontFamily.Monospace)
                                        Text(child.value, fontSize = 10.sp, color = TextColor, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (children.isEmpty()) {
                                    Text("No children", fontSize = 10.sp, color = MutedColor, modifier = Modifier.padding(start = 48.dp, bottom = 2.dp))
                                }
                            }
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
                        Row(
                            Modifier
                                .padding(start = 24.dp, top = 2.dp, bottom = 2.dp)
                                .fillMaxWidth()
                                .clickable { onJumpToSource(frame.file, frame.line) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Code, null, tint = if (frame.active) Color(0xFF569CD6) else IconColor, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(frame.function, fontSize = 11.sp, color = if (frame.active) Color(0xFF569CD6) else TextColor, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (frame.active) FontWeight.Bold else FontWeight.Normal)
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
                        Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp).fillMaxWidth()
                            .clickable { onJumpToSource(bp.filePath, bp.line) }, verticalAlignment = Alignment.CenterVertically) {
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
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
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

