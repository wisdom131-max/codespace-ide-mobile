package com.codespace.ide.agent.facts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OWNER REQUEST (2026-11-23): "You do it yourself now." The comparison with LangMem found one real
 * gap in our memory: facts are found by SHARED WORDS only, so a question that means the same thing
 * but uses different words finds nothing. LangMem finds them because it searches a vector store.
 *
 * We already own the half LangMem borrows from OpenAI: the real bge-base model on the device. This is
 * the other half - a vector per fact, stored BESIDE the facts rather than inside them.
 *
 * Why a sidecar file and not a field on Fact: the fact file is the owner's memory and it is parsed by
 * a hand-written reader with tests around every field. Adding a 768-number array to it would mean
 * rewriting that reader for no benefit. A separate file also means the vectors can be thrown away and
 * rebuilt at any time without touching a single fact, which is exactly what happens after a model
 * change.
 *
 * Nothing here is required for memory to work: no vector, no problem - the fact is still found by its
 * words, exactly as today.
 */
object FactVectors {

    private const val FILE_NAME = "fact-vectors.json"
    /** bge-base produces 768. Kept so a stale vector from another model is ignored, never compared. */
    private const val DIMS = 768

    fun file(context: Context): File = File(FactsStore.memoryDir(context), FILE_NAME)

    fun load(context: Context): Map<String, FloatArray> = loadFrom(file(context))

    fun loadFrom(f: File): Map<String, FloatArray> {
        if (!f.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText())
            val out = HashMap<String, FloatArray>(root.length())
            for (id in root.keys()) {
                val arr = root.optJSONArray(id) ?: continue
                if (arr.length() != DIMS) continue
                val v = FloatArray(DIMS)
                for (i in 0 until DIMS) v[i] = arr.optDouble(i, 0.0).toFloat()
                out[id] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun put(context: Context, factId: String, vector: FloatArray?): Boolean {
        if (vector == null || vector.size != DIMS) return false
        return runCatching {
            val f = file(context)
            val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
            val arr = JSONArray()
            for (x in vector) arr.put((Math.round(x * 100000f) / 100000.0))
            root.put(factId, arr)
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
            true
        }.getOrDefault(false)
    }

    fun putMany(context: Context, vectors: Map<String, FloatArray>): Boolean {
        if (vectors.isEmpty()) return true
        return runCatching {
            val f = file(context)
            val root = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
            for ((id, v) in vectors) {
                if (v.size != DIMS) continue
                val arr = JSONArray()
                for (x in v) arr.put((Math.round(x * 100000f) / 100000.0))
                root.put(id, arr)
            }
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
            true
        }.getOrDefault(false)
    }

    /** Forget vectors whose fact no longer exists, so the file cannot grow without bound. */
    fun prune(context: Context, liveIds: Set<String>): Boolean = runCatching {
        val f = file(context)
        if (!f.isFile) return true
        val root = JSONObject(f.readText())
        val dead = root.keys().asSequence().filter { it !in liveIds }.toList()
        if (dead.isEmpty()) return true
        for (id in dead) root.remove(id)
        f.writeText(root.toString())
        true
    }.getOrDefault(false)

    /**
     * Gives a vector to every fact that lacks one, once, on a background thread. Called when the
     * prompt is built: a fact saved before the model existed still becomes findable by meaning, and
     * nothing on the send path waits for it.
     */
    fun backfill(context: Context, facts: List<Fact>) {
        if (!com.codespace.ide.agent.embedding.TrainedEmbeddings.isReady()) return
        val have = load(context)
        val missing = facts.filter { it.isRetrievable() && have[it.id] == null }
        if (missing.isEmpty()) return
        Thread({
            val made = HashMap<String, FloatArray>()
            for (f in missing) {
                val v = com.codespace.ide.agent.embedding.TrainedEmbeddings.embed(f.content)
                if (v != null) made[f.id] = v
            }
            if (made.isNotEmpty()) {
                putMany(context, made)
                prune(context, facts.map { it.id }.toSet())
            }
        }, "vncode-fact-vectors").start()
    }

    /** Cosine similarity. Both vectors come from the same model, so no rescaling is needed. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }
}
