package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.10: the resize maths, before any bitmap exists.
 *
 * The rule worth pinning is that the sampled image must land ABOVE the target, never below it. A
 * sampler that overshoots slightly only costs memory; one that undershoots hands back a permanently
 * softer image than the user asked for, and no later step can recover it.
 */
class ImagePipelineTest {

    @Test
    fun aHugePhotoIsSampledInPowersOfTwo() {
        // 4000x3000 into 800: sample 2 leaves 2000, sample 4 leaves 1000 (still above 800) and
        // sample 8 would leave 500 (below it), so 4 is the largest power of two that stays above.
        assertEquals(4, ImagePipeline.sampleSizeFor(4000, 3000, 800))
        assertEquals(8, ImagePipeline.sampleSizeFor(4000, 3000, 400))
    }

    @Test
    fun samplingNeverLandsBelowTheTarget() {
        val cases = listOf(
            Triple(4032, 3024, 1024),
            Triple(1080, 1920, 512),
            Triple(8000, 6000, 1500),
            Triple(640, 480, 200),
        )
        cases.forEach { (w, h, target) ->
            val sample = ImagePipeline.sampleSizeFor(w, h, target)
            val longEdge = maxOf(w, h) / sample
            assertTrue(
                "$w x $h sampled by $sample lands at $longEdge, below the $target target",
                longEdge >= target || sample == 1,
            )
        }
    }

    @Test
    fun anImageAlreadySmallEnoughIsLeftAlone() {
        assertEquals(1, ImagePipeline.sampleSizeFor(400, 300, 1024))
        assertEquals(Pair(400, 300), ImagePipeline.targetSize(400, 300, 1024))
    }

    @Test
    fun theExactScaleKeepsTheAspectRatio() {
        val (w, h) = ImagePipeline.targetSize(4000, 3000, 1000)
        assertEquals(1000, w)
        assertEquals(750, h)
    }

    @Test
    fun aPortraitImageIsScaledByItsLongEdge() {
        val (w, h) = ImagePipeline.targetSize(1200, 3600, 900)
        assertEquals(900, h)
        assertEquals(300, w)
    }

    @Test
    fun upscalingIsRefusedRatherThanInvented() {
        assertEquals(Pair(200, 100), ImagePipeline.targetSize(200, 100, 4000))
    }

    @Test
    fun nonsenseInputStaysHarmless() {
        assertEquals(1, ImagePipeline.sampleSizeFor(0, 0, 800))
        assertEquals(Pair(0, 0), ImagePipeline.targetSize(0, 0, 800))
        assertEquals(Pair(100, 100), ImagePipeline.targetSize(100, 100, 0))
        assertEquals(100, ImagePipeline.clampQuality(500))
        assertEquals(1, ImagePipeline.clampQuality(-20))
    }

    @Test
    fun theOutputFormatComesFromTheOutputPath() {
        assertEquals("jpeg", ImagePipeline.formatOf("photo.JPG"))
        assertEquals("png", ImagePipeline.formatOf("a/b/c.png"))
        assertEquals("webp", ImagePipeline.formatOf("x.webp"))
        assertEquals("", ImagePipeline.formatOf("notes.txt"))
        assertEquals("", ImagePipeline.formatOf("noextension"))
    }
}
