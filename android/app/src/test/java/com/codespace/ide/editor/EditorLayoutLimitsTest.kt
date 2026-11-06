package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The 1 MB crash (owner log 2026-11-06_19-54-33) was representational, not performance:
 * `Can't represent a size of 3000001 in Constraints` out of CoreTextField.measure. A single
 * Compose layout cannot be taller than 262,143 px, so the guard has to refuse the open BEFORE
 * that layout is asked for.
 */
class EditorLayoutLimitsTest {

    // The editor's real metrics: fontSize 13sp, LINE_HEIGHT_MULTIPLIER 1.25 (EditorMetrics.kt:68).
    private fun lh(density: Float, fontScale: Float = 1f) =
        EditorLayoutLimits.lineHeightPx(fontSizeSp = 13f, density = density, fontScale = fontScale)

    @Test
    fun theCeilingIsTheOneComposeActuallyEnforces() {
        assertEquals("2^18 - 1: above this, Constraints throws", 262143, EditorLayoutLimits.MAX_DIMENSION_PX)
        assertEquals("work to 60% of it", 157285, EditorLayoutLimits.SAFE_MAX_PX)
    }

    @Test
    fun theOwnersPhoneCrashesSomewhereAroundSixThousandLines() {
        // Density 2.75 and 3.0 bracket the TECNO KL4's reported 720-1080p class; the exact
        // figure comes from the harness (displayMetrics.density on the device).
        // HARD ceiling (what Compose accepts): 262,143 px / 44.7 px per line at 2.75x = ~5,864.
        val hard275 = EditorLayoutLimits.maxLinesHard(lh(2.75f))
        val hard300 = EditorLayoutLimits.maxLinesHard(lh(3.0f))
        assertTrue("2.75x crashes past ~5.9k lines, was $hard275", hard275 in 5_600..6_200)
        assertTrue("3.0x crashes past ~5.4k lines, was $hard300", hard300 in 5_100..5_700)
        // Guard ceiling at 60%: ~3,519 lines at 2.75x. This is what REFUSES the open.
        val guard275 = EditorLayoutLimits.maxLines(lh(2.75f))
        assertTrue("2.75x guard stops at ~3.5k lines, was $guard275", guard275 in 3_300..3_700)

        // The 5,000-line big.txt is UNDER the hard limit (that is why it stalled instead of dying)
        // and OVER the 60% guard (which is deliberate: a file that just fits crashes on a font bump).
        assertTrue("5000 lines are UNDER the hard ceiling at 2.75x (5000 <= ${EditorLayoutLimits.maxLinesHard(lh(2.75f))})",
            5000 <= EditorLayoutLimits.maxLinesHard(lh(2.75f)))
        assertTrue("5000 lines are refused by the 60% guard at 2.75x",
            EditorLayoutLimits.exceedsEditor(5000, lh(2.75f)))
    }

    @Test
    fun aOneMegabyteJsFileIsFarOverTheCeiling() {
        // 1 MB of the ladder's 79-byte JS line is ~13,000 lines: 2.6x the phone's ceiling.
        val lines = 13_268
        assertTrue(EditorLayoutLimits.exceedsEditor(lines, lh(2.75f)))
        assertTrue("and it is over even at the lowest plausible density",
            EditorLayoutLimits.exceedsEditor(lines, lh(2.0f)))
    }

    @Test
    fun aLargerSystemFontLowersTheLineCeiling() {
        val base = EditorLayoutLimits.maxLines(lh(2.75f, 1.0f))
        val scaled = EditorLayoutLimits.maxLines(lh(2.75f, 1.3f))
        assertTrue("font scale 1.3 must reduce the ceiling ($scaled vs $base)", scaled < base)
    }

    /**
     * The real Compose call, when the class is on this JVM's classpath. Marked NOT MEASURED when
     * it is not, rather than pretending the assertion ran.
     */
    @Test
    fun composeItselfRejectsATooTallLayout() {
        val cls = try {
            Class.forName("androidx.compose.ui.unit.Constraints")
        } catch (t: Throwable) {
            null
        }
        if (cls == null) {
            println("[perf] constraints: NOT MEASURED on the JVM classpath (device-only)")
            return
        }
        assumeTrue(true)
        // The public constructor is Constraints(minWidth, maxWidth, minHeight, maxHeight).
        val ctor = try {
            cls.getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
        } catch (t: Throwable) {
            println("[perf] constraints: NOT MEASURED (no 4-int constructor on the classpath)")
            return
        }
        var threw: Throwable? = null
        try {
            // 3,000,001 px of height is exactly the owner's crash value.
            ctor.newInstance(1080, 1080, 0, 3_000_001)
        } catch (t: Throwable) {
            threw = t.cause ?: t
        }
        assertTrue(
            "Constraints must reject a 3,000,001 px height, got $threw",
            threw is IllegalArgumentException,
        )
        println("[perf] constraints: 3,000,001 px rejected with " + threw!!.javaClass.simpleName)
    }
}
