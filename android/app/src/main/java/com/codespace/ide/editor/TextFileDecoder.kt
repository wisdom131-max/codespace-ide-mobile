package com.codespace.ide.editor

import java.io.File

/**
 * A1-device-fix (advisor 2026-10-26, part 2): BOM-aware text decoding at the
 * editor read seam. The shared classifier now (correctly) opens UTF-16 source
 * files as TEXT, but FileCache's plain `readText()` decoded everything as UTF-8
 * with replacement chars — the owner saw raw bytes for a UTF-16 .java that the
 * classifier had already cleared. VS Code parity (src/vs/workbench/services/
 * textfile/common/encoding.ts, detectEncodingByBOMFromBuffer): the BOM decides
 * the decode BEFORE any text is produced.
 *
 * - UTF-16 LE (FF FE) and UTF-16 BE (FE FF) decode with their charset, BOM stripped.
 * - UTF-8 BOM (EF BB BF) decodes as UTF-8, BOM stripped.
 * - No BOM: plain UTF-8 (previous behavior unchanged).
 *
 * Without a BOM we never guess UTF-16 (same choice VS Code makes); the
 * classifier's pair-heuristic handles request-side classification, and the
 * binary dialog's "Open anyway as text" still gives an explicit escape hatch.
 */
object TextFileDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun readText(path: String): String = decode(File(path).readBytes())
}
