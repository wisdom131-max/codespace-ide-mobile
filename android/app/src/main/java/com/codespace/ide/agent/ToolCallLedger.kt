package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Area 5 - WHAT DID THIS TOOL CALL ACTUALLY DO?
 *
 * One record per tool-call id, persisted, so a duplicate id after a retry, a restored session or
 * a process death cannot re-apply a side effect. Bounded: the oldest records fall off at
 * [MAX_ENTRIES], which is enough to cover a conversation's live turn without growing the file
 * forever.
 *
 * The ledger never decides policy. It reports state; [com.codespace.ide.chat.panel.ToolRetry]
 * decides what that state permits.
 */
class ToolCallLedger private constructor(private val file: File) {

    data class Entry(
        val callId: String,
        val tool: String,
        val effect: ToolEffect,
        val outcome: ToolOutcome,
        /** Bounded tail of the result, kept so REUSE can return something real. */
        val result: String?,
        val updatedAt: Long,
        /** Arguments the call carried, so a reconciler can inspect what was asked for. */
        val args: Map<String, String> = emptyMap(),
    )

    private val entries = LinkedHashMap<String, Entry>()

    init {
        load()
    }

    @Synchronized
    fun lookup(callId: String): Entry? = entries[callId]

    @Synchronized
    fun recordStarted(callId: String, tool: String, effect: ToolEffect, args: Map<String, String> = emptyMap()) {
        // Started but no verdict yet: exactly the state that must never be silently retried.
        // Audit 2026-11-12: long argument values are fingerprinted, not kept whole - a written file
        // body is the whole file, and 200 of those on a phone is memory spent on one comparison.
        put(Entry(callId, tool, effect, ToolOutcome.UNKNOWN_AFTER_START, null,
            System.currentTimeMillis(), ArgFingerprint.stamp(args)))
    }

    /** Proven already applied by a read-only inspection: the call is complete, not retried. */
    @Synchronized
    fun recordReconciled(callId: String, tool: String, effect: ToolEffect, evidence: String) {
        val prev = entries[callId]
        put(Entry(callId, tool, effect, ToolOutcome.COMPLETED_OK, evidence.takeLast(RESULT_CAP),
            System.currentTimeMillis(), prev?.args ?: emptyMap()))
    }

    /**
     * 2026-11-12 (found by `AgentToolsDispatchTest`): the terminal records used to REPLACE the entry
     * without its arguments, so by the time an outcome was uncertain the reconciler had nothing to
     * inspect and every recoverable write degraded to a refusal. The arguments travel with the
     * outcome now; they are the evidence a reconciler needs.
     */
    @Synchronized
    fun recordCompleted(callId: String, tool: String, effect: ToolEffect, result: String) {
        put(Entry(callId, tool, effect, ToolOutcome.COMPLETED_OK, result.takeLast(RESULT_CAP),
            System.currentTimeMillis(), entries[callId]?.args ?: emptyMap()))
    }

    @Synchronized
    fun recordFailed(callId: String, tool: String, effect: ToolEffect, outcome: ToolOutcome) {
        put(Entry(callId, tool, effect, outcome, null, System.currentTimeMillis(),
            entries[callId]?.args ?: emptyMap()))
    }

    private fun put(e: Entry) {
        entries[e.callId] = e
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("callId").takeIf { it.isNotBlank() } ?: continue
                entries[id] = Entry(
                    callId = id,
                    tool = o.optString("tool"),
                    effect = if (o.optString("effect") == "READ_ONLY") ToolEffect.READ_ONLY else ToolEffect.SIDE_EFFECTING,
                    outcome = runCatching { ToolOutcome.valueOf(o.optString("outcome")) }
                        .getOrDefault(ToolOutcome.UNKNOWN_AFTER_START),
                    result = o.optString("result").takeIf { it.isNotEmpty() },
                    updatedAt = o.optLong("updatedAt"),
                    args = o.optJSONObject("args")?.let { a ->
                        buildMap { a.keys().forEach { k -> put(k, a.optString(k)) } }
                    } ?: emptyMap(),
                )
            }
        } catch (_: Throwable) {
            // An unreadable ledger is treated as EMPTY, never as proof that nothing ran: a
            // duplicate call then starts from UNKNOWN_AFTER_START via recordStarted.
            entries.clear()
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            entries.values.forEach { e ->
                arr.put(JSONObject().apply {
                    put("callId", e.callId)
                    put("tool", e.tool)
                    put("effect", e.effect.name)
                    put("outcome", e.outcome.name)
                    put("result", e.result ?: "")
                    put("updatedAt", e.updatedAt)
                    if (e.args.isNotEmpty()) put("args", JSONObject().apply {
                        e.args.forEach { (k, v) -> put(k, v.take(2000)) }
                    })
                })
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // Losing the file must never fail a tool call; it only costs reuse.
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val RESULT_CAP = 4096

        @Volatile private var instance: ToolCallLedger? = null

        /** Test seam: a ledger on a plain file, no Android context. */
        internal fun forTest(file: File): ToolCallLedger = ToolCallLedger(file)

        fun get(context: Context): ToolCallLedger =
            instance ?: synchronized(this) {
                instance ?: ToolCallLedger(File(context.applicationContext.filesDir, "tool-call-ledger.json"))
                    .also { instance = it }
            }
    }
}
