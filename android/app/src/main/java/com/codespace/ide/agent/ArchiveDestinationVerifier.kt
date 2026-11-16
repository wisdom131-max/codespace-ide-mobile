package com.codespace.ide.agent

import java.io.File
import java.util.zip.ZipFile

/**
 * PROJECT FOUR, STAGE 4.7 (2026-11-16): read the destination, not the extraction's own report.
 *
 * Stage 4.6 compared an extraction against the listing the tool printed. That still trusts the tool's
 * account. This opens the archive read-only and walks the destination on disk, so "extracted" has to
 * survive a look at the files that are actually there.
 *
 * Never extracts, never writes, never overwrites: verification that modifies what it verifies is not
 * verification. Entries that would escape the destination are refused rather than followed, duplicate
 * names are reported rather than silently collapsed, and an unreadable destination is reported as
 * unknown rather than as success.
 */
object ArchiveDestinationVerifier {

    enum class Result { VERIFIED, REFUTED, INCONCLUSIVE, NOT_CHECKABLE }

    data class Outcome(val result: Result, val note: String) {
        val ok: Boolean get() = result == Result.VERIFIED
    }

    private const val MAX_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

    fun verify(archivePath: String?, destination: String?): Outcome {
        if (archivePath.isNullOrBlank()) return Outcome(Result.NOT_CHECKABLE, "no archive was named")
        val archive = File(archivePath)
        if (!archive.exists()) return Outcome(Result.NOT_CHECKABLE, "the archive is not there to check")
        val dest = destination?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: return Outcome(Result.NOT_CHECKABLE, "no extraction destination was named")
        if (!dest.isDirectory) {
            return Outcome(Result.NOT_CHECKABLE, "the destination directory could not be read")
        }
        if (!dest.canRead()) return Outcome(Result.NOT_CHECKABLE, "the destination cannot be read")

        val entries = ArrayList<String>()
        val sizes = HashMap<String, Long>()
        val duplicates = ArrayList<String>()
        val escaping = ArrayList<String>()
        var total = 0L
        try {
            ZipFile(archive).use { zip ->
                val seen = HashSet<String>()
                val list = zip.entries()
                var count = 0
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    if (++count > MAX_ENTRIES) {
                        return Outcome(Result.NOT_CHECKABLE, "the archive holds more than " + MAX_ENTRIES + " entries, which is beyond the inspection limit")
                    }
                    val name = entry.name.replace('\\', '/')
                    if (unsafe(name)) {
                        escaping.add(name)
                        continue
                    }
                    if (entry.isDirectory) continue
                    if (entry.size > MAX_ENTRY_BYTES) {
                        return Outcome(Result.NOT_CHECKABLE, "an entry is larger than the inspection limit")
                    }
                    if (!seen.add(name)) duplicates.add(name)
                    entries.add(name)
                    sizes[name] = entry.size
                    total += entry.size.coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            val unsupported = archive.extension.lowercase() !in setOf("zip", "jar", "apk", "war", "aar")
            return if (unsupported) {
                Outcome(Result.NOT_CHECKABLE, "that archive format cannot be inspected here")
            } else {
                Outcome(Result.REFUTED, "the archive could not be opened, so it is corrupt or incomplete")
            }
        }

        if (escaping.isNotEmpty()) {
            return Outcome(Result.REFUTED, "the archive contains unsafe paths and must not be extracted: " +
                escaping.take(3).joinToString(", "))
        }
        if (entries.isEmpty()) return Outcome(Result.INCONCLUSIVE, "the archive reports no files, so there is nothing to compare")

        val present = relativeFiles(dest)
            ?: return Outcome(Result.NOT_CHECKABLE, "the destination contents could not be listed")

        val missing = entries.filter { name -> present.none { it == name || it.endsWith("/" + name) } }
        val unexpected = present.filter { path -> entries.none { it == path || path.endsWith("/" + it) } }
        val wrongType = entries.filter { name ->
            val direct = File(dest, name)
            direct.exists() && direct.isDirectory
        }
        val truncated = entries.mapNotNull { name ->
            val onDisk = present.firstOrNull { it == name || it.endsWith("/" + name) } ?: return@mapNotNull null
            val file = File(dest, onDisk)
            val expected = sizes[name] ?: return@mapNotNull null
            if (file.length() < expected) name to (expected to file.length()) else null
        }

        val notes = ArrayList<String>()
        if (duplicates.isNotEmpty()) notes.add("duplicate entry names in the archive: " + duplicates.take(3).joinToString(", "))
        if (unexpected.isNotEmpty()) notes.add("also present in the destination: " + unexpected.take(3).joinToString(", "))

        return when {
            missing.size == entries.size ->
                Outcome(Result.REFUTED, "nothing from the archive is in the destination")
            wrongType.isNotEmpty() ->
                Outcome(Result.REFUTED, "extracted as the wrong type: " + wrongType.take(3).joinToString(", "))
            truncated.isNotEmpty() -> Outcome(
                Result.REFUTED,
                "extracted short: " + truncated.take(3).joinToString(", ") { it.first + " (" + it.second.second + " of " + it.second.first + " bytes)" },
            )
            missing.isNotEmpty() -> Outcome(
                Result.INCONCLUSIVE,
                "partial extraction: " + missing.size + " of " + entries.size + " file(s) are missing, starting with " + missing.first(),
            )
            else -> Outcome(
                Result.VERIFIED,
                "all " + entries.size + " file(s) are on disk" + (if (notes.isEmpty()) "" else " (" + notes.joinToString("; ") + ")"),
            )
        }
    }

    private fun unsafe(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("~")) return true
        if (name.contains("..")) return true
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return true
        return false
    }

    /** Every file under the destination, as paths relative to it. Depth-bounded. */
    private fun relativeFiles(root: File): List<String>? {
        return runCatching {
            val out = ArrayList<String>()
            val queue = ArrayDeque<Pair<File, String>>()
            queue.add(root to "")
            var visited = 0
            while (queue.isNotEmpty()) {
                val (dir, prefix) = queue.removeFirst()
                val children = dir.listFiles() ?: return null
                for (child in children) {
                    if (++visited > MAX_ENTRIES) return null
                    val path = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
                    if (child.isDirectory) queue.add(child to path) else out.add(path)
                }
            }
            out
        }.getOrNull()
    }
}
