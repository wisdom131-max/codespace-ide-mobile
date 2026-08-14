package com.codespace.ide.debug

import com.codespace.ide.domain.Language

/**
 * P27-9: Debug configuration — replaces the cosmetic config dropdown with real data.
 * Stored in project settings. Default: auto-derive from active file extension.
 */
data class DebugConfiguration(
    val name: String,
    val type: String,           // "python", "node", "terminal"
    val program: String? = null, // null = active file
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val runtime: String? = null,
) {
    companion object {
        /**
         * Auto-derive a debug configuration from a file path.
         * Returns null for non-debuggable file types.
         */
        fun fromFilePath(filePath: String): DebugConfiguration? {
            val language = Language.fromPath(filePath)
            return when (language) {
                Language.PYTHON -> DebugConfiguration(
                    name = "Python: ${filePath.substringAfterLast('/')}",
                    type = "python",
                    program = filePath,
                )
                Language.JAVASCRIPT, Language.TYPESCRIPT -> DebugConfiguration(
                    name = "Node: ${filePath.substringAfterLast('/')}",
                    type = "node",
                    program = filePath,
                )
                Language.SHELL -> DebugConfiguration(
                    name = "Shell: ${filePath.substringAfterLast('/')}",
                    type = "terminal",
                    program = filePath,
                )
                else -> null
        }
        }

        /** Default configs shown in the dropdown when no file is open. */
        val defaults = listOf(
            DebugConfiguration(name = "Python: Current File", type = "python"),
            DebugConfiguration(name = "Node: Current File", type = "node"),
            DebugConfiguration(name = "Shell: Current File", type = "terminal"),
        )
    }
}
