package com.lemon7679.note.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

/**
 * Synology DSM File Station Web API client (DSM 6.2 / 7.x).
 *
 * Works with nothing but the default DSM web port (5000/5001) — no WebDAV
 * package needed — and supports 2-step verification: the first login with an
 * OTP code returns a device token that skips the code afterwards.
 * Paths are shared-folder paths: "/home/Notes", "/homes/<user>/Notes", "/docs".
 */
class SynologyClient(
    val baseUrl: String,
    val username: String,
    private val password: String,
    deviceToken: String? = null,
    tls: TlsPolicy = TlsPolicy(),
    transport: HttpTransport? = null,
) : RemoteFileSystem {
    /** One-time code for the next login (2-step verification). */
    @Volatile var otpCode: String? = null

    /** Device token from a previous OTP login ("did"); saved by the app. */
    @Volatile var deviceToken: String? = deviceToken
        private set
    var onDeviceToken: ((String) -> Unit)? = null

    val transport: HttpTransport = transport ?: HttpTransport(tls, 60)
    @Volatile private var sid: String? = null

    private val entryUrl get() = baseUrl.trimEnd('/') + "/webapi/entry.cgi"

    private class Decoded(val payload: JsonObject, val errorCode: Int?, val detail: String? = null)

    /** POSTs an API call and returns the `data` object. Re-logins once when the session expired. */
    private suspend fun call(api: String, version: Int, method: String, params: List<Pair<String, String>> = emptyList(),
                             retrying: Boolean = true): JsonObject {
        if (api != AUTH && sid == null) login()
        val all = ArrayList<Pair<String, String>>()
        all += "api" to api
        all += "version" to version.toString()
        all += "method" to method
        all += params
        if (api != AUTH) sid?.let { all += "_sid" to it }
        val body = formBody(all).toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val r = transport.execute(Request.Builder().url(entryUrl).post(body).build())
        if (!r.isSuccess) throw RemoteException.Server(r.status)
        val decoded = decode(r.body)
        val code = decoded.errorCode
        if (code != null) {
            if (retrying && api != AUTH && code in SESSION_ERRORS) {
                sid = null
                return call(api, version, method, params, false)
            }
            throw map(code, api)
        }
        return decoded.payload
    }

    suspend fun login() {
        val params = arrayListOf("account" to username, "passwd" to password, "session" to "FileStation", "format" to "sid")
        val otp = otpCode
        val did = deviceToken
        if (!otp.isNullOrEmpty()) {
            params += "otp_code" to otp
            params += "enable_device_token" to "yes"
            params += "device_name" to DEVICE_NAME
        } else if (!did.isNullOrEmpty()) {
            params += "device_id" to did
            params += "device_name" to DEVICE_NAME
        }
        val data = call(AUTH, 6, "login", params)
        sid = data.str("sid") ?: throw RemoteException.InvalidResponse()
        otpCode = null
        val token = data.str("did") ?: data.str("device_id")
        if (!token.isNullOrEmpty()) {
            deviceToken = token
            onDeviceToken?.invoke(token)
        }
    }

    suspend fun logout() {
        if (sid == null) return
        runCatching { call(AUTH, 6, "logout", listOf("session" to "FileStation")) }
        sid = null
    }

    override suspend fun list(path: String): List<RemoteItem> {
        val folder = RemotePath.normalize(path)
        val out = ArrayList<RemoteItem>()
        var offset = 0
        while (true) {
            val data = call("SYNO.FileStation.List", 2, "list", listOf(
                "folder_path" to folder, "offset" to offset.toString(), "limit" to "1000",
                "additional" to jsonArray(listOf("size", "time")),
            ))
            val files = data["files"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
            out += files.mapNotNull { item(it.jsonObject) }
            val total = (data["total"] as? JsonPrimitive)?.intOrNull ?: out.size
            offset += files.size
            if (files.isEmpty() || offset >= total) break
        }
        return out.filter { !FileNaming.isIgnored(it.name) }
    }

    /** Shared folders visible to the user ("/home", "/docs" …), for the folder picker root. */
    suspend fun listShares(): List<RemoteItem> {
        val data = call("SYNO.FileStation.List", 2, "list_share", listOf("additional" to jsonArray(listOf("time"))))
        val shares = data["shares"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return shares.mapNotNull { s -> s.jsonObject.str("path")?.let { RemoteItem.of(it, true) } }
    }

    override suspend fun stat(path: String): RemoteItem? {
        val p = RemotePath.normalize(path)
        if (p == "/") return RemoteItem.of("/", true)
        return try {
            val data = call("SYNO.FileStation.List", 2, "getinfo", listOf(
                "path" to jsonArray(listOf(p)), "additional" to jsonArray(listOf("size", "time")),
            ))
            val file = data["files"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            if (file["code"] != null) null else item(file)   // per-item error 408: no such file
        } catch (e: RemoteException.NotFound) {
            null
        }
    }

    override suspend fun download(path: String, to: File) {
        if (sid == null) login()
        fun request(): Request {
            val query = listOf(
                "api" to "SYNO.FileStation.Download", "version" to "2", "method" to "download",
                "path" to RemotePath.normalize(path), "mode" to "download", "_sid" to (sid ?: ""),
            ).joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
            return Request.Builder().url("$entryUrl?$query").build()
        }
        var r = transport.download(request(), to)
        if (isJson(r)) {
            // DSM answers errors with a small JSON body instead of the file.
            to.delete()
            val code = runCatching { decode(r.body).errorCode }.getOrNull() ?: 100
            if (code !in SESSION_ERRORS) throw map(code, "SYNO.FileStation.Download")
            sid = null
            login()
            r = transport.download(request(), to)
            if (isJson(r)) {
                to.delete()
                throw RemoteException.Synology(code)
            }
        }
        if (!r.isSuccess) throw RemoteException.Server(r.status)
    }

    private fun isJson(r: HttpResult) = (r.header("Content-Type") ?: "").lowercase().contains("application/json")

    /** Some DSM builds reject the optional `mtime` field; after the first such refusal it is left out. */
    @Volatile private var sendMtime = true

    override suspend fun upload(from: File, path: String, modified: Instant?): RemoteItem? {
        val target = RemotePath.normalize(path)
        val mtime = modified?.takeIf { sendMtime }
        try {
            uploadOnce(from, target, mtime, true)
        } catch (e: RemoteException.Synology) {
            if (mtime == null || e.code !in MTIME_SUSPECT_ERRORS) throw e
            uploadOnce(from, target, null, true)   // succeeded without mtime: stop sending it
            sendMtime = false
        }
        return stat(target)
    }

    private suspend fun uploadOnce(from: File, target: String, modified: Instant?, retrying: Boolean) {
        if (sid == null) login()
        val fields = arrayListOf("path" to RemotePath.parent(target), "create_parents" to "true", "overwrite" to "true")
        modified?.let { fields += "mtime" to it.toEpochMilli().toString() }
        val body = DsmUploadBody(fields, RemotePath.name(target), from)
        val query = listOf("api" to "SYNO.FileStation.Upload", "version" to "2", "method" to "upload", "_sid" to (sid ?: ""))
            .joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        val r = transport.execute(Request.Builder().url("$entryUrl?$query").post(body).build())
        if (!r.isSuccess) throw RemoteException.Server(r.status)
        val decoded = decode(r.body)
        val code = decoded.errorCode ?: return
        if (retrying && code in SESSION_ERRORS) {
            sid = null
            uploadOnce(from, target, modified, false)
            return
        }
        val mapped = map(code, "SYNO.FileStation.Upload")
        // Keep what DSM said (e.g. {"code":401,"errors":[…]}) so a failure can be diagnosed.
        throw if (mapped is RemoteException.Synology) RemoteException.Synology(code, decoded.detail) else mapped
    }

    override suspend fun createDirectory(path: String) {
        val p = RemotePath.normalize(path)
        if (RemotePath.depth(p) <= 1) return  // shared folders cannot be created here
        try {
            call("SYNO.FileStation.CreateFolder", 2, "create", listOf(
                "folder_path" to jsonArray(listOf(RemotePath.parent(p))),
                "name" to jsonArray(listOf(RemotePath.name(p))),
                "force_parent" to "true",
            ))
        } catch (e: RemoteException.AlreadyExists) {
            return
        }
    }

    override suspend fun delete(path: String) {
        val p = RemotePath.normalize(path)
        if (RemotePath.depth(p) <= 1) throw RemoteException.Forbidden()
        try {
            call("SYNO.FileStation.Delete", 2, "delete", listOf("path" to jsonArray(listOf(p)), "recursive" to "true"))
        } catch (e: RemoteException.NotFound) {
            return
        }
    }

    override suspend fun move(from: String, to: String) {
        val src = RemotePath.normalize(from)
        val dst = RemotePath.normalize(to)
        if (RemotePath.parent(src) == RemotePath.parent(dst)) {
            rename(src, RemotePath.name(dst))
            return
        }
        // Move into the destination folder under the source name, then rename if needed.
        createDirectory(RemotePath.parent(dst))
        val started = call("SYNO.FileStation.CopyMove", 3, "start", listOf(
            "path" to jsonArray(listOf(src)), "dest_folder_path" to RemotePath.parent(dst),
            "overwrite" to "true", "remove_src" to "true",
        ))
        val taskId = started.str("taskid") ?: throw RemoteException.InvalidResponse()
        while (true) {
            delay(300)
            val status = call("SYNO.FileStation.CopyMove", 3, "status", listOf("taskid" to taskId))
            if ((status["finished"] as? JsonPrimitive)?.booleanOrNull == true) break
        }
        if (RemotePath.name(src) != RemotePath.name(dst)) {
            rename(RemotePath.join(RemotePath.parent(dst), RemotePath.name(src)), RemotePath.name(dst))
        }
    }

    private suspend fun rename(path: String, name: String) {
        call("SYNO.FileStation.Rename", 2, "rename", listOf("path" to jsonArray(listOf(path)), "name" to jsonArray(listOf(name))))
    }

    override suspend fun testConnection(probeWrite: Boolean, folder: String): ServerInfo {
        sid = null
        login()
        val info = runCatching { call("SYNO.FileStation.Info", 2, "get") }.getOrNull()
        val host = info?.str("hostname") ?: ""
        if (RemotePath.depth(folder) > 0) stat(folder) ?: throw RemoteException.NotFound(folder)
        val writable = if (probeWrite && RemotePath.depth(folder) > 1) probeWritable(folder) else true
        return ServerInfo("Synology DSM", host, writable)
    }

    companion object {
        private const val AUTH = "SYNO.API.Auth"
        private const val DEVICE_NAME = "Note Android"
        private val SESSION_ERRORS = setOf(106, 107, 119)
        private val MTIME_SUSPECT_ERRORS = setOf(400, 401)
        private val json = Json { ignoreUnknownKeys = true }

        /** Query-string escaping that also escapes "+" and "&" (DSM decodes "+" as space). */
        fun encode(value: String): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~")) sb.append(ch) else sb.append("%%%02X".format(c))
            }
            return sb.toString()
        }

        fun formBody(params: List<Pair<String, String>>): String =
            params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

        /** JSON array parameter: ["/home/a"]. */
        fun jsonArray(values: List<String>): String =
            "[" + values.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "]"

        private fun decode(data: ByteArray): Decoded {
            val obj = runCatching { json.parseToJsonElement(data.decodeToString()).jsonObject }.getOrNull()
                ?: throw RemoteException.InvalidResponse()
            val success = (obj["success"] as? JsonPrimitive)?.booleanOrNull ?: throw RemoteException.InvalidResponse()
            if (success) return Decoded((obj["data"] as? JsonObject) ?: JsonObject(emptyMap()), null)
            val error = obj["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.intOrNull ?: 100
            val detail = error?.filterKeys { it != "code" }?.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString().take(200) }
            return Decoded(JsonObject(emptyMap()), code, detail)
        }

        fun map(code: Int, api: String): RemoteException {
            if (api == AUTH) {
                return when (code) {
                    400, 401, 402 -> RemoteException.Unauthorized()
                    403 -> RemoteException.TwoFactorRequired()
                    404 -> RemoteException.TwoFactorInvalid()
                    else -> RemoteException.Synology(code)
                }
            }
            return when (code) {
                105, 407 -> RemoteException.Forbidden()
                408 -> RemoteException.NotFound("")
                414 -> RemoteException.AlreadyExists("")
                416 -> RemoteException.InsufficientStorage()
                else -> RemoteException.Synology(code)
            }
        }

        fun message(code: Int): String = when (code) {
            100 -> "알 수 없는 DSM 오류입니다."
            101, 102, 103, 104 -> "DSM이 이 요청을 지원하지 않습니다. DSM을 업데이트해 주세요."
            105 -> "권한이 없습니다."
            400 -> "DSM이 파일 작업 요청을 이해하지 못했습니다 (400)."
            401 -> "DSM이 파일 작업을 거부했습니다 (401)."
            402 -> "NAS가 바빠 파일 작업을 하지 못했습니다 (402)."
            403 -> "이 사용자는 이 파일 작업을 할 수 없습니다 (403)."
            404 -> "이 사용자 그룹은 이 파일 작업을 할 수 없습니다 (404)."
            106 -> "세션이 만료되었습니다."
            107 -> "다른 곳에서 로그인하여 세션이 끊어졌습니다."
            119 -> "세션 ID를 찾을 수 없습니다."
            406 -> "이 계정은 2단계 인증을 켜야 로그인할 수 있습니다."
            407 -> "로그인 시도가 너무 많아 IP가 차단되었습니다."
            408 -> "파일 또는 폴더가 없습니다."
            409, 410 -> "암호가 만료되었습니다. DSM에서 암호를 변경해 주세요."
            414 -> "같은 이름의 파일이 이미 있습니다."
            415 -> "디스크 할당량을 초과했습니다."
            416 -> "NAS 저장 공간이 부족합니다."
            418 -> "잘못된 경로입니다."
            419 -> "잘못된 파일 이름입니다."
            in 1800..1805 -> "업로드에 실패했습니다 ($code)."
            else -> "DSM 오류 $code"
        }

        fun item(file: JsonObject): RemoteItem? {
            val path = file.str("path") ?: return null
            val additional = file["additional"] as? JsonObject
            val size = (additional?.get("size") as? JsonPrimitive)?.longOrNull ?: 0
            val mtime = ((additional?.get("time") as? JsonObject)?.get("mtime") as? JsonPrimitive)?.doubleOrNull
            return RemoteItem.of(path, (file["isdir"] as? JsonPrimitive)?.booleanOrNull ?: false, size,
                mtime?.let { Instant.ofEpochMilli((it * 1000).toLong()) })
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        @Suppress("unused") private fun JsonElement.asObject() = this as? JsonObject
    }
}
