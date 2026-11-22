package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillRecordTest {
    @Test fun roundTrips() {
        val s = SkillRecord.Skill("deploy", listOf("build", "push"), 3)
        assertEquals(s, SkillRecord.parse(SkillRecord.render(s)))
    }
    @Test fun blankStepsGiveEmptyList() {
        assertEquals(emptyList<String>(), SkillRecord.parse("x" + 9.toChar() + "0" + 9.toChar())?.steps)
    }
}
