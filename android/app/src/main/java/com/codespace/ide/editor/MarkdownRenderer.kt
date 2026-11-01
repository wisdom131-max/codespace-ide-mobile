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
<meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=6.0, user-scalable=yes">
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
/* Code blocks: highlighted spans + copy affordance */
.code-block { position: relative; }
.code-block .copy-btn { position: absolute; top: 6px; right: 8px; background: #3a3a3a; color: #d4d4d4;
  border: 1px solid #555; border-radius: 6px; padding: 3px 8px; font-size: 11px; opacity: .85; }
.code-block .copy-btn:active { background: #4a4a4a; }
.hl-keyword { color: #569cd6; }
.hl-string { color: #ce9178; }
.hl-comment { color: #6a9955; font-style: italic; }
.hl-number { color: #b5cea8; }
.hl-function { color: #dcdcaa; }
/* Math */
.math-display { overflow-x: auto; padding: 6px 0; }
math { font-size: 1.05em; }

/* Scrollbar */
::-webkit-scrollbar { width: 8px; }
::-webkit-scrollbar-thumb { background: #424242; border-radius: 4px; }
::-webkit-scrollbar-track { background: #1e1e1e; }
</style>
</head>
<body>
$body
<script>
document.addEventListener('click', function (e) {
  var b = e.target && e.target.closest ? e.target.closest('.copy-btn') : null;
  if (!b) return;
  var block = b.parentNode;
  var code = block ? block.querySelector('code') : null;
  if (!code) return;
  var text = code.innerText;
  var done = function () { b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1200); };
  var fallback = function () {
    var ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch (err) {}
    document.body.removeChild(ta); done();
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, fallback);
  } else { fallback(); }
});
document.addEventListener('click', function (e) {
  var a = e.target && e.target.closest ? e.target.closest('a') : null;
  if (!a) return;
  var href = a.getAttribute('href') || '';
  if (href.charAt(0) === '#') return;  // in-page anchor: let the browser jump
  e.preventDefault();
  location.href = 'vncode://link/' + encodeURIComponent(href);
});
document.addEventListener('dblclick', function (e) {
  var el = e.target;
  while (el && el !== document.body && !(el.getAttribute && el.getAttribute('data-line'))) el = el.parentNode;
  if (el && el.getAttribute && el.getAttribute('data-line')) {
    location.href = 'vncode://line/' + el.getAttribute('data-line');
  }
});
window.onerror = function (m, s, l) { console.error('[error] ' + m + ' (' + (s || '') + ':' + (l || 0) + ')'); };
window.addEventListener('unhandledrejection', function (e) {
  var r = e && e.reason;
  console.error('[unhandled] ' + (r && r.message ? r.message : r));
});
</script>
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

            // Display math: $$ … $$ as its own block.
            if (line.trim().startsWith("$$") && line.trim().length > 2) {
                closeList(); closeTable()
                val tex = StringBuilder(line.trim().removePrefix("$$").removeSuffix("$$"))
                if (!line.trim().endsWith("$$") || line.trim() == "$$") {
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                        tex.append("\n").append(lines[i]); i++
                    }
                    if (i < lines.size) tex.append("\n").append(lines[i].trim().removeSuffix("$$"))
                }
                html.append("<div class=\"math-display\">")
                html.append(MarkdownMath.toMathML(tex.toString(), display = true))
                html.append("</div>\n")
                i++
                continue
            }

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
                val raw = code.toString().trimEnd()
                html.append("<div class=\"code-block\" data-line=\"${i + 1}\">")
                html.append("<button class=\"copy-btn\" type=\"button\">Copy</button>")
                html.append("<pre><code")
                if (lang.isNotEmpty()) html.append(" class=\"language-$lang\"")
                html.append(">")
                html.append(if (MarkdownHighlight.isSupported(lang)) MarkdownHighlight.highlight(raw, lang) else escapeHtml(raw))
                html.append("</code></pre></div>\n")
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
                html.append("<h$level data-line=\"${i + 1}\">$text</h$level>\n")
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

            // Task list item: - [ ] / - [x] (GFM) — rendered as a disabled checkbox
            val taskMatch = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)").matchEntire(line)
            if (taskMatch != null) {
                closeTable()
                if (!inList || listType != "ul") { closeList(); html.append("<ul>\n"); inList = true; listType = "ul" }
                val checked = if (taskMatch.groupValues[1].lowercase() == "x") " checked" else ""
                html.append("<li style=\"list-style:none;margin-left:-1.2em;\"><input type=\"checkbox\"$checked disabled> ${inlineFormat(taskMatch.groupValues[2])}</li>\n")
                i++
                continue
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
            val paraLine = i + 1
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
                html.append("<p data-line=\"$paraLine\">${inlineFormat(para.toString())}</p>\n")
            }
        }

        closeList(); closeTable()
        return html.toString()
    }

    private fun inlineFormat(text: String): String {
        // Inline math is lifted out BEFORE escaping: the TeX parser needs the raw characters,
        // and escapeHtml would turn < and & inside a formula into entities the parser then
        // reads as text. Placeholders use control characters because escapeHtml only rewrites
        // &, < and >, so a token survives the escape pass untouched.
        // Code spans are protected FIRST: `$HOME` is a shell variable, not a formula, and the
        // maths pass below would otherwise lift it out of the backticks and render it.
        val codes = mutableListOf<String>()
        val codeProtected = Regex("`([^`]+)`").replace(text) { m ->
            codes.add(m.groupValues[1])
            "\u0001C${codes.size - 1}\u0002"
        }
        val maths = mutableListOf<String>()
        val withTokens = Regex("\\$(?![\\s$])(.+?)(?<![\\s])\\$").replace(codeProtected) { m ->
            maths.add(MarkdownMath.toMathML(m.groupValues[1]))
            "\u0001M${maths.size - 1}\u0002"
        }
        var result = escapeHtml(withTokens)
        // Images: ![alt](url)
        result = Regex("!\\[(.+?)\\]\\((.+?)\\)").replace(result) { m ->
            "<img src=\"${encodeUrl(m.groupValues[2])}\" alt=\"${m.groupValues[1]}\">"
        }
        // Links: [text](url)
        result = Regex("\\[(.+?)\\]\\((.+?)\\)").replace(result) { m ->
            "<a href=\"${encodeUrl(m.groupValues[2])}\">${m.groupValues[1]}</a>"
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
        codes.forEachIndexed { idx, code ->
            result = result.replace("\u0001C$idx\u0002", "<code>" + escapeHtml(code) + "</code>")
        }
        maths.forEachIndexed { idx, math ->
            result = result.replace("\u0001M$idx\u0002", math)
        }
        return result
    }

    // MD-IMG: file-relative URLs need percent-encoding per path segment (spaces,
    // unicode) or WebView file:// loads fail. Absolute http(s)/data/about/anchor
    // URLs and already-encoded segments pass through untouched.
    private fun encodeUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") ||
            url.startsWith("about:") || url.startsWith("#")) return url
        val queryIdx = url.indexOf('?')
        val path = if (queryIdx >= 0) url.substring(0, queryIdx) else url
        val query = if (queryIdx >= 0) url.substring(queryIdx) else ""
        val encPath = path.split("/").joinToString("/") { seg ->
            seg.split("%").joinToString("%") { s ->
                if (s.isEmpty()) "" else java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            }
        }
        return encPath + query
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
