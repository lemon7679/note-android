package com.lemon7679.note.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.DocumentPlacement
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.app.PaneRole
import com.lemon7679.note.app.backTargetId
import com.lemon7679.note.app.closeDocument
import com.lemon7679.note.app.closeWorkspace
import com.lemon7679.note.app.openDocumentCount
import com.lemon7679.note.core.FileNaming
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.render.PdfHandle
import com.lemon7679.note.render.PdfSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PdfHit(val page: Int, val rect: RectF, val context: String)

/** PDF viewer (design: PDF artboard): continuous pages, thumbnails, search, scrubber, "필기 시작". */
@Composable
fun PdfReaderScreen(app: AppModel, doc: OpenDocument, role: PaneRole = PaneRole.SINGLE) {
    val c = NoteTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdf = remember(doc.file) { PdfHandle.open(doc.file) }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    // The library button closes everything; the right pane closes only itself.
    val close = { if (role.closesWorkspace) app.closeWorkspace() else app.closeDocument(doc.id) }
    BackHandler(app.backTargetId == doc.id) { app.closeDocument(doc.id) }
    // Moving to another pane or window rebuilds this screen: come back to the same page.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = app.sessions.pdfPage(doc.id))
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { app.sessions.setPdfPage(doc.id, it) }
    }
    var showThumbs by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PdfHit>>(emptyList()) }
    var hitIndex by remember { mutableStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val pageCount = pdf?.pageCount ?: 0
    val current by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex } }

    val title = FileNaming.split(doc.file.name).first
    Column(Modifier.fillMaxSize().background(c.desk).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (role == PaneRole.FLOATING) {
            FloatingHeader(app, doc.id, title, Icons.Outlined.PictureAsPdf) {
                NoteIconButton(Icons.Outlined.Search, "문서 내 검색", isOn = searching) { searching = !searching }
            }
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            Row(Modifier.fillMaxWidth().height(52.dp).background(c.surface).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (role.closesWorkspace) {
                    Row(Modifier.clickable { close() }.padding(end = 6.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChevronLeft, if (app.openDocumentCount > 1) "모두 닫고 라이브러리로" else "라이브러리", tint = c.accent)
                        if (!compact) Text("라이브러리", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    NoteIconButton(Icons.Outlined.Close, "이 창 닫기") { close() }
                }
                NoteIconButton(Icons.Outlined.ViewSidebar, "페이지 목록", isOn = showThumbs) { showThumbs = !showThumbs }
                WindowMenuButton(app, doc.id)
                NoteIconButton(Icons.Outlined.Search, "문서 내 검색", isOn = searching) { searching = !searching }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                if (doc.isRemoteCopy) {
                    if (compact) {
                        NoteIconButton(Icons.Outlined.FileDownload, "노트 폴더로 가져오기") {
                            app.perform(LibrarySource(doc.ref.source.accountId)) { it.importFile(doc.file, "/") }
                        }
                    } else TextButton({
                        app.perform(LibrarySource(doc.ref.source.accountId)) { it.importFile(doc.file, "/") }
                    }) {
                        Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                        Text(" 노트 폴더로 가져오기")
                    }
                } else if (compact) {
                    NoteIconButton(NoteIcons.pen, "필기 시작", tint = c.accent) { app.annotate(doc.ref, DocumentPlacement.Replacing(doc.id)) }
                } else {
                    // The notebook takes this PDF's place (pane or window).
                    PrimaryButton("필기 시작", icon = NoteIcons.pen) { app.annotate(doc.ref, DocumentPlacement.Replacing(doc.id)) }
                }
                if (!compact) NoteIconButton(Icons.Outlined.Share, "공유") { shareFile(context, doc.file, "application/pdf") }
            }
        }
        Hairline()
        if (searching) {
            Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.weight(1f), placeholder = "PDF에서 찾기", autoFocus = true)
                Spacer(Modifier.width(8.dp))
                Text(if (hits.isEmpty()) "" else "${hitIndex + 1} / ${hits.size}", fontSize = 13.sp, color = c.textSecondary)
                NoteIconButton(Icons.Outlined.KeyboardArrowUp, "이전", enabled = hits.isNotEmpty()) {
                    hitIndex = (hitIndex - 1 + hits.size) % hits.size
                    scope.launch { list.animateScrollToItem(hits[hitIndex].page) }
                }
                NoteIconButton(Icons.Outlined.KeyboardArrowDown, "다음", enabled = hits.isNotEmpty()) {
                    hitIndex = (hitIndex + 1) % hits.size
                    scope.launch { list.animateScrollToItem(hits[hitIndex].page) }
                }
            }
            if (Build.VERSION.SDK_INT < 35) {
                Text("문서 내 검색은 Android 15 이상에서 지원됩니다.", fontSize = 13.sp, color = c.textSecondary,
                    modifier = Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 6.dp))
            }
            Hairline()
            LaunchedEffect(query) {
                val q = query.trim()
                if (q.length < 1 || pdf == null) {
                    hits = emptyList()
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(250)
                hits = withContext(Dispatchers.IO) {
                    (0 until pdf.pageCount).flatMap { i -> pdf.search(i, q).map { PdfHit(i, it, q) } }
                }
                hitIndex = 0
                hits.firstOrNull()?.let { list.animateScrollToItem(it.page) }
            }
        }
        Row(Modifier.weight(1f)) {
            if (showThumbs && pdf != null) {
                LazyColumn(Modifier.width(132.dp).fillMaxHeight().background(c.background), contentPadding = PaddingValues(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(List(pageCount) { it }) { i, _ ->
                        val size = pdf.pageSizes[i]
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { scope.launch { list.scrollToItem(i) } }) {
                            Box(Modifier.width(84.dp).aspectRatio((size.width / size.height).toFloat()).clip(RoundedCornerShape(3.dp))
                                .border(if (i == current) 2.dp else 1.dp, if (i == current) c.accent else c.separator, RoundedCornerShape(3.dp))) {
                                PdfPageImage(pdf, i, 168)
                            }
                            Text("${i + 1}", fontSize = 12.sp, color = if (i == current) c.accent else c.textSecondary,
                                fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.separator))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        val z = (zoom * zoomChange).coerceIn(1f, 5f)
                        zoom = z
                        pan = if (z == 1f) Offset.Zero else Offset(
                            (pan.x + panChange.x).coerceIn(-size.width * (z - 1) / 2, size.width * (z - 1) / 2), 0f)
                    }
                }) {
                if (pdf == null) {
                    Text("PDF를 열 수 없습니다.", color = c.textSecondary, modifier = Modifier.align(Alignment.Center))
                } else {
                    val density = LocalDensity.current
                    val pageWidthDp = minOf(maxWidth - 32.dp, 900.dp).coerceAtLeast(120.dp)
                    val pagePx = with(density) { pageWidthDp.toPx() }.roundToInt()
                    LazyColumn(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x },
                        state = list, contentPadding = PaddingValues(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(List(pageCount) { it }) { i, _ ->
                            val size = pdf.pageSizes[i]
                            val pageHits = hits.filter { it.page == i }
                            Box(Modifier.width(pageWidthDp).aspectRatio((size.width / size.height).toFloat()).shadow(4.dp)
                                .background(Color.White)
                                .drawWithContent {
                                    drawContent()
                                    val s = this.size.width / size.width.toFloat()
                                    pageHits.forEachIndexed { k, h ->
                                        val active = hits.indexOf(h) == hitIndex
                                        drawRect(if (active) Color(0x88FFC107) else Color(0x55FFEB3B),
                                            topLeft = Offset(h.rect.left * s, h.rect.top * s), size = Size(h.rect.width() * s, h.rect.height() * s))
                                    }
                                }) {
                                PdfPageImage(pdf, i, (pagePx * 1.5f).roundToInt().coerceAtMost(2400))
                            }
                        }
                    }
                    // Page indicator + scrubber.
                    Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${current + 1} / $pageCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 6.dp))
                        if (pageCount > 1) {
                            Slider(current.toFloat(), { v -> scope.launch { list.scrollToItem(v.roundToInt()) } }, valueRange = 0f..(pageCount - 1).toFloat(),
                                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** One PDF page rendered at `pixelWidth` (off the main thread). */
@Composable
fun PdfPageImage(pdf: PdfHandle, index: Int, pixelWidth: Int) {
    var bitmap by remember(index, pixelWidth) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, pixelWidth) {
        bitmap = withContext(Dispatchers.IO) {
            val size = pdf.pageSizes[index]
            val scale = pixelWidth / size.width.toFloat()
            val bmp = Bitmap.createBitmap(pixelWidth.coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            PdfSupport.draw(pdf, index, size, bmp, scale, 0f, 0f)
            bmp
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
}
