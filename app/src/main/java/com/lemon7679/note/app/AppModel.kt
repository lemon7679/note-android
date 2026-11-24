package com.lemon7679.note.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lemon7679.note.core.AccountStore
import com.lemon7679.note.core.ConflictResolution
import com.lemon7679.note.core.CoverStyle
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.LibraryEntry
import com.lemon7679.note.core.LibraryFileSystem
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.NasAccount
import com.lemon7679.note.core.NasConnection
import com.lemon7679.note.core.NasKind
import com.lemon7679.note.core.NotebookDocument
import com.lemon7679.note.core.NotebookPackage
import com.lemon7679.note.core.PageSize
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.RemoteException
import com.lemon7679.note.core.RemoteFileSystem
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.core.SyncConflict
import com.lemon7679.note.core.SyncEngine
import com.lemon7679.note.core.SyncOptions
import com.lemon7679.note.core.SyncProgress
import com.lemon7679.note.core.TrashBin
import com.lemon7679.note.render.PdfSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant

/** Library badge on document cards. */
enum class SyncBadgeState(val label: String) {
    SYNCED("동기화됨"), SYNCING("동기화 중"), CONFLICT("충돌"), LOCAL_ONLY("이 기기에만 있음"),
    CLOUD_ONLY("NAS에만 있음 · 열면 다운로드"), ERROR("동기화 오류"),
}

/**
 * App-wide state: library sources, NAS accounts and their sync, favorites,
 * recents and the document on screen. Main-thread only (Compose snapshot state).
 */
class AppModel(val context: Context) {
    val paths = AppPaths(context)
    val scope = MainScope()
    private val prefs = Prefs(context)
    val credentials = KeystoreCredentialStore(context)
    val network = NetworkMonitor(context)
    private val accountStore = AccountStore(paths.accountsFile)

    var accounts by mutableStateOf(accountStore.load())
        private set
    val syncStatus = mutableStateMapOf<String, AccountSyncStatus>()
    val conflicts = mutableStateMapOf<String, List<SyncConflict>>()
    var favorites by mutableStateOf(prefs.load("favorites", ListSerializer(LibraryRef.serializer())) ?: emptyList())
        private set
    var recents by mutableStateOf(prefs.load("recents", ListSerializer(RecentEntry.serializer())) ?: emptyList())
        private set
    var openDocument by mutableStateOf<OpenDocument?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    /** Bumped whenever library contents may have changed (views reload). */
    var libraryRevision by mutableIntStateOf(0)
        private set
    var settings by mutableStateOf(prefs.load("settings", AppSettings.serializer()) ?: AppSettings())
        private set

    /** Strokes copied with the lasso (engine clipboard format). */
    var strokeClipboard by mutableStateOf<ByteArray?>(null)

    private val engines = HashMap<String, SyncEngine>()
    private val syncJobs = HashMap<String, Job>()
    private val scheduled = HashMap<String, Job>()
    private var periodic: Job? = null
    private val openNotebooks = HashMap<LibrarySource, MutableSet<String>>()

    init {
        for (a in accounts) syncStatus[a.id] = AccountSyncStatus(lastSync = a.lastSync)
    }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        settings = change(settings)
        prefs.save("settings", AppSettings.serializer(), settings)
    }

    // Sources

    fun library(source: LibrarySource): LibraryFileSystem =
        LibraryFileSystem(source.accountId?.let { paths.mirror(it) } ?: paths.localLibrary)

    fun trash(source: LibrarySource) = TrashBin(paths.trash(source))
    fun account(id: String?): NasAccount? = id?.let { i -> accounts.firstOrNull { it.id == i } }

    fun displayName(source: LibrarySource): String =
        source.accountId?.let { account(it)?.name ?: "NAS" } ?: "이 기기"

    fun file(ref: LibraryRef): File = library(ref.source).file(ref.path)
    fun refreshLibrary() {
        libraryRevision += 1
    }

    // Listing

    suspend fun items(location: LibraryLocation): List<LibraryEntry> {
        val items = withContext(Dispatchers.IO) {
            runCatching { library(location.source).list(location.folder) }.getOrDefault(emptyList())
        }.toMutableList()
        location.source.accountId?.let { id ->
            engine(id)?.let { engine ->
                val known = items.map { it.path }.toSet()
                items += engine.placeholders(location.folder).filter { it.path !in known }
            }
        }
        return settings.librarySort.sort(items)
    }

    suspend fun search(query: String, source: LibrarySource): List<LibraryEntry> {
        val results = withContext(Dispatchers.IO) { library(source).search(query) }.toMutableList()
        source.accountId?.let { id -> engine(id)?.let { results += it.searchPlaceholders(query) } }
        return settings.librarySort.sort(results)
    }

    fun badge(item: LibraryEntry, source: LibrarySource): SyncBadgeState {
        val id = source.accountId ?: return SyncBadgeState.LOCAL_ONLY
        if (item.isPlaceholder) return SyncBadgeState.CLOUD_ONLY
        if (conflicts[id].orEmpty().any { RemotePath.isAncestor(it.path, item.path) }) return SyncBadgeState.CONFLICT
        val status = status(id)
        if (status.phase is AccountSyncStatus.Phase.Error) return SyncBadgeState.ERROR
        val last = status.lastSync
        val modified = item.modified
        if (last != null && modified != null && !modified.isAfter(last)) return SyncBadgeState.SYNCED
        return if (last == null || status.isSyncing) SyncBadgeState.SYNCING else SyncBadgeState.SYNCED
    }

    // Favorites & recents

    fun isFavorite(ref: LibraryRef) = favorites.contains(ref)

    fun toggleFavorite(ref: LibraryRef) {
        favorites = if (favorites.contains(ref)) favorites - ref else favorites + ref
        prefs.save("favorites", ListSerializer(LibraryRef.serializer()), favorites)
    }

    private fun noteRecent(ref: LibraryRef) {
        recents = (listOf(RecentEntry(ref, System.currentTimeMillis())) + recents.filter { it.ref != ref }).take(30)
        prefs.save("recents", ListSerializer(RecentEntry.serializer()), recents)
    }

    /** Recents / favorites that still exist. */
    fun existingItems(refs: List<LibraryRef>): List<Pair<LibraryRef, LibraryEntry>> = refs.mapNotNull { ref ->
        val f = library(ref.source).file(ref.path)
        if (!f.exists()) return@mapNotNull null
        val kind = LibraryItemKind.classify(f.name, f.isDirectory)
        var modified = f.lastModified()
        if (kind == LibraryItemKind.NOTEBOOK) File(f, NotebookPackage.DOCUMENT_FILE).takeIf { it.exists() }?.let { modified = it.lastModified() }
        ref to LibraryEntry(ref.path, kind, Instant.ofEpochMilli(modified), 0)
    }

    // Opening & closing documents

    fun open(item: LibraryEntry, source: LibrarySource) {
        scope.launch {
            val ref = LibraryRef(source, item.path)
            var file = library(source).file(item.path)
            if (item.isPlaceholder) {
                val engine = source.accountId?.let { engine(it) }
                if (engine != null) {
                    toast = "NAS에서 받는 중…"
                    try {
                        file = engine.materialize(item.path)
                        refreshLibrary()
                    } catch (e: Exception) {
                        errorMessage = "NAS에서 파일을 받지 못했습니다. ${e.message ?: ""}"
                        return@launch
                    } finally {
                        toast = null
                    }
                }
            }
            when (item.kind) {
                LibraryItemKind.NOTEBOOK -> {
                    openNotebooks.getOrPut(source) { HashSet() }.add(item.path)
                    openDocument = OpenDocument(ref, file, OpenDocument.Kind.NOTEBOOK)
                }
                LibraryItemKind.PDF -> openDocument = OpenDocument(ref, file, OpenDocument.Kind.PDF)
                else -> {
                    errorMessage = "이 형식(${RemotePath.pathExtension(item.path).uppercase()})은 열 수 없습니다. 공유 메뉴로 다른 앱에서 열어 주세요."
                    return@launch
                }
            }
            noteRecent(ref)
        }
    }

    fun openNotebook(ref: LibraryRef) {
        openNotebooks.getOrPut(ref.source) { HashSet() }.add(ref.path)
        openDocument = OpenDocument(ref, file(ref), OpenDocument.Kind.NOTEBOOK)
        noteRecent(ref)
    }

    fun documentClosed(doc: OpenDocument, exportPdf: (() -> File?)? = null) {
        openNotebooks[doc.ref.source]?.remove(doc.ref.path)
        val id = doc.ref.source.accountId
        if (doc.kind == OpenDocument.Kind.NOTEBOOK && id != null && account(id)?.options?.exportPdfCopies == true) {
            exportPdf?.invoke()?.let { exported ->
                // "<노트>.pdf" next to the notebook, synced like any file.
                val lib = library(doc.ref.source)
                val name = FileNaming.split(RemotePath.name(doc.ref.path)).first + ".pdf"
                runCatching { exported.copyTo(lib.file(RemotePath.join(RemotePath.parent(doc.ref.path), name)), true) }
            }
        }
        if (openDocument?.id == doc.id) openDocument = null
        refreshLibrary()
        localChange(doc.ref.source)
    }

    // Creating & importing

    fun createNotebook(location: LibraryLocation, title: String, coverHex: String?, paper: PaperStyle, size: PageSize,
                       open: Boolean = true): LibraryRef? = try {
        val doc = NotebookDocument.create(title.ifBlank { "새 노트" }, CoverStyle(coverHex), paper, size, 1)
        val pkg = NotebookPackage.create(library(location.source).file(location.folder), doc)
        val ref = LibraryRef(location.source, RemotePath.join(location.folder, pkg.dir.name))
        refreshLibrary()
        localChange(location.source)
        if (open) openNotebook(ref)
        ref
    } catch (e: Exception) {
        errorMessage = "노트를 만들지 못했습니다. ${e.message ?: ""}"
        null
    }

    /** Copies a picked document (content:// URI) into the cache and returns it with its display name. */
    fun copyIn(uri: Uri): File? = runCatching {
        var name = "문서"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val dir = File(paths.imports, com.lemon7679.note.core.newId()).apply { mkdirs() }
        val target = File(dir, FileNaming.sanitize(name))
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return null
        target
    }.getOrNull()

    /** Imports a PDF from outside. `annotate` makes it a notebook right away. */
    fun importPdf(uri: Uri, location: LibraryLocation, annotate: Boolean) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { copyIn(uri) }
            if (file == null) {
                errorMessage = "파일을 읽을 수 없습니다."
                return@launch
            }
            importPdfFile(file, location, annotate)
        }
    }

    fun importPdfFile(file: File, location: LibraryLocation, annotate: Boolean) {
        val lib = library(location.source)
        try {
            if (annotate && file.extension.lowercase() == "pdf") {
                val sizes = PdfSupport.pageSizes(file)
                if (sizes.isEmpty()) throw RemoteException.InvalidResponse()
                val pkg = NotebookPackage.createAnnotating(file, FileNaming.split(file.name).first, sizes,
                    lib.file(location.folder))
                openNotebook(LibraryRef(location.source, RemotePath.join(location.folder, pkg.dir.name)))
            } else {
                lib.importFile(file, location.folder)
            }
            refreshLibrary()
            localChange(location.source)
        } catch (e: Exception) {
            errorMessage = "PDF를 가져오지 못했습니다. PDF 파일이 맞는지 확인해 주세요."
        }
    }

    fun importFile(uri: Uri, location: LibraryLocation) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { copyIn(uri) } ?: run {
                errorMessage = "파일을 읽을 수 없습니다."
                return@launch
            }
            perform(location.source) { it.importFile(file, location.folder) }
        }
    }

    /**
     * "필기 시작" on a PDF in the library: reuses the notebook linked to it, or
     * creates "<PDF> 필기.inknote" beside it (the original PDF stays untouched on the NAS).
     */
    fun annotate(pdf: LibraryRef) {
        val lib = library(pdf.source)
        val folder = RemotePath.parent(pdf.path)
        for (item in runCatching { lib.list(folder) }.getOrDefault(emptyList())) {
            if (item.kind != LibraryItemKind.NOTEBOOK) continue
            val doc = runCatching { NotebookPackage(lib.file(item.path)).readDocument() }.getOrNull() ?: continue
            if (doc.pdf?.linkedPath == pdf.path) {
                openNotebook(LibraryRef(pdf.source, item.path))
                return
            }
        }
        val pdfFile = lib.file(pdf.path)
        val sizes = PdfSupport.pageSizes(pdfFile)
        if (sizes.isEmpty()) {
            errorMessage = "PDF를 읽을 수 없습니다."
            return
        }
        try {
            val title = FileNaming.split(RemotePath.name(pdf.path)).first + " 필기"
            val pkg = NotebookPackage.createAnnotating(pdfFile, title, sizes, lib.file(folder), linkedPath = pdf.path)
            openNotebook(LibraryRef(pdf.source, RemotePath.join(folder, pkg.dir.name)))
            refreshLibrary()
            localChange(pdf.source)
        } catch (e: Exception) {
            errorMessage = "필기 노트를 만들지 못했습니다. ${e.message ?: ""}"
        }
    }

    // File operations

    fun perform(source: LibrarySource, operation: (LibraryFileSystem) -> Unit) {
        try {
            operation(library(source))
            refreshLibrary()
            localChange(source)
        } catch (e: Exception) {
            errorMessage = e.message ?: "작업을 완료하지 못했습니다."
        }
    }

    fun createFolder(name: String, location: LibraryLocation) =
        perform(location.source) { it.createFolder(name, location.folder) }

    fun rename(ref: LibraryRef, name: String) = perform(ref.source) { lib ->
        replaceRef(ref, LibraryRef(ref.source, lib.rename(ref.path, name)))
    }

    fun move(ref: LibraryRef, folder: String) = perform(ref.source) { lib ->
        replaceRef(ref, LibraryRef(ref.source, lib.move(ref.path, folder)))
    }

    fun duplicate(ref: LibraryRef) = perform(ref.source) { it.duplicate(ref.path) }

    fun delete(item: LibraryEntry, source: LibrarySource) {
        val ref = LibraryRef(source, item.path)
        if (item.isPlaceholder) {
            val engine = source.accountId?.let { engine(it) } ?: return
            scope.launch {
                try {
                    engine.deletePlaceholder(item.path)
                    refreshLibrary()
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }
            return
        }
        perform(source) { it.delete(item.path, trash(source)) }
        if (favorites.contains(ref)) toggleFavorite(ref)
        recents = recents.filter { it.ref != ref }
        prefs.save("recents", ListSerializer(RecentEntry.serializer()), recents)
    }

    fun restore(entry: TrashBin.Entry, source: LibrarySource) = perform(source) { trash(source).restore(entry, it) }

    /** Frees space by removing the local copy of a downloaded NAS file. */
    fun evict(item: LibraryEntry, source: LibrarySource) {
        val engine = source.accountId?.let { engine(it) } ?: return
        scope.launch {
            runCatching { engine.evict(item.path) }
            refreshLibrary()
        }
    }

    private fun replaceRef(old: LibraryRef, new: LibraryRef) {
        if (favorites.contains(old)) favorites = favorites - old + new
        recents = recents.map { if (it.ref == old) it.copy(ref = new) else it }
        prefs.save("favorites", ListSerializer(LibraryRef.serializer()), favorites)
        prefs.save("recents", ListSerializer(RecentEntry.serializer()), recents)
    }

    // Accounts

    fun saveAccount(account: NasAccount, password: String?) {
        password?.let { credentials.setSecret(it, account.passwordKey) }
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            val old = accounts[index]
            accounts = accounts.toMutableList().also { it[index] = account }
            engines.remove(account.id)
            if (old.syncFolder != account.syncFolder || old.kind != account.kind || old.host != account.host ||
                old.endpointPath != account.endpointPath || old.treeUri != account.treeUri) {
                // A different remote folder: start a fresh mirror.
                syncJobs.remove(account.id)?.cancel()
                paths.mirror(account.id).deleteRecursively()
                paths.syncState(account.id).delete()
            }
        } else {
            accounts = accounts + account
            syncStatus[account.id] = AccountSyncStatus()
        }
        persistAccounts()
        refreshLibrary()
        sync(account.id, manual = true)
    }

    fun removeAccount(id: String) {
        val account = account(id) ?: return
        syncJobs.remove(id)?.cancel()
        engines.remove(id)
        credentials.setSecret(null, account.passwordKey)
        credentials.setSecret(null, account.deviceTokenKey)
        paths.mirror(id).deleteRecursively()
        paths.syncState(id).delete()
        paths.trash(LibrarySource(id)).deleteRecursively()
        accounts = accounts.filter { it.id != id }
        syncStatus.remove(id)
        conflicts.remove(id)
        favorites = favorites.filter { it.source.accountId != id }
        recents = recents.filter { it.ref.source.accountId != id }
        persistAccounts()
        refreshLibrary()
    }

    fun updateOptions(id: String, change: (SyncOptions) -> SyncOptions) {
        accounts = accounts.map { if (it.id == id) it.copy(options = change(it.options)) else it }
        persistAccounts()
    }

    private fun persistAccounts() {
        try {
            accountStore.save(accounts)
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    /** A client for browsing / testing (separate from the sync engine). */
    fun fileSystem(account: NasAccount, password: String? = null, otp: String? = null): RemoteFileSystem? = runCatching {
        val creds = if (password != null) {
            com.lemon7679.note.core.InMemoryCredentialStore().apply {
                setSecret(password, account.passwordKey)
                setSecret(credentials.secret(account.deviceTokenKey), account.deviceTokenKey)
            }
        } else credentials
        NasConnection.makeFileSystem(account, creds, otp, pinHandler(account.id), deviceTokenHandler(account),
            folderFileSystem = { DocumentTreeFileSystem(context, it) })
    }.getOrNull()

    private fun pinHandler(id: String): (String) -> Unit = { fingerprint ->
        scope.launch {
            if (accounts.any { it.id == id }) {
                accounts = accounts.map { if (it.id == id) it.copy(pinnedCertificateSha256 = fingerprint) else it }
                persistAccounts()
            }
        }
    }

    private fun deviceTokenHandler(account: NasAccount): (String) -> Unit = { token ->
        credentials.setSecret(token, account.deviceTokenKey)
    }

    // Sync

    fun engine(id: String): SyncEngine? {
        engines[id]?.let { return it }
        val account = account(id) ?: return null
        val fs = runCatching {
            NasConnection.makeFileSystem(account, credentials, null, pinHandler(id), deviceTokenHandler(account),
                folderFileSystem = { DocumentTreeFileSystem(context, it) })
        }.getOrNull() ?: return null
        val root = if (account.kind == NasKind.FILES_FOLDER) "/" else account.syncFolder
        val engine = SyncEngine(fs, paths.mirror(id), root, paths.syncState(id), deviceName)
        engines[id] = engine
        return engine
    }

    fun status(id: String): AccountSyncStatus = syncStatus[id] ?: AccountSyncStatus()
    val totalConflicts: Int get() = conflicts.values.sumOf { it.size }

    fun syncAll(manual: Boolean = false) = accounts.forEach { sync(it.id, manual) }

    /** Something changed locally in `source`; sync a few seconds later (coalesced). */
    fun localChange(source: LibrarySource) {
        val id = source.accountId ?: return
        if (account(id)?.options?.autoSync != true) return
        scheduled.remove(id)?.cancel()
        scheduled[id] = scope.launch {
            delay(3000)
            sync(id)
        }
    }

    fun sync(id: String, manual: Boolean = false) {
        if (syncJobs[id] != null) return
        val account = account(id) ?: return
        var status = status(id)
        if (!manual) {
            if (!account.options.autoSync) return
            if (account.options.wifiOnly && !network.isOnWifi) {
                syncStatus[id] = status.copy(phase = AccountSyncStatus.Phase.WaitingForWifi)
                return
            }
        }
        if (!network.isConnected && account.kind != NasKind.FILES_FOLDER) {
            syncStatus[id] = status.copy(phase = AccountSyncStatus.Phase.Offline)
            return
        }
        val engine = engine(id)
        if (engine == null) {
            syncStatus[id] = status.copy(phase = AccountSyncStatus.Phase.Error("연결 정보를 확인해 주세요."))
            return
        }
        status = status.copy(phase = AccountSyncStatus.Phase.Syncing, progress = null)
        syncStatus[id] = status
        val busy = openNotebooks[LibrarySource(id)]?.toSet() ?: emptySet()
        val onProgress: (SyncProgress) -> Unit = { p ->
            scope.launch { syncStatus[id]?.let { syncStatus[id] = it.copy(progress = p) } }
        }
        syncJobs[id] = scope.launch {
            try {
                val report = engine.run(account.options, busy, onProgress)
                val now = Instant.now()
                syncStatus[id] = AccountSyncStatus(
                    if (report.errors.isEmpty()) AccountSyncStatus.Phase.Idle
                    else AccountSyncStatus.Phase.Error("일부 파일을 동기화하지 못했습니다 (${report.errors.size}개)."),
                    null, now, report)
                conflicts[id] = report.conflicts
                accounts = accounts.map { if (it.id == id) it.copy(lastSync = now) else it }
                persistAccounts()
                refreshLibrary()
            } catch (e: CancellationException) {
                syncStatus[id]?.let { syncStatus[id] = it.copy(phase = AccountSyncStatus.Phase.Idle) }
            } catch (e: Exception) {
                syncStatus[id] = status(id).copy(phase = AccountSyncStatus.Phase.Error(e.message ?: "동기화 오류"))
                if (e is RemoteException.Unauthorized || e is RemoteException.TwoFactorRequired) {
                    engines.remove(id)   // rebuild with fresh credentials next time
                }
            } finally {
                syncJobs.remove(id)
            }
        }
    }

    fun resolve(conflict: SyncConflict, id: String, resolution: ConflictResolution) {
        val engine = engine(id) ?: return
        conflicts[id] = conflicts[id].orEmpty().filter { it.path != conflict.path }
        scope.launch {
            engine.resolve(conflict.path, resolution)
            sync(id, manual = true)
        }
    }

    fun loadConflicts() {
        for (account in accounts) {
            val engine = engine(account.id) ?: continue
            scope.launch { conflicts[account.id] = engine.conflicts() }
        }
    }

    // Lifecycle

    fun becameActive() {
        loadConflicts()
        syncAll()
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                delay(300_000)
                syncAll()
            }
        }
    }

    /**
     * Android cuts the network of apps in the background (most aggressively on Samsung), so a sync
     * started here dies mid-transfer ("Software caused connection abort"). Edits are already saved
     * locally; they go up on the next start, return to the foreground or edit.
     */
    fun enteredBackground() {
        periodic?.cancel()
        periodic = null
        scheduled.values.forEach { it.cancel() }
        scheduled.clear()
    }
}
