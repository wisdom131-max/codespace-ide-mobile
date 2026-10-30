package com.codespace.ide.chat.panel.attachments

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The picker's browsing rules. These are the failures a file manager must not have: an unreadable
 * folder that looks empty, a duplicate entry that stages two chips, a "back" that climbs out of the
 * project, a listing that freezes the frame.
 */
class AttachmentPickerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-picker", "test").let { f -> f.delete(); f.mkdirs(); f }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun touch(name: String, size: Int = 4): File =
        File(root, name).apply { writeBytes(ByteArray(size) { 65 }) }

    @Test
    fun aFolderIsAFolderAndEveryOtherKindComesFromTheExtension() {
        assertEquals(PickerKind.FOLDER, AttachmentPicker.kindOf("src", true))
        assertEquals(PickerKind.CODE, AttachmentPicker.kindOf("main.py", false))
        assertEquals(PickerKind.CODE, AttachmentPicker.kindOf("Editor.kt", false))
        assertEquals(PickerKind.ARCHIVE, AttachmentPicker.kindOf("bundle.zip", false))
        assertEquals(PickerKind.PDF, AttachmentPicker.kindOf("spec.pdf", false))
        assertEquals(PickerKind.WORD, AttachmentPicker.kindOf("memo.docx", false))
        assertEquals(PickerKind.EXCEL, AttachmentPicker.kindOf("sales.xlsx", false))
        assertEquals(PickerKind.POWERPOINT, AttachmentPicker.kindOf("pitch.pptx", false))
        assertEquals(PickerKind.IMAGE, AttachmentPicker.kindOf("photo.jpg", false))
        assertEquals(PickerKind.OTHER, AttachmentPicker.kindOf("mystery", false))
    }

    @Test
    fun foldersSortBeforeFilesAndEachGroupIsAlphabetical() {
        touch("zeta.txt")
        touch("alpha.txt")
        File(root, "beta").mkdirs()
        File(root, "Alpha").mkdirs()

        val listing = AttachmentPicker.listing(root)
        assertNull(listing.problem)
        assertEquals(listOf("Alpha", "beta", "alpha.txt", "zeta.txt"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].isDir)
        assertFalse(listing.entries[2].isDir)
    }

    @Test
    fun aHugeFolderIsCappedAndSaysSoRatherThanLookingComplete() {
        repeat(12) { touch("f" + it + ".txt") }
        val listing = AttachmentPicker.listing(root, cap = 5)
        assertEquals(5, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun aMissingFolderIsAProblemAndNeverAnEmptyFolder() {
        val listing = AttachmentPicker.listing(File(root, "nope"))
        assertEquals(emptyList<PickerEntry>(), listing.entries)
        assertTrue(listing.problem!!.isNotBlank())
    }

    @Test
    fun aFilePathIsRefusedWithAReason() {
        val f = touch("a.txt")
        assertTrue(AttachmentPicker.listing(f).problem!!.isNotBlank())
    }

    @Test
    fun backStopsAtTheRootInsteadOfClimbingOutOfTheProject() {
        val sub = File(root, "src/deep").apply { mkdirs() }
        assertEquals(File(root, "src").absolutePath, AttachmentPicker.parentOf(sub.absolutePath, root.absolutePath))
        assertNull("at the root there is nowhere up to go", AttachmentPicker.parentOf(root.absolutePath, root.absolutePath))
        assertNull("an unrelated path is not walkable", AttachmentPicker.parentOf("/tmp", root.absolutePath))
    }

    @Test
    fun aSiblingWithTheSamePrefixIsNotInsideTheRoot() {
        assertTrue(AttachmentPicker.isInsideRoot(root.absolutePath + "/a", root.absolutePath))
        assertFalse(AttachmentPicker.isInsideRoot(root.absolutePath + "evil", root.absolutePath))
    }

    @Test
    fun badgesNameTheTypeNotTheWholeFile() {
        fun entry(n: String) = PickerEntry(n, "/x/" + n, false, 1L, AttachmentPicker.kindOf(n, false))
        assertEquals("PY", AttachmentPicker.badgeFor(entry("main.py")))
        assertEquals("ZIP", AttachmentPicker.badgeFor(entry("a.zip")))
        assertEquals("PDF", AttachmentPicker.badgeFor(entry("a.pdf")))
        assertEquals("MD", AttachmentPicker.badgeFor(entry("notes.md")))
        assertEquals("TXT", AttachmentPicker.badgeFor(entry("a.txt")))
    }

    @Test
    fun sizesReadLikeAFileManager() {
        assertEquals("", AttachmentPicker.sizeLabel(0))
        assertEquals("900 B", AttachmentPicker.sizeLabel(900))
        assertEquals("2 KB", AttachmentPicker.sizeLabel(2048))
        assertEquals("1.5 MB", AttachmentPicker.sizeLabel((1.5 * 1024 * 1024).toLong()))
    }
}
