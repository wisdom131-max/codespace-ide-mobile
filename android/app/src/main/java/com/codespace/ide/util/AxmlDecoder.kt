package com.codespace.ide.util

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin decoder for Android's compiled binary XML format (AXML) — the format
 * AndroidManifest.xml (and compiled layout/resource XML) is stored in inside an APK.
 * No native toolchain, no subprocess, no proot — this is exactly why it's safe on our
 * Samsung-kernel-restricted device: it's just parsing bytes we already streamed in.
 *
 * Format reference: standard AOSP ResChunk_header / ResStringPool / ResXMLTree chunks.
 * This is intentionally defensive — a malformed or unrecognized chunk is skipped rather
 * than crashing the whole decode, so partial output is still useful.
 */
object AxmlDecoder {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_XML_START_NS = 0x0100
    private const val CHUNK_XML_END_NS = 0x0101
    private const val CHUNK_XML_START_ELEMENT = 0x0102
    private const val CHUNK_XML_END_ELEMENT = 0x0103
    private const val CHUNK_XML_CDATA = 0x0104
    private const val CHUNK_XML_RESOURCE_MAP = 0x0180

    // Common android: manifest attribute resource IDs -> readable names.
    // Covers what you actually need to read a manifest; unknown IDs fall back to attr_0x....
    private val KNOWN_ATTRS = mapOf(
        0x01010003 to "name", 0x01010001 to "label", 0x01010002 to "icon",
        0x010100d0 to "theme", 0x0101021b to "roundIcon", 0x0101020c to "allowBackup",
        0x0101000e to "versionCode", 0x0101021c to "versionCode",
        0x0101021d to "versionName", 0x0101020d to "debuggable",
        0x0101020e to "requestLegacyExternalStorage", 0x0101020f to "usesCleartextTraffic",
        0x01010270 to "usesCleartextTraffic",
        0x01010006 to "permission",
        0x01010009 to "resource", 0x0101000c to "process",
        0x01010018 to "authorities", 0x0101001b to "exported",
        0x01010028 to "minSdkVersion", 0x0101020b to "targetSdkVersion",
        0x010102b0 to "maxSdkVersion", 0x01010035 to "value",
        0x0101001a to "grantUriPermissions", 0x0101024f to "supportsRtl",
        0x0101001d to "readPermission", 0x0101001e to "writePermission",
        0x01010021 to "protectionLevel", 0x0101003f to "launchMode",
        0x0101002c to "screenOrientation", 0x0101001f to "configChanges",
        0x010102ae to "resizeableActivity",
        0x01010064 to "scheme", 0x01010065 to "host", 0x01010066 to "port",
        0x01010067 to "path", 0x01010068 to "pathPrefix", 0x01010069 to "pathPattern",
        0x01010026 to "mimeType", 0x0101000a to "priority", 0x0101000d to "taskAffinity",
        0x01010045 to "windowSoftInputMode", 0x0101002d to "stateNotNeeded",
        0x01010010 to "multiprocess", 0x0101000b to "enabled",
        0x0101000f to "sharedUserId", 0x0101020a to "installLocation",
        0x0101003c to "gwpAsanMode",
        0x0101006e to "syncable", 0x01010457 to "banner",
        0x01010529 to "networkSecurityConfig",
        0x01010024 to "hardwareAccelerated",
    )

    data class Attr(val namespace: String?, val name: String, val value: String)
    sealed class Node {
        data class Element(
            val name: String,
            val namespace: String?,
            val attrs: MutableList<Attr> = mutableListOf(),
            val children: MutableList<Node> = mutableListOf(),
        ) : Node()
        data class Text(val text: String) : Node()
    }

    /** Decodes a compiled AndroidManifest.xml (or any AXML) InputStream into pretty-printed XML text. */
    fun decodeToXmlString(input: InputStream): String {
        val bytes = readBytesStreaming(input, maxSize = 8 * 1024 * 1024) // 8MB cap; manifests are tiny
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val root = parse(buf) ?: return "(Could not decode — not a valid compiled binary XML)"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        printNode(root, sb, 0)
        return sb.toString()
    }

    private fun printNode(node: Node, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        when (node) {
            is Node.Text -> sb.append(indent).append(escapeXml(node.text)).append('\n')
            is Node.Element -> {
                sb.append(indent).append('<').append(node.name)
                for (a in node.attrs) {
                    val prefix = if (a.namespace != null) "${a.namespace}:" else ""
                    sb.append(' ').append(prefix).append(a.name).append("=\"").append(escapeXml(a.value)).append('"')
                }
                if (node.children.isEmpty()) {
                    sb.append(" />\n")
                } else {
                    sb.append(">\n")
                    for (c in node.children) printNode(c, sb, depth + 1)
                    sb.append(indent).append("</").append(node.name).append(">\n")
                }
            }
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun parse(buf: ByteBuffer): Node.Element? {
        if (buf.remaining() < 8) return null
        val type = buf.short.toInt() and 0xFFFF
        buf.short // headerSize, unused at top level
        val totalSize = buf.int
        if (type != CHUNK_XML) return null

        var strings: List<String> = emptyList()
        var resourceIds: IntArray = IntArray(0)

        // Fake root wrapper so multiple top-level elements (rare) still work.
        val rootStack = ArrayDeque<Node.Element>()
        val virtualRoot = Node.Element(name = "__root__", namespace = null)
        rootStack.addLast(virtualRoot)

        val endPos = minOf(totalSize, buf.limit())
        while (buf.position() < endPos - 8) {
            val chunkStart = buf.position()
            if (buf.remaining() < 8) break
            val cType = buf.short.toInt() and 0xFFFF
            buf.short // headerSize
            val cSize = buf.int
            if (cSize <= 0 || chunkStart + cSize > buf.limit()) break
            val chunkEnd = chunkStart + cSize

            try {
                when (cType) {
                    CHUNK_STRING_POOL -> {
                        buf.position(chunkStart)
                        strings = parseStringPool(buf, chunkStart)
                    }
                    CHUNK_XML_RESOURCE_MAP -> {
                        val count = (cSize - 8) / 4
                        resourceIds = IntArray(count) { buf.int }
                    }
                    CHUNK_XML_START_NS, CHUNK_XML_END_NS -> {
                        buf.position(chunkEnd)
                    }
                    CHUNK_XML_START_ELEMENT -> {
                        buf.int // lineNumber
                        buf.int // comment
                        val nsIdx = buf.int
                        val nameIdx = buf.int
                        buf.short // attrStart
                        buf.short // attrSize
                        val attrCount = buf.short.toInt() and 0xFFFF
                        buf.short; buf.short; buf.short // idIndex, classIndex, styleIndex

                        val elemName = strings.getOrNull(nameIdx) ?: "unknown"
                        val elemNs = if (nsIdx >= 0) strings.getOrNull(nsIdx) else null
                        val elem = Node.Element(name = elemName, namespace = nsPrefix(elemNs))

                        repeat(attrCount) {
                            val aNsIdx = buf.int
                            val aNameIdx = buf.int
                            val aRawValueIdx = buf.int
                            buf.short // size
                            buf.get()  // res0
                            val dataType = buf.get().toInt() and 0xFF
                            val data = buf.int

                            val attrName = if (aNameIdx in strings.indices && strings[aNameIdx].isNotEmpty()) {
                                strings[aNameIdx]
                            } else {
                                val resId = resourceIds.getOrNull(aNameIdx) ?: 0
                                KNOWN_ATTRS[resId] ?: "attr_0x%08x".format(resId)
                            }
                            val attrNs = if (aNsIdx >= 0) strings.getOrNull(aNsIdx) else null
                            val value = decodeAttrValue(dataType, data, aRawValueIdx, strings)
                            elem.attrs.add(Attr(nsPrefix(attrNs), attrName, value))
                        }

                        rootStack.lastOrNull()?.children?.add(elem)
                        rootStack.addLast(elem)
                    }
                    CHUNK_XML_END_ELEMENT -> {
                        if (rootStack.size > 1) rootStack.removeLast()
                    }
                    CHUNK_XML_CDATA -> {
                        buf.int; buf.int // lineNumber, comment
                        val dataIdx = buf.int
                        buf.short; buf.get(); buf.get(); buf.int // typed value (8 bytes)
                        val text = strings.getOrNull(dataIdx)
                        if (!text.isNullOrBlank()) rootStack.lastOrNull()?.children?.add(Node.Text(text))
                    }
                }
            } catch (_: Exception) {
                // Corrupt/unsupported chunk — skip it and keep going rather than aborting the whole decode.
            }
            buf.position(chunkEnd.coerceAtMost(buf.limit()))
        }

        return virtualRoot.children.filterIsInstance<Node.Element>().firstOrNull()
            ?: virtualRoot.takeIf { it.children.isNotEmpty() }
    }

    private fun nsPrefix(uri: String?): String? = when {
        uri == null -> null
        uri.contains("android") -> "android"
        else -> null
    }

    private fun decodeAttrValue(dataType: Int, data: Int, rawValueIdx: Int, strings: List<String>): String {
        if (rawValueIdx >= 0) strings.getOrNull(rawValueIdx)?.let { return it }
        return when (dataType) {
            0x01 -> "@0x%08x".format(data) // reference
            0x03 -> strings.getOrNull(data) ?: ""
            0x10 -> data.toString()
            0x11 -> "0x%08x".format(data)
            0x12 -> if (data != 0) "true" else "false"
            else -> data.toString()
        }
    }

    private fun parseStringPool(buf: ByteBuffer, chunkStart: Int): List<String> {
        buf.position(chunkStart)
        buf.short; buf.short // type, headerSize
        buf.int // chunk size (already known)
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart, unused

        val isUtf8 = (flags and (1 shl 8)) != 0
        val offsets = IntArray(stringCount) { buf.int }
        repeat(styleCount) { buf.int } // skip style offsets, we don't render styled spans

        val dataBase = chunkStart + stringsStart
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val pos = dataBase + offsets[i]
            if (pos < 0 || pos >= buf.limit()) { out.add(""); continue }
            out.add(
                try {
                    if (isUtf8) readUtf8String(buf, pos) else readUtf16String(buf, pos)
                } catch (_: Exception) { "" }
            )
        }
        return out
    }

    private fun readUtf8String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        // UTF-8 strings are prefixed with two lengths (char length, then byte length),
        // each a 1-or-2-byte varint (high bit set => 2-byte form).
        fun readLen(): Int {
            val b0 = buf.get(p).toInt() and 0xFF; p += 1
            return if (b0 and 0x80 != 0) {
                val b1 = buf.get(p).toInt() and 0xFF; p += 1
                ((b0 and 0x7F) shl 8) or b1
            } else b0
        }
        readLen() // char length, unused
        val byteLen = readLen()
        val bytes = ByteArray(byteLen)
        for (i in 0 until byteLen) bytes[i] = buf.get(p + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(buf: ByteBuffer, pos: Int): String {
        var p = pos
        fun readLen(): Int {
            val u0 = buf.getShort(p).toInt() and 0xFFFF; p += 2
            return if (u0 and 0x8000 != 0) {
                val u1 = buf.getShort(p).toInt() and 0xFFFF; p += 2
                ((u0 and 0x7FFF) shl 16) or u1
            } else u0
        }
        val len = readLen()
        val chars = CharArray(len)
        for (i in 0 until len) { chars[i] = buf.getShort(p).toInt().toChar(); p += 2 }
        return String(chars)
    }

    /** Streaming read with a hard cap — never blindly readBytes() an unbounded stream. */
    private fun readBytesStreaming(input: InputStream, maxSize: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(maxSize, 64 * 1024))
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxSize) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
