package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Manages Ubuntu proot installation and launch.
 *
 * Uses pre-built Termux proot binaries (bundled in assets/proot/arm64-v8a/)
 * instead of building from source, avoiding all Android 14 TLS / SONAME /
 * PIE-vs-EXEC issues that plagued the custom build approach.
 *
 * On first Ubuntu launch:
 *  1. [ensureBinaries] extracts proot, proot-loader, libtalloc.so.2, and
 *     libandroid-shmem.so from assets into filesDir/proot-bin/
 *  2. [install] downloads + extracts the Ubuntu rootfs tarball
 *  3. [launchArgs] returns the correct executable path + env vars,
 *     with LD_LIBRARY_PATH pointing at our extracted libs dir so the
 *     dynamic linker finds them instead of the hardcoded Termux path.
 */
object ProotInstaller {

    private const val TAG = "ProotInstaller"
    private const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    private const val VERSION = "ubuntu-questing-v4.30.1"

    // The ABI sub-folder inside assets/proot/ that matches this device.
    // We only ship arm64-v8a for now; arm devices fall back gracefully.
    private val ASSET_ABI = "arm64-v8a"

    private val BINARY_NAMES = listOf(
        "proot",
        "proot-loader",
        "libtalloc.so.2",
        "libandroid-shmem.so"
    )

    // ── public helpers ────────────────────────────────────────────────────────

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu-rootfs")

    fun isInstalled(context: Context): Boolean {
        val versionFile = File(context.filesDir, ".ubuntu_version")
        return versionFile.exists() &&
               versionFile.readText().trim() == VERSION &&
               File(rootfsDir(context), "usr/bin/bash").exists()
    }

    /** Extract the bundled Termux proot binaries from assets → filesDir/proot-bin/ */
    fun ensureBinaries(context: Context) {
        val binDir = File(context.filesDir, "proot-bin").apply { mkdirs() }
        val assetMgr = context.assets
        for (name in BINARY_NAMES) {
            val dest = File(binDir, name)
            if (dest.exists() && dest.length() > 0) continue   // already extracted
            try {
                assetMgr.open("proot/$ASSET_ABI/$name").use { src ->
                    dest.outputStream().use { it.write(src.readBytes()) }
                }
                dest.setExecutable(true, false)
                dest.setReadable(true, false)
                Log.d(TAG, "Extracted $name → ${dest.absolutePath} (${dest.length()} B)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $name: ${e.message}")
            }
        }
    }

    /** Download + unpack the Ubuntu rootfs tarball. */
    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        val rootfs    = rootfsDir(context)
        val versionFile = File(context.filesDir, ".ubuntu_version")

        if (isInstalled(context)) {
            Log.d(TAG, "Ubuntu rootfs already installed")
            return
        }

        try {
            onProgress("Downloading Ubuntu rootfs (~250 MB)…")
            val tarXzFile = File(context.cacheDir, "ubuntu.tar.xz")
            URL(ROOTFS_URL).openStream().use { input ->
                tarXzFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
            Log.d(TAG, "Downloaded ${tarXzFile.length()} bytes")

            onProgress("Extracting Ubuntu rootfs…")
            rootfs.deleteRecursively()
            rootfs.mkdirs()

            var filesWritten = 0
            var totalBytes   = 0L
            XZCompressorInputStream(tarXzFile.inputStream()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        // Skip raw device nodes — proot virtualises /dev
                        if (!entry.name.contains("/dev/") || entry.name.endsWith("/dev/")) {
                            val stripped = entry.name.split("/", limit = 2)
                                .let { if (it.size > 1) it[1] else entry.name }
                            val outFile = File(rootfs, stripped)
                            when {
                                entry.isDirectory -> outFile.mkdirs()
                                entry.isSymbolicLink -> {
                                    runCatching {
                                        val link   = outFile.toPath()
                                        val target = java.nio.file.Paths.get(entry.linkName)
                                        if (java.nio.file.Files.exists(link))
                                            java.nio.file.Files.delete(link)
                                        java.nio.file.Files.createSymbolicLink(link, target)
                                    }.onFailure { Log.w(TAG, "Symlink failed ${entry.name}: ${it.message}") }
                                }
                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    runCatching {
                                        var bytes = 0L
                                        outFile.outputStream().use { out ->
                                            val buf = ByteArray(8192)
                                            var n: Int
                                            while (tar.read(buf).also { n = it } != -1) {
                                                out.write(buf, 0, n); bytes += n
                                            }
                                        }
                                        filesWritten++; totalBytes += bytes
                                        val mode = entry.mode
                                        if ((mode and 0b001_001_001) != 0 ||
                                            outFile.path.contains("/bin/") ||
                                            outFile.path.contains("/sbin/") ||
                                            outFile.path.contains("/lib/"))
                                            outFile.setExecutable(true, false)
                                        outFile.setReadable(true, false)
                                    }.onFailure { Log.w(TAG, "Skipped ${entry.name}: ${it.message}") }
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }

            tarXzFile.delete()
            versionFile.writeText(VERSION)
            onProgress("Ubuntu ready: $filesWritten files extracted ✓")
            Log.d(TAG, "Rootfs installed. files=$filesWritten bytes=$totalBytes")

        } catch (e: Exception) {
            Log.e(TAG, "Rootfs install failed: ${e.message}", e)
            onProgress("Failed: ${e.message}")
        }
    }

    /**
     * Returns (prootPath, args, envVars) ready to pass to TerminalSession.
     *
     * Key fix vs previous approach:
     *  - Uses the pre-built Termux proot from filesDir/proot-bin/ (not nativeLibDir)
     *  - Sets LD_LIBRARY_PATH to that same dir so the dynamic linker finds
     *    libtalloc.so.2 and libandroid-shmem.so regardless of the hardcoded
     *    RUNPATH in the proot binary (/data/data/com.termux/...)
     *  - PROOT_LOADER points to filesDir/proot-bin/proot-loader (a proper EXEC,
     *    which is what proot expects — it exec()s the loader, not dlopen()s it)
     */
    fun launchArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        ensureBinaries(context)

        val binDir  = File(context.filesDir, "proot-bin").absolutePath
        val proot   = "$binDir/proot"
        val loader  = "$binDir/proot-loader"
        val rootfs  = rootfsDir(context).absolutePath
        val tmpDir  = File(context.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath
        val hostFiles = context.filesDir.absolutePath

        // Make sure exec bits survived extraction
        File(proot).setExecutable(true, false)
        File(loader).setExecutable(true, false)

        val args = arrayOf(
            "--link2symlink",
            "--kill-on-exit",
            "--sysvipc",
            "-S", rootfs,
            "-b", "/proc:/proc",
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "$hostFiles:/host-files",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=en_US.UTF-8",
            "OLLAMA_HOST=0.0.0.0:11434",
            "OLLAMA_MODELS=/root/.ollama/models",
            "OLLAMA_KEEP_ALIVE=30m",
            "/bin/bash", "--login"
        )

        val envVars = arrayOf(
            // Tell proot where its loader lives (it exec()s it — must be executable)
            "PROOT_LOADER=$loader",
            // Override the hardcoded /data/data/com.termux RUNPATH
            "LD_LIBRARY_PATH=$binDir",
            "PROOT_TMP_DIR=$tmpDir",
            "TMPDIR=$tmpDir",
            // Disable seccomp filtering — many Android kernels don't support it
            "PROOT_NO_SECCOMP=1",
            "HOME=${context.filesDir.absolutePath}"
        )

        return Triple(proot, args, envVars)
    }
}
