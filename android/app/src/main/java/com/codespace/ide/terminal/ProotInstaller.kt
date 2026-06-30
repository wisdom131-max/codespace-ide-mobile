package com.codespace.ide.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProotInstaller — Ubuntu 25.04 (Questing) rootfs installer.
 *
 * Symlink strategy (r14):
 *   Write target←relpath to SYMLINKS.txt during streaming.
 *   After extraction + double GC, call Os.symlink(target, rootfs + "/" + relpath).
 *   This is the exact Termux pattern from TermuxInstaller.java.
 *   Avoids Files.createSymbolicLink() which uses symlinkat() — blocked by Samsung seccomp.
 *
 * Path construction fix (r14):
 *   hostLink = rootfs.absolutePath + "/" + relPath  (never File(rootfs, "/absolute"))
 *   File(parent, "/absolute") silently ignores parent in Java/Kotlin — r14 eliminates this bug.
 */
object ProotInstaller {

    private const val TAG     = "ProotInstaller"
    private const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    private const val VERSION = "ubuntu-questing-v4.30.1-r14"
    private const val XZ_MEMORY_LIMIT_KIB = 98304  // 96 MB — safe for LZMA2 preset 6

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu-rootfs")

    fun isInstalled(context: Context): Boolean {
        val v = File(context.filesDir, ".ubuntu_version")
        return v.exists() &&
               v.readText().trim() == VERSION &&
               File(rootfsDir(context), "usr/bin/bash").exists()
    }

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) { onProgress("Ubuntu already installed \u2713"); return }

        val rootfs      = rootfsDir(context)
        val versionFile = File(context.filesDir, ".ubuntu_version")
        val tarXzFile   = File(context.cacheDir, "ubuntu.tar.xz")

        // ── Download (with resume) ────────────────────────────────────────────
        try {
            onProgress("Downloading Ubuntu rootfs (~250 MB)\u2026")
            var attempts = 0
            while (true) {
                attempts++
                try {
                    val existingBytes = if (tarXzFile.exists()) tarXzFile.length() else 0L
                    val conn = URL(ROOTFS_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 120_000
                    if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
                    conn.connect()

                    val totalSize = conn.contentLengthLong.let {
                        if (it < 0) 260L * 1024 * 1024 else existingBytes + it
                    }.coerceAtLeast(260L * 1024 * 1024)

                    conn.inputStream.use { input ->
                        val fos = if (existingBytes > 0)
                            java.io.FileOutputStream(tarXzFile, true)
                        else
                            tarXzFile.outputStream()
                        fos.use { output ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = existingBytes
                            var n: Int
                            var lastPct = -1
                            while (input.read(buf).also { n = it } != -1) {
                                output.write(buf, 0, n)
                                downloaded += n
                                val pct = ((downloaded * 100) / totalSize).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    onProgress("Downloading... $pct% (${downloaded / (1024*1024)} MB)")
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
        } catch (e: Exception) {
            onProgress("Download failed: ${e.message}")
            return
        }

        // ── Extract ───────────────────────────────────────────────────────────
        onProgress("Preparing extraction\u2026")
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        System.gc()
        Thread.sleep(300)

        onProgress("Extracting rootfs (this takes ~3 min)\u2026")

        // Write symlinks to file during streaming (Termux pattern — no RAM list).
        val symlinksFile   = File(context.cacheDir, "SYMLINKS.txt")
        val symlinksWriter = BufferedWriter(FileWriter(symlinksFile))

        var filesWritten = 0
        var symCount     = 0

        try {
            XZCompressorInputStream(tarXzFile.inputStream(), false, XZ_MEMORY_LIMIT_KIB).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    val writeBuf = ByteArray(32 * 1024)
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        // stripped = path relative to rootfs root (no leading slash)
                        val stripped = stripRoot(entry.name)

                        if (stripped.startsWith("dev/") && stripped != "dev/") {
                            entry = tar.nextTarEntry; continue
                        }

                        val outFile = File(rootfs.absolutePath + "/" + stripped)

                        when {
                            entry.isDirectory -> outFile.mkdirs()

                            entry.isSymbolicLink -> {
                                // Termux format: target←relpath  (relpath has no leading slash)
                                symlinksWriter.write("${entry.linkName}\u2190${stripped}")
                                symlinksWriter.newLine()
                                outFile.parentFile?.mkdirs()
                                symCount++
                            }

                            else -> {
                                outFile.parentFile?.mkdirs()
                                runCatching {
                                    outFile.outputStream().use { o ->
                                        var n: Int
                                        while (tar.read(writeBuf).also { n = it } != -1)
                                            o.write(writeBuf, 0, n)
                                    }
                                    val mode = entry.mode
                                    if ((mode and 0b001_001_001) != 0 ||
                                        outFile.path.contains("/bin/") ||
                                        outFile.path.contains("/sbin/") ||
                                        outFile.path.contains("/lib/"))
                                        outFile.setExecutable(true, false)
                                    outFile.setReadable(true, false)
                                    filesWritten++
                                }.onFailure { Log.w(TAG, "Skipped ${entry.name}: ${it.message}") }
                            }
                        }
                        if (filesWritten > 0 && filesWritten % 1000 == 0)
                            onProgress("Extracting\u2026 $filesWritten files, $symCount symlinks")
                        entry = tar.nextTarEntry
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { symlinksWriter.close() }
            onProgress("Extract failed at $filesWritten files: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Extract failed", e)
            return
        } finally {
            runCatching { symlinksWriter.close() }
        }

        // ── Free memory before symlink pass ───────────────────────────────────
        tarXzFile.delete()
        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(300)

        // ── Symlink pass — exact Termux Os.symlink() pattern ──────────────────
        // hostLink = rootfs.absolutePath + "/" + relPath  (never File(rootfs, "/abs"))
        // File(root, "/usr/bin/python3") ignores root — Os.symlink avoids this bug.
        onProgress("Creating $symCount symlinks\u2026")
        var symlinksDone   = 0
        var symlinksFailed = 0

        symlinksFile.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                val idx = line.indexOf('\u2190')
                if (idx < 0) return@forEach
                val target   = line.substring(0, idx)
                val relPath  = line.substring(idx + "\u2190".length)
                val hostLink = rootfs.absolutePath + "/" + relPath

                File(hostLink).delete()
                runCatching {
                    Os.symlink(target, hostLink)
                    symlinksDone++
                }.onFailure { e ->
                    Log.w(TAG, "Os.symlink failed [$symlinksDone]: $hostLink -> $target : ${e.message}")
                    symlinksFailed++
                }

                if (symlinksDone % 200 == 0 && symlinksDone > 0)
                    onProgress("Symlinks\u2026 $symlinksDone / $symCount")
            }
        }
        symlinksFile.delete()
        Log.d(TAG, "Symlinks: done=$symlinksDone failed=$symlinksFailed total=$symCount")

        // ── Post-install config ───────────────────────────────────────────────
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply { mkdirs() }.setWritable(true, false)

        // DNS
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }

        // apt config
        val aptConfDir = File(rootfs, "etc/apt/apt.conf.d").apply { mkdirs() }
        File(aptConfDir, "00sandbox").writeText(
            "APT::Sandbox::User \"root\";\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n"
        )
        File(aptConfDir, "01dpkg-options").writeText(
            "DPkg::Options {\n   \"--force-unsafe-io\";\n};\n"
        )

        // Ubuntu 25.04 sources
        File(rootfs, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText(
                "# Ubuntu 25.04 (Questing) \u2014 written by VN Code\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports questing main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports questing-updates main restricted universe multiverse\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports questing-security main restricted universe multiverse\n"
            )
        }
        // Remove any .sources files that may override sources.list
        File(rootfs, "etc/apt/sources.list.d").listFiles()?.forEach { f ->
            if (f.name.endsWith(".sources") || f.name.endsWith(".list")) f.delete()
        }

        // dpkg unsafe-io for Samsung kernel
        File(rootfs, "etc/dpkg").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg").writeText(
            "# Written by CodeSpace IDE \u2014 OEM kernel workaround\nforce-unsafe-io\n"
        )

        versionFile.writeText(VERSION)
        onProgress("Ubuntu ready \u2713  ($filesWritten files, $symlinksDone/$symCount symlinks)")
        Log.d(TAG, "Install complete: files=$filesWritten symlinks=$symlinksDone/$symCount")
    }

    private fun stripRoot(name: String): String =
        name.split("/", limit = 2).let { if (it.size > 1) it[1] else name }

    fun launchArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        val nativeDir  = context.applicationInfo.nativeLibraryDir
        val proot      = "$nativeDir/libproot.so"
        val loader     = "$nativeDir/libproot-loader.so"
        val rootfs     = rootfsDir(context).absolutePath
        val tmpDir     = File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        val hostFiles  = context.filesDir.absolutePath
        val selinuxDir = File(context.cacheDir, "fake-selinux").apply { mkdirs() }.absolutePath

        Log.d(TAG, "launchArgs: proot=$proot  exists=${File(proot).exists()}")
        Log.d(TAG, "launchArgs: loader=$loader  exists=${File(loader).exists()}")
        Log.d(TAG, "launchArgs: rootfs=$rootfs  bash=${File(rootfs, "usr/bin/bash").exists()}")

        val args = arrayOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",
            "--kernel-release=5.15.0-android13-4",
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
            "--bind=/sdcard",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games",
            "MOZ_FAKE_NO_SANDBOX=1",
            "/bin/bash", "--login"
        )

        val envVars = arrayOf(
            "PROOT_LOADER=$loader",
            "PROOT_TMP_DIR=$tmpDir",
            "PROOT_NO_SECCOMP=1",
            "TMPDIR=$tmpDir",
            "HOME=/root",
            "DEBIAN_FRONTEND=noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN=true",
            "DPKG_FORCE=unsafe-io",
            "PERL_BADLANG=0",
            "LANG=C.UTF-8",
            "LC_ALL=C"
        )

        return Triple(proot, args, envVars)
    }
}
