package com.codespace.ide.chat.panel

import com.codespace.ide.agent.voice.SpeakingPulse
import com.codespace.ide.agent.voice.VoiceTurnMachine
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.ResearchPresentation
import com.codespace.ide.agent.research.ResearchSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN 7.11 - the seams between areas, through the real production objects.
 *
 * Every case crosses two areas: voice into the turn machine, the machine into the orb, the outcome
 * presenter into the row the tree renders. Per-component unit tests can all pass while a seam is
 * broken, which is the thing this file exists to catch.
 */
class Stage711IntegrationTest {

    private fun listening(): VoiceTurnMachine {
        val m = VoiceTurnMachine()
        m.on(VoiceTurnMachine.Event.Enter(modelConfigured = true, recognizerReady = true))
        return m
    }

    @Test
    fun `a repeated final transcript submits once, not twice`() {
        val m = listening()
        val first = m.on(VoiceTurnMachine.Event.Utterance("t1", "open the build file"))
        val again = m.on(VoiceTurnMachine.Event.Utterance("t1", "open the build file"))
        assertEquals(1, first.count { it is VoiceTurnMachine.Effect.SubmitTurn })
        assertEquals(0, again.count { it is VoiceTurnMachine.Effect.SubmitTurn })
    }

    @Test
    fun `an empty recognition result submits nothing`() {
        val m = listening()
        val effects = m.on(VoiceTurnMachine.Event.Utterance("t2", "   "))
        assertEquals(0, effects.count { it is VoiceTurnMachine.Effect.SubmitTurn })
    }

    @Test
    fun `speech is not started for a turn that was never submitted`() {
        val m = listening()
        val effects = m.on(VoiceTurnMachine.Event.ReplyStarted("ghost", 2))
        assertEquals(0, effects.count { it is VoiceTurnMachine.Effect.SpeakSegment })
    }

    @Test
    fun `a word boundary moves the orb and fades before the next word`() {
        val m = listening()
        m.on(VoiceTurnMachine.Event.WordBoundary(atMs = 10_000L, gapMs = 400L))
        val s = m.state
        assertEquals(10_000L, s.lastWordAtMs)
        assertEquals(1f, SpeakingPulse.energyAt(10_000L, s.lastWordAtMs, s.wordGapMs), 0.001f)
        val mid = SpeakingPulse.energyAt(10_200L, s.lastWordAtMs, s.wordGapMs)
        assertTrue("mid-word energy decays: " + mid, mid in 0.4f..0.6f)
        assertEquals(0f, SpeakingPulse.energyAt(10_400L, s.lastWordAtMs, s.wordGapMs), 0.001f)
    }

    @Test
    fun `a tool that only reports ok is never rendered as done`() {
        val row = ChatRowModel.ToolActivity("read_file", "the manifest", "r1c1", ok = true)
        assertNotEquals(CopilotPresentation.TaskState.DONE, CopilotBindings.taskState(row))
    }

    @Test
    fun `refused, failed and unanswered results stay three different answers`() {
        val refused = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "REFUSED by policy", "r1c1", ok = false), "")
        val failed = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "fatal: no upstream", "r1c2", ok = false), "")
        val unknown = CopilotBindings.resultRow(
            ChatRowModel.ToolActivity("git_push", "sent, no reply", "r1c3", ok = true), "")
        assertNotEquals(refused.state, failed.state)
        assertNotEquals(failed.state, unknown.state)
    }

    @Test
    fun `an unanswered tool call is never described as a success`() {
        val unanswered = CopilotBindings.nextAction(
            ChatRowModel.ToolActivity("read_file", "sent, no reply", "r1c3", ok = true))
        assertEquals(CopilotPresentation.Ran.UNKNOWN, unanswered.ran)
        assertFalse(unanswered.retrySafe)
        val ok = CopilotBindings.nextAction(
            ChatRowModel.ToolActivity("read_file", "read 12 lines", "r1c4", ok = true), "12 lines of Gradle")
        assertEquals(CopilotPresentation.Ran.YES, ok.ran)
        assertFalse("a success must not offer a retry", ok.retrySafe)
    }

    @Test
    fun `sources are bounded and the omitted count is exact`() {
        val items = (1..55).map {
            ResearchSources.Item("S" + it, "Source " + it, "example.com", "https://example.com/d/" + it)
        }
        val bounded = ResearchPresentation.bound(items)
        assertEquals(40, bounded.shown.size)
        assertEquals(15, bounded.hidden)
        val line = ResearchPresentation.overflowLine(bounded.hidden)
        assertNotNull(line)
        assertTrue("the omitted count is stated: " + line, (line ?: "").contains("15"))
    }
}
