package com.codespace.ide.testing

import com.codespace.ide.domain.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * F4 (F-TRACK TG04): flat discovered-test store keyed by TestId — VS Code's
 * testExplorerView shape: ONE flat map, the tree is a PROJECTION over TestId
 * prefixes (no nested object model). TestIds match the F1 lenses and the F3
 * result store exactly, so live states and gutter glyphs stay consistent.
 */
object TestStore {

    /** One discovered test target (a leaf test or a suite container). */
    data class DiscoveredTest(
        /** F1 TestId: "<hostFilePath>/<chain>/<name>" — same identity as F3 results. */
        val testId: String,
        /** Host absolute file path (the TestId's first segment). */
        val filePath: String,
        /** 0-based declaration line. */
        val lineIndex: Int,
        /** True for suite containers (@Nested class, describe, pytest class). */
        val suite: Boolean,
        val language: Language,
    )

    private val _items = MutableStateFlow<Map<String, DiscoveredTest>>(emptyMap())
    val items: StateFlow<Map<String, DiscoveredTest>> = _items.asStateFlow()

    /** Swap the whole file's items after a discovery pass (atomic per file). */
    fun replaceFile(filePath: String, fileItems: List<DiscoveredTest>) {
        val prefix = filePath.trimEnd('/') + "/"
        _items.update { current ->
            val without = current.filterKeys { !it.startsWith(prefix) }
            without + fileItems.associateBy { it.testId }
        }
    }

    /** Remove a file's items (file deleted or no longer a candidate). */
    fun removeFile(filePath: String) {
        val prefix = filePath.trimEnd('/') + "/"
        _items.update { current -> current.filterKeys { !it.startsWith(prefix) } }
    }

    /** Clear everything (project switch). */
    fun clear() {
        _items.value = emptyMap()
    }

    /** Leaf (non-suite) items in discovery order — run-all input. */
    fun leafItems(): List<DiscoveredTest> =
        _items.value.values.filter { !it.suite }.sortedWith(
            compareBy({ it.filePath }, { it.lineIndex })
        )
}
