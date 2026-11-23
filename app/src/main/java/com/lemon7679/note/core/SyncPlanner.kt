package com.lemon7679.note.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** What the last successful sync knew about one path (relative to the sync roots). */
@Serializable
data class SyncRecord(
    val isDirectory: Boolean,
    /** Local signature at the last sync; null when the file only exists on the NAS (placeholder). */
    val localSig: String?,
    /** Remote signature at the last sync. */
    val remoteSig: String?,
    val remoteSize: Long = 0,
    @Serializable(with = Iso8601Serializer::class) val remoteModified: Instant? = null,
) {
    val isPlaceholder: Boolean get() = !isDirectory && localSig == null
}

/** A file or folder in the local mirror. */
data class LocalEntry(val path: String, val isDirectory: Boolean, val size: Long, val modified: Instant?) {
    val signature: String
        get() = if (isDirectory) "dir" else "$size:${modified?.toEpochMilli() ?: 0}"
}

@Serializable
enum class ConflictResolution {
    /** Upload this device's version over the NAS one. */
    @SerialName("keepLocal") KEEP_LOCAL,
    /** Replace this device's version with the NAS one. */
    @SerialName("keepRemote") KEEP_REMOTE,
    /** Keep this device's version as a "(충돌 사본 …)" copy and take the NAS one. */
    @SerialName("keepBoth") KEEP_BOTH,
}

@Serializable
data class SyncConflict(
    /** The notebook package or file that changed on both sides. */
    val path: String,
    @Serializable(with = Iso8601Serializer::class) val localModified: Instant? = null,
    @Serializable(with = Iso8601Serializer::class) val remoteModified: Instant? = null,
    @Serializable(with = Iso8601Serializer::class) val detected: Instant,
) {
    val isNotebook: Boolean get() = RemotePath.pathExtension(path) == FileNaming.NOTEBOOK_EXTENSION
    val displayName: String
        get() = RemotePath.name(path).let { if (isNotebook) FileNaming.split(it).first else it }
}

sealed class SyncAction(val path: String) {
    class MakeRemoteDirectory(p: String) : SyncAction(p)
    class MakeLocalDirectory(p: String) : SyncAction(p)
    class Upload(p: String) : SyncAction(p)
    class Download(p: String) : SyncAction(p)
    /** Deletes a file or a whole folder on the NAS. */
    class DeleteRemote(p: String) : SyncAction(p)
    class DeleteLocal(p: String) : SyncAction(p)
    /** Remote-only file kept online (on-demand); state only. */
    class Placeholder(p: String) : SyncAction(p)
    /** Both sides already hold the same thing; state only. */
    class Adopt(p: String) : SyncAction(p)
    /** Gone on both sides; state only. */
    class Forget(p: String) : SyncAction(p)
    /** Copies the local item aside as a conflict copy (before it is replaced). */
    class ConflictCopy(p: String) : SyncAction(p)

    /** Execution order: copies, folders (shallow first), transfers, deletions (deep first). */
    val phase: Int
        get() = when (this) {
            is ConflictCopy -> 0
            is MakeRemoteDirectory, is MakeLocalDirectory -> 1
            is Upload, is Download -> 2
            is DeleteRemote, is DeleteLocal -> 3
            is Placeholder, is Adopt, is Forget -> 4
        }

    val transfersData: Boolean get() = this is Upload || this is Download

    override fun equals(other: Any?) = other is SyncAction && other.javaClass == javaClass && other.path == path
    override fun hashCode() = javaClass.hashCode() * 31 + path.hashCode()
    override fun toString() = "${javaClass.simpleName}($path)"
}

data class SyncPlan(val actions: List<SyncAction>, val conflicts: List<SyncConflict>)

/**
 * Three-way comparison of the local mirror, the NAS folder and the state of
 * the last sync. Pure (no I/O) so every rule is unit-tested. Same rules as note-ipad:
 *
 * - Changed on one side → copy to the other. Deleted on one side and unchanged
 *   on the other → delete. Deleted on one side but changed on the other → the
 *   change wins (the file is restored).
 * - Changed on both sides → conflict. A conflict anywhere in a `.inknote`
 *   package is a conflict of the whole notebook.
 * - A folder deleted on one side is deleted on the other only when nothing
 *   inside it changed there; otherwise it is recreated.
 * - Items under `busy` (open notebooks) are left for the next sync.
 */
object SyncPlanner {
    fun plan(
        local: Map<String, LocalEntry>,
        remote: Map<String, RemoteItem>,
        base: Map<String, SyncRecord>,
        busy: Set<String> = emptySet(),
        resolutions: Map<String, ConflictResolution> = emptyMap(),
        downloadOnDemand: Boolean = true,
        now: Instant = Instant.now(),
    ): SyncPlan {
        val actions = ArrayList<SyncAction>()
        val conflicts = LinkedHashMap<String, SyncConflict>()
        val handledPrefixes = ArrayList<String>()

        val allPaths = (local.keys + remote.keys + base.keys).filter { it != "/" }
            .sortedWith(compareBy<String> { RemotePath.depth(it) }.thenBy { it })

        fun isBusy(p: String) = busy.any { RemotePath.isAncestor(it, p) }
        fun isHandled(p: String) = handledPrefixes.any { RemotePath.isAncestor(it, p) }

        // 1. Explicit resolutions take over whole subtrees.
        for ((root, resolution) in resolutions.entries.sortedBy { it.key }) {
            if (isBusy(root)) continue
            if (resolution == ConflictResolution.KEEP_BOTH && local[root] != null) actions += SyncAction.ConflictCopy(root)
            for (p in allPaths.filter { RemotePath.isAncestor(root, it) }) {
                val l = local[p]
                val r = remote[p]
                when (resolution) {
                    ConflictResolution.KEEP_LOCAL -> actions += when {
                        l != null -> if (l.isDirectory) SyncAction.MakeRemoteDirectory(p) else SyncAction.Upload(p)
                        r != null -> SyncAction.DeleteRemote(p)
                        else -> SyncAction.Forget(p)
                    }
                    ConflictResolution.KEEP_REMOTE, ConflictResolution.KEEP_BOTH -> actions += when {
                        r != null -> if (r.isDirectory) SyncAction.MakeLocalDirectory(p) else SyncAction.Download(p)
                        l != null -> SyncAction.DeleteLocal(p)
                        else -> SyncAction.Forget(p)
                    }
                }
            }
            handledPrefixes += root
        }

        // 2. Folders deleted on one side (shallowest first, so a deleted tree is one action).
        for (p in allPaths) {
            if (isHandled(p) || isBusy(p)) continue
            val b = base[p] ?: continue
            if (!b.isDirectory) continue
            val l = local[p]
            val r = remote[p]
            if (l == null && r != null && r.isDirectory) {
                val changedRemotely = remote.any { (key, item) ->
                    RemotePath.isAncestor(p, key) && key != p && base[key]?.remoteSig != item.signature
                }
                if (!changedRemotely) {
                    actions += SyncAction.DeleteRemote(p)
                    handledPrefixes += p
                }
            } else if (r == null && l != null && l.isDirectory) {
                val changedLocally = local.any { (key, entry) ->
                    RemotePath.isAncestor(p, key) && key != p && base[key]?.localSig != entry.signature
                }
                if (!changedLocally) {
                    actions += SyncAction.DeleteLocal(p)
                    handledPrefixes += p
                }
            } else if (l == null && r == null) {
                actions += SyncAction.Forget(p)
                handledPrefixes += p
            }
        }

        // 3. Everything else, path by path.
        val perPath = ArrayList<SyncAction>()
        for (p in allPaths) {
            if (isHandled(p) || isBusy(p)) continue
            val l = local[p]
            val r = remote[p]
            val b = base[p]
            val inPackage = FileNaming.packageRoot(p) != null
            fun conflict() {
                val root = FileNaming.packageRoot(p) ?: p
                if (conflicts[root] == null) {
                    conflicts[root] = SyncConflict(root, local[root]?.modified ?: l?.modified,
                        remote[root]?.modified ?: r?.modified, now)
                }
            }

            // Folders.
            if (l?.isDirectory == true || r?.isDirectory == true || b?.isDirectory == true) {
                val ld = l?.isDirectory
                val rd = r?.isDirectory
                when {
                    ld == true && rd == true -> if (b == null) perPath += SyncAction.Adopt(p)
                    ld == true && rd == null -> perPath += SyncAction.MakeRemoteDirectory(p)
                    ld == null && rd == true -> perPath += SyncAction.MakeLocalDirectory(p)
                    ld == null && rd == null -> perPath += SyncAction.Forget(p)
                    else -> conflict()   // a file on one side, a folder on the other
                }
                continue
            }

            val lChanged = if (b == null) l != null else l?.signature != b.localSig
            val rChanged = if (b == null) r != null else r?.signature != b.remoteSig
            when {
                l == null && r == null -> if (b != null) perPath += SyncAction.Forget(p)
                l != null && r == null -> perPath += when {
                    b == null || b.remoteSig == null -> SyncAction.Upload(p)   // new on this device
                    lChanged -> SyncAction.Upload(p)                            // deleted on NAS, edited here
                    else -> SyncAction.DeleteLocal(p)
                }
                l == null && r != null -> when {
                    b != null && b.isPlaceholder -> if (rChanged) perPath += SyncAction.Placeholder(p)
                    b == null -> perPath += if (downloadOnDemand && !inPackage) SyncAction.Placeholder(p) else SyncAction.Download(p)
                    rChanged -> perPath += SyncAction.Download(p)               // deleted here, edited on NAS
                    else -> perPath += SyncAction.DeleteRemote(p)
                }
                else -> {
                    l!!
                    r!!
                    if (b == null || b.isPlaceholder) {
                        // Both exist without history (first sync, or a placeholder that appeared locally).
                        if (l.size == r.size) perPath += SyncAction.Adopt(p) else conflict()
                    } else if (lChanged && rChanged) {
                        conflict()
                    } else if (lChanged) {
                        perPath += SyncAction.Upload(p)
                    } else if (rChanged) {
                        perPath += SyncAction.Download(p)
                    }
                }
            }
        }

        // Conflicted packages drop every action inside them.
        for (action in perPath) {
            if (conflicts.keys.none { RemotePath.isAncestor(it, action.path) }) actions += action
        }

        val ordered = actions.withIndex().sortedWith { a, b ->
            val pa = a.value.phase
            val pb = b.value.phase
            if (pa != pb) return@sortedWith pa.compareTo(pb)
            val da = RemotePath.depth(a.value.path)
            val db = RemotePath.depth(b.value.path)
            if (da != db) return@sortedWith if (pa == 3) db.compareTo(da) else da.compareTo(db)
            a.index.compareTo(b.index)
        }.map { it.value }

        return SyncPlan(ordered, conflicts.values.sortedBy { it.path })
    }
}
