package com.codespace.ide.ui.panes

import java.io.File
import java.io.RandomAccessFile

/**
 * FileDetector — detects file type using magic bytes (file signature),
 * file extension, and MIME type mapping.
 *
 * Foundation for Phase 21 (Universal File Viewer System).
 * Every file opened in the IDE goes through this detector to determine
 * which viewer to use.
 */

data class FileTypeInfo(
    val fileName: String,
    val fileSize: Long,
    val extension: String,
    val mimeType: String,
    val detectedFormat: FileFormat,
    val encoding: String,
    val lastModified: Long,
    val detectionConfidence: DetectionConfidence,
    val magicBytesHex: String,
    val isText: Boolean,
    val isBinary: Boolean,
    val isArchive: Boolean,
    val isImage: Boolean,
    val isAudio: Boolean,
    val isVideo: Boolean,
    val isDocument: Boolean,
    val isDatabase: Boolean,
    val isCode: Boolean,
    val isFont: Boolean,
    val isCertificate: Boolean,
    val isApk: Boolean,
    val isElf: Boolean,
)

enum class FileFormat {
    UNKNOWN, TEXT, CODE, MARKDOWN, JSON, XML, YAML, HTML, CSV,
    PDF, DOCX, EPUB, RTF,
    PNG, JPG, JPEG, GIF, BMP, WEBP, SVG, ICO,
    MP3, WAV, OGG, FLAC, AAC, M4A,
    MP4, WEBM, MKV, AVI, MOV,
    ZIP, RAR, SEVEN_Z, TAR, GZIP, JAR, AAR, APK,
    SQLITE,
    TTF, OTF, WOFF,
    PEM, DER, JKS, KEYSTORE,
    ELF, DEX, OAT, VDEX,
    BINARY,
}

enum class DetectionConfidence { HIGH, MEDIUM, LOW }

object FileDetector {

    // ── Magic byte signatures ──────────────────────────────────────────────
    private data class Signature(
        val offset: Int,
        val bytes: ByteArray,
        val format: FileFormat,
        val mask: ByteArray? = null
    ) {
        override fun equals(other: Any?) = other is Signature && offset == other.offset && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * offset + bytes.contentHashCode()
    }

    private val signatures = listOf(
        Signature(0, byteArrayOf(0x25, 0x50, 0x44, 0x46), FileFormat.PDF),           // %PDF
        Signature(0, byteArrayOf(0x50, 0x4B, 0x03, 0x04), FileFormat.ZIP),             // PK\x03\x04 (ZIP, JAR, AAR, APK, DOCX, EPUB)
        Signature(0, byteArrayOf(0x50, 0x4B, 0x05, 0x06), FileFormat.ZIP),             // PK\x05\x06 (empty ZIP)
        Signature(0, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07), FileFormat.RAR), // Rar!
        Signature(0, byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C), FileFormat.SEVEN_Z), // 7z
        Signature(0, byteArrayOf(0x1F, 0x8B.toByte()), FileFormat.GZIP),                          // GZIP
        Signature(0, byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65), FileFormat.SQLITE), // SQLite
        Signature(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), FileFormat.JPEG),                    // JPEG
        Signature(0, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), FileFormat.PNG),             // PNG
        Signature(0, byteArrayOf(0x47, 0x49, 0x46, 0x38), FileFormat.GIF),             // GIF8
        Signature(0, byteArrayOf(0x42, 0x4D), FileFormat.BMP),                           // BM (BMP)
        Signature(0, byteArrayOf(0x52, 0x49, 0x46, 0x46), FileFormat.WEBP),             // RIFF (WEBP/WAV/AVI)
        Signature(0, byteArrayOf(0xFF.toByte(), 0xFB.toByte()), FileFormat.MP3),                           // MP3
        Signature(0, byteArrayOf(0x49, 0x44, 0x33), FileFormat.MP3),                     // ID3 (MP3)
        Signature(0, byteArrayOf(0x4F, 0x67, 0x67, 0x53), FileFormat.OGG),             // OggS
        Signature(0, byteArrayOf(0x66, 0x4C, 0x61, 0x43), FileFormat.FLAC),             // fLaC
        Signature(0, byteArrayOf(0x00, 0x00, 0x01, 0x00), FileFormat.ICO),              // ICO
        Signature(0, byteArrayOf(0x00, 0x01, 0x00, 0x00), FileFormat.TTF),             // TTF
        Signature(0, byteArrayOf(0x4F, 0x54, 0x54, 0x4F), FileFormat.OTF),             // OTTO (OTF)
        Signature(0, byteArrayOf(0x77, 0x4F, 0x46, 0x46), FileFormat.WOFF),            // wOFF
        Signature(0, byteArrayOf(0x7F, 0x45, 0x4C, 0x46), FileFormat.ELF),             // \x7fELF
        Signature(0, byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35), FileFormat.DEX), // dex\n035
        Signature(0, byteArrayOf(0x2D, 0x2D, 0x2D, 0x2D, 0x2D), FileFormat.PEM),       // ----- (PEM cert/key)
        Signature(0, byteArrayOf(0x30, 0x82.toByte()), FileFormat.DER),                          // DER cert
        Signature(0, byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte()), FileFormat.KEYSTORE),        // JKS keystore
    )

    // ── Extension to format mapping ───────────────────────────────────────
    private val extensionMap = mapOf(
        "txt" to FileFormat.TEXT, "log" to FileFormat.TEXT,
        "kt" to FileFormat.CODE, "java" to FileFormat.CODE, "py" to FileFormat.CODE,
        "c" to FileFormat.CODE, "cpp" to FileFormat.CODE, "h" to FileFormat.CODE,
        "js" to FileFormat.CODE, "ts" to FileFormat.CODE, "go" to FileFormat.CODE,
        "rs" to FileFormat.CODE, "rb" to FileFormat.CODE, "sh" to FileFormat.CODE,
        "swift" to FileFormat.CODE, "scala" to FileFormat.CODE, "lua" to FileFormat.CODE,
        "md" to FileFormat.MARKDOWN, "markdown" to FileFormat.MARKDOWN,
        "json" to FileFormat.JSON,
        "xml" to FileFormat.XML,
        "yaml" to FileFormat.YAML, "yml" to FileFormat.YAML,
        "html" to FileFormat.HTML, "htm" to FileFormat.HTML,
        "csv" to FileFormat.CSV,
        "pdf" to FileFormat.PDF,
        "docx" to FileFormat.DOCX, "doc" to FileFormat.DOCX,
        "epub" to FileFormat.EPUB,
        "rtf" to FileFormat.RTF,
        "png" to FileFormat.PNG, "jpg" to FileFormat.JPG, "jpeg" to FileFormat.JPEG,
        "gif" to FileFormat.GIF, "bmp" to FileFormat.BMP, "webp" to FileFormat.WEBP,
        "svg" to FileFormat.SVG, "ico" to FileFormat.ICO,
        "mp3" to FileFormat.MP3, "wav" to FileFormat.WAV, "ogg" to FileFormat.OGG,
        "flac" to FileFormat.FLAC, "aac" to FileFormat.AAC, "m4a" to FileFormat.M4A,
        "mp4" to FileFormat.MP4, "webm" to FileFormat.WEBM, "mkv" to FileFormat.MKV,
        "avi" to FileFormat.AVI, "mov" to FileFormat.MOV,
        "zip" to FileFormat.ZIP, "rar" to FileFormat.RAR, "7z" to FileFormat.SEVEN_Z,
        "tar" to FileFormat.TAR, "gz" to FileFormat.GZIP, "tgz" to FileFormat.GZIP,
        "jar" to FileFormat.JAR, "aar" to FileFormat.AAR, "apk" to FileFormat.APK,
        "db" to FileFormat.SQLITE, "sqlite" to FileFormat.SQLITE, "sqlite3" to FileFormat.SQLITE,
        "ttf" to FileFormat.TTF, "otf" to FileFormat.OTF, "woff" to FileFormat.WOFF,
        "pem" to FileFormat.PEM, "crt" to FileFormat.DER, "der" to FileFormat.DER,
        "jks" to FileFormat.KEYSTORE, "keystore" to FileFormat.KEYSTORE,
        "so" to FileFormat.ELF, "dex" to FileFormat.DEX,
        "oat" to FileFormat.OAT, "vdex" to FileFormat.VDEX,
    )

    // ── MIME type mapping ─────────────────────────────────────────────────
    private val mimeMap = mapOf(
        FileFormat.TEXT to "text/plain",
        FileFormat.CODE to "text/plain",
        FileFormat.MARKDOWN to "text/markdown",
        FileFormat.JSON to "application/json",
        FileFormat.XML to "application/xml",
        FileFormat.YAML to "text/yaml",
        FileFormat.HTML to "text/html",
        FileFormat.CSV to "text/csv",
        FileFormat.PDF to "application/pdf",
        FileFormat.PNG to "image/png",
        FileFormat.JPEG to "image/jpeg",
        FileFormat.JPG to "image/jpeg",
        FileFormat.GIF to "image/gif",
        FileFormat.BMP to "image/bmp",
        FileFormat.WEBP to "image/webp",
        FileFormat.SVG to "image/svg+xml",
        FileFormat.ICO to "image/x-icon",
        FileFormat.MP3 to "audio/mpeg",
        FileFormat.WAV to "audio/wav",
        FileFormat.OGG to "audio/ogg",
        FileFormat.FLAC to "audio/flac",
        FileFormat.MP4 to "video/mp4",
        FileFormat.WEBM to "video/webm",
        FileFormat.ZIP to "application/zip",
        FileFormat.RAR to "application/x-rar-compressed",
        FileFormat.SEVEN_Z to "application/x-7z-compressed",
        FileFormat.GZIP to "application/gzip",
        FileFormat.JAR to "application/java-archive",
        FileFormat.AAR to "application/android-archive",
        FileFormat.APK to "application/vnd.android.package-archive",
        FileFormat.SQLITE to "application/x-sqlite3",
        FileFormat.TTF to "font/ttf",
        FileFormat.OTF to "font/otf",
        FileFormat.ELF to "application/x-elf",
        FileFormat.DEX to "application/x-dex",
        FileFormat.PEM to "application/x-pem-file",
        FileFormat.DER to "application/x-x509-cert",
    )

    // ── Code extensions for syntax highlighting detection ──────────────────
    private val codeExtensions = setOf(
        "kt", "java", "py", "c", "cpp", "h", "hpp", "js", "ts", "go", "rs", "rb",
        "sh", "swift", "scala", "lua", "xml", "json", "yaml", "yml", "html", "htm",
        "css", "scss", "sql", "gradle", "kts", "toml", "ini", "cfg", "conf"
    )

    fun detect(file: File): FileTypeInfo {
        val ext = file.extension.lowercase()
        val fileSize = file.length()
        val lastModified = file.lastModified()

        // Read first 16 bytes for magic byte detection
        val (magicBytes, magicHex) = readMagicBytes(file, 16)

        // Try magic byte detection first (highest confidence)
        var detectedFormat = FileFormat.UNKNOWN
        var confidence = DetectionConfidence.LOW

        for (sig in signatures) {
            if (magicBytes.size >= sig.offset + sig.bytes.size) {
                val match = sig.bytes.indices.all { magicBytes[sig.offset + it] == sig.bytes[it] }
                if (match) {
                    detectedFormat = sig.format
                    confidence = DetectionConfidence.HIGH
                    break
                }
            }
        }

        // Fall back to extension-based detection (medium confidence)
        if (detectedFormat == FileFormat.UNKNOWN) {
            extensionMap[ext]?.let {
                detectedFormat = it
                confidence = DetectionConfidence.MEDIUM
            }
        }

        // Special case: ZIP-based formats (DOCX, EPUB, JAR, AAR, APK are all ZIP)
        if (detectedFormat == FileFormat.ZIP) {
            when (ext) {
                "apk" -> { detectedFormat = FileFormat.APK; confidence = DetectionConfidence.HIGH }
                "jar" -> { detectedFormat = FileFormat.JAR; confidence = DetectionConfidence.HIGH }
                "aar" -> { detectedFormat = FileFormat.AAR; confidence = DetectionConfidence.HIGH }
                "docx" -> { detectedFormat = FileFormat.DOCX; confidence = DetectionConfidence.HIGH }
                "epub" -> { detectedFormat = FileFormat.EPUB; confidence = DetectionConfidence.HIGH }
            }
        }

        // RIFF can be WEBP, WAV, or AVI — check extension
        if (magicBytes.size >= 12 && magicBytes.sliceArray(0..3).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46))) {
            val fourcc = magicBytes.sliceArray(8..11)
            when {
                fourcc.contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) -> { detectedFormat = FileFormat.WEBP; confidence = DetectionConfidence.HIGH }
                fourcc.contentEquals(byteArrayOf(0x57, 0x41, 0x56, 0x45)) -> { detectedFormat = FileFormat.WAV; confidence = DetectionConfidence.HIGH }
                fourcc.contentEquals(byteArrayOf(0x41, 0x56, 0x49, 0x20)) -> { detectedFormat = FileFormat.AVI; confidence = DetectionConfidence.HIGH }
            }
        }

        // Detect text vs binary
        val isText = isTextFile(file, detectedFormat, ext)
        val isBinary = !isText && detectedFormat != FileFormat.UNKNOWN

        // Determine encoding
        val encoding = if (isText) detectEncoding(file) else "binary"

        val mimeType = mimeMap[detectedFormat] ?: if (isText) "text/plain" else "application/octet-stream"

        return FileTypeInfo(
            fileName = file.name,
            fileSize = fileSize,
            extension = ext,
            mimeType = mimeType,
            detectedFormat = detectedFormat,
            encoding = encoding,
            lastModified = lastModified,
            detectionConfidence = confidence,
            magicBytesHex = magicHex,
            isText = isText,
            isBinary = isBinary,
            isArchive = detectedFormat in setOf(FileFormat.ZIP, FileFormat.RAR, FileFormat.SEVEN_Z, FileFormat.TAR, FileFormat.GZIP, FileFormat.JAR, FileFormat.AAR, FileFormat.APK),
            isImage = detectedFormat in setOf(FileFormat.PNG, FileFormat.JPG, FileFormat.JPEG, FileFormat.GIF, FileFormat.BMP, FileFormat.WEBP, FileFormat.SVG, FileFormat.ICO),
            isAudio = detectedFormat in setOf(FileFormat.MP3, FileFormat.WAV, FileFormat.OGG, FileFormat.FLAC, FileFormat.AAC, FileFormat.M4A),
            isVideo = detectedFormat in setOf(FileFormat.MP4, FileFormat.WEBM, FileFormat.MKV, FileFormat.AVI, FileFormat.MOV),
            isDocument = detectedFormat in setOf(FileFormat.PDF, FileFormat.DOCX, FileFormat.EPUB, FileFormat.RTF),
            isDatabase = detectedFormat == FileFormat.SQLITE,
            isCode = ext in codeExtensions,
            isFont = detectedFormat in setOf(FileFormat.TTF, FileFormat.OTF, FileFormat.WOFF),
            isCertificate = detectedFormat in setOf(FileFormat.PEM, FileFormat.DER, FileFormat.KEYSTORE),
            isApk = detectedFormat == FileFormat.APK,
            isElf = detectedFormat == FileFormat.ELF,
        )
    }

    private fun readMagicBytes(file: File, count: Int): Pair<ByteArray, String> {
        return try {
            val raf = RandomAccessFile(file, "r")
            val buf = ByteArray(minOf(count, raf.length().toInt().coerceAtLeast(0)))
            raf.readFully(buf)
            raf.close()
            buf to buf.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            ByteArray(0) to ""
        }
    }

    private fun isTextFile(file: File, format: FileFormat, ext: String): Boolean {
        // Known text formats
        if (format in setOf(FileFormat.TEXT, FileFormat.CODE, FileFormat.MARKDOWN, FileFormat.JSON, FileFormat.XML, FileFormat.YAML, FileFormat.HTML, FileFormat.CSV, FileFormat.SVG, FileFormat.PEM)) return true
        // Known binary formats
        if (format in setOf(FileFormat.PDF, FileFormat.PNG, FileFormat.JPG, FileFormat.JPEG, FileFormat.GIF, FileFormat.BMP, FileFormat.WEBP, FileFormat.ICO, FileFormat.MP3, FileFormat.WAV, FileFormat.OGG, FileFormat.FLAC, FileFormat.MP4, FileFormat.WEBM, FileFormat.MKV, FileFormat.AVI, FileFormat.MOV, FileFormat.ZIP, FileFormat.RAR, FileFormat.SEVEN_Z, FileFormat.GZIP, FileFormat.SQLITE, FileFormat.TTF, FileFormat.OTF, FileFormat.WOFF, FileFormat.ELF, FileFormat.DEX, FileFormat.DER, FileFormat.KEYSTORE)) return false
        // Heuristic: read first 1KB and check for null bytes
        return try {
            val bytes = ByteArray(1024)
            val raf = RandomAccessFile(file, "r")
            val read = raf.read(bytes)
            raf.close()
            if (read <= 0) return true
            val sample = bytes.sliceArray(0 until read)
            // If >10% null bytes, it's binary
            val nullCount = sample.count { it == 0.toByte() }
            nullCount < sample.size / 10
        } catch (e: Exception) {
            ext.isNotBlank() && !ext.contains("bin")
        }
    }

    private fun detectEncoding(file: File): String {
        return try {
            val raf = RandomAccessFile(file, "r")
            val buf = ByteArray(4)
            val read = raf.read(buf)
            raf.close()
            if (read >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) "UTF-8 (BOM)"
            else if (read >= 2 && buf[0] == 0xFF.toByte() && buf[1] == 0xFE.toByte()) "UTF-16LE"
            else if (read >= 2 && buf[0] == 0xFE.toByte() && buf[1] == 0xFF.toByte()) "UTF-16BE"
            else "UTF-8"
        } catch (e: Exception) {
            "UTF-8"
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}