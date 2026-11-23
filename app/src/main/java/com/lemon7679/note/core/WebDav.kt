package com.lemon7679.note.core

import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import kotlin.coroutines.coroutineContext

/**
 * WebDAV (RFC 4918) client: Synology "WebDAV Server" package, QNAP, TrueNAS,
 * OpenMediaVault, Nextcloud/ownCloud, Apache/nginx dav.
 *
 * Paths are relative to the endpoint (`baseUrl`), e.g. baseUrl
 * `https://nas.local:5006` and path `/home/Notes`, or baseUrl
 * `https://cloud.example/remote.php/dav/files/me` and path `/Notes`.
 */
class WebDavClient(
    val baseUrl: String,
    username: String,
    password: String,
    tls: TlsPolicy = TlsPolicy(),
    transport: HttpTransport? = null,
) : RemoteFileSystem {
    private val authorization = if (username.isEmpty()) null else HttpTransport.basicAuthorization(username, password)
    val transport: HttpTransport = transport ?: HttpTransport(tls)
    @Volatile private var depthInfinitySupported = true

    fun url(path: String, directory: Boolean = false): String {
        var encoded = RemotePath.components(path).joinToString("") { "/" + encodeSegment(it) }
        if (directory || encoded.isEmpty()) encoded += "/"
        return baseUrl.trimEnd('/') + encoded
    }

    /** Server path of the endpoint root, decoded ("/remote.php/dav/files/me"). */
    val basePath: String get() = RemotePath.normalize(decodePath(URI(baseUrl).rawPath ?: "/"))

    private fun request(method: String, path: String, directory: Boolean = false, body: RequestBody? = null,
                        headers: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(url(path, directory)).method(method, body)
        authorization?.let { b.header("Authorization", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private fun check(r: HttpResult, path: String, allow: Set<Int> = emptySet()) {
        if (r.isSuccess || r.status in allow) return
        throw error(r.status, path)
    }

    private suspend fun propfind(path: String, depth: String, directory: Boolean): List<RemoteItem> {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val r = transport.execute(request("PROPFIND", path, directory, body, mapOf("Depth" to depth)))
        check(r, path)
        return WebDavMultistatusParser.parse(r.body, basePath)
    }

    override suspend fun list(path: String): List<RemoteItem> {
        val dir = RemotePath.normalize(path)
        return propfind(dir, "1", true).filter { it.path != dir && !FileNaming.isIgnored(it.name) }
    }

    override suspend fun listRecursive(path: String): List<RemoteItem> {
        val dir = RemotePath.normalize(path)
        if (depthInfinitySupported) {
            try {
                return propfind(dir, "infinity", true).filter { item ->
                    item.path != dir && RemotePath.components(item.path).none(FileNaming::isIgnored)
                }
            } catch (e: RemoteException.Forbidden) {
                depthInfinitySupported = false   // most servers disable infinite depth
            } catch (e: RemoteException.Server) {
                if (e.status != 400 && e.status != 501) throw e
                depthInfinitySupported = false
            }
        }
        val out = ArrayList<RemoteItem>()
        val queue = ArrayDeque(listOf(dir))
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            for (item in list(queue.removeFirst())) {
                out.add(item)
                if (item.isDirectory) queue.add(item.path)
            }
        }
        return out
    }

    override suspend fun stat(path: String): RemoteItem? {
        val p = RemotePath.normalize(path)
        return try {
            val items = propfind(p, "0", false)
            items.firstOrNull { it.path == p } ?: items.firstOrNull()
        } catch (e: RemoteException.NotFound) {
            null
        }
    }

    override suspend fun download(path: String, to: File) {
        val r = transport.download(request("GET", path), to)
        check(r, path)
    }

    override suspend fun upload(from: File, path: String, modified: Instant?): RemoteItem? {
        val headers = HashMap<String, String>()
        // Nextcloud/ownCloud keep the original modification time.
        modified?.let { headers["X-OC-Mtime"] = it.epochSecond.toString() }
        fun put() = request("PUT", path, body = from.asRequestBody("application/octet-stream".toMediaType()), headers = headers)
        var r = transport.execute(put())
        if (r.status == 409) {
            createDirectory(RemotePath.parent(path))
            r = transport.execute(put())
        }
        check(r, path)
        return stat(path)
    }

    override suspend fun createDirectory(path: String) {
        val p = RemotePath.normalize(path)
        if (p == "/") return
        val r = transport.execute(request("MKCOL", p, directory = true))
        when {
            r.isSuccess || r.status == 405 -> return  // created, or already exists
            r.status == 409 -> {
                createDirectory(RemotePath.parent(p))
                check(transport.execute(request("MKCOL", p, directory = true)), p, setOf(405))
            }
            else -> check(r, p)
        }
    }

    override suspend fun delete(path: String) {
        val p = RemotePath.normalize(path)
        if (p == "/") throw RemoteException.Forbidden()
        check(transport.execute(request("DELETE", p)), p, setOf(404))
    }

    override suspend fun move(from: String, to: String) {
        fun req() = request("MOVE", from, headers = mapOf("Destination" to url(to), "Overwrite" to "T"))
        var r = transport.execute(req())
        if (r.status == 409) {
            createDirectory(RemotePath.parent(to))
            r = transport.execute(req())
        }
        check(r, from)
    }

    override suspend fun testConnection(probeWrite: Boolean, folder: String): ServerInfo {
        val options = transport.execute(request("OPTIONS", folder, directory = true, headers = mapOf("Depth" to "0")))
        if (options.status == 401) throw RemoteException.Unauthorized()
        val server = options.header("Server") ?: ""
        stat(folder) ?: throw RemoteException.NotFound(folder)
        val writable = if (probeWrite) probeWritable(folder) else true
        return ServerInfo("WebDAV", server, writable)
    }

    companion object {
        const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"

        private const val SEGMENT_ALLOWED = "-._~!$&'()*+,;=:@"

        /** RFC 3986 `pchar` minus "/" — what may appear unescaped inside one path segment. */
        fun encodeSegment(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in SEGMENT_ALLOWED)) sb.append(ch)
                else sb.append('%').append("%02X".format(c))
            }
            return sb.toString()
        }

        fun decodePath(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        fun error(status: Int, path: String): RemoteException = when (status) {
            401 -> RemoteException.Unauthorized()
            403 -> RemoteException.Forbidden()
            404, 410 -> RemoteException.NotFound(path)
            405, 412 -> RemoteException.AlreadyExists(path)
            409 -> RemoteException.Conflict(RemotePath.parent(path))
            507 -> RemoteException.InsufficientStorage()
            else -> RemoteException.Server(status)
        }
    }
}

/** Parses a 207 Multi-Status PROPFIND response. */
object WebDavMultistatusParser {
    private class Response {
        var href: String? = null
        var isCollection = false
        var length: Long? = null
        var modified: String? = null
        var etag: String? = null
        var ok = true
    }

    fun parse(data: ByteArray, basePath: String): List<RemoteItem> {
        val responses = ArrayList<Response>()
        var sawMultistatus = false
        val handler = object : DefaultHandler() {
            var current: Response? = null
            var propstat = Response()
            var inPropstat = false
            val text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                text.setLength(0)
                when (local(localName, qName)) {
                    "multistatus" -> sawMultistatus = true
                    "response" -> current = Response()
                    "propstat" -> { inPropstat = true; propstat = Response() }
                    "collection" -> if (inPropstat) propstat.isCollection = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val value = text.toString().trim()
                when (local(localName, qName)) {
                    "href" -> if (current != null && !inPropstat && current?.href == null) current?.href = value
                    "getcontentlength" -> if (inPropstat) propstat.length = value.toLongOrNull()
                    "getlastmodified" -> if (inPropstat) propstat.modified = value
                    "getetag" -> if (inPropstat) propstat.etag = value
                    "status" -> if (inPropstat) propstat.ok = value.contains(" 2") else current?.ok = value.contains(" 2")
                    "propstat" -> {
                        inPropstat = false
                        if (propstat.ok) {
                            current?.let { c ->
                                if (propstat.isCollection) c.isCollection = true
                                propstat.length?.let { c.length = it }
                                propstat.modified?.let { c.modified = it }
                                propstat.etag?.let { c.etag = it }
                            }
                        }
                    }
                    "response" -> {
                        current?.let { responses.add(it) }
                        current = null
                    }
                }
                text.setLength(0)
            }
        }
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), handler)
        } catch (e: Exception) {
            throw RemoteException.InvalidResponse()
        }
        if (!sawMultistatus) throw RemoteException.InvalidResponse()
        return responses.mapNotNull { r ->
            val href = r.href ?: return@mapNotNull null
            if (!r.ok) return@mapNotNull null
            val serverPath = decodeHref(href) ?: return@mapNotNull null
            val rel = RemotePath.relative(serverPath, basePath) ?: return@mapNotNull null
            RemoteItem.of(rel, r.isCollection, r.length ?: 0, r.modified?.let(HttpDate::parse), r.etag?.let(::cleanEtag))
        }
    }

    private fun local(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: ""

    /** href may be an absolute URL or an absolute path, percent-encoded. */
    fun decodeHref(href: String): String? {
        val trimmed = href.trim()
        var raw = trimmed
        if (trimmed.contains("://")) raw = runCatching { URI(trimmed).rawPath }.getOrNull()?.ifEmpty { "/" } ?: return null
        return RemotePath.normalize(WebDavClient.decodePath(raw))
    }

    fun cleanEtag(etag: String): String = etag.trim().removePrefix("W/").trim('"')
}

/** RFC 1123 dates used by HTTP / WebDAV ("Mon, 23 Nov 2026 07:20:00 GMT"). */
object HttpDate {
    private val formats = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US).withZone(java.time.ZoneOffset.UTC),
    )

    fun parse(s: String): Instant? {
        for (f in formats) {
            runCatching { return ZonedDateTime.parse(s.trim(), f).toInstant() }
        }
        return runCatching { Instant.parse(s.trim()) }.getOrNull()
    }

    fun format(i: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(i.atZone(java.time.ZoneOffset.UTC))
}
