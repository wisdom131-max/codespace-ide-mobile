package com.codespace.ide.engine

/**
 * PROJECT NINE 9.15 (catalog 16) - named prompts with a version, instead of strings inline.
 *
 * Prompt text lived inline at the call site, so there was no way to say which prompt produced a
 * bad answer, and no way to change one without hunting every copy. A registry gives each prompt a
 * name and a version, and bumping the version on replacement is what makes a regression traceable.
 */
object PromptRegistry {

    data class Entry(val name: String, val version: Int, val body: String)

    class Registry {

        private val entries = LinkedHashMap<String, Entry>()

        fun register(name: String, body: String): Entry {
            val key = name.trim()
            require(key.isNotEmpty()) { "a prompt needs a name" }
            val version = (entries[key]?.version ?: 0) + 1
            val entry = Entry(key, version, body)
            entries[key] = entry
            return entry
        }

        fun get(name: String): Entry? = entries[name]

        fun names(): List<String> = entries.keys.toList()

        fun size(): Int = entries.size
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun put(name: String, body: String, version: Int = 1): Entry {
        val e = Entry(name, version, body)
        entries[name] = e
        return e
    }

    fun body(name: String, fallback: String = ""): String = entries[name]?.body ?: fallback

    init {
        // Seeded here rather than in the app, so the text has one home and can be versioned or
        // overridden without editing a call site.
        put("critic.checklist", "## BEFORE YOU FINISH\n" +
            SelfCritique.CHECKLIST.joinToString("\n") { "- " + it })
    }
}
