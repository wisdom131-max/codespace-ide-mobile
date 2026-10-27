package com.codespace.ide.spike

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VN Spike — native-vs-WebView chat UI spike, harness v2 (advisor ruling
 * 2026-10-27): FrameMetrics per-frame durations, refresh rate reported,
 * TOTAL memory (own + related renderer/GPU pids + device availMem),
 * real keystroke->frame input latency or NOT MEASURED, A alone then B
 * alone, honest cold start, NOT ENOUGH DATA verdicts on missing metrics.
 */
class SpikeActivity : ComponentActivity() {

    private var coldStartMs: Long? = null
    private val tCreate = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpikeScreen(benchHolder = remember { BenchHolder(this@SpikeActivity) },
                            scope = rememberCoroutineScope())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && coldStartMs == null) {
            coldStartMs = SystemClock.elapsedRealtime() - tCreate
        }
    }

    fun coldStart(): Long? = coldStartMs
}

/** Owns the collectors; one instance per activity. */
class BenchHolder(val activity: android.app.Activity) {
    val frames = FrameStatsCollector(activity)
    val mem = MemBench(activity)
    val input = InputLatencyBench()
}

@Composable
fun SpikeScreen(benchHolder: BenchHolder, scope: CoroutineScope) {
    val messages: SnapshotStateList<SpikeMsg> = remember { mutableStateListOf() }
    var phase by remember { mutableStateOf("Idle — tap Run") }
    var results by remember { mutableStateOf(
        "Tap Run to measure both phases. TYPE in the input box during BOTH\n" +
        "phases so input latency is measured; then select + paste this block back.") }
    var running by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(SpikeTokens.bg)
            .statusBarsPadding()) {

        SpikeHeader(phase = phase, running = running, onRun = {
            if (!running) {
                running = true
                scope.launch(Dispatchers.Main) {
                    runBenchmark(benchHolder, messages,
                        setPhase = { phase = it },
                        setResults = { results = it },
                        setDone = { running = false })
                }
            }
        })

        Row(Modifier.fillMaxWidth().weight(1f)) {
            NativeChatPane(messages, Modifier.weight(1f).fillMaxHeight())
            WebViewChatPane(Modifier.weight(1f).fillMaxHeight())
        }

        NativeInputBox(value = input, onValueChange = { input = it },
                       onKey = { benchHolder.input.onKeyDown() })

        ResultsPanel(results)
    }
}

@Composable
private fun SpikeHeader(phase: String, running: Boolean, onRun: () -> Unit) {
    Row(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(phase, fontSize = 13.sp, modifier = Modifier.weight(1f),
             color = SpikeTokens.textPrimary)
        Button(onClick = onRun, enabled = !running) { Text(if (running) "Running…" else "Run") }
    }
}

@Composable
private fun NativeInputBox(value: String, onValueChange: (String) -> Unit, onKey: () -> Unit) {
    Row(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onKey() // keystroke -> next frame: input latency sample
                onValueChange(it)
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type during both phases (input latency)", fontSize = 12.sp) },
            singleLine = true
        )
    }
}

@Composable
private fun ResultsPanel(text: String) {
    Column(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV)
            .heightIn(max = 210.dp)
            .verticalScroll(rememberScrollState())) {
        Text("Results — select + copy, paste back", fontSize = 12.sp, color = SpikeTokens.textSecondary)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                 color = SpikeTokens.textPrimary)
        }
    }
}

/** One phase's frame stats, frozen at phase end (the collector resets per phase). */
private data class FrameSummary(
    val p50Ms: Double?, val p95Ms: Double?, val jankPct: Double?, val dropped: Long, val frames: Int,
)

/**
 * Runs A ALONE then B ALONE — FrameMetrics are window-wide, so only one pane
 * streams at a time and the other stays idle. Memory snapshots wrap each
 * phase (own + related pids + device availMem).
 */
private suspend fun runBenchmark(
    benchHolder: BenchHolder,
    messages: SnapshotStateList<SpikeMsg>,
    setPhase: (String) -> Unit,
    setResults: (String) -> Unit,
    setDone: () -> Unit,
) {
    val stamp = "Build 2 (${BuildConfig.GIT_HASH}) — v${BuildConfig.VERSION_NAME}" +
        " — CI run ${BuildConfig.CI_RUN_NUMBER} @ ${BuildConfig.BUILD_TIME}"

    // ── Phase A: native pane only ───────────────────────────────────────
    setPhase("Phase A: native streaming — type in the box…")
    val memBeforeA = benchHolder.mem.snapshot()
    benchHolder.frames.start()
    streamNative(messages)
    benchHolder.frames.stop()
    val memAfterA = benchHolder.mem.snapshot()
    val statsA = FrameSummary(
        benchHolder.frames.p50Ms(), benchHolder.frames.p95Ms(),
        benchHolder.frames.jankPct(), benchHolder.frames.droppedCount(),
        benchHolder.frames.frameCount)
    val inputA = benchHolder.input.medianMs()

    delay(1500)
    setPhase("Phase B: WebView streaming — keep typing…")

    // ── Phase B: WebView pane only (native list stays idle) ────────────
    val web = SpikeWeb.bridge
    if (web == null) {
        setPhase("WebView not ready — tap Run again in a moment.")
        return
    }
    val memBeforeB = benchHolder.mem.snapshot()
    benchHolder.frames.start()
    web.clearAll()
    streamWeb(web)
    benchHolder.frames.stop()
    val memAfterB = benchHolder.mem.snapshot()
    val statsB = FrameSummary(
        benchHolder.frames.p50Ms(), benchHolder.frames.p95Ms(),
        benchHolder.frames.jankPct(), benchHolder.frames.droppedCount(),
        benchHolder.frames.frameCount)
    val inputB = benchHolder.input.medianMs()

    val a = PaneMetrics("A", statsA.p50Ms, statsA.p95Ms, statsA.jankPct, statsA.dropped,
        memAfterA.capturedTotalKb(), memAfterA.hasUncaptured(), memAfterA.capturedNames(),
        memBeforeA.availMemKb, memAfterA.availMemKb, inputA)
    val b = PaneMetrics("B", statsB.p50Ms, statsB.p95Ms, statsB.jankPct, statsB.dropped,
        memAfterB.capturedTotalKb(), memAfterB.hasUncaptured(), memAfterB.capturedNames(),
        memBeforeB.availMemKb, memAfterB.availMemKb, inputB)

    val block = ResultsBlock.build(
        (benchHolder.activity as SpikeActivity).coldStart(), a, b,
        benchHolder.frames.refreshHz, benchHolder.mem.apkInstalledBytes(),
        stamp, statsA.frames, statsB.frames)
    setResults(block)
    setPhase("Done — select + paste the results block back.")
    setDone()
}

/** Streams the corpus into the native pane at 30 deltas/s (phase A). */
private suspend fun streamNative(messages: SnapshotStateList<SpikeMsg>) {
    val corpus = listOf(
        SpikeMsg(1, "assistant", SpikeCorpus.MSG1),
        SpikeMsg(2, "error", SpikeCorpus.MSG2),
        SpikeMsg(3, "assistant", SpikeCorpus.MSG3)
    )
    for (m in corpus) {
        messages.add(SpikeMsg(m.id, m.role, ""))
        val deltas = SpikeCorpus.deltas(m.text)
        for (d in deltas) {
            val idx = messages.indexOfFirst { it.id == m.id }
            if (idx >= 0) messages[idx] = messages[idx].copy(text = messages[idx].text + d)
            delay(SpikeCorpus.DELAY_MS)
        }
    }
}

/** Streams the same corpus into the WebView through the typed bridge (phase B). */
private suspend fun streamWeb(bridge: SpikeBridge) {
    val corpus = listOf(
        1 to SpikeCorpus.MSG1, 2 to SpikeCorpus.MSG2, 3 to SpikeCorpus.MSG3
    )
    for ((id, text) in corpus) {
        bridge.startMessage(id, if (id == 2) "error" else "assistant")
        val deltas = SpikeCorpus.deltas(text)
        for (d in deltas) {
            bridge.delta(id, d)
            delay(SpikeCorpus.DELAY_MS)
        }
        bridge.doneMessage(id)
    }
}
