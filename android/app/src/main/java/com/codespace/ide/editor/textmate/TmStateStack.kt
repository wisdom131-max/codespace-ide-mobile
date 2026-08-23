package com.codespace.ide.editor.textmate

/**
 * The state stack carried between lines during incremental tokenization.
 *
 * Written from scratch. Architecture reference: org.eclipse.tm4e.core.internal.grammar.StateStack
 * (EPL 2.0). Represents the grammar's position in the rule tree after tokenizing a line.
 *
 * When tokenizing line N+1, we start from the state stack left over from line N.
 * If the state stacks match, re-tokenization can be skipped (incremental highlighting).
 *
 * The stack is a linked list of StackElement frames. Each frame records:
 * - The rule that is currently active (e.g., a BeginEndRule that hasn't ended yet)
 * - The position where the rule's begin pattern matched
 * - The content scope name (applied to text between begin and end)
 * - The end regex (resolved with back-references from begin captures)
 */
data class TmStateStack(
    val ruleId: Int,
    /** Char position where the begin pattern matched on the original line. */
    val enterPos: Int,
    /** Anchor position for \G and ^ anchors. */
    val anchorPos: Int,
    /** Whether the begin pattern matched at end of line (for "first line" detection). */
    val beginRuleMatchesAtEOL: Boolean,
    /** The resolved content scope name (e.g., "string.quoted.double.kotlin"). */
    val contentName: String?,
    /** End regex source resolved with back-references, or null if not a BeginEndRule. */
    val endPattern: String?,
    /** Parent frame in the stack (null = bottom of stack). */
    val parent: TmStateStack?,
) {
    companion object {
        /** The empty stack (no rules active — top-level state). */
        val NULL = TmStateStack(
            ruleId = -1,
            enterPos = 0,
            anchorPos = -1,
            beginRuleMatchesAtEOL = false,
            contentName = null,
            endPattern = null,
            parent = null,
        )

        /**
         * Check if two state stacks are equivalent for incremental highlighting.
         * If equal, the line using this state can skip re-tokenization.
         */
        fun equals(a: TmStateStack?, b: TmStateStack?): Boolean {
            if (a == null && b == null) return true
            if (a == null || b == null) return false
            if (a.ruleId != b.ruleId) return false
            if (a.contentName != b.contentName) return false
            if (a.endPattern != b.endPattern) return false
            return equals(a.parent, b.parent)
        }
    }

    /**
     * Push a new frame onto the stack.
     */
    fun push(
        ruleId: Int,
        enterPos: Int,
        anchorPos: Int,
        beginRuleMatchesAtEOL: Boolean,
        contentName: String?,
        endPattern: String?,
    ): TmStateStack {
        return TmStateStack(
            ruleId = ruleId,
            enterPos = enterPos,
            anchorPos = anchorPos,
            beginRuleMatchesAtEOL = beginRuleMatchesAtEOL,
            contentName = contentName,
            endPattern = endPattern,
            parent = this,
        )
    }

    /**
     * Pop the top frame off the stack.
     * Returns null if this is the bottom of the stack.
     */
    fun pop(): TmStateStack? {
        return parent
    }

    /**
     * Depth of the stack (0 = top-level).
     */
    fun depth(): Int {
        var d = 0
        var s: TmStateStack? = this
        while (s != null && s !== NULL) {
            d++
            s = s.parent
        }
        return d
    }

    /**
     * Check if any frame in the stack has the given ruleId.
     * Used to detect infinite loops (same rule pushed without advancing).
     */
    fun hasSameRuleAs(other: TmStateStack): Boolean {
        var a: TmStateStack? = this
        var b: TmStateStack? = other
        while (a != null && a !== NULL) {
            if (b != null && b !== NULL && a.ruleId == b.ruleId) return true
            a = a.parent
            b = b.parent
        }
        return false
    }
}
