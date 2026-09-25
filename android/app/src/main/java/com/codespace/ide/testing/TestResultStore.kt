package com.codespace.ide.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * F3 (F-TRACK TG03): flat result store keyed by TestId (VS Code
 * testResultService's map shape). Latest outcome per test wins; a newer run
 * marks same-file items it did not cover as RETIRED (dimmed in F4's tree,
 * no gutter glyph). F4's TestStore (discovery) composes with this — same
 * TestId keys.
 */
object TestResultStore {

    private val _items = MutableStateFlow<Map<String, TestResultItem>>(emptyMap())
    val items: StateFlow<Map<String, TestResultItem>> = _items.asStateFlow()

    private val _latestRun = MutableStateFlow<TestRun?>(null)
    val latestRun: StateFlow<TestRun?> = _latestRun.asStateFlow()

    /** Records one test's outcome (latest wins per TestId). */
    fun record(item: TestResultItem) {
        _items.update { it + (item.testId to item) }
    }

    /** Records a run summary (F4's tree header rollup consumes it). */
    fun recordRun(run: TestRun) {
        _latestRun.value = run
    }

    /**
     * Retires every recorded item under this FILE that the just-finished run
     * did NOT cover — the run's own ids stay live. (VS Code retired semantics.)
     */
    fun retireMissing(filePath: String, liveIds: Collection<String>) {
        val prefix = filePath.trimEnd('/') + "/"
        _items.update { current ->
            current.mapValues { (id, item) ->
                if (id.startsWith(prefix) && id !in liveIds) {
                    item.copy(retired = true, computedState = TestResultState.RETIRED)
                } else item
            }
        }
    }

    /** Clears all results for a file (tab close is NOT a clear — file delete is). */
    fun clearFile(filePath: String) {
        val prefix = filePath.trimEnd('/') + "/"
        _items.update { current -> current.filterKeys { !it.startsWith(prefix) } }
    }

    /**
     * Gutter decoration input: 0-based line → state for THIS file's tests with
     * a known line. Only non-retired items with a line produce a glyph.
     */
    fun statesForFile(filePath: String?): Map<Int, TestResultState> {
        if (filePath.isNullOrBlank()) return emptyMap()
        val prefix = filePath.trimEnd('/') + "/"
        return _items.value.entries
            .filter { it.key.startsWith(prefix) }
            .filter { !it.value.retired && it.value.lineIndex >= 0 }
            .associate { it.value.lineIndex to it.value.computedState }
    }
}
