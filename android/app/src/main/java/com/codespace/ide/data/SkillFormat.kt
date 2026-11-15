package com.codespace.ide.data

/**
 * PHASE 6a (owner decision 4, 2026-11-14): a skill can be switched off without being deleted.
 *
 * The store wrote name/description/body and nothing else, so "disabled" had nowhere to live and
 * every saved skill was offered to the model forever. The rule that matters is one line in the
 * index: a disabled skill must not appear in the block the model reads, while its file and its
 * text are kept exactly as they were.
 *
 * Pure, so the format is tested without a Context or a file system.
 */
object SkillFormat {

    data class Record(
        val slug: String,
        val name: String,
        val description: String,
        val body: String,
        val enabled: Boolean = true,
    )

    /** JSON that round-trips. `enabled` is written explicitly so an older file gains it on next save. */
    fun encode(record: Record): String =
        org.json.JSONObject()
            .put("name", record.name)
            .put("description", record.description)
            .put("body", record.body)
            .put("slug", record.slug)
            .put("enabled", record.enabled)
            .toString(2)

    /**
     * Reads one skill file.
     *
     * A file written before this phase has no `enabled` key; it restores as ENABLED, because a
     * skill that worked yesterday must not silently disappear today. A file that will not parse
     * returns null and the caller reports it - never a half-read skill.
     */
    fun decode(text: String, fallbackSlug: String): Record? = runCatching {
        val o = org.json.JSONObject(text)
        Record(
            slug = o.optString("slug", fallbackSlug),
            name = o.optString("name", fallbackSlug),
            description = o.optString("description", ""),
            body = o.optString("body", ""),
            enabled = o.optBoolean("enabled", true),
        )
    }.getOrNull()

    /** The lines the model is shown. Disabled skills are absent, not marked. */
    fun indexLines(records: List<Record>): List<String> =
        records.filter { it.enabled }.map { record ->
            if (record.description.isBlank()) "- " + record.name
            else "- " + record.name + ": " + record.description
        }
}
