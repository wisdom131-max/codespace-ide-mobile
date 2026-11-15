package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PROJECT 2.7 (2026-11-15): presentations can be CREATED.
 *
 * The brief lists "create presentations" next to reading them, and a deck is not a text file: the
 * package needs a presentation part, a slide master, at least one layout, a theme, and slide parts
 * that point at a layout. A deck missing any of those opens as a repair prompt in PowerPoint, which
 * is worse than refusing to write it. So this writer emits the whole minimum chain - master, one
 * title layout, one title-and-content layout, theme, slides - rather than the smallest package that
 * merely unzips.
 *
 * What it does not do is say anything it cannot verify: shapes beyond title and body placeholders,
 * images, transitions, notes and animation are not written, and the writer never claims a file was
 * opened in PowerPoint, only that it is structurally complete.
 */
object PptxWriter {

    data class Slide(val title: String, val bullets: List<String>)

    /** "# Title" starts a slide; "- text" adds a bullet; any other line is a bullet too. */
    fun slidesOf(source: String): List<Slide> {
        val slides = ArrayList<Slide>()
        var title: String? = null
        var bullets = ArrayList<String>()
        fun flush() {
            val t = title
            if (t != null) slides.add(Slide(t, ArrayList(bullets)))
            bullets = ArrayList()
        }
        for (raw in source.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                flush()
                title = line.dropWhile { it == '#' }.trim()
                continue
            }
            if (title == null) {
                title = line
                continue
            }
            bullets.add(line.removePrefix("- ").removePrefix("* ").trim())
        }
        flush()
        return slides
    }

    fun build(slides: List<Slide>): ByteArray {
        val deck = slides.ifEmpty { listOf(Slide("", emptyList())) }.take(MAX_SLIDES)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(deck.size))
            put("_rels/.rels", ROOT_RELS)
            put("ppt/presentation.xml", presentation(deck.size))
            put("ppt/_rels/presentation.xml.rels", presentationRels(deck.size))
            put("ppt/slideMasters/slideMaster1.xml", MASTER)
            put("ppt/slideMasters/_rels/slideMaster1.xml.rels", MASTER_RELS)
            put("ppt/slideLayouts/slideLayout1.xml", LAYOUT_TITLE)
            put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layoutRels())
            put("ppt/slideLayouts/slideLayout2.xml", LAYOUT_CONTENT)
            put("ppt/slideLayouts/_rels/slideLayout2.xml.rels", layoutRels())
            put("ppt/theme/theme1.xml", THEME)
            deck.forEachIndexed { i, slide ->
                put("ppt/slides/slide" + (i + 1) + ".xml", slidePart(slide))
                put("ppt/slides/_rels/slide" + (i + 1) + ".xml.rels", slideRels(slide.bullets.isEmpty()))
            }
        }
        return out.toByteArray()
    }

    // ── parts ────────────────────────────────────────────────────────────────

    private fun slidePart(slide: Slide): String {
        val sb = StringBuilder()
        sb.append(HEAD).append("<p:sld><p:cSld><p:spTree>").append(GROUP_SHAPE)
        sb.append(shape(2, "Title", placeholder = "title", text = slide.title.ifBlank { "Slide" }))
        if (slide.bullets.isNotEmpty()) {
            sb.append(bodyShape(slide.bullets))
        }
        sb.append("</p:spTree></p:cSld>")
        sb.append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun shape(id: Int, name: String, placeholder: String, text: String): String {
        val sb = StringBuilder()
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"").append(name).append("\"/>")
        sb.append("<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"")
            .append(placeholder).append("\"/></p:nvPr></p:nvSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"").append(OFF_X).append("\" y=\"").append(OFF_Y)
            .append("\"/><a:ext cx=\"").append(BOX_W).append("\" cy=\"").append(BOX_H)
            .append("\"/></a:xfrm></p:spPr>")
        sb.append("<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>")
            .append(escape(text)).append("</a:t></a:r></a:p></p:txBody></p:sp>")
        return sb.toString()
    }

    /**
     * The body placeholder, one paragraph per bullet. Bullets are the whole point of a content
     * slide, so they are written into the placeholder rather than left in the model's head.
     */
    private fun bodyShape(bullets: List<String>): String {
        val sb = StringBuilder()
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Content\"/>")
        sb.append("<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>")
        sb.append("<p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"").append(OFF_X).append("\" y=\"1825625\"/>")
        sb.append("<a:ext cx=\"").append(BOX_W).append("\" cy=\"4351338\"/></a:xfrm></p:spPr>")
        sb.append("<p:txBody><a:bodyPr/><a:lstStyle/>")
        bullets.forEach { b ->
            sb.append("<a:p><a:pPr lvl=\"0\"/><a:r><a:t>").append(escape(b)).append("</a:t></a:r></a:p>")
        }
        sb.append("</p:txBody></p:sp>")
        return sb.toString()
    }

    private fun presentation(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append(HEAD).append("<p:presentation><p:sldMasterIdLst>")
        sb.append("<p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
        for (i in 1..slideCount) {
            sb.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        sb.append("</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/>")
        sb.append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        return sb.toString()
    }

    private fun presentationRels(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sb.append("<Relationship Id=\"rId1\" Type=\"").append(RT_SLIDE_MASTER)
            .append("\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..slideCount) {
            sb.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(RT_SLIDE)
                .append("\" Target=\"slides/slide").append(i).append(".xml\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun slideRels(isTitleOnly: Boolean): String {
        val layout = if (isTitleOnly) 1 else 2
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$RT_SLIDE_LAYOUT\" Target=\"../slideLayouts/slideLayout" +
            layout + ".xml\"/></Relationships>"
    }

    private fun layoutRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$RT_SLIDE_MASTER\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>"

    private fun contentTypes(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        sb.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        sb.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        sb.append("<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        sb.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        for (i in 1..slideCount) {
            sb.append("<Override PartName=\"/ppt/slides/slide").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_SLIDES = 60
    private const val OFF_X = 838200
    private const val OFF_Y = 365125L
    private const val BOX_W = 10515600L
    private const val BOX_H = 1325563L

    private const val RT_SLIDE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val RT_SLIDE_MASTER =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
    private const val RT_SLIDE_LAYOUT =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"

    private const val HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">"""

    private const val GROUP_SHAPE =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

    private const val MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""

    private const val MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
</p:spTree></p:cSld>
<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/><p:sldLayoutId id="2147483650" r:id="rId2"/></p:sldLayoutIdLst>
<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4000" b="1"/></a:lvl1pPr></p:titleStyle>
<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2000"/></a:lvl1pPr></p:bodyStyle>
<p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles>
</p:sldMaster>"""

    private const val LAYOUT_TITLE = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="title">
<p:cSld name="Title Slide"><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="1524000" y="1122363"/><a:ext cx="9144000" cy="2387600"/></a:xfrm></p:spPr>
<p:txBody><a:bodyPr anchor="b"/><a:lstStyle/><a:p><a:r><a:t>Title</a:t></a:r></a:p></p:txBody></p:sp>
</p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private const val LAYOUT_CONTENT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="obj">
<p:cSld name="Title and Content"><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr>
<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Title</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="838200" y="1825625"/><a:ext cx="10515600" cy="4351338"/></a:xfrm></p:spPr>
<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Body</a:t></a:r></a:p></p:txBody></p:sp>
</p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private const val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="VN Code">
<a:themeElements>
<a:clrScheme name="VN Code"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
<a:dk2><a:srgbClr val="1F1F1F"/></a:dk2><a:lt2><a:srgbClr val="F2F2F2"/></a:lt2>
<a:accent1><a:srgbClr val="2F6FED"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>
<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>
<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
<a:fontScheme name="VN Code"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>
<a:fmtScheme name="VN Code">
<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
</a:fmtScheme></a:themeElements></a:theme>"""
}
