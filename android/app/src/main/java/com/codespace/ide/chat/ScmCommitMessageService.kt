package com.codespace.ide.chat

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * I3 — SCM AI (2026-09-13, VS Code Copilot commit-message analog): one-shot
 * commit-message generation for the SourceControlPane, routed through the SAME
 * provider stack as the chat panel — ChatModelSelection for the model,
 * ChatProviderRegistry for the provider, ChatKeyFailover for multi-key
 * resilience. No UI, no chat session: a direct complete() with a focused
 * system prompt (Conventional Commits, summary line under 72 chars).
 */
object ScmCommitMessageService {

    private const val SYSTEM_PROMPT =
        "You write concise, high-quality git commit messages following Conventional Commits " +
            "(feat/fix/chore/docs/refactor/test). Reply with ONLY the commit message: a single " +
            "summary line under 72 characters, optionally followed by a blank line and a short " +
            "body when the changes genuinely need one. No markdown, no code fences, no explanation."
    private const val MAX_DIFF_CHARS = 8000

    /**
     * Generates a commit message from the repo's current staged + unstaged state.
     * Throws with a user-readable message when no provider is configured.
     */
    suspend fun generate(context: Context, tokenStore: SecureTokenStore, repoPath: String): String =
        withContext(Dispatchers.IO) {
            val modelStr = ChatModelSelection.resolveAuto(
                context, ChatModelSelection.get(context) ?: "auto", tokenStore
            )
            val colonIdx = modelStr.indexOf(':')
            if (colonIdx <= 0) throw IllegalStateException(
                "No AI provider configured \u2014 add an API key in Settings first"
            )
            val providerId = modelStr.substring(0, colonIdx)
            val apiModel = modelStr.substring(colonIdx + 1)
            val provider = ChatProviderRegistry.byId(providerId)
                ?: throw IllegalStateException("Unknown AI provider: $providerId")
            if (!provider.isAvailable(tokenStore)) throw IllegalStateException(provider.unavailableMessage())

            val diffBlock = buildDiffBlock(context, repoPath)
            if (diffBlock.isBlank()) throw IllegalStateException(
                "No changes to describe \u2014 stage or edit something first"
            )

            val convMsgs = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Write a commit message for these git changes in the repository.\n\n" + diffBlock
                )
            )
            val raw = ChatKeyFailover.execute(providerId, tokenStore, hasStarted = { false }, onInfo = { }) { key ->
                provider.complete(ChatRequest(apiModel, SYSTEM_PROMPT, convMsgs, key))
            }
            clean(raw)
        }

    private fun buildDiffBlock(context: Context, repoPath: String): String {
        val sb = StringBuilder()
        fun run(vararg args: String) =
            when (val r = com.codespace.ide.scm.GitCommandExecutor.run(context, args.toList(), workdir = repoPath)) {
                is com.codespace.ide.scm.GitResult.Ok -> r.output
                is com.codespace.ide.scm.GitResult.Err -> ""
            }
        val status = run("status", "--porcelain")
        if (status.isNotBlank()) sb.append("git status --porcelain:\n").append(status).append("\n\n")
        val staged = run("diff", "--cached")
        if (staged.isNotBlank()) sb.append("git diff --cached:\n").append(staged).append("\n\n")
        val unstaged = run("diff")
        if (unstaged.isNotBlank()) sb.append("git diff:\n").append(unstaged)
        return sb.toString().take(MAX_DIFF_CHARS)
    }

    private fun clean(raw: String): String {
        var t = raw.trim()
        // Strip a whole-message code fence if the model added one anyway.
        if (t.startsWith("```")) t = t.removePrefix("```").removeSuffix("```").trim()
        if (t.lines().size == 1 && (t.startsWith("`") && t.endsWith("`"))) t = t.trim('`')
        return t.trim()
    }
}
