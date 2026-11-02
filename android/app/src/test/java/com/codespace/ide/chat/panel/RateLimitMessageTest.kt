package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 429 copy, against the source it was copied from.
 *
 * Thresholds come from `secondsToHumanReadableTime` in microsoft/vscode-copilot-chat
 * (`src/util/common/time.ts`): under 90 stays in seconds, up to 5400 becomes minutes, beyond that
 * hours with a minutes remainder. Getting these edges wrong is the difference between "2 minutes"
 * and "1 minutes" appearing where the reference says the other one.
 */
class RateLimitMessageTest {

    @Test
    fun underNinetySecondsStaysInSeconds() {
        assertEquals("0 seconds", RateLimitMessage.humanize(0))
        assertEquals("45 seconds", RateLimitMessage.humanize(45))
        assertEquals("89 seconds", RateLimitMessage.humanize(89))
    }

    @Test
    fun ninetySecondsIsTheMinutesBoundaryAndKeepsUpstreamPluralisation() {
        // Upstream returns "1 minutes" here. Mirrored on purpose: a copy that reads better than
        // the reference is no longer the same copy.
        assertEquals("1 minutes", RateLimitMessage.humanize(90))
        assertEquals("2 minutes", RateLimitMessage.humanize(120))
    }

    @Test
    fun ninetyMinutesIsTheHoursBoundary() {
        assertEquals("90 minutes", RateLimitMessage.humanize(5400))
        assertEquals("3 hours", RateLimitMessage.humanize(10800))
        assertEquals("3 hours 30 minutes", RateLimitMessage.humanize(12600))
    }

    @Test
    fun anUnknownWaitSaysAMomentRatherThanGuessingANumber() {
        assertTrue(RateLimitMessage.detail(null).contains("wait a moment before trying again"))
        assertTrue(RateLimitMessage.detail(0).contains("wait a moment"))
        assertTrue(RateLimitMessage.detail(-5).contains("wait a moment"))
    }

    @Test
    fun theSentenceCarriesTheWaitAndTheReferenceLink() {
        val text = RateLimitMessage.detail(120)
        assertEquals(
            "Sorry, your request was rate-limited. Please wait 2 minutes before trying again. " +
                "[Learn More](" + RateLimitMessage.LEARN_MORE + ")",
            text,
        )
    }

    @Test
    fun aRateLimitedErrorLandsInTheFailureRowWithTheReferenceCopy() {
        val row = ChatStatusMapping.rowForError(ChatError.RateLimited(120))
        assertTrue(row is ChatRowModel.Failed)
        val failed = row as ChatRowModel.Failed
        assertEquals(RateLimitMessage.VERDICT, failed.verdict)
        assertEquals(RateLimitMessage.detail(120), failed.detail)
        assertTrue("a 429 stays retryable by hand, never automatically", failed.canRetry)
    }
}
