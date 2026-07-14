package com.codespace.ide.recovery

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SafeMode {
    var active: Boolean = false
}

object RecoveryManager {
    private const val PREFS_NAME = "recovery_prefs"
    private const val KEY_CRASH_START_COUNT = "crash_start_count"

    fun autosaveFile(projectDir: File, filename: String, content: String) {
        try {
            val autosaveDir = File(projectDir, ".autosave")
            if (!autosaveDir.exists()) {
                autosaveDir.mkdirs()
            }
            val saveFile = File(autosaveDir, "$filename.autosave")
            FileOutputStream(saveFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun listAutosaves(projectDir: File): List<File> {
        val autosaveDir = File(projectDir, ".autosave")
        if (!autosaveDir.exists() || !autosaveDir.isDirectory) {
            return emptyList()
        }
        return autosaveDir.listFiles { file ->
            file.isFile && file.name.endsWith(".autosave")
        }?.toList() ?: emptyList()
    }

    fun restoreAutosave(file: File): String {
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun deleteAutosave(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createSnapshot(context: Context, projectDir: File): File {
        val snapshotsDir = File(projectDir, ".snapshots")
        if (!snapshotsDir.exists()) {
            snapshotsDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(snapshotsDir, "$timestamp.zip")
        
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zipDirectory(projectDir, projectDir, zos)
        }
        return zipFile
    }

    private fun zipDirectory(rootDir: File, sourceDir: File, zos: ZipOutputStream) {
        val files = sourceDir.listFiles() ?: return
        for (file in files) {
            if (file.name == ".snapshots") {
                // Skip the snapshots directory itself to avoid nested/infinite recursion
                continue
            }
            if (file.isDirectory) {
                zipDirectory(rootDir, file, zos)
            } else {
                val relativePath = file.absolutePath.substring(rootDir.absolutePath.length + 1)
                FileInputStream(file).use { fis ->
                    val entry = ZipEntry(relativePath)
                    zos.putNextEntry(entry)
                    val buffer = ByteArray(4096)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        zos.write(buffer, 0, length)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    fun generateDiagnosticsReport(context: Context): String {
        val sb = java.lang.StringBuilder()
        sb.append("=== DIAGNOSTICS REPORT ===\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Release: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Package: ").append(context.packageName).append("\n")
        
        try {
            val pm = context.packageManager
            val pInfo = pm.getPackageInfo(context.packageName, 0)
            val version = pInfo.versionName
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            sb.append("VersionName: ").append(version).append("\n")
            sb.append("VersionCode: ").append(code).append("\n")
        } catch (e: Exception) {
            sb.append("Version info error: ").append(e.message).append("\n")
        }

        sb.append("\n=== RECENT CRASH LOGS ===\n")
        try {
            val crashDir = File(context.filesDir, "crash_logs")
            if (crashDir.exists() && crashDir.isDirectory) {
                val files = crashDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                if (files != null && files.isNotEmpty()) {
                    val lastThree = files.take(3)
                    for (file in lastThree) {
                        sb.append("File: ").append(file.name)
                            .append(" (Modified: ").append(Date(file.lastModified()).toString()).append(")\n")
                        try {
                            sb.append(file.readText(Charsets.UTF_8))
                        } catch (ex: Exception) {
                            sb.append("Failed to read: ").append(ex.message).append("\n")
                        }
                        sb.append("\n---------------------------\n")
                    }
                } else {
                    sb.append("No crash logs found.\n")
                }
            } else {
                sb.append("Crash directory does not exist.\n")
            }
        } catch (e: Exception) {
            sb.append("Error reading crash logs: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun recordCrashStart(context: Context) {
        val prefs = getPrefs(context)
        val current = prefs.getInt(KEY_CRASH_START_COUNT, 0)
        prefs.edit().putInt(KEY_CRASH_START_COUNT, current + 1).apply()
    }

    fun recordCleanStart(context: Context) {
        getPrefs(context).edit().putInt(KEY_CRASH_START_COUNT, 0).apply()
    }

    fun isSafeModeRequired(context: Context): Boolean {
        return getPrefs(context).getInt(KEY_CRASH_START_COUNT, 0) >= 2
    }

    fun clearSafeMode(context: Context) {
        getPrefs(context).edit().putInt(KEY_CRASH_START_COUNT, 0).apply()
    }
}
