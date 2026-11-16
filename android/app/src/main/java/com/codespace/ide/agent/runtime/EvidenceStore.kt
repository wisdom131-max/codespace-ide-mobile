package com.codespace.ide.agent.runtime

import android.content.Context
import com.codespace.ide.agent.EvidenceReconciler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PROJECT FOUR, STAGE 4.7 (2026-11-16): observations that outlive the process.
 *
 * Stage 4.6 could only compare two observations made in the same run, so a contradiction discovered
 * before a restart was forgotten after it. This follows the same convention as [PrereqStore]: one
 * small JSON file in `filesDir`, written to a temp file and renamed, bounded, and read defensively.
 *
 * It stores observations, not facts: a resource, a digest of what was seen, the session and plan
 * revision that saw it, which tool saw it, and when. A digest is not proof of content - the content is
 * gone - so a restored observation can only ever say "this disagrees with what we saw before", never
 * "this is what the file contains". An unreadable file yields nothing plus a reason: losing evidence is
 * safe, inventing it is not.
 */
internal class EvidenceStore(private val file: File) {

    data class Loaded(val observations: Map<String, EvidenceReconciler.Observation>, val note: String = "")

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyMap())
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return Loaded(emptyMap(), "the evidence record was empty, so it was not trusted")
            val rows = JSONArray(text)
            val out = LinkedHashMap<String, EvidenceReconciler.Observation>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val resource = o.optString("resource").trim()
                val digest = o.optString("digest").trim()
                if (resource.isEmpty() || digest.isEmpty()) continue
                out[resource] = EvidenceReconciler.Observation(
                    resource = resource,
                    digest = digest,
                    at = o.optLong("at", 0L),
                    session = o.optString("session"),
                    revision = o.optInt("revision", 0),
                    tool = o.optString("tool"),
                )
            }
            Loaded(out.values.toList().takeLast(CAP).associateBy { it.resource })
        }.getOrElse {
            Loaded(emptyMap(), "the stored evidence could not be read, so it was not trusted")
        }
    }

    /** Atomic: a kill between write and rename leaves the previous file, never half of a new one. */
    fun save(observations: Map<String, EvidenceReconciler.Observation>) {
        runCatching {
            val rows = JSONArray()
            observations.values.toList().takeLast(CAP).forEach { o ->
                rows.put(
                    JSONObject()
                        .put("resource", o.resource)
                        .put("digest", o.digest)
                        .put("at", o.at)
                        .put("session", o.session)
                        .put("revision", o.revision)
                        .put("tool", o.tool)
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
        /** Bounded: the last observation of each of at most this many resources. */
        internal const val CAP = 200

        fun get(context: Context): EvidenceStore =
            EvidenceStore(File(context.applicationContext.filesDir, "evidence-observations.json"))

        internal fun forTest(file: File): EvidenceStore = EvidenceStore(file)
    }
}
