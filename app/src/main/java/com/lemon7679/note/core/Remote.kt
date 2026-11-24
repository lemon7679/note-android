package com.lemon7679.note.core

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** A file or folder on a NAS. Paths are absolute on the server ("/home/Notes/a.pdf"). */
@Serializable
data class RemoteItem(
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    @Serializable(with = Iso8601Serializer::class) val modified: Instant? = null,
    /** Server change token (WebDAV ETag) when the server has one. */
    val etag: String? = null,
) {
    val name: String get() = RemotePath.name(path)

    /** Signature used by the sync engine to notice remote changes. */
    val signature: String
        get() {
            if (isDirectory) return "dir"
            if (!etag.isNullOrEmpty()) return "e:$etag"
            val ms = modified?.toEpochMilli() ?: 0
            return "s:$size:${ms / 1000}"
        }

    companion object {
        fun of(path: String, isDirectory: Boolean, size: Long = 0, modified: Instant? = null, etag: String? = null) =
            RemoteItem(RemotePath.normalize(path), isDirectory, size, modified, etag)
    }
}

data class ServerInfo(val product: String, val detail: String, val writable: Boolean) {
    val summary: String
        get() = listOfNotNull(product, detail.ifEmpty { null }, if (writable) "쓰기 가능" else "읽기 전용").joinToString(" · ")
}

sealed class RemoteException(message: String) : IOException(message) {
    class NotFound(val path: String) :
        RemoteException("${RemotePath.name(path).ifEmpty { path }}을(를) NAS에서 찾을 수 없습니다.")
    class Unauthorized : RemoteException("사용자 이름 또는 암호가 올바르지 않습니다.")
    class Forbidden : RemoteException("이 폴더에 접근할 권한이 없습니다.")
    class AlreadyExists(val path: String) : RemoteException("${RemotePath.name(path)}이(가) 이미 있습니다.")
    class Conflict(val path: String) : RemoteException("$path 상위 폴더가 없습니다.")
    class InsufficientStorage : RemoteException("NAS 저장 공간이 부족합니다.")
    class Server(val status: Int, detail: String? = null) :
        RemoteException("서버 오류 ($status)" + (detail?.let { ": $it" } ?: ""))
    class Network(detail: String) : RemoteException("네트워크 오류: $detail")
    class InvalidResponse : RemoteException("서버 응답을 해석할 수 없습니다.")
    /** TLS certificate not trusted; carries the SHA-256 fingerprint so the user can trust it. */
    class UntrustedCertificate(val fingerprint: String?) :
        RemoteException("서버 인증서를 신뢰할 수 없습니다. ‘자체 서명 인증서 신뢰’를 켜 주세요.")
    class CertificateChanged(val fingerprint: String) :
        RemoteException("서버 인증서가 이전과 다릅니다. 보안을 위해 연결을 중단했습니다.")
    class TwoFactorRequired : RemoteException("2단계 인증 코드가 필요합니다.")
    class TwoFactorInvalid : RemoteException("2단계 인증 코드가 올바르지 않습니다.")
    class Synology(val code: Int, val detail: String? = null) :
        RemoteException(SynologyClient.message(code) + (detail?.let { " · $it" } ?: ""))
    class Unsupported(what: String) : RemoteException("지원하지 않는 기능입니다: $what")

    /** Worth retrying right away: dropped connections, server hiccups, a busy NAS. */
    val isTransient: Boolean
        get() = this is Network || (this is Server && status >= 500) || (this is Synology && code == 402)

    /** Errors that make a sync pointless to continue (every other request would fail too). */
    val stopsSync: Boolean
        get() = this is Unauthorized || this is UntrustedCertificate || this is CertificateChanged ||
            this is TwoFactorRequired || this is TwoFactorInvalid || this is InsufficientStorage
}

/**
 * Uniform access to a NAS. Implemented by `WebDavClient`, `SynologyClient` and
 * `DocumentTreeFileSystem` (SMB / any storage-provider folder).
 */
interface RemoteFileSystem {
    /** Immediate children of a folder. */
    suspend fun list(path: String): List<RemoteItem>

    /** Everything below a folder (default: breadth-first `list`). */
    suspend fun listRecursive(path: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        val queue = ArrayDeque(listOf(RemotePath.normalize(path)))
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            for (item in list(queue.removeFirst())) {
                if (FileNaming.isIgnored(item.name)) continue
                out.add(item)
                if (item.isDirectory) queue.add(item.path)
            }
        }
        return out
    }

    /** null when nothing exists at `path`. */
    suspend fun stat(path: String): RemoteItem?
    suspend fun download(path: String, to: File)

    /** Uploads a file, replacing an existing one. Returns the new item when cheaply known. */
    suspend fun upload(from: File, path: String, modified: Instant?): RemoteItem?

    /** Creates a folder and any missing parents; succeeds if it already exists. */
    suspend fun createDirectory(path: String)

    /** Deletes a file or a whole folder; succeeds if it is already gone. */
    suspend fun delete(path: String)
    suspend fun move(from: String, to: String)
    suspend fun testConnection(probeWrite: Boolean, folder: String): ServerInfo

    /** Writes and deletes a small probe folder to check write permission. */
    suspend fun probeWritable(folder: String): Boolean {
        val probe = RemotePath.join(folder, ".note-write-test-${newId().take(8)}")
        return try {
            createDirectory(probe)
            delete(probe)
            true
        } catch (e: IOException) {
            false
        }
    }
}
