package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 1 Group A: json_query, csv_parse, base64_codec.
 *
 * Each test names the promise it keeps. The refusals matter as much as the successes: these tools
 * feed a model, and a model told "no, here is why" is useful, while a model handed a quietly
 * truncated or half-decoded answer is not.
 */
class DataToolsTest {

    private val people = """{"team":{"members":[
        {"id":1,"name":"Ada","role":"lead","tags":["a","b"]},
        {"id":2,"name":"Blaise","role":"dev","tags":[]},
        {"id":3,"name":"Cy","role":"dev","tags":["a"]}
    ]},"version":2}"""

    // ── json_query ─────────────────────────────────────────────────────────

    @Test
    fun aNestedKeyIsSelectedOnItsOwn() {
        val out = DataTools.jsonQuery(people, "$.team.members[0].name")
        assertTrue(out, out.contains("\"count\":1"))
        assertTrue(out, out.contains("\"Ada\""))
    }

    @Test
    fun anArrayIndexSelectsOneElementAndReportsItsPath() {
        val out = DataTools.jsonQuery(people, "$.team.members[1]")
        assertTrue(out, out.contains("$.team.members[1]"))
        assertTrue(out, out.contains("Blaise"))
    }

    @Test
    fun aWildcardWalksEveryElement() {
        val out = DataTools.jsonQuery(people, "$.team.members[*].name")
        assertTrue(out, out.contains("\"count\":3"))
        for (n in listOf("Ada", "Blaise", "Cy")) assertTrue(out, out.contains(n))
    }

    @Test
    fun recursiveDescentFindsAKeyAtAnyDepth() {
        val out = DataTools.jsonQuery(people, "$..id")
        assertTrue(out, out.contains("\"count\":3"))
        assertTrue(out, out.contains("$.team.members[2].id"))
    }

    @Test
    fun aUnionReturnsTheRequestedKeysInOrder() {
        val out = DataTools.jsonQuery(people, "$.team.members[0]['name','role']")
        assertTrue(out, out.contains("Ada"))
        assertTrue(out, out.contains("lead"))
    }

    @Test
    fun aMissingKeyIsZeroMatchesNotAnError() {
        val out = DataTools.jsonQuery(people, "$.team.nope")
        assertTrue(out, out.contains("\"count\":0"))
        assertFalse(out, out.contains("Invalid"))
    }

    @Test
    fun malformedJsonIsRefusedAndSaysWhy() {
        val out = DataTools.jsonQuery("{\"a\": ", "$.a")
        assertTrue(out, out.startsWith("Invalid JSON"))
        assertTrue(out, out.contains("not modified"))
    }

    @Test
    fun aJsonPathFilterIsRefusedRatherThanApproximated() {
        val out = DataTools.jsonQuery(people, "$.team.members[?(@.role=='dev')]")
        assertTrue(out, out.startsWith("Invalid expression"))
        assertTrue(out, out.contains("not supported"))
    }

    @Test
    fun anExpressionMustStartAtTheRoot() {
        val out = DataTools.jsonQuery(people, "team.members")
        assertTrue(out, out.startsWith("Invalid expression"))
    }

    @Test
    fun aBareDollarIsRefusedBecauseItSelectsEverything() {
        val out = DataTools.jsonQuery(people, "$")
        assertTrue(out, out.startsWith("Invalid expression"))
    }

    @Test
    fun resultsAreCappedAndTheCapIsAnnounced() {
        val big = (0 until 260).joinToString(",", "[", "]") { "{\"i\":$it}" }
        val out = DataTools.jsonQuery(big, "$[*]")
        assertTrue(out, out.contains("\"count\":" + DataTools.MAX_MATCHES))
        assertTrue(out, out.contains("\"truncated\":true"))
        assertTrue("the notice must state the cap", out.contains("Stopped after " + DataTools.MAX_MATCHES))
    }

    @Test
    fun anOversizedDocumentIsRefusedBeforeParsing() {
        val huge = "{\"a\":\"" + "x".repeat(DataTools.MAX_JSON_CHARS) + "\"}"
        val out = DataTools.jsonQuery(huge, "$.a")
        assertTrue(out, out.startsWith("Refusing"))
    }

    @Test
    fun theDocumentIsNeverModified() {
        val out = DataTools.jsonQuery(people, "$.version")
        assertTrue(out, out.contains("2"))
        // re-querying the same source still finds everything: nothing was consumed or rewritten
        assertTrue(DataTools.jsonQuery(people, "$.team.members[*]").contains("\"count\":3"))
    }

    // ── csv_parse ──────────────────────────────────────────────────────────

    @Test
    fun quotedCommasStayInsideTheirField() {
        val csv = "name,note\nAda,\"likes maths, a lot\"\n"
        val out = DataTools.csvParse(csv)
        assertTrue(out, out.contains("Ada | likes maths, a lot"))
        assertTrue(out, out.contains("1 data rows, 2 columns"))
    }

    @Test
    fun aNewlineInsideQuotesDoesNotStartANewRow() {
        val csv = "id,text\n1,\"line one\nline two\"\n2,plain\n"
        val out = DataTools.csvParse(csv)
        assertTrue(out, out.contains("2 data rows"))
        assertTrue(out, out.contains("line one\nline two"))
    }

    @Test
    fun doubledQuotesBecomeOneQuote() {
        val csv = "q\n\"she said \"\"hi\"\"\"\n"
        val out = DataTools.csvParse(csv)
        assertTrue(out, out.contains("she said \"hi\""))
    }

    @Test
    fun aHeaderlessFileNamesItsColumnsByPosition() {
        val out = DataTools.csvParse("1,2,3\n4,5,6\n", hasHeader = false)
        assertTrue(out, out.contains("col1, col2, col3"))
        assertTrue(out, out.contains("2 data rows, 3 columns"))
        assertTrue(out, out.contains("no header row"))
    }

    @Test
    fun emptyCellsArePreserved() {
        val out = DataTools.csvParse("a,b,c\n1,,3\n")
        assertTrue(out, out.contains("1 |  | 3"))
    }

    @Test
    fun aColumnCanBeSelectedByName() {
        val out = DataTools.csvParse("name,age\nAda,36\nCy,41\n", columns = listOf("age"))
        assertTrue(out, out.contains("row 1: 36"))
        assertFalse(out, out.contains("Ada"))
    }

    @Test
    fun anUnknownColumnListsTheRealOnes() {
        val out = DataTools.csvParse("name,age\nAda,36\n", columns = listOf("height"))
        assertTrue(out, out.contains("No column named 'height'"))
        assertTrue(out, out.contains("name, age"))
    }

    @Test
    fun raggedRowsArePaddedAndCounted() {
        val out = DataTools.csvParse("a,b,c\n1,2\n1,2,3\n")
        assertTrue(out, out.contains("Warning: 1 row(s) do not have 3 cells"))
        assertTrue(out, out.contains("row 1: 1 | 2 | "))
    }

    @Test
    fun anOffsetPastTheEndSaysSo() {
        val out = DataTools.csvParse("a\n1\n", offset = 5)
        assertTrue(out, out.contains("Offset 5 is past the end"))
    }

    @Test
    fun aBoundedPageSaysHowToContinue() {
        val csv = "n\n" + (1..120).joinToString("\n") { it.toString() } + "\n"
        val out = DataTools.csvParse(csv, limit = 10)
        assertTrue(out, out.contains("Showing 10 of 120"))
        assertTrue(out, out.contains("offset=10"))
    }

    @Test
    fun anUnclosedQuoteIsMalformedAndNothingIsParsed() {
        val out = DataTools.csvParse("a,b\n1,\"unfinished\n")
        assertTrue(out, out.startsWith("Malformed CSV"))
        assertTrue(out, out.contains("Nothing was parsed"))
    }

    @Test
    fun anEmptyDocumentIsReportedNotEmptyInvented() {
        assertEquals("The CSV is empty: no rows found.", DataTools.csvParse(""))
    }

    @Test
    fun aDifferentDelimiterWorks() {
        val out = DataTools.csvParse("a;b\n1;2\n", delimiter = ';')
        assertTrue(out, out.contains("1 | 2"))
    }

    // ── base64_codec ───────────────────────────────────────────────────────

    @Test
    fun encodeThenDecodeRoundTripsUnicode() {
        val text = "héllo ✓ 世界"
        val encoded = DataTools.base64Codec("encode", text)
        val b64 = encoded.substringAfter('\n')
        val decoded = DataTools.base64Codec("decode", b64)
        assertTrue(decoded, decoded.contains(text))
        assertEquals(DataTools.base64Codec("encode", text), DataTools.base64Codec("enc", text))
    }

    @Test
    fun decodeReportsTheByteCount() {
        val out = DataTools.base64Codec("decode", "aGk=")
        assertTrue(out, out.contains("Decoded 2 bytes"))
        assertTrue(out, out.contains("hi"))
    }

    @Test
    fun anInvalidCharacterIsRefusedWithItsPosition() {
        val out = DataTools.base64Codec("decode", "aGk!=")
        assertTrue(out, out.contains("Invalid Base64"))
        assertTrue(out, out.contains("position 4"))
        assertTrue(out, out.contains("no characters are skipped"))
    }

    @Test
    fun aBadLengthIsRefusedRatherThanPadded() {
        val out = DataTools.base64Codec("decode", "aGk")
        assertTrue(out, out.contains("not a multiple of 4"))
    }

    @Test
    fun lineBreaksAreToleratedBecauseRealBase64HasThem() {
        val out = DataTools.base64Codec("decode", "aGk=\n")
        assertTrue(out, out.contains("Decoded 2 bytes"))
    }

    @Test
    fun binaryOutputIsNotPretendedToBeText() {
        val encoded = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))
        val out = DataTools.base64Codec("decode", encoded)
        assertTrue(out, out.contains("BINARY"))
        assertTrue(out, out.contains("00010203"))
    }

    @Test
    fun emptyInputIsReportedForBothDirections() {
        assertEquals("Nothing to encode: the input is empty.", DataTools.base64Codec("encode", ""))
        assertEquals("Nothing to decode: the input is empty.", DataTools.base64Codec("decode", ""))
    }

    @Test
    fun anUnknownOperationIsRefusedWithTheOptions() {
        val out = DataTools.base64Codec("rotate", "hi")
        assertTrue(out, out.contains("Unknown operation 'rotate'"))
        assertTrue(out, out.contains("encode or decode"))
    }

    @Test
    fun anOversizedInputIsRefused() {
        val out = DataTools.base64Codec("encode", "x".repeat(DataTools.MAX_CODEC_CHARS + 1))
        assertTrue(out, out.startsWith("Refusing"))
    }
}
