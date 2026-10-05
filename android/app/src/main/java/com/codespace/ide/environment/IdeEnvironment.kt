package com.codespace.ide.environment

import android.content.Context
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.util.ProjectPathResolver
import com.codespace.ide.diagnostics.AppOutputLog
import java.io.File

/**
 * Central environment configuration — single source of truth for all proot-based
 * processes (terminal, LSP, DAP, future build tooling).
 *
 * Modeled after AndroidIDE's Environment.java pattern: one class defines HOME, PATH,
 * and project-specific vars. Every component reads from here — no component constructs
 * its own copy of these env vars independently.
 *
 * Gap 1 (central env class) + Gap 3 (bake WORKSPACE_PATH/PROJECT_FILES into env map):
 * Previously, WORKSPACE_PATH and PROJECT_FILES were injected post-hoc via
 * session.write("export WORKSPACE_PATH=...") after the terminal session started.
 * Now they are baked directly into the env map so they are available immediately
 * on process start. The post-hoc session.write() remains as a fallback only.
 *
 * Usage:
 *   val env = IdeEnvironment.forTerminal(context, projectId)
 *   // env.prootArgs  → Array<String>  (proot command-line args)
 *   // env.envVars    → Array<String>  (KEY=VALUE environment variables)
 *   // env.workspacePath → String?      (proot-translated project path, or null)
 *
 * For LSP/DAP (no interactive PTY, stdin/stdout are JSON-RPC pipes):
 *   val env = IdeEnvironment.forSubprocess(context, projectId, stripStdioBinds = true)
 */
object IdeEnvironment {

    private const val TAG = "IdeEnvironment"

    /**
     * Full environment for an interactive terminal session.
     *
     * @param context Android context
     * @param projectId Project ID for workspace path resolution
     * @param workDir Optional explicit working directory (overrides projectId resolution)
     * @return [ProotEnv] containing proot path, args, env vars, and resolved workspace path
     */
    fun forTerminal(
        context: Context,
        projectId: String = "default",
        workDir: String? = null,
        // D15-a: per-session KEY=VALUE entries injected into the guest env (passed via
        // "/usr/bin/env -i" args like WORKSPACE_PATH). Used for the shell-integration
        // nonce (CODESPACE_SHELL_NONCE). forSubprocess deliberately does NOT take this —
        // the nonce is terminal-session-only.
        extraEnv: List<String> = emptyList(),
    ): ProotEnv {
        val (proot, args, envVars) = ProotInstaller.launchArgs(context)
        val workspacePath = resolveWorkspacePath(context, projectId, workDir)

        // DIAGNOSTIC: Log every step of workspace path resolution to identify
        // exactly where the chain breaks when WORKSPACE_PATH ends up empty.
        AppOutputLog.log("forTerminal DIAG: projectId=$projectId workDir=$workDir", "terminal")
        AppOutputLog.log("forTerminal DIAG: workspacePath resolved=$workspacePath", "terminal")
        AppOutputLog.log("forTerminal DIAG: enrichedArgs will ${if (workspacePath != null) "INSERT" else "SKIP"} env vars into proot args", "terminal")
        AppOutputLog.log("forTerminal DIAG: enrichedEnvVars will ${if (workspacePath != null) "ADD" else "SKIP"} WORKSPACE_PATH/PROJECT_FILES", "terminal")

        val enrichedEnv = enrichEnvVars(envVars, workspacePath)
        val enrichedArgs = enrichArgs(args, workspacePath, extraEnv)

        // DIAGNOSTIC: Verify the env vars actually made it into the args
        if (workspacePath != null) {
            val hasWsPath = enrichedArgs.any { it.startsWith("WORKSPACE_PATH=") }
            val hasProjFiles = enrichedArgs.any { it.startsWith("PROJECT_FILES=") }
            AppOutputLog.log("forTerminal DIAG: args contain WORKSPACE_PATH=$hasWsPath PROJECT_FILES=$hasProjFiles", "terminal")
        }

        return ProotEnv(
            proot = proot,
            args = enrichedArgs,
            envVars = enrichedEnv,
            workspacePath = workspacePath,
        )
    }

    /**
     * Environment for a non-interactive subprocess (LSP, DAP, build tooling).
     * Strips stdin/stdout/stderr bind mounts to prevent noise from corrupting
     * JSON-RPC pipes. Also strips the fd bind since it's not needed for pipes.
     *
     * @param context Android context
     * @param projectId Project ID for workspace path resolution
     * @param workDir Optional explicit working directory
     * @return [ProotEnv] with stdio binds stripped
     */
    fun forSubprocess(
        context: Context,
        projectId: String = "default",
        workDir: String? = null,
    ): ProotEnv {
        val (proot, args, envVars) = ProotInstaller.launchArgs(context)
        val workspacePath = resolveWorkspacePath(context, projectId, workDir)

        val filteredArgs = args.filter {
            it != "--bind=/proc/self/fd/0:/dev/stdin" &&
            it != "--bind=/proc/self/fd/1:/dev/stdout" &&
            it != "--bind=/proc/self/fd/2:/dev/stderr"
        }.toTypedArray()

        val enrichedEnv = enrichEnvVars(envVars, workspacePath)
        val enrichedArgs = enrichArgs(filteredArgs, workspacePath, emptyList())

        return ProotEnv(
            proot = proot,
            args = enrichedArgs,
            envVars = enrichedEnv,
            workspacePath = workspacePath,
        )
    }

    /**
     * Resolve the project workspace path and translate it to the proot-internal path.
     * /storage/emulated/0 → /sdcard (bind-mounted in proot)
     * /sdcard → /sdcard
     * /root → /root
     * Other paths → null (not accessible inside proot)
     */
    private fun resolveWorkspacePath(
        context: Context,
        projectId: String,
        workDir: String? = null,
    ): String? {
        val rawPath = workDir ?: ProjectPathResolver.resolveProjectRoot(context, projectId)
        // V0-e (2026-10-05): the dialect branch table lives in the resolver core now
        // (resolver/PathDialects.workspaceToGuest — /root passthrough, hostToGuest
        // for everything else, null = unreachable = fail closed). This was the LAST
        // private copy of the host->guest workspace dialect; one source of truth.
        AppOutputLog.log("resolveWorkspacePath (V0 core): projectId=$projectId workDir=$workDir rawPath=$rawPath", "terminal")
        val result = rawPath?.let { com.codespace.ide.resolver.PathResolver.workspaceToGuest(context, it) }
        AppOutputLog.log("resolveWorkspacePath (V0 core): final result=$result", "terminal")
        return result
    }

    /**
     * Bake WORKSPACE_PATH and PROJECT_FILES directly into the env vars array.
     * These are passed via "/usr/bin/env -i" in the proot args, so they become
     * part of the process's initial environment — available immediately, no
     * post-hoc session.write() needed.
     */
    private fun enrichEnvVars(envVars: Array<String>, workspacePath: String?): Array<String> {
        if (workspacePath == null) return envVars
        val workspaceEnv = arrayOf(
            "WORKSPACE_PATH=$workspacePath",
            "PROJECT_FILES=$workspacePath",
        )
        return envVars + workspaceEnv
    }

    /**
     * Inject WORKSPACE_PATH, PROJECT_FILES (when a workspace is open) and any
     * extraEnv entries into the proot "/usr/bin/env -i" argument list, right
     * before the final "/bin/bash" command. This is how they become real
     * environment variables for the guest process. D15-a: extraEnv (e.g. the
     * per-session shell-integration nonce) is injected even WITHOUT a workspace.
     */
    private fun enrichArgs(args: Array<String>, workspacePath: String?, extraEnv: List<String>): Array<String> {
        val envEntries = buildList {
            if (workspacePath != null) {
                add("WORKSPACE_PATH=$workspacePath")
                add("PROJECT_FILES=$workspacePath")
            }
            addAll(extraEnv)
        }
        if (envEntries.isEmpty()) return args

        // Find the "/bin/bash" entry near the end and insert env vars before it
        val bashIndex = args.indexOfLast { it == "/bin/bash" }
        if (bashIndex < 0) return args

        val result = args.toMutableList()
        result.addAll(bashIndex, envEntries.toList())
        return result.toTypedArray()
    }

    /**
     * Build the fallback session.write() commands for WORKSPACE_PATH injection.
     * This is kept as a belt-and-suspenders fallback for sessions where the
     * env-arg injection might not have taken effect (e.g. fallback sessions).
     * Only call if the primary env-arg injection is NOT used.
     */
    fun workspacePathFallbackCommands(workspacePath: String?): List<String> {
        if (workspacePath == null) return emptyList()
        return listOf(
            "export WORKSPACE_PATH=\"$workspacePath\"\n",
            "export PROJECT_FILES=\"$workspacePath\"\n",
            "cd \"$workspacePath\" 2>/dev/null && clear || echo \"[LOCK-DIAG] cd to '$workspacePath' failed - not reachable inside Ubuntu, keeping current cwd\"\n",
            // D15-a: append, never clobber — 99-shell-integration.sh installs a
            // PROMPT_COMMAND wrapper that re-evaluates whatever it captured; a bare
            // assignment here destroyed it. With integration active the value composes
            // ("__csi_prompt_cmd_original; history -a"); without it, it stays exactly
            // "history -a" as before.
            "export PROMPT_COMMAND=\"\${PROMPT_COMMAND:+\$PROMPT_COMMAND;}history -a\"\n",
            "export HISTFILE=~/.bash_history\n",
            "export HISTSIZE=500\n",
            "export HISTFILESIZE=500\n",
        )
    }

    /**
     * Parse a KEY=VALUE env var string into a pair for ProcessBuilder.environment().
     */
    fun parseEnvVar(kv: String): Pair<String, String>? {
        val idx = kv.indexOf('=')
        if (idx <= 0) return null
        return kv.substring(0, idx) to kv.substring(idx + 1)
    }

    /**
     * Apply env vars to a ProcessBuilder's environment map.
     */
    fun applyToProcessBuilder(pb: ProcessBuilder, envVars: Array<String>) {
        val envMap = pb.environment()
        envVars.forEach { kv ->
            parseEnvVar(kv)?.let { (key, value) -> envMap[key] = value }
        }
    }
}

/**
 * Resolved proot environment for a session or subprocess.
 *
 * @property proot Path to the libproot.so binary
 * @property args Full proot command-line arguments (including env entries via /usr/bin/env -i)
 * @property envVars Host-side environment variables (PROOT_LOADER, TMPDIR, etc.)
 * @property workspacePath Proot-internal workspace path (e.g. /sdcard/MyProject), or null
 */
data class ProotEnv(
    val proot: String,
    val args: Array<String>,
    val envVars: Array<String>,
    val workspacePath: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProotEnv) return false
        return proot == other.proot &&
            args.contentEquals(other.args) &&
            envVars.contentEquals(other.envVars) &&
            workspacePath == other.workspacePath
    }

    override fun hashCode(): Int {
        var result = proot.hashCode()
        result = 31 * result + args.contentHashCode()
        result = 31 * result + envVars.contentHashCode()
        result = 31 * result + (workspacePath?.hashCode() ?: 0)
        return result
    }
}
