package com.lemon7679.note.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** End-to-end: two devices syncing through a "NAS" that is a temp folder (as in note-ipad). */
class SyncEngineTest {
    private lateinit var dir: File
    private lateinit var nas: LocalDirectoryFileSystem
    private val options = SyncOptions(downloadOnDemand = true)

    @Before fun setUp() {
        dir = Files.createTempDirectory("note-sync").toFile()
        nas = LocalDirectoryFileSystem(File(dir, "nas"))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun device(name: String): Pair<SyncEngine, LibraryFileSystem> {
        val root = File(dir, "device-$name")
        return SyncEngine(nas, root, "/home/Notes", File(dir, "state-$name.json"), name) to LibraryFileSystem(root)
    }

    private fun bump(f: File, text: String) {
        f.writeText(text)
        // Make sure the signature changes even on coarse file-system clocks.
        f.setLastModified(System.currentTimeMillis() + Random.nextLong(10_000, 1_000_000))
    }

    @Test fun notebookTravelsBetweenDevices() = runBlocking {
        val (engineA, libA) = device("A")
        val (engineB, libB) = device("B")
        val folder = libA.createFolder("강의", "/")
        val doc = NotebookDocument.create("선형대수", pageCount = 2)
        val pkg = NotebookPackage.create(libA.file(folder), doc)
        pkg.writeInk(ByteArray(100) { 1 }, doc.pages[0].id)

        val first = engineA.run(options)
        assertEquals(2, first.uploaded)   // document.json + one page
        assertTrue(first.errors.toString(), first.errors.isEmpty())
        assertNotNull(nas.stat("/home/Notes/강의/선형대수.inknote/document.json"))

        val second = engineB.run(options)
        assertEquals(2, second.downloaded)
        val pkgB = NotebookPackage(libB.file("/강의/선형대수.inknote"))
        assertEquals(doc.id, pkgB.readDocument().id)

        bump(pkgB.inkFile(doc.pages[0].id), "x".repeat(200))
        assertEquals(1, engineB.run(options).uploaded)
        assertEquals(1, engineA.run(options).downloaded)
        assertEquals(200, pkg.readInk(doc.pages[0].id)?.size)
        assertEquals(0, engineA.run(options).transferred)

        libA.delete("/강의/선형대수.inknote")
        engineA.run(options)
        assertEquals(1, engineB.run(options).deletedLocal)
        assertFalse(libB.exists("/강의/선형대수.inknote"))
    }

    @Test fun conflictIsReportedThenKeptBoth() = runBlocking {
        val (engineA, libA) = device("A")
        val (engineB, libB) = device("B")
        val doc = NotebookDocument.create("보고서")
        val pkgA = NotebookPackage.create(libA.root, doc)
        engineA.run(options)
        engineB.run(options)
        val pkgB = NotebookPackage(libB.file("/보고서.inknote"))

        bump(pkgA.inkFile(doc.pages[0].id), "from A")
        bump(pkgB.inkFile(doc.pages[0].id), "from B, longer")
        engineA.run(options)
        val report = engineB.run(options)
        assertEquals(listOf("/보고서.inknote"), report.conflicts.map { it.path })
        assertEquals(1, engineB.conflicts().size)

        engineB.resolve("/보고서.inknote", ConflictResolution.KEEP_BOTH)
        val resolved = engineB.run(options)
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals("from A", pkgB.readInk(doc.pages[0].id)?.decodeToString())
        val names = libB.list("/").map { it.fileName }.sorted()
        assertEquals(names.toString(), 2, names.size)
        val copy = names.first { it.contains("충돌 사본") }
        assertNotEquals(doc.id, NotebookPackage(libB.file("/$copy")).readDocument().id)
        assertNotNull(nas.stat(RemotePath.join("/home/Notes", "$copy/document.json")))
        assertTrue(engineB.conflicts().isEmpty())
    }

    @Test fun pdfsAreOnDemand() = runBlocking {
        val (engineA, libA) = device("A")
        val (engineB, libB) = device("B")
        libA.file("/강의자료.pdf").writeBytes(ByteArray(1000) { 7 })
        engineA.run(options)
        engineB.run(options)
        assertFalse(libB.exists("/강의자료.pdf"))
        val placeholders = engineB.placeholders("/")
        assertEquals(listOf("강의자료.pdf"), placeholders.map { it.fileName })
        assertTrue(placeholders[0].isPlaceholder)
        assertEquals(1000L, placeholders[0].size)

        assertEquals(1000L, engineB.materialize("/강의자료.pdf").length())
        assertTrue(engineB.placeholders("/").isEmpty())
        assertEquals(0, engineB.run(options).transferred)

        engineB.evict("/강의자료.pdf")
        assertFalse(libB.exists("/강의자료.pdf"))
        assertEquals(1, engineB.placeholders("/").size)
        assertEquals(0, engineB.run(options).deletedRemote)   // evicting is not deleting
    }

    @Test fun emptyNasIsNotMirroredAsMassDeletion() = runBlocking {
        val (engineA, libA) = device("A")
        repeat(6) { libA.file("/f$it.txt").writeText("$it") }
        engineA.run(options)
        repeat(6) { nas.delete("/home/Notes/f$it.txt") }
        try {
            engineA.run(options)
            fail("expected the safety stop")
        } catch (e: SyncException.SuspiciousEmptyRemote) {
            assertEquals(6, libA.list("/").size)
        }
    }

    @Test fun missingRemoteFolderAfterSetupIsAnError() = runBlocking {
        val (engineA, libA) = device("A")
        libA.file("/a.txt").writeText("x")
        engineA.run(options)
        nas.delete("/home/Notes")
        try {
            engineA.run(options)
            fail("expected an error")
        } catch (e: SyncException.RemoteFolderMissing) {
            assertTrue(libA.exists("/a.txt"))
        }
    }
}
