package com.codespace.ide.agent

import java.io.File

/**
 * GIT TOOLS AND RECOVERY (2026-11-12).
 *
 * FOUR TOOLS, ZERO SILENT LOSS. The rules that shape this file:
 *
 *  1. `git_checkpoint` never moves the working tree. It calls `git stash create`, which writes a
 *     commit object holding the tracked changes WITHOUT stashing them, then points a
 *     `refs/vncode/checkpoint/<stamp>` ref at it. The user's files are exactly as they were, and the
 *     checkpoint is immutable and addressable by name. Untracked files are NOT in that object, so
 *     they are listed in the answer instead of being quietly claimed.
 *  2. `git_restore` is two-phase. Without `confirm` it only reports what would change; with
 *     `confirm` it checks a checkpoint ref (or a commit) out onto the given paths. There is no
 *     `reset --hard` anywhere in this file, and nothing runs without an explicit target.
 *  3. `git_conflict_resolve` never picks a side. It lists the conflicted paths, stages a path the
 *     user says is resolved, or aborts the merge. Choosing "ours" or "theirs" is the owner's call.
 *  4. Every command is executed through [GitExtraTools.Runner] — the same seam the stash and tag
 *     tools already use — so the argument vectors are asserted by tests instead of trusted.
 */
object GitRecoveryTools {

    private const val MAX_LOG = 40
    private const val MAX_FILES = 60

    /** A checkpoint ref namespace of our own: discoverable, and never a branch. */
    private const val CHECKPOINT_NS = "refs/vncode/checkpoint/"

    // -------------------------------------------------------------------- log

    fun log(repo: File, run: GitExtraTools.Runner, limit: Int = 20, path: String? = null): String {
        if (!isRepo(repo, run)) return noRepo(repo)
        val n = limit.coerceIn(1, MAX_LOG)
        val args = mutableListOf("log", "-n", n.toString(), "--date=short",
            "--pretty=format:%h|%ad|%an|%s")
        if (!path.isNullOrBlank()) args.addAll(listOf("--", path))
        val out = run.run(args)
        return renderLog(out, n, path)
    }

    /** Pure: the same bounded rendering the tool returns, so the bounds are testable. */
    fun renderLog(raw: String, limit: Int, path: String?): String {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) {
            return "No commits matched" + if (path.isNullOrBlank()) "." else " for path '$path'."
        }
        val sb = StringBuilder("Commits (" + lines.size + if (lines.size >= limit) ", newest first, capped at $limit" else "" + "):\n")
        for (l in lines) {
            val parts = l.split('|', limit = 4)
            if (parts.size < 4) {
                sb.append("  ").append(l.take(160)).append('\n')
                continue
            }
            sb.append("  ").append(parts[0]).append("  ").append(parts[1]).append("  ")
                .append(parts[2].take(24)).append("  ").append(parts[3].take(120)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // --------------------------------------------------------------- checkpoint

    fun checkpoint(repo: File, run: GitExtraTools.Runner, message: String, stamp: String): String {
        if (!isRepo(repo, run)) return noRepo(repo)
        val label = message.trim().take(80).ifBlank { "checkpoint" }
        // stash create: writes the commit object, leaves the working tree untouched.
        val createOut = run.run(listOf("stash", "create", label)).trim()
        if (createOut.isEmpty()) {
            val dirty = run.run(listOf("status", "--porcelain")).trim()
            return if (dirty.isEmpty())
                "Nothing to checkpoint: the working tree has no tracked changes."
            else
                "Nothing to checkpoint: the only changes are untracked files, which a checkpoint " +
                    "cannot hold. Untracked: " + dirty.lineSequence().take(MAX_FILES)
                        .joinToString(", ") { it.trim().removePrefix("?? ").take(60) }
        }
        val ref = CHECKPOINT_NS + stamp
        val update = run.run(listOf("update-ref", ref, createOut)).trim()
        val untracked = run.run(listOf("status", "--porcelain"))
            .lineSequence().map { it.trim() }.filter { it.startsWith("??") }
            .map { it.removePrefix("?? ").take(60) }.take(MAX_FILES).toList()
        val sb = StringBuilder()
        sb.append("Checkpoint created: ").append(createOut.take(12)).append('\n')
            .append("Ref: ").append(ref).append('\n')
            .append("Label: ").append(label).append('\n')
            .append("The working tree was NOT modified: your tracked changes are still in place.\n")
        if (untracked.isEmpty()) {
            sb.append("Untracked files: none.")
        } else {
            sb.append("Untracked files are NOT in this checkpoint (git cannot stash-create them): ")
                .append(untracked.joinToString(", "))
        }
        if (update.isNotEmpty() && !update.contains("refs/vncode")) {
            sb.append("\nNote from update-ref: ").append(update.take(200))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ restore

    /**
     * Two-phase restore. [confirm] false = preview only, no mutation.
     *
     * The preview is a diffstat against the checkpoint, so it names the files and the line counts
     * that a confirm would change.
     */
    fun restore(
        repo: File,
        run: GitExtraTools.Runner,
        ref: String,
        files: List<String>,
        confirm: Boolean,
    ): String {
        if (!isRepo(repo, run)) return noRepo(repo)
        val target = ref.trim()
        if (target.isEmpty()) {
            return "restore needs ref= (a checkpoint ref from git_checkpoint, or a commit hash). " +
                "List them with: git for-each-ref refs/vncode/checkpoint"
        }
        if (files.isEmpty()) {
            return "restore needs files=[path...]: only the paths you name are touched, so nothing " +
                "can be reverted by accident."
        }
        val scope = listOf("--") + files
        val preview = run.run(listOf("diff", "--stat", target) + scope).trim()
        if (preview.isEmpty()) {
            return "Nothing to restore: " + files.joinToString(", ") + " already match " + target.take(12) + "."
        }
        if (!confirm) {
            return "PREVIEW ONLY - nothing has changed yet.\nRestoring from " + target.take(16) +
                " would change:\n" + preview.take(3000) +
                "\nCall again with confirm=true to apply exactly these paths."
        }
        val apply = run.run(listOf("checkout", target) + scope).trim()
        val sb = StringBuilder("Restored ").append(files.joinToString(", ")).append(" from ")
            .append(target.take(16)).append(".\n").append(preview.take(2000))
        if (apply.isNotEmpty()) sb.append("\nGit: ").append(apply.take(600))
        sb.append("\nOther files were not touched. `git status` shows anything still outstanding.")
        return sb.toString()
    }

    // ---------------------------------------------------------------- conflicts

    fun conflicts(repo: File, run: GitExtraTools.Runner, action: String, filePath: String?): String {
        if (!isRepo(repo, run)) return noRepo(repo)
        val unmerged = run.run(listOf("diff", "--name-only", "--diff-filter=U")).trim()
        when (action.lowercase()) {
            "list" -> return renderConflicts(unmerged)
            "stage" -> {
                if (filePath.isNullOrBlank()) return "stage needs file= (the path you resolved by hand)."
                val listed = unmerged.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                if (listed.none { it == filePath }) {
                    return "'" + filePath.take(120) + "' is not conflicted, so there is nothing to " +
                        "stage. Conflicted paths:\n" + renderConflicts(unmerged)
                }
                run.run(listOf("add", "--", filePath))
                val after = run.run(listOf("diff", "--name-only", "--diff-filter=U")).trim()
                return "Staged " + filePath.take(120) + " as resolved.\n" + renderConflicts(after)
            }
            "abort" -> {
                val mode = run.run(listOf("status", "--porcelain")).trim()
                if (mode.isEmpty() && unmerged.isEmpty()) return "No merge is in progress."
                run.run(listOf("merge", "--abort"))
                return "Merge aborted. Your pre-merge state was restored by git; run git_status to confirm."
            }
            else -> return "action must be list, stage or abort."
        }
    }

    fun renderConflicts(raw: String): String {
        val files = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_FILES).toList()
        if (files.isEmpty()) return "No merge conflicts. The tree is clean of unmerged paths."
        val sb = StringBuilder("Conflicted paths (" + files.size + "):\n")
        for (f in files) sb.append("  ").append(f).append('\n')
        sb.append("Resolve each file by editing it (keep the side that is right), then call " +
            "git_conflict_resolve with action=stage and file=<path>. Nothing is chosen for you.")
        return sb.toString()
    }

    // -------------------------------------------------------------------- shared

    private fun isRepo(repo: File, run: GitExtraTools.Runner): Boolean =
        runCatching { run.run(listOf("rev-parse", "--is-inside-work-tree")).trim() == "true" }
            .getOrDefault(false)

    private fun noRepo(repo: File): String =
        "Not a git repository: " + repo.path.take(160) + ". Open the project root first."
}
