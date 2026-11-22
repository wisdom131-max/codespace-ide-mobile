package com.codespace.ide.engine

/**
 * PROJECT NINE 9.19 (catalog 38) - keep a procedure once it has been worked out.
 *
 * SkillRecord could render and parse a procedure, but nothing held them, so the same multi-step
 * fix was worked out from scratch every time. A book gives procedures a home and a way to find the
 * one that matches the request.
 *
 * Learning the same name twice does not overwrite silently: it reports false, so a caller can
 * decide whether the new version is better instead of losing the old one by accident.
 */
object SkillBook {

    class Book(private val maxSkills: Int = 200) {

        private val skills = LinkedHashMap<String, SkillRecord.Skill>()

        fun learn(skill: SkillRecord.Skill): Boolean {
            val name = skill.name.trim()
            if (name.isEmpty() || skills.containsKey(name)) return false
            if (skills.size >= maxSkills) return false
            skills[name] = skill
            return true
        }

        fun match(request: String): SkillRecord.Skill? {
            val wanted = terms(request)
            if (wanted.isEmpty()) return null
            return skills.values
                .map { s -> s to terms(s.name + " " + s.steps.joinToString(" ")).count { wanted.contains(it) } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .firstOrNull()
                ?.first
        }

        fun recordUse(name: String): SkillRecord.Skill? {
            val existing = skills[name] ?: return null
            val bumped = existing.copy(uses = existing.uses + 1)
            skills[name] = bumped
            return bumped
        }

        fun all(): List<SkillRecord.Skill> = skills.values.toList()

        fun size(): Int = skills.size

        private fun terms(s: String): Set<String> =
            s.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.toSet()
    }
}
