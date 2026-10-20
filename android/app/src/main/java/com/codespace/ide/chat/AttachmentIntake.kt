package com.codespace.ide.chat

import java.io.File

/**
 * C12 Stage 3 (advisor GO 2026-10-20): the attach-time INTAKE pipeline — one
 * place that turns a raw ChatAttachment into its sniffed verdict (U1) and,
 * from the next commits in this group, its extracted document/archive text
 * (U2/U3). All extraction runs off the main thread, cancellable and
 * time-limited; the pure extractors stay JVM-testable.
 */
object AttachmentIntake {

    const val EXTRACT_TIMEOUT_MS = 5_000L
    const val MAX_SOURCE_BYTES = 10L * 1024 * 1024       // 10 MB source cap
    const val MAX_EXTRACTED_SNAPSHOT_CHARS = 60_000       // preview/snapshot cap
    const val MAX_SEND_CHARS = ChatAttachmentInjector.SEND_CAP_PER_FILE  // 12,000

    /**
     * U1: the TypeVerdict for an attachment, from its kind + a bounded magic
     * read. Never throws; a missing file degrades to the extension verdict.
     */
    fun verdictOf(att: ChatAttachment): TypeVerdict {
        return when (att.kind) {
            ChatAttachment.Kind.IMAGE -> AttachmentTypeSniffer.verdictOf(AttachmentTypeKind.IMAGE, "Image")
            ChatAttachment.Kind.AUDIO -> AttachmentTypeSniffer.verdictOf(AttachmentTypeKind.AUDIO, "Audio")
            ChatAttachment.Kind.SELECTION -> AttachmentTypeSniffer.verdictOf(AttachmentTypeKind.TEXT, "Editor selection")
            ChatAttachment.Kind.FILE -> {
                val magic = try {
                    File(att.path).inputStream().use { ins ->
                        val buf = ByteArray(AttachmentTypeSniffer.SNIFF_WINDOW_BYTES)
                        val n = ins.read(buf)
                        if (n <= 0) null else buf.copyOf(n)
                    }
                } catch (_: Exception) { null }
                AttachmentTypeSniffer.sniff(att.name, magic)
            }
        }
    }

    /**
     * The extracted-content carrier. [text] is REDACTED (attachment tier)
     * extracted text; [notes] carries the honest markers ("showing X of Y",
     * sheet names); [truncated] means the text was cut by
     * [MAX_EXTRACTED_SNAPSHOT_CHARS]. [refusal] != null means nothing was
     * extracted (unsupported/encrypted/malformed/timeout) and the reason is
     * displayed instead — never junk.
     */
    data class ExtractedDoc(
        val text: String = "",
        val notes: List<String> = emptyList(),
        val truncated: Boolean = false,
        val refusal: String? = null,
    )

    /**
     * U2/U3 EXTRACTION — BLOCKING worker. MUST be called from a background
     * dispatcher; callers wrap in a timeout + cancellation (see
     * AttachmentExtractionEffects). Returns the refusal verdict rather than
     * throwing for every recoverable case.
     */
    fun extractBlocking(att: ChatAttachment): ExtractedDoc = try {
        val f = File(att.path)
        if (!f.isFile || !f.canRead()) return ExtractedDoc(refusal = "Could not read the file.")
        if (f.length() > MAX_SOURCE_BYTES) {
            return ExtractedDoc(refusal = "File is larger than 10 MB — showing file info only.")
        }
        val verdict = verdictOf(att)
        when (verdict.extraction) {
            AttachmentExtraction.DOCUMENT -> DocumentTextExtractor.extract(f, verdict)
            AttachmentExtraction.TEXT_SNAPSHOT, AttachmentExtraction.LATER_PHASE ->
                // text snapshot path / later phase: nothing to extract here
                ExtractedDoc("", refusal = null)
            AttachmentExtraction.UNSUPPORTED ->
                ExtractedDoc(refusal = verdict.reason)
        }
    } catch (e: Exception) {
        ExtractedDoc(refusal = "Extraction failed: ${e.message ?: "unreadable file"}")
    }
}
