package com.codespace.ide.editor

import java.net.URLEncoder

/**
 * P45-4: Lightweight Markdown → HTML renderer for the live preview pane.
 * Supports: headings, bold, italic, inline code, code blocks, links,
 * images, lists, blockquotes, horizontal rules, and tables.
 * Self-contained — no external dependencies.
 */
object MarkdownRenderer {

    fun render(markdown: String): String {
        val body = parseMarkdown(markdown)
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
:root { color-scheme: dark; }
body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    font-size: 15px;
    line-height: 1.6;
    color: #d4d4d4;
    background: #1e1e1e;
    padding: 16px;
    margin: 0;
    word-wrap: break-word;
    overflow-wrap: break-word;
}
h1, h2, h3, h4, h5, h6 {
    color: #569cd6;
    margin-top: 1.2em;
    margin-bottom: 0.4em;
    font-weight: 600;
}
h1 { font-size: 1.8em; border-bottom: 1px solid #333; padding-bottom: 0.2em; }
h2 { font-size: 1.5em; border-bottom: 1px solid #333; padding-bottom: 0.2em; }
h3 { font-size: 1.25em; }
h4 { font-size: 1.1em; }
h5, h6 { font-size: 1em; }
p { margin: 0.6em 0; }
a { color: #3794ff; text-decoration: none; }
a:hover { text-decoration: underline; }
strong { color: #f0f0f0; font-weight: 700; }
em { font-style: italic; }
del { text-decoration: line-through; color: #888; }
code {
    font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
    font-size: 0.9em;
    background: #2d2d30;
    color: #ce9178;
    padding: 2px 5px;
    border-radius: 4px;
}
pre {
    background: #1e1e1e;
    border: 1px solid #333;
    border-radius: 6px;
    padding: 12px;
    overflow-x: auto;
    margin: 0.8em 0;
}
pre code {
    background: none;
    color: #d4d4d4;
    padding: 0;
    font-size: 0.85em;
}
blockquote {
    border-left: 3px solid #569cd6;
    margin: 0.6em 0;
    padding: 0.4em 0 0.4em 16px;
    color: #999;
    background: rgba(86, 156, 214, 0.05);
}
ul, ol { padding-left: 1.8em; margin: 0.5em 0; }
li { margin: 0.2em 0; }
hr {
    border: none;
    border-top: 1px solid #333;
    margin: 1.2em 0;
}
img {
    max-width: 100%;
    border-radius: 6px;
}
table {
    border-collapse: collapse;
    width: 100%;
    margin: 0.8em 0;
}
th, td {
    border: 1px solid #333;
    padding: 6px 12px;
    text-align: left;
}
th {
    background: #2d2d30;
    color: #569cd6;
    font-weight: 600;
}
/* Checkbox lists */
.task-list-item { list-style: none; }
.task-list-item input { margin-right: 6px; }
/* Scrollbar */
::-webkit-scrollbar { width: 8px; }
::-webkit-scrollbar-thumb { background: #424242; border-radius: 4px; }
::-webkit-scrollbar-track { background: #1e1e1e; }
</style>
</head>
<body>
$body
</body>
</html>"""
    }

    private fun parseMarkdown(md: String): String {
        var lines = md.lines()
        val html = StringBuilder()
        var i = 0
        var inList = false
        var listType = ""  // "ul" or "ol"
        var inTable = false
        var tableRows = mutableListOf<List<String>>()

        fun closeList() {
            if (inList) { html.append("</$listType>\n"); inList = false; listType = "" }
        }
        fun closeTable() {
            if (inTable) {
                html.append("</table>\n")
                inTable = false
                tableRows.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]

            // Code block (```)
            if (line.trimStart().startsWith("```")) {
                closeList(); closeTable()
                val lang = line.trimStart().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append("\n")
                    i++
                }
                i++  // skip closing ```
                html.append("<pre><code")
                if (lang.isNotEmpty()) html.append(" class=\"language-$lang\"")
                html.append(">${escapeHtml(code.toString().trimEnd())}</code></pre>\n")
                continue
            }

            // Horizontal rule
            if (line.matches(Regex("^\\s*([-*_])\\1{2,}\\s*$"))) {
                closeList(); closeTable()
                html.append("<hr>\n")
                i++
                continue
            }

            // Headings
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            if (headingMatch != null) {
                closeList(); closeTable()
                val level = headingMatch.groupValues[1].length
                val text = inlineFormat(headingMatch.groupValues[2].trim())
                html.append("<h$level>$text</h$level>\n")
                i++
                continue
            }

            // Blockquote
            if (line.trimStart().startsWith(">")) {
                closeList(); closeTable()
                html.append("<blockquote>")
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val qtext = inlineFormat(lines[i].trimStart().removePrefix(">").trim())
                    html.append("<p>$qtext</p>")
                    i++
                }
                html.append("</blockquote>\n")
                continue
            }

            // Table (simplified — detects | rows)
            if (line.contains("|") && line.trim().startsWith("|")) {
                if (!inTable) {
                    closeList()
                    inTable = true
                    html.append("<table>")
                    // Header row
                    val cells = line.trim().trim('|').split("|").map { it.trim() }
                    html.append("<thead><tr>")
                    cells.forEach { html.append("<th>${inlineFormat(it)}</th>") }
                    html.append("</tr></thead><tbody>")
                    i++
                    // Skip separator row (|---|---|)
                    if (i < lines.size && lines[i].matches(Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)*\\|?\\s*$"))) {
                        i++
                    }
                    continue
                }
                // Data row
                val cells = line.trim().trim('|').split("|").map { it.trim() }
                html.append("<tr>")
                cells.forEach { html.append("<td>${inlineFormat(it)}</td>") }
                html.append("</tr>")
                i++
                // Check if next line is still a table row
                if (i >= lines.size || !lines[i].trim().startsWith("|")) {
                    html.append("</tbody></table>\n")
                    inTable = false
                }
                continue
            } else if (inTable) {
                html.append("</tbody></table>\n")
                inTable = false
            }

            // Unordered list
            val ulMatch = Regex("^\\s*[-*+]\\s+(.+)").matchEntire(line)
            if (ulMatch != null) {
                closeTable()
                if (!inList || listType != "ul") { closeList(); html.append("<ul>\n"); inList = true; listType = "ul" }
                html.append("<li>${inlineFormat(ulMatch.groupValues[1])}</li>\n")
                i++
                continue
            }

            // Ordered list
            val olMatch = Regex("^\\s*\\d+\\.\\s+(.+)").matchEntire(line)
            if (olMatch != null) {
                closeTable()
                if (!inList || listType != "ol") { closeList(); html.append("<ol>\n"); inList = true; listType = "ol" }
                html.append("<li>${inlineFormat(olMatch.groupValues[1])}</li>\n")
                i++
                continue
            }

            // Empty line
            if (line.isBlank()) {
                closeList(); closeTable()
                i++
                continue
            }

            // Regular paragraph
            closeList(); closeTable()
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() &&
                   !lines[i].trimStart().startsWith("#") &&
                   !lines[i].trimStart().startsWith("```") &&
                   !lines[i].trimStart().startsWith(">") &&
                   !Regex("^\\s*[-*+]\\s+.+").matches(lines[i]) &&
                   !Regex("^\\s*\\d+\\.\\s+.+").matches(lines[i]) &&
                   !lines[i].matches(Regex("^\\s*([-*_])\\1{2,}\\s*$"))) {
                if (para.isNotEmpty()) para.append("\n")
                para.append(lines[i])
                i++
            }
            if (para.isNotEmpty()) {
                html.append("<p>${inlineFormat(para.toString())}</p>\n")
            }
        }

        closeList(); closeTable()
        return html.toString()
    }

    private fun inlineFormat(text: String): String {
        var result = escapeHtml(text)
        // Images: ![alt](url)
        result = Regex("!\\[(.+?)\\]\\((.+?)\\)").replace(result) { m ->
            "<img src=\"${m.groupValues[2]}\" alt=\"${m.groupValues[1]}\">"
        }
        // Links: [text](url)
        result = Regex("\\[(.+?)\\]\\((.+?)\\)").replace(result) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        // Bold: **text** or __text__
        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("__(.+?)__").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        // Italic: *text* or _text_
        result = Regex("\\*(.+?)\\*").replace(result) { "<em>${it.groupValues[1]}</em>" }
        result = Regex("_(.+?)_").replace(result) { "<em>${it.groupValues[1]}</em>" }
        // Strikethrough: ~~text~~
        result = Regex("~~(.+?)~~").replace(result) { "<del>${it.groupValues[1]}</del>" }
        // Inline code: `code`
        result = Regex("`(.+?)`").replace(result) { "<code>${it.groupValues[1]}</code>" }
        return result
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
