package com.codespace.ide.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * R7-PLAN (VS Code plan-review + todos parity): per-session structured plan.
 *
 * The AGENT tool `plan` writes the FULL step list each call (todo_write
 * semantics — the model re-sends the whole list with updated statuses):
 *   <tool>{"name":"plan","arguments":{"steps":[
 *     {"title":"Fix the parser","detail":"Off-by-one in Lexer.kt","status":"in_progress"},
 *     {"title":"Add tests","status":"pending"}]}}</tool>
 *
 * The tool loop STOPS the turn after a plan call so the user reviews the card
 * (ChatPlanCard) and taps Approve / Revise. After approval the agent executes
 * and keeps calling plan to mark progress (pending -> in_progress -> completed)
 * — the card doubles as the todo list. Persisted per session in prefs
 * ("plans_v1"), so plans survive restarts like sessions do.
 */
object ChatPlanStore {

    data class PlanStep(
        val title: String,
        val detail: String = "",
        val status: String = "pending", // pending | in_progress | completed
    )

    data class Plan(
        val steps: List<PlanStep> = emptyList(),
        val approved: Boolean = false,
    ) {
        val doneCount: Int get() = steps.count { it.status == "completed" }
        val allDone: Boolean get() = steps.isNotEmpty() && steps.all { it.status == "completed" }
    }

    /** Bumped on every mutation — read it in composition to recompose the card. */
    val revision = androidx.compose.runtime.mutableStateOf(0)

    /** Set by the chat panel (same registration as PendingChangesStore.activeSessionId). */
    @Volatile var activeSessionId: String? = null

    private const val PREFS = "copilot_chat"
    private const val KEY = "plans_v1"
    private val plans = HashMap<String, Plan>()
    private var loaded = false

    fun planFor(sessionId: String): Plan? {
        ensureLoaded()
        return plans[sessionId]
    }

    fun update(sessionId: String, steps: List<PlanStep>) {
        ensureLoaded()
        plans[sessionId] = Plan(steps, plans[sessionId]?.approved ?: false)
        bump()
    }

    fun setApproved(sessionId: String) {
        ensureLoaded()
        plans[sessionId] = (plans[sessionId] ?: Plan()).copy(approved = true)
        bump()
    }

    fun clear(sessionId: String) {
        ensureLoaded()
        plans.remove(sessionId)
        bump()
    }

    /** Tool-args adapter: {"steps":[{title,detail?,status?}]} -> store update. */
    fun updateFromArgs(stepsJson: JSONArray) {
        val sid = activeSessionId ?: "default"
        val steps = (0 until stepsJson.length()).mapNotNull { i ->
            try {
                val o = stepsJson.getJSONObject(i)
                PlanStep(
                    title = o.optString("title").ifBlank { o.optString("step") },
                    detail = o.optString("detail"),
                    status = normalizeStatus(o.optString("status", "pending")),
                )
            } catch (_: Exception) { null }
        }.filter { it.title.isNotBlank() }
        if (steps.isNotEmpty()) update(sid, steps)
    }

    private fun normalizeStatus(s: String): String = when (s.lowercase().trim()) {
        "in_progress", "inprogress", "active", "started" -> "in_progress"
        "completed", "complete", "done" -> "completed"
        else -> "pending"
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val ctx = appContext ?: return
            val str = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            val obj = JSONObject(str)
            obj.keys().forEach { sid ->
                val p = obj.getJSONObject(sid)
                val arr = p.optJSONArray("steps") ?: JSONArray()
                plans[sid] = Plan(
                    steps = (0 until arr.length()).mapNotNull { i ->
                        try {
                            val s = arr.getJSONObject(i)
                            PlanStep(s.optString("title"), s.optString("detail"), normalizeStatus(s.optString("status", "pending")))
                        } catch (_: Exception) { null }
                    },
                    approved = p.optBoolean("approved", false),
                )
            }
        } catch (_: Exception) { }
    }

    private fun bump() {
        revision.value++
        persist()
    }

    private fun persist() {
        try {
            val ctx = appContext ?: return
            val obj = JSONObject()
            plans.forEach { (sid, p) ->
                val arr = JSONArray()
                p.steps.forEach { s ->
                    arr.put(JSONObject().put("title", s.title).put("detail", s.detail).put("status", s.status))
                }
                obj.put(sid, JSONObject().put("steps", arr).put("approved", p.approved))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obj.toString()).apply()
        } catch (_: Exception) { }
    }

    // Application context for lazy load/persist without a Context parameter.
    // Init'd from CodeSpaceApplication.onCreate (CustomEndpointStore pattern).
    @Volatile private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }
}
