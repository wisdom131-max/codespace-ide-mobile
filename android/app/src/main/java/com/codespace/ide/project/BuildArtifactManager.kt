package com.codespace.ide.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.codespace.ide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 12-F — Build Artifact Manager
 *
 * Manages APK/AAB files produced by builds:
 *   list · info · share · delete
 *
 * Artifacts are scanned from the project's build output directories.
 */
object BuildArtifactManager {

    private const val TAG = "ArtifactManager"

    data class Artifact(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val type: ArtifactType,
    ) {
        val sizeLabel: String
            get() {
                val kb = sizeBytes / 1024.0
                return if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024)
            }

        val dateLabel: String
            get() = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastModified))
    }

    enum class ArtifactType(val extension: String) {
        APK("apk"), AAB("aab");

        companion object {
            fun fromName(name: String): ArtifactType? = when {
                name.endsWith(".apk", ignoreCase = true) -> APK
                name.endsWith(".aab", ignoreCase = true) -> AAB
                else -> null
            }
        }
    }

    private val _artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val artifacts: StateFlow<List<Artifact>> = _artifacts.asStateFlow()

    /**
     * Scan a project directory for APK/AAB artifacts.
     * Looks in standard Gradle output paths.
     */
    suspend fun scan(projectPath: String): List<Artifact> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Artifact>()
        val outputDirs = listOf(
            File(projectPath, "app/build/outputs/apk"),
            File(projectPath, "app/build/outputs/bundle"),
            File(projectPath, "build/outputs/apk"),
            File(projectPath, "build/outputs/bundle"),
        )

        outputDirs.filter { it.exists() && it.isDirectory }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && ArtifactType.fromName(it.name) != null }
                .forEach { file ->
                    results += Artifact(
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        type = ArtifactType.fromName(file.name)!!,
                    )
                }
        }

        // Sort newest first
        val sorted = results.sortedByDescending { it.lastModified }
        _artifacts.value = sorted
        sorted
    }

    /**
     * Delete an artifact file from disk and remove from the list.
     * @return true if deleted successfully
     */
    suspend fun delete(artifact: Artifact): Boolean = withContext(Dispatchers.IO) {
        val file = File(artifact.path)
        val ok = if (file.exists()) file.delete() else true
        if (ok) {
            _artifacts.value = _artifacts.value.filter { it.path != artifact.path }
        }
        ok
    }

    /**
     * Create a share Intent for an artifact (APK or AAB).
     */
    fun shareIntent(context: Context, artifact: Artifact): Intent {
        val file = File(artifact.path)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = when (artifact.type) {
                ArtifactType.APK -> "application/vnd.android.package-archive"
                ArtifactType.AAB -> "application/octet-stream"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Create an install Intent for an APK (not applicable for AAB).
     */
    fun installIntent(context: Context, artifact: Artifact): Intent? {
        if (artifact.type != ArtifactType.APK) return null
        val file = File(artifact.path)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
