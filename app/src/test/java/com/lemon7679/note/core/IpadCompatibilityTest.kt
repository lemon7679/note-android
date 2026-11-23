package com.lemon7679.note.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The same `.inknote` must open on the iPad and on Android. This is the document.json
 * the iPad app writes (Swift JSONEncoder: sorted keys, ISO 8601 dates, upper-case UUIDs,
 * nil optionals omitted) — see note-ipad docs/ARCHITECTURE.md.
 */
class IpadCompatibilityTest {
    private val ipadJson = """
        {
          "cover" : { "colorHex" : "#2F5BD3" },
          "created" : "2026-11-23T07:20:00Z",
          "defaultPaper" : { "color" : "white", "spacing" : 0, "template" : "ruled" },
          "defaultSize" : { "height" : 841.89, "width" : 595.28 },
          "format" : 1,
          "id" : "6F1C1D5E-2B1A-4C5B-9C1E-0D2A7A9C3B11",
          "lastPageIndex" : 0,
          "modified" : "2026-11-23T08:02:00Z",
          "pages" : [
            {
              "background" : { "kind" : "paper" },
              "bookmarked" : false,
              "elements" : [
                { "colorHex" : "#1C1C1E", "fontSize" : 16, "frame" : { "height" : 28, "width" : 240, "x" : 80, "y" : 120 },
                  "id" : "0A3F5B2C-11D4-4E2B-8F6A-3C9B1D2E4F50", "kind" : "text", "text" : "메모" }
              ],
              "id" : "A1B2C3D4-E5F6-4711-8899-AABBCCDDEEFF",
              "paper" : { "color" : "ivory", "spacing" : 0, "template" : "narrowRuled" },
              "size" : { "height" : 841.89, "width" : 595.28 }
            },
            {
              "background" : { "kind" : "pdf", "pageIndex" : 3 },
              "bookmarked" : true,
              "elements" : [],
              "id" : "B1B2C3D4-E5F6-4711-8899-AABBCCDDEEFF",
              "paper" : { "color" : "white", "spacing" : 0, "template" : "blank" },
              "size" : { "height" : 792, "width" : 612 }
            }
          ],
          "pdf" : { "file" : "source.pdf", "linkedPath" : "/강의자료/운영체제_09강.pdf", "originalName" : "운영체제_09강.pdf" },
          "title" : "선형대수 필기"
        }
    """.trimIndent()

    @Test fun readsTheIpadDocument() {
        val doc = NoteJson.decodeFromString(NotebookDocument.serializer(), ipadJson)
        assertEquals("선형대수 필기", doc.title)
        assertEquals(Instant.parse("2026-11-23T07:20:00Z"), doc.created)
        assertEquals("#2F5BD3", doc.cover.colorHex)
        assertEquals(PaperTemplate.NARROW_RULED, doc.pages[0].paper.template)
        assertEquals(PaperColor.IVORY, doc.pages[0].paper.color)
        assertNull(doc.pages[0].background.pdfPageIndex)
        assertEquals(3, doc.pages[1].background.pdfPageIndex)
        assertTrue(doc.pages[1].bookmarked)
        assertEquals("메모", doc.pages[0].elements.single().text)
        assertEquals(PageElement.Kind.TEXT, doc.pages[0].elements.single().kind)
        assertEquals("A1B2C3D4-E5F6-4711-8899-AABBCCDDEEFF.ink", doc.pages[0].inkFileName)
        assertEquals("/강의자료/운영체제_09강.pdf", doc.pdf?.linkedPath)
    }

    @Test fun writesWhatTheIpadCanRead() {
        val doc = NoteJson.decodeFromString(NotebookDocument.serializer(), ipadJson)
            .copy(modified = Instant.parse("2026-11-24T01:02:03.456789Z"), cover = CoverStyle(null))
        val json = Json.parseToJsonElement(NoteJson.encodeToString(NotebookDocument.serializer(), doc)).jsonObject
        // Swift's .iso8601 date strategy rejects fractional seconds.
        assertEquals("2026-11-24T01:02:03Z", json["modified"]!!.jsonPrimitive.content)
        // nil optionals are omitted, enums use the Swift raw values.
        assertFalse(json["cover"]!!.jsonObject.containsKey("colorHex"))
        val page0 = json["pages"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("""{"kind":"paper"}""", page0["background"].toString())
        assertEquals("narrowRuled", page0["paper"]!!.jsonObject["template"]!!.jsonPrimitive.content)
        // New ids look like Swift's UUID().uuidString.
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(newId()))
    }

    @Test fun readsTheIpadTrashIndexAndSyncState() {
        val trash = """[{"deletedAt":"2026-11-23T07:20:00Z","originalPath":"/강의/a.pdf","storedName":"X-a.pdf"}]"""
        val entries = NoteJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrashBin.Entry.serializer()), trash)
        assertEquals("a.pdf", entries.single().displayName)
        val state = """{"version":1,"records":{"/a.pdf":{"isDirectory":false,"remoteSig":"s:1:2","remoteSize":1}},
            "conflicts":{},"resolutions":{"/b.pdf":"keepBoth"},"lastSync":"2026-11-23T07:20:00Z"}"""
        val s = NoteJson.decodeFromString(SyncState.serializer(), state)
        assertTrue(s.records["/a.pdf"]!!.isPlaceholder)
        assertEquals(ConflictResolution.KEEP_BOTH, s.resolutions["/b.pdf"])
    }
}
