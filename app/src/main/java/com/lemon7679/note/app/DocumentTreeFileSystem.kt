package com.lemon7679.note.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.RemoteException
import com.lemon7679.note.core.RemoteFileSystem
import com.lemon7679.note.core.RemoteItem
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.core.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * A folder picked with the system file picker (ACTION_OPEN_DOCUMENT_TREE): SMB shares
 * exposed by a storage-provider app, USB drives, cloud providers or local storage.
 * The Android counterpart of the iPad's "파일 앱" folder (security-scoped bookmark).
 */
class DocumentTreeFileSystem(private val context: Context, treeUri: String) : RemoteFileSystem {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        ?: throw RemoteException.NotFound("/")

    private fun resolve(path: String): DocumentFile? {
        var current = root
        for (part in RemotePath.components(path)) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun item(path: String, doc: DocumentFile) = RemoteItem.of(
        path, doc.isDirectory, if (doc.isFile) doc.length() else 0,
        doc.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
    )

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val dir = resolve(path) ?: throw RemoteException.NotFound(path)
        dir.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            if (FileNaming.isIgnored(name)) null else item(RemotePath.join(path, name), doc)
        }
    }

    override suspend fun stat(path: String): RemoteItem? = withContext(Dispatchers.IO) {
        resolve(path)?.let { item(RemotePath.normalize(path), it) }
    }

    override suspend fun download(path: String, to: File) {
        withContext(Dispatchers.IO) {
            val doc = resolve(path) ?: throw RemoteException.NotFound(path)
            to.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(doc.uri) ?: throw RemoteException.NotFound(path)
            input.use { inp -> to.outputStream().use { inp.copyTo(it) } }
        }
    }

    override suspend fun upload(from: File, path: String, modified: Instant?): RemoteItem? = withContext(Dispatchers.IO) {
        val parent = ensureDirectory(RemotePath.parent(path))
        val name = RemotePath.name(path)
        val doc = parent.findFile(name)?.takeIf { it.isFile }
            ?: parent.createFile("application/octet-stream", name)
            ?: throw RemoteException.Forbidden()
        val output = context.contentResolver.openOutputStream(doc.uri, "wt") ?: throw RemoteException.Forbidden()
        output.use { out -> from.inputStream().use { it.copyTo(out) } }
        // Some providers append an extension to new files; rename back to the exact name.
        if (doc.name != name) doc.renameTo(name)
        resolve(path)?.let { item(RemotePath.normalize(path), it) }
    }

    private fun ensureDirectory(path: String): DocumentFile {
        var current = root
        for (part in RemotePath.components(path)) {
            current = current.findFile(part)?.takeIf { it.isDirectory }
                ?: current.createDirectory(part)
                ?: throw RemoteException.Forbidden()
        }
        return current
    }

    override suspend fun createDirectory(path: String) {
        withContext(Dispatchers.IO) { ensureDirectory(path) }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            if (RemotePath.normalize(path) == "/") throw RemoteException.Forbidden()
            resolve(path)?.delete()
        }
    }

    override suspend fun move(from: String, to: String) {
        withContext(Dispatchers.IO) {
            val src = resolve(from) ?: throw RemoteException.NotFound(from)
            if (RemotePath.parent(from) == RemotePath.parent(to)) {
                if (!src.renameTo(RemotePath.name(to))) throw RemoteException.Forbidden()
                return@withContext
            }
            throw RemoteException.Unsupported("폴더 사이 이동")
        }
    }

    override suspend fun testConnection(probeWrite: Boolean, folder: String): ServerInfo = withContext(Dispatchers.IO) {
        if (!root.exists()) throw RemoteException.NotFound("/")
        ServerInfo("폴더", root.name ?: "", root.canWrite())
    }
}
