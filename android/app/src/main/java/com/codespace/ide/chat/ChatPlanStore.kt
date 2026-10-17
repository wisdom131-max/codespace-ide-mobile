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
 *
 * BATCH4-P3 (S01 fix, SK01 model): every mutation is now TYPED — the in-memory
 * change is committed only when persist() reports a real save; a failed save
 * ROLLS BACK the in-memory change and does NOT bump the revision counter, so
 * the counter can never claim a save that did not reach storage. A corrupt
 * payload is QUARANTINED (copied under a ".corrupt-<ts>" sibling,
 * content-deduped) and the original is NEVER deleted or overwritten — the app
 * starts empty with an honest verdict instead of silently wiping the data.
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

    // BATCH4-P3: JVM-test seam — when set, ALL reads/writes go through it.
    @Volatile internal var testBackend: ChatPrefsBackend? = null

    internal fun backend(): ChatPrefsBackend? {
        testBackend?.let { return it }
        val ctx = appContext ?: return null
        return AndroidChatPrefsBackend(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    fun planFor(sessionId: String): Plan? {
        ensureLoaded()
        return plans[sessionId]
    }

    /** BATCH4-P3: typed mutations — return true only on a real save. */
    fun update(sessionId: String, steps: List<PlanStep>): Boolean =
        mutate { plans[sessionId] = Plan(steps, plans[sessionId]?.approved ?: false) }

    fun setApproved(sessionId: String): Boolean =
        mutate { plans[sessionId] = (plans[sessionId] ?: Plan()).copy(approved = true) }

    fun clear(sessionId: String): Boolean =
        mutate { plans.remove(sessionId) }

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

    /**
     * BATCH4-P3: apply -> persist -> commit or roll back. On a failed save the
     * in-memory change is rolled back and the revision does NOT bump, so
     * planFor()/revision never claim a save that did not happen.
     */
    private fun mutate(block: () -> Unit): Boolean {
        ensureLoaded()
        val snapshot = HashMap(plans)
        block()
        val ok = persist()
        if (!ok) {
            plans.clear()
            plans.putAll(snapshot)
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[PLAN] save FAILED - in-memory change rolled back; the plan card stays at its last saved state (BATCH4-P3: the revision bumps only on a real save)",
                "chat",
            )
        } else {
            revision.value++
        }
        return ok
    }

    private fun normalizeStatus(s: String): String = when (s.lowercase().trim()) {
        "in_progress", "inprogress", "active", "started" -> "in_progress"
        "completed", "complete", "done" -> "completed"
        else -> "pending"
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val b = backend() ?: return
        val raw = b.getString(KEY) ?: return
        try {
            val obj = JSONObject(raw)
            var skippedSteps = 0
            obj.keys().forEach { sid ->
                val p = obj.getJSONObject(sid)
                val arr = p.optJSONArray("steps") ?: JSONArray()
                plans[sid] = Plan(
                    steps = (0 until arr.length()).mapNotNull { i ->
                        try {
                            val s = arr.getJSONObject(i)
                            PlanStep(s.optString("title"), s.optString("detail"), normalizeStatus(s.optString("status", "pending")))
                        } catch (_: Exception) {
                            skippedSteps++
                            null
                        }
                    },
                    approved = p.optBoolean("approved", false),
                )
            }
            if (skippedSteps > 0) {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[PLAN] $skippedSteps plan step(s) unreadable and skipped - the rest of the plan survived",
                    "chat",
                )
            }
        } catch (_: Exception) {
            // BATCH4-P3: never silently empty — quarantine the payload, start honest-empty.
            quarantineCorrupt(b, raw)
        }
    }

    /**
     * BATCH4-P3 (SK01 model): the corrupt payload is NEVER deleted and NEVER
     * overwritten — the original key keeps its bytes untouched, and a copy is
     * parked under a ".corrupt-<ts>" sibling key. Content-deduped so repeated
     * launches with the same corrupt payload do not accumulate duplicates.
     */
    private fun quarantineCorrupt(b: ChatPrefsBackend, raw: String) {
        val prefix = "$KEY.corrupt-"
        try {
            val existing = b.allWithPrefix(prefix)
            if (!existing.containsValue(raw)) {
                b.putString(prefix + System.currentTimeMillis(), raw)
            }
        } catch (_: Exception) { /* quarantine write failure must not crash the load */ }
        com.codespace.ide.diagnostics.AppOutputLog.log(
            "[PLAN] plans_v1 is unreadable - the payload is PRESERVED under a quarantine key and the original is left untouched (nothing deleted); plans start empty",
            "chat",
        )
    }

    /** Typed save: true = the payload reached storage. */
    private fun persist(): Boolean {
        val b = backend() ?: return false
        return try {
            val obj = JSONObject()
            plans.forEach { (sid, p) ->
                val arr = JSONArray()
                p.steps.forEach { s ->
                    arr.put(JSONObject().put("title", s.title).put("detail", s.detail).put("status", s.status))
                }
                obj.put(sid, JSONObject().put("steps", arr).put("approved", p.approved))
            }
            b.putString(KEY, obj.toString())
        } catch (e: Exception) {
            com.codespace.ide.diagnostics.AppOutputLog.log("[PLAN] save FAILED - " + e.message, "chat")
            false
        }
    }

    // Application context for lazy load/persist without a Context parameter.
    // Init'd from CodeSpaceApplication.onCreate (CustomEndpointStore pattern).
    @Volatile private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    /** JVM tests only: resets singleton state between test cases. */
    internal fun resetForTests() {
        plans.clear()
        loaded = false
        revision.value = 0
    }
}
