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
    private const val VERSION = "ubuntu-questing-v4.30.1-r7"

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
            System.gc() // Free rootfs extraction memory before DNS config
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

                // CRITICAL: Write sources.list — the Ubuntu questing minimal image
                // ships with an empty or localhost-only sources.list, so apt update
                // finds zero packages. We write the full Ubuntu 25.04 (questing) sources.
                val sourcesDir = File(rootfs, "etc/apt")
                sourcesDir.mkdirs()
                File(sourcesDir, "sources.list").writeText(
                    "# Ubuntu 25.04 (Questing) — written by VN Code\n" +
                    "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports questing main restricted universe multiverse\n" +
                    "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports questing-updates main restricted universe multiverse\n" +
                    "deb [trusted=yes] http://ports.ubuntu.com/ubuntu-ports questing-security main restricted universe multiverse\n"
                )
                // Also remove any .sources files that may override sources.list
                File(rootfs, "etc/apt/sources.list.d").listFiles()?.forEach { f ->
                    if (f.name.endsWith(".sources") || f.name.endsWith(".list")) {
                        f.delete()
                    }
                }
                // Write /etc/dpkg/dpkg.cfg with the full option set device-verified in
                // ubuntu-proot-test (see AGENTS.md r6): force-unsafe-io alone got dpkg
                // running, but force-confnew/force-overwrite/no-debsig/no-triggers were
                // needed for clean multi-package upgrades and reinstalls without manual
                // prompts (which can't be answered — no controlling terminal in proot).
                val dpkgCfgDir = File(rootfs, "etc/dpkg")
                dpkgCfgDir.mkdirs()
                File(dpkgCfgDir, "dpkg.cfg").writeText(
                    "# Written by CodeSpace IDE — OEM kernel workaround\n" +
                    "force-unsafe-io\n" +
                    "force-confnew\n" +
                    "force-overwrite\n" +
                    "no-debsig\n" +
                    "no-triggers\n"
                )

                // Also write /etc/apt/apt.conf.d/01dpkg-options to pass the same flags
                // through apt automatically. DPkg::Lock::Timeout "0" skips the dpkg lock
                // wait entirely — flock() may itself be a blocked syscall on Samsung 5.15,
                // so waiting on it can hang rather than fail fast.
                File(aptConfDir, "01dpkg-options").writeText(
                    "DPkg::Options {\n" +
                    "   \"--force-unsafe-io\";\n" +
                    "   \"--force-confnew\";\n" +
                    "   \"--force-overwrite\";\n" +
                    "};\n" +
                    "DPkg::Lock::Timeout \"0\";\n" +
                    "DPkg::NoDebsig \"true\";\n"
                )

                // Fix dpkg database permissions. "Permission denied" creating
                // /var/lib/dpkg/status-old means dpkg can't write to its own database
                // directory — the rootfs was extracted by an Android process, so files
                // are owned by the Android app UID but proot's guest root has no write
                // permission on them. Force the whole dpkg database world-writable.
                val dpkgDbDir = File(rootfs, "var/lib/dpkg")
                dpkgDbDir.mkdirs()
                dpkgDbDir.setWritable(true, false)
                dpkgDbDir.setReadable(true, false)
                dpkgDbDir.setExecutable(true, false)
                val dpkgStatusFile = File(dpkgDbDir, "status")
                if (!dpkgStatusFile.exists()) dpkgStatusFile.createNewFile()
                dpkgStatusFile.setWritable(true, false)
                listOf("updates", "info", "parts", "triggers").forEach { sub ->
                    File(dpkgDbDir, sub).mkdirs()
                    File(dpkgDbDir, sub).setWritable(true, false)
                    File(dpkgDbDir, sub).setExecutable(true, false)
                }
                listOf("var/lib/apt/lists", "var/lib/apt/lists/partial",
                       "var/cache/apt/archives", "var/cache/apt/archives/partial").forEach { d ->
                    File(rootfs, d).mkdirs()
                    File(rootfs, d).setWritable(true, false)
                    File(rootfs, d).setExecutable(true, false)
                }

                // CRITICAL FIX (matches the exact bug reported live — dpkg-preconfigure
                // crashing with "cannot fetch initial working directory: Function not
                // implemented"): dpkg-preconfigure calls getcwd() via Perl at two points
                // during every package install. Samsung kernel 5.15 blocks SYS_getcwd
                // inside proot — binding /proc/self/cwd (see launchArgs below) helps the
                // top-level shell but does NOT cover getcwd() calls made by subprocesses
                // dpkg forks with a different real host cwd context, which is exactly
                // what dpkg-preconfigure's Perl runtime does. Making it a no-op skips the
                // entire Debconf pre-configuration stage — non-essential for package
                // installs in a headless proot environment. Same approach used by
                // proot-distro and UserLAnd for this exact class of kernel restriction.
                val dpkgPreconfigure = File(rootfs, "usr/sbin/dpkg-preconfigure")
                dpkgPreconfigure.parentFile?.mkdirs()
                dpkgPreconfigure.writeText("#!/bin/sh\n# no-op: getcwd() fails on Samsung 5.15 kernel inside proot\nexit 0\n")
                dpkgPreconfigure.setExecutable(true, false)

                // dpkg triggers call ldconfig, systemd-tmpfiles, invoke-rc.d after every
                // package install. These use syscalls blocked on Samsung 5.15 (unshare,
                // mount, pivot_root) and crash silently, leaving dpkg exit code 100 with
                // no error text. No-op them — same approach as proot-distro/UserLAnd/Andronix.
                // NOTE: dpkg-split, update-alternatives, and usr/sbin/service are
                // deliberately NOT in this list (see the comment further down this file) —
                // they are real, working binaries and stubbing them was previously the
                // actual root cause of silent install failures, not a fix.
                val noopScript = "#!/bin/sh\nexit 0\n"
                listOf(
                    "sbin/ldconfig",             // MOST CRITICAL — called after every lib install
                    "sbin/ldconfig.real",        // Ubuntu: ldconfig is a symlink to ldconfig.real
                    "usr/sbin/update-initramfs", // tries mount syscalls — crashes in proot
                    "usr/bin/systemd-tmpfiles",  // systemd — crashes immediately in proot
                    "usr/sbin/invoke-rc.d"       // tries to start daemons — always fails in proot
                ).forEach { rel ->
                    val f = File(rootfs, rel)
                    f.parentFile?.mkdirs()
                    f.writeText(noopScript)
                    f.setExecutable(true, false)
                }
                // policy-rc.d must return 101 ("action not allowed") so dpkg skips
                // attempting to start any bundled service during install.
                File(rootfs, "usr/sbin/policy-rc.d").let {
                    it.parentFile?.mkdirs()
                    it.writeText("#!/bin/sh\nexit 101\n")
                    it.setExecutable(true, false)
                }
                // Skip systemd/initramfs files entirely — their post-install triggers crash proot
                File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
                File(rootfs, "etc/dpkg/dpkg.cfg.d/01-proot-excludes").writeText(
                    "path-exclude /lib/systemd/system/*\n" +
                    "path-exclude /etc/systemd/*\n" +
                    "path-exclude /usr/share/initramfs-tools/*\n" +
                    "path-exclude /etc/initramfs-tools/*\n"
                )

                // Back up the real dpkg-split/update-alternatives/service binaries right
                // after extraction (they are never stubbed — see above) so the self-heal
                // check in 99-dpkg-fix.sh can restore them if anything ever reverts one
                // to a stub again (a package reinstall, a future regression).
                val persistentFixes = File(rootfs, "root/persistent-fixes")
                persistentFixes.mkdirs()
                listOf(
                    "usr/bin/dpkg-split"          to "dpkg-split.real",
                    "usr/bin/update-alternatives" to "update-alternatives.real",
                    "usr/sbin/service"            to "service.real"
                ).forEach { (rel, backupName) ->
                    val src = File(rootfs, rel)
                    if (src.exists()) {
                        runCatching { src.copyTo(File(persistentFixes, backupName), overwrite = true) }
                            .onFailure { Log.w(TAG, "Backup $rel failed: ${it.message}") }
                    }
                }

                // ssh init-script fix, applied by 99-dpkg-fix.sh once openssh-server is
                // apt-installed (not present in the base rootfs). Patched against the
                // REAL openssh-server_10.0p1-5ubuntu5 init script pulled from the Ubuntu
                // questing archive (not guessed) — fixes a blank Default-Stop LSB header
                // (silently skips shutdown symlinks) and a missing post-start liveness
                // check (false "[ OK ]" reported even when sshd's bind() fails, since
                // start-stop-daemon's exit code only reflects a successful fork).
                runCatching {
                    context.assets.open("rootfs-fixes/ssh-initd.patched").use { input ->
                        File(persistentFixes, "ssh-initd.patched").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }.onFailure { Log.w(TAG, "ssh-initd.patched asset copy failed: ${it.message}") }

                Log.d(TAG, "Baked DNS + apt config + dpkg fixes (preconfigure/permissions/triggers) into rootfs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bake DNS/apt config: ${e.message}")
            }

            // -- dpkg/apt fixes ported from ubuntu-proot-test after on-device verification --
            // (nano, a 29-package gcc toolchain, libc6-dev, and a 4-package postgresql chain
            // all installed cleanly with this exact combination -- see AGENTS.md for the full
            // debugging trail.) IMPORTANT: unlike the ubuntu-proot-test experiments, this code
            // deliberately does NOT stub out dpkg-split, update-alternatives, or service --
            // those no-op stubs (added defensively, never verified) turned out to be the
            // actual root cause of "apt install silently does nothing," not a real fix.
            // The freshly-extracted rootfs tarball already ships real versions of all three;
            // simply never touching them is the fix.
            try {
                // Copy the LD_PRELOAD shim into the guest rootfs. Fixes:
                //  1. link() -> EACCES on Android (dpkg's status/status-old hardlink backup)
                //     -> redirected to rename() instead.
                //  2. chown()/lchown()/fchown() -> EPERM in proot without real root
                //     -> no-op (returns success).
                val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
                val dpkgFixSrc = File(nativeLibDir, "libdpkg_android_fix.so")
                val dpkgFixDst = File(rootfs, "usr/lib/libdpkg_android_fix.so")
                dpkgFixDst.parentFile?.mkdirs()
                if (dpkgFixSrc.exists()) {
                    dpkgFixSrc.copyTo(dpkgFixDst, overwrite = true)
                    dpkgFixDst.setExecutable(true, false)
                    dpkgFixDst.setReadable(true, false)
                    Log.i(TAG, "dpkg_android_fix.so installed into rootfs")
                } else {
                    Log.w(TAG, "dpkg_android_fix.so not found at: ${dpkgFixSrc.absolutePath}")
                }

                // LD_PRELOAD must only be set INSIDE the guest (profile.d), never as a host
                // env var passed to proot itself, or libproot.so on the host fails to find it.
                val profileDDir = File(rootfs, "etc/profile.d")
                profileDDir.mkdirs()
                File(profileDDir, "99-dpkg-fix.sh").writeText(
                    "#!/bin/sh\n" +
                    "# dpkg Android/Samsung-5.15 self-heal fix - persists across every shell.\n" +
                    "# Consolidates every device-verified fix from the ubuntu-proot-test debug\n" +
                    "# sessions (see AGENTS.md). Runs on every shell start so that if any of\n" +
                    "# these get reverted (a package reinstall, a manual mistake, a future\n" +
                    "# rootfs bug), the environment repairs itself without a full re-extraction.\n" +
                    "\n" +
                    "# Fixes link()->EACCES (status-old backup) and chown()->EPERM.\n" +
                    "# Guarded: the shim is arm64-v8a only (see cpp/CMakeLists.txt) - on\n" +
                    "# other ABIs the file won't exist, so skip rather than break every shell.\n" +
                    "if [ -f /usr/lib/libdpkg_android_fix.so ]; then\n" +
                    "  export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so\n" +
                    "fi\n" +
                    "\n" +
                    "# dpkg-split, update-alternatives, service must NEVER be no-op stubs --\n" +
                    "# each is a real, working binary; stubbing any of them causes SILENT\n" +
                    "# no-op \"success\" that is extremely hard to diagnose. Restore from the\n" +
                    "# backup taken at extraction time if one is ever found stubbed.\n" +
                    "_restore_if_stub() {\n" +
                    "    live=\"\$1\"; backup=\"\$2\"\n" +
                    "    [ -f \"\$backup\" ] || return 0\n" +
                    "    if [ -f \"\$live\" ]; then\n" +
                    "        size=\$(wc -c < \"\$live\" 2>/dev/null || echo 0)\n" +
                    "        head2=\$(head -c 2 \"\$live\" 2>/dev/null)\n" +
                    "        if [ \"\$head2\" = \"#!\" ] && [ \"\$size\" -lt 200 ]; then\n" +
                    "            cp \"\$backup\" \"\$live\"; chmod 755 \"\$live\"\n" +
                    "        fi\n" +
                    "    else\n" +
                    "        cp \"\$backup\" \"\$live\"; chmod 755 \"\$live\"\n" +
                    "    fi\n" +
                    "}\n" +
                    "_restore_if_stub /usr/bin/dpkg-split          /root/persistent-fixes/dpkg-split.real\n" +
                    "_restore_if_stub /usr/bin/update-alternatives /root/persistent-fixes/update-alternatives.real\n" +
                    "_restore_if_stub /usr/sbin/service            /root/persistent-fixes/service.real\n" +
                    "\n" +
                    "# groupadd/useradd/usermod/groupdel/userdel must stay wrapped (not the raw\n" +
                    "# .real binary) so the lock-file EACCES fallback stays available. Each\n" +
                    "# wrapper's own script content was backed up at bake time.\n" +
                    "_restore_wrapper() {\n" +
                    "    name=\"\$1\"; live=\"/usr/sbin/\$name\"; backup=\"/root/persistent-fixes/\$name.wrapper\"\n" +
                    "    [ -f \"\$backup\" ] || return 0\n" +
                    "    if ! grep -q \"nlink lock-file workaround\" \"\$live\" 2>/dev/null; then\n" +
                    "        cp \"\$backup\" \"\$live\"; chmod 755 \"\$live\"\n" +
                    "    fi\n" +
                    "}\n" +
                    "for w in groupadd useradd usermod groupdel userdel; do\n" +
                    "    _restore_wrapper \"\$w\"\n" +
                    "done\n" +
                    "\n" +
                    "# ssh init-script self-heal: false \"[ OK ]\" on a failed bind + blank\n" +
                    "# Default-Stop LSB header (silently skips shutdown symlinks). Only\n" +
                    "# applies once openssh-server is apt-installed.\n" +
                    "if [ -f /etc/init.d/ssh ] && ! grep -q \"sshd exited immediately\" /etc/init.d/ssh 2>/dev/null; then\n" +
                    "    if [ -f /root/persistent-fixes/ssh-initd.patched ]; then\n" +
                    "        cp /root/persistent-fixes/ssh-initd.patched /etc/init.d/ssh\n" +
                    "        chmod 755 /etc/init.d/ssh\n" +
                    "    fi\n" +
                    "fi\n" +
                    "\n" +
                    "# Standalone Default-Stop check, independent of the marker check above.\n" +
                    "# No insserv on this platform -> update-rc.d reads the LSB header directly\n" +
                    "# and silently skips K-links (shutdown symlinks) if Default-Stop is blank --\n" +
                    "# this catches that case even if the file otherwise has the liveness-check\n" +
                    "# marker already (e.g. a future edit reintroduces just this one field).\n" +
                    "if [ -f /etc/init.d/ssh ] && grep -q \"^# Default-Stop:[[:space:]]*\$\" /etc/init.d/ssh 2>/dev/null; then\n" +
                    "    sed -i \"s/^# Default-Stop:.*\$/# Default-Stop:\\t\\t0 1 6/\" /etc/init.d/ssh\n" +
                    "fi\n"
                )
                File(profileDDir, "99-dpkg-fix.sh").setExecutable(true, false)

                installShadowUtilsWrappers(rootfs)

                Log.d(TAG, "dpkg fixes + shadow-utils wrappers installed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install dpkg/shadow-utils fixes: ${e.message}")
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
     * Installs 5 shadow-utils wrapper scripts (groupadd, useradd, usermod, groupdel,
     * userdel) that fall back to direct /etc/passwd|group|shadow edits when the real
     * binary fails on its lock-file check.
     *
     * Root cause: these tools lock /etc/passwd|group by link()-ing a temp file to a
     * .lock file, then explicitly check the resulting link count (nlink) to confirm
     * the lock is real. dpkg_android_fix.so's link()->rename() fallback makes the
     * call succeed, but rename() produces nlink=1, not the nlink=2 a genuine hardlink
     * would have -- so these tools correctly detect it's not a real lock and refuse.
     * No syscall-level shim can fix this; the lock semantics need bypassing at a
     * higher level, hence these wrapper scripts.
     *
     * Verified on-device (ubuntu-proot-test) against real postinst scripts:
     * openssh-client (groupadd), nginx-light (useradd/groupadd for www-data),
     * a 4-package postgresql chain (useradd with name-based GID, usermod -c/-a -G),
     * and direct groupdel/userdel calls -- plus a full install -> purge -> reinstall
     * cycle. See AGENTS.md for the full trail, including two bugs found IN these
     * wrappers themselves and fixed: (1) useradd's original fallback wrote a group
     * *name* straight into /etc/passwd's numeric GID field unresolved, corrupting the
     * line for glibc's NSS parser; (2) usermod/useradd/groupadd originally used
     * `args="$@"; set -- $args`, which POSIX-word-splits and silently truncates any
     * multi-word argument (e.g. -c "test comment" -> just "test"). Neither bug is
     * present below -- args are parsed directly from "$@"/"$1", never re-split.
     *
     * NOTE: constructed from the detailed ubuntu-proot-test session reports (the
     * scripts themselves were never committed to that repo -- they only existed live
     * on-device); not yet independently re-verified in this repo's own build. Treat
     * as code-verified, pending on-device confirmation here too.
     */
    private fun installShadowUtilsWrappers(rootfs: File) {
        fun installWrapper(binPath: String, script: String) {
            val original = File(rootfs, binPath)
            val real = File(rootfs, "$binPath.real")
            if (original.exists() && !real.exists()) {
                original.copyTo(real, overwrite = true)
                real.setExecutable(true, false)
            }
            original.parentFile?.mkdirs()
            original.writeText(script)
            // Back up the wrapper's own script content for 99-dpkg-fix.sh's self-heal
            // check -- without this, there's no way to reconstruct the wrapper if it's
            // ever overwritten (e.g. a package reinstall replacing it with .real).
            runCatching {
                val persistentFixes = File(rootfs, "root/persistent-fixes")
                persistentFixes.mkdirs()
                val backup = File(persistentFixes, "${original.name}.wrapper")
                backup.writeText(script)
            }
            original.setExecutable(true, false)
        }

        // Shared fallback trigger: only take the manual-edit path on a genuine
        // lock-file failure, never mask other real errors from the real binary.
        val lockCheck = """
case "${'$'}OUT" in
    *"lock"*|*"cannot lock"*) ;;
    *) echo "${'$'}OUT" >&2; exit ${'$'}STATUS ;;
esac
        """.trimIndent()

        installWrapper("usr/sbin/useradd", """
#!/bin/sh
# useradd wrapper - Android/proot nlink lock-file workaround (see AGENTS.md)
REAL=/usr/sbin/useradd.real
OUT=${'$'}("${'$'}REAL" "${'$'}@" 2>&1)
STATUS=${'$'}?
if [ ${'$'}STATUS -eq 0 ]; then echo "${'$'}OUT"; exit 0; fi
${lockCheck}

UID_VAL=""; GID_NAME=""; HOME_DIR=""; COMMENT=""; SHELL_PATH="/bin/sh"
GROUPS_LIST=""; USERNAME=""
while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        -u|--uid) UID_VAL="${'$'}2"; shift 2 ;;
        -g|--gid) GID_NAME="${'$'}2"; shift 2 ;;
        -d|--home|--home-dir) HOME_DIR="${'$'}2"; shift 2 ;;
        -c|--comment) COMMENT="${'$'}2"; shift 2 ;;
        -s|--shell) SHELL_PATH="${'$'}2"; shift 2 ;;
        -G|--groups) GROUPS_LIST="${'$'}2"; shift 2 ;;
        -m|--create-home|-r|--system) shift ;;
        -*) shift ;;
        *) USERNAME="${'$'}1"; shift ;;
    esac
done

if [ -z "${'$'}USERNAME" ]; then echo "useradd: no username given" >&2; exit 1; fi
if grep -q "^${'$'}USERNAME:" /etc/passwd 2>/dev/null; then
    echo "useradd: user '${'$'}USERNAME' already exists" >&2; exit 9
fi

if [ -z "${'$'}UID_VAL" ]; then
    UID_VAL=${'$'}(awk -F: '${'$'}3>=1000 && ${'$'}3<60000 {print ${'$'}3}' /etc/passwd | sort -n | tail -1)
    UID_VAL=${'$'}((${'$'}UID_VAL + 1))
    [ "${'$'}UID_VAL" -lt 1000 ] && UID_VAL=1000
fi

if [ -n "${'$'}GID_NAME" ]; then
    case "${'$'}GID_NAME" in
        ''|*[!0-9]*)
            GID_VAL=${'$'}(awk -F: -v g="${'$'}GID_NAME" '${'$'}1==g {print ${'$'}3}' /etc/group)
            if [ -z "${'$'}GID_VAL" ]; then
                echo "useradd: group '${'$'}GID_NAME' does not exist" >&2; exit 6
            fi
            ;;
        *) GID_VAL="${'$'}GID_NAME" ;;
    esac
else
    GID_VAL=${'$'}(awk -F: -v g="${'$'}USERNAME" '${'$'}1==g {print ${'$'}3}' /etc/group)
    [ -z "${'$'}GID_VAL" ] && GID_VAL=100
fi

[ -z "${'$'}HOME_DIR" ] && HOME_DIR="/home/${'$'}USERNAME"

echo "${'$'}USERNAME:x:${'$'}UID_VAL:${'$'}GID_VAL:${'$'}COMMENT:${'$'}HOME_DIR:${'$'}SHELL_PATH" >> /etc/passwd
echo "${'$'}USERNAME:!:19000:0:99999:7:::" >> /etc/shadow

if [ -n "${'$'}GROUPS_LIST" ]; then
    OLDIFS="${'$'}IFS"; IFS=','
    for g in ${'$'}GROUPS_LIST; do
        IFS="${'$'}OLDIFS"
        awk -F: -v g="${'$'}g" -v u="${'$'}USERNAME" 'BEGIN{OFS=":"} ${'$'}1==g { if (${'$'}4=="") ${'$'}4=u; else ${'$'}4=${'$'}4","u } {print}' /etc/group > /etc/group.tmp && mv /etc/group.tmp /etc/group
        IFS=','
    done
    IFS="${'$'}OLDIFS"
fi
exit 0
        """.trimIndent())

        installWrapper("usr/sbin/usermod", """
#!/bin/sh
# usermod wrapper - Android/proot nlink lock-file workaround (see AGENTS.md)
# -g/--gid deliberately unhandled: needs the same group-name resolution as
# useradd, not yet added upstream either.
REAL=/usr/sbin/usermod.real
OUT=${'$'}("${'$'}REAL" "${'$'}@" 2>&1)
STATUS=${'$'}?
if [ ${'$'}STATUS -eq 0 ]; then echo "${'$'}OUT"; exit 0; fi
${lockCheck}

NEW_COMMENT=""; HAS_COMMENT=0
NEW_SHELL=""; HAS_SHELL=0
NEW_UID=""; HAS_UID=0
NEW_HOME=""; HAS_HOME=0
APPEND_GROUPS=""; APPEND_MODE=0
USERNAME=""
while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        -c|--comment) NEW_COMMENT="${'$'}2"; HAS_COMMENT=1; shift 2 ;;
        -s|--shell) NEW_SHELL="${'$'}2"; HAS_SHELL=1; shift 2 ;;
        -u|--uid) NEW_UID="${'$'}2"; HAS_UID=1; shift 2 ;;
        -d|--home) NEW_HOME="${'$'}2"; HAS_HOME=1; shift 2 ;;
        -a|--append) APPEND_MODE=1; shift ;;
        -G|--groups) APPEND_GROUPS="${'$'}2"; shift 2 ;;
        -g|--gid) shift 2 ;;
        -*) shift ;;
        *) USERNAME="${'$'}1"; shift ;;
    esac
done

if [ -z "${'$'}USERNAME" ] || ! grep -q "^${'$'}USERNAME:" /etc/passwd 2>/dev/null; then
    echo "usermod: user '${'$'}USERNAME' does not exist" >&2; exit 6
fi
if [ "${'$'}HAS_UID" -eq 1 ]; then
    case "${'$'}NEW_UID" in ''|*[!0-9]*) echo "usermod: invalid uid '${'$'}NEW_UID'" >&2; exit 3 ;; esac
fi

awk -F: -v u="${'$'}USERNAME" -v newc="${'$'}NEW_COMMENT" -v hasc="${'$'}HAS_COMMENT" -v news="${'$'}NEW_SHELL" -v hass="${'$'}HAS_SHELL" -v newu="${'$'}NEW_UID" -v hasu="${'$'}HAS_UID" -v newh="${'$'}NEW_HOME" -v hash="${'$'}HAS_HOME" 'BEGIN{OFS=":"} ${'$'}1==u { if (hasu=="1") ${'$'}3=newu; if (hash=="1") ${'$'}6=newh; if (hass=="1") ${'$'}7=news; if (hasc=="1") ${'$'}5=newc; } {print}' /etc/passwd > /etc/passwd.tmp && mv /etc/passwd.tmp /etc/passwd

if [ -n "${'$'}APPEND_GROUPS" ] && [ "${'$'}APPEND_MODE" -eq 1 ]; then
    OLDIFS="${'$'}IFS"; IFS=','
    for g in ${'$'}APPEND_GROUPS; do
        IFS="${'$'}OLDIFS"
        awk -F: -v g="${'$'}g" -v u="${'$'}USERNAME" 'BEGIN{OFS=":"} ${'$'}1==g { already=0; n=split(${'$'}4, members, ","); for (i=1;i<=n;i++) if (members[i]==u) already=1; if (already==0) { if (${'$'}4=="") ${'$'}4=u; else ${'$'}4=${'$'}4","u } } {print}' /etc/group > /etc/group.tmp && mv /etc/group.tmp /etc/group
        IFS=','
    done
    IFS="${'$'}OLDIFS"
fi
exit 0
        """.trimIndent())

        installWrapper("usr/sbin/groupadd", """
#!/bin/sh
# groupadd wrapper - Android/proot nlink lock-file workaround (see AGENTS.md)
REAL=/usr/sbin/groupadd.real
OUT=${'$'}("${'$'}REAL" "${'$'}@" 2>&1)
STATUS=${'$'}?
if [ ${'$'}STATUS -eq 0 ]; then echo "${'$'}OUT"; exit 0; fi
${lockCheck}

GID_VAL=""; GROUPNAME=""
while [ ${'$'}# -gt 0 ]; do
    case "${'$'}1" in
        -g|--gid) GID_VAL="${'$'}2"; shift 2 ;;
        -*) shift ;;
        *) GROUPNAME="${'$'}1"; shift ;;
    esac
done
if [ -z "${'$'}GROUPNAME" ]; then echo "groupadd: no group name given" >&2; exit 1; fi
if grep -q "^${'$'}GROUPNAME:" /etc/group 2>/dev/null; then
    echo "groupadd: group '${'$'}GROUPNAME' already exists" >&2; exit 9
fi
if [ -z "${'$'}GID_VAL" ]; then
    GID_VAL=${'$'}(awk -F: '${'$'}3>=1000 && ${'$'}3<60000 {print ${'$'}3}' /etc/group | sort -n | tail -1)
    GID_VAL=${'$'}((${'$'}GID_VAL + 1))
    [ "${'$'}GID_VAL" -lt 1000 ] && GID_VAL=1000
fi
echo "${'$'}GROUPNAME:x:${'$'}GID_VAL:" >> /etc/group
exit 0
        """.trimIndent())

        installWrapper("usr/sbin/userdel", """
#!/bin/sh
# userdel wrapper - Android/proot nlink lock-file workaround (see AGENTS.md)
# Also fixes: real userdel's fallback path used to leave the deleted user
# as a permanent orphaned entry in every group's member list. This rebuilds
# /etc/group to drop them, same as the real binary would.
REAL=/usr/sbin/userdel.real
OUT=${'$'}("${'$'}REAL" "${'$'}@" 2>&1)
STATUS=${'$'}?
if [ ${'$'}STATUS -eq 0 ]; then echo "${'$'}OUT"; exit 0; fi
${lockCheck}

REMOVE_HOME=0; USERNAME=""
for arg in "${'$'}@"; do
    case "${'$'}arg" in
        -r|--remove) REMOVE_HOME=1 ;;
        -*) ;;
        *) USERNAME="${'$'}arg" ;;
    esac
done
if [ -z "${'$'}USERNAME" ] || ! grep -q "^${'$'}USERNAME:" /etc/passwd 2>/dev/null; then
    echo "userdel: user '${'$'}USERNAME' does not exist" >&2; exit 6
fi
HOME_DIR=${'$'}(awk -F: -v u="${'$'}USERNAME" '${'$'}1==u {print ${'$'}6}' /etc/passwd)

grep -v "^${'$'}USERNAME:" /etc/passwd > /etc/passwd.tmp && mv /etc/passwd.tmp /etc/passwd
grep -v "^${'$'}USERNAME:" /etc/shadow > /etc/shadow.tmp && mv /etc/shadow.tmp /etc/shadow

awk -F: -v u="${'$'}USERNAME" 'BEGIN{OFS=":"} { n=split(${'$'}4, members, ","); out=""; for (i=1;i<=n;i++) { if (members[i]!="" && members[i]!=u) out = (out=="") ? members[i] : out","members[i] } ${'$'}4=out; print }' /etc/group > /etc/group.tmp && mv /etc/group.tmp /etc/group

if [ "${'$'}REMOVE_HOME" -eq 1 ] && [ -n "${'$'}HOME_DIR" ] && [ -d "${'$'}HOME_DIR" ]; then
    find "${'$'}HOME_DIR" -mindepth 1 -delete 2>/dev/null
    rmdir "${'$'}HOME_DIR" 2>/dev/null
fi
exit 0
        """.trimIndent())

        installWrapper("usr/sbin/groupdel", """
#!/bin/sh
# groupdel wrapper - Android/proot nlink lock-file workaround (see AGENTS.md)
REAL=/usr/sbin/groupdel.real
OUT=${'$'}("${'$'}REAL" "${'$'}@" 2>&1)
STATUS=${'$'}?
if [ ${'$'}STATUS -eq 0 ]; then echo "${'$'}OUT"; exit 0; fi
${lockCheck}

GROUPNAME=""
for arg in "${'$'}@"; do
    case "${'$'}arg" in -*) ;; *) GROUPNAME="${'$'}arg" ;; esac
done
if [ -z "${'$'}GROUPNAME" ] || ! grep -q "^${'$'}GROUPNAME:" /etc/group 2>/dev/null; then
    echo "groupdel: group '${'$'}GROUPNAME' does not exist" >&2; exit 6
fi
GID_VAL=${'$'}(awk -F: -v g="${'$'}GROUPNAME" '${'$'}1==g {print ${'$'}3}' /etc/group)
if awk -F: -v gid="${'$'}GID_VAL" '${'$'}4==gid {found=1} END{exit !found}' /etc/passwd; then
    echo "groupdel: cannot remove the primary group of a user" >&2; exit 8
fi
grep -v "^${'$'}GROUPNAME:" /etc/group > /etc/group.tmp && mv /etc/group.tmp /etc/group
exit 0
        """.trimIndent())
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
            // --link2symlink REMOVED (r5): this flag makes proot queue all hardlinks as
            // deferred symlinks, resolved via symlinkat(). Samsung/TECNO Android 14 kernel
            // blocks symlinkat() inside unprivileged namespaces via seccomp -> SIGSYS crash.
            // This was fixed once already (r4, commit 70b415a) but got accidentally reverted
            // along with an unrelated bad experiment (commit 9de8534) and never came back.
            // The ubuntu-questing-aarch64 tarball uses real symlinks, not hardlinks, so
            // --link2symlink was never needed -- removing it stops the crash again.
            // --sysvipc removed: Samsung 5.15 kernel blocks SysV IPC syscalls inside
            // unprivileged namespaces (clone() seccomp). proot fails to start with it.
            "--kernel-release=5.15.0-android13-4",
            // -L (LDSO interception) removed: conflicts with our nativeLibraryDir .so layout
            // and is only needed for running guest executables that need a different linker.
            // Ubuntu 25.04 ships with a compatible linker — no interception needed.
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
            // -w sets the initial working directory inside proot to /root.
            // Without this getcwd() fails — proot can't map the host cwd into guest space.
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
            // LD_LIBRARY_PATH=$nativeDir REMOVED — this was injecting libandroid-support.so
            // (our app's AArch64 JNI .so) into the bash process inside proot, causing:
            // "CANNOT LINK EXECUTABLE: library libandroid-support.so not found"
            // proot itself finds its own libs via PROOT_LOADER. bash and Ubuntu binaries
            // use their own rpath — they must NOT see the host nativeLibraryDir at all.
            "TMPDIR=$tmpDir",
            "HOME=/root",  // inside proot chroot, home is /root (not host filesDir)
            // Prevent dpkg/debconf from trying to open a terminal frontend (dialog, readline).
            // Inside proot there's no controlling terminal for debconf — it crashes without this.
            "DEBIAN_FRONTEND=noninteractive",
            // Force dpkg to skip atomic rename (linkat/renameat2) which Samsung 5.15
            // kernel blocks inside proot. Uses direct write instead of rename-swap.
            // This is the same workaround Termux uses for OEM kernels.
            "DPKG_FORCE=unsafe-io",
            "DEBCONF_NONINTERACTIVE_SEEN=true",
            // Suppress perl locale warnings from dpkg post-install scripts
            "PERL_BADLANG=0",
            "LANG=C.UTF-8",
            "LC_ALL=C"
        )

        return Triple(proot, args, envVars)
    }
}

