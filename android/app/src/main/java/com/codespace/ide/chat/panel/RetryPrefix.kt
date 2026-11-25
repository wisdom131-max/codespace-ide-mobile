package com.codespace.ide.chat.panel

/**
 * STAGE 0 (Checkpoint 0.5) - the text a retry continues from.
 *
 * A retry used to keep only the LENGTH of the failed attempt's buffer, then restore it with
 * StringBuilder.setLength(oldLength) on a buffer that had just been cleared. That call pads with
 * '\u0000' whenever the target length is larger than the current one, so a retry could begin with
 * a run of NUL characters that the reader never typed and the provider never sent.
 *
 * Keeping the TEXT instead makes the padding impossible rather than guarded: nothing is ever
 * grown to a length it does not already hold. The prefix is also shown, not just buffered, so a
 * retry keeps the opening the reader already has.
 */
internal object RetryPrefix {

    /**
     * Folds a released chunk into the text already shown.
     *
     * A provider that re-emits the prefix at the start of a continuation must not have it shown
     * twice; a provider that continues from where it stopped must not have it dropped. Both cases
     * are decided by whether the new chunk already begins with everything shown so far.
     */
    fun merge(shown: String, fresh: String): String =
        if (shown.isNotEmpty() && fresh.startsWith(shown)) fresh else shown + fresh
}
