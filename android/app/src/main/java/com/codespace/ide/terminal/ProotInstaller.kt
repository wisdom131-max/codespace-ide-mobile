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
 * Uses pre-built Termux proot binaries from jniLibs/arm64-v8a/ (packaged by Gradle
 * into nativeLibraryDir — always executable on Android, no W^X issues).
 *
 * Binaries in nativeLibraryDir:
 *   libproot.so         — the real proot PIE binary (entry point, not a shared lib)
 *   libproot-loader.so  — proot's guest ELF loader
 *   libtalloc.so        — talloc (SONAME patched to libtalloc.so)
 *   libandroid-shmem.so — Android shared memory shim required by proot
 *
 * On first Ubuntu launch:
 *  1. [install] downloads + extracts the Ubuntu rootfs tarball
 *  2. [launchArgs] returns correct executable + env vars using nativeLibraryDir paths
 */
object ProotInstaller {

    private const val TAG = "ProotInstaller"
    private const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    private const val VERSION = "ubuntu-questing-v4.30.1"

    // ── public helpers ────────────────────────────────────────────────────────

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu-rootfs")

    fun isInstalled(context: Context): Boolean {
        val versionFile = File(context.filesDir, ".ubuntu_version")
        return versionFile.exists() &&
               versionFile.readText().trim() == VERSION &&
               File(rootfsDir(context), "usr/bin/bash").exists()
    }

    /** No-op: binaries are in nativeLibraryDir (Gradle packages them automatically) */
    fun ensureBinaries(context: Context) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        Log.d(TAG, "nativeLibraryDir=$nativeDir")
        val proot  = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        Log.d(TAG, "proot:  exists=${proot.exists()}  exec=${proot.canExecute()}  size=${proot.length()}")
        Log.d(TAG, "loader: exists=${loader.exists()}  exec=${loader.canExecute()}  size=${loader.length()}")
    }

    /** Download + unpack the Ubuntu rootfs tarball. */
    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        val rootfs      = rootfsDir(context)
        val versionFile = File(context.filesDir, ".ubuntu_version")

        if (isInstalled(context)) {
            Log.d(TAG, "Ubuntu rootfs already installed")
            return
        }

        try {
            onProgress("Downloading Ubuntu rootfs (~250 MB)\u2026")
            val tarXzFile = File(context.cacheDir, "ubuntu.tar.xz")
            URL(ROOTFS_URL).openStream().use { input ->
                tarXzFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
            Log.d(TAG, "Downloaded ${tarXzFile.length()} bytes")

            onProgress("Extracting Ubuntu rootfs\u2026")
            rootfs.deleteRecursively()
            rootfs.mkdirs()

            var filesWritten = 0
            var totalBytes   = 0L
            XZCompressorInputStream(tarXzFile.inputStream()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
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
            onProgress("Ubuntu ready: $filesWritten files extracted \u2713")
            Log.d(TAG, "Rootfs installed. files=$filesWritten bytes=$totalBytes")

        } catch (e: Exception) {
            Log.e(TAG, "Rootfs install failed: ${e.message}", e)
            onProgress("Failed: ${e.message}")
        }
    }

    /**
     * Returns (prootPath, args, envVars) ready to pass to TerminalSession.
     *
     * Uses nativeLibraryDir — Gradle packages all libXXX.so files there automatically,
     * and Android always marks nativeLibraryDir as executable (no W^X issue).
     *
     * argv[0] MUST be the program name ("proot") — execvp() convention.
     * The JNI code does: execvp(cmdStr, argv) where argv[0] is args[0].
     * Without this the args are shifted by 1 and proot fails immediately.
     */
    fun launchArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot     = "$nativeDir/libproot.so"
        val loader    = "$nativeDir/libproot-loader.so"
        val rootfs    = rootfsDir(context).absolutePath
        val tmpDir    = File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        val hostFiles = context.filesDir.absolutePath

        // Log for diagnosis — this is how v13 handoff confirmed PROOT_LOADER was empty
        Log.d(TAG, "launchArgs: nativeDir=$nativeDir")
        Log.d(TAG, "launchArgs: proot=$proot  exists=${File(proot).exists()}")
        Log.d(TAG, "launchArgs: loader=$loader  exists=${File(loader).exists()}")
        Log.d(TAG, "launchArgs: rootfs=$rootfs  bashExists=${File(rootfs, "usr/bin/bash").exists()}")

        val args = arrayOf(
            "proot",            // argv[0] = program name — REQUIRED by execvp convention
            "--link2symlink",   // handle symlinks via ptrace (filesDir has no symlink support)
            "--kill-on-exit",
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
            "/usr/bin/bash", "--login"
        )

        val envVars = arrayOf(
            "PROOT_LOADER=$loader",          // proot exec()s the loader — must be set correctly
            "LD_LIBRARY_PATH=$nativeDir",    // linker finds libtalloc.so + libandroid-shmem.so here
            "PROOT_TMP_DIR=$tmpDir",
            "TMPDIR=$tmpDir",
            "PROOT_NO_SECCOMP=1",            // required on most Android kernels
            "HOME=${context.filesDir.absolutePath}"
        )

        return Triple(proot, args, envVars)
    }
}
