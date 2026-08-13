package com.codespace.ide.scm

import android.content.Context
import com.codespace.ide.terminal.ProotInstaller

/**
 * GitCommandExecutor — central git command runner.
 *
 * Wraps ProotInstaller.execOnce() with:
 *  - safe.directory='*' (avoids "dubious ownership" on all repos)
 *  - structured GitResult output (no more raw string parsing)
 *  - auth header injection for push/pull/fetch via GitHub token
 *  - error classification (NotARepo, NoUpstream, AuthFailed, etc.)
 *
 * ALL git commands in the app should go through this executor.
 * Replaces the scattered execOnce("git ...") calls in ExplorerPane,
 * ProjectShellScreen, EditorPane, AgentTools, TimelinePanel.
 *
 * Phase SCM-3: GitCommandExecutor. No UI, no state management.
 */
object GitCommandExecutor {

    /**
     * Run a git command in the proot environment and return a structured result.
     *
     * @param context Android context (for proot launch args)
     * @param args git subcommand args, e.g. listOf("status", "--porcelain")
     * @param workdir guest-side path (e.g. "/root/myproject"), null for /root
     * @param timeoutSeconds max execution time
     * @param token GitHub OAuth token for authenticated commands (push/pull/fetch)
     * @return GitResult.Ok with stdout, or GitResult.Err with classified error
     */
    fun run(
        context: Context,
        args: List<String>,
        workdir: String? = null,
        timeoutSeconds: Long = 60,
        token: String? = null,
    ): GitResult {
        // Always prepend safe.directory='*' — prevents "dubious ownership" on proot repos
        val safeDir = "-c"
        val safeArg = "safe.directory=*"

        // Build auth header for remote operations if token is provided
        val authArgs = if (token != null && needsAuth(args)) {
            val basic = android.util.Base64.encodeToString(
                "x-access-token:$token".toByteArray(),
                android.util.Base64.NO_WRAP
            )
            listOf(
                "-c",
                "http.extraheader=Authorization: Basic $basic"
            )
        } else {
            emptyList()
        }

        // Quote each argument safely
        val quotedArgs = (listOf(safeDir, safeArg) + authArgs + args).joinToString(" ") { a ->
            "'" + a.replace("'", "'\\''") + "'"
        }
        val command = "git $quotedArgs"

        val raw = ProotInstaller.execOnce(
            context = context,
            command = command,
            workdir = workdir,
            timeoutSeconds = timeoutSeconds,
            logToOutput = false,
        )

        return classify(raw, args, workdir)
    }

    /**
     * Run a git command and return raw stdout string (for callers that still
     * expect plain String — backward compat during migration).
     */
    fun runRaw(
        context: Context,
        command: String,
        workdir: String? = null,
        timeoutSeconds: Long = 60,
    ): String {
        val fullCommand = "git -c safe.directory='*' $command"
        return ProotInstaller.execOnce(
            context = context,
            command = fullCommand,
            workdir = workdir,
            timeoutSeconds = timeoutSeconds,
            logToOutput = false,
        )
    }

    // ── Error classification ──────────────────────────────────────────────

    private fun classify(raw: String, args: List<String>, workdir: String?): GitResult {
        // execOnce returns these patterns on failure:
        //   "Exit code N\n<stderr>"
        //   "Error running command in Ubuntu rootfs: <msg>"
        //   "Timed out after Ns running: <cmd>"
        // On success: trimmed stdout or "(command completed, no output)" / "(done)"

        if (raw.startsWith("Timed out")) {
            return GitResult.Err(GitError.Timeout(args.joinToString(" ")))
        }

        if (raw.startsWith("Error running command")) {
            return GitResult.Err(GitError.Unknown(raw))
        }

        if (raw.startsWith("Exit code ")) {
            val exitCode = raw.substringAfter("Exit code ")
                .substringBefore("\n")
                .toIntOrNull() ?: -1
            val stderr = raw.substringAfter("Exit code $exitCode\n", "")

            // Classify by exit code + stderr content
            return when {
                stderr.contains("not a git repository") || stderr.contains("not a git dir") ->
                    GitResult.Err(GitError.NotARepo(workdir ?: "(unknown)"))

                stderr.contains("no upstream") || stderr.contains("No remote") ||
                    stderr.contains("set-upstream") ->
                    GitResult.Err(GitError.NoUpstream(extractBranch(stderr)))

                stderr.contains("Authentication failed") || stderr.contains("could not read Username") ||
                    stderr.contains("403") || stderr.contains("401") ->
                    GitResult.Err(GitError.AuthFailed(stderr.take(200)))

                stderr.contains("CONFLICT") || stderr.contains("merge conflict") ->
                    GitResult.Err(GitError.MergeConflict(extractConflictedFiles(stderr)))

                stderr.contains("Could not resolve host") || stderr.contains("Connection refused") ||
                    stderr.contains("Network is unreachable") ->
                    GitResult.Err(GitError.NetworkFailed(stderr.take(200)))

                stderr.contains("Another git process seems to be running") ||
                    stderr.contains("Unable to create") && stderr.contains(".lock") ->
                    GitResult.Err(GitError.LockFailed(stderr.take(200)))

                else -> GitResult.Err(GitError.ExecFailed(exitCode, stderr))
            }
        }

        // Success — strip the "(command completed, no output)" placeholder
        val output = when (raw.trim()) {
            "(command completed, no output)", "(done)" -> ""
            else -> raw.trim()
        }
        return GitResult.Ok(output)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Whether a git subcommand needs authentication (remote network operations).
     */
    private fun needsAuth(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        return when (args[0]) {
            "push", "pull", "fetch", "clone", "ls-remote" -> true
            else -> false
        }
    }

    private fun extractBranch(stderr: String): String {
        // Try to find branch name from error like:
        // "There is no tracking information for the current branch main."
        val regex = Regex("""branch\s+(\S+)\s""")
        return regex.find(stderr)?.groupValues?.getOrNull(1) ?: "(unknown)"
    }

    private fun extractConflictedFiles(stderr: String): List<String> {
        // Parse "CONFLICT (content): Merge conflict in <path>"
        val regex = Regex("""Merge conflict in (\S+)""")
        return regex.findAll(stderr).map { it.groupValues[1] }.toList()
    }
}
