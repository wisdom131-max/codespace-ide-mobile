package com.codespace.ide.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Wave 1 Group D (owner approval 2026-11-11): coverage, schema and memory inspection.
 *
 * Each of these reads a source that already exists in this project. None of them invents data, and
 * each says plainly which source it read:
 *  - coverage: JaCoCo XML / CSV or LCOV reports under the module's build directory. This repository
 *    does not currently configure a coverage task, so the usual answer is "no report found, here is
 *    how one is produced". Adding a coverage framework was explicitly out of scope.
 *  - schema: this app's entity store (agent_data/<entity>/<id>.json), where the schema is the shape
 *    the records actually have.
 *  - memory: AgentMemory's own store, filtered by the SAME scope rules the UI and read_memory use.
 */
object InsightTools {

    const val MAX_FILES = 60
    const val MAX_UNCOVERED_LINES = 60
    const val MAX_SCHEMA_FIELDS = 80
    const val MAX_MEMORY_HITS = 15

    // ── coverage_read ──────────────────────────────────────────────────────

    /** Where instrumentation/unit-test coverage lands for this project, newest first. */
    fun coverageCandidates(moduleDir: File): List<File> {
        val out = ArrayList<File>()
        fun scan(dir: File, depth: Int) {
            if (depth > 4 || !dir.isDirectory) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) {
                    if (c.name in setOf("intermediates", "generated", "tmp", "kotlin", "res", "assets")) continue
                    scan(c, depth + 1)
                } else {
                    val n = c.name.lowercase()
                    if (n == "jacoco.xml" || n == "report.xml" || n.endsWith(".lcov") || n == "lcov.info" ||
                        (n.endsWith(".csv") && c.parentFile?.name == "jacocoTestReport")) {
                        out.add(c)
                    }
                }
            }
        }
        scan(File(moduleDir, "build/reports"), 0)
        scan(File(moduleDir, "build/test-results"), 0)
        return out.sortedByDescending { it.lastModified() }.take(MAX_FILES)
    }

    data class CoverageSummary(
        val source: String,
        val format: String,
        val lineCovered: Int,
        val lineTotal: Int,
        val branchCovered: Int?,
        val branchTotal: Int?,
        val files: List<FileCoverage>,
        val filesTruncated: Boolean,
        val note: String?,
    ) {
        val linePercent: Double get() = if (lineTotal == 0) 0.0 else (lineCovered * 100.0 / lineTotal)

        fun render(): String {
            val sb = StringBuilder()
            sb.append("Coverage from ").append(source).append(" (").append(format).append(")\n")
            sb.append("lines: ").append(lineCovered).append("/").append(lineTotal)
                .append(" (").append(String.format(java.util.Locale.US, "%.1f", linePercent)).append("%)\n")
            val bc = branchCovered
            val bt = branchTotal
            if (bc != null && bt != null) {
                sb.append("branches: ").append(bc).append("/").append(bt).append(" (")
                    .append(String.format(java.util.Locale.US, "%.1f", if (bt == 0) 0.0 else bc * 100.0 / bt))
                    .append("%)\n")
            } else {
                sb.append("branches: not reported by this report (unavailable, NOT zero coverage)\n")
            }
            if (files.isNotEmpty()) {
                sb.append("files (lowest coverage first):\n")
                files.forEach { sb.append("  ").append(it.render()).append('\n') }
            }
            if (filesTruncated) sb.append("  ...more files not shown\n")
            note?.let { sb.append(it).append('\n') }
            return sb.toString().trimEnd()
        }
    }

    data class FileCoverage(
        val name: String,
        val covered: Int,
        val total: Int,
        val missedLines: List<Int>,
    ) {
        fun render(): String {
            val pct = if (total == 0) 0.0 else covered * 100.0 / total
            val missed = if (missedLines.isEmpty()) "" else "  missed: " + missedLines.take(20).joinToString(",")
            return String.format(java.util.Locale.US, "%.1f%% %d/%d %s%s", pct, covered, total, name, missed)
        }
    }

    /** Parse a JaCoCo XML report. Unknown shapes are refused, not guessed at. */
    fun parseJacocoXml(xml: String, filter: String?): CoverageSummary? {
        return try {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(xml.byteInputStream())
            val packages = doc.getElementsByTagName("package")
            val files = ArrayList<FileCoverage>()
            var covered = 0
            var total = 0
            var bc: Int? = null
            var bt: Int? = null
            var truncated = false
            for (p in 0 until packages.length) {
                val pkg = packages.item(p) as? org.w3c.dom.Element ?: continue
                val pkgName = pkg.getAttribute("name")
                val sourceFiles = pkg.getElementsByTagName("sourcefile")
                for (f in 0 until sourceFiles.length) {
                    val sf = sourceFiles.item(f) as? org.w3c.dom.Element ?: continue
                    val path = if (pkgName.isBlank()) sf.getAttribute("name") else pkgName + "/" + sf.getAttribute("name")
                    if (!filter.isNullOrBlank() && !path.contains(filter, ignoreCase = true)) continue
                    var lc = 0
                    var lt = 0
                    var fbc: Int? = null
                    var fbt: Int? = null
                    val missed = ArrayList<Int>()
                    val counters = sf.getElementsByTagName("counter")
                    for (c in 0 until counters.length) {
                        val el = counters.item(c) as? org.w3c.dom.Element ?: continue
                        val type = el.getAttribute("type")
                        val m = el.getAttribute("missed").toIntOrNull() ?: 0
                        val cv = el.getAttribute("covered").toIntOrNull() ?: 0
                        if (type == "LINE") { lc = cv; lt = cv + m }
                        if (type == "BRANCH") { fbc = cv; fbt = cv + m }
                    }
                    val lines = sf.getElementsByTagName("line")
                    for (l in 0 until lines.length) {
                        val el = lines.item(l) as? org.w3c.dom.Element ?: continue
                        if ((el.getAttribute("mi").toIntOrNull() ?: 0) > 0) {
                            el.getAttribute("nr").toIntOrNull()?.let { missed.add(it) }
                        }
                    }
                    covered += lc; total += lt
                    if (fbc != null && fbt != null) {
                        bc = (bc ?: 0) + fbc
                        bt = (bt ?: 0) + fbt
                    }
                    files.add(FileCoverage(path, lc, lt, missed.take(MAX_UNCOVERED_LINES)))
                    if (files.size >= MAX_FILES) { truncated = true; break }
                }
                if (truncated) break
            }
            if (total == 0 && files.isEmpty()) return null
            CoverageSummary(
                source = "jacoco.xml", format = "JaCoCo XML",
                lineCovered = covered, lineTotal = total,
                branchCovered = bc, branchTotal = bt,
                files = files.sortedBy { if (it.total == 0) 0.0 else it.covered.toDouble() / it.total },
                filesTruncated = truncated,
                note = null,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Parse LCOV (LF/LH and optional BRF/BRH per file), keeping per-file counters. */
    fun parseLcov(text: String, filter: String?): CoverageSummary? {
        var path: String? = null
        var fCovered = 0
        var fTotal = 0
        var fBc = 0
        var fBt = 0
        var fSawBranch = false
        var gCovered = 0
        var gTotal = 0
        var gBc = 0
        var gBt = 0
        var gSawBranch = false
        val files = ArrayList<FileCoverage>()
        val missed = ArrayList<Int>()

        fun closeRecord() {
            val name = path ?: return
            if (filter.isNullOrBlank() || name.contains(filter, ignoreCase = true)) {
                files.add(FileCoverage(name, fCovered, fTotal, missed.take(MAX_UNCOVERED_LINES)))
            }
            path = null
            fCovered = 0; fTotal = 0; fBc = 0; fBt = 0; fSawBranch = false
            missed.clear()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("SF:")) {
                closeRecord()
                path = line.removePrefix("SF:")
            } else if (line.startsWith("DA:")) {
                val parts = line.removePrefix("DA:").split(',')
                val nr = parts.getOrNull(0)?.toIntOrNull()
                val hits = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fTotal++
                gTotal++
                if (hits > 0) {
                    fCovered++
                    gCovered++
                } else {
                    nr?.let { missed.add(it) }
                }
            } else if (line.startsWith("BRDA:")) {
                fSawBranch = true
                gSawBranch = true
                fBt++
                gBt++
                val hits = line.substringAfterLast(',').toIntOrNull() ?: 0
                if (hits > 0) { fBc++; gBc++ }
            } else if (line == "end_of_record") {
                closeRecord()
            }
        }
        closeRecord()
        if (gTotal == 0) return null
        return CoverageSummary(
            source = "lcov", format = "LCOV",
            lineCovered = gCovered, lineTotal = gTotal,
            branchCovered = if (gSawBranch) gBc else null,
            branchTotal = if (gSawBranch) gBt else null,
            files = files.sortedBy { if (it.total == 0) 0.0 else it.covered.toDouble() / it.total }.take(MAX_FILES),
            filesTruncated = files.size > MAX_FILES,
            note = null,
        )
    }

    /** Read the newest report that parses. An empty answer explains how to produce one. */
    fun coverage(moduleDir: File, filter: String?): String {
        val candidates = coverageCandidates(moduleDir)
        if (candidates.isEmpty()) {
            return "No coverage report found under ${moduleDir.path}/build. This project does not " +
                "currently configure a coverage task, so there is nothing to read - that is a missing " +
                "report, NOT zero coverage.\nTo produce one: add the JaCoCo plugin and run the unit " +
                "test task, then report.xml appears under build/reports/coverage/. Filtering and " +
                "file-level detail work as soon as a report exists."
        }
        val failures = ArrayList<String>()
        for (file in candidates) {
            try {
                val text = file.readText()
                val summary = when {
                    file.name.endsWith(".xml", ignoreCase = true) -> parseJacocoXml(text, filter)
                    file.name.endsWith(".csv", ignoreCase = true) -> parseJacocoCsv(text, filter)
                    else -> parseLcov(text, filter)
                }
                if (summary != null) return summary.copy(source = file.path).render()
                failures.add(file.path + ": no parsable counters")
            } catch (e: Exception) {
                failures.add(file.path + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return "Found coverage files but none could be read:\n" + failures.joinToString("\n") { "  " + it }
    }

    /** JaCoCo's per-package CSV: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,...,LINE_MISSED,LINE_COVERED,... */
    fun parseJacocoCsv(text: String, filter: String?): CoverageSummary? {
        val files = ArrayList<FileCoverage>()
        var covered = 0
        var total = 0
        val rows = text.lineSequence().toList()
        if (rows.isEmpty()) return null
        val header = rows.first().split(',')
        val missedIdx = header.indexOf("LINE_MISSED")
        val coveredIdx = header.indexOf("LINE_COVERED")
        val classIdx = header.indexOf("CLASS")
        if (missedIdx < 0 || coveredIdx < 0) return null
        for (row in rows.drop(1)) {
            val cells = row.split(',')
            val cls = cells.getOrNull(classIdx) ?: continue
            if (!filter.isNullOrBlank() && !cls.contains(filter, ignoreCase = true)) continue
            val m = cells.getOrNull(missedIdx)?.toIntOrNull() ?: 0
            val c = cells.getOrNull(coveredIdx)?.toIntOrNull() ?: 0
            covered += c; total += c + m
            if (files.size < MAX_FILES) files.add(FileCoverage(cls, c, c + m, emptyList()))
        }
        if (total == 0) return null
        return CoverageSummary("jacoco.csv", "JaCoCo CSV", covered, total, null, null,
            files.sortedBy { if (it.total == 0) 0.0 else it.covered.toDouble() / it.total }, false, null)
    }

    // ── entity_schema_read ─────────────────────────────────────────────────

    /**
     * This project's "entities" are AgentEntityManager's record directories: one JSON file per
     * record, so the schema is the union of field names with the types actually stored. Derived from
     * real records - an entity with no records has no schema to report, and says so.
     */
    fun entitySchema(context: Context, entity: String, sampleSize: Int = 5): String =
        entitySchemaFromStore(File(context.filesDir, "agent_data"), entity, sampleSize)

    /**
     * The store walk, over a DIRECTORY rather than a Context: this project's "entities" are
     * AgentEntityManager's record directories (one JSON file per record), so the schema is the union
     * of field names with the types actually stored. Derived from real records - an entity with no
     * records has no schema to report, and says so.
     */
    fun entitySchemaFromStore(root: File, entity: String, sampleSize: Int = 5): String {
        if (!root.isDirectory) {
            return "No entity store yet: nothing has been created with create_entity. " +
                "The store would live at " + root.path + "."
        }
        val entities = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { it.name }.sorted()
        if (entity.isBlank()) {
            if (entities.isEmpty()) return "No entities exist yet."
            return "Entities (" + entities.size + "): " + entities.joinToString(", ") +
                "\nAsk for one by name to see its fields."
        }
        val segment = com.codespace.ide.util.CanonicalPaths.safeNameSegment(entity)
            ?: return "Invalid entity name: '" + entity + "' (must be a single name, no paths)."
        val dir = File(root, segment)
        if (!dir.isDirectory) {
            return "No entity named \"" + entity + "\". Known entities: " +
                (if (entities.isEmpty()) "(none)" else entities.joinToString(", "))
        }
        val records = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (records.isEmpty()) {
            return "Entity \"" + entity + "\" exists but has no records, so it has no observable schema yet."
        }
        val fieldTypes = LinkedHashMap<String, MutableSet<String>>()
        val fieldPresence = LinkedHashMap<String, Int>()
        var parsed = 0
        val problems = ArrayList<String>()
        for (f in records.take(sampleSize.coerceIn(1, 50))) {
            try {
                val json = JSONObject(f.readText())
                parsed++
                for (key in json.keys()) {
                    if (fieldTypes.size >= MAX_SCHEMA_FIELDS && key !in fieldTypes) continue
                    fieldTypes.getOrPut(key) { LinkedHashSet() }.add(typeOf(json.opt(key)))
                    fieldPresence[key] = (fieldPresence[key] ?: 0) + 1
                }
            } catch (e: Exception) {
                problems.add(f.name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        if (parsed == 0) {
            return "Entity \"" + entity + "\" has records but none are valid JSON:\n" +
                problems.take(5).joinToString("\n") { "  " + it }
        }
        val sb = StringBuilder("Entity \"" + entity + "\": " + records.size + " record" +
            (if (records.size == 1) "" else "s") + ", schema from " + parsed + " sampled\n")
        for ((field, types) in fieldTypes) {
            val present = fieldPresence[field] ?: 0
            val required = present == parsed
            sb.append("  ").append(field).append(": ").append(types.joinToString(" | "))
            sb.append(if (required) " (in every sampled record)" else " (in " + present + " of " + parsed + ")")
            if (field == "id" || field == "created_date") sb.append(" [store-managed]")
            sb.append('\n')
        }
        if (problems.isNotEmpty()) sb.append("  unreadable records: ").append(problems.size).append('\n')
        sb.append("Source: ").append(dir.path).append(" (JSON records; the schema is the observed shape)")
        return sb.toString().trimEnd()
    }

    private fun typeOf(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> "boolean"
        is Int, is Long -> "number"
        is Double, is Float -> "number"
        is JSONObject -> "object"
        is org.json.JSONArray -> "array"
        else -> "string"
    }

    // ── memory_search ──────────────────────────────────────────────────────

    data class MemoryHit(val key: String, val value: String, val score: Int)

    /**
     * Search the memories the CURRENT scope may see. The scope filter is the same one read_memory
     * uses, so this can never surface another session's memory: [rank] only ever sees what
     * [AgentMemory.visibleEntries] handed it.
     */
    fun memorySearch(context: Context, query: String, sessionId: String, limit: Int = 10): String {
        val q = query.trim()
        if (q.isEmpty()) {
            return "Give a word or phrase to search memory for. Use read_memory to list everything " +
                "visible in the current scope."
        }
        return rank(AgentMemory.visibleEntries(context, sessionId), q, limit)
    }

    /**
     * Literal, explainable matching over already-filtered entries: a key match outranks a value
     * match, and an exact key beats a partial one. Pure, so every ranking rule is asserted by a test
     * instead of being described in a comment.
     */
    fun rank(entries: Map<String, String>, query: String, limit: Int = 10): String {
        val q = query.trim()
        if (q.isEmpty()) {
            return "Give a word or phrase to search memory for. Use read_memory to list everything " +
                "visible in the current scope."
        }
        val terms = q.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 1 }
        if (terms.isEmpty()) return "That query has nothing searchable in it."
        if (entries.isEmpty()) return "Memory is empty for this scope, so \"" + q + "\" has no matches."
        val hits = ArrayList<MemoryHit>()
        for ((key, value) in entries) {
            val k = key.lowercase()
            val v = value.lowercase()
            var score = 0
            for (t in terms) {
                if (k == t) score += 60
                else if (k.contains(t)) score += 40
                if (v.contains(t)) score += 10
            }
            if (score > 0) hits.add(MemoryHit(key, value, score))
        }
        if (hits.isEmpty()) {
            return "No memory matches \"" + q + "\" (searched " + entries.size +
                " visible " + (if (entries.size == 1) "entry" else "entries") + ")."
        }
        val ranked = hits.sortedWith(compareByDescending<MemoryHit> { it.score }.thenBy { it.key })
        val shown = ranked.take(limit.coerceIn(1, MAX_MEMORY_HITS))
        val sb = StringBuilder("Memory matches for \"" + q + "\" (" + ranked.size + "):\n")
        for (h in shown) sb.append("  ").append(h.key).append(": ").append(h.value.take(300)).append('\n')
        if (ranked.size > shown.size) sb.append("  ...and ").append(ranked.size - shown.size).append(" more\n")
        return sb.toString().trimEnd()
    }
}
