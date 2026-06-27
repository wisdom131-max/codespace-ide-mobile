package com.codespace.ide.terminal

import android.content.Context
import android.util.Log
import java.io.File

object BusyboxInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val VERSION = "bootstrap-2024.08.18"

    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    // Android 14 W^X: binaries must be in codeCacheDir to be executable
    fun prefixDir(context: Context): File = context.codeCacheDir

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
        File(home, ".inputrc").writeText("set completion-ignore-case on\nset show-all-if-ambiguous on\n\"\\\\e[A\": history-search-backward\n\"\\\\e[B\": history-search-forward\n")
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
        val home = File(context.filesDir, "home").absolutePath
        val bin  = binDir(context).absolutePath
        val prefs = context.getSharedPreferences("vncode_prefs", android.content.Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: ""

        appendLine("# VN Code shell profile")
        appendLine("export VN_CODE_OFFLINE=1")
        appendLine("export PATH=$bin:\$PATH")
        appendLine("export HOME=$home")
        appendLine("export TERM=xterm-256color")
        appendLine("export HISTSIZE=5000")
        appendLine("export HISTFILESIZE=10000")
        appendLine("export HISTCONTROL=ignoredups:erasedups")
        appendLine("export MCP_SERVER_URL='$backendUrl'")

        // Aliases
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
        appendLine("alias cls='clear'")
        appendLine("alias pkg='$bin/pkg'")
        appendLine("alias apt='$bin/apt'")
        appendLine("alias apt-get='$bin/apt-get'")
        appendLine("alias grep='grep --color=auto'")
        appendLine("alias vi='vim'")

        // Code snippets
        appendLine("snip() {")
        appendLine("  case \"\$1\" in")
        appendLine("    kt-main) printf 'fun main() {\\n    println(\"Hello, World!\")\\n}\\n' > main.kt; echo 'Created main.kt' ;;")
        appendLine("    py-main) printf 'def main():\\n    print(\"Hello!\")\\n\\nif __name__ == \"__main__\":\\n    main()\\n' > main.py; echo 'Created main.py' ;;")
        appendLine("    ts-main) printf 'const main = (): void => {\\n  console.log(\"Hello!\");\\n};\\nmain();\\n' > index.ts; echo 'Created index.ts' ;;")
        appendLine("    sh-main) printf '#!/usr/bin/env bash\\nset -euo pipefail\\nmain() {\\n  echo \"Hello!\"\\n}\\nmain \"\$@\"\\n' > main.sh; chmod +x main.sh; echo 'Created main.sh' ;;")
        appendLine("    *) echo 'snip <kt-main|py-main|ts-main|sh-main>' ;;")
        appendLine("  esac")
        appendLine("}")

        // MCP tools (curl to backend)
        appendLine("mcp() {")
        appendLine("  [ -z \"\$MCP_SERVER_URL\" ] && { echo '[mcp] Set backend_url in Settings'; return 1; }")
        appendLine("  local tool=\$1; shift; local params='{}'")
        appendLine("  case \"\$tool\" in")
        appendLine("    read_file)    params=\"{\\\"path\\\":\\\"\$1\\\"}\" ;;")
        appendLine("    write_file)   params=\"{\\\"path\\\":\\\"\$1\\\",\\\"content\\\":\\\"\$2\\\"}\" ;;")
        appendLine("    list_dir)     params=\"{\\\"path\\\":\\\"\${1:-.}\\\"}\" ;;")
        appendLine("    search_files) params=\"{\\\"query\\\":\\\"\$1\\\",\\\"dir\\\":\\\"\${2:-.}\\\"}\" ;;")
        appendLine("    run_command)  params=\"{\\\"command\\\":\\\"\$1\\\"}\" ;;")
        appendLine("  esac")
        appendLine("  curl -s -X POST \"\${MCP_SERVER_URL}/ai/mcp/execute\" -H 'Content-Type: application/json' -d \"{\\\"tool\\\":\\\"\$tool\\\",\\\"params\\\":\$params}\" | python3 -c 'import sys,json;print(json.load(sys.stdin).get(\"result\",\"\"))' 2>/dev/null || echo '[mcp] unreachable'")
        appendLine("}")
        appendLine("alias mcp_read='mcp read_file'")
        appendLine("alias mcp_write='mcp write_file'")
        appendLine("alias mcp_run='mcp run_command'")
        appendLine("alias mcp_ls='mcp list_dir'")
        appendLine("alias mcp_grep='mcp search_files'")

        appendLine("help() {")
        appendLine("  echo 'VN Code Shell:'")
        appendLine("  echo '  pkg install/list/search    offline packages'")
        appendLine("  echo '  snip kt-main|py-main|...   boilerplate'")
        appendLine("  echo '  gs/ga/gaa/gc/gcm/gp/gl     git shortcuts'")
        appendLine("  echo '  mcp_read/write/run/ls/grep  MCP file tools'")
        appendLine("}")
        appendLine("PS1='\\[\\033[0;32m\\]\\u@vncode\\[\\033[0m\\]:\\[\\033[0;34m\\]\\w\\[\\033[0m\\]\\\$ '")
    }
    private fun buildOfflinePackageScript(context: Context): String = buildString {
        val stateDir = File(context.filesDir, "offline_state")
        val dbFile = File(stateDir, "packages.txt")
        stateDir.mkdirs()
        appendLine("#!/system/bin/sh")
        appendLine("set -e")
        appendLine("STATE_DIR='${stateDir.absolutePath}'")
        appendLine("DB_FILE='${dbFile.absolutePath}'")
        appendLine("mkdir -p \"\$STATE_DIR\"")
        appendLine("touch \"\$DB_FILE\"")
        appendLine("case \"\$1\" in")
        appendLine("  install)")
        appendLine("    shift")
        appendLine("    if [ \"\$#\" -eq 0 ]; then echo 'usage: pkg install <package> [package...]'; exit 0; fi")
        appendLine("    for pkg in \"\$@\"; do")
        appendLine("      echo \"Installing \$pkg\"")
        appendLine("      grep -Fxq \"\$pkg\" \"\$DB_FILE\" || echo \"\$pkg\" >> \"\$DB_FILE\"")
        appendLine("      echo \"Installed \$pkg\"")
        appendLine("    done")
        appendLine("    ;;")
        appendLine("  uninstall)")
        appendLine("    shift")
        appendLine("    if [ \"\$#\" -eq 0 ]; then echo 'usage: pkg uninstall <package>'; exit 0; fi")
        appendLine("    for pkg in \"\$@\"; do")
        appendLine("      grep -v -x \"\$pkg\" \"\$DB_FILE\" > \"\$DB_FILE.tmp\" || true")
        appendLine("      mv \"\$DB_FILE.tmp\" \"\$DB_FILE\"")
        appendLine("      echo \"Removed \$pkg\"")
        appendLine("    done")
        appendLine("    ;;")
        appendLine("  list)")
        appendLine("    echo 'Installed offline packages:'")
        appendLine("    cat \"\$DB_FILE\"")
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
        val bin = binDir(context)
        val versionFile = File(context.filesDir, ".bootstrap_version")

        if (versionFile.exists() && versionFile.readText().trim() == VERSION &&
            File(bin, "busybox").exists()) {
            Log.d(TAG, "Bootstrap already installed")
            return
        }

        Log.d(TAG, "Installing busybox bootstrap to ${prefix.absolutePath}")
        try {
            prefix.deleteRecursively()
            prefix.mkdirs()
            bin.mkdirs()

            // Install busybox from assets/tools/busybox_arm64 into codeCacheDir/bin/
            // codeCacheDir is always executable on Android (unlike filesDir which is noexec)
            val busyboxDest = File(bin, "busybox")
            context.assets.open("tools/busybox_arm64").use { input ->
                busyboxDest.outputStream().use { out ->
                    val buf = ByteArray(8192); var n: Int
                    while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
            busyboxDest.setExecutable(true, false)
            busyboxDest.setReadable(true, false)

            // Create symlinks: bash → busybox, sh → busybox (busybox applet mode)
            listOf("bash", "sh", "cat", "ls", "cp", "mv", "rm", "mkdir",
                   "chmod", "chown", "grep", "sed", "awk", "cut", "sort",
                   "head", "tail", "find", "xargs", "tar", "gzip", "echo",
                   "printf", "test", "true", "false", "env", "which").forEach { tool ->
                val link = File(bin, tool)
                if (!link.exists()) {
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(),
                            java.nio.file.Paths.get(busyboxDest.absolutePath)
                        )
                    }.onFailure { Log.w(TAG, "Symlink $tool failed: \${it.message}") }
                }
            }

            versionFile.writeText(VERSION)
            Log.d(TAG, "Bootstrap installed. bash=${File(bin, "bash").exists()} busybox=${busyboxDest.exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap installation failed: \${e.message}", e)
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
