// ⚠️ DEAD CODE — DO NOT EDIT OR RELY ON THIS FILE
// The app is Ubuntu-only. The Bash/Termux tab was removed. No code calls this class.
// Kept for reference only.

package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Extracts the full Termux bootstrap prefix from the bundled bootstrap-aarch64.zip asset.
 *
 * The ZIP is embedded in android/app/src/main/assets/bootstrap-aarch64.zip.
 * It contains 3,490 entries: bash 5.x, curl, apt, dpkg, coreutils, full Termux prefix.
 *
 * Extraction target: context.filesDir/termux-prefix/
 * Shell path after install: termux-prefix/bin/bash
 *
 * SYMLINKS.txt format (inside the ZIP):
 *   target←./link/path   (← = \u2190)
 *   Paths are relative to the prefix root.
 *
 * SAMSUNG KERNEL FIX:
 *   Samsung kernel 5.15 blocks symlinkat() via seccomp in app namespaces.
 *   All SYMLINKS.txt entries that point at coreutils/gawk/dash are resolved
 *   by COPYING the real binary instead of creating a symlink. The coreutils
 *   multi-call binary dispatches based on argv[0], so copying it as "ls",
 *   "cat", etc. works identically to a symlink but survives Samsung seccomp.
 *
 * MEMORY: ZipInputStream is fully streaming — no ZIP-in-memory. Safe on 3 GB device.
 *
 * IMPORTANT: This only manages the Termux prefix (bash tab).
 * It does NOT touch ProotInstaller / Ubuntu tab in any way.
 */
object TermuxBootstrapInstaller {

    private const val TAG        = "TermuxBootstrap"
    private const val ASSET_NAME = "bootstrap-aarch64.zip"
    // Bump version so existing installs re-extract with the copy-instead-of-symlink fix
    private const val VERSION    = "termux-bootstrap-3490-v8"

    // Multi-call binaries: the target binary dispatches via argv[0].
    // Copying is safe and avoids symlinkat() seccomp block on Samsung.
    private val MULTI_CALL_BINARIES = mapOf(
        "coreutils" to listOf(
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln",
            "head", "tail", "wc", "chmod", "chown", "touch", "stat",
            "echo", "printf", "test", "true", "false", "pwd", "env",
            "sleep", "sort", "uniq", "cut", "tr", "tee", "dd",
            "mktemp", "dirname", "basename", "readlink", "realpath",
            "date", "id", "whoami", "uname", "du", "df", "sync",
            "shuf", "nproc", "nohup", "nice", "timeout", "kill",
            "stty", "tty"
        ),
        "gawk"  to listOf("awk"),
        "dash"  to listOf("sh"),
        "grep"  to listOf("egrep", "fgrep"),
        "gzip"  to listOf("gunzip", "zcat"),
        "bzip2" to listOf("bzip2recover"),
        "xz"    to listOf("xzdec")
    )

    fun prefixDir(context: Context): File = File(context.filesDir, "termux-prefix")

    fun bashPath(context: Context): String =
        File(prefixDir(context), "bin/bash").absolutePath

    fun isInstalled(context: Context): Boolean {
        val ver = File(context.filesDir, ".termux_bootstrap_version")
        return ver.exists() &&
               ver.readText().trim() == VERSION &&
               File(prefixDir(context), "bin/bash").exists()
    }

    /**
     * Extract bootstrap-aarch64.zip from assets into termux-prefix/.
     * Call on a background thread — never on main thread.
     */
    fun installIfNeeded(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) {
            Log.d(TAG, "Termux bootstrap already installed (v$VERSION)")
            ensureAptConf(context)
            return
        }

        val prefix = prefixDir(context)
        prefix.deleteRecursively()
        prefix.mkdirs()

        var filesWritten  = 0
        var symlinksDone  = 0
        var copiesDone    = 0
        var symlinkLines: List<String> = emptyList()

        try {
            onProgress("Extracting Termux bootstrap…")

            context.assets.open(ASSET_NAME).use { assetStream ->
                ZipInputStream(assetStream.buffered(64 * 1024)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "SYMLINKS.txt" -> {
                                symlinkLines = zip.readBytes()
                                    .toString(Charsets.UTF_8)
                                    .lines()
                                    .filter { it.contains('\u2190') }
                            }
                            entry.isDirectory -> {
                                File(prefix, name).mkdirs()
                            }
                            else -> {
                                val outFile = File(prefix, name)
                                outFile.parentFile?.mkdirs()
                                runCatching {
                                    outFile.outputStream().use { out ->
                                        val buf = ByteArray(8192)
                                        var n: Int
                                        while (zip.read(buf).also { n = it } != -1)
                                            out.write(buf, 0, n)
                                    }
                                    if (name.startsWith("bin/") ||
                                        name.startsWith("sbin/") ||
                                        name.startsWith("libexec/") ||
                                        name.endsWith(".so") ||
                                        name.contains(".so.")) {
                                        outFile.setExecutable(true, false)
                                    }
                                    outFile.setReadable(true, false)
                                    filesWritten++
                                    if (filesWritten % 200 == 0)
                                        onProgress("Extracting… $filesWritten files")
                                }.onFailure {
                                    Log.w(TAG, "Skipped $name: ${it.message}")
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // Build reverse lookup: aliasName -> sourceFile in bin/
            // e.g. "ls" -> File(prefix, "bin/coreutils")
            val copyMap = mutableMapOf<String, File>() // alias -> source File
            for ((srcName, aliases) in MULTI_CALL_BINARIES) {
                val srcFile = File(prefix, "bin/$srcName")
                if (srcFile.exists()) {
                    for (alias in aliases) {
                        copyMap[alias] = srcFile
                    }
                }
            }

            // Process SYMLINKS.txt — copy multi-call targets, symlink everything else
            onProgress("Wiring commands…")
            for (line in symlinkLines) {
                val parts = line.split('\u2190')
                if (parts.size != 2) continue
                val target  = parts[0].trim()          // e.g. "coreutils" or "../../lib/foo.so"
                val linkRel = parts[1].trim().removePrefix("./")
                val linkFile = File(prefix, linkRel)
                linkFile.parentFile?.mkdirs()

                // Determine if target is a known multi-call binary (just the basename)
                val _targetBase = target.trimStart('.', '/').substringAfterLast('/')
                val srcFile    = copyMap[File(linkRel).name] // alias name e.g. "ls"

                if (srcFile != null && srcFile.exists()) {
                    // COPY instead of symlink — survives Samsung seccomp
                    runCatching {
                        if (linkFile.exists()) linkFile.delete()
                        srcFile.copyTo(linkFile, overwrite = true)
                        linkFile.setExecutable(true, false)
                        linkFile.setReadable(true, false)
                        copiesDone++
                    }.onFailure {
                        Log.w(TAG, "Copy failed $linkRel from $srcFile: ${it.message}")
                    }
                } else {
                    // Regular symlink for library .so files and non-multi-call entries
                    runCatching {
                        val linkPath   = linkFile.toPath()
                        val targetPath = Paths.get(target)
                        if (Files.exists(linkPath) || Files.isSymbolicLink(linkPath))
                            Files.delete(linkPath)
                        Files.createSymbolicLink(linkPath, targetPath)
                        symlinksDone++
                    }.onFailure {
                        Log.w(TAG, "Symlink failed $linkRel → $target: ${it.message}")
                    }
                }
            }

            writeProfile(context, prefix)
            ensureAptConf(context)

            File(context.filesDir, ".termux_bootstrap_version").writeText(VERSION)
            onProgress("Termux bootstrap ready ✓ ($filesWritten files, $copiesDone copies, $symlinksDone symlinks)")
            Log.d(TAG, "Bootstrap installed: files=$filesWritten copies=$copiesDone symlinks=$symlinksDone prefix=$prefix")

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap install failed: ${e.message}", e)
            onProgress("Bootstrap failed: ${e.message}")
        }
    }

    /**
     * Returns launch args for the Termux bash session.
     * NO LD_PRELOAD — libtermux-exec hardcodes com.termux paths and
     * causes "/etc/profile: Permission denied" in our package.
     */
    fun shellArgs(context: Context): Pair<String, Array<String>> {
        val prefix = prefixDir(context).absolutePath
        val home   = File(context.filesDir, "home").apply { mkdirs() }.absolutePath
        val _nativeDir = context.applicationInfo.nativeLibraryDir

        val shell = "$prefix/bin/bash"
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$home",
            "PREFIX=$prefix",
            "PATH=$prefix/bin:$prefix/sbin:/system/bin",
            "TMPDIR=$prefix/tmp",
            "SHELL=$prefix/bin/bash",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            // CRITICAL: Do NOT set LD_LIBRARY_PATH at all.
            // $prefix/lib causes "unexpected e_version" on libandroid-support.so.
            // $nativeDir causes our app's AArch64 JNI .so files to be injected into
            // the bash process — wrong ABI context, triggers signal 31 (SIGSEGV cleanup).
            // Termux bootstrap binaries use rpath baked at build time; they find their
            // own libs without LD_LIBRARY_PATH. Omitting it entirely is the correct fix.
            "DPKG_FORCE=unsafe-io",
            "PERL_BADLANG=0",
            "TERMUX_VERSION=0.118.1",
            "TERMUX_APP_PACKAGE_MANAGER=apt"
        )
        return Pair(shell, env)
    }

    private fun writeProfile(context: Context, prefix: File) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val prefixPath = prefix.absolutePath

        // ── .bashrc ───────────────────────────────────────────────────────────
        File(home, ".bashrc").writeText(
            "# VN Code bash profile\n" +
            "export PREFIX=$prefixPath\n" +
            "export PATH=\$PREFIX/bin:\$PREFIX/sbin:/system/bin\n" +
            "export TMPDIR=\$PREFIX/tmp\n" +
            "export HOME=${home.absolutePath}\n" +
            "export TERM=xterm-256color\n" +
            "export LANG=en_US.UTF-8\n" +
            "export LC_ALL=en_US.UTF-8\n" +
            "export DPKG_FORCE=unsafe-io\n" +
            "export PERL_BADLANG=0\n" +
            "export TERMUX_VERSION=0.118.1\n" +
            "export TERMUX_APP_PACKAGE_MANAGER=apt\n\n" +
            "alias ll='ls -la'\n" +
            "alias la='ls -A'\n" +
            "alias gs='git status'\n" +
            "alias gp='git push'\n" +
            "alias gc='git commit'\n" +
            "alias gl='git log --oneline --graph --decorate --all -20'\n\n" +
            "PS1='\\u@vncode:\\w\\$ '\n" +
            "echo \"Termux bash ready — run: apt update && apt install <package>\"\n"
        )

        File(prefix, "tmp").mkdirs()

        // ── PATCH ALL SCRIPTS ─────────────────────────────────────────────────
        // The Termux bootstrap has 185+ shell scripts that all hardcode
        // /data/data/com.termux/files/usr  — replace with our actual prefix.
        // We do a recursive walk of bin/, lib/, etc/ to patch every text file.
        patchAllScripts(prefix, prefixPath)

        // ── etc/profile ───────────────────────────────────────────────────────
        // Overwrite entirely — the bootstrap version hardcodes com.termux paths
        File(prefix, "etc/profile").writeText(
            "# VN Code etc/profile\n" +
            "for i in \$PREFIX/etc/profile.d/*.sh; do\n" +
            "  [ -r \$i ] && . \$i\n" +
            "done\nunset i\n" +
            "[ -r \$PREFIX/etc/bash.bashrc ] && . \$PREFIX/etc/bash.bashrc\n" +
            "[ -r \$HOME/.bashrc ] && . \$HOME/.bashrc\n"
        )

        // ── etc/termux/bootstrap ──────────────────────────────────────────────
        // Overwrite second-stage script with our prefix
        val secondStage = File(prefix, "etc/termux/bootstrap/termux-bootstrap-second-stage.sh")
        if (secondStage.exists()) {
            val txt = secondStage.readText()
                .replace("#!/data/data/com.termux/files/usr/bin/bash", "#!${prefix.absolutePath}/bin/bash")
                .replace("/data/data/com.termux/files/usr", prefixPath)
                .replace("""export TERMUX_PREFIX="/data/data/com.termux/files/usr"""",
                         """export TERMUX_PREFIX="$prefixPath"""")
            secondStage.writeText(txt)
            secondStage.setExecutable(true, false)
        }

        // ── sources.list ──────────────────────────────────────────────────────
        // Ensure apt has the correct Termux repo — this is what was missing!
        val aptDir = File(prefix, "etc/apt")
        aptDir.mkdirs()
        File(aptDir, "sources.list").writeText(
            "# Termux main repository\n" +
            "deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n"
        )
    }

    /**
     * Walk all text files under the Termux prefix and replace the hardcoded
     * /data/data/com.termux/files/usr path with our actual prefix path.
     * This patches all 185 shell scripts in one pass.
     */
    private fun patchAllScripts(prefix: File, prefixPath: String) {
        val OLD_PREFIX = "/data/data/com.termux/files/usr"
        val OLD_HOME   = "/data/data/com.termux/files/home"
        val newHome    = File(prefix.parentFile?.parentFile ?: prefix, "home").absolutePath
        val oldPrefixBytes = OLD_PREFIX.toByteArray(Charsets.ISO_8859_1)

        prefix.walkTopDown()
            .filter { it.isFile && it.length() < 500_000L }  // skip large binaries
            .forEach { file ->
                try {
                    val bytes = file.readBytes()
                    // Quick check: scan bytes for the ASCII marker before decoding as UTF-8
                    var found = false
                    outer@ for (i in 0..(bytes.size - oldPrefixBytes.size)) {
                        for (j in oldPrefixBytes.indices) {
                            if (bytes[i + j] != oldPrefixBytes[j]) continue@outer
                        }
                        found = true; break
                    }
                    if (found) {
                        val text = bytes.toString(Charsets.UTF_8)
                        val patched = text
                            .replace(OLD_PREFIX, prefixPath)
                            .replace(OLD_HOME, newHome)
                        file.writeText(patched, Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    // Binary file or unreadable — skip silently
                }
            }
    }

    /**
     * Writes apt.conf disabling GPG verification and enabling unsafe-io for dpkg.
     * Samsung kernel 5.15 blocks gpgv fork() and dpkg atomic rename syscalls.
     * Called on fresh install AND on every app startup for existing installs.
     */
    fun ensureAptConf(context: Context) {
        val aptConfDir = File(prefixDir(context), "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()

        // Always overwrite — ensure latest config is present even after updates
        File(aptConfDir, "99-vncode-nogpg").writeText(
            "// VN Code -- Samsung kernel seccomp workarounds\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n" +
            // Disable sandboxing — Samsung blocks setresuid/prctl inside apt sandbox
            "APT::Sandbox::User \"root\";\n" +
            "APT::Sandbox::Seccomp \"false\";\n" +
            // Disable PTY allocation in dpkg (PTY alloc via ioctl is blocked)
            "Dpkg::Use-Pty \"0\";\n" +
            // Suppress pre-install script hooks (fork() inside hook scripts blocked)
            "DPkg::Pre-Install-Pkgs {};\n" +
            "DPkg::Post-Invoke {};\n"
        )

        // dpkg.cfg — force unsafe-io AND disable triggers (trigger fork is blocked)
        val dpkgCfgDir = File(prefixDir(context), "etc/dpkg")
        dpkgCfgDir.mkdirs()
        File(dpkgCfgDir, "dpkg.cfg").writeText(
            "# VN Code -- Samsung kernel seccomp workarounds\n" +
            "force-unsafe-io\n" +
            "no-triggers\n"
        )

        // Write a wrapper script for 'ls' that uses busybox if coreutils ls fails
        // Samsung may block certain syscalls in the Termux coreutils binary
        val binDir = File(prefixDir(context), "bin")
        // Ensure busybox aliases exist as fallback for Samsung-blocked coreutils calls
        // We write a small sh wrapper that tries the real ls first, falls back to busybox
        // Actually: the issue is coreutils calls __NR_statx which Samsung 5.15 blocks.
        // Use busybox as direct replacement for blocked coreutils tools.
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val busyboxPath = "$nativeDir/libbusybox.so"
        if (java.io.File(busyboxPath).exists()) {
            // Replace coreutils ls/cat/etc copies with busybox equivalents
            // Busybox uses older syscalls (getdents not getdents64/statx) — Samsung-safe
            listOf("ls","cat","cp","mv","rm","mkdir","rmdir","head","tail","wc",
                   "chmod","touch","echo","printf","pwd","env","sleep","sort",
                   "uniq","cut","tr","tee","dirname","basename","date","id",
                   "whoami","uname","du","df","stat","readlink","realpath").forEach { cmd ->
                val cmdFile = File(binDir, cmd)
                if (cmdFile.exists()) {
                    // Write a thin wrapper script: exec busybox cmd "$@"
                    // Write wrapper: exec busybox applet
                    cmdFile.writeText("#!/bin/sh\nexec \"$busyboxPath\" $cmd \"\$@\"\n")
                    cmdFile.setExecutable(true, false)
                }
            }
        }
    }
}
