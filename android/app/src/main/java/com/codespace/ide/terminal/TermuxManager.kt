package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * TermuxManager — single source of truth for the Termux bootstrap prefix.
 *
 * Replaces TermuxBootstrapInstaller + BusyboxInstaller with a clean, first-party
 * implementation that achieves exact feature parity with real Termux.
 *
 * ── What this does ───────────────────────────────────────────────────────────
 * 1. Extracts bootstrap-aarch64.zip (bundled asset) to filesDir/termux-prefix/
 * 2. Resolves SYMLINKS.txt — copies multi-call binaries instead of symlinking
 *    (Samsung kernel 5.15 blocks symlinkat() via seccomp in app namespaces)
 * 3. Patches all shell scripts: replaces hardcoded /data/data/com.termux/files/usr
 *    with our actual prefix path (185 scripts in the Termux 0.118.x bootstrap)
 * 4. Writes a clean etc/profile and ~/.bashrc using $PREFIX-relative paths
 * 5. Writes etc/apt/sources.list pointing to packages-cf.termux.dev
 * 6. Returns a ready-to-exec session config for TerminalSession / NativePty
 *
 * ── Session launch (exact Termux parity) ─────────────────────────────────────
 * executable : filesDir/termux-prefix/bin/bash
 * argv[0]    : "-bash"   ← leading dash = login shell (sources etc/profile)
 * env        : PREFIX, HOME, PATH, TMPDIR, TERM, LANG, SHELL
 *              NO LD_LIBRARY_PATH — Termux binaries use rpath; setting it causes
 *              e_version mismatches with our app's .so files (signal 31 crash)
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * installIfNeeded() is safe to call from any thread; it is idempotent once the
 * version file is written.
 */
object TermuxManager {

    private const val TAG        = "TermuxManager"
    private const val ASSET_NAME = "bootstrap-aarch64.zip"

    // Bump this whenever extraction logic changes — forces re-extraction on device
    private const val VERSION = "termux-manager-v1"

    // ── Multi-call binary table ───────────────────────────────────────────────
    // These binaries dispatch via argv[0]. We COPY them instead of symlinking
    // to work around Samsung kernel's seccomp block on symlinkat().
    private val MULTI_CALL = mapOf(
        "coreutils" to listOf(
            "ls","cat","cp","mv","rm","mkdir","rmdir","ln","head","tail","wc",
            "chmod","chown","touch","stat","echo","printf","test","true","false",
            "pwd","env","sleep","sort","uniq","cut","tr","tee","dd","mktemp",
            "dirname","basename","readlink","realpath","date","id","whoami",
            "uname","du","df","sync","shuf","nproc","nohup","nice","timeout",
            "kill","stty","tty","yes","seq","comm","join","paste","expand",
            "unexpand","fold","nl","od","base64","md5sum","sha1sum","sha256sum",
            "sha512sum","cksum","numfmt","factor","truncate","shred","install",
            "link","unlink","logname","groups","users","who","pinky","uptime",
            "hostid","pathchk","runcon","chroot","mknod","mkfifo"
        ),
        "gawk"  to listOf("awk"),
        "dash"  to listOf("sh"),
        "grep"  to listOf("egrep","fgrep","rgrep"),
        "gzip"  to listOf("gunzip","zcat"),
        "bzip2" to listOf("bzip2recover","bzcat","bzip2"),
        "xz"    to listOf("xzdec","lzma","unlzma","lzcat"),
        "busybox" to listOf("ash")
    )

    // ── Paths ─────────────────────────────────────────────────────────────────
    fun prefixDir(context: Context): File = File(context.filesDir, "termux-prefix")
    fun homeDir(context: Context):   File = File(context.filesDir, "home").also { it.mkdirs() }
    fun bashPath(context: Context):  String = File(prefixDir(context), "bin/bash").absolutePath

    fun isInstalled(context: Context): Boolean {
        val ver = File(context.filesDir, ".termux_manager_version")
        return ver.exists() &&
               ver.readText().trim() == VERSION &&
               File(prefixDir(context), "bin/bash").exists()
    }

    // ── Main install ──────────────────────────────────────────────────────────
    fun installIfNeeded(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) {
            Log.d(TAG, "Already installed at version $VERSION")
            return
        }
        Log.d(TAG, "Starting fresh extraction — version $VERSION")
        onProgress("Installing Termux environment…")

        val prefix = prefixDir(context)
        prefix.deleteRecursively()
        prefix.mkdirs()

        // ── Step 1: Extract ZIP ───────────────────────────────────────────────
        var fileCount = 0
        context.assets.open(ASSET_NAME).use { assetStream ->
            ZipInputStream(assetStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != "SYMLINKS.txt") {
                        val outFile = File(prefix, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(8192); var n: Int
                            while (zip.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                        // Set executable bit for binaries
                        val name = entry.name
                        if (name.contains("/bin/") || name.contains("/sbin/") ||
                            name.endsWith(".so") || name.contains("/libexec/"))
                            outFile.setExecutable(true, false)
                        outFile.setReadable(true, false)
                        fileCount++
                        if (fileCount % 500 == 0) onProgress("Extracted $fileCount files…")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        onProgress("Extracted $fileCount files. Resolving symlinks…")

        // ── Step 2: Resolve SYMLINKS.txt ─────────────────────────────────────
        val symlinksTxt = File(prefix, "SYMLINKS.txt")
        if (!symlinksTxt.exists()) {
            // Try reading from asset directly
            try {
                context.assets.open(ASSET_NAME).use { assetStream ->
                    ZipInputStream(assetStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "SYMLINKS.txt") {
                                symlinksTxt.outputStream().use { out ->
                                    val buf = ByteArray(4096); var n: Int
                                    while (zip.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                                }
                                break
                            }
                            zip.closeEntry(); entry = zip.nextEntry
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "SYMLINKS.txt not found: ${e.message}") }
        }

        var symlinkCount = 0; var copyCount = 0
        if (symlinksTxt.exists()) {
            symlinksTxt.forEachLine { line ->
                // Format: "target←linkPath"  where ← = \u2190
                val parts = line.split("←")
                if (parts.size != 2) return@forEachLine
                val target   = parts[0].trim()
                val linkPath = parts[1].trim()
                val linkFile = File(prefix, linkPath)
                linkFile.parentFile?.mkdirs()

                // Determine if target is a multi-call binary
                val targetName = target.substringAfterLast("/")
                val isMcall = MULTI_CALL.containsKey(targetName)

                if (isMcall) {
                    // COPY instead of symlink — Samsung seccomp blocks symlinkat()
                    val targetFile = File(prefix, target)
                    if (targetFile.exists() && !linkFile.exists()) {
                        targetFile.copyTo(linkFile, overwrite = false)
                        linkFile.setExecutable(true, false)
                        copyCount++
                    }
                } else {
                    // Real symlink for everything else
                    runCatching {
                        if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath()))
                            java.nio.file.Files.delete(linkFile.toPath())
                        val targetPath = if (target.startsWith("/")) {
                            // Absolute target — make it relative to prefix
                            java.nio.file.Paths.get(prefix.absolutePath + target)
                        } else {
                            java.nio.file.Paths.get(target)
                        }
                        java.nio.file.Files.createSymbolicLink(linkFile.toPath(), targetPath)
                        symlinkCount++
                    }.onFailure {
                        // Symlink failed (seccomp) — fall back to copy
                        val targetFile = File(prefix, target).let { f ->
                            if (f.exists()) f else File(prefix, "bin/$target")
                        }
                        if (targetFile.exists()) {
                            runCatching { targetFile.copyTo(linkFile, overwrite = true) }
                            linkFile.setExecutable(true, false)
                            copyCount++
                        }
                    }
                }
            }
            symlinksTxt.delete()
        }
        onProgress("Symlinks: $symlinkCount created, $copyCount copied.")

        // ── Step 3: Patch all scripts — replace hardcoded com.termux paths ───
        onProgress("Patching scripts…")
        patchAllScripts(prefix, prefix.absolutePath, homeDir(context).absolutePath)

        // ── Step 4: Write environment files ───────────────────────────────────
        writeEnvironment(context, prefix)

        // ── Step 5: Mark installation complete ────────────────────────────────
        File(context.filesDir, ".termux_manager_version").writeText(VERSION)
        onProgress("Termux environment ready.")
        Log.d(TAG, "Install complete — $fileCount files, $symlinkCount symlinks, $copyCount copies")
    }

    // ── Patch all shell scripts ───────────────────────────────────────────────
    private fun patchAllScripts(prefix: File, prefixPath: String, homePath: String) {
        val OLD_PREFIX = "/data/data/com.termux/files/usr"
        val OLD_HOME   = "/data/data/com.termux/files/home"
        val marker     = OLD_PREFIX.toByteArray(Charsets.ISO_8859_1)
        var patched    = 0

        prefix.walkTopDown()
            .filter { it.isFile && it.length() in 1L..500_000L }
            .forEach { file ->
                try {
                    val bytes = file.readBytes()
                    // Fast byte scan for marker before UTF-8 decode
                    var found = false
                    outer@ for (i in 0..(bytes.size - marker.size)) {
                        for (j in marker.indices) {
                            if (bytes[i + j] != marker[j]) continue@outer
                        }
                        found = true; break
                    }
                    if (found) {
                        val text = bytes.toString(Charsets.UTF_8)
                            .replace(OLD_PREFIX, prefixPath)
                            .replace(OLD_HOME, homePath)
                        file.writeText(text, Charsets.UTF_8)
                        patched++
                    }
                } catch (_: Exception) { /* binary file — skip */ }
            }
        Log.d(TAG, "patchAllScripts: $patched files patched")
    }

    // ── Write environment files ───────────────────────────────────────────────
    private fun writeEnvironment(context: Context, prefix: File) {
        val prefixPath = prefix.absolutePath
        val homePath   = homeDir(context).absolutePath

        // etc/profile — rewritten entirely; bootstrap version has com.termux hardcodes
        File(prefix, "etc/profile").apply {
            parentFile?.mkdirs()
            writeText(
                "# VN Code — etc/profile\n" +
                "export PREFIX=$prefixPath\n" +
                "export HOME=$homePath\n" +
                "export TMPDIR=\$PREFIX/tmp\n" +
                "export PATH=\$PREFIX/bin:\$PREFIX/sbin:/system/bin\n" +
                "export TERM=xterm-256color\n" +
                "export LANG=en_US.UTF-8\n" +
                "export LC_ALL=en_US.UTF-8\n" +
                "export SHELL=\$PREFIX/bin/bash\n" +
                "export TERMUX_VERSION=0.118.1\n" +
                "export TERMUX_APP_PACKAGE_MANAGER=apt\n" +
                "export DPKG_FORCE=unsafe-io\n" +
                "export PERL_BADLANG=0\n" +
                "for i in \$PREFIX/etc/profile.d/*.sh; do [ -r \"\$i\" ] && . \"\$i\"; done\n" +
                "unset i\n" +
                "[ -r \"\$PREFIX/etc/bash.bashrc\" ] && . \"\$PREFIX/etc/bash.bashrc\"\n" +
                "[ -r \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"\n"
            )
        }

        // ~/.bashrc
        File(homePath, ".bashrc").apply {
            parentFile?.mkdirs()
            writeText(
                "# VN Code ~/.bashrc\n" +
                "alias ll='ls -la'\n" +
                "alias la='ls -A'\n" +
                "alias gs='git status'\n" +
                "alias gl='git log --oneline --graph --decorate --all -20'\n" +
                "alias gp='git push'\n" +
                "alias gc='git commit'\n" +
                "PS1='\\u@vncode:\\w\\$ '\n" +
                "echo \"Termux bash ready — apt update && apt install <package>\"\n"
            )
        }

        // etc/apt/sources.list — Termux main repo
        File(prefix, "etc/apt").mkdirs()
        File(prefix, "etc/apt/sources.list").writeText(
            "# Termux main repository\n" +
            "deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n"
        )

        // etc/apt/apt.conf.d/00vncode
        File(prefix, "etc/apt/apt.conf.d").mkdirs()
        File(prefix, "etc/apt/apt.conf.d/00vncode").writeText(
            "APT::Sandbox::User \"root\";\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "DPkg::Options { \"--force-unsafe-io\"; };\n"
        )

        // etc/dpkg/dpkg.cfg — force-unsafe-io for Samsung kernel
        File(prefix, "etc/dpkg").mkdirs()
        File(prefix, "etc/dpkg/dpkg.cfg").writeText(
            "# VN Code — Samsung kernel workaround\n" +
            "force-unsafe-io\n"
        )

        // tmp dir
        File(prefix, "tmp").mkdirs()
    }

    // ── Session launch args (exact Termux parity) ────────────────────────────
    /**
     * Returns (executablePath, argv, envArray) ready to pass to TerminalSession or NativePty.
     *
     * executable : prefix/bin/bash
     * argv[0]    : "-bash"  (login shell — bash sources etc/profile automatically)
     * env        : Minimal set matching Termux's TermuxShellEnvironment
     *              CRITICAL: NO LD_LIBRARY_PATH — causes signal 31 on Samsung
     */
    fun sessionArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        val prefix = prefixDir(context).absolutePath
        val home   = homeDir(context).absolutePath

        val shell = "$prefix/bin/bash"
        val argv  = arrayOf("-bash")  // argv[0] with leading dash = login shell
        val env   = arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "PWD=$home",
            "PREFIX=$prefix",
            "PATH=$prefix/bin:$prefix/sbin:/system/bin:/system/xbin",
            "TMPDIR=$prefix/tmp",
            "SHELL=$shell",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "TERMUX_VERSION=0.118.1",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "DPKG_FORCE=unsafe-io",
            "PERL_BADLANG=0",
            // Android system vars — passed through exactly as Termux does
            *buildAndroidEnv()
        )
        return Triple(shell, argv, env)
    }

    private fun buildAndroidEnv(): Array<String> {
        val keys = listOf(
            "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
            "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "EXTERNAL_STORAGE", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"
        )
        return keys.mapNotNull { k -> System.getenv(k)?.let { "$k=$it" } }.toTypedArray()
    }

    // ── Busybox fallback (used when bootstrap not yet installed) ─────────────
    fun busyboxPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libbusybox.so").absolutePath

    fun busyboxFallbackArgs(context: Context): Triple<String, Array<String>, Array<String>> {
        val busybox = busyboxPath(context)
        val home = homeDir(context).absolutePath
        val env = arrayOf(
            "HOME=$home", "TERM=xterm-256color", "LANG=en_US.UTF-8",
            "PATH=/system/bin:/system/xbin", "TMPDIR=${context.cacheDir.absolutePath}"
        )
        return Triple(busybox, arrayOf("-ash"), env)
    }
}
