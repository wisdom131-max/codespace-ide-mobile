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
 *
 * MEMORY BUDGET (3 GB Samsung device):
 *   - Download buffer: 64 KB (was 1 MB — no gain from larger buf on compressed stream)
 *   - XZCompressorInputStream memoryLimitInKb: 96 MB  — caps XZ decoder RAM usage.
 *     Default is unlimited; ubuntu-questing-aarch64 uses ~80 MB peak.  96 MB is safe.
 *   - Extraction buf per file: 8 KB   (unchanged — streaming, no per-file heap spike)
 */
object ProotInstaller {

    private const val TAG = "ProotInstaller"
    private const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    private const val VERSION = "ubuntu-questing-v4.30.1"

    // XZ memory limit in KiB — caps decoder RAM to 96 MB. Ubuntu .xz needs ~80 MB peak.
    // Without this, XZCompressorInputStream allocates whatever XZ blocks request (up to 800 MB).
    private const val XZ_MEMORY_LIMIT_KIB = 96 * 1024  // 96 MB

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
            val tarXzFile = File(context.cacheDir, "ubuntu.tar.xz")
            val expectedSize = 250L * 1024 * 1024
            var attempts = 0
            while (attempts < 3) {
                attempts++
                val existingBytes = if (tarXzFile.exists()) tarXzFile.length() else 0L
                if (existingBytes > 0) {
                    onProgress("Resuming download from ${existingBytes / (1024 * 1024)}MB...")
                } else {
                    onProgress("Downloading Ubuntu rootfs (~250 MB)...")
                }
                try {
                    val connection = java.net.URL(ROOTFS_URL).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000
                    if (existingBytes > 0) connection.setRequestProperty("Range", "bytes=$existingBytes-")
                    connection.connect()
                    val responseCode = connection.responseCode
                    if (responseCode == 416) { tarXzFile.delete(); continue }
                    val totalSize = connection.contentLengthLong.let { if (it > 0) it + existingBytes else expectedSize }
                    val outStream = if (existingBytes > 0 && responseCode == 206) java.io.FileOutputStream(tarXzFile, true) else tarXzFile.outputStream()
                    connection.inputStream.use { input ->
                        outStream.use { output ->
                            // 64 KB buffer — smaller means less peak heap during download.
                            // The bottleneck is network I/O, not memcpy, so 64 KB vs 1 MB
                            // makes zero throughput difference on a mobile connection.
                            val buf = ByteArray(64 * 1024)
                            var downloaded = existingBytes
                            var n: Int
                            var lastPct = -1
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                downloaded += n
                                val pct = ((downloaded * 100) / totalSize).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    onProgress("Downloading... $pct% (${downloaded / (1024*1024)}MB)")
                                    lastPct = pct
                                }
                            }
                        }
                    }
                    break
                } catch (e: Exception) {
                    if (attempts >= 3) throw e
                    onProgress("Download interrupted, retrying ($attempts/3)...")
                    Thread.sleep(2000)
                }
            }
            Log.d(TAG, "Downloaded ${tarXzFile.length()} bytes")

            onProgress("Extracting Ubuntu rootfs\u2026")
            rootfs.deleteRecursively()
            rootfs.mkdirs()

            // Force GC before opening the XZ decompressor — free any download-phase garbage.
            System.gc()
            Thread.sleep(300)

            var filesWritten = 0
            var totalBytes   = 0L
            // memoryLimitInKb caps XZ decoder RAM to 96 MB.
            // Without this, XZCompressorInputStream has no limit and can allocate up to
            // ~800 MB on large .xz files, causing OOM kills on devices with 3 GB RAM.
            // The ubuntu-questing-aarch64 tarball uses the LZMA2 preset 6 which peaks at
            // about 80 MB decoder RAM — 96 MB gives 16 MB headroom, safely within budget.
            XZCompressorInputStream(tarXzFile.inputStream(), false, XZ_MEMORY_LIMIT_KIB).use { xz ->
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
            System.gc() // Free rootfs extraction memory before pre-install
            Thread.sleep(500) // Give GC time to run
            // Install static busybox into rootfs for dpkg/tar support
            try {
                val busyboxDest = File(rootfs, "usr/local/bin/busybox")
                busyboxDest.parentFile?.mkdirs()
                context.assets.open("tools/busybox_arm64").use { input ->
                    busyboxDest.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                    }
                }
                busyboxDest.setExecutable(true, false)
                // Create symlinks for common tools
                listOf("tar", "ar", "xz", "gzip", "zstd", "unzstd", "sh").forEach { tool ->
                    val link = File(rootfs, "usr/local/bin/$tool")
                    if (!link.exists()) {
                        runCatching {
                            java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("/usr/local/bin/busybox"))
                        }
                    }
                }
                Log.d(TAG, "Busybox installed to rootfs")
            } catch (e: Exception) {
                Log.w(TAG, "Busybox install failed: ${e.message}")
            }
            File(rootfs, "root").mkdirs()

            // Pre-install essential packages into rootfs on host side (no proot/chdir needed)
            onProgress("Pre-installing essential packages...")
            try {
                // Dynamically resolve package URLs by streaming Packages.gz line by line
                val mirrorBase = "https://ports.ubuntu.com/ubuntu-ports"
                val packagesUrl = "$mirrorBase/dists/questing/main/binary-arm64/Packages.gz"
                val targets = setOf("curl", "libcurl4t64")  // curl binary + its shared lib
                val resolvedUrls = mutableMapOf<String, String>()
                java.util.zip.GZIPInputStream(java.net.URL(packagesUrl).openStream()).bufferedReader().useLines { lines ->
                    var currentPkg = ""
                    var currentFile = ""
                    for (line in lines) {
                        when {
                            line.startsWith("Package: ") -> {
                                currentPkg = line.removePrefix("Package: ").trim()
                                currentFile = ""
                            }
                            line.startsWith("Filename: ") -> currentFile = line.removePrefix("Filename: ").trim()
                            line.isEmpty() -> {
                                if (currentPkg in targets && currentFile.isNotEmpty()) {
                                    resolvedUrls[currentPkg] = "$mirrorBase/$currentFile"
                                }
                                currentPkg = ""; currentFile = ""
                            }
                        }
                        if (resolvedUrls.size == targets.size) return@useLines
                    }
                }
                val essentialDebs = resolvedUrls.values.toList()
                if (essentialDebs.isEmpty()) {
                    Log.w(TAG, "Pre-install: could not resolve any package URLs — check mirror or package names")
                    onProgress("Pre-install: package resolution failed (check network)")
                } else {
                    Log.d(TAG, "Resolved debs: $essentialDebs")
                }
                // Force GC before deb downloads — rootfs extraction leaves heap fragmented
                System.gc(); System.runFinalization(); System.gc()
                Thread.sleep(800) // give GC time to actually reclaim
                for (debUrl in essentialDebs) {
                    val debName = debUrl.substringAfterLast("/")
                    onProgress("Downloading $debName...")
                    val debFile = File(context.cacheDir, debName)
                    // Stream download to file
                    java.net.URL(debUrl).openStream().use { inp ->
                        debFile.outputStream().use { out ->
                            val buf = ByteArray(8192); var n: Int
                            while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                    }
                    onProgress("Extracting $debName...")
                    // Parse ar format — use raw FileInputStream (not buffered) so BoundedInputStream
                    // gets exact byte positions without buffered over-read corrupting zstd frames.
                    java.io.FileInputStream(debFile).use { fis ->
                        fis.skip(8) // ar global header ("!<arch>\n")
                        val headerBuf = ByteArray(60)
                        while (fis.read(headerBuf) == 60) {
                            val entryName = String(headerBuf, 0, 16).trim()
                            val entrySize = String(headerBuf, 48, 10).trim().toLongOrNull() ?: 0L
                            if (entryName.startsWith("data.tar")) {
                                // Single-pass streaming: BoundedInputStream -> decompressor -> tar
                                // No temp file = no disk space waste, no double buffering.
                                val bounded = org.apache.commons.compress.utils.BoundedInputStream(fis, entrySize)
                                val tarInput: java.io.InputStream = when {
                                    entryName.contains("zst") ->
                                        org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(bounded)
                                    entryName.contains("xz") ->
                                        // Also cap deb-internal XZ streams to 96 MB
                                        org.apache.commons.compress.compressors.xz.XZCompressorInputStream(bounded, false, XZ_MEMORY_LIMIT_KIB)
                                    entryName.contains("gz") ->
                                        java.util.zip.GZIPInputStream(bounded)
                                    else -> bounded
                                }
                                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(tarInput).use { tar ->
                                    var entry = tar.nextTarEntry
                                    val buf = ByteArray(8192)
                                    while (entry != null) {
                                        val stripped = entry.name.removePrefix("./")
                                        when {
                                            entry.isSymbolicLink -> {
                                                // Record symlinks — create after all files extracted
                                                // (target may not exist yet)
                                                val link = File(rootfs, stripped)
                                                link.parentFile?.mkdirs()
                                                runCatching {
                                                    java.nio.file.Files.createSymbolicLink(
                                                        link.toPath(),
                                                        java.nio.file.Paths.get(entry.linkName)
                                                    )
                                                }
                                            }
                                            entry.isDirectory -> File(rootfs, stripped).mkdirs()
                                            else -> {
                                                val outFile = File(rootfs, stripped)
                                                outFile.parentFile?.mkdirs()
                                                runCatching {
                                                    outFile.outputStream().use { o ->
                                                        var n: Int
                                                        while (tar.read(buf).also { n = it } != -1) o.write(buf, 0, n)
                                                    }
                                                    if ((entry.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                                }
                                            }
                                        }
                                        entry = tar.nextTarEntry
                                    }
                                }
                                break
                            } else {
                                // Drain non-data entries exactly (plus alignment padding byte)
                                var remaining = entrySize + if (entrySize % 2 != 0L) 1L else 0L
                                val skipBuf = ByteArray(8192)
                                while (remaining > 0) {
                                    val toRead = minOf(skipBuf.size.toLong(), remaining).toInt()
                                    val read = fis.read(skipBuf, 0, toRead)
                                    if (read == -1) break
                                    remaining -= read
                                }
                            }
                        }
                    }
                    debFile.delete()
                }
                onProgress("Essential packages pre-installed")
            } catch (e: Exception) {
                Log.e(TAG, "Pre-install failed: ${e.javaClass.simpleName}: ${e.message}", e)
                onProgress("Pre-install error: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Bake DNS + apt config permanently so they survive across proot sessions
            try {
                val resolvConf = File(rootfs, "etc/resolv.conf")
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

                val aptConfDir = File(rootfs, "etc/apt/apt.conf.d")
                aptConfDir.mkdirs()
                File(aptConfDir, "00sandbox").writeText(
                    "APT::Sandbox::User \"root\";\n" +
                    "Acquire::AllowInsecureRepositories \"true\";\n" +
                    "APT::Get::AllowUnauthenticated \"true\";\n"
                )
                Log.d(TAG, "Baked DNS + apt config into rootfs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bake DNS/apt config: ${e.message}")
            }

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
        val selinuxDir = File(context.cacheDir, "fake-selinux").apply { mkdirs() }.absolutePath

        // Log for diagnosis — this is how v13 handoff confirmed PROOT_LOADER was empty
        Log.d(TAG, "launchArgs: nativeDir=$nativeDir")
        Log.d(TAG, "launchArgs: proot=$proot  exists=${File(proot).exists()}")
        Log.d(TAG, "launchArgs: loader=$loader  exists=${File(loader).exists()}")
        Log.d(TAG, "launchArgs: rootfs=$rootfs  bashExists=${File(rootfs, "usr/bin/bash").exists()}")

        val args = arrayOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=6.17.0-android13-1",
            "-L",
            "--change-id=0:0",
            "--rootfs=$rootfs",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=$selinuxDir:/sys/fs/selinux",
            "--bind=$rootfs/tmp:/dev/shm",
            "--bind=$hostFiles:/host-files",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "MOZ_FAKE_NO_SANDBOX=1",
            "/bin/bash", "--login"
        )

        val envVars = arrayOf(
            "PROOT_LOADER=$loader",
            "PROOT_TMP_DIR=$tmpDir",
            "PROOT_NO_SECCOMP=1",
            "LD_LIBRARY_PATH=$nativeDir",
            // LD_PRELOAD libtermux-exec — intercepts exec() path rewriting, same trick Termux uses
            "LD_PRELOAD=$nativeDir/libtermux-exec.so",
            "TMPDIR=$tmpDir",
            "HOME=${context.filesDir.absolutePath}"
        )

        return Triple(proot, args, envVars)
    }
}
