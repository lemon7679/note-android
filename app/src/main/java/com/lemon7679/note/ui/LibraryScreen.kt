package com.lemon7679.note.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.byteLabel
import com.lemon7679.note.app.libraryDateLabel
import com.lemon7679.note.core.LibraryEntry
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.LibrarySort
import com.lemon7679.note.core.NotebookPackage
import com.lemon7679.note.core.NotebookSummary
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.render.PdfHandle
import com.lemon7679.note.render.PdfSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class KindFilter(val title: String) {
    ALL("전체"), NOTES("노트"), PDFS("PDF"), FOLDERS("폴더");

    fun includes(item: LibraryEntry) = when (this) {
        ALL -> true
        NOTES -> item.kind == LibraryItemKind.NOTEBOOK
        PDFS -> item.kind == LibraryItemKind.PDF
        FOLDERS -> item.kind == LibraryItemKind.FOLDER
    }
}

/** Folder navigation inside one library source. */
@Composable
fun LibraryBrowser(app: AppModel, source: LibrarySource, openMenu: (() -> Unit)?) {
    val stack = remember(source) { mutableStateListOf<String>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.size - 1) }
    val folder = stack.lastOrNull() ?: "/"
    LibraryView(app, LibraryLocation(source, folder), openMenu,
        onOpenFolder = { stack.add(it) },
        onNavigate = { depth -> while (stack.size > depth) stack.removeAt(stack.size - 1) })
}

/** One folder of a library source (design: Main artboard). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryView(
    app: AppModel,
    location: LibraryLocation,
    openMenu: (() -> Unit)?,
    onOpenFolder: (String) -> Unit,
    onNavigate: (Int) -> Unit,
) {
    val c = NoteTheme.colors
    val source = location.source
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(KindFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var newSheet by remember { mutableStateOf<NewItemMode?>(null) }
    var renaming by remember { mutableStateOf<LibraryEntry?>(null) }
    var moving by remember { mutableStateOf<LibraryEntry?>(null) }
    var deleting by remember { mutableStateOf<LibraryEntry?>(null) }
    var showSyncCenter by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { app.importPdf(it, location, annotate = true) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val type = context.contentResolver.getType(uri) ?: ""
            if (type == "application/pdf") app.importPdf(uri, location, annotate = false) else app.importFile(uri, location)
        }
    }

    suspend fun reload() {
        items = app.items(location)
        loaded = true
        if (query.isNotBlank()) searchResults = app.search(query.trim(), source).filter { it.kind != LibraryItemKind.FOLDER }
    }
    LaunchedEffect(location, app.libraryRevision, app.settings.librarySort) { reload() }
    LaunchedEffect(query) {
        searchResults = if (query.isBlank()) null else app.search(query.trim(), source).filter { it.kind != LibraryItemKind.FOLDER }
    }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        LibraryHeader(app, location, openMenu, query, { query = it }, onNavigate,
            onNew = { newSheet = it }, onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
            onPickFile = { pickFile.launch(arrayOf("*/*")) }, onSyncCenter = { showSyncCenter = true })
        PullToRefreshBox(refreshing, onRefresh = {
            refreshing = true
            source.accountId?.let { app.sync(it, manual = true) }
            scope.launch {
                reload()
                refreshing = false
            }
        }, modifier = Modifier.fillMaxSize()) {
            val folders = items.filter { it.kind == LibraryItemKind.FOLDER && filter.includes(it) }
            val documents = items.filter { it.kind != LibraryItemKind.FOLDER && filter.includes(it) }
            val shown = searchResults
            LazyVerticalGrid(
                GridCells.Adaptive(152.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindFilter.entries.forEach { f -> NoteChip(f.title, filter == f) { filter = f } }
                        source.accountId?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(app.status(it).summary, style = NoteTheme.Fonts.caption, color = c.textSecondary, maxLines = 1)
                        }
                    }
                }
                if (shown != null) {
                    items(shown, key = { "s" + it.path }) { item ->
                        DocumentCard(app, item, source, onOpen = { app.open(item, source) },
                            menu = { dismiss -> ItemMenu(app, item, source, dismiss, { renaming = it }, { moving = it }, { deleting = it }) })
                    }
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(null, "검색 결과가 없습니다", "다른 단어로 찾아보세요.")
                        }
                    }
                } else {
                    items(folders, key = { "f" + it.path }, span = { GridItemSpan(minOf(2, maxLineSpan)) }) { folder ->
                        FolderCard(app, folder, source, onOpen = { onOpenFolder(folder.path) },
                            menu = { dismiss -> ItemMenu(app, folder, source, dismiss, { renaming = it }, { moving = it }, { deleting = it }) })
                    }
                    if (folders.isNotEmpty() && documents.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(2.dp)) }
                    }
                    items(documents, key = { "d" + it.path }) { item ->
                        DocumentCard(app, item, source, onOpen = { app.open(item, source) },
                            menu = { dismiss -> ItemMenu(app, item, source, dismiss, { renaming = it }, { moving = it }, { deleting = it }) })
                    }
                    if (loaded && folders.isEmpty() && documents.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                if (source.accountId != null) Icons.Outlined.Folder else Icons.Outlined.MenuBook,
                                if (source.accountId != null) "이 NAS 폴더는 비어 있습니다" else "아직 노트가 없습니다",
                                "새 노트를 만들거나 PDF를 가져와 필기를 시작하세요.",
                            ) {
                                PrimaryButton("새 노트") { newSheet = NewItemMode.NOTEBOOK }
                                SecondaryButton("PDF 가져오기") { pickPdf.launch(arrayOf("application/pdf")) }
                            }
                        }
                    }
                }
            }
        }
    }

    newSheet?.let { mode -> NewItemSheet(app, location, mode, onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) }) { newSheet = null } }
    moving?.let { item -> MoveSheet(app, source, item) { moving = null } }
    if (showSyncCenter) SyncCenterDialog(app, source.accountId) { showSyncCenter = false }
    renaming?.let { item ->
        var text by remember(item) { mutableStateOf(item.displayName) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("이름 변경") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ app.rename(LibraryRef(source, item.path), text); renaming = null }) { Text("변경") } },
            dismissButton = { TextButton({ renaming = null }) { Text("취소") } })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("‘${item.displayName}’을(를) 삭제할까요?") },
            text = {
                Text(when {
                    item.isPlaceholder -> "NAS에서 바로 삭제됩니다."
                    source.accountId != null -> "휴지통으로 옮기고, 다음 동기화 때 NAS에서도 삭제됩니다."
                    else -> "휴지통으로 옮깁니다."
                })
            },
            confirmButton = { TextButton({ app.delete(item, source); deleting = null }) { Text("삭제", color = c.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("취소") } })
    }
}

@Composable
private fun LibraryHeader(
    app: AppModel,
    location: LibraryLocation,
    openMenu: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    onNavigate: (Int) -> Unit,
    onNew: (NewItemMode) -> Unit,
    onPickPdf: () -> Unit,
    onPickFile: () -> Unit,
    onSyncCenter: () -> Unit,
) {
    val c = NoteTheme.colors
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        Column {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (compact) 8.dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                openMenu?.let { NoteIconButton(Icons.Outlined.Menu, "메뉴", onClick = it) }
                Breadcrumb(app, location, onNavigate, Modifier.weight(1f))
                if (!compact || searching) {
                    if (!compact) SearchField(query, onQuery, Modifier.widthIn(min = 140.dp, max = 240.dp))
                } else {
                    NoteIconButton(Icons.Outlined.Search, "검색") { searching = true }
                }
                Box {
                    NoteIconButton(Icons.AutoMirrored.Outlined.Sort, "정렬") { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem({ Text(s.displayName, fontWeight = if (app.settings.librarySort == s) FontWeight.SemiBold else FontWeight.Normal) },
                                { app.updateSettings { it.copy(librarySort = s) }; sortMenu = false })
                        }
                    }
                }
                location.source.accountId?.let { id ->
                    val status = app.status(id)
                    Box {
                        NoteIconButton(Icons.Outlined.Sync, "동기화 센터", isOn = status.isSyncing, onClick = onSyncCenter)
                        if (app.totalConflicts > 0) {
                            Box(Modifier.padding(start = 30.dp, top = 8.dp).size(9.dp).clip(CircleShape).background(c.conflict))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    if (compact) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(NoteTheme.Radius.control)).background(c.accent)
                            .clickable { newMenu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, "새로 만들기", tint = Color.White)
                        }
                    } else {
                        PrimaryButton("새로 만들기", icon = Icons.Outlined.Add) { newMenu = true }
                    }
                    DropdownMenu(newMenu, { newMenu = false }) {
                        DropdownMenuItem({ Text("새 노트") }, { newMenu = false; onNew(NewItemMode.NOTEBOOK) },
                            leadingIcon = { Icon(Icons.Outlined.MenuBook, null) })
                        DropdownMenuItem({ Text("PDF 가져와서 필기") }, { newMenu = false; onPickPdf() },
                            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) })
                        DropdownMenuItem({ Text("파일 가져오기") }, { newMenu = false; onPickFile() },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) })
                        DropdownMenuItem({ Text("새 폴더") }, { newMenu = false; onNew(NewItemMode.FOLDER) },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                    }
                }
            }
            if (compact && searching) {
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, onQuery, Modifier.weight(1f), autoFocus = true)
                    TextButton({ onQuery(""); searching = false }) { Text("취소") }
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun Breadcrumb(app: AppModel, location: LibraryLocation, onNavigate: (Int) -> Unit, modifier: Modifier) {
    val c = NoteTheme.colors
    val parts = RemotePath.components(location.folder)
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (parts.isEmpty()) {
            Text(app.displayName(location.source), style = NoteTheme.Fonts.folderTitle, color = c.textPrimary, maxLines = 1)
        } else {
            Text(app.displayName(location.source), style = NoteTheme.Fonts.body, color = c.textSecondary,
                modifier = Modifier.clickable { onNavigate(0) })
            parts.forEachIndexed { i, name ->
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = c.offline)
                if (i == parts.size - 1) {
                    Text(name, style = NoteTheme.Fonts.folderTitle, color = c.textPrimary, maxLines = 1)
                } else {
                    Text(name, style = NoteTheme.Fonts.body, color = c.textSecondary, modifier = Modifier.clickable { onNavigate(i + 1) })
                }
            }
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "노트와 PDF 검색",
                autoFocus: Boolean = false) {
    val c = NoteTheme.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(modifier.height(40.dp).clip(RoundedCornerShape(NoteTheme.Radius.control)).background(c.fieldFill).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.textSecondary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(placeholder, style = NoteTheme.Fonts.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = c.textPrimary),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Cancel, "검색어 지우기", Modifier.size(18.dp).clickable { onQuery("") }, tint = c.textSecondary)
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun ItemMenu(
    app: AppModel,
    item: LibraryEntry,
    source: LibrarySource,
    dismiss: () -> Unit,
    onRename: (LibraryEntry) -> Unit,
    onMove: (LibraryEntry) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
) {
    val c = NoteTheme.colors
    val ref = LibraryRef(source, item.path)
    val context = LocalContext.current
    fun entry(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = c.textPrimary, action: () -> Unit) =
        @Composable { DropdownMenuItem({ Text(title, color = color) }, { dismiss(); action() }, leadingIcon = { Icon(icon, null, tint = color) }) }
    if (item.kind != LibraryItemKind.FOLDER) entry("열기", Icons.AutoMirrored.Outlined.OpenInNew) { app.open(item, source) }()
    if (item.kind == LibraryItemKind.PDF && !item.isPlaceholder) entry("필기 시작", NoteIcons.pen) { app.annotate(ref) }()
    if (!item.isPlaceholder) {
        entry("이름 변경", Icons.Outlined.Edit) { onRename(item) }()
        entry("이동", Icons.Outlined.DriveFileMove) { onMove(item) }()
        entry("복제", Icons.Outlined.ContentCopy) { app.duplicate(ref) }()
        if (item.kind != LibraryItemKind.FOLDER) {
            val fav = app.isFavorite(ref)
            entry(if (fav) "즐겨찾기 해제" else "즐겨찾기", if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder) { app.toggleFavorite(ref) }()
        }
        if (item.kind == LibraryItemKind.PDF || item.kind == LibraryItemKind.IMAGE || item.kind == LibraryItemKind.OTHER) {
            entry("공유", Icons.Outlined.Share) { shareFile(context, app.file(ref)) }()
        }
        if (source.accountId != null && item.kind != LibraryItemKind.FOLDER && item.kind != LibraryItemKind.NOTEBOOK) {
            entry("다운로드 항목 제거", Icons.Outlined.CloudUpload) { app.evict(item, source) }()
        }
    }
    entry("삭제", Icons.Outlined.Delete, c.danger) { onDelete(item) }()
}

// Cards

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(app: AppModel, item: LibraryEntry, source: LibrarySource, onOpen: () -> Unit, menu: @Composable (() -> Unit) -> Unit) {
    val c = NoteTheme.colors
    var count by remember { mutableStateOf<Int?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    LaunchedEffect(item.path, app.libraryRevision) {
        count = withContext(Dispatchers.IO) { runCatching { app.library(source).list(item.path).size }.getOrNull() }
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NoteTheme.Radius.card))
                .background(c.surface)
                .border(1.dp, c.separator, RoundedCornerShape(NoteTheme.Radius.card))
                .combinedClickable(onClick = onOpen, onLongClick = { showMenu = true })
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(NoteTheme.Radius.control)).background(c.accentSoft),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Folder, null, tint = c.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = NoteTheme.Fonts.bodyEmphasized, color = c.textPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(count?.let { "항목 ${it}개" } ?: " ", style = NoteTheme.Fonts.caption, color = c.textSecondary)
            }
        }
        DropdownMenu(showMenu, { showMenu = false }) { menu { showMenu = false } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(app: AppModel, item: LibraryEntry, source: LibrarySource, onOpen: () -> Unit, menu: @Composable (() -> Unit) -> Unit) {
    val c = NoteTheme.colors
    var summary by remember { mutableStateOf<NotebookSummary?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val file = app.file(LibraryRef(source, item.path))
    LaunchedEffect(item.path, app.libraryRevision, item.modified) {
        when (item.kind) {
            LibraryItemKind.NOTEBOOK -> {
                summary = withContext(Dispatchers.IO) { app.library(source).summary(item.path) }
                if (summary?.coverHex == null) thumbnail = ThumbnailCache.notebook(file)
            }
            LibraryItemKind.PDF -> if (!item.isPlaceholder) thumbnail = ThumbnailCache.pdf(file)
            LibraryItemKind.IMAGE -> if (!item.isPlaceholder) thumbnail = ThumbnailCache.image(file)
            else -> Unit
        }
    }
    Box {
        Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { showMenu = true }),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
                val s = summary
                when {
                    item.kind == LibraryItemKind.NOTEBOOK && s?.coverHex != null ->
                        NotebookCover(s.title, s.coverHex, Modifier.fillMaxSize())
                    item.kind == LibraryItemKind.NOTEBOOK && thumbnail == null && s?.firstPaper != null && s.firstPageSize != null ->
                        PageFrame { PaperPreview(s.firstPaper, s.firstPageSize, Modifier.fillMaxSize()) }
                    item.kind == LibraryItemKind.NOTEBOOK || item.kind == LibraryItemKind.PDF || item.kind == LibraryItemKind.IMAGE ->
                        PageFrame {
                            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                                thumbnail?.let {
                                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } ?: if (item.isPlaceholder) Icon(Icons.Outlined.CloudDownload, null, Modifier.size(30.dp), tint = c.accent) else Unit
                                if (item.kind == LibraryItemKind.PDF) {
                                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                                        Text("PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.danger,
                                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.dangerSoft).padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                    else -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(c.surface)
                        .border(1.dp, c.separator, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(36.dp), tint = c.offline)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = NoteTheme.Fonts.cardTitle, color = c.textPrimary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        (summary?.modified ?: item.modified)?.let { libraryDateLabel(it) },
                        summary?.pageCount?.let { "${it}쪽" },
                        if (item.isPlaceholder) byteLabel(item.size) else null,
                    ).joinToString(" · ")
                    Text(meta, style = NoteTheme.Fonts.caption, color = c.textSecondary, maxLines = 1)
                }
                Spacer(Modifier.width(4.dp))
                SyncBadge(app.badge(item, source), 15.dp)
                if (app.isFavorite(LibraryRef(source, item.path))) {
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.Star, "즐겨찾기", Modifier.size(13.dp), tint = c.conflict)
                }
            }
        }
        DropdownMenu(showMenu, { showMenu = false }) { menu { showMenu = false } }
    }
}

@Composable
private fun PageFrame(content: @Composable () -> Unit) {
    val c = NoteTheme.colors
    Box(Modifier.fillMaxSize().shadow(6.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
        .border(1.dp, c.separator, RoundedCornerShape(4.dp))) { content() }
}

/** Library thumbnails (notebook thumbnail.png, PDF first pages, images). */
object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(f: File) = f.absolutePath + "@" + f.lastModified()

    suspend fun notebook(pkg: File): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(pkg, NotebookPackage.THUMBNAIL_FILE)
        if (!f.exists()) return@withContext null
        cache.get(key(f)) ?: BitmapFactory.decodeFile(f.absolutePath)?.also { cache.put(key(f), it) }
    }

    suspend fun image(f: File): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(key(f)) ?: run {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var s = 1
            while (opts.outWidth / s > 480) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })?.also { cache.put(key(f), it) }
        }
    }

    suspend fun pdf(f: File): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(key(f)) ?: PdfHandle.open(f)?.use { pdf ->
            val size = pdf.pageSizes.firstOrNull() ?: return@use null
            val width = 360
            val scale = width / size.width.toFloat()
            val bmp = Bitmap.createBitmap(width, maxOf(1, (size.height * scale).toInt()), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            PdfSupport.draw(pdf, 0, size, bmp, scale, 0f, 0f)
            bmp
        }?.also { cache.put(key(f), it) }
    }

    fun invalidate(pkg: File) {
        val f = File(pkg, NotebookPackage.THUMBNAIL_FILE)
        cache.remove(key(f))
    }
}

fun shareFile(context: android.content.Context, file: File, mime: String? = null) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val type = mime ?: when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
