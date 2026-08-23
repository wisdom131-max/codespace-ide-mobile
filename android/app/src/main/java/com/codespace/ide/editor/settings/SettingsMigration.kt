package com.codespace.ide.editor.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Migrates settings from the old SharedPreferences-based stores to the
 * unified JSON format.
 *
 * Old stores:
 *   - "project_settings" (ProjectSettingsStore)
 *   - "feature_toggles" (FeatureToggleStore)
 *   - "keybindings" (KeyBindingRegistry)
 *
 * Migration runs once on first launch with the new system. After successful
 * migration, the old SharedPreferences files are left intact (not cleared)
 * so users can downgrade without losing settings. A "migrated" flag is
 * written to prevent re-running.
 */
object SettingsMigration {

    private const val TAG = "SettingsMigration"
    private const val MIGRATION_PREFS = "settings_migration"
    private const val KEY_MIGRATED = "migrated_to_json_v1"

    /**
     * Run migration if not already done.
     * Returns the migrated settings map, or null if migration was already done.
     */
    fun migrateIfNeeded(context: Context): MigratedData? {
        val migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean(KEY_MIGRATED, false)) return null

        Log.i(TAG, "Starting migration from SharedPreferences to JSON settings v1")
        val data = MigratedData()

        // ── Migrate ProjectSettingsStore ──
        val projectPrefs = context.getSharedPreferences("project_settings", Context.MODE_PRIVATE)
        migrateProjectSettings(projectPrefs, data)

        // ── Migrate FeatureToggleStore ──
        val togglePrefs = context.getSharedPreferences("feature_toggles", Context.MODE_PRIVATE)
        migrateFeatureToggles(togglePrefs, data)

        // ── Migrate KeyBindingRegistry ──
        val kbPrefs = context.getSharedPreferences("keybindings", Context.MODE_PRIVATE)
        migrateKeybindings(kbPrefs, data)

        Log.i(TAG, "Migration complete: ${data.settings.size} settings, ${data.toggles.size} toggles, ${data.keybindings.size} keybindings")

        migrationPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        return data
    }

    private fun migrateProjectSettings(prefs: SharedPreferences, data: MigratedData) {
        for (def in SettingsSchema.all) {
            val key = def.key
            if (!prefs.contains(key)) continue
            when (def.type) {
                is SettingsSchema.SettingType.Bool -> {
                    data.settings[key] = prefs.getBoolean(key, def.type.default)
                }
                is SettingsSchema.SettingType.Int -> {
                    data.settings[key] = prefs.getInt(key, def.type.default)
                }
                is SettingsSchema.SettingType.Long -> {
                    data.settings[key] = prefs.getLong(key, def.type.default)
                }
                is SettingsSchema.SettingType.Str -> {
                    data.settings[key] = prefs.getString(key, def.type.default) ?: def.type.default
                }
                is SettingsSchema.SettingType.Enum -> {
                    data.settings[key] = prefs.getString(key, def.type.default) ?: def.type.default
                }
            }
        }
    }

    private fun migrateFeatureToggles(prefs: SharedPreferences, data: MigratedData) {
        for (toggle in SettingsSchema.featureToggles) {
            if (prefs.contains(toggle.key)) {
                data.toggles[toggle.key] = prefs.getBoolean(toggle.key, toggle.default)
            }
        }
    }

    private fun migrateKeybindings(prefs: SharedPreferences, data: MigratedData) {
        for (entry in prefs.all) {
            val value = entry.value as? String ?: continue
            data.keybindings[entry.key] = value
        }
    }

    /**
     * Container for migrated data from all three old stores.
     */
    class MigratedData {
        val settings: MutableMap<String, Any> = mutableMapOf()
        val toggles: MutableMap<String, Boolean> = mutableMapOf()
        val keybindings: MutableMap<String, String> = mutableMapOf()
    }
}
