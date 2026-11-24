package com.lemon7679.note.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** File Station upload against a fake DSM: the request format and the fallbacks. */
class SynologyUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val uploads = ArrayList<String>()
    /** Fake DSM rule: answers an upload with this error code when it returns one. */
    private var rejectUpload: (String) -> Int? = { null }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Before fun setUp() {
        dir = Files.createTempDirectory("syno").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = request.requestUrl?.query ?: ""
                val body = request.body.readUtf8()
                return when {
                    body.contains("api=SYNO.API.Auth") -> json("""{"success":true,"data":{"sid":"SID"}}""")
                    query.contains("SYNO.FileStation.Upload") -> {
                        uploads += body
                        rejectUpload(body)?.let { json("""{"success":false,"error":{"code":$it}}""") }
                            ?: json("""{"success":true,"data":{}}""")
                    }
                    body.contains("method=getinfo") -> json(
                        """{"success":true,"data":{"files":[{"path":"/home/Notes/노트.inknote/document.json","isdir":false,""" +
                            """"additional":{"size":2,"time":{"mtime":1795000000}}}]}}""")
                    else -> json("""{"success":false,"error":{"code":101}}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun client() = SynologyClient(server.url("/").toString().trimEnd('/'), "me", "pw")
    private fun file() = File(dir, "document.json").apply { writeText("{}") }

    @Test fun uploadUsesDsmMultipartFormat() = runBlocking {
        val item = client().upload(file(), "/home/Notes/노트.inknote/document.json", Instant.ofEpochMilli(1_795_000_000_000))
        assertNotNull(item)
        val body = uploads.single()
        // Fields first, file last, no per-part Content-Length.
        assertFalse(body, body.contains("Content-Length", ignoreCase = true))
        assertTrue(body, body.contains("name=\"path\"\r\n\r\n/home/Notes/노트.inknote\r\n"))
        assertTrue(body.contains("name=\"overwrite\"\r\n\r\ntrue\r\n"))
        assertTrue(body.contains("name=\"mtime\"\r\n\r\n1795000000000\r\n"))
        val filePart = body.indexOf("name=\"file\"; filename=\"document.json\"\r\nContent-Type: application/octet-stream\r\n\r\n{}\r\n--")
        assertTrue(body, filePart > body.indexOf("name=\"mtime\""))
        assertTrue(body.trimEnd().endsWith("--"))
    }

    @Test fun mtimeIsDroppedWhenDsmRejectsIt() = runBlocking {
        rejectUpload = { body -> if (body.contains("name=\"mtime\"")) 401 else null }
        val c = client()
        c.upload(file(), "/home/Notes/노트.inknote/document.json", Instant.now())
        assertEquals(2, uploads.size)
        assertFalse(uploads[1].contains("name=\"mtime\""))
        // Remembered for the next files.
        c.upload(file(), "/home/Notes/노트.inknote/thumbnail.png", Instant.now())
        assertEquals(3, uploads.size)
        assertFalse(uploads[2].contains("name=\"mtime\""))
    }

    @Test fun otherErrorsKeepDsmDetails() = runBlocking {
        rejectUpload = { 416 }
        try {
            client().upload(file(), "/home/Notes/a.pdf", Instant.now())
            throw AssertionError("expected failure")
        } catch (e: RemoteException.InsufficientStorage) {
            assertEquals(1, uploads.size)   // no mtime retry for unrelated errors
        }
    }
}
