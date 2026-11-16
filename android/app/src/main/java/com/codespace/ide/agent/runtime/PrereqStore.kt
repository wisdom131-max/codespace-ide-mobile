package com.codespace.ide.agent.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STAGE 4.3: prerequisite evidence that survives the process.
 *
 * Stage 4.2's ledger died with the activity, so a restarted app forgot which prerequisites had been
 * proved and a dependent call was refused (or worse, a write was offered again). This is the same
 * convention as [com.codespace.ide.agent.ToolCallLedger]: one small JSON file in `filesDir`, written
 * through a temp file and a rename so a kill mid-write cannot leave half a record, and bounded so it
 * cannot grow with use.
 *
 * It stores structure, not transcripts: a capability, the tool that satisfied it, up to 240
 * characters of evidence, whether the call changed something, and when. A corrupt or unreadable file
 * yields an empty record plus a reason rather than an exception: losing unreadable evidence is safe,
 * inventing it is not.
 */
internal class PrereqStore(private val file: File) {

    data class Loaded(val entries: List<PrereqLedger.Entry>, val note: String = "")

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList())
        return runCatching {
            val rows = JSONArray(file.readText())
            val out = ArrayList<PrereqLedger.Entry>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val capability = o.optString("capability").trim()
                if (capability.isEmpty()) continue
                out.add(
                    PrereqLedger.Entry(
                        capability = capability,
                        solvedBy = o.optString("tool"),
                        evidence = o.optString("evidence").take(240),
                        failed = o.optBoolean("failed", false),
                        session = o.optString("session"),
                        revision = o.optInt("revision", 0),
                        sideEffect = o.optBoolean("sideEffect", false),
                        at = o.optLong("at", 0L),
                    )
                )
            }
            Loaded(out.take(CAP))
        }.getOrElse { Loaded(emptyList(), "the stored prerequisite record could not be read, so it was not trusted") }
    }

    fun save(entries: List<PrereqLedger.Entry>) {
        runCatching {
            val rows = JSONArray()
            entries.takeLast(CAP).forEach { e ->
                rows.put(
                    JSONObject()
                        .put("capability", e.capability)
                        .put("tool", e.solvedBy)
                        .put("evidence", e.evidence.take(240))
                        .put("failed", e.failed)
                        .put("session", e.session)
                        .put("revision", e.revision)
                        .put("sideEffect", e.sideEffect)
                        .put("at", e.at)
                )
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rows.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(rows.toString())
                tmp.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        private const val CAP = 200

        fun get(context: Context): PrereqStore =
            PrereqStore(File(context.applicationContext.filesDir, "prereq-ledger.json"))
    }
}
