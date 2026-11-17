package com.codespace.ide.agent

/**
 * PROJECT FOUR, STAGE 4.6 (2026-11-16): an archive listing is checked, not assumed.
 *
 * Uses the text the existing `zip_list` and `zip_extract` tools already produce. Nothing is opened
 * directly, so verification cannot corrupt or rewrite an archive, and an unsupported format is
 * reported as unsupported rather than as an empty archive.
 */
object ArchiveVerifier {

    enum class Result { VERIFIED, REFUTED, INCONCLUSIVE, NOT_CHECKABLE }

    data class Outcome(val result: Result, val note: String) {
        val ok: Boolean get() = result == Result.VERIFIED
    }

    data class Entry(val path: String, val size: Long?) {
        val isDirectory: Boolean get() = path.endsWith("/") || path.endsWith("\\")
    }

    data class Listing(
        val entries: List<Entry>,
        val declaredCount: Int?,
        /** Lines that looked like listing output but could not be parsed: the listing is INCOMPLETE. */
        val skippedLines: Int = 0,
    ) {
        val files: List<Entry> get() = entries.filter { !it.isDirectory }
        val plausible: Boolean get() = declaredCount == null || entries.size >= declaredCount - 0 ||
            entries.size >= declaredCount
    }

    private val CORRUPT = listOf(
        "is not a zip", "not a zip", "corrupt", "invalid", "unexpected end",
        "cannot open", "bad archive", "unexpected eof", "truncated",
    )
    private val UNSUPPORTED = listOf("unsupported", "not supported", "unknown format", "cannot read archive format")

    fun parseListing(output: String): Listing? {
        val text = output.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (CORRUPT.any { lower.contains(it) } || UNSUPPORTED.any { lower.contains(it) }) return null

        val trailingSize = Regex("^(.+?)\\s+(\\d{1,9})$")
        val leadingSize = Regex("^(\\d{1,9})\\s+(.+)$")
        val entries = ArrayList<Entry>()
        var skipped = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("==")) return@forEach
            if (line.startsWith("Archive:") || line.startsWith("Listing")) return@forEach
            var path: String? = null
            var size: Long? = null
            trailingSize.find(line)?.let { m ->
                path = m.groupValues[1].trim()
                size = m.groupValues[2].toLongOrNull()
            }
            if (path == null) {
                leadingSize.find(line)?.let { m ->
                    size = m.groupValues[1].toLongOrNull()
                    path = m.groupValues[2].trim()
                }
            }
            if (path == null) {
                // A bare path is still an entry; anything with spaces in it is prose, not a listing.
                if (line.contains(' ')) {
                    skipped++
                    return@forEach
                }
                path = line
            }
            val resolved = path ?: return@forEach
            if (resolved.isEmpty()) return@forEach
            entries.add(Entry(resolved, size))
        }
        val count = Regex("(\\d+)\\s+(?:files?|entries)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        return if (entries.isEmpty() && count == null) null else Listing(entries, count, skipped)
    }

    /**
     * PROJECT EIGHT 8.6 - why an entry path is unsafe, or null when it is fine.
     *
     * The old check looked only for "..". That missed the two shapes an archive actually uses to escape
     * a destination: an ABSOLUTE entry (/data/data/<pkg>/...) and a Windows-rooted one (C:\ or \\host\share),
     * both of which tar and unzip will write wherever they name. Detecting only ".." let a hostile
     * archive pass verification with a VERIFIED verdict, which is worse than not checking at all: the
     * caller then believes the extraction was reviewed.
     */
    internal fun unsafePath(raw: String): String? {
        val path = raw.replace('\\', '/').trim()
        if (path.isEmpty()) return "empty entry name"
        if (path.any { it.code < 32 }) return "entry name carries control characters"
        if (path.startsWith("/")) return "absolute entry name"
        if (path.startsWith("//")) return "network-rooted entry name"
        if (Regex("^[A-Za-z]:/").containsMatchIn(path)) return "drive-rooted entry name"
        // Any segment that climbs is unsafe, including one hidden mid-path ("a/../../b").
        val segments = path.split('/')
        if (segments.any { it == ".." }) return "entry name climbs out of the destination"
        return null
    }

    fun verifyListing(output: String): Outcome {
        val text = output.trim()
        val lower = text.lowercase()
        if (text.isEmpty()) return Outcome(Result.NOT_CHECKABLE, "no listing was produced")
        if (CORRUPT.any { lower.contains(it) }) return Outcome(Result.REFUTED, "the archive could not be opened")
        if (UNSUPPORTED.any { lower.contains(it) }) return Outcome(Result.NOT_CHECKABLE, "that archive format is not supported")
        val listing = parseListing(output) ?: return Outcome(Result.INCONCLUSIVE, "no entries could be read from the listing")
        val bad = listing.entries.filter { unsafePath(it.path) != null }
        if (bad.isNotEmpty()) {
            return Outcome(
                Result.REFUTED,
                "the archive contains unsafe paths: " +
                    bad.take(3).joinToString(", ") { it.path },
            )
        }
        if (listing.entries.isEmpty()) return Outcome(Result.INCONCLUSIVE, "the archive reported no entries")
        // A listing this code could not fully read is not evidence that the archive is clean. Reporting
        // VERIFIED after skipping lines is how a hostile entry stays unexamined and yet approved.
        if (listing.skippedLines > 0) {
            return Outcome(
                Result.INCONCLUSIVE,
                listing.skippedLines.toString() + " listing line(s) could not be read, so the archive was not fully checked",
            )
        }
        if (listing.declaredCount != null && listing.entries.size < listing.declaredCount) {
            return Outcome(Result.INCONCLUSIVE, "the archive claims " + listing.declaredCount +
                " entries but only " + listing.entries.size + " were read")
        }
        return Outcome(Result.VERIFIED, listing.files.size.toString() + " file(s) listed, including " +
            listing.files.take(2).joinToString(", ") { it.path })
    }

    /** Was everything the listing promised actually written to disk? */
    fun verifyExtraction(listing: Listing?, presentPaths: List<String>): Outcome {
        if (listing == null) return Outcome(Result.NOT_CHECKABLE, "no listing to compare against")
        if (listing.files.isEmpty()) return Outcome(Result.INCONCLUSIVE, "the archive listed no files to extract")
        val present = presentPaths.map { it.replace('\\', '/').trimStart('.', '/') }.toSet()
        val missing = listing.files.filter { entry ->
            val path = entry.path.replace('\\', '/').trimStart('.', '/')
            present.none { it == path || it.endsWith("/" + path) || path.endsWith("/" + it) }
        }
        return when {
            missing.isEmpty() -> Outcome(Result.VERIFIED, "all " + listing.files.size + " file(s) are present after extraction")
            missing.size == listing.files.size -> Outcome(Result.REFUTED, "nothing from the archive is on disk")
            else -> Outcome(Result.INCONCLUSIVE, "partial extraction: " + missing.size + " of " +
                listing.files.size + " file(s) are missing, starting with " + missing.first().path)
        }
    }
}
