package com.lemon7679.note.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.core.CoverStyle
import com.lemon7679.note.core.ElementFrame
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.NotebookDocument
import com.lemon7679.note.core.NotebookPackage
import com.lemon7679.note.core.NotebookPage
import com.lemon7679.note.core.PageBackground
import com.lemon7679.note.core.PageElement
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.PaperTemplate
import com.lemon7679.note.core.newId
import com.lemon7679.note.ink.BrushKind
import com.lemon7679.note.ink.EraserMode
import com.lemon7679.note.ink.InkPage
import com.lemon7679.note.ink.InkRGBA
import com.lemon7679.note.ink.InkSnapshot
import com.lemon7679.note.ink.StabilizerMode
import com.lemon7679.note.ink.StrokeBuilder
import com.lemon7679.note.render.NotebookPdfExporter
import com.lemon7679.note.render.PageImageRenderer
import com.lemon7679.note.render.PdfHandle
import com.lemon7679.note.ui.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Serializable
enum class EditorTool(val label: String) {
    PEN("펜"), HIGHLIGHTER("형광펜"), ERASER("지우개"), LASSO("올가미 선택"), TEXT("텍스트"), IMAGE("이미지"), LASER("레이저 포인터");

    /** Tools whose strokes go into the page. */
    val inks: Boolean get() = this == PEN || this == HIGHLIGHTER
}

val BrushKind.label: String
    get() = when (this) {
        BrushKind.BALLPOINT -> "볼펜"
        BrushKind.PENCIL -> "연필"
        BrushKind.HIGHLIGHTER -> "형광펜"
        BrushKind.FOUNTAIN_PEN -> "만년필"
        BrushKind.BRUSH_PEN -> "붓펜"
        BrushKind.CALLIGRAPHY -> "캘리그라피"
        BrushKind.MARKER -> "마커"
    }

/** Pens offered in the pen picker (the highlighter has its own tool). */
val PEN_KINDS = listOf(BrushKind.BALLPOINT, BrushKind.FOUNTAIN_PEN, BrushKind.PENCIL, BrushKind.BRUSH_PEN, BrushKind.CALLIGRAPHY, BrushKind.MARKER)

val BrushKind.defaultWidths: List<Double>
    get() = when (this) {
        BrushKind.BALLPOINT -> listOf(0.9, 1.4, 2.2)
        BrushKind.FOUNTAIN_PEN, BrushKind.PENCIL -> listOf(1.0, 1.6, 2.6)
        BrushKind.BRUSH_PEN -> listOf(1.6, 2.8, 4.5)
        BrushKind.CALLIGRAPHY -> listOf(2.0, 3.5, 5.5)
        BrushKind.MARKER -> listOf(2.5, 4.0, 6.0)
        BrushKind.HIGHLIGHTER -> listOf(8.0, 12.0, 18.0)
    }

/** The tool palette, shared by all notebooks. Same fields and defaults as the iPad app. */
@Serializable
data class ToolState(
    val tool: EditorTool = EditorTool.PEN,
    val lastInkTool: EditorTool = EditorTool.PEN,
    val penKind: Int = BrushKind.BALLPOINT.raw,
    val penColor: String = "#1C1C1E",
    /** Three width presets per pen kind (page points). */
    val penWidths: Map<Int, List<Double>> = emptyMap(),
    val penWidthIndex: Int = 1,
    val highlighterColor: String = "#FFE45C",
    val highlighterWidths: List<Double> = listOf(8.0, 12.0, 18.0),
    val highlighterWidthIndex: Int = 1,
    /** Highlighter snaps to a straight line when the pen rests. */
    val highlighterStraight: Boolean = true,
    val eraserPrecise: Boolean = true,
    /** Eraser diameter in screen dp. */
    val eraserSize: Double = 14.0,
    val eraseHighlighterOnly: Boolean = false,
    /** Draw-and-hold shapes. */
    val snapShapes: Boolean = true,
    /** Handwriting correction 0…1. */
    val correction: Double = 0.3,
    /** Extra stabilizer (moving average window, ms). 0 = off. */
    val stabilizer: Double = 0.0,
    /** Colors picked with the custom color picker (most recent first). */
    val customColors: List<String> = emptyList(),
) {
    val pen: BrushKind get() = BrushKind.of(penKind)
    val eraserMode: EraserMode get() = if (eraserPrecise) EraserMode.PRECISE else EraserMode.STROKE
    val currentPenWidths: List<Double> get() = penWidths[penKind] ?: pen.defaultWidths
    val penWidth: Double get() = currentPenWidths[penWidthIndex.coerceIn(0, currentPenWidths.size - 1)]
    val highlighterWidth: Double get() = highlighterWidths[highlighterWidthIndex.coerceIn(0, highlighterWidths.size - 1)]
    val activeColor: String get() = if (tool == EditorTool.HIGHLIGHTER) highlighterColor else penColor

    fun select(newTool: EditorTool) = copy(tool = newTool, lastInkTool = if (newTool.inks) newTool else lastInkTool)
    fun withColor(hex: String) = if (tool == EditorTool.HIGHLIGHTER) copy(highlighterColor = hex) else copy(penColor = hex)

    fun withWidth(width: Double, index: Int): ToolState = if (tool == EditorTool.HIGHLIGHTER) {
        copy(highlighterWidths = highlighterWidths.toMutableList().also { it[index] = width }, highlighterWidthIndex = index)
    } else {
        copy(penWidths = penWidths + (penKind to currentPenWidths.toMutableList().also { it[index] = width }), penWidthIndex = index)
    }

    fun rememberingCustomColor(hex: String) = copy(customColors = (listOf(hex) + customColors.filter { it != hex }).take(8))

    /** Configures the engine's stroke builder for the next stroke. */
    fun configure(builder: StrokeBuilder, viewScale: Float, highlighter: Boolean = tool == EditorTool.HIGHLIGHTER) {
        if (highlighter) {
            builder.setBrush(BrushKind.HIGHLIGHTER, InkRGBA.hex(highlighterColor), highlighterWidth.toFloat())
            builder.setSnapOnHold(highlighterStraight)
            builder.setCorrection(0f)
        } else {
            builder.setBrush(pen, InkRGBA.hex(penColor), penWidth.toFloat())
            builder.setSnapOnHold(snapShapes)
            builder.setCorrection(correction.toFloat())
        }
        builder.setStabilizer(if (stabilizer > 0) StabilizerMode.MOVING_AVERAGE else StabilizerMode.NONE, stabilizer.toFloat())
        builder.setViewScale(viewScale)
    }
}

/** What the canvas must react to when the model changes underneath it. */
interface CanvasControlling {
    fun pagesDidChange()
    fun inkDidChange(pageId: String, rect: RectF?)
    fun elementsDidChange(pageId: String)
    fun scrollToPage(index: Int, animated: Boolean)
    fun highlight(pageIndex: Int, rect: RectF)
    fun clearSelection()
}

/** One open notebook: document, lazily loaded ink pages, undo across pages, autosave. */
class EditorModel(val app: AppModel, val ref: LibraryRef, file: File) {
    private sealed class UndoEntry {
        class Ink(val pageId: String) : UndoEntry()
        class Document(val before: NotebookDocument, val after: NotebookDocument) : UndoEntry()
    }

    val pkg = NotebookPackage(file)
    var document by mutableStateOf(pkg.readDocument().let { if (it.pages.isEmpty()) it.copy(pages = listOf(it.makePage())) else it })
        private set
    var tools by mutableStateOf(app.loadTools())
        private set
    var currentPageIndex by mutableIntStateOf(document.lastPageIndex.coerceIn(0, document.pages.size - 1))
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var thumbnailRevision by mutableIntStateOf(0)
        private set
    var lastError by mutableStateOf<String?>(null)

    val pdf: PdfHandle? = document.pdf?.let { PdfHandle.open(pkg.assetFile(it.file)) }
    var canvas: CanvasControlling? = null

    private val pages = HashMap<String, InkPage>()
    private val dirtyInk = HashSet<String>()
    private var documentDirty = false
    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()
    private var autosave: Job? = null
    private val thumbnails = HashMap<String, Bitmap>()

    val title: String get() = document.title
    val pageCount: Int get() = document.pages.size
    val currentPage: NotebookPage get() = document.pages[currentPageIndex.coerceIn(0, document.pages.size - 1)]

    fun updateTools(change: (ToolState) -> ToolState) {
        tools = change(tools)
        app.saveTools(tools)
    }

    // Ink pages

    fun inkPage(id: String): InkPage = pages.getOrPut(id) {
        val info = document.pages.firstOrNull { it.id == id }
        InkPage((info?.size?.width ?: 595.0).toFloat(), (info?.size?.height ?: 842.0).toFloat()).also { ink ->
            pkg.readInk(id)?.let { ink.load(it) }
        }
    }

    /** Call after every ink edit on a page (stroke, erase, lasso operation). */
    fun noteInkEdit(id: String) {
        undoStack += UndoEntry.Ink(id)
        if (undoStack.size > 500) undoStack.removeAt(0)
        redoStack.clear()
        dirtyInk += id
        thumbnails.remove(id)
        updateUndoFlags()
        scheduleSave()
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        canvas?.clearSelection()
        when (entry) {
            is UndoEntry.Ink -> {
                val ink = inkPage(entry.pageId)
                if (ink.undo()) {
                    dirtyInk += entry.pageId
                    thumbnails.remove(entry.pageId)
                    canvas?.inkDidChange(entry.pageId, ink.takeDirty())
                }
            }
            is UndoEntry.Document -> applyDocument(entry.before)
        }
        redoStack += entry
        updateUndoFlags()
        scheduleSave()
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        canvas?.clearSelection()
        when (entry) {
            is UndoEntry.Ink -> {
                val ink = inkPage(entry.pageId)
                if (ink.redo()) {
                    dirtyInk += entry.pageId
                    thumbnails.remove(entry.pageId)
                    canvas?.inkDidChange(entry.pageId, ink.takeDirty())
                }
            }
            is UndoEntry.Document -> applyDocument(entry.after)
        }
        undoStack += entry
        updateUndoFlags()
        scheduleSave()
    }

    // Document changes (pages, paper, elements) — undoable

    private fun changeDocument(change: (NotebookDocument) -> NotebookDocument) {
        val before = document
        val after = change(before)
        if (after == before) return
        undoStack += UndoEntry.Document(before, after)
        redoStack.clear()
        applyDocument(after)
        updateUndoFlags()
        scheduleSave()
    }

    private fun applyDocument(doc: NotebookDocument) {
        val old = document
        val structureChanged = doc.pages.map { it.id } != old.pages.map { it.id } ||
            doc.pages.zip(old.pages).any { (a, b) -> a.size != b.size || a.paper != b.paper || a.background != b.background }
        val elementPages = doc.pages.zip(old.pages).filter { (a, b) -> a.elements != b.elements }.map { it.first.id }
        // Pages coming back (undo of a delete) must be written again: saving pruned their ink files.
        val previous = old.pages.map { it.id }.toSet()
        for (p in doc.pages) if (p.id !in previous && pages.containsKey(p.id)) dirtyInk += p.id
        document = doc
        documentDirty = true
        currentPageIndex = currentPageIndex.coerceAtMost(doc.pages.size - 1)
        if (structureChanged) {
            thumbnails.clear()
            canvas?.pagesDidChange()
        } else {
            for (id in elementPages) {
                thumbnails.remove(id)
                canvas?.elementsDidChange(id)
            }
        }
        thumbnailRevision++
    }

    fun addPage(after: Int, template: NotebookPage? = null) {
        val source = template ?: document.pages.getOrNull(after)
        var page = document.makePage(source)
        if (source?.background?.pdfPageIndex != null) {
            page = page.copy(paper = if (document.defaultPaper.template == PaperTemplate.BLANK) PaperStyle(PaperTemplate.BLANK) else document.defaultPaper)
        }
        val insertAt = (after + 1).coerceIn(0, document.pages.size)
        changeDocument { it.copy(pages = it.pages.toMutableList().apply { add(insertAt, page) }) }
        currentPageIndex = insertAt
        canvas?.scrollToPage(insertAt, true)
    }

    fun insertPage(before: Int) = addPage(before - 1, document.pages.getOrNull(before))

    fun duplicatePage(index: Int) {
        val original = document.pages.getOrNull(index) ?: return
        val copy = original.copy(id = newId(), elements = original.elements.map { it.copy(id = newId()) })
        inkPage(original.id).save()?.let { data ->
            pages[copy.id] = InkPage(copy.size.width.toFloat(), copy.size.height.toFloat()).also { it.load(data) }
            dirtyInk += copy.id
        }
        changeDocument { it.copy(pages = it.pages.toMutableList().apply { add(index + 1, copy) }) }
    }

    fun deletePage(index: Int) {
        if (document.pages.size <= 1 || index !in document.pages.indices) return
        inkPage(document.pages[index].id)   // keep the ink in memory so undo can restore it
        changeDocument { it.copy(pages = it.pages.toMutableList().apply { removeAt(index) }) }
    }

    fun movePage(from: Int, to: Int) {
        if (from !in document.pages.indices || to !in document.pages.indices || from == to) return
        changeDocument { d -> d.copy(pages = d.pages.toMutableList().apply { add(to, removeAt(from)) }) }
        currentPageIndex = to
    }

    fun toggleBookmark(index: Int) {
        if (index !in document.pages.indices) return
        changeDocument { d -> d.copy(pages = d.pages.mapIndexed { i, p -> if (i == index) p.copy(bookmarked = !p.bookmarked) else p }) }
    }

    fun setPaper(paper: PaperStyle, forAll: Boolean) = changeDocument { d ->
        if (forAll) d.copy(defaultPaper = paper, pages = d.pages.map { if (it.background == PageBackground.PAPER) it.copy(paper = paper) else it })
        else d.copy(pages = d.pages.mapIndexed { i, p -> if (i == currentPageIndex) p.copy(paper = paper) else p })
    }

    fun rename(title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) changeDocument { it.copy(title = clean) }
    }

    fun setCover(hex: String?) = changeDocument { it.copy(cover = CoverStyle(hex)) }

    // Elements

    fun addElement(element: PageElement, pageId: String) = changeDocument { d ->
        d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(elements = it.elements + element) else it })
    }

    fun updateElement(element: PageElement, pageId: String) = changeDocument { d ->
        d.copy(pages = d.pages.map { p -> if (p.id == pageId) p.copy(elements = p.elements.map { if (it.id == element.id) element else it }) else p })
    }

    fun removeElement(elementId: String, pageId: String) = changeDocument { d ->
        d.copy(pages = d.pages.map { p -> if (p.id == pageId) p.copy(elements = p.elements.filter { it.id != elementId }) else p })
    }

    fun addImage(bytes: ByteArray, pageId: String, cx: Float, cy: Float) {
        val page = document.pages.firstOrNull { it.id == pageId } ?: return
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) throw IllegalArgumentException("이미지를 읽을 수 없습니다.")
            // Store as JPEG (like the iPad app), at most 2400 px on the long side.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val name = pkg.addAsset(out.toByteArray(), "jpg")
            val maxW = page.size.width * 0.6
            val maxH = page.size.height * 0.5
            val scale = minOf(maxW / bmp.width, maxH / bmp.height, 1.0)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val frame = ElementFrame((cx - w / 2).coerceIn(0.0, page.size.width - w), (cy - h / 2).coerceIn(0.0, page.size.height - h), w, h)
            addElement(PageElement(kind = PageElement.Kind.IMAGE, frame = frame, asset = name), pageId)
        } catch (e: Exception) {
            lastError = e.message ?: "이미지를 넣지 못했습니다."
        }
    }

    // Navigation

    fun go(index: Int) {
        if (index !in document.pages.indices) return
        currentPageIndex = index
        canvas?.scrollToPage(index, true)
    }

    val bookmarkedIndices: List<Int> get() = document.pages.indices.filter { document.pages[it].bookmarked }

    data class SearchHit(val pageIndex: Int, val rect: RectF, val context: String)

    /** Text search in the underlying PDF (Android 15+). */
    fun search(text: String): List<SearchHit> {
        val q = text.trim()
        val pdf = pdf ?: return emptyList()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        document.pages.forEachIndexed { i, page ->
            val idx = page.background.pdfPageIndex ?: return@forEachIndexed
            val native = pdf.pageSizes.getOrNull(idx) ?: return@forEachIndexed
            val sx = (page.size.width / native.width).toFloat()
            val sy = (page.size.height / native.height).toFloat()
            for (r in pdf.search(idx, q)) hits += SearchHit(i, RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy), q)
        }
        return hits
    }

    fun show(hit: SearchHit) {
        go(hit.pageIndex)
        canvas?.highlight(hit.pageIndex, hit.rect)
    }

    // Thumbnails

    /** Page-list thumbnail, rendered off the main thread from an ink snapshot. */
    suspend fun thumbnail(page: NotebookPage, width: Int = 180): Bitmap? {
        thumbnails[page.id]?.let { return it }
        val snapshot = inkPage(page.id).snapshot()
        val bmp = try {
            withContext(Dispatchers.Default) { runCatching { PageImageRenderer.image(page, snapshot, pdf, pkg, width) }.getOrNull() }
        } finally {
            snapshot.release()
        } ?: return null
        thumbnails[page.id] = bmp
        return bmp
    }

    // Saving
    //
    // What to write is captured on the main thread — ink snapshots (pointer copies, no
    // serialization) and the immutable document — and written on one background writer
    // thread: serializing big pages, file I/O and the cover thumbnail never stall
    // drawing. The writer runs saves strictly in capture order, and a synchronous save
    // (pause, close, export) waits for the ones queued before it, so an older autosave
    // can never land after newer data.

    private val writer = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "note-save").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }   // no idle thread left behind per opened notebook

    private class SaveWork(
        val ink: List<Pair<String, InkSnapshot>>,
        val document: NotebookDocument,
        val cover: Pair<NotebookPage, InkSnapshot>?,
    ) {
        fun release() {
            ink.forEach { it.second.release() }
            cover?.second?.release()
        }
    }

    private fun scheduleSave() {
        autosave?.cancel()
        autosave = app.scope.launch {
            delay(2000)
            val work = captureSave() ?: return@launch
            val error = withContext(Dispatchers.IO) { writer.submit<String?> { write(work) }.get() }
            if (error != null) saveFailed(work, error)
            thumbnailRevision++
        }
    }

    val hasUnsavedChanges: Boolean get() = documentDirty || dirtyInk.isNotEmpty()

    /** Saves now and waits for it (and for any autosave still being written). */
    fun save() {
        autosave?.cancel()
        val work = captureSave() ?: run {
            // Nothing new, but an autosave may still be writing: wait for it.
            runCatching { writer.submit(Runnable {}).get() }
            return
        }
        val error = runCatching { writer.submit<String?> { write(work) }.get() }.getOrElse { it.message ?: "" }
        if (error != null) saveFailed(work, error)
    }

    private fun captureSave(): SaveWork? {
        if (!hasUnsavedChanges) return null
        val ink = dirtyInk.filter { document.pageIndex(it) != null }.mapNotNull { id -> pages[id]?.let { id to it.snapshot() } }
        dirtyInk.clear()
        document = document.copy(modified = Instant.now(), lastPageIndex = currentPageIndex)
        documentDirty = false
        val cover = document.pages.firstOrNull()?.let { it to inkPage(it.id).snapshot() }
        return SaveWork(ink, document, cover)
    }

    /** On the writer thread. Returns an error message, or null. */
    private fun write(work: SaveWork): String? = try {
        for ((id, snapshot) in work.ink) snapshot.save()?.let { pkg.writeInk(it, id) }
        pkg.write(work.document)
        pkg.pruneInk(work.document)
        work.cover?.let { (page, snapshot) -> writeCoverThumbnail(page, snapshot) }
        null
    } catch (e: Exception) {
        "저장하지 못했습니다. ${e.message ?: ""}"
    } finally {
        work.release()
    }

    private fun saveFailed(work: SaveWork, error: String) {
        lastError = error
        // Try again with the next save.
        dirtyInk += work.ink.map { it.first }
        documentDirty = true
    }

    private fun writeCoverThumbnail(page: NotebookPage, ink: InkSnapshot) {
        runCatching {
            val bmp = PageImageRenderer.image(page, ink, pdf, pkg, 360)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            pkg.writeThumbnail(out.toByteArray())
            ThumbnailCache.invalidate(pkg.dir)
        }
    }

    /** Saves, hands a flattened PDF to the app when the account wants PDF copies, and frees the engine pages. */
    /** True once [close] has run: the ink pages are disposed, views must stop using them. */
    var closed = false
        private set

    fun close() {
        if (document.lastPageIndex != currentPageIndex) documentDirty = true
        save()
        closed = true
        app.documentClosed(OpenDocument(ref, pkg.dir, OpenDocument.Kind.NOTEBOOK)) { runCatching { exportPdf() }.getOrNull() }
        pages.values.forEach { it.dispose() }
        pages.clear()
        pdf?.close()
    }

    // Export

    fun exportPdf(): File {
        save()
        val out = File(app.paths.exports, FileNaming.sanitize(document.title) + ".pdf")
        out.delete()
        val inks = document.pages.associate { it.id to inkPage(it.id) }
        NotebookPdfExporter.export(document, pkg, inks, pdf, out)
        return out
    }

    fun exportPageImage(index: Int): File? {
        val page = document.pages.getOrNull(index) ?: return null
        return runCatching {
            val bmp = PageImageRenderer.image(page, inkPage(page.id), pdf, pkg, (page.size.width * 3).toInt())
            val out = File(app.paths.exports, "${FileNaming.sanitize(document.title)}-${index + 1}.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        }.getOrNull()
    }
}

private val toolsKey = "tools"

fun AppModel.loadTools(): ToolState =
    context.getSharedPreferences("note", 0).getString(toolsKey, null)?.let {
        runCatching { com.lemon7679.note.core.NoteJson.decodeFromString(ToolState.serializer(), it) }.getOrNull()
    } ?: ToolState()

fun AppModel.saveTools(t: ToolState) {
    context.getSharedPreferences("note", 0).edit()
        .putString(toolsKey, com.lemon7679.note.core.NoteJson.encodeToString(ToolState.serializer(), t)).apply()
}
