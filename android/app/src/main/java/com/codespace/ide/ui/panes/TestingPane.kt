package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.testing.TestResultState
import com.codespace.ide.testing.TestResultStore
import com.codespace.ide.testing.TestResultStates
import com.codespace.ide.testing.TestRunManager
import com.codespace.ide.testing.TestStore
import kotlinx.coroutines.launch

/**
 * F4 (F-TRACK TG04): Testing pane — VS Code's testing view shape: a flat
 * TestId store (TestStore, discovery) + a TREE PROJECTION rendered here,
 * with live states from the F3 result store. Replaces the old P41-P
 * file-list Test Explorer whose "Run" button only switched to the Terminal
 * tab without running anything.
 *
 * Header: Refresh / Run All / Run Failed / Stop (while a batch runs).
 * Per-row: inline Run AND Debug gated by the capability bitset (supportsRun /
 * supportsDebug) — Debug (F5) starts a real UniversalDebugManager session.
 * Suite rows collapse their descendants; retired results dim.
 */
@Composable
fun TestingPane(
    context: Context,
    projectId: String,
    projectRootPath: String?,
    onOpenFileAtLine: (path: String, lineOneBased: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val storeItems by TestStore.items.collectAsState()
    val resultItems by TestResultStore.items.collectAsState()

    var scanning by remember { mutableStateOf(true) }
    var batchRunning by remember { mutableStateOf(false) }
    val batchCancelled = remember { mutableStateOf(false) }
    var collapsedSuites by remember { mutableStateOf(setOf<String>()) }

    // Project scan on open + on Refresh. The F1 detector is the shared core:
    // the tree shows exactly what the editor lenses would run.
    fun rescan() {
        scanning = true
        scope.launch {
            val rootFile = projectRootPath?.let { java.io.File(it) }
            com.codespace.ide.testing.TestDiscoveryService.scanProject(context, rootFile)
            scanning = false
        }
    }
    LaunchedEffect(projectId) { rescan() }

    fun startBatch(targets: List<TestRunManager.BatchTarget>) {
        if (batchRunning || targets.isEmpty()) return
        batchRunning = true
        batchCancelled.value = false
        scope.launch {
            TestRunManager.runBatch(context, projectRootPath, targets) { batchCancelled.value }
            batchRunning = false
        }
    }

    // ── Projection (pure): flat store + results -> visible rows ──────────────
    val rows = remember(storeItems, resultItems, collapsedSuites) {
        buildRows(storeItems.values.toList(), resultItems, collapsedSuites)
    }
    val fileCount = rows.count { it is TestRow.FileRow }
    val leafCount = storeItems.values.count { !it.suite }
    val failedCount = storeItems.values.count { !it.suite &&
        (resultItems[it.testId]?.computedState == TestResultState.FAILED ||
            resultItems[it.testId]?.computedState == TestResultState.ERRORED) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        // Header (mandatory UI rule: rounded surface, 12/10 padding)
        TestingPaneHeader(
            scanning = scanning,
            batchRunning = batchRunning,
            leafCount = leafCount,
            fileCount = fileCount,
            failedCount = failedCount,
            onRefresh = { rescan() },
            onRunAll = {
                startBatch(
                    storeItems.values.filter { !it.suite }.sortedWith(
                        compareBy({ it.filePath }, { it.lineIndex })
                    ).map {
                        TestRunManager.BatchTarget(it.testId, it.filePath, it.language, it.suite, it.lineIndex)
                    }
                )
            },
            onRunFailed = {
                // Re-run ONLY the actual failures from the last runs: live F3
                // results in FAILED/ERRORED, intersected with current discovery.
                startBatch(
                    storeItems.values.filter { !it.suite }
                        .filter {
                            val r = resultItems[it.testId]
                            r != null && !r.retired &&
                                (r.computedState == TestResultState.FAILED ||
                                    r.computedState == TestResultState.ERRORED)
                        }
                        .sortedWith(compareBy({ it.filePath }, { it.lineIndex }))
                        .map {
                            TestRunManager.BatchTarget(it.testId, it.filePath, it.language, it.suite, it.lineIndex)
                        }
                )
            },
            onStop = {
                batchCancelled.value = true
                TestRunManager.cancelRun()
            },
        )

        Spacer(Modifier.size(8.dp))

        when {
            scanning -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF569CD6))
                }
            }
            rows.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No tests discovered. Refresh scans the project for\n@Test, test_/it/describe, test_* and *_test files.",
                        color = Color(0xFF666666),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is TestRow.FileRow -> TestingFileRow(row)
                            is TestRow.TestLeafRow -> TestingTestRow(
                                row = row,
                                onRun = {
                                    scope.launch {
                                        TestRunManager.runTest(
                                            context, projectRootPath, row.filePath,
                                            row.language, row.testId, row.suite, row.lineIndex,
                                        )
                                    }
                                },
                                onDebug = {
                                    // F5 (TG07p1): real debug session via UniversalDebugManager.
                                    scope.launch {
                                        TestRunManager.debugTest(
                                            context, projectRootPath, row.filePath,
                                            row.language, row.testId, row.suite, row.lineIndex,
                                        )
                                    }
                                },
                                onOpen = { onOpenFileAtLine(row.filePath, row.lineIndex + 1) },
                                onToggle = {
                                    collapsedSuites = if (row.testId in collapsedSuites) {
                                        collapsedSuites - row.testId
                                    } else {
                                        collapsedSuites + row.testId
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Projection: flat TestId store -> visible rows (VS Code testExplorerView shape)
// ─────────────────────────────────────────────────────────────────────────────

/** One visible row of the tree projection. */
sealed class TestRow {
    abstract val key: String

    data class FileRow(
        override val key: String,
        val filePath: String,
        val relPath: String,
        val testCount: Int,
        val state: TestResultState?,
    ) : TestRow()

    data class TestLeafRow(
        override val key: String,
        val testId: String,
        val filePath: String,
        val label: String,
        val level: Int,
        val suite: Boolean,
        val hasChildren: Boolean,
        val collapsed: Boolean,
        val lineIndex: Int,
        val language: com.codespace.ide.domain.Language,
        val state: TestResultState?,
        val retired: Boolean,
    ) : TestRow()
}

private fun buildRows(
    items: List<TestStore.DiscoveredTest>,
    results: Map<String, com.codespace.ide.testing.TestResultItem>,
    collapsedSuites: Set<String>,
): List<TestRow> {
    val rows = mutableListOf<TestRow>()
    val byFile = items.groupBy { it.filePath }.toSortedMap()

    for ((filePath, fileItems) in byFile) {
        val sorted = fileItems.sortedBy { it.lineIndex }
        var fileState: TestResultState? = null
        for (item in sorted) {
            val r = results[item.testId]
            if (r != null) {
                fileState = if (fileState == null) r.computedState
                else TestResultStates.merge(fileState, r.computedState)
            }
        }
        val relPath = filePath.substringAfterLast('/')
        rows.add(
            TestRow.FileRow(
                key = filePath,
                filePath = filePath,
                relPath = relPath,
                testCount = sorted.size,
                state = fileState,
            )
        )

        for (item in sorted) {
            // Skip descendants of collapsed suites.
            val chainPrefix = item.testId
            var hidden = false
            for (suiteId in collapsedSuites) {
                if (chainPrefix.startsWith(suiteId + "/")) {
                    hidden = true
                    break
                }
            }
            if (hidden) continue

            val r = results[item.testId]
            val chain = item.testId.removePrefix(item.filePath.trimEnd('/') + "/")
            val segments = chain.split('/')
            val label = segments.lastOrNull() ?: item.testId
            val level = (segments.size - 1).coerceAtLeast(0)
            val hasChildren = items.any { it.testId != item.testId && it.testId.startsWith(item.testId + "/") }
            rows.add(
                TestRow.TestLeafRow(
                    key = item.testId,
                    testId = item.testId,
                    filePath = item.filePath,
                    label = label,
                    level = level,
                    suite = item.suite,
                    hasChildren = hasChildren,
                    collapsed = item.testId in collapsedSuites,
                    lineIndex = item.lineIndex,
                    language = item.language,
                    state = r?.computedState,
                    retired = r?.retired == true,
                )
            )
        }
    }
    return rows
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TestingPaneHeader(
    scanning: Boolean,
    batchRunning: Boolean,
    leafCount: Int,
    fileCount: Int,
    failedCount: Int,
    onRefresh: () -> Unit,
    onRunAll: () -> Unit,
    onRunFailed: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                if (batchRunning) "Running tests\u2026" else "$leafCount tests in $fileCount files" +
                    (if (failedCount > 0) " \u00b7 $failedCount failed" else ""),
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.size(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderAction("Refresh", enabled = !scanning, onClick = onRefresh)
                HeaderAction(
                    "Run All",
                    enabled = !batchRunning && leafCount > 0,
                    onClick = onRunAll,
                )
                HeaderAction(
                    "Run Failed ($failedCount)",
                    enabled = !batchRunning && failedCount > 0,
                    onClick = onRunFailed,
                )
                if (batchRunning) {
                    HeaderAction("Stop", enabled = true, onClick = onStop)
                }
            }
        }
    }
}

@Composable
private fun HeaderAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color(0xFF2A2D2E) else Color(0xFF1A1A1A),
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Text(
            label,
            color = if (enabled) Color(0xFF4EC9B0) else Color(0xFF555555),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TestingFileRow(row: TestRow.FileRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stateGlyph(row.state), color = stateColor(row.state), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(6.dp))
        Text(
            row.relPath,
            color = Color(0xFFD4D4D4),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "(${row.testCount})",
            color = Color(0xFF666666),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TestingTestRow(
    row: TestRow.TestLeafRow,
    onRun: () -> Unit,
    onDebug: () -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val runSupported = TestRunManager.supportsRun(row.language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (row.hasChildren) onToggle() else onOpen() }
            .padding(
                start = (12 + row.level * 12).dp,
                end = 4.dp,
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (row.hasChildren) {
            Text(
                if (row.collapsed) "\u25b8" else "\u25be",
                color = Color(0xFF888888),
                fontSize = 10.sp,
            )
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Text(
            stateGlyph(row.state, row.retired),
            color = stateColor(row.state),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            row.label,
            color = when {
                row.retired -> Color(0xFF555555)
                row.state == TestResultState.FAILED || row.state == TestResultState.ERRORED -> Color(0xFFE06C75)
                row.state == TestResultState.RUNNING -> Color(0xFF61AFEF)
                else -> Color(0xFFCCCCCC)
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Inline Run — capability-gated (a row must never promise a run it
        // cannot execute). Debug (F5) is gated the same way: only languages
        // whose test debugger is really wired through UniversalDebugManager.
        if (runSupported) {
            Text(
                "Run",
                color = Color(0xFF4EC9B0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color(0xFF2A2D2E), RoundedCornerShape(8.dp))
                    .clickable { onRun() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (TestRunManager.supportsDebug(row.language)) {
            Text(
                "Debug",
                color = Color(0xFF61AFEF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color(0xFF2A2D2E), RoundedCornerShape(8.dp))
                    .clickable { onDebug() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// State decoration (same palette as the F3 gutter glyph)
// ─────────────────────────────────────────────────────────────────────────────

private fun stateGlyph(state: TestResultState?, retired: Boolean = false): String = when {
    retired -> "\u2013"
    state == null -> "\u00b7"
    state == TestResultState.PASSED -> "\u2713"
    state == TestResultState.FAILED -> "\u2717"
    state == TestResultState.ERRORED -> "\u2717"
    state == TestResultState.RUNNING -> "\u25cf"
    state == TestResultState.QUEUED -> "\u25cb"
    state == TestResultState.SKIPPED -> "\u25cb"
    state == TestResultState.RETIRED -> "\u2013"
    else -> "\u00b7"
}

private fun stateColor(state: TestResultState?): Color = when (state) {
    TestResultState.PASSED -> Color(0xFF4EC9B0)
    TestResultState.FAILED, TestResultState.ERRORED -> Color(0xFFE06C75)
    TestResultState.RUNNING, TestResultState.QUEUED -> Color(0xFF61AFEF)
    TestResultState.SKIPPED, TestResultState.RETIRED -> Color(0xFF5C6370)
    null -> Color(0xFF666666)
    else -> Color(0xFF666666)
}
