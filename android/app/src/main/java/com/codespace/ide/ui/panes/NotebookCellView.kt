package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The notebook CELL view (2026-10-31, owner request: "if it could be opened as a tab that'll be
 * cool"). An .ipynb used to open as raw JSON in the text editor, which is the file format, not the
 * document. This renders the document: cells in order, source editable in place, add and delete,
 * saved back as valid .ipynb.
 *
 * Deliberately no execution. Running a cell needs a kernel, and a Fake Run button that silently
 * does nothing would be worse than none. Execution stays with the existing agent tools until a
 * kernel path exists; the cells it edits here are the same cells those tools read.
 *
 * Loaded in one pass: an .ipynb is small, and a diff-based reload would fight the user's cursor.
 */
@Composable
fun NotebookCellView(
    path: String,
    content: String,
    savedContent: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = remember(content) { NotebookDocument.parse(content) }
    var expanded by remember { mutableStateOf<Int?>(null) }

    val surface = Color(0xFF1E1E2A)
    val border = Color(0xFF2D2D3F)
    val textPri = Color(0xFFD4D4D4)
    val textSec = Color(0xFF9A9AB0)
    val accent = Color(0xFF569CD6)

    if (doc == null) {
        // Not our file after all (malformed, or an older format): say so rather than showing
        // an empty notebook and pretending the content is gone.
        Column(modifier.fillMaxSize().padding(12.dp)) {
            Text("Not a readable .ipynb", color = Color(0xFFEF5350), fontSize = 13.sp)
            Text(path, color = textSec, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(doc.cells(), key = { i, c -> "$i:${c.kind}" }) { index, cell ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(surface, RoundedCornerShape(10.dp))
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .padding(12.dp, 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (cell.kind == "markdown") "MARKDOWN" else "CODE",
                        color = if (cell.kind == "markdown") Color(0xFF4EC9B0) else accent,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("[${index + 1}]", color = textSec, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    CellAction("edit") {
                        expanded = if (expanded == index) null else index
                    }
                    CellAction("del", Color(0xFFEF5350)) {
                        onContentChange(doc.remove(index).serialize())
                        expanded = null
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (expanded == index) {
                    var field by remember(index, cell.source) { mutableStateOf(TextFieldValue(cell.source)) }
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            onContentChange(doc.withSource(index, it.text).serialize())
                        },
                        textStyle = TextStyle(
                            color = textPri,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF15151F), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    )
                } else if (cell.kind == "markdown") {
                    // Markdown cells read as prose; the raw text is one tap away in edit.
                    val presentation = NotebookDocument.markdownPreview(cell.source)
                    if (presentation.isBlank()) {
                        Text("(empty cell)", color = textSec, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        Text(presentation, color = textPri, fontSize = 13.sp)
                    }
                } else {
                    Text(
                        cell.source.ifBlank { " " },
                        color = if (cell.source.isBlank()) textSec else textPri,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                cell.output?.let { out ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        out,
                        color = Color(0xFFB0B0C0),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF15151F), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddPill("+ code") { onContentChange(doc.add("code").serialize()) }
                AddPill("+ markdown") { onContentChange(doc.add("markdown").serialize()) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (content == savedContent) "saved" else "unsaved",
                    color = if (content == savedContent) textSec else Color(0xFFFFD93D),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun CellAction(label: String, color: Color = Color(0xFF7A7A8C), onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun AddPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF569CD6),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(Color(0xFF1E1E2A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2D2D3F), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A parsed notebook that can be edited without losing what it does not understand.
 *
 * The root object is kept whole, so nbformat, metadata, per-cell ids and any extension fields
 * survive an edit. Only `cells` is rebuilt, and only the cell that changed is rewritten.
 */
class NotebookDocument private constructor(
    private val root: JSONObject,
    private var cellsArray: JSONArray,
) {

    data class CellModel(val kind: String, val source: String, val output: String?)

    fun cells(): List<CellModel> {
        val out = ArrayList<CellModel>(cellsArray.length())
        for (i in 0 until cellsArray.length()) {
            val c = cellsArray.optJSONObject(i) ?: continue
            out.add(CellModel(c.optString("cell_type", "code"), sourceOf(c), outputOf(c)))
        }
        return out
    }

    fun withSource(index: Int, text: String): NotebookDocument {
        val c = cellsArray.optJSONObject(index) ?: return this
        c.put("source", sourceJson(text))
        return this
    }

    fun add(kind: String): NotebookDocument {
        val cell = JSONObject()
        cell.put("cell_type", kind)
        cell.put("metadata", JSONObject())
        cell.put("source", JSONArray())
        if (kind == "code") {
            cell.put("execution_count", JSONObject.NULL)
            cell.put("outputs", JSONArray())
        }
        cellsArray.put(cell)
        return this
    }

    fun remove(index: Int): NotebookDocument {
        val rebuilt = JSONArray()
        for (i in 0 until cellsArray.length()) {
            if (i != index) cellsArray.opt(i)?.let { rebuilt.put(it) }
        }
        cellsArray = rebuilt
        return this
    }

    fun serialize(): String {
        root.put("cells", cellsArray)
        return root.toString(1)
    }

    private fun sourceOf(c: JSONObject): String = when (val src = c.opt("source")) {
        is JSONArray -> (0 until src.length()).joinToString("") { src.optString(it) }
        is String -> src
        else -> ""
    }

    private fun sourceJson(text: String): JSONArray {
        val array = JSONArray()
        // nbformat stores source as a list of lines WITH their newlines; splitting that way keeps
        // the file readable in git and identical to what Jupyter writes.
        text.split("\n").forEachIndexed { i, line ->
            array.put(if (i == 0) line else "\n$line")
        }
        return array
    }

    private fun outputOf(c: JSONObject): String? {
        val outputs = c.optJSONArray("outputs") ?: return null
        val parts = ArrayList<String>()
        for (i in 0 until outputs.length()) {
            val o = outputs.optJSONObject(i) ?: continue
            when (o.optString("output_type")) {
                "stream" -> parts.add(textOf(o.opt("text")))
                "execute_result", "display_data" -> {
                    val data = o.optJSONObject("data")
                    val plain = data?.opt("text/plain")
                    if (plain != null) parts.add(textOf(plain))
                }
                "error" -> parts.add((o.optJSONArray("traceback") ?: JSONArray()).let { tb ->
                    (0 until tb.length()).joinToString("\n") { tb.optString(it) }
                })
            }
        }
        val joined = parts.filter { it.isNotBlank() }.joinToString("\n")
        return joined.takeIf { it.isNotBlank() }?.take(4000)
    }

    private fun textOf(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString("") { v.optString(it) }
        is String -> v
        null -> ""
        else -> v.toString()
    }

    companion object {
        fun parse(json: String): NotebookDocument? {
            val root = try {
                JSONObject(json)
            } catch (_: Throwable) {
                return null
            }
            val cells = root.optJSONArray("cells") ?: return null
            return NotebookDocument(root, cells)
        }

        /** Light markdown for the read view: headings and emphasis dropped to plain text. */
        fun markdownPreview(source: String): String = source
            .lineSequence()
            .joinToString("\n") { line ->
                line.trimStart().removePrefix("#").removePrefix("##").removePrefix("###")
                    .replace("**", "").replace("__", "").trim()
            }
            .trim()
    }
}
