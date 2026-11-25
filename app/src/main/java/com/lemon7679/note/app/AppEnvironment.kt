package com.lemon7679.note.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lemon7679.note.core.CredentialStore
import com.lemon7679.note.core.LibrarySort
import com.lemon7679.note.core.NoteJson
import com.lemon7679.note.core.PageSize
import com.lemon7679.note.core.PageSizePreset
import com.lemon7679.note.core.PaperColor
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.PaperTemplate
import com.lemon7679.note.core.SyncProgress
import com.lemon7679.note.core.SyncReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where everything lives on the device. */
class AppPaths(context: Context) {
    private val files = context.filesDir
    private val cache = context.cacheDir

    /** "이 기기" library. */
    val localLibrary: File = File(files, "Library").apply { mkdirs() }
    val support: File = File(files, "Note").apply { mkdirs() }

    /** Local mirror of a NAS account's notes folder. */
    fun mirror(id: String) = File(support, "Mirrors/$id").apply { mkdirs() }
    fun syncState(id: String) = File(support, "Sync/$id.json")
    fun trash(source: LibrarySource) = File(support, "Trash/${source.id}")
    val accountsFile = File(support, "accounts.json")
    val remoteDownloads get() = File(cache, "RemoteFiles").apply { mkdirs() }
    val exports get() = File(cache, "Exports").apply { mkdirs() }
    val imports get() = File(cache, "Imports").apply { mkdirs() }
}

/** A library root: this device, or the mirror of a NAS account. */
@Serializable
data class LibrarySource(val accountId: String? = null) {
    val id: String get() = accountId ?: "local"
    val isLocal: Boolean get() = accountId == null

    companion object {
        val LOCAL = LibrarySource(null)
    }
}

/** An item somewhere in the library. */
@Serializable
data class LibraryRef(val source: LibrarySource, val path: String) {
    val id: String get() = source.id + ":" + path
}

data class LibraryLocation(val source: LibrarySource, val folder: String = "/")

@Serializable
data class RecentEntry(val ref: LibraryRef, val opened: Long)

/** What the full-screen document view shows. */
data class OpenDocument(val ref: LibraryRef, val file: File, val kind: Kind, val isRemoteCopy: Boolean = false) {
    enum class Kind { NOTEBOOK, PDF }

    val id: String get() = ref.id + if (isRemoteCopy) "#remote" else ""
}

/** What a click of the stylus side button (S Pen button) does while the pen hovers. */
@Serializable
enum class StylusButtonAction(val label: String) {
    /** Opens the tool wheel (tools, colors, width, pen settings) at the pen tip. */
    @SerialName("wheel") WHEEL("휠 메뉴"),
    /** Switches between the eraser and the last pen. */
    @SerialName("eraser") ERASER("지우개 전환"),
    @SerialName("none") NONE("사용 안 함"),
}

/** How a notebook's pages are laid out in the editor. */
@Serializable
enum class PageLayout(val label: String) {
    /** One continuous vertical scroll. */
    @SerialName("vertical") VERTICAL("세로 스크롤"),
    /** One page per screen; swipe sideways to turn pages. */
    @SerialName("horizontal") HORIZONTAL("옆으로 넘기기"),
}

/** App-wide preferences. */
@Serializable
data class AppSettings(
    /** Only the stylus draws; fingers scroll and zoom. */
    val stylusOnly: Boolean = true,
    /** Two-finger tap = undo, three-finger tap = redo. */
    val tapGesturesUndo: Boolean = true,
    /** 낙서로 지우기: scribbling back and forth over ink with the pen erases it. */
    val scribbleToErase: Boolean = true,
    /** Stylus button click while hovering (holding it while drawing always erases). */
    val stylusButtonAction: StylusButtonAction = StylusButtonAction.WHEEL,
    /** Continuous vertical scroll or sideways page turning. */
    val pageLayout: PageLayout = PageLayout.VERTICAL,
    /** Render ink with the GPU (OpenGL ES 3): page tiles on worker threads and the stroke being
     *  drawn on a low-latency front buffer. Falls back to the CPU where unsupported. */
    val gpuRendering: Boolean = true,
    val defaultTemplate: PaperTemplate = PaperTemplate.RULED,
    val defaultPaperColor: PaperColor = PaperColor.WHITE,
    val defaultSize: PageSizePreset = PageSizePreset.A4,
    val defaultLandscape: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.MODIFIED,
) {
    val defaultPageSize: PageSize get() = if (defaultLandscape) defaultSize.size.landscape else defaultSize.size.portrait
    val defaultPaper: PaperStyle get() = PaperStyle(defaultTemplate, defaultPaperColor)
}

/** Small JSON values in SharedPreferences. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("note", Context.MODE_PRIVATE)

    fun <T> load(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { NoteJson.decodeFromString(serializer, it) }.getOrNull() }

    fun <T> save(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        prefs.edit().putString(key, NoteJson.encodeToString(serializer, value)).apply()
    }
}

/**
 * Passwords encrypted with an AES-GCM key that never leaves the Android Keystore
 * (the counterpart of the iPad keychain).
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("note.secrets", Context.MODE_PRIVATE)
    private val alias = "note.nas.credentials"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    override fun secret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    override fun setSecret(value: String?, key: String) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }
    }
}

/** Reachability for Wi-Fi-only sync and offline badges. */
class NetworkMonitor(context: Context) {
    var isConnected by mutableStateOf(true)
        private set
    var isOnWifi by mutableStateOf(true)
        private set

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun update(caps: NetworkCapabilities?) {
            isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            isOnWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        update(cm.getNetworkCapabilities(cm.activeNetwork))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                android.os.Handler(android.os.Looper.getMainLooper()).post { update(caps) }.let { }

            override fun onLost(network: Network) =
                android.os.Handler(android.os.Looper.getMainLooper()).post { update(null) }.let { }
        })
    }
}

data class AccountSyncStatus(
    val phase: Phase = Phase.Idle,
    val progress: SyncProgress? = null,
    val lastSync: Instant? = null,
    val lastReport: SyncReport? = null,
) {
    sealed class Phase {
        data object Idle : Phase()
        data object Syncing : Phase()
        data class Error(val message: String) : Phase()
        data object Offline : Phase()
        data object WaitingForWifi : Phase()
    }

    val isSyncing: Boolean get() = phase == Phase.Syncing

    val summary: String
        get() = when (val p = phase) {
            Phase.Idle -> lastSync?.let { "동기화됨 · ${relativeLabel(it)}" } ?: "아직 동기화하지 않음"
            Phase.Syncing -> progress?.takeIf { it.total > 0 }?.let { "동기화 중 · ${it.completed} / ${it.total}" }
                ?: "변경 사항 확인 중…"
            is Phase.Error -> p.message
            Phase.Offline -> "오프라인 · 연결되면 동기화"
            Phase.WaitingForWifi -> "Wi-Fi 연결 대기 중"
        }
}

/** "방금", "3분 전", "2시간 전", "어제", "11월 21일". */
fun relativeLabel(time: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(time, now)
    return when {
        d.isNegative || d.seconds < 45 -> "방금"
        d.toMinutes() < 60 -> "${d.toMinutes()}분 전"
        d.toHours() < 24 -> "${d.toHours()}시간 전"
        d.toDays() < 7 -> "${d.toDays()}일 전"
        else -> libraryDateLabel(time)
    }
}

/** "오늘", "어제", "11월 21일", "2025년 3월 2일". */
fun libraryDateLabel(time: Instant): String {
    val zone = java.time.ZoneId.systemDefault()
    val date = time.atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when {
        date == today -> "오늘"
        date == today.minusDays(1) -> "어제"
        date.year == today.year -> "${date.monthValue}월 ${date.dayOfMonth}일"
        else -> "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
    }
}

fun byteLabel(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000_000 -> "%.0f KB".format(bytes / 1000.0)
    bytes < 1000_000_000 -> "%.1f MB".format(bytes / 1000_000.0)
    else -> "%.1f GB".format(bytes / 1000_000_000.0)
}

val deviceName: String get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
