package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome

/**
 * Area 5 - CAN WE PROVE WHAT ALREADY HAPPENED?
 *
 * Reconciliation exists only where a read-only inspection of the real world gives a yes/no answer.
 * Today that is one narrowly scoped case: a file write whose target's current content can be read
 * and compared with what the call asked for. Everything else keeps the honest refusal, because a
 * generic "assume it worked" is how a side effect gets applied twice.
 *
 * The reconciler NEVER re-runs the original operation to find out; it only reads.
 */
fun interface ToolReconciler {

    /** Non-null means "proven already applied", and the value is the result to reuse. */
    fun reconcile(tool: String, prior: ToolCallLedger.Entry): String?

    companion object {
        /** Default: nothing is reconcilable. Refusal stands. */
        val None = ToolReconciler { _, _ -> null }

        /**
         * The one provable class: file writes and edits, checked by reading the target back.
         *
         * [readFile] is injectable so the rule can be tested without a filesystem, and so the
         * caller decides which paths it is willing to read.
         */
        fun fileWrites(readFile: (String) -> String?): ToolReconciler = ToolReconciler { tool, prior ->
            if (tool !in WRITE_TOOLS) return@ToolReconciler null
            if (prior.outcome != ToolOutcome.UNKNOWN_AFTER_START) return@ToolReconciler null
            val path = prior.args["path"] ?: return@ToolReconciler null
            val content = readFile(path) ?: return@ToolReconciler null
            val wanted = prior.args["content"]
            when {
                // Content recorded and matched: the write landed.
                wanted != null && content == wanted ->
                    "Verified already applied: $path already holds the requested content. No re-write."
                // Recorded content present but different: do NOT claim victory, let the caller retry
                // deliberately (the file is readable and provably not the requested state).
                wanted != null -> null
                else -> null
            }
        }

        /** Tools whose "did it land?" question can be answered by reading the target. */
        val WRITE_TOOLS = setOf("write_file", "create_file")
    }
}
