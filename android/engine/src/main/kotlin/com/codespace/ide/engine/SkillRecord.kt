package com.codespace.ide.engine

object SkillRecord {
    data class Skill(val name: String, val steps: List<String>, val uses: Int = 0)
    fun render(s: Skill): String = s.name + "\t" + s.uses + "\t" + s.steps.joinToString(" | ")
    fun parse(line: String): Skill? {
        val p = line.split("\t")
        if (p.size < 3) return null
        val uses = p[1].toIntOrNull() ?: return null
        val steps = if (p[2].isBlank()) emptyList() else p[2].split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
        return Skill(p[0], steps, uses)
    }
}
