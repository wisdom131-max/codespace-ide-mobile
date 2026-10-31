package com.codespace.ide.ui.panes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cell view's document model: what it keeps, what it rewrites, what it refuses. */
class NotebookDocumentTest {

    private val nb = """
    {
      "nbformat": 4,
      "nbformat_minor": 5,
      "metadata": {"kernelspec": {"name": "python3"}},
      "cells": [
        {"cell_type": "markdown", "metadata": {}, "source": ["# Title\n", "text"]},
        {"cell_type": "code", "metadata": {}, "execution_count": 1, "source": ["print(1)"],
         "outputs": [{"output_type": "stream", "name": "stdout", "text": ["1\n"]}]}
      ]
    }
    """.trimIndent()

    @Test
    fun `cells come back in order with their kind`() {
        val doc = NotebookDocument.parse(nb)
        assertNotNull(doc)
        val cells = doc!!.cells()
        assertEquals(2, cells.size)
        assertEquals("markdown", cells[0].kind)
        assertEquals("# Title\ntext", cells[0].source)
        assertEquals("code", cells[1].kind)
    }

    @Test
    fun `code output is kept for display`() {
        val doc = NotebookDocument.parse(nb)!!
        assertTrue(doc.cells()[1].output!!.contains("1"))
    }

    @Test
    fun `editing a cell rewrites only that source`() {
        val doc = NotebookDocument.parse(nb)!!
        val json = doc.withSource(1, "print(2)").serialize()
        val root = JSONObject(json)
        assertEquals("python3", root.getJSONObject("metadata").getJSONObject("kernelspec").getString("name"))
        assertEquals(4, root.getInt("nbformat"))
        val reread = NotebookDocument.parse(json)!!
        assertEquals("print(2)", reread.cells()[1].source)
        assertEquals("# Title\ntext", reread.cells()[0].source)
    }

    @Test
    fun `add appends a cell of the asked kind`() {
        val doc = NotebookDocument.parse(nb)!!
        doc.add("markdown")
        val cells = NotebookDocument.parse(doc.serialize())!!.cells()
        assertEquals(3, cells.size)
        assertEquals("markdown", cells[2].kind)
        assertEquals("", cells[2].source)
    }

    @Test
    fun `remove drops the right cell and nothing else`() {
        val doc = NotebookDocument.parse(nb)!!
        val cells = NotebookDocument.parse(doc.remove(0).serialize())!!.cells()
        assertEquals(1, cells.size)
        assertEquals("code", cells[0].kind)
    }

    @Test
    fun `a file that is not a notebook is refused rather than half parsed`() {
        assertNull(NotebookDocument.parse("{}"))
        assertNull(NotebookDocument.parse("not json"))
        assertNull(NotebookDocument.parse("""{"cells": "nope"}"""))
    }

    @Test
    fun `markdown preview drops heading markers and emphasis`() {
        assertEquals("Title", NotebookDocument.markdownPreview("### Title"))
        assertEquals("bold", NotebookDocument.markdownPreview("**bold**"))
    }
}
