package com.codespace.ide.terminal

import android.content.Context
import com.codespace.ide.diagnostics.AppOutputLog
import java.io.File

/**
 * IdeTerminalBridge - terminal to editor bridge.
 *
 * Part A of the terminal file-linking feature (2026-09-05):
 *
 *  A1: OSC 7777 handler wiring. The vendored termux TerminalEmulator (see
 *      com.termux.terminal.TerminalEmulator.doOscIdeOpen) consumes the
 *      Acode-compatible sequence "ESC]7777;open;TYPE;PATH[;LINE]BEL"
 *      and calls TerminalSession.onOscIdeOpen on the reader thread. This
 *      class attaches the listener that hops to the main thread, translates
 *      the proot-guest path to a host path, and routes it to the app's
 *      EXISTING open-file-at-line editor entry point.
 *
 *      Protocol verified from Acode-Foundation/Acode source:
 *      src/components/terminal/terminal.js (setupOscHandler - registerOscHandler(7777)),
 *      src/components/terminal/terminalManager.js (onOscOpen -> openFile),
 *      src/plugins/terminal/scripts/init-alpine.sh (acode CLI printf).
 *      Acode's format is "open;type;path"; the ";line" 4th field is our
 *      documented extension (accepted ; ambiguity, same as Acode's own).
 *
 *  A2: `ide` CLI helper installed into the proot rootfs at /usr/local/bin/ide
 *      (Acode installs /usr/local/bin/acode the same way). Usage in the shell:
 *          ide src/Main.kt:42     -> opens file at line 42
 *          ide notes.txt          -> opens file
 *          ide .                  -> emits folder type (folder handling is Phase 2)
 *
 *  A3: plain-text tap detection helper - resolves a tapped "path.kt:42"-style
 *      token (build-error output is NOT wrapped in OSC 7777) against the
 *      session's cwd and the proot guest-path map.
 */
object IdeTerminalBridge {

    private const val CHANNEL = "terminal"

    // ── A1: OSC 7777 listener wiring ─────────────────────────────────────────

    /**
     * Attaches the OSC 7777 open handler to a LOCAL (proot) terminal session.
     * Remote/SSH sessions must NOT get this - a remote host must not be able
     * to trigger local file opens (Acode enforces the same rule).
     *
     * The [openFileAtLine] lambda follows the app's existing shell-level
     * convention: 0-BASED line (ProjectShellScreen adds +1 before scrolling;
     * see its onOpenFileAtLine lambda). A negative value means "no line".
     */
    fun attachOscIdeOpen(
        context: Context,
        session: com.termux.terminal.TerminalSession,
        openFileAtLine: (path: String, line: Int) -> Unit,
    ) {
        val appCtx = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        session.setOscIdeOpenListener { type, path, line ->
            main.post {
                try {
                    if (type != "file") {
                        AppOutputLog.log("OSC 7777: ignored non-file open request (type=" + type + " path=" + path + ")", CHANNEL)
                        return@post
                    }
                    val host = guestPathToHostFile(appCtx, path)
                    if (host == null) {
                        AppOutputLog.log("OSC 7777: path not found on host (guest=" + path + ")", CHANNEL)
                        return@post
                    }
                    // OSC line numbers are 1-based human numbers ("Main.kt:42");
                    // the shell lambda expects 0-based. Absent (-1) = no line.
                    val zeroBased = line - 1
                    AppOutputLog.log("OSC 7777 open: " + host.absolutePath + " line=" + line, CHANNEL)
                    openFileAtLine(host.absolutePath, zeroBased)
                } catch (e: Exception) {
                    AppOutputLog.log("OSC 7777 handler error: " + e.message, CHANNEL)
                }
            }
        }
    }

    // ── path translation ─────────────────────────────────────────────────────

    /**
     * Translates a proot-guest path (what the shell sees) to a real Android
     * host path, or null if the target does not actually exist on the host.
     *
     * Guest to host rules (reverse of IdeEnvironment.resolveWorkspacePath):
     *   /sdcard/...            -> /storage/emulated/0/...
     *   /storage/emulated/0/.. -> already host-style, kept as-is
     *   anything else          -> rootfs-relative (e.g. /root/... -> filesDir/ubuntu-rootfs/root/...)
     */
    fun guestPathToHostFile(context: Context, guestPath: String): File? {
        val trimmed = guestPath.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("/sdcard/") || trimmed == "/sdcard" -> {
                val hostPath = trimmed.replaceFirst("/sdcard", "/storage/emulated/0")
                File(hostPath).takeIf { it.exists() }
            }
            trimmed.startsWith("/storage/emulated/") -> File(trimmed).takeIf { it.exists() }
            else -> {
                // rootfs-relative (e.g. /root/x, /etc/x). ProotInstaller.guestToHostPath
                // maps "/x" to rootfsDir/x on the host.
                val f = ProotInstaller.guestToHostPath(context, trimmed)
                f.takeIf { it.exists() }
            }
        }
    }

    /**
     * A3: resolve a plain-text "path:line" token tapped in the terminal
     * (build-error style output - NOT OSC-wrapped) to a host file + 0-based
     * line, or null if it cannot be resolved.
     *
     * Resolution order:
     *   1. token as absolute host path (exists)
     *   2. token via guest-to-host translation (/sdcard/..., /root/...)
     *   3. token relative to the session shell's real cwd (TerminalSession.getCwd,
     *      which reads /proc/PID/cwd - a HOST path) - first as-is, then guest-translated
     */
    fun resolveTappedFileLink(
        context: Context,
        session: com.termux.terminal.TerminalSession?,
        token: String,
    ): Pair<File, Int>? {
        if (token.isBlank()) return null
        val clean = token.trim().trimEnd(':', ',', ';')
        // Split optional :line suffix
        var pathPart = clean
        var lineNum = -1
        val lastColon = clean.lastIndexOf(':')
        if (lastColon > 0) {
            val candidatePath = clean.substring(0, lastColon)
            val candidateLine = clean.substring(lastColon + 1)
            if (candidateLine.isNotEmpty() && candidateLine.all { it.isDigit() }) {
                pathPart = candidatePath
                lineNum = candidateLine.toInt()
            }
        }
        val appCtx = context.applicationContext
        // 1. absolute host path
        File(pathPart).takeIf { it.isAbsolute && it.exists() }?.let { return Pair(it, lineNum - 1) }
        // 2. guest -> host translation
        guestPathToHostFile(appCtx, pathPart)?.let { return Pair(it, lineNum - 1) }
        // 3. relative to the shell's real cwd
        val cwd = try { session?.cwd } catch (_: Exception) { null }
        if (cwd != null) {
            File(cwd, pathPart).takeIf { it.exists() }?.let { return Pair(it, lineNum - 1) }
        }
        return null
    }

    // ── A2: `ide` CLI installer ─────────────────────────────────────────────

    /**
     * Installs /usr/local/bin/ide into the proot rootfs (Acode installs its
     * `acode` helper at /usr/local/bin/acode in init-alpine.sh the same way).
     * Idempotent - overwrites with the current version on every call.
     */
    fun installIdeCli(context: Context) {
        try {
            val rootfs = ProotInstaller.rootfsDir(context)
            val binDir = File(rootfs, "usr/local/bin")
            binDir.mkdirs()
            val script = File(binDir, "ide")
            script.writeText(buildIdeCliScript())
            script.setReadable(true, false)
            script.setWritable(true, false)
            script.setExecutable(true, false)
        } catch (e: Exception) {
            AppOutputLog.log("ide CLI install failed: " + e.message, CHANNEL)
        }
    }

    /** POSIX-sh script. Lines joined with \n - never embed raw newlines in Kotlin literals. */
    private fun buildIdeCliScript(): String = listOf(
        "#!/bin/sh",
        "# ide - open a file or folder in the Codespace IDE editor from the terminal.",
        "# Sends OSC 7777 (Acode-compatible file-open protocol):",
        "#   ESC]7777;open;TYPE;PATH[;LINE]BEL",
        "# The IDE terminal consumes the sequence and opens the file in the editor.",
        "# Usage: ide <path>[:line]   |   ide .   (opens current folder)",
        "if [ \"\$#\" -eq 0 ]; then",
        "    set -- \".\"",
        "fi",
        "target=\"\$1\"",
        "line=\"\"",
        "maybe=\$(printf '%s' \"\$target\" | cut -d: -f1)",
        "suffix=\$(printf '%s' \"\$target\" | cut -d: -f2)",
        "if [ -n \"\$suffix\" ] && [ -e \"\$maybe\" ]; then",
        "    target=\"\$maybe\"",
        "    line=\"\$suffix\"",
        "fi",
        "abs=\$(realpath -- \"\$target\" 2>/dev/null)",
        "if [ -z \"\$abs\" ] || [ ! -e \"\$abs\" ]; then",
        "    if [ -e \"\$target\" ]; then",
        "        d=\$(dirname -- \"\$target\")",
        "        f=\$(basename -- \"\$target\")",
        "        abs=\$(cd -- \"\$d\" 2>/dev/null && pwd -P)/\$f",
        "    fi",
        "fi",
        "if [ -z \"\$abs\" ] || [ ! -e \"\$abs\" ]; then",
        "    echo \"ide: '\$1' does not exist\" >&2",
        "    exit 1",
        "fi",
        "type=\"file\"",
        "[ -d \"\$abs\" ] && type=\"folder\"",
        "if [ -n \"\$line\" ]; then",
        "    printf '\\033]7777;open;%s;%s;%s\\a' \"\$type\" \"\$abs\" \"\$line\"",
        "else",
        "    printf '\\033]7777;open;%s;%s\\a' \"\$type\" \"\$abs\"",
        "fi",
        "",
    ).joinToString("\n")
}
