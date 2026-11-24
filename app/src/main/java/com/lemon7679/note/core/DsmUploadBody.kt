package com.lemon7679.note.core

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File

/**
 * multipart/form-data for SYNO.FileStation.Upload, written exactly like Synology's own example
 * (and the iPad app): text fields first, the file part last, and no per-part headers besides
 * Content-Disposition (and Content-Type for the file). OkHttp's MultipartBody adds a
 * `Content-Length` header to every part; with it, a real DSM created the notebook folders
 * (plain API calls) but refused every file upload with error 401.
 */
class DsmUploadBody(
    fields: List<Pair<String, String>>,
    fileName: String,
    private val file: File,
    private val boundary: String = "NoteBoundary-" + newId(),
) : RequestBody() {
    private val head: ByteArray
    private val tail: ByteArray = "\r\n--$boundary--\r\n".toByteArray()

    init {
        val sb = StringBuilder()
        for ((name, value) in fields) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(quote(name)).append("\"\r\n\r\n")
            sb.append(value).append("\r\n")
        }
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(quote(fileName)).append("\"\r\n")
        sb.append("Content-Type: application/octet-stream\r\n\r\n")
        head = sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(s: String) = s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

    override fun contentType(): MediaType = "multipart/form-data; boundary=$boundary".toMediaType()

    override fun contentLength(): Long = head.size + file.length() + tail.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        file.source().use { sink.writeAll(it) }
        sink.write(tail)
    }
}
