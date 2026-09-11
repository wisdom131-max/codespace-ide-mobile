package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * CUSTOM-ENDPOINT STORE (2026-09-11): persisted base URL for the generic
 * OpenAI-compatible "Custom Endpoint" provider.
 *
 * Deliberately NOT in SecureTokenStore: the URL is configuration, not a
 * credential (matches VS Code — endpoint/model config lives in settings,
 * only the key goes in SecretStorage). Plain SharedPreferences.
 *
 * Init pattern follows JsonSettingsStore/ProjectSettingsStore: initialized
 * once from CodeSpaceApplication.onCreate(), then a process-wide singleton.
 */
object CustomEndpointStore {
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("custom_endpoint", Context.MODE_PRIVATE)
    }

    /** Current base URL (trimmed, non-blank) or null if none saved. */
    var baseUrl: String?
        get() = try {
            prefs?.getString("base_url", null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
        set(value) {
            try {
                val p = prefs ?: return
                if (value.isNullOrBlank()) p.edit().remove("base_url").apply()
                else p.edit().putString("base_url", value.trim()).apply()
            } catch (_: Exception) { }
        }
}
