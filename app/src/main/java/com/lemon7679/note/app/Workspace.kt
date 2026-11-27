package com.lemon7679.note.app

import com.lemon7679.note.editor.CanvasView
import com.lemon7679.note.editor.EditorModel

/**
 * Where a document opens in the workspace (the layer over the library while documents are
 * open: up to two panes side by side plus floating windows). Same rules as the iPad app.
 */
sealed class DocumentPlacement {
    /** From the library: the only pane. While documents are already open, a floating window. */
    data object Main : DocumentPlacement()
    /** In place of an open document (e.g. "필기 시작" in a PDF pane). */
    data class Replacing(val id: String) : DocumentPlacement()
    /** Split screen next to this pane (replaces the other pane when there are two). */
    data class Beside(val id: String) : DocumentPlacement()
    /** A floating window over the panes. */
    data object Floating : DocumentPlacement()

    val title: String
        get() = when (this) {
            is Beside -> "옆에 열기"
            Floating -> "떠 있는 창으로 열기"
            is Replacing -> "이 창에 열기"
            Main -> "문서 열기"
        }
}

/** How a pane shows its document. */
enum class PaneRole {
    /** Alone on the screen. */
    SINGLE,
    /** Left / right half of the split screen. */
    LEFT, RIGHT,
    /** Inside a floating window. */
    FLOATING;

    /** The leading button closes every document and goes back to the library. */
    val closesWorkspace: Boolean get() = this == SINGLE || this == LEFT
}

/** A floating window's frame in dp, inside the workspace. */
data class WindowFrame(val x: Float, val y: Float, val width: Float, val height: Float)

/** A document in a floating window. */
data class FloatingDocument(
    val document: OpenDocument,
    /** Null until moved or resized (a default spot is used). */
    val frame: WindowFrame? = null,
    val collapsed: Boolean = false,
    /** Stacking order (higher is in front). */
    val z: Int,
    /** Offsets the default spot so new windows don't cover each other exactly. */
    val cascade: Int,
) {
    val id: String get() = document.id
}

/**
 * Editor state of the open documents, kept while a document moves between panes and
 * floating windows (the composables and canvas views are rebuilt; the models are not).
 */
class DocumentSessions {
    private val editors = HashMap<String, Result<EditorModel>>()
    private val pdfPages = HashMap<String, Int>()

    /** The notebook's editor, made on first use. Null once the document has been closed. */
    fun editor(app: AppModel, doc: OpenDocument): Result<EditorModel>? {
        editors[doc.id]?.let { return it }
        if (!app.isOpen(doc.id)) return null
        return runCatching { EditorModel(app, doc.ref, doc.file) }.also { editors[doc.id] = it }
    }

    fun loadedEditor(id: String): EditorModel? = editors[id]?.getOrNull()

    val openEditors: Collection<EditorModel> get() = editors.values.mapNotNull { it.getOrNull() }

    /** Page a PDF reader was on, so it comes back there in another pane or window. */
    fun pdfPage(id: String): Int = pdfPages[id] ?: 0

    fun setPdfPage(id: String, page: Int) { pdfPages[id] = page }

    fun discard(id: String) {
        editors.remove(id)
        pdfPages.remove(id)
    }
}

const val MAX_FLOATING_WINDOWS = 3
/** Panes narrower than this would not fit the editor's tool row. */
const val MIN_PANE_WIDTH_DP = 320f

val AppModel.isWorkspaceOpen: Boolean get() = panes.isNotEmpty()
val AppModel.openDocumentCount: Int get() = panes.size + floating.size
val AppModel.openNotebookCount: Int
    get() = panes.count { it.kind == OpenDocument.Kind.NOTEBOOK } + floating.count { it.document.kind == OpenDocument.Kind.NOTEBOOK }

fun AppModel.isOpen(id: String): Boolean = panes.any { it.id == id } || floating.any { it.id == id }

fun AppModel.openDocument(id: String): OpenDocument? =
    panes.firstOrNull { it.id == id } ?: floating.firstOrNull { it.id == id }?.document

/** The document the back button closes: the pane or window last touched. */
val AppModel.backTargetId: String?
    get() = focusedDocumentId?.takeIf { isOpen(it) } ?: panes.firstOrNull()?.id

/**
 * Shows [doc]; if it is already open somewhere, brings that one forward instead
 * (a notebook is never edited in two places at once).
 */
fun AppModel.present(doc: OpenDocument, placement: DocumentPlacement = DocumentPlacement.Main) {
    if (isOpen(doc.id)) {
        focus(doc.id, reveal = true)
        return
    }
    if (doc.kind == OpenDocument.Kind.NOTEBOOK) openNotebooks.getOrPut(doc.ref.source) { HashSet() }.add(doc.ref.path)
    when (placement) {
        DocumentPlacement.Main, DocumentPlacement.Floating ->
            if (panes.isEmpty()) panes = listOf(doc) else addFloating(doc)
        is DocumentPlacement.Replacing -> replaceDocument(placement.id, doc)
        is DocumentPlacement.Beside -> when (panes.size) {
            0 -> panes = listOf(doc)
            1 -> {
                panes = panes + doc
                splitFraction = 0.5f
            }
            else -> replaceDocument((panes.firstOrNull { it.id != placement.id } ?: panes[1]).id, doc)
        }
    }
    focusedDocumentId = doc.id
}

private fun AppModel.addFloating(doc: OpenDocument) {
    if (floating.size >= MAX_FLOATING_WINDOWS) floating.minByOrNull { it.z }?.let { closeDocument(it.id) }
    floatingCounter += 1
    floating = floating + FloatingDocument(doc, z = floatingCounter, cascade = floatingCounter)
}

private fun AppModel.replaceDocument(id: String, doc: OpenDocument) {
    val old = openDocument(id)
    if (old == null) {
        if (panes.isEmpty()) panes = listOf(doc) else addFloating(doc)
        return
    }
    if (panes.any { it.id == id }) {
        panes = panes.map { if (it.id == id) doc else it }
    } else {
        floating = floating.map { if (it.id == id) it.copy(document = doc, collapsed = false) else it }
    }
    if (focusedDocumentId == id) focusedDocumentId = doc.id
    finishClosing(old)
}

/**
 * Closes one document (saving it). The other documents stay; a floating window moves into
 * the panes when the last pane closes.
 */
fun AppModel.closeDocument(id: String) {
    val doc = openDocument(id) ?: return
    removeFromLayout(id)
    finishClosing(doc)
}

/** Closes every open document and goes back to the library. */
fun AppModel.closeWorkspace() {
    // Floating windows first, so none of them is promoted into a pane on the way.
    floating.forEach { closeDocument(it.id) }
    panes.reversed().forEach { closeDocument(it.id) }
}

private fun AppModel.finishClosing(doc: OpenDocument) {
    val editor = sessions.loadedEditor(doc.id)
    if (editor != null) {
        // The canvas stops drawing before the model frees its pages.
        (editor.canvas as? CanvasView)?.dispose()
        editor.close()   // saves, then calls documentClosed
    } else {
        documentClosed(doc)
    }
    sessions.discard(doc.id)
}

fun AppModel.removeFromLayout(id: String) {
    if (panes.any { it.id == id }) {
        panes = panes.filter { it.id != id }
        if (panes.isEmpty()) {
            floating.maxByOrNull { it.z }?.let { front ->
                floating = floating.filter { it.id != front.id }
                panes = listOf(front.document)
            }
        }
    } else {
        floating = floating.filter { it.id != id }
    }
    val focused = focusedDocumentId
    if (focused == id || (focused != null && !isOpen(focused))) focusedDocumentId = frontmostId()
}

private fun AppModel.frontmostId(): String? =
    floating.filter { !it.collapsed }.maxByOrNull { it.z }?.id ?: panes.firstOrNull()?.id

/** The pane or window the user last touched; a floating window also comes to the front. */
fun AppModel.focus(id: String, reveal: Boolean = false) {
    if (focusedDocumentId != id) focusedDocumentId = id
    val window = floating.firstOrNull { it.id == id } ?: return
    val top = floating.maxOf { it.z }
    val raise = window.z != top
    if (raise || (reveal && window.collapsed)) {
        if (raise) floatingCounter += 1
        val z = if (raise) floatingCounter else window.z
        floating = floating.map { if (it.id == id) it.copy(z = z, collapsed = if (reveal) false else it.collapsed) else it }
    }
}

fun AppModel.requestDocument(placement: DocumentPlacement) {
    documentPicker = placement
}

fun AppModel.swapPanes() {
    if (panes.size != 2) return
    panes = panes.reversed()
    splitFraction = 1f - splitFraction
}

/** A split pane becomes a floating window (the other pane fills the screen). */
fun AppModel.moveToFloating(id: String) {
    if (panes.size != 2) return
    val doc = panes.firstOrNull { it.id == id } ?: return
    panes = panes.filter { it.id != id }
    floatingCounter += 1
    floating = floating + FloatingDocument(doc, z = floatingCounter, cascade = floatingCounter)
    focusedDocumentId = id
}

/**
 * A floating window joins the split screen; with two panes already, it trades places with
 * the right one (which takes over the window).
 */
fun AppModel.moveToSplit(id: String) {
    val window = floating.firstOrNull { it.id == id } ?: return
    if (panes.size < 2) {
        floating = floating.filter { it.id != id }
        panes = panes + window.document
        splitFraction = 0.5f
    } else {
        val right = panes[1]
        panes = listOf(panes[0], window.document)
        floating = floating.map { if (it.id == id) it.copy(document = right, collapsed = false) else it }
    }
    focusedDocumentId = id
}

fun AppModel.setCollapsed(id: String, collapsed: Boolean) {
    floating = floating.map { if (it.id == id) it.copy(collapsed = collapsed) else it }
    if (collapsed) {
        if (focusedDocumentId == id) focusedDocumentId = frontmostId()
    } else {
        focus(id)
    }
}

fun AppModel.setFloatingFrame(id: String, frame: WindowFrame) {
    floating = floating.map { if (it.id == id) it.copy(frame = frame) else it }
}
