package com.lemon7679.note.core

import java.io.File
import java.time.Instant

/** A "NAS" that is a temp folder (the two-device sync tests' server). */
class LocalDirectoryFileSystem(private val root: File) : RemoteFileSystem {
    init {
        root.mkdirs()
    }

    private fun f(path: String) = root.resolveRemote(path)

    private fun item(path: String, file: File) = RemoteItem.of(path, file.isDirectory, if (file.isFile) file.length() else 0,
        Instant.ofEpochMilli(file.lastModified()))

    override suspend fun list(path: String): List<RemoteItem> {
        val dir = f(path)
        val children = dir.listFiles() ?: throw RemoteException.NotFound(path)
        return children.filter { !FileNaming.isIgnored(it.name) }.map { item(RemotePath.join(path, it.name), it) }
    }

    override suspend fun stat(path: String): RemoteItem? = f(path).takeIf { it.exists() }?.let { item(RemotePath.normalize(path), it) }

    override suspend fun download(path: String, to: File) {
        val src = f(path)
        if (!src.isFile) throw RemoteException.NotFound(path)
        to.parentFile?.mkdirs()
        src.copyTo(to, overwrite = true)
    }

    override suspend fun upload(from: File, path: String, modified: Instant?): RemoteItem? {
        val dst = f(path)
        dst.parentFile?.mkdirs()
        from.copyTo(dst, overwrite = true)
        modified?.let { dst.setLastModified(it.toEpochMilli()) }
        return stat(path)
    }

    override suspend fun createDirectory(path: String) {
        f(path).mkdirs()
    }

    override suspend fun delete(path: String) {
        if (RemotePath.normalize(path) == "/") throw RemoteException.Forbidden()
        f(path).deleteRecursively()
    }

    override suspend fun move(from: String, to: String) {
        f(to).parentFile?.mkdirs()
        if (!f(from).renameTo(f(to))) throw RemoteException.Forbidden()
    }

    override suspend fun testConnection(probeWrite: Boolean, folder: String) = ServerInfo("folder", "", true)
}
