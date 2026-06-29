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
    private const val VERSION    = "termux-bootstrap-3490-v2"

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
                val targetBase = target.trimStart('.', '/').substringAfterLast('/')
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
        val nativeDir = context.applicationInfo.nativeLibraryDir

        val shell = "$prefix/bin/bash"
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$home",
            "PREFIX=$prefix",
            "PATH=$prefix/bin:$prefix/sbin:/system/bin",
            "TMPDIR=$prefix/tmp",
            "SHELL=$prefix/bin/bash",
            "LANG=en_US.UTF-8",
            "LD_LIBRARY_PATH=$prefix/lib:$nativeDir",
            // DPKG_FORCE_UNSAFE_IO: bypass Samsung kernel's blocked linkat/renameat2
            "DPKG_FORCE=unsafe-io",
            // Suppress perl locale warnings from dpkg postinst scripts
            "PERL_BADLANG=0",
            "LC_ALL=en_US.UTF-8"
        )
        return Pair(shell, env)
    }

    private fun writeProfile(context: Context, prefix: File) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val prefixPath = prefix.absolutePath

        File(home, ".bashrc").writeText(
            "# VN Code — Termux bash profile (auto-generated)\n" +
            "export PREFIX=$prefixPath\n" +
            "export PATH=\$PREFIX/bin:\$PREFIX/sbin:/system/bin\n" +
            "export TMPDIR=\$PREFIX/tmp\n" +
            "export HOME=${home.absolutePath}\n" +
            "export TERM=xterm-256color\n" +
            "export LANG=en_US.UTF-8\n" +
            "export DPKG_FORCE=unsafe-io\n" +
            "export PERL_BADLANG=0\n\n" +
            "alias ll='ls -la'\n" +
            "alias la='ls -A'\n" +
            "alias gs='git status'\n" +
            "alias gp='git push'\n" +
            "alias gc='git commit'\n" +
            "alias gl='git log --oneline --graph --decorate --all -20'\n\n" +
            "PS1='\\u@vncode:\\w\\$ '\n" +
            "echo \"VN Code bash ready\"\n"
        )

        File(prefix, "tmp").mkdirs()
    }

    /**
     * Writes apt.conf disabling GPG verification and enabling unsafe-io for dpkg.
     * Samsung kernel 5.15 blocks gpgv fork() and dpkg atomic rename syscalls.
     * Called on fresh install AND on every app startup for existing installs.
     */
    fun ensureAptConf(context: Context) {
        val aptConfDir = File(prefixDir(context), "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()

        val nogpg = File(aptConfDir, "99-vncode-nogpg")
        if (!nogpg.exists()) {
            nogpg.writeText(
                "// VN Code -- disable GPG check (Samsung kernel blocks gpgv subprocess)\n" +
                "APT::Get::AllowUnauthenticated \"true\";\n" +
                "Acquire::AllowInsecureRepositories \"true\";\n" +
                "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n" +
                "APT::Sandbox::User \"root\";\n"
            )
        }

        // Also write dpkg.cfg to force unsafe-io permanently
        val dpkgCfgDir = File(prefixDir(context), "etc/dpkg")
        dpkgCfgDir.mkdirs()
        val dpkgCfg = File(dpkgCfgDir, "dpkg.cfg")
        if (!dpkgCfg.exists()) {
            dpkgCfg.writeText(
                "# VN Code -- OEM kernel workaround\n" +
                "force-unsafe-io\n"
            )
        }
    }
}
