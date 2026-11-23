package com.lemon7679.note.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.URI
import java.time.Instant

@Serializable
enum class NasKind(val displayName: String) {
    @SerialName("synology") SYNOLOGY("Synology DSM"),
    @SerialName("webdav") WEBDAV("WebDAV"),
    /** A folder picked with the system file picker (SMB provider apps, USB, local storage). */
    @SerialName("filesFolder") FILES_FOLDER("SMB · 폴더"),
}

@Serializable
data class SyncOptions(
    /** Sync on launch, on return to foreground, after edits and every few minutes. */
    val autoSync: Boolean = true,
    /** Skip automatic syncs on mobile data. */
    val wifiOnly: Boolean = false,
    /** PDFs and other files (not notebooks) are listed but downloaded only when opened. */
    val downloadOnDemand: Boolean = true,
    /** Resolve conflicts without asking by keeping both versions. */
    val keepBothOnConflict: Boolean = false,
    /** Also write "<notebook>.pdf" (ink flattened) next to each notebook. */
    val exportPdfCopies: Boolean = false,
)

/** A connected NAS. Secrets (password, Synology device token) live in the credential store. */
@Serializable
data class NasAccount(
    val id: String = newId(),
    val name: String = "",
    val kind: NasKind = NasKind.SYNOLOGY,
    val host: String = "",
    /** null = protocol default. */
    val port: Int? = null,
    val useHttps: Boolean = true,
    /** WebDAV endpoint path on the server ("/" or "/remote.php/dav/files/me"). */
    val endpointPath: String = "/",
    val username: String = "",
    /** Remote folder mirrored into the app ("/home/Notes"). */
    val syncFolder: String = "/",
    val allowSelfSigned: Boolean = true,
    val pinnedCertificateSha256: String? = null,
    /** Storage-provider folder (content:// tree URI with a persisted permission). */
    val treeUri: String? = null,
    val folderDisplayName: String? = null,
    val options: SyncOptions = SyncOptions(),
    @Serializable(with = Iso8601Serializer::class) val created: Instant = Instant.now(),
    @Serializable(with = Iso8601Serializer::class) val lastSync: Instant? = null,
) {
    val defaultPort: Int
        get() = when (kind) {
            NasKind.SYNOLOGY -> if (useHttps) 5001 else 5000
            NasKind.WEBDAV -> if (useHttps) 5006 else 5005
            NasKind.FILES_FOLDER -> 0
        }

    val effectivePort: Int get() = port ?: defaultPort

    /** Server URL (DSM web root, or the WebDAV endpoint). */
    val baseUrl: String?
        get() {
            var h = host.trim()
            for (prefix in listOf("https://", "http://")) if (h.lowercase().startsWith(prefix)) h = h.drop(prefix.length)
            h = h.trimEnd('/')
            if (h.isEmpty()) return null
            if (h.count { it == ':' } > 1 && !h.startsWith("[")) h = "[$h]"  // IPv6 literal
            val path = if (kind == NasKind.WEBDAV) {
                val p = RemotePath.normalize(endpointPath)
                if (p == "/") "" else p.split('/').joinToString("/") { WebDavClient.encodeSegment(it) }
            } else ""
            val url = "${if (useHttps) "https" else "http"}://$h:$effectivePort$path"
            return runCatching { URI(url); url }.getOrNull()
        }

    val displayAddress: String
        get() = when (kind) {
            NasKind.FILES_FOLDER -> folderDisplayName ?: "폴더"
            else -> "$host:$effectivePort · ${if (useHttps) "HTTPS" else "HTTP"}"
        }

    val passwordKey: String get() = "password.$id"
    val deviceTokenKey: String get() = "synology-device.$id"
}

interface CredentialStore {
    fun secret(key: String): String?
    fun setSecret(value: String?, key: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val values = HashMap<String, String>()
    @Synchronized override fun secret(key: String) = values[key]
    @Synchronized override fun setSecret(value: String?, key: String) {
        if (value.isNullOrEmpty()) values.remove(key) else values[key] = value
    }
}

/** accounts.json (no secrets). */
class AccountStore(private val file: File) {
    private val serializer = ListSerializer(NasAccount.serializer())

    fun load(): List<NasAccount> = runCatching { NoteJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    fun save(accounts: List<NasAccount>) = writeAtomically(file, NoteJson.encodeToString(serializer, accounts).toByteArray())
}

object NasConnection {
    class ConnectionException(message: String) : java.io.IOException(message)

    /**
     * Builds the client for an account. `onPin` receives a newly trusted certificate
     * fingerprint, `onDeviceToken` a Synology 2-step device token — both to be saved.
     * `folderFileSystem` opens a storage-provider folder (Android-specific).
     */
    fun makeFileSystem(
        account: NasAccount,
        credentials: CredentialStore,
        otpCode: String? = null,
        onPin: ((String) -> Unit)? = null,
        onDeviceToken: ((String) -> Unit)? = null,
        folderFileSystem: ((String) -> RemoteFileSystem)? = null,
    ): RemoteFileSystem {
        val tls = TlsPolicy(account.allowSelfSigned, account.pinnedCertificateSha256)
        val password = credentials.secret(account.passwordKey) ?: ""
        return when (account.kind) {
            NasKind.SYNOLOGY -> {
                val url = account.baseUrl ?: throw ConnectionException("서버 주소를 확인해 주세요.")
                SynologyClient(url, account.username, password, credentials.secret(account.deviceTokenKey), tls).also {
                    it.otpCode = otpCode
                    it.transport.onCertificatePinned = onPin
                    it.onDeviceToken = onDeviceToken
                }
            }
            NasKind.WEBDAV -> {
                val url = account.baseUrl ?: throw ConnectionException("서버 주소를 확인해 주세요.")
                WebDavClient(url, account.username, password, tls).also { it.transport.onCertificatePinned = onPin }
            }
            NasKind.FILES_FOLDER -> {
                val uri = account.treeUri ?: throw ConnectionException("폴더를 다시 선택해 주세요.")
                folderFileSystem?.invoke(uri) ?: throw ConnectionException("폴더를 다시 선택해 주세요.")
            }
        }
    }
}
