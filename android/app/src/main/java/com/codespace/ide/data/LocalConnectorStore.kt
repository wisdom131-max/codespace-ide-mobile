package com.codespace.ide.data

import android.content.Context
import org.json.JSONArray

/**
 * Firebase, Supabase and n8n used to live in their own "Project services" page reached from the
 * gear menu. They are connectors now, in the Connectors Hub, next to Gmail and GitLab - so this
 * holds their credentials for the Hub and for the agent's `use_connector`, instead of each UI
 * screen reaching into preference keys itself.
 *
 * They stay per-project, on purpose. A Supabase project URL and an n8n instance URL are the two
 * things most likely to differ between repositories, and the credentials were already stored that
 * way; the storage keys are unchanged, so anything configured before the move keeps working.
 *
 * The Hub can be opened from Settings with no project on screen, which is why the project is
 * resolved rather than passed in: the last project the IDE opened, then the first project in the
 * local list. Nothing is invented when both are empty - [activeProjectId] returns null and the Hub
 * says so rather than writing credentials under a made-up id.
 */
object LocalConnectorStore {

    /** The three services that answer to on-device credentials instead of a backend OAuth app. */
    val SERVICES = listOf("firebase", "supabase", "n8n")

    private const val PREFS_PROJECTS = "projects"
    private const val KEY_PROJECT_LIST = "list"
    private const val PREFS_ACTIVE = "active_project"

    private fun activePrefs(context: Context) =
        context.getSharedPreferences(PREFS_ACTIVE, Context.MODE_PRIVATE)

    /** Recorded by the project shell on open, so the Hub knows which project the user was in. */
    fun rememberActiveProject(context: Context, projectId: String) {
        if (projectId.isBlank()) return
        activePrefs(context).edit().putString("id", projectId).apply()
    }

    /**
     * Last opened project, else the first project in the local list, else null.
     * Not "the first project" alone: after opening a second repository, credentials saved from the
     * Hub must land on the one the user is actually working in.
     */
    fun activeProjectId(context: Context): String? {
        val remembered = activePrefs(context).getString("id", null)
        if (!remembered.isNullOrBlank()) return remembered
        return runCatching {
            val raw = context.getSharedPreferences(PREFS_PROJECTS, Context.MODE_PRIVATE)
                .getString(KEY_PROJECT_LIST, null) ?: return@runCatching null
            val arr = JSONArray(raw)
            if (arr.length() == 0) null else arr.getJSONObject(0).optString("id").ifBlank { null }
        }.getOrNull()
    }

    /** Field names as documented per service, so no screen invents its own key. */
    fun fieldsFor(service: String): List<String> = when (service.lowercase()) {
        "firebase" -> listOf("firebase_apikey", "firebase_project_id")
        "supabase" -> listOf("supabase_url", "supabase_anon_key")
        "n8n" -> listOf("n8n_url", "n8n_apikey")
        else -> emptyList()
    }

    fun value(context: Context, service: String, field: String): String? {
        val project = activeProjectId(context) ?: return null
        return SecureTokenStore(context).projectSecret(project, field)
    }

    fun setValue(context: Context, service: String, field: String, value: String?) {
        val project = activeProjectId(context) ?: return
        SecureTokenStore(context).setProjectSecret(project, field, value?.ifBlank { null })
    }

    /** Every field filled: half a credential cannot make a call, so it is not offered to the agent. */
    fun isConfigured(context: Context, service: String): Boolean {
        val fields = fieldsFor(service)
        if (fields.isEmpty()) return false
        return fields.all { !value(context, service, it).isNullOrBlank() }
    }

    fun clear(context: Context, service: String) {
        for (field in fieldsFor(service)) setValue(context, service, field, null)
    }

    /** The base URL a service is called against. Firebase builds its own from the project id. */
    fun baseUrl(context: Context, service: String): String? = when (service.lowercase()) {
        "supabase" -> value(context, "supabase", "supabase_url")?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        "n8n" -> value(context, "n8n", "n8n_url")?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        "firebase" -> value(context, "firebase", "firebase_project_id")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "https://firestore.googleapis.com/v1/projects/$it/databases/(default)/documents" }
        else -> null
    }
}
