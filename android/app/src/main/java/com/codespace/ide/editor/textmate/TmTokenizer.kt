package com.codespace.ide.editor.textmate

import org.joni.Regex
import org.joni.Matcher
import org.joni.Option
import org.jcodings.specific.UTF8Encoding
import java.nio.charset.Charset

/**
 * Line tokenizer — tokenizes a single line of text using a TextMate grammar.
 *
 * Written from scratch. Architecture reference: org.eclipse.tm4e.core.internal.grammar.LineTokenizer
 * (EPL 2.0). This is the core algorithm that:
 *
 * 1. Takes a line of text and a state stack (from the previous line)
 * 2. Applies grammar rules to find token matches
 * 3. Produces tokens (start position + scope name) and a new state stack
 * 4. Handles begin/end rule pushing/popping, captures, and includes
 *
 * The tokenization loop (simplified):
 *   while not at end of line:
 *     1. Compile active rules into a single regex
 *     2. Search for the earliest match
 *     3. If no match → emit remaining text as default token, stop
 *     4. If match is an end rule → pop the stack, emit captures
 *     5. If match is a begin rule → push onto stack, emit begin captures
 *     6. If match is a simple match → emit the matched text with its scope
 *     7. Advance position past the match
 */
class TmTokenizer(
    private val grammar: TmGrammar,
) {
    private val encoding = UTF8Encoding.INSTANCE
    private val utf8: Charset = Charsets.UTF_8

    /** Sentinel rule IDs. */
    private val END_RULE_ID = 0
    private val WHILE_RULE_ID = 1

    /**
     * Result of tokenizing one line.
     *
     * @param tokens List of (startChar, endChar, scopeName) tuples
     * @param newState The state stack to pass to the next line
     */
    data class TokenizeResult(
        val tokens: List<TmToken>,
        val newState: TmStateStack,
    )

    /** A single token: a character range with a scope name. */
    data class TmToken(
        val start: Int,
        val end: Int,
        val scopes: List<String>,
    )

    /**
     * Tokenize a line.
     *
     * @param lineText The line text (without trailing newline)
     * @param prevState The state stack from the previous line (or NULL for first line)
     * @return TokenizeResult with tokens and new state stack
     */
    fun tokenizeLine(lineText: String, prevState: TmStateStack): TokenizeResult {
        val tokens = mutableListOf<TmToken>()
        var stack = if (prevState === TmStateStack.NULL) null else prevState
        var linePos = 0
        var isFirstLine = (prevState === TmStateStack.NULL)
        var anchorPosition = -1
        var stop = false
        var iterations = 0
        val maxIterations = lineText.length * 10 + 100 // safety against infinite loops

        // Active scope path — accumulates scope names from the stack
        val scopePath = mutableListOf<String>()

        // Build initial scope path from the stack
        var s: TmStateStack? = stack
        val tempScopes = mutableListOf<String>()
        while (s != null && s !== TmStateStack.NULL) {
            s.contentName?.let { tempScopes.add(0, it) }
            s = s.parent
        }
        scopePath.addAll(tempScopes)

        while (!stop) {
            if (++iterations > maxIterations) break

            // 1. Get the current rule's compiled patterns
            val compiled = compileRules(stack, lineText)

            // 2. Search for the earliest match
            val match = if (compiled != null) {
                searchEarliest(compiled, lineText, linePos, isFirstLine, anchorPosition)
            } else null

            if (match == null) {
                // No more matches — emit remaining text
                if (linePos < lineText.length) {
                    tokens.add(TmToken(linePos, lineText.length, scopePath.toList()))
                }
                stop = true
                break
            }

            val (matchedRuleId, captureIndices) = match
            val matchStart = captureIndices[0].start
            val matchEnd = captureIndices[0].end
            val hasAdvanced = matchEnd > linePos

            // Emit text before the match
            if (matchStart > linePos) {
                tokens.add(TmToken(linePos, matchStart, scopePath.toList()))
            }

            if (matchedRuleId == END_RULE_ID) {
                // End rule matched — pop the stack
                val poppedRule = grammar.rulesById[stack?.ruleId] as? TmRule.BeginEndRule
                if (poppedRule != null) {
                    // Emit captures for the end pattern
                    if (poppedRule.name != null) {
                        tokens.add(TmToken(matchStart, matchEnd, scopePath + (poppedRule.name!!)))
                    }
                    handleCaptures(lineText, stack, poppedRule.endCaptures, captureIndices, tokens, scopePath)
                }

                // Pop the stack
                val popped = stack
                stack = stack?.pop()
                anchorPosition = popped?.anchorPos ?: -1

                // Remove the popped scope from the scope path
                if (poppedRule?.contentName != null && scopePath.isNotEmpty()) {
                    scopePath.removeAt(scopePath.lastIndex)
                }

                linePos = matchEnd

                // Guard against infinite loops
                if (!hasAdvanced && popped?.enterPos == linePos) {
                    if (linePos < lineText.length) {
                        tokens.add(TmToken(linePos, lineText.length, scopePath.toList()))
                    }
                    stop = true
                }
            } else {
                // A regular rule matched
                val rule = grammar.rulesById[matchedRuleId]

                when (rule) {
                    is TmRule.MatchRule -> {
                        // Simple match — emit the matched text with its scope
                        val matchScopes = if (rule.name != null) {
                            scopePath + rule.name!!
                        } else {
                            scopePath.toList()
                        }
                        tokens.add(TmToken(matchStart, matchEnd, matchScopes))
                        handleCaptures(lineText, stack, rule.captures, captureIndices, tokens, scopePath)
                        linePos = matchEnd
                    }

                    is TmRule.BeginEndRule -> {
                        // Push onto stack
                        val scopeName = rule.name
                        if (scopeName != null) scopePath.add(scopeName)

                        // Resolve end pattern with back-references
                        val resolvedEnd = resolveBackRefs(rule.end, captureIndices, lineText)

                        stack = TmStateStack(
                            ruleId = rule.id,
                            enterPos = linePos,
                            anchorPos = anchorPosition,
                            beginRuleMatchesAtEOL = matchEnd == lineText.length,
                            contentName = rule.contentName,
                            endPattern = resolvedEnd,
                            parent = stack ?: TmStateStack.NULL,
                        )

                        // Emit begin captures
                        if (rule.name != null) {
                            tokens.add(TmToken(matchStart, matchEnd, scopePath.toList()))
                        }
                        handleCaptures(lineText, stack, rule.beginCaptures, captureIndices, tokens, scopePath)

                        // Push content name onto scope path
                        rule.contentName?.let { scopePath.add(it) }

                        anchorPosition = matchEnd
                        linePos = matchEnd

                        // Guard: same rule pushed without advancing
                        val beforePush = stack?.parent
                        if (!hasAdvanced && beforePush != null && beforePush.hasSameRuleAs(stack!!)) {
                            stack = stack.pop()
                            if (linePos < lineText.length) {
                                tokens.add(TmToken(linePos, lineText.length, scopePath.toList()))
                            }
                            stop = true
                        }
                    }

                    is TmRule.BeginWhileRule -> {
                        // Push onto stack (similar to BeginEndRule but with while)
                        val scopeName = rule.name
                        if (scopeName != null) scopePath.add(scopeName)

                        stack = TmStateStack(
                            ruleId = rule.id,
                            enterPos = linePos,
                            anchorPos = anchorPosition,
                            beginRuleMatchesAtEOL = matchEnd == lineText.length,
                            contentName = rule.contentName,
                            endPattern = null, // While rules don't have end patterns
                            parent = stack ?: TmStateStack.NULL,
                        )

                        if (rule.name != null) {
                            tokens.add(TmToken(matchStart, matchEnd, scopePath.toList()))
                        }
                        handleCaptures(lineText, stack, rule.beginCaptures, captureIndices, tokens, scopePath)

                        rule.contentName?.let { scopePath.add(it) }

                        anchorPosition = matchEnd
                        linePos = matchEnd
                    }

                    is TmRule.IncludeRule -> {
                        // Include rules are resolved at compile time, not here
                        // If we somehow matched an include rule directly, just advance
                        linePos = if (matchEnd > linePos) matchEnd else linePos + 1
                    }

                    is TmRule.CaptureRule -> {
                        // Capture rules are handled in handleCaptures, not directly
                        linePos = if (matchEnd > linePos) matchEnd else linePos + 1
                    }

                    else -> {
                        // Unknown rule type — advance to avoid infinite loop
                        linePos = if (matchEnd > linePos) matchEnd else linePos + 1
                    }
                }
            }

            // Safety: if we haven't advanced, force advance by 1
            if (linePos <= (matchStart.coerceAtLeast(0)) && !stop) {
                linePos = (matchStart.coerceAtLeast(0)) + 1
                if (linePos >= lineText.length) {
                    if (linePos <= lineText.length) {
                        tokens.add(TmToken(linePos - 1, lineText.length, scopePath.toList()))
                    }
                    stop = true
                }
            }
        }

        // Merge adjacent tokens with the same scopes
        val merged = mergeAdjacentTokens(tokens)

        return TokenizeResult(merged, stack ?: TmStateStack.NULL)
    }

    /**
     * Compile the active rules for the current stack position into a list of
     * (regex, ruleId) pairs. The tokenizer searches all patterns and picks the earliest match.
     */
    private fun compileRules(stack: TmStateStack?, lineText: String): List<CompiledPattern>? {
        val patterns = mutableListOf<CompiledPattern>()

        if (stack == null || stack === TmStateStack.NULL) {
            // Top-level — use grammar's root patterns
            collectPatterns(grammar.patterns, patterns)
        } else {
            // Inside a rule — use that rule's sub-patterns + end pattern
            val rule = grammar.rulesById[stack.ruleId]
            when (rule) {
                is TmRule.BeginEndRule -> {
                    // Add end pattern first (unless applyEndPatternLast)
                    if (!rule.applyEndPatternLast && stack.endPattern != null) {
                        patterns.add(CompiledPattern(
                            regex = compileOnig(stack.endPattern),
                            ruleId = END_RULE_ID,
                        ))
                    }
                    collectPatterns(rule.patterns, patterns)
                    if (rule.applyEndPatternLast && stack.endPattern != null) {
                        patterns.add(CompiledPattern(
                            regex = compileOnig(stack.endPattern),
                            ruleId = END_RULE_ID,
                        ))
                    }
                }
                is TmRule.BeginWhileRule -> {
                    // While pattern is checked separately, but sub-patterns run
                    collectPatterns(rule.patterns, patterns)
                }
                else -> {
                    collectPatterns(grammar.patterns, patterns)
                }
            }
        }

        return if (patterns.isEmpty()) null else patterns
    }

    /**
     * Collect patterns from a list of rules, resolving includes.
     */
    private fun collectPatterns(rules: List<TmRule>, out: MutableList<CompiledPattern>) {
        for (rule in rules) {
            when (rule) {
                is TmRule.MatchRule -> {
                    val regex = compileOnig(rule.match)
                    if (regex != null) {
                        out.add(CompiledPattern(regex, rule.id))
                    }
                }
                is TmRule.BeginEndRule -> {
                    val regex = compileOnig(rule.begin)
                    if (regex != null) {
                        out.add(CompiledPattern(regex, rule.id))
                    }
                }
                is TmRule.BeginWhileRule -> {
                    val regex = compileOnig(rule.begin)
                    if (regex != null) {
                        out.add(CompiledPattern(regex, rule.id))
                    }
                }
                is TmRule.IncludeRule -> {
                    val resolved = TmGrammarLoader.resolveInclude(rule.include, grammar)
                    collectPatterns(resolved, out)
                }
                is TmRule.CaptureRule -> {
                    // Capture rules don't produce patterns directly
                }
            }
        }
    }

    /**
     * Search all compiled patterns and return the earliest match.
     */
    private fun searchEarliest(
        patterns: List<CompiledPattern>,
        lineText: String,
        startPos: Int,
        isFirstLine: Boolean,
        anchorPosition: Int,
    ): MatchResult? {
        var bestStart = Int.MAX_VALUE
        var bestResult: OnigMatchResult? = null
        var bestRuleId = -1

        for (pattern in patterns) {
            val result = OnigRegexFactory.search(pattern.regex, lineText, startPos)
            if (result != null) {
                val matchStart = result.matchStart
                if (matchStart < bestStart || (matchStart == bestStart && result.matchEnd > (bestResult?.matchEnd ?: 0))) {
                    bestStart = matchStart
                    bestResult = result
                    bestRuleId = pattern.ruleId
                }
            }
        }

        return if (bestResult != null) {
            MatchResult(bestRuleId, bestResult.captures)
        } else null
    }

    /**
     * Handle capture groups — emit tokens for named captures.
     */
    private fun handleCaptures(
        lineText: String,
        stack: TmStateStack?,
        captures: List<TmRule.CaptureRule>,
        captureIndices: Array<OnigCaptureIndex>,
        tokens: MutableList<TmToken>,
        scopePath: List<String>,
    ) {
        for (cap in captures) {
            if (cap.groupIndex >= captureIndices.size) continue
            val idx = captureIndices[cap.groupIndex]
            if (idx.start < 0 || idx.end <= idx.start) continue
            if (cap.name != null) {
                tokens.add(TmToken(idx.start, idx.end, scopePath + (cap.name!!)))
            }
        }
    }

    /**
     * Resolve back-references ($1, $2, \1, \2) in an end pattern using
     * the begin pattern's capture groups.
     */
    private fun resolveBackRefs(
        endPattern: String?,
        beginCaptures: Array<OnigCaptureIndex>,
        lineText: String,
    ): String? {
        if (endPattern == null) return null
        var result: String = endPattern!!
        // Replace $1, $2, etc.
        for (i in beginCaptures.indices.reversed()) {
            if (beginCaptures[i].start < 0) continue
            val replacement = lineText.substring(
                beginCaptures[i].start.coerceAtLeast(0),
                beginCaptures[i].end.coerceAtLeast(0)
            )
            result = result.replace("\$$i", java.util.regex.Matcher.quoteReplacement(replacement))
            result = result.replace("\\$i", java.util.regex.Matcher.quoteReplacement(replacement))
        }
        return result
    }

    /**
     * Compile an Oniguruma pattern, with caching.
     */
    private val regexCache = mutableMapOf<String, Regex>()

    private fun compileOnig(pattern: String): Regex {
        return regexCache.getOrPut(pattern) {
            OnigRegexFactory.compile(pattern) ?: OnigRegexFactory.compile("\uFFFF")!!
        }
    }

    /**
     * Merge adjacent tokens that have the same scope list.
     */
    private fun mergeAdjacentTokens(tokens: List<TmToken>): List<TmToken> {
        if (tokens.isEmpty()) return tokens
        val result = mutableListOf<TmToken>()
        var current = tokens[0]
        for (i in 1 until tokens.size) {
            val next = tokens[i]
            if (current.end == next.start && current.scopes == next.scopes) {
                current = TmToken(current.start, next.end, current.scopes)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    /** A compiled regex pattern with its associated rule ID. */
    private data class CompiledPattern(
        val regex: Regex,
        val ruleId: Int,
    )

    /** Internal match result. */
    private data class MatchResult(
        val ruleId: Int,
        val captures: Array<OnigCaptureIndex>,
    )
}
