package com.lemon7679.note.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class StorageTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("note-storage").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun createWriteAndReadBack() {
        val doc = NotebookDocument.create("선형대수", pageCount = 3)
        val pkg = NotebookPackage.create(dir, doc)
        assertEquals("선형대수.inknote", pkg.dir.name)
        pkg.writeInk(byteArrayOf(1, 2, 3), doc.pages[1].id)
        assertEquals(listOf<Byte>(1, 2, 3), pkg.readInk(doc.pages[1].id)?.toList())
        assertNull(pkg.readInk(doc.pages[0].id))
        val back = pkg.readDocument()
        assertEquals(doc.copy(created = back.created, modified = back.modified), back)
        // Second notebook with the same title gets a unique package name.
        assertEquals("선형대수 2.inknote", NotebookPackage.create(dir, doc).dir.name)
        // Ink of removed pages is pruned.
        pkg.pruneInk(doc.copy(pages = doc.pages.take(1)))
        assertNull(pkg.readInk(doc.pages[1].id))
    }

    @Test fun duplicateGetsNewIdentity() {
        val doc = NotebookDocument.create("원본")
        val pkg = NotebookPackage.create(dir, doc)
        val copy = pkg.duplicate(dir, "원본 사본")
        assertNotEquals(doc.id, copy.readDocument().id)
        assertEquals("원본 사본", copy.readDocument().title)
    }

    @Test fun newerFormatIsRejected() {
        val pkg = NotebookPackage.create(dir, NotebookDocument.create("미래"))
        pkg.documentFile.writeText(pkg.documentFile.readText().replace("\"format\": 1", "\"format\": 99"))
        try {
            pkg.readDocument()
            throw AssertionError("expected rejection")
        } catch (e: NoteException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test fun listingRenameMoveDuplicateTrash() {
        val lib = LibraryFileSystem(dir)
        val folder = lib.createFolder("강의", "/")
        NotebookPackage.create(lib.file(folder), NotebookDocument.create("노트"))
        lib.file("/a.pdf").writeBytes(ByteArray(4))
        lib.file("/.hidden").writeText("x")
        val kinds = lib.list("/").associate { it.fileName to it.kind }
        assertEquals(mapOf("강의" to LibraryItemKind.FOLDER, "a.pdf" to LibraryItemKind.PDF), kinds)
        assertEquals(LibraryItemKind.NOTEBOOK, lib.list(folder).single().kind)

        val renamed = lib.rename("/강의/노트.inknote", "새 이름")
        assertEquals("/강의/새 이름.inknote", renamed)
        assertEquals("새 이름", NotebookPackage(lib.file(renamed)).readDocument().title)
        val moved = lib.move("/a.pdf", "/강의")
        assertEquals("/강의/a.pdf", moved)
        assertEquals("/강의/a 사본.pdf", lib.duplicate(moved))
        assertEquals(listOf("/강의"), lib.allFolders())
        assertEquals(1, lib.search("새").size)

        val trash = TrashBin(File(dir, ".trash"))
        lib.delete(moved, trash)
        assertFalse(lib.exists(moved))
        val entry = trash.entries().single()
        assertEquals("a.pdf", entry.displayName)
        assertEquals(moved, trash.restore(entry, lib))
        assertTrue(trash.entries().isEmpty())
    }

    @Test fun pathsAndNames() {
        assertEquals("/a/b", RemotePath.normalize("a//b/./c/.."))
        assertEquals("/a/b", RemotePath.join("/a/", "b/"))
        assertEquals("/b/c", RemotePath.relative("/a/b/c", "/a"))
        assertEquals(null, RemotePath.relative("/x", "/a"))
        assertTrue(RemotePath.isAncestor("/a", "/a/b"))
        assertFalse(RemotePath.isAncestor("/a", "/ab"))
        assertEquals("/a/b.inknote", FileNaming.packageRoot("/a/b.inknote/pages/x.ink"))
        assertTrue(FileNaming.isIgnored("#recycle"))
        assertEquals("a-b-c-", FileNaming.sanitize("  a/b:c?  "))
        assertEquals("제목 없음", FileNaming.sanitize("..."))
        val taken = setOf("노트.inknote", "노트 2.inknote")
        assertEquals("노트 3.inknote", FileNaming.unique("노트", "inknote") { it in taken })
        val name = FileNaming.conflictCopyName("보고서.inknote", "Android", Instant.parse("2026-11-23T05:02:00Z"))
        assertTrue(name, name.startsWith("보고서 (충돌 사본 Android 2026-11-23 ") || name.startsWith("보고서 (충돌 사본 Android 2026-11-2"))
        assertTrue(name, name.endsWith(").inknote"))
    }

    @Test fun parsesEngineSvg() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200pt" height="100pt" viewBox="0 0 200 100">
            <rect width="100%" height="100%" fill="#ffffff"/>
            <g opacity="0.5">
            <path d="M10,10 L20,10 20,20 10,20Z" fill="#1c1c1e"/>
            <path d="M1.5,2 L3,4.25Z" fill="#ffe45c" stroke="#ffe45c" stroke-width="2.5" stroke-linejoin="round" opacity="0.4" style="mix-blend-mode:multiply"/>
            </g>
            </svg>
        """.trimIndent()
        val outlines = InkOutlineParser.parse(svg)
        assertEquals(2, outlines.size)
        assertEquals(8, outlines[0].points.size)
        assertEquals(0.5f, outlines[0].opacity, 1e-6f)
        assertFalse(outlines[0].multiply)
        assertEquals(listOf(1.5f, 2f, 3f, 4.25f), outlines[1].points.toList())
        assertEquals(2.5f, outlines[1].strokeWidth)
        assertEquals(0.2f, outlines[1].opacity, 1e-6f)
        assertTrue(outlines[1].multiply)
        assertTrue(outlines[0].holes.isEmpty())
    }

    @Test fun parsesEraserMasks() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200pt" height="100pt" viewBox="0 0 200 100">
            <g>
            <mask id="e7" maskUnits="userSpaceOnUse" x="0" y="0" width="50" height="50"><rect x="0" y="0" width="50" height="50" fill="#ffffff"/><polygon points="1,1 4,1 4,4 1,4" fill="#000000"/><polygon points="5,5 6,5 6,6" fill="#000000"/></mask>
            <path d="M0,0 L40,0 40,40 0,40Z" fill="#ffe45c" mask="url(#e7)" style="mix-blend-mode:multiply"/>
            <path d="M0,0 L10,0 10,10Z" fill="#1c1c1e"/>
            </g>
            </svg>
        """.trimIndent()
        val outlines = InkOutlineParser.parse(svg)
        assertEquals(2, outlines.size)  // mask shapes are not ink
        assertEquals(2, outlines[0].holes.size)
        assertEquals(listOf(1f, 1f, 4f, 1f, 4f, 4f, 1f, 4f), outlines[0].holes[0].toList())
        assertTrue(outlines[1].holes.isEmpty())
    }

    @Test fun notebookAndFileCopyToAnotherSource() {
        val device = LibraryFileSystem(File(dir, "device"))
        val nas = LibraryFileSystem(File(dir, "nas"))
        val pdf = File(dir, "모의고사.pdf").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val pkg = NotebookPackage.createAnnotating(pdf, "모의고사", listOf(PageSize(595.0, 842.0)), device.root)
        val doc = pkg.readDocument()
        pkg.writeInk(byteArrayOf(4, 5, 6), doc.pages[0].id)
        val notePath = "/" + pkg.dir.name
        nas.createFolder("국어", "/")
        NotebookPackage.create(nas.file("/국어"), NotebookDocument.create("모의고사"))

        val copied = nas.copyFrom(device, notePath, "/국어")
        assertEquals("/국어/모의고사 2.inknote", copied)
        val back = NotebookPackage(nas.file(copied))
        assertEquals(doc.id, back.readDocument().id)
        assertEquals(listOf<Byte>(4, 5, 6), back.readInk(doc.pages[0].id)?.toList())
        assertEquals(2048L, File(back.assetsDir, "source.pdf").length())
        assertEquals(LibraryItemKind.NOTEBOOK, nas.list("/국어").first { it.path == copied }.kind)
        // The original stays until the caller removes it.
        assertTrue(device.exists(notePath))

        device.importFile(pdf, "/")
        assertEquals("/모의고사.pdf", nas.copyFrom(device, "/모의고사.pdf", "/"))
        assertEquals(2048L, nas.file("/모의고사.pdf").length())

        try {
            nas.copyFrom(device, "/없음.inknote", "/")
            throw AssertionError("expected not found")
        } catch (e: NoteException) {
            assertTrue(e.message!!.contains("없음"))
        }
    }
}
