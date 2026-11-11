package com.codespace.ide.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The census must be RIGHT before anyone trusts it, because the last cost model was built on a
 * number nobody could reproduce (the "350 spans on a plain file" in EditorSpanBudget's header).
 */
class SpanCensusTest {

    private fun plain(lines: Int): AnnotatedString =
        AnnotatedString((1..lines).joinToString("\n") { "line $it of the big file" })

    @Test
    fun plainTextHasNoSpansAtAll() {
        val r = SpanCensus.count(plain(5_000))
        println(SpanCensus.perfLine(r, "plain5000"))
        assertEquals("a plain line emits no style spans in this pipeline", 0, r.totalSpans)
        assertEquals(5_000, r.lines)
        assertEquals(0L, r.widthPassCost)
    }

    @Test
    fun oneStylePerLineIsCountedAndCostsTheWidthPass() {
        val text = buildAnnotatedString {
            (1..5_000).forEachIndexed { i, _ ->
                withStyle(SpanStyle(color = Color(0xFF00FF00))) { append("line " + (i + 1) + " of the big file") }
                if (i < 4_999) append('\n')
            }
        }
        val r = SpanCensus.count(text)
        println(SpanCensus.perfLine(r, "span-per-line5000"))
        assertEquals(5_000, r.spanStyles)
        assertEquals(1, r.distinctSpanStyles)
        assertEquals(25_000_000L, r.widthPassCost)
    }

    @Test
    fun mixedProducersAreDistinguishedByStyle() {
        val text = buildAnnotatedString {
            append("a")
            withStyle(SpanStyle(color = Color.Red)) { append("b") }
            withStyle(SpanStyle(color = Color.Blue, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))) { append("c") }
            append("d")
        }
        val r = SpanCensus.count(text)
        assertEquals(2, r.spanStyles)
        assertEquals("two distinct styles, not one lumped 'spans=2'", 2, r.distinctSpanStyles)
    }

    @Test
    fun thePerfLineCarriesEveryNumberADiagnosisNeeds() {
        val line = SpanCensus.perfLine(SpanCensus.count(plain(10)), "unit")
        println(line)
        listOf("[perf] spans", "total=", "styles=", "para=", "ann=", "distinct=", "lines=",
            "chars=", "spansPerLine=", "widthPassCost=").forEach {
            assertTrue("missing " + it + " in " + line, line.contains(it))
        }
    }
}
