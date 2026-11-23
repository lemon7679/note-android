package com.lemon7679.note.editor

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.core.CoverStyle
import com.lemon7679.note.core.ElementFrame
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.PageElement
import com.lemon7679.note.core.PaperColor
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.ink.BrushKind
import com.lemon7679.note.ui.NoteIconButton
import com.lemon7679.note.ui.NoteIcons
import com.lemon7679.note.ui.NoteSheet
import com.lemon7679.note.ui.NoteTheme
import com.lemon7679.note.ui.OptionToggle
import com.lemon7679.note.ui.PaperTemplateGrid
import com.lemon7679.note.ui.PrimaryButton
import com.lemon7679.note.ui.SearchField
import com.lemon7679.note.ui.SectionLabel
import com.lemon7679.note.ui.SegmentedPicker
import com.lemon7679.note.ui.StatusPill
import com.lemon7679.note.ui.hexColor
import com.lemon7679.note.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Loads a notebook and shows the editor (or why it could not be opened). */
@Composable
fun NotebookEditorHost(app: AppModel, doc: OpenDocument) {
    val c = NoteTheme.colors
    val result = remember(doc.id) { runCatching { EditorModel(app, doc.ref, doc.file) } }
    val model = result.getOrNull()
    if (model != null) {
        NotebookEditor(app, model)
    } else {
        BackHandler { app.documentClosed(doc) }
        Column(Modifier.fillMaxSize().background(c.background).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text("노트를 열 수 없습니다.\n${result.exceptionOrNull()?.message ?: ""}", style = NoteTheme.Fonts.body, color = c.textPrimary)
            PrimaryButton("라이브러리로 돌아가기") { app.documentClosed(doc) }
        }
    }
}

val EditorTool.icon: ImageVector
    get() = when (this) {
        EditorTool.PEN -> NoteIcons.pen
        EditorTool.HIGHLIGHTER -> Icons.Outlined.BorderColor
        EditorTool.ERASER -> NoteIcons.eraser
        EditorTool.LASSO -> NoteIcons.lasso
        EditorTool.TEXT -> Icons.Outlined.TextFields
        EditorTool.IMAGE -> Icons.Outlined.Image
        EditorTool.LASER -> NoteIcons.laser
    }

private data class TextRequest(val pageId: String, val element: PageElement?, val x: Float, val y: Float)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookEditor(app: AppModel, model: EditorModel) {
    val c = NoteTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canvas by remember { mutableStateOf<CanvasView?>(null) }
    var selectionAnchor by remember { mutableStateOf<RectF?>(null) }
    var showPenSettings by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var textRequest by remember { mutableStateOf<TextRequest?>(null) }
    var imageTarget by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    var elementMenu by remember { mutableStateOf<Triple<String, PageElement, RectF>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showSidebar by remember { mutableStateOf<Boolean?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = imageTarget ?: return@rememberLauncherForActivityResult
        imageTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes != null) model.addImage(bytes, target.first, target.second, target.third)
        }
    }

    val host = remember {
        object : CanvasHost {
            override fun selectionChanged(anchor: RectF?) { selectionAnchor = anchor }
            override fun requestText(pageId: String, element: PageElement?, x: Float, y: Float) { textRequest = TextRequest(pageId, element, x, y) }
            override fun requestImage(pageId: String, x: Float, y: Float) {
                imageTarget = Triple(pageId, x, y)
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            override fun elementTapped(pageId: String, element: PageElement, anchor: RectF) { elementMenu = Triple(pageId, element, anchor) }
            override fun pasteMenu(anchor: RectF) {}
            override fun toolChanged() {}
        }
    }

    fun close() {
        canvas?.dispose()
        model.close()
    }
    BackHandler { close() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) model.save() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(model.tools.tool) { canvas?.applySettings() }

    Column(Modifier.fillMaxSize().background(c.desk).windowInsetsPadding(WindowInsets.safeDrawing)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 700.dp
            if (showSidebar == null) showSidebar = !compact
            Column {
                TopBar(app, model, compact, showSidebar == true, onSidebar = { showSidebar = showSidebar != true },
                    onClose = ::close, onPanel = { showPanel = true }, onPaper = { showPaper = true }, onRename = { renaming = true },
                    onPaste = { canvas?.paste() }, onExport = { kind ->
                        scope.launch {
                            busy = "내보내는 중…"
                            try {
                                when (kind) {
                                    0 -> shareFile(context, withContext(Dispatchers.Default) { model.exportPdf() }, "application/pdf")
                                    1 -> model.exportPageImage(model.currentPageIndex)?.let { shareFile(context, it, "image/png") }
                                    else -> {
                                        val pdf = model.exportPdf()
                                        val lib = app.library(model.ref.source)
                                        val name = FileNaming.split(RemotePath.name(model.ref.path)).first + ".pdf"
                                        pdf.copyTo(lib.file(RemotePath.join(RemotePath.parent(model.ref.path), name)), overwrite = true)
                                        app.refreshLibrary()
                                        app.localChange(model.ref.source)
                                    }
                                }
                            } catch (e: Exception) {
                                model.lastError = e.message
                            } finally {
                                busy = null
                            }
                        }
                    })
                ToolRow(model, onPenSettings = { showPenSettings = true }, showPenSettings, { showPenSettings = false })
            }
        }
        Row(Modifier.weight(1f)) {
            AnimatedVisibility(showSidebar == true) {
                PageSidebar(model)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(factory = { ctx -> CanvasView(ctx, model, host).also { canvas = it } }, modifier = Modifier.fillMaxSize().clipToBounds(),
                    update = { it.applySettings() })
                BottomOverlay(model)
                selectionAnchor?.let { anchor -> SelectionMenu(anchor, canvas) }
                elementMenu?.let { (pageId, element, anchor) ->
                    val density = LocalDensity.current
                    Popup(offset = IntOffset(anchor.left.roundToInt(), (anchor.bottom + with(density) { 8.dp.toPx() }).roundToInt()),
                        onDismissRequest = { elementMenu = null }, properties = PopupProperties(focusable = true)) {
                        Row(Modifier.shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(4.dp)) {
                            if (element.kind == PageElement.Kind.TEXT) {
                                MenuAction(Icons.Outlined.Edit, "편집") {
                                    elementMenu = null
                                    textRequest = TextRequest(pageId, element, element.frame.x.toFloat(), element.frame.y.toFloat())
                                }
                            }
                            MenuAction(Icons.Outlined.Delete, "삭제", c.danger) {
                                elementMenu = null
                                model.removeElement(element.id, pageId)
                            }
                        }
                    }
                }
                busy?.let {
                    Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = Color.White)
                    }
                }
            }
        }
    }

    if (showPanel) EditorPanel(model) { showPanel = false }
    if (showPaper) PaperSheet(model) { showPaper = false }
    if (renaming) {
        var text by remember { mutableStateOf(model.title) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("노트 이름") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ model.rename(text); renaming = false }) { Text("변경") } },
            dismissButton = { TextButton({ renaming = false }) { Text("취소") } })
    }
    textRequest?.let { req ->
        var text by remember(req) { mutableStateOf(req.element?.text ?: "") }
        AlertDialog(onDismissRequest = { textRequest = null }, title = { Text(if (req.element == null) "텍스트 상자" else "텍스트 편집") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton({
                    val existing = req.element
                    val page = model.document.pages.firstOrNull { it.id == req.pageId }
                    if (existing != null) {
                        if (text.isBlank()) model.removeElement(existing.id, req.pageId)
                        else model.updateElement(existing.copy(text = text, frame = existing.frame.copy(height = textHeight(existing.copy(text = text)))), req.pageId)
                    } else if (text.isNotBlank() && page != null) {
                        val width = maxOf(80.0, minOf(260.0, page.size.width - req.x - 24))
                        val element = PageElement(kind = PageElement.Kind.TEXT, frame = ElementFrame(req.x.toDouble(), req.y.toDouble() - 12, width, 28.0),
                            text = text, fontSize = 16.0, colorHex = model.tools.penColor)
                        model.addElement(element.copy(frame = element.frame.copy(height = textHeight(element))), req.pageId)
                    }
                    textRequest = null
                }) { Text("완료") }
            },
            dismissButton = { TextButton({ textRequest = null }) { Text("취소") } })
    }
    model.lastError?.let { message ->
        AlertDialog(onDismissRequest = { model.lastError = null }, title = { Text("오류") }, text = { Text(message) },
            confirmButton = { TextButton({ model.lastError = null }) { Text("확인") } })
    }
}

private fun textHeight(e: PageElement): Double =
    maxOf(28.0, com.lemon7679.note.render.ElementDrawing.textLayout(e, e.frame.width.toFloat()).height.toDouble() + 4)

@Composable
private fun MenuAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val c = NoteTheme.colors
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint ?: c.iconMuted)
        Text(label, fontSize = 11.sp, color = tint ?: c.textPrimary, maxLines = 1)
    }
}

// Top bar (design: 52, surface)

@Composable
private fun TopBar(
    app: AppModel,
    model: EditorModel,
    compact: Boolean,
    sidebar: Boolean,
    onSidebar: () -> Unit,
    onClose: () -> Unit,
    onPanel: () -> Unit,
    onPaper: () -> Unit,
    onRename: () -> Unit,
    onPaste: () -> Unit,
    onExport: (Int) -> Unit,
) {
    val c = NoteTheme.colors
    var pageMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp).background(c.surface).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).height(44.dp).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ChevronLeft, "라이브러리", tint = c.accent)
            if (!compact) Text("라이브러리", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        NoteIconButton(Icons.Outlined.ViewSidebar, "페이지 목록", isOn = sidebar, onClick = onSidebar)
        if (!compact) {
            val bookmarked = model.currentPage.bookmarked
            NoteIconButton(if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "책갈피", isOn = bookmarked) {
                model.toggleBookmark(model.currentPageIndex)
            }
            NoteIconButton(Icons.Outlined.Search, "검색과 책갈피", onClick = onPanel)
        }
        Row(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(model.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable(onClick = onRename))
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                SyncPill(app, model)
            }
        }
        NoteIconButton(Icons.AutoMirrored.Outlined.Undo, "실행 취소", enabled = model.canUndo) { model.undo() }
        NoteIconButton(Icons.AutoMirrored.Outlined.Redo, "다시 실행", enabled = model.canRedo) { model.redo() }
        Box {
            NoteIconButton(Icons.AutoMirrored.Outlined.NoteAdd, "페이지 추가") { pageMenu = true }
            DropdownMenu(pageMenu, { pageMenu = false }) {
                DropdownMenuItem({ Text("뒤에 페이지 추가") }, { pageMenu = false; model.addPage(model.currentPageIndex) })
                DropdownMenuItem({ Text("앞에 페이지 추가") }, { pageMenu = false; model.insertPage(model.currentPageIndex) })
                DropdownMenuItem({ Text("현재 페이지 복제") }, { pageMenu = false; model.duplicatePage(model.currentPageIndex) })
                DropdownMenuItem({ Text("종이 바꾸기") }, { pageMenu = false; onPaper() })
                if (model.pageCount > 1) {
                    DropdownMenuItem({ Text("현재 페이지 삭제", color = c.danger) }, { pageMenu = false; model.deletePage(model.currentPageIndex) })
                }
            }
        }
        Box {
            NoteIconButton(Icons.Outlined.IosShare, "내보내기") { exportMenu = true }
            DropdownMenu(exportMenu, { exportMenu = false }) {
                DropdownMenuItem({ Text("PDF로 내보내기") }, { exportMenu = false; onExport(0) })
                DropdownMenuItem({ Text("현재 페이지를 이미지로") }, { exportMenu = false; onExport(1) })
                DropdownMenuItem({ Text(if (model.ref.source.accountId != null) "NAS 폴더에 PDF 저장" else "노트 옆에 PDF 저장") },
                    { exportMenu = false; onExport(2) })
            }
        }
        Box {
            NoteIconButton(Icons.Outlined.MoreHoriz, "더 보기") { moreMenu = true }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                if (compact) {
                    DropdownMenuItem({ Text(if (model.currentPage.bookmarked) "책갈피 해제" else "책갈피") },
                        { moreMenu = false; model.toggleBookmark(model.currentPageIndex) })
                    DropdownMenuItem({ Text("검색과 책갈피") }, { moreMenu = false; onPanel() })
                }
                DropdownMenuItem({ Text("이름 변경") }, { moreMenu = false; onRename() })
                DropdownMenuItem({ Text("표지…") }, { moreMenu = false; coverMenu = true })
                if (app.strokeClipboard != null) DropdownMenuItem({ Text("붙여넣기") }, { moreMenu = false; onPaste() },
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) })
                HorizontalDivider()
                DropdownMenuItem({ Text("손가락으로 그리기") }, { app.updateSettings { it.copy(stylusOnly = !it.stylusOnly) } },
                    trailingIcon = { androidx.compose.material3.Checkbox(!app.settings.stylusOnly, null) })
                DropdownMenuItem({ Text("두 손가락 탭으로 실행 취소") }, { app.updateSettings { it.copy(tapGesturesUndo = !it.tapGesturesUndo) } },
                    trailingIcon = { androidx.compose.material3.Checkbox(app.settings.tapGesturesUndo, null) })
            }
            DropdownMenu(coverMenu, { coverMenu = false }) {
                DropdownMenuItem({ Text("표지 없음") }, { coverMenu = false; model.setCover(null) })
                CoverStyle.palette.forEach { hex ->
                    DropdownMenuItem({ Text(hex) }, { coverMenu = false; model.setCover(hex) },
                        leadingIcon = { Box(Modifier.size(18.dp).clip(CircleShape).background(hexColor(hex))) })
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
}

@Composable
private fun SyncPill(app: AppModel, model: EditorModel) {
    val c = NoteTheme.colors
    val id = model.ref.source.accountId
    val account = app.account(id)
    if (id == null || account == null) {
        StatusPill("이 기기", c.textSecondary, c.fieldFill)
        return
    }
    val status = app.status(id)
    when {
        status.phase is com.lemon7679.note.app.AccountSyncStatus.Phase.Error -> StatusPill(account.name, c.danger, c.dangerSoft)
        status.isSyncing -> StatusPill(account.name, c.accent, c.accentSoft)
        else -> StatusPill(account.name, c.synced, c.syncedSoft)
    }
}

// Tool row (design: 56)

@Composable
private fun ToolRow(model: EditorModel, onPenSettings: () -> Unit, showPenSettings: Boolean, dismissPenSettings: () -> Unit) {
    val c = NoteTheme.colors
    val tools = model.tools
    Row(Modifier.fillMaxWidth().height(56.dp).background(c.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        EditorTool.entries.forEach { tool ->
            NoteIconButton(tool.icon, tool.label, isOn = tools.tool == tool) {
                if (tools.tool == tool && (tool.inks || tool == EditorTool.ERASER)) onPenSettings()
                else model.updateTools { it.select(tool) }
            }
        }
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(28.dp).background(c.separator))
        when (tools.tool) {
            EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.TEXT -> {
                ColorSwatches(model)
                if (tools.tool != EditorTool.TEXT) {
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(28.dp).background(c.separator))
                    WidthPresets(model)
                }
            }
            EditorTool.ERASER -> {
                SegmentedPicker(listOf("정밀", "획 전체"), if (tools.eraserPrecise) 0 else 1, Modifier.width(150.dp)) { i ->
                    model.updateTools { it.copy(eraserPrecise = i == 0) }
                }
                Spacer(Modifier.width(8.dp))
                val on = tools.eraseHighlighterOnly
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(NoteTheme.Radius.control)).background(if (on) c.accentSoft else Color.Transparent)
                    .clickable { model.updateTools { it.copy(eraseHighlighterOnly = !it.eraseHighlighterOnly) } }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text("형광펜만", fontSize = 13.sp, color = if (on) c.accent else c.iconMuted)
                }
            }
            EditorTool.LASSO -> Hint("둘러서 선택 · 끌어서 이동 · 모서리로 크기 조절")
            EditorTool.IMAGE -> Hint("이미지를 넣을 곳을 탭하세요")
            EditorTool.LASER -> Hint("발표할 때 가리키기 · 저장되지 않습니다")
        }
        Spacer(Modifier.width(8.dp))
        if (tools.tool.inks || tools.tool == EditorTool.ERASER) {
            Box {
                Row(Modifier.height(36.dp).clip(RoundedCornerShape(NoteTheme.Radius.control))
                    .border(1.dp, c.controlBorder, RoundedCornerShape(NoteTheme.Radius.control)).clickable(onClick = onPenSettings)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(penSummary(tools), fontSize = 13.sp, color = c.iconMuted)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = c.iconMuted)
                }
                if (showPenSettings) {
                    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 120), onDismissRequest = dismissPenSettings,
                        properties = PopupProperties(focusable = true)) {
                        Box(Modifier.width(340.dp).heightIn(max = 560.dp).shadow(12.dp, RoundedCornerShape(NoteTheme.Radius.popover))
                            .clip(RoundedCornerShape(NoteTheme.Radius.popover)).background(c.surface)) {
                            PenSettings(model)
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
}

private fun penSummary(t: ToolState): String = when (t.tool) {
    EditorTool.HIGHLIGHTER -> "형광펜 · ${"%.0f".format(t.highlighterWidth)}pt"
    EditorTool.ERASER -> if (t.eraserPrecise) "정밀 지우개" else "획 지우개"
    else -> "${t.pen.label} · ${"%.1f".format(t.penWidth / 72 * 25.4)}mm"
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = NoteTheme.colors.textSecondary, maxLines = 1)
}

@Composable
private fun ColorSwatches(model: EditorModel) {
    val c = NoteTheme.colors
    val t = model.tools
    var picker by remember { mutableStateOf(false) }
    val palette = if (t.tool == EditorTool.HIGHLIGHTER) NoteTheme.highlighterColors else NoteTheme.penColors
    val colors = (palette + t.customColors.take(2)).take(6)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { hex ->
            val selected = t.activeColor.equals(hex, ignoreCase = true)
            Box(Modifier.size(36.dp).clip(CircleShape).border(2.dp, if (selected) c.accent else Color.Transparent, CircleShape)
                .clickable { model.updateTools { it.withColor(hex) } }.padding(4.dp).clip(CircleShape).background(hexColor(hex)))
        }
        Box {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { picker = true }.padding(5.dp).clip(CircleShape)
                .background(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))))
            if (picker) {
                Popup(onDismissRequest = { picker = false }, offset = IntOffset(0, 110), properties = PopupProperties(focusable = true)) {
                    ColorPickerCard(t.activeColor) { hex ->
                        model.updateTools { it.withColor(hex).rememberingCustomColor(hex) }
                    }
                }
            }
        }
    }
}

/** Simple HSV color picker (custom colors). */
@Composable
private fun ColorPickerCard(initial: String, onPick: (String) -> Unit) {
    val c = NoteTheme.colors
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initial), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val hex = "#%06X".format(argb and 0xFFFFFF)
    Column(Modifier.width(280.dp).shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(argb)))
            Spacer(Modifier.width(10.dp))
            Text(hex, color = c.textPrimary, modifier = Modifier.weight(1f))
            TextButton({ onPick(hex) }) { Text("사용") }
        }
        Text("색상", style = NoteTheme.Fonts.caption, color = c.textSecondary)
        Slider(h, { h = it }, valueRange = 0f..360f)
        Text("채도", style = NoteTheme.Fonts.caption, color = c.textSecondary)
        Slider(s, { s = it })
        Text("밝기", style = NoteTheme.Fonts.caption, color = c.textSecondary)
        Slider(v, { v = it })
    }
}

@Composable
private fun WidthPresets(model: EditorModel) {
    val c = NoteTheme.colors
    val t = model.tools
    val widths = if (t.tool == EditorTool.HIGHLIGHTER) t.highlighterWidths else t.currentPenWidths
    val selected = if (t.tool == EditorTool.HIGHLIGHTER) t.highlighterWidthIndex else t.penWidthIndex
    val maxWidth = widths.maxOrNull() ?: 1.0
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        widths.forEachIndexed { i, w ->
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(NoteTheme.Radius.control)).background(if (selected == i) c.fieldFill else Color.Transparent)
                .clickable {
                    model.updateTools { if (it.tool == EditorTool.HIGHLIGHTER) it.copy(highlighterWidthIndex = i) else it.copy(penWidthIndex = i) }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(18.dp).height(maxOf(2.0, w / maxWidth * 8).dp).clip(CircleShape).background(hexColor(t.activeColor)))
            }
        }
    }
}

// Pen settings popover

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenSettings(model: EditorModel) {
    val c = NoteTheme.colors
    val t = model.tools
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (t.tool) {
            EditorTool.ERASER -> {
                SegmentedPicker(listOf("정밀 지우개", "획 지우개"), if (t.eraserPrecise) 0 else 1) { i -> model.updateTools { it.copy(eraserPrecise = i == 0) } }
                LabeledSlider("크기", "${t.eraserSize.toInt()}dp", t.eraserSize.toFloat(), 4f..60f) { v -> model.updateTools { it.copy(eraserSize = v.toDouble()) } }
                OptionToggle("형광펜만 지우기", t.eraseHighlighterOnly) { v -> model.updateTools { it.copy(eraseHighlighterOnly = v) } }
                Text("스타일러스의 지우개 끝이나 옆 버튼으로도 지울 수 있습니다.", style = NoteTheme.Fonts.caption, color = c.textSecondary)
            }
            EditorTool.HIGHLIGHTER -> {
                LabeledSlider("굵기", "%.1fpt".format(t.highlighterWidth), t.highlighterWidth.toFloat(), 4f..30f) { v ->
                    model.updateTools { it.withWidth(v.toDouble(), it.highlighterWidthIndex) }
                }
                OptionToggle("멈추면 직선으로", t.highlighterStraight) { v -> model.updateTools { it.copy(highlighterStraight = v) } }
                Text("형광펜은 글씨 아래에 칠해져 글자가 흐려지지 않습니다.", style = NoteTheme.Fonts.caption, color = c.textSecondary)
            }
            else -> {
                SectionLabel("펜 종류")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                    PEN_KINDS.forEach { kind ->
                        val on = t.pen == kind
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(NoteTheme.Radius.control))
                            .background(if (on) c.accentSoft else c.fieldFill).clickable { model.updateTools { it.copy(penKind = kind.raw) } },
                            contentAlignment = Alignment.Center) {
                            Text(kind.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) c.accent else c.textPrimary)
                        }
                    }
                }
                LabeledSlider("굵기", "%.1fpt · %.2fmm".format(t.penWidth, t.penWidth / 72 * 25.4), t.penWidth.toFloat(), 0.3f..10f) { v ->
                    model.updateTools { it.withWidth(v.toDouble(), it.penWidthIndex) }
                }
                LabeledSlider("글씨 보정", if (t.correction == 0.0) "끔" else "${(t.correction * 100).toInt()}%", t.correction.toFloat(), 0f..1f) { v ->
                    model.updateTools { it.copy(correction = v.toDouble()) }
                }
                Text("흔들린 선을 펴고 ㄱ·ㄴ·ㅁ의 모서리를 맞춥니다. 필체는 그대로 둡니다.", style = NoteTheme.Fonts.caption, color = c.textSecondary)
                LabeledSlider("손떨림 보정", if (t.stabilizer == 0.0) "끔" else "${t.stabilizer.toInt()}ms", t.stabilizer.toFloat(), 0f..60f, steps = 11) { v ->
                    model.updateTools { it.copy(stabilizer = v.toDouble()) }
                }
                OptionToggle("그리고 멈추면 도형으로", t.snapShapes) { v -> model.updateTools { it.copy(snapShapes = v) } }
            }
        }
    }
}

@Composable
private fun LabeledSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    val c = NoteTheme.colors
    Column {
        Row {
            Text(title, style = NoteTheme.Fonts.body, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(value, style = NoteTheme.Fonts.caption, color = c.textSecondary)
        }
        Slider(current, onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
    }
}

// Overlays

@Composable
private fun BottomOverlay(model: EditorModel) {
    val c = NoteTheme.colors
    Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.Bottom) {
        if (model.tools.tool == EditorTool.PEN && model.tools.snapShapes) {
            Row(Modifier.height(36.dp).clip(CircleShape).background(c.surface).border(1.dp, c.separator, CircleShape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(NoteIcons.shapes, null, Modifier.size(16.dp), tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text("그리고 멈추면 도형으로 · 다른 손가락을 대면 완벽한 도형", fontSize = 13.sp, color = c.iconMuted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("${model.currentPageIndex + 1} / ${model.pageCount}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/** Lasso menu (design: 올가미 메뉴): copy, cut, duplicate, recolor, 줄 정렬, 글씨 다듬기, delete. */
@Composable
private fun SelectionMenu(anchor: RectF, canvas: CanvasView?) {
    val c = NoteTheme.colors
    val density = LocalDensity.current
    var colors by remember { mutableStateOf(false) }
    val y = (anchor.bottom + with(density) { 14.dp.toPx() }).roundToInt()
    Popup(offset = IntOffset(anchor.left.roundToInt().coerceAtLeast(0), y), properties = PopupProperties(focusable = false)) {
        Column(Modifier.shadow(10.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(4.dp)) {
            Row {
                MenuAction(Icons.Outlined.ContentCopy, "복사") { canvas?.copySelection() }
                MenuAction(Icons.Outlined.ContentCut, "잘라내기") { canvas?.copySelection(); canvas?.deleteSelection() }
                MenuAction(Icons.Outlined.LibraryAdd, "복제") { canvas?.duplicateSelection() }
                MenuAction(Icons.Outlined.Palette, "색 변경") { colors = !colors }
                MenuAction(Icons.AutoMirrored.Outlined.FormatAlignLeft, "줄 정렬") { canvas?.applyToSelection { ink, ids -> ink.straighten(ids, 1f) } }
                MenuAction(Icons.Outlined.AutoFixHigh, "글씨 다듬기") { canvas?.applyToSelection { ink, ids -> ink.correct(ids, 0.6f) } }
                MenuAction(Icons.Outlined.Delete, "삭제", c.danger) { canvas?.deleteSelection() }
            }
            if (colors) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoteTheme.penColors.forEach { hex ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(hexColor(hex)).clickable { canvas?.recolorSelection(hex); colors = false })
                    }
                }
            }
        }
    }
}

// Page sidebar (design: 132)

@Composable
private fun PageSidebar(model: EditorModel) {
    val c = NoteTheme.colors
    val list = rememberLazyListState()
    LaunchedEffect(model.currentPageIndex) {
        if (model.currentPageIndex !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.animateScrollToItem(model.currentPageIndex)
    }
    Row(Modifier.width(133.dp).fillMaxHeight()) {
        LazyColumn(Modifier.width(132.dp).fillMaxHeight().background(c.background), state = list,
            contentPadding = PaddingValues(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(model.document.pages, key = { _, p -> p.id }) { index, page ->
                PageThumb(model, index, page)
            }
            item {
                Box(Modifier.width(84.dp).height(44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.controlBorder, RoundedCornerShape(8.dp))
                    .clickable { model.addPage(model.pageCount - 1) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "페이지 추가", tint = c.textSecondary)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.separator))
    }
}

@Composable
private fun PageThumb(model: EditorModel, index: Int, page: com.lemon7679.note.core.NotebookPage) {
    val c = NoteTheme.colors
    val selected = index == model.currentPageIndex
    var menu by remember { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, page, model.thumbnailRevision) { value = model.thumbnail(page) }
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).androidLongClick({ model.go(index) }, { menu = true })) {
            Box(Modifier.width(84.dp).aspectRatio((page.size.width / page.size.height).toFloat()).clip(RoundedCornerShape(3.dp))
                .background(Color.White).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.separator, RoundedCornerShape(3.dp))) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                if (page.bookmarked) Icon(Icons.Filled.Bookmark, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp), tint = c.danger)
            }
            Text("${index + 1}", fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) c.accent else c.textSecondary)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("앞에 페이지 추가") }, { menu = false; model.insertPage(index) })
            DropdownMenuItem({ Text("뒤에 페이지 추가") }, { menu = false; model.addPage(index) })
            DropdownMenuItem({ Text("복제") }, { menu = false; model.duplicatePage(index) })
            DropdownMenuItem({ Text(if (page.bookmarked) "책갈피 해제" else "책갈피") }, { menu = false; model.toggleBookmark(index) })
            if (index > 0) DropdownMenuItem({ Text("위로 이동") }, { menu = false; model.movePage(index, index - 1) })
            if (index < model.pageCount - 1) DropdownMenuItem({ Text("아래로 이동") }, { menu = false; model.movePage(index, index + 1) })
            if (model.pageCount > 1) DropdownMenuItem({ Text("페이지 삭제", color = c.danger) }, { menu = false; model.deletePage(index) })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.androidLongClick(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

// Search & bookmarks panel

@Composable
private fun EditorPanel(model: EditorModel, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    var tab by remember { mutableStateOf(if (model.pdf != null) 1 else 0) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<EditorModel.SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    NoteSheet("탐색", onDismiss, maxWidth = 480.dp, dismissLabel = "완료") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val tabs = if (model.pdf != null) listOf("책갈피", "PDF 검색") else listOf("책갈피")
            SegmentedPicker(tabs, tab.coerceAtMost(tabs.size - 1)) { tab = it }
            if (tab == 1 && model.pdf != null) {
                SearchField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "PDF에서 찾기")
                LaunchedEffect(query) {
                    kotlinx.coroutines.delay(300)
                    hits = withContext(Dispatchers.Default) { model.search(query) }
                    searched = query.isNotBlank()
                }
                if (android.os.Build.VERSION.SDK_INT < 35) {
                    Text("PDF 검색은 Android 15 이상에서 지원됩니다.", color = c.textSecondary, fontSize = 13.sp)
                } else if (searched && hits.isEmpty()) {
                    Text("검색 결과가 없습니다.", color = c.textSecondary, fontSize = 13.sp)
                }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(hits) { _, hit ->
                        Column(Modifier.fillMaxWidth().clickable { model.show(hit); onDismiss() }.padding(vertical = 10.dp)) {
                            Text(hit.context, color = c.textPrimary, maxLines = 2)
                            Text("${hit.pageIndex + 1}쪽", style = NoteTheme.Fonts.caption, color = c.textSecondary)
                        }
                        HorizontalDivider(color = c.separator)
                    }
                }
            } else {
                val marks = model.bookmarkedIndices
                if (marks.isEmpty()) Text("책갈피한 페이지가 없습니다.", color = c.textSecondary)
                marks.forEach { i ->
                    Text("${i + 1}쪽", color = c.accent, modifier = Modifier.fillMaxWidth().clickable { model.go(i); onDismiss() }.padding(vertical = 12.dp))
                    HorizontalDivider(color = c.separator)
                }
            }
        }
    }
}

/** Change the paper of this page or the whole notebook. */
@Composable
private fun PaperSheet(model: EditorModel, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    var template by remember { mutableStateOf(model.currentPage.paper.template) }
    var color by remember { mutableStateOf(model.currentPage.paper.color) }
    var all by remember { mutableStateOf(false) }
    NoteSheet("종이 바꾸기", onDismiss, maxWidth = 560.dp, confirm = "적용", onConfirm = {
        model.setPaper(PaperStyle(template, color), all)
        onDismiss()
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionLabel("종이")
            PaperTemplateGrid(template, color) { template = it }
            SectionLabel("색")
            SegmentedPicker(PaperColor.entries.map { it.displayName }, color.ordinal) { color = PaperColor.entries[it] }
            OptionToggle("모든 페이지에 적용", all) { all = it }
            if (model.currentPage.background.pdfPageIndex != null) {
                Text("PDF 페이지는 종이가 바뀌지 않습니다.", style = NoteTheme.Fonts.caption, color = c.textSecondary)
            }
        }
    }
}
