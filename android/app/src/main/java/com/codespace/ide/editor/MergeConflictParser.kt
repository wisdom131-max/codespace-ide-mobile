package com.codespace.ide.editor

/**
 * MergeConflictParser — parses git merge conflict markers into structured hunks.
 *
 * Conflict format:
 * <<<<<<< HEAD
 * ours line 1
 * ours line 2
 * =======
 * theirs line 1
 * theirs line 2
 * >>>>>>> branch-name
 *
 * Phase 22-D — merge conflict inline editor foundation.
 */

data class ConflictHunk(
    val startLine: Int,      // 0-based line index of <<<<<<< marker
    val separatorLine: Int,  // 0-based line index of ======= marker
    val endLine: Int,        // 0-based line index of >>>>>>> marker
    val oursBranch: String,  // e.g. "HEAD"
    val theirsBranch: String, // e.g. "feature-branch"
    val oursLines: List<String>,   // content between <<<<<<< and =======
    val theirsLines: List<String>,  // content between ======= and >>>>>>>
)

object MergeConflictParser {

    private val CONFLICT_START = Regex("^<<<<<<< (.+)$")
    private val CONFLICT_SEP = "======="
    private val CONFLICT_END = Regex("^>>>>>>> (.+)$")

    fun parse(content: String): List<ConflictHunk> {
        val lines = content.lines()
        val hunks = mutableListOf<ConflictHunk>()
        var i = 0

        while (i < lines.size) {
            val startMatch = CONFLICT_START.find(lines[i])
            if (startMatch != null) {
                val oursBranch = startMatch.groupValues[1]
                val startLine = i
                val oursLines = mutableListOf<String>()

                i++
                // Collect ours lines until =======
                while (i < lines.size && lines[i].trim() != CONFLICT_SEP) {
                    oursLines.add(lines[i])
                    i++
                }

                if (i >= lines.size) break // malformed — no separator
                val separatorLine = i

                i++
                val theirsLines = mutableListOf<String>()
                // Collect theirs lines until >>>>>>>
                while (i < lines.size) {
                    val endMatch = CONFLICT_END.find(lines[i])
                    if (endMatch != null) {
                        val theirsBranch = endMatch.groupValues[1]
                        val endLine = i
                        hunks.add(ConflictHunk(
                            startLine = startLine,
                            separatorLine = separatorLine,
                            endLine = endLine,
                            oursBranch = oursBranch,
                            theirsBranch = theirsBranch,
                            oursLines = oursLines,
                            theirsLines = theirsLines
                        ))
                        i++
                        break
                    }
                    theirsLines.add(lines[i])
                    i++
                }
            } else {
                i++
            }
        }

        return hunks
    }

    fun hasConflicts(content: String): Boolean {
        return content.contains("<<<<<<< ") && content.contains(">>>>>>> ")
    }

    /**
     * Resolve a single conflict hunk by choosing ours, theirs, or both.
     * Returns the new content with the hunk replaced.
     */
    fun resolveHunk(content: String, hunk: ConflictHunk, resolution: ConflictResolution): String {
        val lines = content.lines().toMutableList()
        val replacement = when (resolution) {
            ConflictResolution.OURS -> hunk.oursLines
            ConflictResolution.THEIRS -> hunk.theirsLines
            ConflictResolution.BOTH -> hunk.oursLines + hunk.theirsLines
            ConflictResolution.BOTH_REVERSED -> hunk.theirsLines + hunk.oursLines
        }

        // Remove lines from startLine to endLine (inclusive), insert replacement
        val before = lines.subList(0, hunk.startLine)
        val after = lines.subList(hunk.endLine + 1, lines.size)
        val newLines = before.toList() + replacement + after.toList()

        return newLines.joinToString("\n")
    }

    /**
     * Resolve ALL conflict hunks in the content at once.
     */
    fun resolveAll(content: String, resolution: ConflictResolution): String {
        var result = content
        // Resolve from bottom to top so line indices don't shift
        val hunks = parse(content).sortedByDescending { it.startLine }
        for (hunk in hunks) {
            result = resolveHunk(result, hunk, resolution)
        }
        return result
    }
}

enum class ConflictResolution { OURS, THEIRS, BOTH, BOTH_REVERSED }
