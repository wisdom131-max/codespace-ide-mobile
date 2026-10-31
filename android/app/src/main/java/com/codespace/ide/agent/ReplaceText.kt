package com.codespace.ide.agent

/**
 * VS Code parity: `replace_string_in_file` (verified against microsoft/vscode-copilot-chat
 * `ToolName.ReplaceString` / `MultiReplaceString`).
 *
 * The matching rules live here, away from any Android type, because they are the whole
 * safety story of a targeted edit:
 *  - an empty needle would match everywhere and rewrite the file from the top, so it is refused;
 *  - a needle that matches nothing is a stale copy of the file, and saying so is better than
 *    silently writing an identical file and reporting success;
 *  - an AMBIGUOUS needle is refused rather than resolved by picking the first hit, because
 *    "it changed something" and "it changed the right thing" are different claims.
 */
internal sealed interface ReplaceOutcome {
    /** [count] occurrences replaced; [updated] is the full new file content. */
    data class Ok(val updated: String, val count: Int) : ReplaceOutcome
    data class Refused(val reason: String) : ReplaceOutcome
}

internal object ReplaceText {

    fun apply(current: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceOutcome {
        if (oldText.isEmpty()) {
            return ReplaceOutcome.Refused("old_string is empty. Pass the exact text to replace.")
        }
        if (oldText == newText) {
            return ReplaceOutcome.Refused("old_string and new_string are identical, so nothing would change.")
        }
        val count = countOf(current, oldText)
        if (count == 0) {
            return ReplaceOutcome.Refused(
                "No match for old_string. The file may have changed since it was read; read it again " +
                    "and copy the exact text, including indentation.",
            )
        }
        if (count > 1 && !replaceAll) {
            return ReplaceOutcome.Refused(
                "old_string appears $count times. Include more surrounding text so the match is " +
                    "unique, or pass replace_all=true to change every occurrence.",
            )
        }
        val updated = if (replaceAll) current.replace(oldText, newText)
        else current.replaceFirst(oldText, newText)
        return ReplaceOutcome.Ok(updated, if (replaceAll) count else 1)
    }

    /** Non-overlapping occurrences, so "aa" in "aaa" counts once. */
    fun countOf(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var from = 0
        var n = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return n
            n++
            from = i + needle.length
        }
    }
}
