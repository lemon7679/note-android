package com.lemon7679.note.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RemoteClientTest {
    @Test fun apacheStyleResponse() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/Notes/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>
                  <D:getlastmodified>Mon, 23 Nov 2026 07:20:00 GMT</D:getlastmodified></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/Notes/%EA%B0%95%EC%9D%98%20%EC%9E%90%EB%A3%8C.pdf</D:href>
                <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>3400</D:getcontentlength>
                  <D:getlastmodified>Sun, 22 Nov 2026 10:00:00 GMT</D:getlastmodified>
                  <D:getetag>W/"abc-123"</D:getetag></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                <D:propstat><D:prop><D:quota/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/Notes/선형대수.inknote/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val items = WebDavMultistatusParser.parse(xml.toByteArray(), "/dav")
        assertEquals(listOf("/Notes", "/Notes/강의 자료.pdf", "/Notes/선형대수.inknote"), items.map { it.path })
        assertTrue(items[0].isDirectory)
        assertFalse(items[1].isDirectory)
        assertEquals(3400L, items[1].size)
        assertEquals("abc-123", items[1].etag)
        assertEquals("e:abc-123", items[1].signature)
        assertEquals(HttpDate.parse("Sun, 22 Nov 2026 10:00:00 GMT"), items[1].modified)
        assertTrue(items[2].isDirectory)
    }

    @Test fun nextcloudStyleAbsoluteHrefs() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
             <d:response><d:href>https://cloud.example/remote.php/dav/files/me/Notes/a%2Bb.pdf</d:href>
              <d:propstat><d:prop><d:getcontentlength>10</d:getcontentlength><d:resourcetype/>
               <d:getetag>&quot;5f1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
             </d:response>
            </d:multistatus>
        """.trimIndent()
        val items = WebDavMultistatusParser.parse(xml.toByteArray(), "/remote.php/dav/files/me")
        assertEquals(listOf("/Notes/a+b.pdf"), items.map { it.path })
        assertEquals("5f1", items[0].etag)
    }

    @Test(expected = RemoteException.InvalidResponse::class)
    fun garbageIsAnError() {
        WebDavMultistatusParser.parse("<not".toByteArray(), "/")
    }

    @Test fun urlEncodingOfPaths() {
        val client = WebDavClient("https://nas.local:5006/dav/", "", "")
        assertEquals("https://nas.local:5006/dav/%EA%B0%95%EC%9D%98%20%EC%9E%90%EB%A3%8C/a%231%3F.pdf", client.url("/강의 자료/a#1?.pdf"))
        assertEquals("https://nas.local:5006/dav/Notes/", client.url("/Notes", directory = true))
        assertEquals("/dav", client.basePath)
    }

    @Test fun httpDates() {
        val d = HttpDate.parse("Mon, 23 Nov 2026 07:20:00 GMT")
        assertNotNull(d)
        assertEquals("Mon, 23 Nov 2026 07:20:00 GMT", HttpDate.format(d!!))
    }

    @Test fun synologyEncodingAndJsonArrays() {
        assertEquals("a%2Bb%20c%26d%2F%C3%A9", SynologyClient.encode("a+b c&d/é"))
        assertEquals("[\"/home/노트\",\"a\\\"b\"]", SynologyClient.jsonArray(listOf("/home/노트", "a\"b")))
        val decoded = Json.parseToJsonElement(SynologyClient.jsonArray(listOf("/home/노트", "x")))
        assertEquals("[\"/home/노트\",\"x\"]", decoded.toString())
    }

    @Test fun synologyErrorMapping() {
        assertTrue(SynologyClient.map(403, "SYNO.API.Auth") is RemoteException.TwoFactorRequired)
        assertTrue(SynologyClient.map(400, "SYNO.API.Auth") is RemoteException.Unauthorized)
        assertTrue(SynologyClient.map(408, "SYNO.FileStation.List") is RemoteException.NotFound)
        assertTrue(SynologyClient.map(416, "SYNO.FileStation.Upload").stopsSync)
    }

    @Test fun synologyFileItemParsing() {
        val file = Json.parseToJsonElement(
            """{"path":"/home/Notes/a.pdf","isdir":false,"name":"a.pdf","additional":{"size":1234,"time":{"mtime":1795000000}}}""",
        ).jsonObject
        val item = SynologyClient.item(file)!!
        assertEquals("/home/Notes/a.pdf", item.path)
        assertEquals(1234L, item.size)
        assertEquals(Instant.ofEpochSecond(1_795_000_000), item.modified)
        assertEquals("s:1234:1795000000", item.signature)
    }

    @Test fun accountUrls() {
        val syn = NasAccount(kind = NasKind.SYNOLOGY, host = "https://192.168.0.10/", useHttps = true)
        assertEquals("https://192.168.0.10:5001", syn.baseUrl)
        val dav = NasAccount(kind = NasKind.WEBDAV, host = "cloud.example", port = 443, endpointPath = "/remote.php/dav/files/me")
        assertEquals("https://cloud.example:443/remote.php/dav/files/me", dav.baseUrl)
        val v6 = NasAccount(kind = NasKind.WEBDAV, host = "fe80::1", useHttps = false)
        assertEquals("http://[fe80::1]:5005", v6.baseUrl)
        assertEquals(null, NasAccount(host = " ").baseUrl)
    }
}
