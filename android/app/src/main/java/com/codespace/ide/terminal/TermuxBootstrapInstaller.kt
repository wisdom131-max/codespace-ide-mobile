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
 * MEMORY: ZipInputStream is fully streaming — no ZIP-in-memory. Safe on 3 GB device.
 *
 * IMPORTANT: This only manages the Termux prefix (bash tab).
 * It does NOT touch ProotInstaller / Ubuntu tab in any way.
 */
object TermuxBootstrapInstaller {

    private const val TAG = "TermuxBootstrap"
    private const val ASSET_NAME = "bootstrap-aarch64.zip"
    private const val VERSION    = "termux-bootstrap-3490-v1"

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
     *
     * @param onProgress callback for UI progress strings
     */
    fun installIfNeeded(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) {
            Log.d(TAG, "Termux bootstrap already installed")
            return
        }

        val prefix = prefixDir(context)
        prefix.deleteRecursively()
        prefix.mkdirs()

        var filesWritten = 0
        var symlinksDone = 0
        var symlinkLines: List<String> = emptyList()

        try {
            onProgress("Extracting Termux bootstrap…")

            context.assets.open(ASSET_NAME).use { assetStream ->
                ZipInputStream(assetStream.buffered(64 * 1024)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name

                        when {
                            // Defer symlinks — targets may not exist yet
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
                                    // Mark executables: bin/, sbin/, lib*.so, libexec/
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

            // Now create symlinks — all targets should exist by now
            onProgress("Creating symlinks…")
            for (line in symlinkLines) {
                val parts = line.split('\u2190')   // ← separator
                if (parts.size != 2) continue
                val target   = parts[0].trim()
                // strip leading ./ from link path
                val linkRel  = parts[1].trim().removePrefix("./")
                val linkFile = File(prefix, linkRel)
                linkFile.parentFile?.mkdirs()
                runCatching {
                    val linkPath   = linkFile.toPath()
                    val targetPath = Paths.get(target)   // keep as-is (relative symlink)
                    if (Files.exists(linkPath) || Files.isSymbolicLink(linkPath))
                        Files.delete(linkPath)
                    Files.createSymbolicLink(linkPath, targetPath)
                    symlinksDone++
                }.onFailure {
                    Log.w(TAG, "Symlink failed $linkRel → $target: ${it.message}")
                }
            }

            // Write environment profile for bash
            writeProfile(context, prefix)

            File(context.filesDir, ".termux_bootstrap_version").writeText(VERSION)
            onProgress("Termux bootstrap ready ✓ ($filesWritten files, $symlinksDone symlinks)")
            Log.d(TAG, "Bootstrap installed: files=$filesWritten symlinks=$symlinksDone prefix=$prefix")

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap install failed: ${e.message}", e)
            onProgress("Bootstrap failed: ${e.message}")
        }
    }

    /**
     * Returns proot-style launch args for a bare Termux prefix bash session
     * (no proot — runs directly in the Android process namespace via NativePty).
     *
     * Shell: termux-prefix/bin/bash
     * Environment mirrors what Termux sets for its bash tab.
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
            "LD_LIBRARY_PATH=$prefix/lib:$nativeDir"
            // NOTE: NO LD_PRELOAD here — libtermux-exec.so is only for the Ubuntu/proot tab.
            // Setting it on the bash tab causes "/etc/profile: Permission denied" because
            // exec() interceptor tries to access /data/data/com.termux paths we don't own.
        )
        return Pair(shell, env)
    }

    private fun writeProfile(context: Context, prefix: File) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val prefixPath = prefix.absolutePath

        File(home, ".bashrc").writeText("""
# VN Code — Termux bash profile (auto-generated)
export PREFIX=$prefixPath
export PATH=${'$'}PREFIX/bin:${'$'}PREFIX/sbin:/system/bin
export TMPDIR=${'$'}PREFIX/tmp
export HOME=${home.absolutePath}
export TERM=xterm-256color
export LANG=en_US.UTF-8

alias ll='ls -la'
alias la='ls -A'
alias gs='git status'
alias gp='git push'
alias gc='git commit'
alias gl='git log --oneline --graph --decorate --all -20'

PS1='\u@vncode:\w\$ '
echo "VN Code bash ready — \$(bash --version | head -1)"
""".trimIndent())

        // tmp dir must exist
        File(prefix, "tmp").mkdirs()

        // apt.conf — disable GPG signature verification.
        // Samsung kernel 5.15 blocks fork() inside proot ptrace, so gpgv (a subprocess
        // apt forks to verify signatures) always exits with "Bad system call".
        // This makes `apt update` and `apt install` work despite the kernel restriction.
        val aptConf = File(prefix, "etc/apt/apt.conf.d/99-vncode-nogpg")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("""
// VN Code — disable GPG check (Samsung kernel blocks gpgv subprocess)
APT::Get::AllowUnauthenticated "true";
Acquire::AllowInsecureRepositories "true";
Acquire::AllowDowngradeToInsecureRepositories "true";
APT::Sandbox::User "root";
""".trimIndent())
    }
}
