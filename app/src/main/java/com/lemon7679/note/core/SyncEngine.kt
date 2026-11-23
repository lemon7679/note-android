package com.lemon7679.note.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** Persistent sync state of one account (files/Note/Sync/<id>.json). */
@Serializable
data class SyncState(
    val version: Int = 1,
    val records: Map<String, SyncRecord> = emptyMap(),
    val conflicts: Map<String, SyncConflict> = emptyMap(),
    val resolutions: Map<String, ConflictResolution> = emptyMap(),
    @Serializable(with = Iso8601Serializer::class) val lastSync: Instant? = null,
)

data class SyncProgress(val phase: Phase, val completed: Int, val total: Int, val currentItem: String?) {
    enum class Phase { SCANNING, TRANSFERRING, FINISHED }

    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

data class SyncReport(
    var uploaded: Int = 0,
    var downloaded: Int = 0,
    var deletedLocal: Int = 0,
    var deletedRemote: Int = 0,
    var conflicts: List<SyncConflict> = emptyList(),
    val errors: MutableList<String> = ArrayList(),
    /** Local paths that changed (for refreshing open views). */
    val changedLocalPaths: MutableList<String> = ArrayList(),
    var finished: Instant = Instant.now(),
) {
    val transferred: Int get() = uploaded + downloaded + deletedLocal + deletedRemote
}

sealed class SyncException(message: String) : java.io.IOException(message) {
    class RemoteFolderMissing(path: String) : SyncException("NAS에서 노트 폴더($path)를 찾을 수 없습니다.")
    class SuspiciousEmptyRemote :
        SyncException("NAS 폴더가 비어 있는 것으로 보여 안전을 위해 동기화를 멈췄습니다. NAS 상태를 확인해 주세요.")
}

/**
 * Two-way mirror between a local folder and a NAS folder.
 *
 * The app always works on the local mirror (fast, offline-capable); the engine
 * reconciles it with the NAS using `SyncPlanner`. State is saved after every
 * ten transfers, so an interrupted sync resumes where it stopped.
 * All entry points are serialized (one at a time per account), like the iPad actor.
 */
class SyncEngine(
    private val remote: RemoteFileSystem,
    val localRoot: File,
    remoteRoot: String,
    private val stateFile: File,
    private val deviceName: String = "Android",
) {
    val remoteRoot: String = RemotePath.normalize(remoteRoot)
    private val mutex = Mutex()
    private var state: SyncState = runCatching {
        NoteJson.decodeFromString(SyncState.serializer(), stateFile.readText())
    }.getOrDefault(SyncState())

    init {
        localRoot.mkdirs()
    }

    suspend fun currentState(): SyncState = mutex.withLock { state }
    suspend fun conflicts(): List<SyncConflict> = mutex.withLock { state.conflicts.values.sortedBy { it.path } }

    private fun saveState() {
        runCatching { writeAtomically(stateFile, NoteJson.encodeToString(SyncState.serializer(), state).toByteArray()) }
    }

    private fun remotePath(rel: String) = RemotePath.join(remoteRoot, rel)
    private fun localFile(rel: String) = localRoot.resolveRemote(rel)

    private fun setRecord(path: String, record: SyncRecord?) {
        state = state.copy(records = if (record == null) state.records - path else state.records + (path to record))
    }

    fun scanLocal(): Map<String, LocalEntry> {
        val out = HashMap<String, LocalEntry>()
        val queue = ArrayDeque(listOf("/"))
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (f in localFile(dir).listFiles() ?: emptyArray()) {
                if (FileNaming.isIgnored(f.name)) continue
                val rel = RemotePath.join(dir, f.name)
                out[rel] = entry(rel, f)
                if (f.isDirectory) queue.add(rel)
            }
        }
        return out
    }

    private fun entry(rel: String, f: File) =
        LocalEntry(rel, f.isDirectory, if (f.isFile) f.length() else 0, Instant.ofEpochMilli(f.lastModified()))

    private fun localEntry(rel: String): LocalEntry? = localFile(rel).takeIf { it.exists() }?.let { entry(rel, it) }

    private suspend fun scanRemote(): Map<String, RemoteItem> {
        if (remote.stat(remoteRoot) == null) {
            if (state.records.isEmpty()) remote.createDirectory(remoteRoot)
            else throw SyncException.RemoteFolderMissing(remoteRoot)
        }
        val out = HashMap<String, RemoteItem>()
        for (item in remote.listRecursive(remoteRoot)) {
            val rel = RemotePath.relative(item.path, remoteRoot) ?: continue
            if (rel == "/") continue
            if (RemotePath.components(rel).any(FileNaming::isIgnored)) continue
            out[rel] = item.copy(path = rel)
        }
        return out
    }

    /** One full sync pass. `busy` holds library paths of notebooks open for editing. */
    suspend fun run(options: SyncOptions, busy: Set<String> = emptySet(), progress: ((SyncProgress) -> Unit)? = null): SyncReport =
        mutex.withLock { withContext(Dispatchers.IO) { runLocked(options, busy, progress, false) } }

    private suspend fun runLocked(options: SyncOptions, busy: Set<String>, progress: ((SyncProgress) -> Unit)?,
                                  followUp: Boolean): SyncReport {
        progress?.invoke(SyncProgress(SyncProgress.Phase.SCANNING, 0, 0, null))
        val remoteItems = scanRemote()
        val localItems = scanLocal()

        // Safety: never turn "the NAS answered with nothing" into "delete everything here".
        val knownRemote = state.records.values.count { it.remoteSig != null }
        if (remoteItems.isEmpty() && knownRemote >= 5) throw SyncException.SuspiciousEmptyRemote()
        // A wiped mirror re-downloads instead of deleting the NAS copy.
        val knownLocal = state.records.values.count { it.localSig != null }
        if (localItems.isEmpty() && knownLocal >= 5) state = state.copy(records = emptyMap())

        val resolutions = HashMap(state.resolutions)
        if (options.keepBothOnConflict) {
            for (path in state.conflicts.keys) resolutions.putIfAbsent(path, ConflictResolution.KEEP_BOTH)
        }

        val plan = SyncPlanner.plan(localItems, remoteItems, state.records, busy, resolutions, options.downloadOnDemand)
        val report = SyncReport()
        val transfers = plan.actions.count { it.phase != 4 }
        var done = 0
        var madeCopies = false

        for (action in plan.actions) {
            coroutineContext.ensureActive()
            if (action.phase != 4) {
                progress?.invoke(SyncProgress(SyncProgress.Phase.TRANSFERRING, done, transfers, RemotePath.name(action.path)))
            }
            try {
                execute(action, remoteItems, report)
                if (action is SyncAction.ConflictCopy) madeCopies = true
            } catch (e: CancellationException) {
                saveState()
                throw e
            } catch (e: RemoteException) {
                if (e.stopsSync) {
                    saveState()
                    throw e   // credentials / certificate / full-disk problems stop the whole sync
                }
                report.errors += "${RemotePath.name(action.path)}: ${e.message}"
            } catch (e: Exception) {
                report.errors += "${RemotePath.name(action.path)}: ${e.message}"
            }
            if (action.phase != 4) {
                done += 1
                if (done % 10 == 0) saveState()
            }
        }

        // Resolutions that were applied are consumed; new conflicts are remembered.
        var newResolutions = state.resolutions
        var oldConflicts = state.conflicts
        for (root in resolutions.keys) {
            if (busy.any { RemotePath.isAncestor(it, root) }) continue
            newResolutions = newResolutions - root
            oldConflicts = oldConflicts - root
        }
        val conflicts = plan.conflicts.associate { c -> c.path to (oldConflicts[c.path] ?: c) }
        state = state.copy(resolutions = newResolutions, conflicts = conflicts, lastSync = Instant.now())
        saveState()

        report.conflicts = conflicts.values.sortedBy { it.path }
        report.finished = Instant.now()
        progress?.invoke(SyncProgress(SyncProgress.Phase.FINISHED, transfers, transfers, null))

        val autoResolve = options.keepBothOnConflict && plan.conflicts.isNotEmpty()
        if (!followUp && (madeCopies || autoResolve)) {
            // Upload conflict copies right away / settle new conflicts as "keep both".
            val next = runLocked(options, busy, null, true)
            report.uploaded += next.uploaded
            report.downloaded += next.downloaded
            report.changedLocalPaths += next.changedLocalPaths
            report.conflicts = next.conflicts
            report.errors += next.errors
        }
        return report
    }

    private suspend fun execute(action: SyncAction, remoteItems: Map<String, RemoteItem>, report: SyncReport) {
        val p = action.path
        when (action) {
            is SyncAction.MakeRemoteDirectory -> {
                remote.createDirectory(remotePath(p))
                setRecord(p, SyncRecord(true, "dir", "dir"))
            }
            is SyncAction.MakeLocalDirectory -> {
                localFile(p).mkdirs()
                setRecord(p, SyncRecord(true, "dir", "dir"))
                report.changedLocalPaths += p
            }
            is SyncAction.Upload -> {
                val entry = localEntry(p) ?: return   // vanished meanwhile
                val item = remote.upload(localFile(p), remotePath(p), entry.modified) ?: remote.stat(remotePath(p))
                setRecord(p, SyncRecord(false, entry.signature, item?.signature, item?.size ?: entry.size, item?.modified))
                report.uploaded += 1
            }
            is SyncAction.Download -> {
                val item = remoteItems[p] ?: return
                downloadFile(p, item)
                report.downloaded += 1
                report.changedLocalPaths += p
            }
            is SyncAction.DeleteRemote -> {
                remote.delete(remotePath(p))
                removeRecords(p)
                report.deletedRemote += 1
            }
            is SyncAction.DeleteLocal -> {
                localFile(p).deleteRecursively()
                removeRecords(p)
                report.deletedLocal += 1
                report.changedLocalPaths += p
            }
            is SyncAction.Placeholder -> {
                val item = remoteItems[p] ?: return
                setRecord(p, SyncRecord(false, null, item.signature, item.size, item.modified))
            }
            is SyncAction.Adopt -> {
                val item = remoteItems[p] ?: return
                val entry = localEntry(p) ?: return
                setRecord(p, SyncRecord(item.isDirectory, entry.signature, item.signature, item.size, item.modified))
            }
            is SyncAction.Forget -> setRecord(p, null)
            is SyncAction.ConflictCopy -> {
                makeConflictCopy(p)
                report.changedLocalPaths += RemotePath.parent(p)
            }
        }
    }

    private fun removeRecords(prefix: String) {
        state = state.copy(records = state.records.filterKeys { !RemotePath.isAncestor(prefix, it) })
    }

    private suspend fun downloadFile(rel: String, item: RemoteItem) {
        val tmpDir = File(localRoot, ".sync-tmp").apply { mkdirs() }
        val tmp = File(tmpDir, newId())
        try {
            remote.download(remotePath(rel), tmp)
            val dest = localFile(rel)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.deleteRecursively()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
            }
            item.modified?.let { dest.setLastModified(it.toEpochMilli()) }
            val entry = localEntry(rel)
            setRecord(rel, SyncRecord(false, entry?.signature, item.signature, item.size, item.modified))
        } finally {
            tmp.delete()
        }
    }

    private fun makeConflictCopy(rel: String) {
        val source = localFile(rel)
        if (!source.exists()) return
        val parent = RemotePath.parent(rel)
        val copyName = FileNaming.conflictCopyName(RemotePath.name(rel), deviceName)
        val library = LibraryFileSystem(localRoot)
        if (NotebookPackage.isPackage(source)) {
            NotebookPackage(source).duplicate(localFile(parent), FileNaming.split(copyName).first)
        } else {
            val (base, ext) = FileNaming.split(copyName)
            val name = FileNaming.unique(base, ext) { library.exists(RemotePath.join(parent, it)) }
            source.copyTo(localFile(RemotePath.join(parent, name)))
        }
    }

    /** Online-only files directly inside `folder`. */
    suspend fun placeholders(folder: String): List<LibraryEntry> = mutex.withLock {
        val dir = RemotePath.normalize(folder)
        state.records.mapNotNull { (path, record) ->
            if (!record.isPlaceholder || RemotePath.parent(path) != dir || localFile(path).exists()) null
            else LibraryEntry(path, LibraryItemKind.classify(RemotePath.name(path), false), record.remoteModified,
                record.remoteSize, isPlaceholder = true)
        }
    }

    /** Online-only files whose name contains `query`. */
    suspend fun searchPlaceholders(query: String): List<LibraryEntry> = mutex.withLock {
        state.records.mapNotNull { (path, record) ->
            if (!record.isPlaceholder || !RemotePath.name(path).contains(query, ignoreCase = true)) null
            else LibraryEntry(path, LibraryItemKind.classify(RemotePath.name(path), false), record.remoteModified,
                record.remoteSize, isPlaceholder = true)
        }
    }

    /** Downloads an online-only file so it can be opened. */
    suspend fun materialize(path: String): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rel = RemotePath.normalize(path)
            val record = state.records[rel]
            if (record != null && !record.isPlaceholder && localFile(rel).exists()) return@withContext localFile(rel)
            val item = remote.stat(remotePath(rel)) ?: throw RemoteException.NotFound(rel)
            downloadFile(rel, item.copy(path = rel))
            saveState()
            localFile(rel)
        }
    }

    /** Deletes an online-only file on the NAS. */
    suspend fun deletePlaceholder(path: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rel = RemotePath.normalize(path)
            remote.delete(remotePath(rel))
            setRecord(rel, null)
            saveState()
        }
    }

    /** Frees space: removes the local copy of a downloaded file but keeps it on the NAS. */
    suspend fun evict(path: String) = mutex.withLock {
        val rel = RemotePath.normalize(path)
        val record = state.records[rel] ?: return@withLock
        if (record.isDirectory || FileNaming.packageRoot(rel) != null) return@withLock
        val entry = localEntry(rel)
        if (entry != null && entry.signature != record.localSig) return@withLock   // unsynced edits
        localFile(rel).delete()
        setRecord(rel, record.copy(localSig = null))
        saveState()
    }

    /** Chooses how a conflict is settled; applied by the next `run`. */
    suspend fun resolve(path: String, resolution: ConflictResolution) = mutex.withLock {
        state = state.copy(resolutions = state.resolutions + (RemotePath.normalize(path) to resolution))
        saveState()
    }

    /** Forgets everything (e.g. when the sync folder changes). */
    suspend fun reset() = mutex.withLock {
        state = SyncState()
        saveState()
    }
}
