package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatError
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFailureAdviceTest {

    @Test fun aRejectedKeyIsNotOfferedAsRetryable() {
        val note = ChatFailureAdvice.noteFor(ChatError.Auth("OpenAI", "openai"))
        assertNotNull(note)
        assertTrue(note!!.contains("401"))
    }

    @Test fun aRateLimitKeepsThePlainRowSoAFixedCauseCanBeRetried() {
        // Retryable: the note would only be noise, and the user may have waited it out already.
        assertNull(ChatFailureAdvice.noteFor(ChatError.RateLimited(3)))
    }

    @Test fun outOfCreditsSaysSoInsteadOfInvitingALoop() {
        assertNotNull(ChatFailureAdvice.noteFor(ChatError.OutOfCredits("Top up the account.")))
    }

    @Test fun aCompletedServiceWriteIsNamedBeforeAResend() {
        val note = ChatFailureAdvice.noteFor(ChatError.RateLimited(1), externalWriteDone = true)
        assertNotNull(note)
        assertTrue(note!!.contains("already changed something"))
        assertTrue(ChatFailureAdvice.externalWriteNote("GitHub").contains("GitHub"))
    }
}
