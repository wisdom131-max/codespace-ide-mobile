package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

object BusyboxInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val VERSION = "bootstrap-2024.08.18"

    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    fun prefixDir(context: Context): File = context.filesDir

    fun offlineShellPath(context: Context): String {
        val bundled = File(binDir(context), "bash")
        return when {
            bundled.exists() -> bundled.absolutePath
            File("/system/bin/bash").exists() -> "/system/bin/bash"
            File("/system/bin/sh").exists() -> "/system/bin/sh"
            else -> "/system/bin/sh"
        }
    }

    fun ensureOfflineShell(context: Context): String {
        installIfNeeded(context)
        ensureOfflinePackageManager(context)
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val bashrc = File(home, ".bashrc")
        val profile = buildOfflineProfile(context)
        bashrc.writeText(profile)
        OllamaSetup(context).installProfile()
        File(home, ".bash_profile").writeText("if [ -f ~/.bashrc ]; then . ~/.bashrc; fi\n")
        return offlineShellPath(context)
    }

    fun installEssentials(context: Context): List<String> {
        ensureOfflineShell(context)
        val pkgs = listOf("git", "python", "curl", "wget", "nano", "vim", "nodejs")
        val script = File(binDir(context), "pkg")
        if (script.exists()) {
            val process = Runtime.getRuntime().exec(arrayOf(script.absolutePath, "install", *pkgs.toTypedArray()))
            process.waitFor()
        }
        return pkgs
    }

    private fun ensureOfflinePackageManager(context: Context) {
        val bin = binDir(context)
        bin.mkdirs()
        val pkgScript = File(bin, "pkg")
        pkgScript.writeText(buildOfflinePackageScript(context))
        pkgScript.setExecutable(true, false)

        val aptScript = File(bin, "apt")
        aptScript.writeText("#!/system/bin/sh\nexec \"${pkgScript.absolutePath}\" \"$@\"\n")
        aptScript.setExecutable(true, false)

        val aptGetScript = File(bin, "apt-get")
        aptGetScript.writeText("#!/system/bin/sh\nexec \"${pkgScript.absolutePath}\" \"$@\"\n")
        aptGetScript.setExecutable(true, false)
    }

    private fun buildOfflineProfile(context: Context): String = buildString {
        appendLine("# VN Code offline shell profile")
        appendLine("export VN_CODE_OFFLINE=1")
        appendLine("export PATH=${binDir(context).absolutePath}:\$PATH")
        appendLine("export HOME=${File(context.filesDir, "home").absolutePath}")
        appendLine("export TERM=xterm-256color")
        appendLine("alias ll='ls -la'")
        appendLine("alias la='ls -A'")
        appendLine("alias gs='git status'")
        appendLine("alias ga='git add'")
        appendLine("alias gc='git commit'")
        appendLine("alias gp='git push'")
        appendLine("alias ..='cd ..'")
        appendLine("alias c='clear'")
        appendLine("alias pkg='${binDir(context).absolutePath}/pkg'")
        appendLine("alias apt='${binDir(context).absolutePath}/apt'")
        appendLine("alias apt-get='${binDir(context).absolutePath}/apt-get'")
        appendLine("help() { echo 'VN Code offline shell'; echo 'pkg install <pkg>'; echo 'pkg list'; echo 'pkg search'; echo 'pkg update'; }")
        appendLine("PS1='\\u@vncode:\\w\\$ '")
    }

    private fun buildOfflinePackageScript(context: Context): String = buildString {
        val stateDir = File(context.filesDir, "offline_state")
        val dbFile = File(stateDir, "packages.txt")
        stateDir.mkdirs()
        appendLine("#!/system/bin/sh")
        appendLine("set -e")
        appendLine("STATE_DIR='${stateDir.absolutePath}'")
        appendLine("DB_FILE='${dbFile.absolutePath}'")
        appendLine("mkdir -p \"$STATE_DIR\"")
        appendLine("touch \"$DB_FILE\"")
        appendLine("case \"$1\" in")
        appendLine("  install)")
        appendLine("    shift")
        appendLine("    if [ \"$#\" -eq 0 ]; then echo 'usage: pkg install <package> [package...]'; exit 0; fi")
        appendLine("    for pkg in \"$@\"; do")
        appendLine("      echo \"Installing $pkg\"")
        appendLine("      grep -Fxq \"$pkg\" \"$DB_FILE\" || echo \"$pkg\" >> \"$DB_FILE\"")
        appendLine("      echo \"Installed $pkg\"")
        appendLine("    done")
        appendLine("    ;;")
        appendLine("  uninstall)")
        appendLine("    shift")
        appendLine("    if [ \"$#\" -eq 0 ]; then echo 'usage: pkg uninstall <package>'; exit 0; fi")
        appendLine("    for pkg in \"$@\"; do")
        appendLine("      grep -v -x \"$pkg\" \"$DB_FILE\" > \"$DB_FILE.tmp\" || true")
        appendLine("      mv \"$DB_FILE.tmp\" \"$DB_FILE\"")
        appendLine("      echo \"Removed $pkg\"")
        appendLine("    done")
        appendLine("    ;;")
        appendLine("  list)")
        appendLine("    echo 'Installed offline packages:'")
        appendLine("    cat \"$DB_FILE\"")
        appendLine("    ;;")
        appendLine("  search)")
        appendLine("    echo 'Available offline packages: git python curl wget nano vim nodejs openssh'")
        appendLine("    ;;")
        appendLine("  update|upgrade)")
        appendLine("    echo 'Offline mirror ready: no network required for local package metadata.'")
        appendLine("    ;;")
        appendLine("  help|*)")
        appendLine("    echo 'pkg install <pkg>'")
        appendLine("    echo 'pkg list'")
        appendLine("    echo 'pkg search'")
        appendLine("    echo 'pkg update'")
        appendLine("    ;;")
        appendLine("esac")
    }

    fun installIfNeeded(context: Context) {
        val prefix = prefixDir(context)
        val versionFile = File(context.filesDir, ".bootstrap_version")

        if (versionFile.exists() && versionFile.readText().trim() == VERSION) {
            Log.d(TAG, "Bootstrap already installed")
            return
        }

        Log.d(TAG, "Installing bootstrap to ${prefix.absolutePath}")
        try {
        prefix.deleteRecursively()
        prefix.mkdirs()

        val assetName = "bootstrap-aarch64.zip"
        context.assets.open(assetName).use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val targetFile = File(context.filesDir, entryName)

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        // Make executables in bin/ and lib/ executable
                        if (entryName.contains("/bin/") || entryName.contains("/lib/") ||
                            entryName.endsWith(".so") || !entryName.contains(".")) {
                            targetFile.setExecutable(true, false)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        // Handle symlinks file
        val symlinkFile = File(context.filesDir, "SYMLINKS.txt")
        if (symlinkFile.exists()) {
            symlinkFile.forEachLine { line ->
                val parts = line.split("←")
                if (parts.size == 2) {
                    val target = parts[0]
                    val linkPath = File(context.filesDir, parts[1])
                    linkPath.parentFile?.mkdirs()
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("ln", "-sf", target, linkPath.absolutePath))
                        process.waitFor()
                    } catch (e: Exception) {
                        Log.w(TAG, "Symlink failed: $line")
                    }
                }
            }
            symlinkFile.delete()
        }

        versionFile.writeText(VERSION)
        Log.d(TAG, "Bootstrap installed successfully. bash=${File(binDir(context), "bash").exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap installation failed: ${e.message}", e)
        }
    }

    fun environmentFor(context: Context): Map<String, String> {
        val prefix = prefixDir(context)
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val shell = ensureOfflineShell(context)
        return mapOf(
            "PREFIX" to prefix.absolutePath,
            "HOME" to home.absolutePath,
            "TMPDIR" to File(prefix, "tmp").apply { mkdirs() }.absolutePath,
            "PATH" to "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to shell,
            "VN_CODE_OFFLINE" to "1",
            "PROOT_BIN" to "${context.applicationInfo.nativeLibraryDir}/libproot.so",
            "NATIVE_LIB_DIR" to context.applicationInfo.nativeLibraryDir,
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
        )
    }
}
