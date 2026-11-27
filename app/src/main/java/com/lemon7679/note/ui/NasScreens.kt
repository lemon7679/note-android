package com.lemon7679.note.ui

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.app.present
import com.lemon7679.note.app.byteLabel
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.NasAccount
import com.lemon7679.note.core.NasKind
import com.lemon7679.note.core.RemoteException
import com.lemon7679.note.core.RemoteItem
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.core.SynologyClient
import kotlinx.coroutines.launch
import java.io.File

/** Finds NAS boxes on the local network (mDNS / DNS-SD: SMB, AFP, WebDAV, Synology). */
class NasDiscovery(context: Context) {
    data class Device(val id: String, val name: String, val service: String, val host: String?)

    val devices = mutableStateListOf<Device>()
    var searching by mutableStateOf(false)
        private set
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (listeners.isNotEmpty()) return
        searching = true
        lock = runCatching { wifi.createMulticastLock("note-nas").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        for (type in listOf("_smb._tcp", "_afpovertcp._tcp", "_webdav._tcp", "_webdavs._tcp", "_http._tcp")) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    val name = info.serviceName ?: return
                    // _http._tcp is everywhere; keep only what looks like a NAS.
                    if (type == "_http._tcp" && !Regex("(?i)synology|diskstation|ds\\d|qnap|nas|truenas").containsMatchIn(name)) return
                    val id = name.lowercase()
                    main.post {
                        if (devices.none { it.id == id }) {
                            devices += Device(id, name, type, null)
                            resolve(info, id)
                        }
                    }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { listeners += listener }
        }
        main.postDelayed({ searching = false }, 6000)
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, id: String) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                    else serviceInfo.host?.hostAddress
                    main.post {
                        val i = devices.indexOfFirst { it.id == id }
                        if (i >= 0) devices[i] = devices[i].copy(host = host?.substringBefore('%'))
                    }
                }
            })
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        runCatching { lock?.release() }
        searching = false
    }
}

/** Add or edit a NAS connection (design: NasConnect artboard). */
@Composable
fun NasConnectScreen(app: AppModel, account: NasAccount, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = account.host.isEmpty() && account.treeUri == null
    var draft by remember { mutableStateOf(account) }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var needsOtp by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pickingFolder by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(account.port?.toString() ?: "") }
    val discovery = remember { NasDiscovery(context) }
    DisposableEffect(draft.kind) {
        if (draft.kind != NasKind.FILES_FOLDER) discovery.start()
        onDispose { discovery.stop() }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "폴더"
        draft = draft.copy(treeUri = uri.toString(), folderDisplayName = name, name = draft.name.ifBlank { name })
        result = null
    }

    val canSave = if (draft.kind == NasKind.FILES_FOLDER) draft.treeUri != null else draft.host.isNotBlank()

    fun current(): NasAccount = draft.copy(
        port = portText.toIntOrNull(),
        name = draft.name.ifBlank { draft.host.ifBlank { draft.folderDisplayName ?: "NAS" } },
    )

    fun save() {
        val a = current()
        app.saveAccount(a, password.ifEmpty { if (isNew) "" else null })
        onDismiss()
    }

    fun test() {
        testing = true
        result = null
        scope.launch {
            try {
                val a = current()
                val fs = app.fileSystem(a, password.ifEmpty { app.credentials.secret(a.passwordKey) ?: "" },
                    if (needsOtp) otp else null) ?: throw RemoteException.InvalidResponse()
                val folder = if (a.kind == NasKind.FILES_FOLDER) "/" else a.syncFolder
                val info = try {
                    fs.testConnection(true, folder)
                } catch (e: RemoteException.NotFound) {
                    // The notes folder may not exist yet: check the parent and create it on first sync.
                    fs.testConnection(false, RemotePath.parent(folder)).let { it.copy(detail = it.detail + " · 첫 동기화 때 폴더를 만듭니다") }
                }
                result = true to "연결됨 · ${info.summary}"
            } catch (e: RemoteException.TwoFactorRequired) {
                needsOtp = true
                result = false to "2단계 인증 코드를 입력한 뒤 다시 테스트하세요."
            } catch (e: Exception) {
                result = false to (e.message ?: "연결하지 못했습니다.")
            } finally {
                testing = false
            }
        }
    }

    NoteSheet(if (isNew) "저장소 연결" else draft.name, onDismiss, maxWidth = 680.dp, confirm = "저장", confirmEnabled = canSave, onConfirm = ::save) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NasKind.entries.forEach { kind ->
                    val selected = draft.kind == kind
                    Column(Modifier.weight(1f).heightIn(min = 84.dp).clip(RoundedCornerShape(NoteTheme.Radius.card)).background(c.surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.separator, RoundedCornerShape(NoteTheme.Radius.card))
                        .clickable {
                            if (draft.kind != kind) {
                                result = null
                                draft = when (kind) {
                                    NasKind.SYNOLOGY -> draft.copy(kind = kind, syncFolder = if (draft.syncFolder in listOf("/", "/Notes")) "/home/Notes" else draft.syncFolder)
                                    NasKind.WEBDAV -> draft.copy(kind = kind, syncFolder = if (draft.syncFolder in listOf("/", "/home/Notes")) "/Notes" else draft.syncFolder)
                                    NasKind.FILES_FOLDER -> draft.copy(kind = kind, syncFolder = "/")
                                }
                            }
                        }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(kind.icon, null, tint = if (selected) c.accent else c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(kind.displayName, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = c.textPrimary, maxLines = 1)
                    }
                }
            }

            if (draft.kind == NasKind.FILES_FOLDER) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("폴더")
                    CardGroup {
                        Row(Modifier.fillMaxWidth().clickable { pickTree.launch(null) }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = c.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(draft.folderDisplayName ?: "폴더 선택…", color = c.textPrimary, modifier = Modifier.weight(1f))
                            Text("변경", color = c.accent)
                        }
                    }
                    Text("SMB 공유 폴더는 SMB를 지원하는 파일 관리자/저장소 앱(Documents 제공자)을 통해 선택할 수 있습니다. USB 저장소나 기기 폴더도 됩니다.",
                        style = NoteTheme.Fonts.caption, color = c.textSecondary)
                }
                Field("이름", draft.name, "예: 사무실 공유 폴더") { draft = draft.copy(name = it) }
            } else {
                if (discovery.devices.isNotEmpty() || discovery.searching) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("같은 네트워크에서 찾은 NAS")
                            if (discovery.searching) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.accent)
                        }
                        CardGroup {
                            discovery.devices.forEachIndexed { i, d ->
                                if (i > 0) CardDivider()
                                Row(Modifier.fillMaxWidth().clickable {
                                    draft = draft.copy(host = d.host ?: "${d.name}.local", name = draft.name.ifBlank { d.name })
                                }.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Router, null, tint = c.accent)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, color = c.textPrimary)
                                        Text(d.host ?: "주소 확인 중…", style = NoteTheme.Fonts.caption, color = c.textSecondary)
                                    }
                                }
                            }
                            if (discovery.devices.isEmpty()) Text("찾는 중…", color = c.textSecondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("서버")
                    Field("이름", draft.name, "예: Home NAS") { draft = draft.copy(name = it) }
                    Field("주소", draft.host, "192.168.0.10 또는 nas.example.com", KeyboardType.Uri) { draft = draft.copy(host = it.trim()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Field("포트", portText, draft.defaultPort.toString(), KeyboardType.Number) { portText = it.filter(Char::isDigit) }
                        }
                        Box(Modifier.weight(1f)) {
                            SegmentedPicker(listOf("HTTPS", "HTTP"), if (draft.useHttps) 0 else 1) { draft = draft.copy(useHttps = it == 0) }
                        }
                    }
                    if (draft.kind == NasKind.WEBDAV) {
                        Field("WebDAV 경로", draft.endpointPath, "/ (Nextcloud: /remote.php/dav/files/<사용자>)") { draft = draft.copy(endpointPath = it) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("계정")
                    Field("사용자 이름", draft.username, "") { draft = draft.copy(username = it.trim()) }
                    Field("암호", password, if (isNew) "" else "변경하지 않으려면 비워 두세요", KeyboardType.Password, secret = true) { password = it }
                    if (needsOtp || draft.kind == NasKind.SYNOLOGY && otp.isNotEmpty()) {
                        Field("2단계 인증 코드", otp, "OTP 6자리", KeyboardType.Number) { otp = it.filter(Char::isDigit) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("노트 폴더")
                    CardGroup {
                        Row(Modifier.fillMaxWidth().clickable(enabled = draft.host.isNotBlank()) { pickingFolder = true }
                            .heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = c.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(draft.syncFolder, color = c.textPrimary, modifier = Modifier.weight(1f))
                            Text("찾아보기", color = if (draft.host.isNotBlank()) c.accent else c.offline)
                        }
                    }
                    Field("직접 입력", draft.syncFolder, "/home/Notes") { draft = draft.copy(syncFolder = RemotePath.normalize(it)) }
                }
            }

            CardGroup {
                if (draft.kind != NasKind.FILES_FOLDER) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        OptionToggle("자체 서명 인증서 신뢰", draft.allowSelfSigned, "처음 본 인증서를 기억하고, 바뀌면 연결하지 않습니다.") {
                            draft = draft.copy(allowSelfSigned = it)
                        }
                    }
                    CardDivider()
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("Wi-Fi에서만 동기화", draft.options.wifiOnly) { draft = draft.copy(options = draft.options.copy(wifiOnly = it)) }
                }
                CardDivider()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("PDF는 열 때 내려받기", draft.options.downloadOnDemand) {
                        draft = draft.copy(options = draft.options.copy(downloadOnDemand = it))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(if (testing) "확인 중…" else "연결 테스트", enabled = canSave && !testing, onClick = ::test)
                Spacer(Modifier.width(12.dp))
                result?.let { (ok, message) ->
                    Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (ok) c.synced else c.danger)
                    Spacer(Modifier.width(6.dp))
                    Text(message, fontSize = 13.sp, color = if (ok) c.synced else c.danger)
                }
            }
        }
    }

    if (pickingFolder) {
        RemoteFolderPicker(app, current(), password.ifEmpty { null }, if (needsOtp) otp else null, onPick = {
            draft = draft.copy(syncFolder = it)
            pickingFolder = false
        }) { pickingFolder = false }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false,
                  onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
}

/** Browse NAS folders to choose the sync folder. */
@Composable
fun RemoteFolderPicker(app: AppModel, account: NasAccount, password: String?, otp: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val fs = remember { app.fileSystem(account, password ?: app.credentials.secret(account.passwordKey) ?: "", otp) }
    var path by remember { mutableStateOf(if (account.kind == NasKind.SYNOLOGY) "/" else "/") }
    var items by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        loading = true
        error = null
        items = try {
            when {
                fs == null -> throw RemoteException.InvalidResponse()
                fs is SynologyClient && path == "/" -> fs.listShares()
                else -> fs.list(path).filter { it.isDirectory && RemotePath.pathExtension(it.path) != FileNaming.NOTEBOOK_EXTENSION }
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
        loading = false
    }
    NoteSheet("노트 폴더 선택", onDismiss, maxWidth = 520.dp, confirm = "선택", confirmEnabled = path != "/" || account.kind == NasKind.WEBDAV,
        onConfirm = { onPick(path) }) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path != "/") NoteIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "상위 폴더") { path = RemotePath.parent(path) }
            Text(path, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        Hairline()
        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 520.dp)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
                error != null -> Text(error ?: "", color = c.danger, modifier = Modifier.padding(20.dp))
                items.isEmpty() -> Text("하위 폴더가 없습니다.", color = c.textSecondary, modifier = Modifier.padding(20.dp))
                else -> LazyColumn {
                    items(items) { item ->
                        Row(Modifier.fillMaxWidth().clickable { path = item.path }.height(48.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, tint = c.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(item.name, color = c.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** NAS 전체 보기: browse the whole NAS (outside the sync folder), open PDFs or copy them into the library. */
@Composable
fun RemoteBrowserScreen(app: AppModel, account: NasAccount, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val scope = rememberCoroutineScope()
    val fs = remember { app.fileSystem(account) }
    var path by remember { mutableStateOf(if (account.kind == NasKind.SYNOLOGY) "/" else "/") }
    var items by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        loading = true
        error = null
        items = try {
            when {
                fs == null -> throw RemoteException.InvalidResponse()
                fs is SynologyClient && path == "/" -> fs.listShares()
                else -> fs.list(path)
            }.sortedWith(compareBy<RemoteItem>({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
        loading = false
    }

    suspend fun download(item: RemoteItem): File? {
        val f = File(app.paths.remoteDownloads, "${account.id}/${item.path.trimStart('/')}")
        busy = item.name
        return try {
            f.parentFile?.mkdirs()
            fs?.download(item.path, f)
            f
        } catch (e: Exception) {
            app.errorMessage = e.message
            null
        } finally {
            busy = null
        }
    }

    NoteSheet("${account.name} · NAS 전체 보기", onDismiss, maxWidth = 640.dp, dismissLabel = "닫기") {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path != "/") NoteIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "상위 폴더") { path = RemotePath.parent(path) }
            Text(path, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            busy?.let {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                Text(" $it", fontSize = 12.sp, color = c.textSecondary, maxLines = 1)
            }
        }
        Hairline()
        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 640.dp)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
                error != null -> Text(error ?: "", color = c.danger, modifier = Modifier.padding(20.dp))
                items.isEmpty() -> Text("비어 있습니다.", color = c.textSecondary, modifier = Modifier.padding(20.dp))
                else -> LazyColumn {
                    items(items) { item ->
                        val kind = LibraryItemKind.classify(item.name, item.isDirectory)
                        Row(Modifier.fillMaxWidth().clickable {
                            if (item.isDirectory) path = item.path
                            else if (kind == LibraryItemKind.PDF) scope.launch {
                                download(item)?.let { f ->
                                    app.present(OpenDocument(LibraryRef(LibrarySource(account.id), item.path), f, OpenDocument.Kind.PDF, isRemoteCopy = true))
                                }
                            }
                        }.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (kind) {
                                LibraryItemKind.FOLDER -> Icons.Outlined.Folder
                                LibraryItemKind.PDF -> Icons.Outlined.PictureAsPdf
                                else -> Icons.Outlined.InsertDriveFile
                            }, null, tint = if (item.isDirectory) c.accent else c.textSecondary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!item.isDirectory) Text(byteLabel(item.size), style = NoteTheme.Fonts.caption, color = c.textSecondary)
                            }
                            if (!item.isDirectory) {
                                TextButton({
                                    scope.launch {
                                        download(item)?.let { f ->
                                            app.perform(LibrarySource(account.id)) { it.importFile(f, "/") }
                                            app.toast = null
                                        }
                                    }
                                }) {
                                    Icon(Icons.Outlined.FileDownload, null, Modifier.size(16.dp))
                                    Text(" 노트 폴더로", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
