package com.codespace.ide.chat

/**
 * C12 STAGE 3 — U1 (advisor GO 2026-10-20): attachment TYPE DETECTION +
 * CAPABILITY DISCLOSURE. PURE — no I/O; callers hand in the file name and the
 * first bytes they already read. The verdict drives (a) the disclosure line the
 * chips/sheet show BEFORE anything is sent ("what will be sent and what the
 * current model can use"), and (b) which U2 extractor runs.
 *
 * Safety stance (advisor items 1/2): legacy binary Office (.doc/.xls/.ppt) and
 * macro formats are detected and HONESTLY labeled — never junk, never executed.
 * Encrypted OOXML cannot be told apart from a normal zip by magic alone; the
 * U2 extractor detects the EncryptedPackage entry and reports it.
 */
enum class AttachmentTypeKind {
    TEXT, HTML, CSV, RTF,
    DOCX, XLSX, PPTX, DOCM, XLSM, ODT, ODS, ODP,
    PDF, IMAGE, AUDIO, VIDEO,
    ZIP, TAR, GZIP,
    LEGACY_OFFICE, OTHER_BINARY,
}

/** What the pipeline can DO with the file today (capability disclosure source). */
enum class AttachmentExtraction {
    /** Plain text rides the existing snapshot path (no extractor). */
    TEXT_SNAPSHOT,
    /** U2 document extractor produces text. */
    DOCUMENT,
    /** U3 archive lister produces entries + per-entry text preview. */
    ARCHIVE,
    /** Later phase (U4 PDF / U5 media) — metadata only for now. */
    LATER_PHASE,
    /** Never extracted — honest metadata-only verdict with a reason. */
    UNSUPPORTED,
}

data class TypeVerdict(
    val kind: AttachmentTypeKind,
    val label: String,
    val extraction: AttachmentExtraction,
    /** Plain-words reason shown to the owner (disclosure, U1 spec). */
    val reason: String,
)

object AttachmentTypeSniffer {

    const val SNIFF_WINDOW_BYTES = 8

    /** Extension → (kind, human label). Magic bytes OVERRIDE the extension. */
    private val BY_EXT = mapOf(
        "txt" to kind(AttachmentTypeKind.TEXT, "Text file"),
        "log" to kind(AttachmentTypeKind.TEXT, "Log file"),
        "md" to kind(AttachmentTypeKind.TEXT, "Markdown text"),
        "json" to kind(AttachmentTypeKind.TEXT, "JSON text"),
        "yml" to kind(AttachmentTypeKind.TEXT, "YAML text"),
        "yaml" to kind(AttachmentTypeKind.TEXT, "YAML text"),
        "xml" to kind(AttachmentTypeKind.TEXT, "XML text"),
        "csv" to kind(AttachmentTypeKind.CSV, "CSV table"),
        "tsv" to kind(AttachmentTypeKind.CSV, "TSV table"),
        "html" to kind(AttachmentTypeKind.HTML, "HTML page"),
        "htm" to kind(AttachmentTypeKind.HTML, "HTML page"),
        "rtf" to kind(AttachmentTypeKind.RTF, "RTF document"),
        "docx" to kind(AttachmentTypeKind.DOCX, "Word document"),
        "xlsx" to kind(AttachmentTypeKind.XLSX, "Excel workbook"),
        "pptx" to kind(AttachmentTypeKind.PPTX, "PowerPoint deck"),
        "docm" to kind(AttachmentTypeKind.DOCM, "Word macro document"),
        "xlsm" to kind(AttachmentTypeKind.XLSM, "Excel macro workbook"),
        "odt" to kind(AttachmentTypeKind.ODT, "OpenDocument text"),
        "ods" to kind(AttachmentTypeKind.ODS, "OpenDocument spreadsheet"),
        "odp" to kind(AttachmentTypeKind.ODP, "OpenDocument presentation"),
        "pdf" to kind(AttachmentTypeKind.PDF, "PDF document"),
        "jpg" to kind(AttachmentTypeKind.IMAGE, "Image"),
        "jpeg" to kind(AttachmentTypeKind.IMAGE, "Image"),
        "png" to kind(AttachmentTypeKind.IMAGE, "Image"),
        "gif" to kind(AttachmentTypeKind.IMAGE, "Image"),
        "webp" to kind(AttachmentTypeKind.IMAGE, "Image"),
        "mp3" to kind(AttachmentTypeKind.AUDIO, "Audio"),
        "wav" to kind(AttachmentTypeKind.AUDIO, "Audio"),
        "m4a" to kind(AttachmentTypeKind.AUDIO, "Audio"),
        "ogg" to kind(AttachmentTypeKind.AUDIO, "Audio"),
        "mp4" to kind(AttachmentTypeKind.VIDEO, "Video"),
        "webm" to kind(AttachmentTypeKind.VIDEO, "Video"),
        "zip" to kind(AttachmentTypeKind.ZIP, "Zip archive"),
        "apk" to kind(AttachmentTypeKind.ZIP, "Android package (zip)"),
        "jar" to kind(AttachmentTypeKind.ZIP, "Java archive (zip)"),
        "tar" to kind(AttachmentTypeKind.TAR, "Tar archive"),
        "gz" to kind(AttachmentTypeKind.GZIP, "Gzip archive"),
        "tgz" to kind(AttachmentTypeKind.GZIP, "Gzipped tar archive"),
        "doc" to kind(AttachmentTypeKind.LEGACY_OFFICE, "Legacy Word document"),
        "xls" to kind(AttachmentTypeKind.LEGACY_OFFICE, "Legacy Excel workbook"),
        "ppt" to kind(AttachmentTypeKind.LEGACY_OFFICE, "Legacy PowerPoint deck"),
    )

    private fun kind(k: AttachmentTypeKind, label: String) = k to label

    /**
     * Sniff [fileName] plus up to [SNIFF_WINDOW_BYTES] leading bytes. Extension
     * picks the candidate; magic bytes OVERRIDE when they disagree (a .docx
     * that is really a PNG is an image; a .png that is really a zip follows
     * the zip family). Unknown extension + unknown magic = OTHER_BINARY.
     */
    /**
     * A1 (advisor 2026-10-24): language-source extensions. Unlike BY_EXT
     * (the name wins outright), source extensions are TEXT-PREFERRED: the
     * name proposes text and the CONTENT still vetoes real binaries (NULs
     * without a BOM or UTF-16 pattern). This is what keeps a real binary
     * renamed to .java in the honest dialog while a UTF-16 source file opens
     * normally. List mirrors the Explorer's runnable-language table.
     */
    private val SOURCE_EXTS = setOf(
        "java", "kt", "kts", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift",
        "scala", "sh", "bash", "zsh", "sql", "ini", "toml", "gradle",
        "properties", "ps1", "bat", "dart", "lua", "pl", "r", "jl", "vue", "svelte",
    )

    fun sniff(fileName: String, magic: ByteArray?): TypeVerdict {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // A1: keep the EMPTY array — a zero-byte file is text (VS Code parity;
        // encoding.ts treats absence of bytes as absence of evidence). Only a
        // null window (read unavailable) stays unknown.
        val m = magic
        val (kind, label) = when {
            isZip(m) -> zipFamily(ext)               // magic first: a real zip
            isGzip(m) -> (AttachmentTypeKind.GZIP to "Gzip archive")
            isTar(m) -> (AttachmentTypeKind.TAR to "Tar archive")
            isPdf(m) -> (AttachmentTypeKind.PDF to "PDF document")
            isImage(m) -> (AttachmentTypeKind.IMAGE to "Image")
            isAudio(m) -> (AttachmentTypeKind.AUDIO to "Audio")
            isVideo(m) -> (AttachmentTypeKind.VIDEO to "Video")
            else -> {
                val src = if (ext in SOURCE_EXTS) (AttachmentTypeKind.TEXT to "Source code") else null
                src?.takeIf { looksTexty(m) }
                    ?: BY_EXT[ext]
                    ?: (if (looksTexty(m)) (AttachmentTypeKind.TEXT to "File")
                        else (AttachmentTypeKind.OTHER_BINARY to "Binary file"))
            }
        }
        return verdictOf(kind, label)
    }

    private val ZIP_FAMILY_KINDS = setOf(
        AttachmentTypeKind.DOCX, AttachmentTypeKind.XLSX, AttachmentTypeKind.PPTX,
        AttachmentTypeKind.DOCM, AttachmentTypeKind.XLSM,
        AttachmentTypeKind.ODT, AttachmentTypeKind.ODS, AttachmentTypeKind.ODP,
        AttachmentTypeKind.ZIP,
    )

    /** Zip magic + zip-family extension (docx/xlsx/odt...) keeps the specific
     *  kind; zip magic + a NON-zip extension (.png, .txt...) follows the
     *  MAGIC — the bytes are a zip, whatever the name claims. */
    private fun zipFamily(ext: String): Pair<AttachmentTypeKind, String> {
        val entry = BY_EXT[ext] ?: return (AttachmentTypeKind.ZIP to "Zip archive")
        return if (entry.first in ZIP_FAMILY_KINDS) entry
        else (AttachmentTypeKind.ZIP to "Zip archive")
    }

    private fun isZip(m: ByteArray?) = m != null && m.size >= 4 &&
        m[0] == 'P'.code.toByte() && m[1] == 'K'.code.toByte() &&
        (m[2] == 3.toByte() || m[2] == 5.toByte() || m[2] == 7.toByte()) &&
        (m[3] == 4.toByte() || m[3] == 6.toByte() || m[3] == 8.toByte())

    private fun isGzip(m: ByteArray?) = m != null && m.size >= 2 &&
        m[0] == 0x1f.toByte() && m[1] == 0x8b.toByte()

    private fun isTar(m: ByteArray?): Boolean {
        // USTAR magic sits at offset 257 — beyond the 8-byte window; the
        // extension drives tar, and the fallback keeps it honest.
        return false
    }

    private fun isPdf(m: ByteArray?) = m != null && m.size >= 4 &&
        m[0] == '%'.code.toByte() && m[1] == 'P'.code.toByte() &&
        m[2] == 'D'.code.toByte() && m[3] == 'F'.code.toByte()

    private fun isImage(m: ByteArray?) = m != null && (
        (m.size >= 3 && m[0] == 0xff.toByte() && m[1] == 0xd8.toByte() && m[2] == 0xff.toByte()) ||
        (m.size >= 8 && m[0] == 0x89.toByte() && m[1] == 'P'.code.toByte() && m[2] == 'N'.code.toByte() && m[3] == 'G'.code.toByte()) ||
        (m.size >= 6 && (m[0] == 'G'.code.toByte() && m[1] == 'I'.code.toByte() && m[2] == 'F'.code.toByte()))
    )

    private fun isAudio(m: ByteArray?) = m != null && m.size >= 3 &&
        m[0] == 'I'.code.toByte() && m[1] == 'D'.code.toByte() && m[2] == '3'.code.toByte()

    private fun isVideo(m: ByteArray?) = m != null && m.size >= 8 &&
        m[4] == 'f'.code.toByte() && m[5] == 't'.code.toByte() && m[6] == 'y'.code.toByte() && m[7] == 'p'.code.toByte()

    /**
     * A1 (advisor 2026-10-24): BOM-aware text test, modeled on VS Code
     * encoding.ts (UTF8_BOM at encoding.ts:25, detectEncodingByBOMFromBuffer
     * at encoding.ts:280). Order: empty = text; UTF-8 / UTF-16 LE / BE BOMs =
     * text regardless of NULs; no NUL = text; NULs = binary UNLESS every pair
     * in the window matches the byte,0 (LE) or 0,byte (BE) pattern of ASCII
     * text in BOM-less UTF-16 — the documented heuristic. VS Code decodes
     * UTF-16 only with a BOM; this best-effort analog plus the binary
     * dialog's Open-anyway escape hatch means a wrong call never blocks.
     */
    private fun looksTexty(m: ByteArray?): Boolean {
        if (m == null) return false
        if (m.isEmpty()) return true
        if (m.size >= 3 && m[0] == 0xEF.toByte() && m[1] == 0xBB.toByte() && m[2] == 0xBF.toByte()) return true
        if (m.size >= 2 && ((m[0] == 0xFF.toByte() && m[1] == 0xFE.toByte()) ||
                (m[0] == 0xFE.toByte() && m[1] == 0xFF.toByte()))) return true
        var hasNul = false
        for (b in m) if (b == 0.toByte()) { hasNul = true; break }
        if (!hasNul) return true
        var le = true
        var be = true
        var i = 0
        while (i + 1 < m.size) {
            if (m[i + 1] != 0.toByte()) le = false
            if (m[i] != 0.toByte()) be = false
            i += 2
        }
        return le || be
    }

    /** Capability + honesty, one place. The chips and sheet show this line. */
    fun verdictOf(kind: AttachmentTypeKind, label: String): TypeVerdict = when (kind) {
        AttachmentTypeKind.TEXT, AttachmentTypeKind.HTML, AttachmentTypeKind.CSV, AttachmentTypeKind.RTF -> TypeVerdict(
            kind, label, AttachmentExtraction.DOCUMENT,
            "Text is sent as an editable copy; non-UTF-8 encodings are decoded first")
        AttachmentTypeKind.DOCX, AttachmentTypeKind.XLSX, AttachmentTypeKind.PPTX,
        AttachmentTypeKind.ODT, AttachmentTypeKind.ODS, AttachmentTypeKind.ODP -> TypeVerdict(
            kind, label, AttachmentExtraction.DOCUMENT, "Document text will be extracted and sent to the model")
        AttachmentTypeKind.DOCM, AttachmentTypeKind.XLSM -> TypeVerdict(
            kind, label, AttachmentExtraction.DOCUMENT,
            "Read as text only — macros are NEVER executed")
        AttachmentTypeKind.ZIP, AttachmentTypeKind.TAR, AttachmentTypeKind.GZIP -> TypeVerdict(
            kind, label, AttachmentExtraction.ARCHIVE,
            "Entries will be listed; pick one to preview its text (never auto-opened)")
        AttachmentTypeKind.PDF -> TypeVerdict(
            kind, label, AttachmentExtraction.DOCUMENT,
            "PDF text will be extracted and sent to the model; scanned pages are sent as images when the provider accepts them")
        AttachmentTypeKind.IMAGE -> TypeVerdict(
            kind, label, AttachmentExtraction.LATER_PHASE, "Sent as an image part")
        AttachmentTypeKind.AUDIO -> TypeVerdict(
            kind, label, AttachmentExtraction.LATER_PHASE, "Sent as an audio part")
        AttachmentTypeKind.VIDEO -> TypeVerdict(
            kind, label, AttachmentExtraction.LATER_PHASE,
            "Video transcription ships in a later phase — metadata only for now")
        AttachmentTypeKind.LEGACY_OFFICE -> TypeVerdict(
            kind, label, AttachmentExtraction.UNSUPPORTED,
            "Legacy binary Office format — not supported. Showing file info only")
        AttachmentTypeKind.OTHER_BINARY -> TypeVerdict(
            kind, label, AttachmentExtraction.UNSUPPORTED,
            "Unrecognized binary — not supported. Showing file info only")
    }

    /** The one-line disclosure rendered by chips and the attach sheet. */
    fun capabilityLine(v: TypeVerdict): String = v.label + " — " + v.reason
}
