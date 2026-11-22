package com.codespace.ide.engine

class FeatureFlags(private val values: Map<String, Boolean> = emptyMap()) {
    fun isOn(name: String): Boolean = values[name] ?: false
    fun with(name: String, on: Boolean): FeatureFlags = FeatureFlags(values + (name to on))
    fun enabled(): List<String> = values.filterValues { it }.keys.sorted()
    companion object { val KNOWN = listOf("native_tools", "prompt_cache", "memory_decay", "hybrid_search", "self_critique") }
}
