package com.codespace.ide.chat

import java.io.File

/**
 * C12 STAGE 3 — U4 (advisor GO 2026-10-20): PDF ATTACHMENTS. Two engines, one
 * honest decision core:
 *
 *  - TEXT PDFs: Apache PDFBox (Tom Roush's Android port, pinned
 *    com.tom-roush:pdfbox-android:2.0.27.0, Apache-2.0, BC excluded — see
 *    build.gradle.kts). Text order/hyphenation is whatever PDFBox gives;
 *    multi-column layouts can read interleaved (stated limitation, honest).
 *  - SCANNED PDFs (no extractable text): page images rendered with Android's
 *    BUILT-IN PdfRenderer — ONLY when the active provider accepts images
 *    (the same supportsImages gate real image chips use); otherwise the honest
 *    metadata fallback with the reason.
 *
 * Safety stance (advisor items): encrypted/password PDFs refuse honestly (no
 * password prompts yet); no JavaScript execution, no link following, embedded
 * files and form actions are ignored (we never call into any such path); one
 * page at a time; bounded memory; OutOfMemoryError is caught and reported,
 * never crashes; the outer 5 s timeout (AttachmentIntake.EXTRACT_TIMEOUT_MS)
 * is backed by an internal deadline so a slow PDF returns PARTIAL text with an
 * honest "showing pages 1-N of M" marker instead of a bare timeout refusal.
 *
 * The DECISION CORE is pure and JVM-testable; the real pdfbox/PdfRenderer
 * objects (PdfBoxTextEngine / AndroidPdfPageRenderer) are injected seams —
 * pdfbox's font resources are Android ASSETS (PDFBoxResourceLoader), so the
 * real text engine can only be exercised on-device (fixtures sheet in docs).
 */
object PdfAttachmentExtractor {

    /** Page cap: never parse more than 50 pages of one attachment. */
    const val MAX_PAGES = 50
    /** Per-page text cap (chars) — one pathological page cannot fill the snapshot. */
    const val MAX_CHARS_PER_PAGE = 10_000
    /** Page images sent for a scanned PDF (first pages only). */
    const val MAX_RENDER_PAGES = 3
    /** Render target: page images are downscaled to this width. */
    const val RENDER_TARGET_WIDTH = 1_280
    /**
     * Internal deadline: the outer effect cancels at EXTRACT_TIMEOUT_MS (5 s);
     * the engine stops at 4.5 s so the run returns partial text + marker
     * instead of the bare "timed out" refusal.
     */
    const val INTERNAL_DEADLINE_MS = 4_500L

    /** Classified engine outcome — the real engine maps exceptions to these. */
    sealed class EngineOutcome {
        /**
         * Raw per-page text (already capped at [MAX_PAGES] + deadline by the
         * engine). [unreadable] lists the 1-BASED page numbers that threw
         * individually (salvage, advisor item 2, approved 2026-10-23): the
         * engine keeps reading the remaining pages instead of failing the
         * whole file, and the decision core turns the list into the honest
         * "(partial: X of Y pages unreadable)" marker. Blank placeholders keep
         * the pages list index-aligned.
         */
        data class Text(
            val pages: List<String>,
            val totalPageCount: Int,
            val unreadable: List<Int> = emptyList(),
        ) : EngineOutcome()
        /** Password-protected or encrypted document. */
        object Encrypted : EngineOutcome()
        /** Not a readable PDF at all. */
        data class Corrupted(val why: String) : EngineOutcome()
        /** Any other honest failure. */
        data class Failed(val why: String) : EngineOutcome()
    }

    fun interface PdfTextEngine {
        fun extract(file: File): EngineOutcome
    }

    /**
     * Renders one page (0-based) as PNG bytes downscaled to [targetWidthPx],
     * or null when that page could not be rendered. OutOfMemoryError
     * PROPAGATES — the decision core catches it and reports honestly.
     */
    fun interface PageImageRenderer {
        fun renderPage(file: File, pageIndex: Int, targetWidthPx: Int): ByteArray?
    }

    fun extract(
        file: File,
        imageDir: File,
        providerAcceptsImages: Boolean = true,
        engine: PdfTextEngine = PdfBoxTextEngine,
        renderer: PageImageRenderer = AndroidPdfPageRenderer,
    ): AttachmentIntake.ExtractedDoc {
        return try {
            when (val outcome = engine.extract(file)) {
                is EngineOutcome.Encrypted -> AttachmentIntake.ExtractedDoc(
                    refusal = "Encrypted or password-protected PDF — not supported yet. Showing file info only."
                )
                is EngineOutcome.Corrupted -> AttachmentIntake.ExtractedDoc(
                    refusal = "Not a readable PDF (${outcome.why}) — showing file info only."
                )
                is EngineOutcome.Failed -> AttachmentIntake.ExtractedDoc(
                    refusal = "PDF extraction failed (${outcome.why}) — showing file info only."
                )
                is EngineOutcome.Text -> finishText(outcome, file, imageDir, providerAcceptsImages, renderer)
            }
        } catch (e: OutOfMemoryError) {
            // Bounded by construction, but a hostile file still gets words,
            // never a crash (advisor item b).
            AttachmentIntake.ExtractedDoc(
                refusal = "Ran out of memory reading this PDF — showing file info only."
            )
        } catch (e: LinkageError) {
            // Defense in depth: an Error is NOT an Exception — without this
            // branch a linkage failure from any seam would crash the process.
            AttachmentIntake.ExtractedDoc(
                refusal = "PDF extraction failed (${e.message ?: e.javaClass.simpleName}) — showing file info only."
            )
        } catch (e: StackOverflowError) {
            // Defense in depth, same Error-not-Exception family: a seam that
            // recurses too deep gets words, never a crash.
            AttachmentIntake.ExtractedDoc(
                refusal = "PDF structure too deeply nested to read safely — showing file info only."
            )
        } catch (e: Exception) {
            AttachmentIntake.ExtractedDoc(refusal = "PDF extraction failed (${e.message ?: "unreadable file"}) — showing file info only.")
        }
    }

    private fun finishText(
        outcome: EngineOutcome.Text,
        file: File,
        imageDir: File,
        providerAcceptsImages: Boolean,
        renderer: PageImageRenderer,
    ): AttachmentIntake.ExtractedDoc {
        val notes = mutableListOf<String>()
        if (outcome.pages.size < outcome.totalPageCount) {
            notes.add("showing pages 1-${outcome.pages.size} of ${outcome.totalPageCount}")
        }
        val sb = StringBuilder()
        for ((i, page) in outcome.pages.withIndex()) {
            if (sb.length >= AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS) break
            if (i > 0) sb.append('\n')
            val capped = page.take(MAX_CHARS_PER_PAGE)
            sb.append(capped)
            if (capped.length < page.length) {
                notes.add("page ${i + 1} text truncated")
            }
        }
        val rawText = sb.toString()
        val scanned = outcome.totalPageCount > 0 && rawText.isBlank()
        // SALVAGE (advisor item 2, approved 2026-10-23): unreadable pages are
        // reported per page; the marker is never presented as a complete read.
        val unreadable = outcome.unreadable
        if (unreadable.isNotEmpty() && unreadable.size >= outcome.pages.size) {
            // EVERY page we attempted threw: honest refusal, never an
            // empty-string success, never a false "scanned" claim.
            return AttachmentIntake.ExtractedDoc(
                refusal = "No readable text was found in this PDF — " +
                    "${unreadable.size} of ${outcome.totalPageCount} pages could not be read; " +
                    "the file may be truncated or damaged — showing file info only."
            )
        }
        if (scanned) {
            return renderScanned(file, imageDir, outcome.totalPageCount, providerAcceptsImages, renderer, unreadable)
        }
        var text = if (rawText.length > AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
            rawText.take(AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
        else rawText
        val truncated = text.length < rawText.length
        if (truncated) notes.add("extracted text truncated at the snapshot cap")
        if (unreadable.isNotEmpty()) {
            // The honest partial marker rides the extracted text the model
            // receives AND the notes channel (one channel, mammoth-style) —
            // appended AFTER the cap so it can never be cut away.
            val marker = "(partial: ${unreadable.size} of ${outcome.totalPageCount} pages unreadable — " +
                "pages ${unreadable.joinToString(", ")})"
            text += "\n" + marker
            notes.add(marker)
        }
        // Same attachment-tier secrets pipeline as every extractor (U2/U3).
        val (redacted, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT)
        if (changed) notes.add("secrets redacted")
        return AttachmentIntake.ExtractedDoc(
            text = redacted,
            notes = notes,
            truncated = truncated,
        )
    }

    private fun renderScanned(
        file: File,
        imageDir: File,
        totalPageCount: Int,
        providerAcceptsImages: Boolean,
        renderer: PageImageRenderer,
        unreadable: List<Int> = emptyList(),
    ): AttachmentIntake.ExtractedDoc {
        // Wording fix (advisor item 2, approved 2026-10-23): blank text does
        // NOT prove "scanned" — the upstream lenient parser returns BLANK
        // text for a truncated file (CI-locked by
        // PdfTruncatedFixtureUpstreamTest). Never claim image-only alone.
        if (!providerAcceptsImages) {
            return AttachmentIntake.ExtractedDoc(
                refusal = "No readable text was found in this PDF — it may be truncated or image-only — " +
                    "and the active provider does not accept page images — showing file info only."
            )
        }
        val notes = mutableListOf("no readable text — the PDF may be truncated or image-only")
        if (unreadable.isNotEmpty()) {
            notes.add("${unreadable.size} page(s) could not be read (pages ${unreadable.joinToString(", ")})")
        }
        val paths = mutableListOf<String>()
        val count = minOf(MAX_RENDER_PAGES, totalPageCount)
        var oom = false
        for (i in 0 until count) {
            try {
                val png = renderer.renderPage(file, i, RENDER_TARGET_WIDTH) ?: continue
                val dir = File(imageDir, "pdf-pages").apply { mkdirs() }
                val out = File(dir, "page-${file.nameWithoutExtension}-$i.png")
                out.writeBytes(png)
                paths.add(out.absolutePath)
            } catch (_: OutOfMemoryError) {
                oom = true
                break
            }
        }
        if (paths.isEmpty()) {
            return if (oom) AttachmentIntake.ExtractedDoc(
                refusal = "Ran out of memory rendering this scanned PDF — showing file info only."
            ) else AttachmentIntake.ExtractedDoc(
                refusal = "No readable text was found in this PDF — it may be truncated or image-only — " +
                    "and no page could be rendered — showing file info only."
            )
        }
        notes.add("${paths.size} page image(s) rendered for the model")
        if (oom) notes.add("page rendering stopped early (out of memory)")
        return AttachmentIntake.ExtractedDoc(notes = notes, pageImages = paths)
    }

    /**
     * REAL text engine (device only — pdfbox font resources are Android
     * assets; see class KDoc). Loads with main-memory-only usage (no scratch
     * files on Android FUSE storage), walks pages one at a time under the
     * internal deadline, classifies every failure honestly.
     */
    object PdfBoxTextEngine : PdfTextEngine {
        override fun extract(file: File): EngineOutcome {
            var doc: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
            try {
                val start = System.currentTimeMillis()
                val deadline = start + INTERNAL_DEADLINE_MS
                doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(
                    file, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly()
                )
                val total = doc.numberOfPages
                val pages = mutableListOf<String>()
                val unreadable = mutableListOf<Int>()
                for (i in 1..minOf(total, MAX_PAGES)) {
                    if (System.currentTimeMillis() > deadline) break
                    // SALVAGE (advisor item 2, approved 2026-10-23): ONE page
                    // that throws (corrupt content stream, missing font,
                    // broken remnant) must never kill the whole file — the
                    // upstream parser silently produces blank text for many
                    // damaged files, but when it THROWS we keep the pages
                    // that DID read and report the rest as unreadable.
                    // Exception only: OOM/StackOverflowError still propagate
                    // to the honest process-level handlers below.
                    try {
                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                        stripper.setStartPage(i)
                        stripper.setEndPage(i)
                        // getText() appends a trailing newline per page; trim later.
                        pages.add(stripper.getText(doc))
                    } catch (e: Exception) {
                        unreadable.add(i)
                        pages.add("")
                    }
                }
                return EngineOutcome.Text(pages, total, unreadable)
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                return EngineOutcome.Encrypted
            } catch (e: java.security.GeneralSecurityException) {
                // Password-protected PDF with a cipher Android no longer ships
                // (e.g. RC4): decryption cannot run. That is still an encrypted
                // PDF from our side — refuse honestly, never Failed-junk.
                return EngineOutcome.Encrypted
            } catch (e: LinkageError) {
                // BouncyCastle is EXCLUDED from the build (see build.gradle.kts):
                // it exists only to DECRYPT encrypted PDFs, which we refuse
                // anyway — a BC reference (public-key handlers, SecurityProvider)
                // resolves to NoClassDefFoundError/ExceptionInInitializerError.
                // LinkageError, not just NoClassDefFoundError: an Error is NOT
                // an Exception, so the core's catch(Exception) would let it
                // CRASH the process if it slipped past here.
                if (isBouncyCastleMissing(e)) return EngineOutcome.Encrypted
                return EngineOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: java.io.IOException) {
                return EngineOutcome.Corrupted(e.message ?: "unreadable")
            } catch (e: OutOfMemoryError) {
                throw e // the decision core reports it
            } catch (e: StackOverflowError) {
                // A hostile PDF can nest its page tree / font program deep
                // enough to exhaust the thread stack inside pdfbox parsing.
                // StackOverflowError is a VirtualMachineError (an Error),
                // so catch(Exception) cannot stop it — refuse honestly.
                return EngineOutcome.Failed("PDF structure too deeply nested to read safely")
            } catch (e: Exception) {
                return EngineOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                try { doc?.close() } catch (_: Exception) {}
            }
        }

    /**
     * True when a [LinkageError] chain involves the excluded BouncyCastle
     * classes (direct reference, static-init failure, or a cause wrapped in
     * NoClassDefFoundError). Walks causes AND suppressed exceptions — a
     * class-not-found inside a static initializer reports as
     * ExceptionInInitializerError whose CAUSE is the ClassNotFoundException.
     */
    internal fun isBouncyCastleMissing(error: Throwable): Boolean {
        var depth = 0
        var t: Throwable? = error
        while (t != null && depth < 8) {
            val name = t.javaClass.name + " " + (t.message ?: "")
            if (name.contains("bouncycastle") || name.contains("spongycastle")) return true
            for (s in t.suppressed) {
                val sn = s.javaClass.name + " " + (s.message ?: "")
                if (sn.contains("bouncycastle") || sn.contains("spongycastle")) return true
            }
            t = t.cause
            depth++
        }
        return false
    }
    }

    /**
     * REAL page renderer (device only): Android's built-in PdfRenderer — one
     * page at a time, downscaled to the target width, bitmap recycled, OOM
     * left to propagate to the honest catch above.
     */
    object AndroidPdfPageRenderer : PageImageRenderer {
        override fun renderPage(file: File, pageIndex: Int, targetWidthPx: Int): ByteArray? {
            var pfd: android.os.ParcelFileDescriptor? = null
            var renderer: android.graphics.pdf.PdfRenderer? = null
            var page: android.graphics.pdf.PdfRenderer.Page? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = android.graphics.pdf.PdfRenderer(pfd)
                page = renderer.openPage(pageIndex)
                val scale = (targetWidthPx.toFloat() / page.width).coerceIn(1f, 2f)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                return baos.toByteArray()
            } catch (e: SecurityException) {
                return null // password-protected for the OS renderer — refused honestly upstream
            } catch (e: Exception) {
                return null // this page could not be rendered; honest notes cover it
            } finally {
                try { bitmap?.recycle() } catch (_: Exception) {}
                try { page?.close() } catch (_: Exception) {}
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
        }
    }
}
