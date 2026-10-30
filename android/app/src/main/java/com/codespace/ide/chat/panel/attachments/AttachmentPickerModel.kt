package com.codespace.ide.chat.panel.attachments

import java.io.File

/**
 * Attachment picker — the browsing rules, separated from the drawing.
 *
 * Why the rules live here: the picker is a file manager, and a file manager's failure modes are
 * data problems, not layout problems - a directory it cannot read being reported as "empty", the
 * same entry listed twice, a "back" that escapes the project, a listing so large it blocks the
 * frame. All four are decided by pure functions and asserted in unit tests; the composable only
 * paints what these return.
 */
internal enum class PickerKind {
    FOLDER, IMAGE, PDF, ARCHIVE, WORD, EXCEL, POWERPOINT, CODE, MARKDOWN, TEXT, AUDIO, VIDEO, OTHER
}

internal data class PickerEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val kind: PickerKind,
)

internal data class PickerListing(
    /** Directory actually listed. */
    val dir: String,
    val entries: List<PickerEntry>,
    /** True when the cap cut the listing short - the UI says so instead of looking complete. */
    val truncated: Boolean = false,
    /** Non-null when the directory could not be read at all. Never shown as an empty folder. */
    val problem: String? = null,
)

internal object AttachmentPicker {

    /** Highest number of entries one directory contributes. Keeps a huge folder off the frame. */
    const val MAX_ENTRIES = 400

    /**
     * The label on a file is the TYPE, chosen from the extension: that is what the reference
     * grid shows (Word/Excel/PowerPoint/PDF/photo), and it is the only signal that stays
     * truthful without opening the file.
     */
    fun kindOf(name: String, isDir: Boolean): PickerKind {
        if (isDir) return PickerKind.FOLDER
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> PickerKind.IMAGE
            "pdf" -> PickerKind.PDF
            "zip", "jar", "apk", "gz", "tgz", "tar", "xz", "7z", "rar" -> PickerKind.ARCHIVE
            "doc", "docx", "odt", "rtf" -> PickerKind.WORD
            "xls", "xlsx", "ods", "csv" -> PickerKind.EXCEL
            "ppt", "pptx", "odp" -> PickerKind.POWERPOINT
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "cs", "go",
            "rs", "rb", "php", "swift", "sh", "bash", "zsh", "sql", "xml", "yml", "yaml",
            "toml", "gradle", "ini", "html", "css", "scss" -> PickerKind.CODE
            "md", "markdown" -> PickerKind.MARKDOWN
            "txt", "log", "conf", "env", "properties" -> PickerKind.TEXT
            "mp3", "wav", "ogg", "m4a", "flac", "aac" -> PickerKind.AUDIO
            "mp4", "mkv", "webm", "mov", "avi", "3gp" -> PickerKind.VIDEO
            else -> PickerKind.OTHER
        }
    }

    /** Short badge text drawn on a file tile: "PY", "ZIP", "PDF"... */
    fun badgeFor(entry: PickerEntry): String = when (entry.kind) {
        PickerKind.FOLDER -> ""
        PickerKind.PDF -> "PDF"
        PickerKind.ARCHIVE -> "ZIP"
        PickerKind.WORD -> "DOC"
        PickerKind.EXCEL -> "XLS"
        PickerKind.POWERPOINT -> "PPT"
        PickerKind.IMAGE -> "IMG"
        PickerKind.AUDIO -> "AUD"
        PickerKind.VIDEO -> "VID"
        PickerKind.MARKDOWN -> "MD"
        PickerKind.TEXT -> "TXT"
        PickerKind.CODE -> entry.name.substringAfterLast('.', "").uppercase().take(3).ifBlank { "TXT" }
        PickerKind.OTHER -> entry.name.substringAfterLast('.', "").uppercase().take(3).ifBlank { "FILE" }
    }

    /**
     * One directory, folders first then files, each alphabetically, no duplicates.
     *
     * Sorted rather than raw listing order because raw order is filesystem-dependent and makes
     * the same folder look different each time it is opened. Duplicates are dropped by absolute
     * path: a symlinked or aliased entry must not appear twice and stage two chips.
     */
    fun listing(dir: File, cap: Int = MAX_ENTRIES): PickerListing {
        if (!dir.exists()) return PickerListing(dir.absolutePath, emptyList(), problem = "This folder is no longer there.")
        if (!dir.isDirectory) return PickerListing(dir.absolutePath, emptyList(), problem = "That path is a file, not a folder.")
        val raw = try {
            dir.listFiles()
        } catch (t: Throwable) {
            return PickerListing(dir.absolutePath, emptyList(), problem = "Cannot read this folder (" + (t.message ?: "denied") + ").")
        } ?: return PickerListing(dir.absolutePath, emptyList(), problem = "Cannot read this folder (permission denied).")

        val seen = HashSet<String>(raw.size * 2)
        val out = ArrayList<PickerEntry>(minOf(raw.size, cap))
        var truncated = false
        for (f in raw) {
            val path = try { f.absolutePath } catch (t: Throwable) { continue }
            if (!seen.add(path)) continue
            if (out.size >= cap) { truncated = true; break }
            val name = f.name
            val isDir = try { f.isDirectory } catch (t: Throwable) { false }
            val size = if (isDir) 0L else try { f.length() } catch (t: Throwable) { 0L }
            out.add(PickerEntry(name, path, isDir, size, kindOf(name, isDir)))
        }
        out.sortWith(
            compareBy<PickerEntry>({ !it.isDir }, { it.name.lowercase() }, { it.name })
        )
        return PickerListing(dir.absolutePath, out, truncated)
    }

    /**
     * The folder "back" goes to, or null when [current] IS the root.
     *
     * Rooted deliberately: the picker browses the open project, so walking above it would list
     * the app's own private directories and invite attaching them.
     */
    fun parentOf(current: String, root: String): String? {
        val cur = File(current).absolutePath
        val base = File(root).absolutePath
        if (cur == base) return null
        if (!isInsideRoot(cur, base)) return null
        val parent = File(cur).parentFile?.absolutePath ?: return null
        return if (isInsideRoot(parent, base)) parent else null
    }

    /** True when [path] is [root] itself or lives under it. Guards every navigation step. */
    fun isInsideRoot(path: String, root: String): Boolean {
        val p = File(path).absolutePath
        val r = File(root).absolutePath
        return p == r || p.startsWith(r.trimEnd('/') + "/")
    }

    /** Human size for a tile caption. */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> bytes.toString() + " B"
        bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
