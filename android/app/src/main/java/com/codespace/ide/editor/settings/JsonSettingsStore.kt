package com.codespace.ide.editor.settings

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File

/**
 * Unified JSON-based settings store.
 *
 * Replaces the scattered SharedPreferences approach with a single JSON file
 * that contains all settings, feature toggles, and keybindings.
 *
 * Features:
 *   - Single JSON file: settings.json in app data directory
 *   - Versioned with automatic migration from old SharedPreferences
 *   - Export/import as JSON (like VS Code's settings.json)
 *   - Schema-validated: unknown keys are ignored, missing keys use defaults
 *   - Compose MutableState integration: every setting has a live state
 *   - Debounced save: writes are batched to avoid disk thrashing on rapid changes
 *
 * Architecture reference: VS Code's ConfigurationService (MIT).
 */
object JsonSettingsStore {

    private const val TAG = "JsonSettingsStore"
    private const val FILE_NAME = "settings.json"

    private lateinit var context: Context
    private val settingsState = mutableMapOf<String, MutableState<Any?>>()
    private val toggleState = mutableMapOf<String, MutableState<Boolean>>()
    private val keybindingOverrides = mutableMapOf<String, String>()

    private var version: Int = SettingsSchema.CURRENT_VERSION
    private var initialized = false
    private var savePending = false

    // SK02 surfacing (P0): Compose-observable write-health state so the UI can react to
    // disk failures instead of assuming success. importJson's typed Boolean remains the
    // in-codebase model; these two states extend the same honesty to load/save paths.
    val writeFailed = mutableStateOf(false)
    val lastLoadQuarantined = mutableStateOf(false)

    /**
     * Initialize the store. Reads the JSON file if it exists, or runs
     * migration from SharedPreferences on first launch.
     */
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        context = ctx.applicationContext

        val file = File(context.filesDir, FILE_NAME)

        if (file.exists()) {
            loadFromJson(file)
        } else {
            // Try migration from old SharedPreferences
            val migrated = SettingsMigration.migrateIfNeeded(context)
            if (migrated != null) {
                applyMigratedData(migrated)
                // SK13: the migrated flag is written only after the JSON save succeeds.
                if (saveToJson()) SettingsMigration.markMigrated(context)
            } else {
                // Fresh install — just use defaults
                initDefaults()
                saveToJson()
            }
        }

        Log.i(TAG, "Initialized: ${settingsState.size} settings, ${toggleState.size} toggles, ${keybindingOverrides.size} keybinding overrides")
    }

    /**
     * Load settings from the JSON file.
     */
    private fun loadFromJson(file: File) {
        try {
            val json = JSONObject(file.readText())
            version = json.optInt("version", SettingsSchema.CURRENT_VERSION)

            // Load settings
            val settingsObj = json.optJSONObject("settings") ?: JSONObject()
            for (def in SettingsSchema.all) {
                val value = settingsObj.opt(def.key) ?: SettingsSchema.defaultValue(def.key)
                settingsState[def.key] = mutableStateOf(value)
            }

            // Load toggles
            val togglesObj = json.optJSONObject("toggles") ?: JSONObject()
            for (toggle in SettingsSchema.featureToggles) {
                val value = togglesObj.optBoolean(toggle.key, toggle.default)
                toggleState[toggle.key] = mutableStateOf(value)
            }

            // Load keybinding overrides
            val kbObj = json.optJSONObject("keybindings") ?: JSONObject()
            for (key in kbObj.keys()) {
                keybindingOverrides[key] = kbObj.getString(key)
            }

            // Run migration check if version is older
            if (version < SettingsSchema.CURRENT_VERSION) {
                migrateVersion(version)
                saveToJson()
            }

            // Also run SharedPreferences migration if not yet done
            val migrated = SettingsMigration.migrateIfNeeded(context)
            if (migrated != null) {
                applyMigratedData(migrated)
                // SK13: mark migrated only when the JSON save succeeded.
                if (saveToJson()) SettingsMigration.markMigrated(context)
            }
        } catch (e: Exception) {
            // SK01 (P0): the old behavior quarantined NOTHING — initDefaults + saveToJson
            // OVERWROTE the corrupt file, silently and irrecoverably resetting ALL settings.
            // Now: quarantine the corrupt file first so recovery is possible, record it in
            // observable state, THEN fall back to defaults.
            Log.e(TAG, "Failed to load settings JSON — quarantining corrupt file, falling back to defaults", e)
            quarantineCorruptFile(file)
            lastLoadQuarantined.value = true
            initDefaults()
            saveToJson()
        }
    }

    /**
     * SK01 (P0): copy the corrupt settings file aside before defaults overwrite it.
     * Keeps exactly one quarantine (latest corrupt state) to avoid accumulation.
     * The file at filesDir/settings.json.corrupt is the recovery point for the user.
     */
    private fun quarantineCorruptFile(file: File) {
        try {
            val quarantine = File(context.filesDir, "$FILE_NAME.corrupt")
            file.copyTo(quarantine, overwrite = true)
            Log.w(TAG, "Quarantined corrupt settings to ${quarantine.absolutePath}")
        } catch (q: Exception) {
            Log.e(TAG, "Failed to quarantine corrupt settings file (proceeding with defaults)", q)
        }
    }

    /**
     * Initialize all settings with their default values.
     */
    private fun initDefaults() {
        for (def in SettingsSchema.all) {
            settingsState[def.key] = mutableStateOf(SettingsSchema.defaultValue(def.key))
        }
        for (toggle in SettingsSchema.featureToggles) {
            toggleState[toggle.key] = mutableStateOf(toggle.default)
        }
    }

    /**
     * Apply migrated data from old SharedPreferences.
     */
    private fun applyMigratedData(data: SettingsMigration.MigratedData) {
        // Ensure defaults are initialized first
        if (settingsState.isEmpty()) initDefaults()

        for ((key, value) in data.settings) {
            settingsState[key]?.value = value
        }
        for ((key, value) in data.toggles) {
            toggleState[key]?.value = value
        }
        for ((key, value) in data.keybindings) {
            keybindingOverrides[key] = value
        }
    }

    /**
     * Save all settings to the JSON file.
     * SK01 (P0): atomic write (tmp + rename) — a truncated/partial write can no longer
     * leave a corrupt settings.json behind. SK02 (P0): typed Boolean result instead of
     * swallowing; failures also set the observable writeFailed state.
     */
    @Synchronized
    fun saveToJson(): Boolean {
        return try {
            val json = JSONObject()
            json.put("version", SettingsSchema.CURRENT_VERSION)

            val settingsObj = JSONObject()
            for ((key, state) in settingsState) {
                settingsObj.putOpt(key, state.value)
            }
            json.put("settings", settingsObj)

            val togglesObj = JSONObject()
            for ((key, state) in toggleState) {
                togglesObj.put(key, state.value)
            }
            json.put("toggles", togglesObj)

            val kbObj = JSONObject()
            for ((key, value) in keybindingOverrides) {
                kbObj.put(key, value)
            }
            json.put("keybindings", kbObj)

            val file = File(context.filesDir, FILE_NAME)
            val ok = atomicWrite(file, json.toString(2))
            writeFailed.value = !ok
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings JSON", e)
            writeFailed.value = true
            false
        }
    }

    /**
     * SK01 (P0): atomic write — write to a sibling .tmp file, then rename over the
     * target. Rename within the same directory is atomic on POSIX, so settings.json is
     * either the OLD complete file or the NEW complete file, never a truncated mix.
     * Defensive copy+delete fallback for vendor-kernel rename quirks (this device
     * family has a documented history of syscall surprises) — the fallback is NOT
     * atomic, but the corrupt-parse quarantine in loadFromJson covers its failure mode.
     */
    private fun atomicWrite(file: File, content: String): Boolean {
        // context.filesDir (non-null) instead of file.parentFile (File?) — same directory.
        val tmp = File(context.filesDir, file.name + ".tmp")
        tmp.writeText(content)
        if (tmp.renameTo(file)) return true
        return try {
            Log.w(TAG, "Rename failed for ${file.name} — falling back to copy+delete")
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy fallback also failed for ${file.name}", e)
            false
        }
    }

    /**
     * Export settings as a JSON string (for the settings.json editor UI).
     */
    fun exportJson(): String {
        val json = JSONObject()
        json.put("version", SettingsSchema.CURRENT_VERSION)

        val settingsObj = JSONObject()
        for ((key, state) in settingsState) {
            settingsObj.putOpt(key, state.value)
        }
        json.put("settings", settingsObj)

        val togglesObj = JSONObject()
        for ((key, state) in toggleState) {
            togglesObj.put(key, state.value)
        }
        json.put("toggles", togglesObj)

        val kbObj = JSONObject()
        for ((key, value) in keybindingOverrides) {
            kbObj.put(key, value)
        }
        json.put("keybindings", kbObj)

        return json.toString(2)
    }

    /**
     * Import settings from a JSON string (from the settings.json editor UI).
     * Validates against the schema — unknown keys are ignored.
     */
    fun importJson(jsonStr: String): Boolean {
        try {
            val json = JSONObject(jsonStr)
            val settingsObj = json.optJSONObject("settings") ?: JSONObject()
            for (def in SettingsSchema.all) {
                if (settingsObj.has(def.key)) {
                    settingsState[def.key]?.value = settingsObj.get(def.key)
                }
            }
            val togglesObj = json.optJSONObject("toggles") ?: JSONObject()
            for (toggle in SettingsSchema.featureToggles) {
                if (togglesObj.has(toggle.key)) {
                    toggleState[toggle.key]?.value = togglesObj.getBoolean(toggle.key)
                }
            }
            val kbObj = json.optJSONObject("keybindings") ?: JSONObject()
            keybindingOverrides.clear()
            for (key in kbObj.keys()) {
                keybindingOverrides[key] = kbObj.getString(key)
            }
            saveToJson()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings JSON", e)
            return false
        }
    }

    // ── Settings accessors ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    fun <T> getState(key: String): MutableState<T> {
        @Suppress("UNCHECKED_CAST")
        return settingsState[key] as? MutableState<T> ?: throw IllegalArgumentException("Unknown setting: $key")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(key: String): T {
        return settingsState[key]?.value as T
    }

    fun <T> setValue(key: String, value: T) {
        settingsState[key]?.value = value
        saveDebounced()
    }

    // ── Feature toggle accessors ──────────────────────────────────────

    fun getToggleState(key: String): MutableState<Boolean> {
        return toggleState[key] ?: throw IllegalArgumentException("Unknown toggle: $key")
    }

    fun getToggle(key: String): Boolean = toggleState[key]?.value ?: true

    fun setToggle(key: String, value: Boolean) {
        toggleState[key]?.value = value
        saveDebounced()
    }

    // ── Keybinding accessors ───────────────────────────────────────────

    fun getKeybindingOverrides(): Map<String, String> = keybindingOverrides.toMap()

    fun setKeybinding(action: String, serialized: String) {
        keybindingOverrides[action] = serialized
        saveDebounced()
    }

    fun removeKeybinding(action: String) {
        keybindingOverrides.remove(action)
        saveDebounced()
    }

    fun clearKeybindings() {
        keybindingOverrides.clear()
        saveDebounced()
    }

    // ── Debounced save ─────────────────────────────────────────────────

    private var saveRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun saveDebounced() {
        if (savePending) return
        savePending = true
        handler.postDelayed({
            savePending = false
            saveToJson()
        }, 500)
    }

    /**
     * Force immediate save (e.g., on app exit).
     */
    fun flush(): Boolean {
        savePending = false
        handler.removeCallbacksAndMessages(null)
        return saveToJson()
    }

    /**
     * Reset all settings to defaults.
     */
    fun resetToDefaults() {
        initDefaults()
        keybindingOverrides.clear()
        saveToJson()
    }

    /**
     * Handle version-specific migrations within the JSON format.
     */
    private fun migrateVersion(oldVersion: Int) {
        Log.i(TAG, "Migrating settings from v$oldVersion to v${SettingsSchema.CURRENT_VERSION}")
        // Currently no version-specific migrations needed (v1 is the first)
        version = SettingsSchema.CURRENT_VERSION
    }
}
