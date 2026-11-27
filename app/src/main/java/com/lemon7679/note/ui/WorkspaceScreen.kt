package com.lemon7679.note.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.DocumentPlacement
import com.lemon7679.note.app.FloatingDocument
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.MIN_PANE_WIDTH_DP
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.app.PaneRole
import com.lemon7679.note.app.WindowFrame
import com.lemon7679.note.app.closeDocument
import com.lemon7679.note.app.closeWorkspace
import com.lemon7679.note.app.focus
import com.lemon7679.note.app.isOpen
import com.lemon7679.note.app.moveToFloating
import com.lemon7679.note.app.moveToSplit
import com.lemon7679.note.app.openDocumentCount
import com.lemon7679.note.app.requestDocument
import com.lemon7679.note.app.setCollapsed
import com.lemon7679.note.app.setFloatingFrame
import com.lemon7679.note.app.swapPanes
import com.lemon7679.note.core.LibraryEntry
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.editor.NotebookEditorHost

/** Below this width the two panes are stacked (top / bottom) instead of side by side. */
private val SIDE_BY_SIDE_MIN_WIDTH = 600.dp
private val DIVIDER = 12.dp
private const val FLOATING_MIN_WIDTH = 320f
private const val FLOATING_MIN_HEIGHT = 300f

/**
 * The open documents over the library: one pane, or two split (side by side, stacked on a
 * phone), plus floating windows that can be moved, resized and minimized.
 */
@Composable
fun WorkspaceScreen(app: AppModel) {
    val c = NoteTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.desk).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val boundsW = maxWidth.value
        val boundsH = maxHeight.value
        val stacked = maxWidth < SIDE_BY_SIDE_MIN_WIDTH
        val total = if (stacked) boundsH else boundsW
        val minFraction = ((if (stacked) 200f else MIN_PANE_WIDTH_DP) / total).coerceAtMost(0.5f)
        fun clamp(f: Float) = f.coerceIn(minFraction, 1f - minFraction)
        val panes = app.panes
        val split = panes.size == 2
        val fraction = clamp(app.splitFraction)
        var preview by remember { mutableStateOf<Float?>(null) }

        val pane: @Composable (Int, OpenDocument, Modifier) -> Unit = { index, doc, modifier ->
            val role = if (!split) PaneRole.SINGLE else if (index == 0) PaneRole.LEFT else PaneRole.RIGHT
            Box(modifier) { DocumentPane(app, doc, role) }
        }
        val divider: @Composable () -> Unit = {
            SplitDivider(stacked, total, fraction, ::clamp, onPreview = { preview = it }) { app.splitFraction = it }
        }
        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                panes.forEachIndexed { index, doc ->
                    key(doc.id) {
                        if (index == 1) divider()
                        pane(index, doc, Modifier.fillMaxWidth().weight(if (!split) 1f else if (index == 0) fraction else 1f - fraction))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                panes.forEachIndexed { index, doc ->
                    key(doc.id) {
                        if (index == 1) divider()
                        pane(index, doc, Modifier.fillMaxHeight().weight(if (!split) 1f else if (index == 0) fraction else 1f - fraction))
                    }
                }
            }
        }
        // Where the divider will land (the panes resize when it is let go).
        preview?.let { f ->
            val at = (total - DIVIDER.value) * f + DIVIDER.value / 2 - 1.5f
            Box((if (stacked) Modifier.offset(y = at.dp).fillMaxWidth().height(3.dp) else Modifier.offset(x = at.dp).fillMaxHeight().width(3.dp))
                .zIndex(5f).background(c.accent))
        }
        app.floating.forEach { window ->
            key(window.id) { FloatingWindow(app, window, boundsW, boundsH) }
        }
    }
    app.documentPicker?.let { placement ->
        DocumentPickerSheet(app, placement) { app.documentPicker = null }
    }
}

/** A notebook editor or a PDF reader in a pane or floating window. */
@Composable
private fun DocumentPane(app: AppModel, doc: OpenDocument, role: PaneRole) {
    // The live-stroke surface sits above the whole window: only the frontmost floating
    // window uses it, and panes only while no window is open over them.
    val visible = app.floating.filter { !it.collapsed }
    val liveInk = if (role == PaneRole.FLOATING) visible.maxByOrNull { it.z }?.id == doc.id else visible.isEmpty()
    Box(Modifier.fillMaxSize().focusOnPress(doc.id) { app.focus(doc.id) }) {
        when (doc.kind) {
            OpenDocument.Kind.NOTEBOOK -> NotebookEditorHost(app, doc, role, liveInk)
            OpenDocument.Kind.PDF -> PdfReaderScreen(app, doc, role)
        }
    }
}

/** Calls [onPress] when a finger or pen goes down anywhere inside, without taking the touch. */
private fun Modifier.focusOnPress(key: Any, onPress: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPress()
    }
}

/** The bar between the panes: drag (a line shows where it goes), double tap for half and half. */
@Composable
private fun SplitDivider(
    stacked: Boolean,
    total: Float,
    fraction: Float,
    clamp: (Float) -> Float,
    onPreview: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
) {
    val c = NoteTheme.colors
    val current by rememberUpdatedState(fraction)
    val density = LocalDensity.current.density
    var dragging by remember { mutableStateOf(false) }
    Box(
        (if (stacked) Modifier.fillMaxWidth().height(DIVIDER) else Modifier.fillMaxHeight().width(DIVIDER))
            .background(c.surface)
            .pointerInput(stacked, total) {
                var f = 0f
                detectDragGestures(
                    onDragStart = { f = current; dragging = true; onPreview(f) },
                    onDragEnd = { dragging = false; onPreview(null); onCommit(clamp(f)) },
                    onDragCancel = { dragging = false; onPreview(null) },
                ) { change, amount ->
                    change.consume()
                    f += (if (stacked) amount.y else amount.x) / density / (total - DIVIDER.value)
                    f = clamp(f)
                    onPreview(f)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onCommit(0.5f) }) }
            .semantics { contentDescription = "분할 화면 크기 · 두 번 탭하면 반반" },
        contentAlignment = Alignment.Center,
    ) {
        Box((if (stacked) Modifier.fillMaxWidth().height(1.dp) else Modifier.fillMaxHeight().width(1.dp)).background(c.separator))
        Box((if (stacked) Modifier.size(44.dp, 5.dp) else Modifier.size(5.dp, 44.dp)).clip(CircleShape)
            .background(if (dragging) c.accent else c.offline))
    }
}

// Floating windows

/** Lets the title bar inside a floating window move the window. */
class FloatingMover(val onDrag: (Offset) -> Unit, val onEnd: () -> Unit)

val LocalFloatingMover = compositionLocalOf<FloatingMover?> { null }

private fun clampFrame(f: WindowFrame, boundsW: Float, boundsH: Float): WindowFrame {
    val w = f.width.coerceIn(minOf(FLOATING_MIN_WIDTH, boundsW), boundsW)
    val h = f.height.coerceIn(minOf(FLOATING_MIN_HEIGHT, boundsH), boundsH)
    return WindowFrame(f.x.coerceIn(0f, maxOf(boundsW - w, 0f)), f.y.coerceIn(0f, maxOf(boundsH - h, 0f)), w, h)
}

/** A default spot on the right, cascading for each new window. */
private fun defaultFrame(cascade: Int, boundsW: Float, boundsH: Float): WindowFrame {
    val w = (boundsW * 0.42f).coerceIn(FLOATING_MIN_WIDTH, 460f)
    val h = (boundsH * 0.62f).coerceIn(FLOATING_MIN_HEIGHT, 620f)
    val step = ((cascade - 1) % 4) * 28f
    return WindowFrame(boundsW - w - 24f - step, 64f + step, w, h)
}

@Composable
private fun FloatingWindow(app: AppModel, window: FloatingDocument, boundsW: Float, boundsH: Float) {
    val c = NoteTheme.colors
    val density = LocalDensity.current.density
    val frame = clampFrame(window.frame ?: defaultFrame(window.cascade, boundsW, boundsH), boundsW, boundsH)
    val currentFrame by rememberUpdatedState(frame)
    var move by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf<Offset?>(null) }
    val shape = RoundedCornerShape(NoteTheme.Radius.card)
    val focused = app.focusedDocumentId == window.id
    val mover = FloatingMover(
        onDrag = { delta ->
            app.focus(window.id)
            move += delta / density
        },
        onEnd = {
            val f = currentFrame
            app.setFloatingFrame(window.id, clampFrame(f.copy(x = f.x + move.x, y = f.y + move.y), boundsW, boundsH))
            move = Offset.Zero
        },
    )
    Box(Modifier.zIndex(10f + window.z).offset((frame.x + move.x).dp, (frame.y + move.y).dp)) {
        if (window.collapsed) {
            CollapsedChip(app, window, mover)
        } else {
            Box(Modifier.size(frame.width.dp, frame.height.dp).shadow(16.dp, shape).clip(shape).background(c.background)
                .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.controlBorder, shape)) {
                CompositionLocalProvider(LocalFloatingMover provides mover) {
                    DocumentPane(app, window.document, PaneRole.FLOATING)
                }
            }
            resize?.let { r ->
                val t = clampFrame(frame.copy(width = frame.width + r.x, height = frame.height + r.y), boundsW - frame.x, boundsH - frame.y)
                Box(Modifier.size(t.width.dp, t.height.dp).border(2.dp, c.accent, shape).background(c.accentSoft.copy(alpha = 0.25f), shape))
            }
            // Bottom-right corner: drag to resize (an outline shows the new size until let go).
            Box(Modifier.offset((frame.width - 44f).dp, (frame.height - 44f).dp).size(44.dp)
                .pointerInput(window.id) {
                    detectDragGestures(
                        onDragStart = { app.focus(window.id); resize = Offset.Zero },
                        onDragEnd = {
                            val r = resize ?: Offset.Zero
                            val f = currentFrame
                            app.setFloatingFrame(window.id, clampFrame(f.copy(width = f.width + r.x, height = f.height + r.y), boundsW, boundsH))
                            resize = null
                        },
                        onDragCancel = { resize = null },
                    ) { change, amount ->
                        change.consume()
                        resize = (resize ?: Offset.Zero) + amount / density
                    }
                }
                .semantics { contentDescription = "창 크기 조절" },
                contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.padding(6.dp).size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.surface.copy(alpha = 0.92f))
                    .border(1.dp, c.separator, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.OpenInFull, null, Modifier.size(14.dp), tint = c.iconMuted)
                }
            }
        }
    }
}

/** A minimized window: a small chip where the window was. Tap to open it again, drag to move it. */
@Composable
private fun CollapsedChip(app: AppModel, window: FloatingDocument, mover: FloatingMover) {
    val c = NoteTheme.colors
    val currentMover by rememberUpdatedState(mover)
    Row(Modifier.shadow(10.dp, CircleShape).clip(CircleShape).background(c.surface).border(1.dp, c.controlBorder, CircleShape)
        .pointerInput(window.id) {
            detectDragGestures(onDragEnd = { currentMover.onEnd() }, onDragCancel = { currentMover.onEnd() }) { change, amount ->
                change.consume()
                currentMover.onDrag(amount)
            }
        }
        .clickable { app.setCollapsed(window.id, false) }
        .height(44.dp).padding(horizontal = 14.dp)
        .semantics { contentDescription = "${documentTitle(window.document)}, 최소화된 창 · 탭하면 다시 엽니다" },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(documentIcon(window.document), null, Modifier.size(18.dp), tint = c.accent)
        Spacer(Modifier.width(8.dp))
        Text(documentTitle(window.document), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.OpenInFull, null, Modifier.size(14.dp), tint = c.iconMuted)
    }
}

private fun documentTitle(doc: OpenDocument): String {
    val name = RemotePath.name(doc.ref.path)
    return if (doc.kind == OpenDocument.Kind.NOTEBOOK) com.lemon7679.note.core.FileNaming.split(name).first else name
}

private fun documentIcon(doc: OpenDocument): ImageVector =
    if (doc.kind == OpenDocument.Kind.NOTEBOOK) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.PictureAsPdf

/**
 * Title bar of a floating window: drag to move; window menu, minimize and close.
 * [trailing] holds the document's own buttons (undo/redo, search).
 */
@Composable
fun FloatingHeader(app: AppModel, documentId: String, title: String, icon: ImageVector, trailing: @Composable RowScope.() -> Unit) {
    val c = NoteTheme.colors
    val mover by rememberUpdatedState(LocalFloatingMover.current)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).background(c.surface).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { mover?.onEnd() }, onDragCancel = { mover?.onEnd() }) { change, amount ->
                    change.consume()
                    mover?.onDrag(amount)
                }
            }
            .semantics { contentDescription = "$title · 끌어서 창을 옮깁니다" }
            .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DragIndicator, null, Modifier.size(18.dp), tint = c.offline)
            Spacer(Modifier.width(6.dp))
            Icon(icon, null, Modifier.size(18.dp), tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
        Box {
            NoteIconButton(Icons.Outlined.MoreHoriz, "창") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (app.panes.size == 2) "분할 화면의 오른쪽과 바꾸기" else "분할 화면으로") },
                    { menu = false; app.moveToSplit(documentId) }, leadingIcon = { Icon(Icons.Outlined.VerticalSplit, null) })
                DropdownMenuItem({ Text("이 창에 다른 문서 열기") }, { menu = false; app.requestDocument(DocumentPlacement.Replacing(documentId)) },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) })
                DropdownMenuItem({ Text("떠 있는 창 하나 더 열기") }, { menu = false; app.requestDocument(DocumentPlacement.Floating) },
                    leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, null) })
            }
        }
        NoteIconButton(Icons.Outlined.Remove, "최소화") { app.setCollapsed(documentId, true) }
        NoteIconButton(Icons.Outlined.Close, "창 닫기") { app.closeDocument(documentId) }
    }
    Hairline()
}

/**
 * "창" in a pane's top bar: open another document beside it or in a floating window, and
 * arrange the split screen.
 */
@Composable
fun WindowMenuButton(app: AppModel, documentId: String) {
    val c = NoteTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        NoteIconButton(Icons.Outlined.VerticalSplit, "창: 분할 화면과 떠 있는 창", isOn = app.openDocumentCount > 1) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(if (app.panes.size == 2) "옆 창에 다른 문서 열기" else "옆에 열기 (분할 화면)") },
                { open = false; app.requestDocument(DocumentPlacement.Beside(documentId)) },
                leadingIcon = { Icon(Icons.Outlined.VerticalSplit, null) })
            DropdownMenuItem({ Text("떠 있는 창으로 열기") }, { open = false; app.requestDocument(DocumentPlacement.Floating) },
                leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, null) })
            if (app.panes.size == 2 && app.panes.any { it.id == documentId }) {
                HorizontalDivider()
                DropdownMenuItem({ Text("두 창 자리 바꾸기") }, { open = false; app.swapPanes() },
                    leadingIcon = { Icon(Icons.Outlined.SwapVert, null) })
                DropdownMenuItem({ Text("이 문서를 떠 있는 창으로") }, { open = false; app.moveToFloating(documentId) },
                    leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, null) })
                DropdownMenuItem({ Text("이 창 닫기") }, { open = false; app.closeDocument(documentId) },
                    leadingIcon = { Icon(Icons.Outlined.Close, null) })
            }
            val collapsed = app.floating.filter { it.collapsed }
            if (collapsed.isNotEmpty()) {
                HorizontalDivider()
                Text("최소화된 창", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                collapsed.forEach { window ->
                    DropdownMenuItem({ Text(documentTitle(window.document), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        { open = false; app.setCollapsed(window.id, false) },
                        leadingIcon = { Icon(documentIcon(window.document), null) })
                }
            }
            if (app.openDocumentCount > 1) {
                HorizontalDivider()
                DropdownMenuItem({ Text("모두 닫고 라이브러리로", color = c.danger) }, { open = false; app.closeWorkspace() },
                    leadingIcon = { Icon(Icons.Outlined.LibraryBooks, null, tint = c.danger) })
            }
        }
    }
}

// Picking a document to open

/** Chooses a notebook or PDF for a split pane or floating window: recent documents and every storage's folders. */
@Composable
private fun DocumentPickerSheet(app: AppModel, placement: DocumentPlacement, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    var location by remember { mutableStateOf<LibraryLocation?>(null) }
    fun pick(item: LibraryEntry, source: LibrarySource) {
        onDismiss()
        app.open(item, source, placement)
    }
    val here = location
    NoteSheet(if (here == null) placement.title else if (here.folder == "/") app.displayName(here.source) else RemotePath.name(here.folder),
        onDismiss, maxWidth = 560.dp) {
        if (here == null) {
            val recents = remember(app.recents, app.libraryRevision) {
                app.existingItems(app.recents.map { it.ref })
                    .filter { it.second.kind == LibraryItemKind.NOTEBOOK || it.second.kind == LibraryItemKind.PDF }
                    .take(8)
            }
            val sources = listOf(LibrarySource.LOCAL) + app.accounts.map { LibrarySource(it.id) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                if (recents.isNotEmpty()) {
                    item { PickerSection("최근 문서") }
                    items(recents, key = { "r:" + it.first.id }) { (ref, item) ->
                        PickerDocumentRow(app, item, ref.source, showSource = true) { pick(item, ref.source) }
                    }
                }
                item { PickerSection("저장소") }
                items(sources, key = { "s:" + it.id }) { source ->
                    PickerRow(app.sourceIcon(source), app.displayName(source), chevron = true) {
                        location = LibraryLocation(source, "/")
                    }
                }
            }
        } else {
            var items by remember(here) { mutableStateOf<List<LibraryEntry>?>(null) }
            LaunchedEffect(here, app.libraryRevision) { items = app.items(here) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                item {
                    PickerRow(Icons.Outlined.ChevronLeft, if (here.folder == "/") "처음으로" else "위 폴더",
                        tint = c.accent) {
                        location = if (here.folder == "/") null else LibraryLocation(here.source, RemotePath.parent(here.folder))
                    }
                    Hairline()
                }
                val shown = items?.filter {
                    it.kind == LibraryItemKind.FOLDER || it.kind == LibraryItemKind.NOTEBOOK || it.kind == LibraryItemKind.PDF
                }
                when {
                    shown == null -> item {
                        Text("불러오는 중…", color = c.textSecondary, modifier = Modifier.padding(20.dp))
                    }
                    shown.isEmpty() -> item {
                        Text("노트나 PDF가 없습니다.", color = c.textSecondary, modifier = Modifier.padding(20.dp))
                    }
                    else -> items(shown, key = { it.path }) { item ->
                        if (item.kind == LibraryItemKind.FOLDER) {
                            PickerRow(Icons.Outlined.Folder, item.displayName, chevron = true) {
                                location = LibraryLocation(here.source, item.path)
                            }
                        } else {
                            PickerDocumentRow(app, item, here.source, showSource = false) { pick(item, here.source) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSection(title: String) {
    Text(title, style = NoteTheme.Fonts.caption, color = NoteTheme.colors.textSecondary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(icon: ImageVector, title: String, chevron: Boolean = false, tint: androidx.compose.ui.graphics.Color? = null,
                      onClick: () -> Unit) {
    val c = NoteTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint ?: c.accent)
        Spacer(Modifier.width(12.dp))
        Text(title, color = tint ?: c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (chevron) Icon(Icons.Outlined.ChevronRight, null, tint = c.offline)
    }
}

@Composable
private fun PickerDocumentRow(app: AppModel, item: LibraryEntry, source: LibrarySource, showSource: Boolean, onClick: () -> Unit) {
    val c = NoteTheme.colors
    val isOpen = app.isOpen(LibraryRef(source, item.path).id)
    val notebook = item.kind == LibraryItemKind.NOTEBOOK
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (notebook) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.PictureAsPdf, null, Modifier.size(22.dp),
            tint = if (notebook) c.accent else c.danger)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showSource) Text(app.displayName(source), style = NoteTheme.Fonts.caption, color = c.textSecondary, maxLines = 1)
        }
        if (isOpen) {
            StatusPill("열려 있음", c.accent, c.accentSoft)
        } else if (item.isPlaceholder) {
            Icon(Icons.Outlined.CloudDownload, "NAS에서 받기", tint = c.accent)
        }
    }
}
