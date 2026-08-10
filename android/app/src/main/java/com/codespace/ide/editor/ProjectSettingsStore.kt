package com.codespace.ide.editor

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * P-FLOW: Settings backing the "In-Project Settings" floating page (gear menu).
 * Persisted in SharedPreferences, mirrors the FeatureToggleStore pattern but
 * kept as a separate store since these settings govern AI Agent behavior
 * rather than editor rendering.
 */
enum class FlowMode { MANUAL, AUTO }

object ProjectSettingsStore {
    private const val PREFS = "project_settings"
    private lateinit var prefs: android.content.SharedPreferences

    /** AUTO = current/default behavior (tool calls execute immediately).
     *  MANUAL = each AI Agent tool call pauses for an Approve/Reject tap. */
    val flowMode: MutableState<FlowMode> = mutableStateOf(FlowMode.AUTO)

    /** Show full JSON args/results for each tool call in the Agent chat transcript
     *  instead of a compact one-line summary. */
    val verboseToolOutput: MutableState<Boolean> = mutableStateOf(false)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        flowMode.value = try {
            FlowMode.valueOf(prefs.getString("flow_mode", FlowMode.AUTO.name) ?: FlowMode.AUTO.name)
        } catch (_: Exception) { FlowMode.AUTO }
        verboseToolOutput.value = prefs.getBoolean("verbose_tool_output", false)
    }

    fun setFlowMode(mode: FlowMode) {
        flowMode.value = mode
        prefs.edit().putString("flow_mode", mode.name).apply()
    }

    fun setVerboseToolOutput(value: Boolean) {
        verboseToolOutput.value = value
        prefs.edit().putBoolean("verbose_tool_output", value).apply()
    }
}
