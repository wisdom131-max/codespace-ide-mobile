package com.codespace.ide.ui.screens

/**
 * PHASE 6b (owner decision 4): what a file picked from the phone becomes when it is imported as a
 * skill. Pure, so the rules are tested without a picker, a ContentResolver or Compose.
 *
 * Rules: a `.json` file is what SkillStore writes, so it is read as a skill; anything else is
 * treated as the skill's TEXT, with the name taken from the file name and the description from a
 * Markdown heading when there is one. An empty file is refused rather than saved as a blank skill.
 */
internal object SkillImportPlan {

    data class Imported(val name: String, val description: String, val body: String)

    fun fromFile(displayName: String, text: String): Imported? {
        if (text.isBlank()) return null
        val fileName = displayName.substringAfterLast('/').trim()
        val base = fileName.substringBeforeLast('.').ifBlank { "Imported skill" }
        if (fileName.lowercase().endsWith(".json")) {
            val record = com.codespace.ide.data.SkillFormat.decode(text, com.codespace.ide.data.SkillStore.slugOf(base))
            if (record != null) {
                return Imported(
                    name = record.name.ifBlank { base },
                    description = record.description,
                    body = record.body,
                )
            }
            // A .json that is not a skill file is still text worth keeping, under its own name.
        }
        val heading = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
        val description = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.take(120)
            .orEmpty()
        return Imported(name = heading?.ifBlank { base } ?: base, description = description, body = text)
    }
}
