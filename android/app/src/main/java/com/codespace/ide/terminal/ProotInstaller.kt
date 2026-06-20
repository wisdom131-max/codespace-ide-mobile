package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    private const val VERSION = "ubuntu-questing-v4.30.1"

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu-rootfs")

    fun isInstalled(context: Context): Boolean {
        val versionFile = File(context.filesDir, ".ubuntu_version")
        return versionFile.exists() && versionFile.readText().trim() == VERSION &&
            File(rootfsDir(context), "bin/bash").exists()
    }

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        val rootfs = rootfsDir(context)
        val versionFile = File(context.filesDir, ".ubuntu_version")

        if (isInstalled(context)) {
            Log.d(TAG, "Ubuntu rootfs already installed")
            return
        }

        try {
            onProgress("Downloading Ubuntu rootfs...")
            val tarXzFile = File(context.cacheDir, "ubuntu.tar.xz")
            URL(ROOTFS_URL).openStream().use { input ->
                tarXzFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
            Log.d(TAG, "Downloaded ${tarXzFile.length()} bytes")

            onProgress("Extracting Ubuntu rootfs...")
            rootfs.deleteRecursively()
            rootfs.mkdirs()

            var filesWritten = 0
            var totalBytes = 0L
            XZCompressorInputStream(tarXzFile.inputStream()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        // Skip device nodes - proot creates these virtually
                        if (!entry.name.contains("/dev/") || entry.name.endsWith("/dev/")) {
                            val outFile = File(rootfs, entry.name.substringAfter("/", entry.name).let {
                                // strip the top-level "ubuntu-questing-aarch64/" folder
                                val parts = entry.name.split("/", limit = 2)
                                if (parts.size > 1) parts[1] else entry.name
                            })
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                try {
                                    var entryBytes = 0L
                                    outFile.outputStream().use { out ->
                                        val buffer = ByteArray(8192)
                                        var read: Int
                                        while (tar.read(buffer).also { read = it } != -1) {
                                            out.write(buffer, 0, read)
                                            entryBytes += read
                                        }
                                    }
                                    filesWritten++
                                    totalBytes += entryBytes
                                    if (filesWritten <= 5 || entryBytes == 0L) {
                                        Log.d(TAG, "Wrote ${entry.name} -> ${outFile.path} ($entryBytes bytes, entry.size=${entry.size})")
                                    }
                                    // Preserve executable bit from tar entry mode, and always
                                    // mark anything in bin/ or lib/ executable as a safety net
                                    val mode = entry.mode
                                    val isExecBit = (mode and 0b001_000_000) != 0 || (mode and 0b000_001_000) != 0 || (mode and 0b000_000_001) != 0
                                    if (isExecBit || outFile.path.contains("/bin/") || outFile.path.contains("/lib/") || outFile.path.contains("/sbin/")) {
                                        outFile.setExecutable(true, false)
                                    }
                                    outFile.setReadable(true, false)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Skipped ${entry.name}: ${e.message}")
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }

            tarXzFile.delete()
            versionFile.writeText(VERSION)
            onProgress("Ubuntu rootfs ready: $filesWritten files, $totalBytes bytes")
            Log.d(TAG, "Ubuntu rootfs installed. filesWritten=$filesWritten totalBytes=$totalBytes bash exists=${File(rootfs, "bin/bash").exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Ubuntu rootfs install failed: ${e.message}", e)
            onProgress("Failed: ${e.message}")
        }
    }

    fun launchArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        val proot = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
        val rootfs = rootfsDir(context).absolutePath
        val tmpDir = File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath
        val args = arrayOf(
            proot,
            "-S", rootfs,
            "/usr/bin/bash", "--login"
        )
        val envVars = arrayOf("PROOT_TMP_DIR=$tmpDir", "TMPDIR=$tmpDir", "PROOT_NO_SECCOMP=1")
        return Triple(proot, args, envVars)
    }
}
