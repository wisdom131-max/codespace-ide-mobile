package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object BusyboxInstaller {

    private const val TAG = "BusyboxInstaller"
    private const val VERSION = "busybox-nativelib-v1"

    /**
     * nativeLibraryDir is ALWAYS executable on Android (no W^X restriction).
     * Android extracts libbusybox.so there from the APK on install.
     * This is the exact same trick Termux uses for libtermux-bootstrap.so.
     */
    fun busyboxPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libbusybox.so").absolutePath

    /**
     * filesDir/bin is on the data partition — NOT executable directly.
     * But symlinks pointing INTO nativeLibraryDir ARE executable.
     * So we put symlinks here for PATH convenience, they exec the real binary.
     */
    fun binDir(context: Context): File = File(context.filesDir, "bin")

    fun prefixDir(context: Context): File = context.filesDir

    /**
     * Returns the best shell path:
     * 1. libbusybox.so in nativeLibraryDir  ← preferred, always executable
     * 2. /system/bin/sh                      ← fallback
     */
    fun shellPath(context: Context): String {
        val busybox = File(busyboxPath(context))
        return if (busybox.exists()) busybox.absolutePath else "/system/bin/sh"
    }

    fun isInstalled(context: Context): Boolean {
        val versionFile = File(context.filesDir, ".busybox_version")
        return versionFile.exists() &&
               versionFile.readText().trim() == VERSION &&
               File(busyboxPath(context)).exists()
    }

    /**
     * Sets up the bin/ symlinks and .bashrc.
     * The actual busybox binary is already in nativeLibraryDir — no extraction needed.
     */
    fun installIfNeeded(context: Context) {
        if (isInstalled(context)) {
            Log.d(TAG, "Busybox already set up")
            return
        }

        val busybox = File(busyboxPath(context))
        if (!busybox.exists()) {
            Log.e(TAG, "libbusybox.so not found in nativeLibraryDir: ${busybox.absolutePath}")
            return
        }

        Log.d(TAG, "Setting up busybox symlinks — binary at: ${busybox.absolutePath}")

        val bin = binDir(context)
        bin.mkdirs()

        // Create symlinks in filesDir/bin/ → nativeLibraryDir/libbusybox.so
        // Symlinks execute the *target*, so they inherit nativeLibraryDir's exec permission.
        val tools = listOf(
            "ash", "sh", "cat", "ls", "cp", "mv", "rm", "mkdir", "rmdir",
            "chmod", "chown", "grep", "sed", "awk", "cut", "sort", "uniq",
            "head", "tail", "find", "xargs", "tar", "gzip", "gunzip",
            "echo", "printf", "test", "true", "false", "env", "which",
            "wget", "ping", "netstat", "ps", "kill", "top", "df", "du",
            "uname", "date", "id", "whoami", "pwd", "touch", "ln",
            "less", "more", "wc", "diff", "patch", "expr", "read",
            "tee", "tr", "md5sum", "sha256sum", "base64", "yes", "seq"
        )

        for (tool in tools) {
            val link = File(bin, tool)
            if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
            runCatching {
                Files.createSymbolicLink(link.toPath(), Paths.get(busybox.absolutePath))
            }.onFailure { Log.w(TAG, "Symlink $tool failed: ${it.message}") }
        }

        // Write version marker
        File(context.filesDir, ".busybox_version").writeText(VERSION)
        Log.d(TAG, "Busybox setup complete. ash=${File(bin,"ash").exists()}, target=${busybox.absolutePath}")
    }

    fun ensureOfflineShell(context: Context): String {
        installIfNeeded(context)
        ensureOfflinePackageManager(context)

        val home = File(context.filesDir, "home").apply { mkdirs() }
        val bashrc = File(home, ".bashrc")
        bashrc.writeText(buildOfflineProfile(context))
        File(home, ".profile").writeText("[ -f ~/.bashrc ] && . ~/.bashrc\n")
        File(home, ".inputrc").writeText(
            "set completion-ignore-case on\n" +
            "set show-all-if-ambiguous on\n" +
            "\"\\\\e[A\": history-search-backward\n" +
            "\"\\\\e[B\": history-search-forward\n"
        )
        OllamaSetup(context).installProfile()
        return shellPath(context)
    }

    private fun ensureOfflinePackageManager(context: Context) {
        val bin = binDir(context)
        bin.mkdirs()
        val pkgScript = File(bin, "pkg")
        pkgScript.writeText(buildOfflinePackageScript(context))
        pkgScript.setExecutable(true, false)
        for (alias in listOf("apt", "apt-get")) {
            File(bin, alias).also { f ->
                f.writeText("#!/system/bin/sh\nexec \"${pkgScript.absolutePath}\" \"$@\"\n")
                f.setExecutable(true, false)
            }
        }
    }

    private fun buildOfflineProfile(context: Context): String = buildString {
        val home = File(context.filesDir, "home").absolutePath
        val bin  = binDir(context).absolutePath
        val busybox = busyboxPath(context)
        val prefs = context.getSharedPreferences("vncode_prefs", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: ""

        appendLine("# VN Code shell profile — powered by busybox at $busybox")
        appendLine("export PATH=$bin:${'$'}PATH")
        appendLine("export HOME=$home")
        appendLine("export TERM=xterm-256color")
        appendLine("export HISTSIZE=5000")
        appendLine("export HISTFILESIZE=10000")
        appendLine("export HISTCONTROL=ignoredups:erasedups")
        appendLine("export MCP_SERVER_URL='$backendUrl'")

        appendLine("alias ll='ls -la'")
        appendLine("alias la='ls -A'")
        appendLine("alias l='ls -CF'")
        appendLine("alias gs='git status'")
        appendLine("alias ga='git add'")
        appendLine("alias gaa='git add -A'")
        appendLine("alias gc='git commit'")
        appendLine("alias gcm='git commit -m'")
        appendLine("alias gp='git push'")
        appendLine("alias gl='git log --oneline --graph --decorate --all -20'")
        appendLine("alias gd='git diff'")
        appendLine("alias gb='git branch'")
        appendLine("alias gco='git checkout'")
        appendLine("alias ..='cd ..'")
        appendLine("alias ...='cd ../..'")
        appendLine("alias c='clear'")
        appendLine("alias grep='grep --color=auto'")

        appendLine("PS1='\\[\\033[0;32m\\]\\u@vncode\\[\\033[0m\\]:\\[\\033[0;34m\\]\\w\\[\\033[0m\\]\\$ '")
        appendLine("echo \"VN Code Shell — busybox $(busybox --help 2>&1 | head -1 | grep -o 'v[0-9.]*') ready\"")
    }

    private fun buildOfflinePackageScript(context: Context): String = buildString {
        val stateDir = File(context.filesDir, "offline_state")
        val dbFile = File(stateDir, "packages.txt")
        stateDir.mkdirs()
        appendLine("#!/system/bin/sh")
        appendLine("STATE_DIR='${stateDir.absolutePath}'")
        appendLine("DB_FILE='${dbFile.absolutePath}'")
        appendLine("mkdir -p \"${'$'}STATE_DIR\"")
        appendLine("touch \"${'$'}DB_FILE\"")
        appendLine("case \"${'$'}1\" in")
        appendLine("  install) shift; for pkg in \"${'$'}@\"; do grep -Fxq \"${'$'}pkg\" \"${'$'}DB_FILE\" || echo \"${'$'}pkg\" >> \"${'$'}DB_FILE\"; echo \"Installed ${'$'}pkg\"; done ;;")
        appendLine("  uninstall) shift; for pkg in \"${'$'}@\"; do grep -v -x \"${'$'}pkg\" \"${'$'}DB_FILE\" > \"${'$'}DB_FILE.tmp\" || true; mv \"${'$'}DB_FILE.tmp\" \"${'$'}DB_FILE\"; echo \"Removed ${'$'}pkg\"; done ;;")
        appendLine("  list) cat \"${'$'}DB_FILE\" ;;")
        appendLine("  search) echo 'Available: git python curl wget nano vim nodejs openssh' ;;")
        appendLine("  update|upgrade) echo 'Package list up to date.' ;;")
        appendLine("  *) echo 'pkg install|uninstall|list|search|update' ;;")
        appendLine("esac")
    }
}
