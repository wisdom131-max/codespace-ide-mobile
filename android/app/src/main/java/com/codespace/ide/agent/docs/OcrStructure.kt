package com.codespace.ide.agent.docs

/**
 * PROJECT 2.12 (2026-11-15): turn recognised text lines into something a reader can use.
 *
 * ML Kit hands back text in the order its detector found it, which is layout order and is *not*
 * reading order: a two-column page comes back interleaved, and a code screenshot loses every
 * indentation because a detector reports words, not leading whitespace.
 *
 * This file is the pure half of OCR and does the one thing an engine cannot do for us: reassemble.
 * It sorts by vertical position, breaks paragraphs at real gaps rather than at every line, keeps
 * two columns apart by never jumping across a wide horizontal gap on the same row, and rebuilds
 * indentation for code-shaped text from the left edge of each line relative to the block.
 */
object OcrStructure {

    /** One recognised line with the box the engine saw it in. Coordinates are in the bitmap's own pixels. */
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float? = null,
    ) {
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val width: Int get() = (right - left).coerceAtLeast(1)
    }

    data class Result(
        val text: String,
        val confidence: Float?,
        val lineCount: Int,
        val looksLikeCode: Boolean,
    )

    /**
     * Assemble lines into readable text.
     *
     * [gapFactor] is how many line heights of vertical space count as a paragraph break. 1.6 leaves
     * ordinary line spacing alone and still splits a genuine paragraph gap; a tighter value turns
     * every line of a list into its own paragraph.
     */
    fun assemble(lines: List<Line>, gapFactor: Double = 1.6): Result {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return Result("", null, 0, false)

        val ordered = orderForReading(usable)
        val code = looksLikeCode(ordered)
        val medianHeight = median(ordered.map { it.height })
        val gapLimit = (medianHeight * gapFactor).toInt().coerceAtLeast(medianHeight + 1)

        val sb = StringBuilder()
        var previous: Line? = null
        ordered.forEach { line ->
            val prev = previous
            if (prev != null) {
                val verticalGap = line.top - prev.bottom
                if (verticalGap > gapLimit) sb.append("\n\n") else sb.append("\n")
            }
            sb.append(if (code) indentOf(line, ordered, medianHeight) else "")
            sb.append(line.text.trim())
            previous = line
        }

        val confidences = usable.mapNotNull { it.confidence }
        val mean = if (confidences.isEmpty()) null else confidences.average().toFloat()
        return Result(sb.toString(), mean, usable.size, code)
    }

    /**
     * Reading order: down one column, then down the next.
     *
     * Sorting by `top` alone interleaves a two-column page, because column A's second line and
     * column B's first line share a band; sorting by `left` first destroys reading order entirely.
     * So a page is first split where a real gutter exists - a horizontal gap wider than a line is
     * tall - and each column is then read top to bottom. A single-column page has no such gap and
     * falls through unchanged, which matters because an indented code block must not be mistaken
     * for a second column.
     */
    fun orderForReading(lines: List<Line>): List<Line> {
        val columns = splitColumns(lines)
        return columns.flatMap { column -> column.sortedWith(compareBy({ it.top }, { it.left })) }
    }

    /**
     * Cluster lines into columns by left edge, breaking only at a gap wider than
     * [COLUMN_GAP_FACTOR] line heights. A cluster of one line is not a column: that is a heading or
     * a stray label, and treating it as a column would reorder the whole page around it.
     */
    fun splitColumns(lines: List<Line>): List<List<Line>> {
        if (lines.size < 2) return listOf(lines)
        val gapLimit = (median(lines.map { it.height }) * COLUMN_GAP_FACTOR).coerceAtLeast(8.0)
        val byLeft = lines.sortedBy { it.left }
        val clusters = ArrayList<MutableList<Line>>()
        var clusterRight = Int.MIN_VALUE
        byLeft.forEach { line ->
            if (clusters.isEmpty() || line.left - clusterRight <= gapLimit) {
                if (clusters.isEmpty()) clusters.add(mutableListOf())
                clusters.last().add(line)
                clusterRight = maxOf(clusterRight, line.right)
            } else {
                clusters.add(mutableListOf(line))
                clusterRight = line.right
            }
        }
        val realColumns = clusters.filter { it.size >= 2 }
        if (realColumns.size < 2) return listOf(lines)
        val leftovers = clusters.filter { it.size < 2 }.flatten()
        return realColumns + listOf(leftovers).filter { it.isNotEmpty() }
    }

    /**
     * A cheap, honest test for "this is code, not prose". Two independent signals, because either
     * alone misfires: punctuation that only code has, and lines short and narrow enough to be
     * statements. Prose with a semicolon in one sentence must not be reformatted as source.
     */
    fun looksLikeCode(lines: List<Line>): Boolean {
        if (lines.isEmpty()) return false
        val codeSignals = listOf(";", "{", "}", "=>", "()", "->", "==", "//", "#include", "def ", "val ", "fun ")
        val hits = lines.count { line -> codeSignals.any { line.text.trim().contains(it) } }
        val ratio = hits.toDouble() / lines.size
        val shortLines = lines.count { it.text.trim().length <= 100 }.toDouble() / lines.size
        return ratio >= 0.3 && shortLines >= 0.6
    }

    /**
     * Rebuild leading indentation from geometry.
     *
     * The detector returns words, not whitespace, so indentation has to be inferred: how far this
     * line starts to the right of the block's left edge, in character widths. Capped, because one
     * deeply indented line must not push everything else off the screen.
     */
    fun indentOf(line: Line, block: List<Line>, medianHeight: Int = 0): String {
        if (block.isEmpty()) return ""
        val minLeft = block.minOf { it.left }
        val charWidth = medianCharacterWidth(block, medianHeight)
        if (charWidth <= 0) return ""
        val levels = Math.round((line.left - minLeft).toDouble() / charWidth).toInt()
        val clamped = levels.coerceIn(0, MAX_INDENT)
        return " ".repeat(clamped * INDENT_WIDTH)
    }

    /** Rough character width: a fraction of line height, which is stable across resolutions. */
    private fun medianCharacterWidth(block: List<Line>, medianHeight: Int): Double {
        val heights = if (medianHeight > 0) medianHeight else median(block.map { it.height })
        val estimated = block.map { it.width.toDouble() / it.text.trim().length.coerceAtLeast(1) }
        val typical = medianDouble(estimated)
        return if (typical > 0) typical else heights * 0.5
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private const val COLUMN_GAP_FACTOR = 1.5
    private const val INDENT_WIDTH = 4
    private const val MAX_INDENT = 6
}
