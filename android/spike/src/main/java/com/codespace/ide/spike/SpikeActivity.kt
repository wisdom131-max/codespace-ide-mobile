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
 * VN Spike — native-vs-WebView chat UI spike (advisor order 2026-10-27).
 * Standalone app: installs beside com.codespace.ide, touches none of its
 * data, safe to uninstall. One screen, two panes, one Run button.
 */
class SpikeActivity : ComponentActivity() {

    private var coldStartMs = 0L
    private val tCreate = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpikeScreen(bench = remember { Bench(this) },
                            scope = rememberCoroutineScope(),
                            coldStart = { coldStartMs })
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && coldStartMs == 0L) {
            coldStartMs = SystemClock.elapsedRealtime() - tCreate
        }
    }
}

@Composable
fun SpikeScreen(bench: Bench, scope: CoroutineScope, coldStart: () -> Long) {
    val messages: SnapshotStateList<SpikeMsg> = remember { mutableStateListOf() }
    var results by remember { mutableStateOf("Tap Run to measure both panes (about 40 s).") }
    var running by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val inputLatencies = remember { mutableListOf<Double>() }
    val lastKeyAt = remember { mutableStateOf(0L) }

    Column(Modifier.fillMaxSize().background(SpikeTokens.bg)
            .statusBarsPadding()) {

        SpikeHeader(running = running, onRun = {
            if (!running) scope.launch(Dispatchers.Main) { runBenchmark(bench, messages, { results = it }, { running = it }) }
        })

        Row(Modifier.fillMaxWidth().weight(1f)) {
            NativeChatPane(messages, Modifier.weight(1f).fillMaxHeight())
            WebViewChatPane(Modifier.weight(1f).fillMaxHeight())
        }

        NativeInputBox(value = input, onValueChange = {
            input = it
            lastKeyAt.value = SystemClock.elapsedRealtime()
            bench.markInput { deltaMs -> inputLatencies.add(deltaMs) }
        }, onSend = { /* the spike sends nothing; input is for latency only */ })

        ResultsPanel(results, coldStart())
    }
}

@Composable
private fun SpikeHeader(running: Boolean, onRun: () -> Unit) {
    Row(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VN Spike — A native · B WebView",
             fontSize = 13.sp, modifier = Modifier.weight(1f),
             color = SpikeTokens.textPrimary)
        Button(onClick = onRun, enabled = !running) { Text(if (running) "Running…" else "Run") }
    }
}

@Composable
private fun NativeInputBox(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type here — input is native in both panes", fontSize = 12.sp) },
            singleLine = true
        )
        Button(onClick = onSend) { Text("Send") }
    }
}

@Composable
private fun ResultsPanel(text: String, coldStartMs: Long) {
    Column(Modifier.fillMaxWidth()
            .background(SpikeTokens.surface, RoundedCornerShape(SpikeTokens.cornerPanel))
            .padding(horizontal = SpikeTokens.padH, vertical = SpikeTokens.padV)
            .heightIn(max = 190.dp)
            .verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Results (select + copy)", fontSize = 12.sp, color = SpikeTokens.textSecondary)
            Text("cold start ${coldStartMs}ms", fontSize = 12.sp, color = SpikeTokens.textSecondary)
        }
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                 color = SpikeTokens.textPrimary)
        }
    }
}

/**
 * The benchmark: same corpus, 30 deltas/s, into A then B. Frame stats and
 * PSS are sampled per pane; input latency is the median keystroke-to-frame
 * delta collected while typing in the shared native input.
 */
private suspend fun runBenchmark(
    bench: Bench,
    messages: SnapshotStateList<SpikeMsg>,
    setResults: (String) -> Unit,
    setRunning: (Boolean) -> Unit,
) {
    setRunning(true)
    val stamp = "Build 1 (${BuildConfig.GIT_HASH}) — v${BuildConfig.VERSION_NAME}" +
        " — CI run ${BuildConfig.CI_RUN_NUMBER} @ ${BuildConfig.BUILD_TIME}"

    // ── Pane A: native ──────────────────────────────────────────────
    val pssBeforeA = bench.pssKb()
    bench.startSampling()
    messages.clear()
    streamNative(messages)
    val statsA = bench.frameStats()
    bench.stopSampling()
    val pssA = (bench.pssKb() - pssBeforeA) / 1024.0
    delay(1200)

    // ── Pane B: WebView ─────────────────────────────────────────────
    val web = SpikeWeb.bridge ?: run {
        setResults("WebView not ready yet — tap Run again in a moment.")
        setRunning(false)
        return
    }
    val pssBeforeB = bench.pssKb()
    bench.startSampling()
    web.clearAll()
    streamWeb(web)
    val statsB = bench.frameStats()
    bench.stopSampling()
    val pssB = (bench.pssKb() - pssBeforeB) / 1024.0

    val a = PaneResult("A", statsA.first, statsA.second, statsA.third, pssA, 0.0)
    val b = PaneResult("B", statsB.first, statsB.second, statsB.third, pssB, 0.0)
    setResults(ResultsBlock.build(0L, a, b, bench.apkBytes(), stamp))
    setRunning(false)
}

/** Streams the corpus into the native pane at 30 deltas/s. */
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

/** Streams the same corpus into the WebView through the typed bridge. */
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
