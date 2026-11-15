package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 6c: explicit memory directives are routed; ordinary prose that mentions memory is not. */
class MemoryIntentTest {

    @Test
    fun anExplicitRememberIsADirective() {
        assertEquals(MemoryIntent.Kind.REMEMBER, MemoryIntent.classify("Remember that I deploy on Fridays"))
        assertEquals(MemoryIntent.Kind.REMEMBER, MemoryIntent.classify("please remember my test command"))
    }

    @Test
    fun anExplicitForgetIsADirective() {
        assertEquals(MemoryIntent.Kind.FORGET, MemoryIntent.classify("Forget that I use gradle"))
        assertEquals(MemoryIntent.Kind.FORGET, MemoryIntent.classify("forget the memory about fridays"))
    }

    @Test
    fun askingWhatIsRememberedIsARecall() {
        assertEquals(MemoryIntent.Kind.RECALL, MemoryIntent.classify("What do you remember about this project?"))
        assertEquals(MemoryIntent.Kind.RECALL, MemoryIntent.classify("do you remember my build command"))
    }

    @Test
    fun ordinaryProseIsNotRouted() {
        assertEquals(MemoryIntent.Kind.NONE, MemoryIntent.classify("I remember when the build broke last week"))
        assertEquals(MemoryIntent.Kind.NONE, MemoryIntent.classify("fix the memory leak in the renderer"))
        assertEquals(MemoryIntent.Kind.NONE, MemoryIntent.classify(""))
    }

    @Test
    fun forgetWinsOverRememberWhenBothAppear() {
        assertEquals(
            MemoryIntent.Kind.FORGET,
            MemoryIntent.classify("Forget that note, I will remember that later"),
        )
    }

    @Test
    fun eachDirectiveNamesTheExactToolItNeeds() {
        assertTrue(MemoryIntent.directive(MemoryIntent.Kind.REMEMBER)!!.contains("save_memory"))
        assertTrue(MemoryIntent.directive(MemoryIntent.Kind.FORGET)!!.contains("delete_memory"))
        assertTrue(MemoryIntent.directive(MemoryIntent.Kind.RECALL)!!.contains("read_memory"))
    }

    @Test
    fun anOrdinaryMessageAddsNothingToTheRequest() {
        assertNull(MemoryIntent.directive(MemoryIntent.Kind.NONE))
    }
}
