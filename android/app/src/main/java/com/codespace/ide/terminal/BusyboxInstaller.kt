package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

object BusyboxInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val VERSION = "bootstrap-2024.08.18"

    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    fun prefixDir(context: Context): File = context.filesDir

    fun installIfNeeded(context: Context) {
        val prefix = prefixDir(context)
        val versionFile = File(context.filesDir, ".bootstrap_version")

        if (versionFile.exists() && versionFile.readText().trim() == VERSION) {
            Log.d(TAG, "Bootstrap already installed")
            return
        }

        Log.d(TAG, "Installing bootstrap to ${prefix.absolutePath}")
        try {
        prefix.deleteRecursively()
        prefix.mkdirs()

        val assetName = "bootstrap-aarch64.zip"
        context.assets.open(assetName).use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val targetFile = File(context.filesDir, entryName)

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        // Make executables in bin/ and lib/ executable
                        if (entryName.contains("/bin/") || entryName.contains("/lib/") ||
                            entryName.endsWith(".so") || !entryName.contains(".")) {
                            targetFile.setExecutable(true, false)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        // Handle symlinks file
        val symlinkFile = File(context.filesDir, "SYMLINKS.txt")
        if (symlinkFile.exists()) {
            symlinkFile.forEachLine { line ->
                val parts = line.split("←")
                if (parts.size == 2) {
                    val target = parts[0]
                    val linkPath = File(context.filesDir, parts[1])
                    linkPath.parentFile?.mkdirs()
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("ln", "-sf", target, linkPath.absolutePath))
                        process.waitFor()
                    } catch (e: Exception) {
                        Log.w(TAG, "Symlink failed: $line")
                    }
                }
            }
            symlinkFile.delete()
        }

        versionFile.writeText(VERSION)
        Log.d(TAG, "Bootstrap installed successfully. bash=${File(binDir(context), "bash").exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap installation failed: ${e.message}", e)
        }
    }

    fun environmentFor(context: Context): Map<String, String> {
        val prefix = prefixDir(context)
        val home = File(context.filesDir, "home").apply { mkdirs() }
        return mapOf(
            "PREFIX" to prefix.absolutePath,
            "HOME" to home.absolutePath,
            "TMPDIR" to File(prefix, "tmp").apply { mkdirs() }.absolutePath,
            "PATH" to "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "${prefix.absolutePath}/bin/bash",
            "PROOT_BIN" to "${context.applicationInfo.nativeLibraryDir}/libproot.so",
            "NATIVE_LIB_DIR" to context.applicationInfo.nativeLibraryDir,
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,

        )
    }
}
