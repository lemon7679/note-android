package com.lemon7679.note.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.text.Collator
import java.time.Instant
import java.util.Locale

class NoteException(message: String) : IOException(message)

/**
 * A notebook on disk: a `.inknote` directory, synced file by file so that only
 * changed pages travel to the NAS.
 *
 *     선형대수 필기.inknote/
 *       document.json         NotebookDocument (pages, paper, PDF source)
 *       pages/<uuid>.ink       InkEngine page data, one file per page
 *       assets/source.pdf      imported PDF (when the notebook annotates one)
 *       assets/<uuid>.jpg      inserted images
 *       thumbnail.png          cover / first page, for library grids on every device
 */
class NotebookPackage(val dir: File) {
    val documentFile get() = File(dir, DOCUMENT_FILE)
    val pagesDir get() = File(dir, PAGES_DIRECTORY)
    val assetsDir get() = File(dir, ASSETS_DIRECTORY)
    val thumbnailFile get() = File(dir, THUMBNAIL_FILE)

    fun readDocument(): NotebookDocument {
        if (!documentFile.exists()) throw NoteException("${dir.name}은(는) 노트 파일이 아닙니다.")
        val doc = NoteJson.decodeFromString(NotebookDocument.serializer(), documentFile.readText())
        if (doc.format > NotebookDocument.CURRENT_FORMAT) {
            throw NoteException("이 노트는 더 새로운 버전(${doc.format})의 앱에서 만들어졌습니다.")
        }
        return doc
    }

    fun write(document: NotebookDocument) {
        writeAtomically(documentFile, NoteJson.encodeToString(NotebookDocument.serializer(), document).toByteArray())
    }

    fun inkFile(pageId: String) = File(pagesDir, "$pageId.ink")
    fun readInk(pageId: String): ByteArray? = inkFile(pageId).takeIf { it.exists() }?.readBytes()

    fun writeInk(data: ByteArray, pageId: String) {
        pagesDir.mkdirs()
        writeAtomically(inkFile(pageId), data)
    }

    fun deleteInk(pageId: String) {
        inkFile(pageId).delete()
    }

    /** Removes ink files of pages that are no longer in `document`. */
    fun pruneInk(document: NotebookDocument) {
        val keep = document.pages.map { it.inkFileName }.toSet()
        pagesDir.listFiles()?.forEach { f -> if (f.name.endsWith(".ink") && f.name !in keep) f.delete() }
    }

    fun assetFile(name: String) = File(assetsDir, name)

    /** Stores `data` as a new asset and returns its file name. */
    fun addAsset(data: ByteArray, extension: String): String {
        assetsDir.mkdirs()
        val name = "${newId()}.$extension"
        writeAtomically(assetFile(name), data)
        return name
    }

    fun writeThumbnail(png: ByteArray) = writeAtomically(thumbnailFile, png)

    /** Copies the package into `directory` with a fresh notebook id (a separate notebook). */
    fun duplicate(directory: File, title: String? = null): NotebookPackage {
        val doc = readDocument()
        val base = FileNaming.sanitize(title ?: FileNaming.split(dir.name).first)
        val name = FileNaming.unique(base, FileNaming.NOTEBOOK_EXTENSION) { File(directory, it).exists() }
        val target = NotebookPackage(File(directory, name))
        dir.copyRecursively(target.dir, overwrite = false)
        target.write(doc.copy(id = newId(), title = title ?: doc.title, modified = Instant.now()))
        return target
    }

    companion object {
        const val DOCUMENT_FILE = "document.json"
        const val PAGES_DIRECTORY = "pages"
        const val ASSETS_DIRECTORY = "assets"
        const val THUMBNAIL_FILE = "thumbnail.png"

        fun isPackage(f: File) = f.extension.lowercase() == FileNaming.NOTEBOOK_EXTENSION

        /** Creates a package named after the document's title (made unique) inside `directory`. */
        fun create(directory: File, document: NotebookDocument): NotebookPackage {
            directory.mkdirs()
            val base = FileNaming.sanitize(document.title)
            val name = FileNaming.unique(base, FileNaming.NOTEBOOK_EXTENSION) { File(directory, it).exists() }
            val pkg = NotebookPackage(File(directory, name))
            pkg.pagesDir.mkdirs()
            pkg.assetsDir.mkdirs()
            pkg.write(document)
            return pkg
        }

        /** Creates a notebook whose pages are the pages of `pdf` (sizes measured by the caller). */
        fun createAnnotating(
            pdf: File, title: String, pageSizes: List<PageSize>, directory: File,
            linkedPath: String? = null, cover: CoverStyle = CoverStyle(null),
        ): NotebookPackage {
            val blank = PaperStyle(template = PaperTemplate.BLANK)
            val doc = NotebookDocument.create(title, cover, blank, pageSizes.firstOrNull() ?: PageSizePreset.A4.size, 0)
                .copy(
                    pages = pageSizes.mapIndexed { i, size ->
                        NotebookPage(size = size, paper = blank, background = PageBackground.pdf(i))
                    },
                    pdf = PdfSource("source.pdf", pdf.name, linkedPath),
                )
            val pkg = create(directory, doc)
            pdf.copyTo(File(pkg.assetsDir, "source.pdf"), overwrite = true)
            return pkg
        }
    }
}

fun writeAtomically(target: File, data: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
    tmp.writeBytes(data)
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("${target.name}을(를) 저장하지 못했습니다.")
        }
    }
}

enum class LibraryItemKind {
    FOLDER, NOTEBOOK, PDF, IMAGE, OTHER;

    companion object {
        fun classify(name: String, isDirectory: Boolean): LibraryItemKind {
            val ext = RemotePath.pathExtension(name)
            if (ext == FileNaming.NOTEBOOK_EXTENSION) return NOTEBOOK
            if (isDirectory) return FOLDER
            return when (ext) {
                "pdf" -> PDF
                "png", "jpg", "jpeg", "heic", "gif", "webp" -> IMAGE
                else -> OTHER
            }
        }
    }
}

/** One entry of a library folder listing. */
data class LibraryEntry(
    /** Path inside the library source ("/강의/선형대수.inknote"). */
    val path: String,
    val kind: LibraryItemKind,
    val modified: Instant?,
    val size: Long,
    /** Listed from the NAS but not downloaded yet (opened on demand). */
    val isPlaceholder: Boolean = false,
) {
    val fileName: String get() = RemotePath.name(path)

    /** Name without the `.inknote` extension (other files keep theirs). */
    val displayName: String get() = if (kind == LibraryItemKind.NOTEBOOK) FileNaming.split(fileName).first else fileName

    val isDocument: Boolean get() = kind == LibraryItemKind.NOTEBOOK || kind == LibraryItemKind.PDF
}

private val collator: Collator = Collator.getInstance(Locale.KOREAN)

@Serializable
enum class LibrarySort(val displayName: String) {
    MODIFIED("수정일"), NAME("이름"), KIND("종류");

    fun sort(items: List<LibraryEntry>): List<LibraryEntry> = when (this) {
        MODIFIED -> items.sortedByDescending { it.modified ?: Instant.EPOCH }
        NAME -> items.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        KIND -> items.sortedWith(compareBy<LibraryEntry> { it.kind.ordinal }
            .then { a, b -> collator.compare(a.displayName, b.displayName) })
    }
}

/** Lightweight facts about a notebook for library cards. */
data class NotebookSummary(
    val title: String,
    val pageCount: Int,
    val coverHex: String?,
    val isPdf: Boolean,
    val modified: Instant,
    val firstPaper: PaperStyle?,
    val firstPageSize: PageSize?,
)

/**
 * File operations on one library source rooted at a local directory: "이 기기"
 * or the local mirror of a NAS account.
 */
class LibraryFileSystem(val root: File) {
    init {
        root.mkdirs()
    }

    fun file(path: String): File = root.resolveRemote(path)
    fun exists(path: String) = file(path).exists()

    fun list(folder: String): List<LibraryEntry> {
        val dir = file(folder)
        val children = dir.listFiles() ?: throw NoteException("${RemotePath.name(folder)}을(를) 찾을 수 없습니다.")
        return children.filter { !FileNaming.isIgnored(it.name) }.map { f ->
            val kind = LibraryItemKind.classify(f.name, f.isDirectory)
            var modified = f.lastModified()
            if (kind == LibraryItemKind.NOTEBOOK) {
                // A package's own mtime does not change when a page inside it does.
                val doc = File(f, NotebookPackage.DOCUMENT_FILE)
                if (doc.exists()) modified = doc.lastModified()
            }
            LibraryEntry(RemotePath.join(folder, f.name), kind, Instant.ofEpochMilli(modified),
                if (f.isFile) f.length() else 0)
        }
    }

    /** Every folder (not notebook packages) below `folder`, depth-first, for move pickers. */
    fun allFolders(folder: String = "/"): List<String> {
        val out = ArrayList<String>()
        for (item in runCatching { list(folder) }.getOrDefault(emptyList())) {
            if (item.kind == LibraryItemKind.FOLDER) {
                out.add(item.path)
                out.addAll(allFolders(item.path))
            }
        }
        return out
    }

    /** Case-insensitive name search through the whole source. */
    fun search(query: String, folder: String = "/"): List<LibraryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<LibraryEntry>()
        for (item in runCatching { list(folder) }.getOrDefault(emptyList())) {
            if (item.displayName.contains(q, ignoreCase = true)) out.add(item)
            if (item.kind == LibraryItemKind.FOLDER) out.addAll(search(q, item.path))
        }
        return out
    }

    fun summary(path: String): NotebookSummary? = runCatching {
        val doc = NotebookPackage(file(path)).readDocument()
        NotebookSummary(doc.title, doc.pages.size, doc.cover.colorHex, doc.pdf != null, doc.modified,
            doc.pages.firstOrNull()?.paper, doc.pages.firstOrNull()?.size)
    }.getOrNull()

    fun createFolder(name: String, folder: String): String {
        val clean = FileNaming.sanitize(name, "새 폴더")
        val unique = FileNaming.unique(clean, "") { exists(RemotePath.join(folder, it)) }
        val path = RemotePath.join(folder, unique)
        file(path).mkdirs()
        return path
    }

    /** Renames an item; for notebooks the title inside the package follows. */
    fun rename(path: String, newName: String): String {
        if (!exists(path)) throw NoteException("${RemotePath.name(path)}을(를) 찾을 수 없습니다.")
        val isNotebook = RemotePath.pathExtension(path) == FileNaming.NOTEBOOK_EXTENSION
        val ext = if (isNotebook) FileNaming.NOTEBOOK_EXTENSION else RemotePath.pathExtension(path)
        var base = FileNaming.sanitize(newName, "")
        if (base.isEmpty()) throw NoteException("사용할 수 없는 이름입니다.")
        if (!isNotebook && ext.isNotEmpty() && base.lowercase().endsWith(".$ext")) base = base.dropLast(ext.length + 1)
        val fileName = if (ext.isEmpty()) base else "$base.$ext"
        val target = RemotePath.join(RemotePath.parent(path), fileName)
        if (target == path) return path
        if (exists(target) && target.lowercase() != path.lowercase()) throw NoteException("‘$fileName’ 이름이 이미 있습니다.")
        if (!file(path).renameTo(file(target))) throw NoteException("이름을 바꾸지 못했습니다.")
        if (isNotebook) {
            val pkg = NotebookPackage(file(target))
            runCatching { pkg.write(pkg.readDocument().copy(title = base, modified = Instant.now())) }
        }
        return target
    }

    fun move(path: String, folder: String): String {
        if (!exists(path)) throw NoteException("${RemotePath.name(path)}을(를) 찾을 수 없습니다.")
        if (RemotePath.parent(path) == RemotePath.normalize(folder)) return path
        if (RemotePath.isAncestor(path, folder)) throw NoteException("폴더를 그 안으로 옮길 수 없습니다.")
        val fileName = RemotePath.name(path)
        val isFolder = file(path).isDirectory && RemotePath.pathExtension(path) != FileNaming.NOTEBOOK_EXTENSION
        val (base, ext) = if (isFolder) fileName to "" else FileNaming.split(fileName)
        val name = FileNaming.unique(base, ext) { exists(RemotePath.join(folder, it)) }
        val target = RemotePath.join(folder, name)
        file(folder).mkdirs()
        if (!file(path).renameTo(file(target))) throw NoteException("옮기지 못했습니다.")
        return target
    }

    fun duplicate(path: String): String {
        if (!exists(path)) throw NoteException("${RemotePath.name(path)}을(를) 찾을 수 없습니다.")
        val folder = RemotePath.parent(path)
        if (RemotePath.pathExtension(path) == FileNaming.NOTEBOOK_EXTENSION) {
            val title = FileNaming.split(RemotePath.name(path)).first + " 사본"
            val copy = NotebookPackage(file(path)).duplicate(file(folder), title)
            return RemotePath.join(folder, copy.dir.name)
        }
        val (base, ext) = FileNaming.split(RemotePath.name(path))
        val name = FileNaming.unique("$base 사본", ext) { exists(RemotePath.join(folder, it)) }
        val target = RemotePath.join(folder, name)
        file(path).copyRecursively(file(target))
        return target
    }

    /** Deletes an item; when `trash` is given it is moved there instead (restorable). */
    fun delete(path: String, trash: TrashBin? = null) {
        if (!exists(path)) return
        if (trash != null) trash.store(file(path), path) else file(path).deleteRecursively()
    }

    /** Copies an outside file into `folder`. */
    fun importFile(source: File, folder: String, preferredName: String? = null): String =
        importStream(source.inputStream(), preferredName ?: source.name, folder)

    fun importStream(input: java.io.InputStream, fileName: String, folder: String): String {
        val (base, ext) = FileNaming.split(FileNaming.sanitize(fileName))
        val name = FileNaming.unique(base, ext) { exists(RemotePath.join(folder, it)) }
        val target = RemotePath.join(folder, name)
        file(folder).mkdirs()
        input.use { inp -> file(target).outputStream().use { inp.copyTo(it) } }
        return target
    }
}

/** Deleted items, kept until emptied. One bin per library source. */
class TrashBin(val directory: File) {
    @Serializable
    data class Entry(
        val storedName: String,
        val originalPath: String,
        @Serializable(with = Iso8601Serializer::class) val deletedAt: Instant,
    ) {
        val displayName: String
            get() {
                val name = RemotePath.name(originalPath)
                return if (RemotePath.pathExtension(name) == FileNaming.NOTEBOOK_EXTENSION) FileNaming.split(name).first else name
            }
    }

    private val index get() = File(directory, "index.json")
    private val serializer = ListSerializer(Entry.serializer())

    init {
        directory.mkdirs()
    }

    fun entries(): List<Entry> = runCatching { NoteJson.decodeFromString(serializer, index.readText()) }
        .getOrDefault(emptyList()).sortedByDescending { it.deletedAt }

    private fun save(list: List<Entry>) = writeAtomically(index, NoteJson.encodeToString(serializer, list).toByteArray())

    fun file(entry: Entry) = File(directory, entry.storedName)

    fun store(item: File, originalPath: String) {
        val stored = "${newId()}-${item.name}"
        if (!item.renameTo(File(directory, stored))) {
            item.copyRecursively(File(directory, stored))
            item.deleteRecursively()
        }
        save(entries() + Entry(stored, originalPath, Instant.now()))
    }

    /** Moves the entry back into `library` (to its folder, or the root if that is gone). */
    fun restore(entry: Entry, library: LibraryFileSystem): String {
        var folder = RemotePath.parent(entry.originalPath)
        if (!library.exists(folder)) folder = "/"
        val (base, ext) = FileNaming.split(RemotePath.name(entry.originalPath))
        val name = FileNaming.unique(base, ext) { library.exists(RemotePath.join(folder, it)) }
        val target = RemotePath.join(folder, name)
        if (!file(entry).renameTo(library.file(target))) throw NoteException("복원하지 못했습니다.")
        save(entries().filter { it.storedName != entry.storedName })
        return target
    }

    fun remove(entry: Entry) {
        file(entry).deleteRecursively()
        save(entries().filter { it.storedName != entry.storedName })
    }

    fun empty() {
        entries().forEach { file(it).deleteRecursively() }
        save(emptyList())
    }
}
