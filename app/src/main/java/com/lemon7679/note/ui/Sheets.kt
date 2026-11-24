package com.lemon7679.note.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lemon7679.note.app.AccountSyncStatus
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.libraryDateLabel
import com.lemon7679.note.app.relativeLabel
import com.lemon7679.note.core.ConflictResolution
import com.lemon7679.note.core.CoverStyle
import com.lemon7679.note.core.LibraryEntry
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.NasAccount
import com.lemon7679.note.core.PageSize
import com.lemon7679.note.core.PageSizePreset
import com.lemon7679.note.core.PaperColor
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.PaperTemplate
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.core.SyncConflict
import com.lemon7679.note.core.SyncOptions
import com.lemon7679.note.core.TrashBin
import com.lemon7679.note.ink.InkPage

/** Sheet container: a centered card on tablets, full screen on phones (design: sheet radius 20). */
@Composable
fun NoteSheet(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 720.dp,
    confirm: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    dismissLabel: String = "취소",
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NoteTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.Center) {
            val compact = this.maxWidth < 600.dp
            Column(
                (if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = maxWidth).fillMaxWidth(0.92f).heightIn(max = this.maxHeight * 0.92f))
                    .clip(RoundedCornerShape(if (compact) 0.dp else NoteTheme.Radius.sheet))
                    .background(c.background),
            ) {
                Row(Modifier.fillMaxWidth().height(56.dp).background(c.surface).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(dismissLabel, color = c.accent) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (header != null) header() else Text(title, style = NoteTheme.Fonts.sheetTitle, color = c.textPrimary, maxLines = 1)
                    }
                    if (confirm != null) {
                        TextButton(onConfirm, enabled = confirmEnabled) {
                            Text(confirm, color = if (confirmEnabled) c.accent else c.offline, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Spacer(Modifier.width(64.dp))
                    }
                }
                Hairline()
                Column(Modifier.weight(1f, fill = false)) { content() }
            }
        }
    }
}

enum class NewItemMode(val title: String) { NOTEBOOK("빈 노트"), PDF("PDF 가져오기"), FOLDER("폴더") }

/** "새로 만들기" sheet (design: NewNotebook artboard). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewItemSheet(app: AppModel, location: LibraryLocation, initial: NewItemMode, onPickPdf: () -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    var mode by remember { mutableStateOf(initial) }
    var title by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(CoverStyle.palette[0]) }
    var template by remember { mutableStateOf(app.settings.defaultTemplate) }
    var paperColor by remember { mutableStateOf(app.settings.defaultPaperColor) }
    var size by remember { mutableStateOf(app.settings.defaultSize) }
    var landscape by remember { mutableStateOf(app.settings.defaultLandscape) }
    val pageSize = if (landscape) size.size.landscape else size.size.portrait

    fun create() {
        when (mode) {
            NewItemMode.NOTEBOOK -> {
                onDismiss()
                app.createNotebook(location, title.trim(), cover, PaperStyle(template, paperColor), pageSize)
            }
            NewItemMode.FOLDER -> {
                app.createFolder(title.ifBlank { "새 폴더" }, location)
                onDismiss()
            }
            NewItemMode.PDF -> {
                onDismiss()
                onPickPdf()
            }
        }
    }

    NoteSheet("새로 만들기", onDismiss, maxWidth = 820.dp, confirm = if (mode == NewItemMode.PDF) null else "만들기",
        onConfirm = ::create, header = {
            SegmentedPicker(NewItemMode.entries.map { it.title }, mode.ordinal, Modifier.widthIn(max = 320.dp)) { mode = NewItemMode.entries[it] }
        }) {
        when (mode) {
            NewItemMode.NOTEBOOK -> BoxWithConstraints {
                val wide = maxWidth >= 640.dp
                val preview: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(20.dp)) {
                        Box(Modifier.width(150.dp).height(206.dp)) {
                            val hex = cover
                            if (hex != null) NotebookCover(title.ifBlank { "새 노트" }, hex, Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).border(1.dp, c.separator, RoundedCornerShape(4.dp))) {
                                PaperPreview(PaperStyle(template, paperColor), pageSize, Modifier.fillMaxSize())
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 4) {
                            (CoverStyle.palette + listOf<String?>(null)).forEach { hex ->
                                val selected = cover == hex
                                Box(Modifier.size(32.dp).clip(CircleShape)
                                    .border(2.dp, if (selected) c.textPrimary else Color.Transparent, CircleShape)
                                    .padding(3.dp).clip(CircleShape)
                                    .background(hex?.let { hexColor(it) } ?: Color.White)
                                    .border(1.dp, if (hex == null) c.controlBorder else Color.Transparent, CircleShape)
                                    .clickable { cover = hex })
                            }
                        }
                    }
                }
                val form: @Composable (Modifier) -> Unit = { m ->
                    Column(m.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("제목")
                            OutlinedTextField(title, { title = it }, placeholder = { Text("새 노트") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionLabel("종이")
                            PaperTemplateGrid(template, paperColor) { template = it }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("크기")
                            val sizes = listOf(PageSizePreset.A4, PageSizePreset.B5, PageSizePreset.LETTER, PageSizePreset.A5)
                            SegmentedPicker(sizes.map { it.displayName }, sizes.indexOf(size).coerceAtLeast(0)) { size = sizes[it] }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("방향")
                            SegmentedPicker(listOf("세로", "가로"), if (landscape) 1 else 0) { landscape = it == 1 }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("종이 색")
                            SegmentedPicker(PaperColor.entries.map { it.displayName }, paperColor.ordinal) { paperColor = PaperColor.entries[it] }
                        }
                        LocationRow(app, location)
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(230.dp).background(c.background)) { preview() }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.separator))
                        form(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { preview() }
                        form(Modifier.heightIn(max = 4000.dp))
                    }
                }
            }
            NewItemMode.PDF -> Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(44.dp), tint = c.accent)
                Text("PDF를 가져와 바로 필기합니다", style = NoteTheme.Fonts.sheetTitle, color = c.textPrimary)
                Text("원본은 노트 안에 그대로 보관되고, 필기는 페이지마다 따로 저장되어 NAS로 빠르게 동기화됩니다.",
                    style = NoteTheme.Fonts.body, color = c.textSecondary, modifier = Modifier.widthIn(max = 420.dp))
                PrimaryButton("PDF 선택") { create() }
                Box(Modifier.widthIn(max = 420.dp)) { LocationRow(app, location) }
            }
            NewItemMode.FOLDER -> Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionLabel("폴더 이름")
                OutlinedTextField(title, { title = it }, placeholder = { Text("새 폴더") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LocationRow(app, location)
            }
        }
    }
}

@Composable
private fun LocationRow(app: AppModel, location: LibraryLocation) {
    val c = NoteTheme.colors
    Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(NoteTheme.Radius.card)).background(c.background)
        .border(1.dp, c.separator, RoundedCornerShape(NoteTheme.Radius.card)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(app.sourceIcon(location.source), null, tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("저장 위치", style = NoteTheme.Fonts.caption, color = c.textSecondary)
            Text((listOf(app.displayName(location.source)) + RemotePath.components(location.folder)).joinToString(" › "),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Grid of paper templates with live previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperTemplateGrid(selection: PaperTemplate, color: PaperColor, onSelect: (PaperTemplate) -> Unit) {
    val c = NoteTheme.colors
    val order = listOf(PaperTemplate.BLANK, PaperTemplate.RULED, PaperTemplate.GRID, PaperTemplate.DOTTED, PaperTemplate.CORNELL,
        PaperTemplate.MUSIC, PaperTemplate.NARROW_RULED)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 3) {
        order.forEach { t ->
            val selected = t == selection
            Column(Modifier.weight(1f).clickable { onSelect(t) }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(8.dp))
                    .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.controlBorder, RoundedCornerShape(8.dp))) {
                    PaperPreview(PaperStyle(t, color), PageSize(300.0, 220.0), Modifier.fillMaxSize())
                }
                Text(t.displayName, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) c.accent else c.iconMuted)
            }
        }
        repeat((3 - order.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Folder picker for "이동". Lists the item's own storage; a file on this device (PDF etc.)
 * can also go to a folder of any connected NAS. Each storage's root row carries that
 * storage's icon (this device: tablet, NAS: its kind).
 */
@Composable
fun MoveSheet(app: AppModel, source: LibrarySource, item: LibraryEntry, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val here = LibraryLocation(source, RemotePath.parent(item.path))
    val destinations = remember(item, app.accounts) {
        val own = listOf(LibraryLocation(source, "/")) + app.library(source).allFolders()
            .filter { !RemotePath.isAncestor(item.path, it) }.map { LibraryLocation(source, it) }
        val others = if (app.canMoveAcross(item, source)) {
            app.accounts.flatMap { account ->
                val nas = LibrarySource(account.id)
                listOf(LibraryLocation(nas, "/")) + app.library(nas).allFolders().map { LibraryLocation(nas, it) }
            }
        } else emptyList()
        own + others
    }
    val firstNas = destinations.firstOrNull { it.source != source }
    var target by remember { mutableStateOf(here) }
    NoteSheet("‘${item.displayName}’ 이동", onDismiss, maxWidth = 520.dp, confirm = "이동",
        confirmEnabled = target != here, onConfirm = {
            app.moveAcross(LibraryRef(source, item.path), target)
            onDismiss()
        }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(destinations, key = { it.source.id + ":" + it.folder }) { location ->
                val selected = location == target
                val isRoot = location.folder == "/"
                Column {
                    if (location == firstNas) {
                        Hairline()
                        Text("NAS로 옮기기 · 다음 동기화 때 올라갑니다", style = NoteTheme.Fonts.caption, color = c.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().clickable { target = location }.background(if (selected) c.accentSoft else Color.Transparent)
                        .padding(start = (16 + 18 * RemotePath.depth(location.folder)).dp, end = 16.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isRoot) app.sourceIcon(location.source) else Icons.Outlined.Folder, null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Text(if (isRoot) app.displayName(location.source) else RemotePath.name(location.folder), color = c.textPrimary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// Sync center (design: SyncCenter artboard)

@Composable
fun SyncCenterDialog(app: AppModel, focus: String?, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    LaunchedEffect(Unit) { app.loadConflicts() }
    val accounts = focus?.let { f -> app.account(f)?.let { listOf(it) + app.accounts.filter { a -> a.id != f } } } ?: app.accounts
    NoteSheet("동기화", onDismiss, maxWidth = 480.dp, dismissLabel = "닫기", confirm = "지금 동기화", onConfirm = { app.syncAll(manual = true) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).background(c.surface)) {
            if (accounts.isEmpty()) Text("연결된 NAS가 없습니다.", color = c.textSecondary, modifier = Modifier.padding(20.dp))
            accounts.forEach { account ->
                AccountSyncSection(app, account)
                HorizontalDivider(color = c.separator)
            }
        }
    }
}

@Composable
private fun AccountSyncSection(app: AppModel, account: NasAccount) {
    val c = NoteTheme.colors
    val status = app.status(account.id)
    val conflicts = app.conflicts[account.id].orEmpty()
    Column {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(account.kind.icon, null, tint = c.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = NoteTheme.Fonts.bodyEmphasized, color = c.textPrimary)
                    Text(status.summary, style = NoteTheme.Fonts.caption, color = when (status.phase) {
                        is AccountSyncStatus.Phase.Error -> c.danger
                        AccountSyncStatus.Phase.Syncing -> c.accent
                        else -> c.textSecondary
                    })
                }
                status.progress?.takeIf { status.isSyncing && it.total > 0 }?.let {
                    Text("${(it.fraction * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                }
            }
            if (status.isSyncing) {
                LinearProgressIndicator(progress = { status.progress?.fraction ?: 0f }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.fieldFill)
                status.progress?.currentItem?.let { Text("⇅  $it", fontSize = 13.sp, color = c.textSecondary) }
            } else {
                status.lastReport?.takeIf { it.transferred > 0 || it.errors.isNotEmpty() }?.let { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r.uploaded > 0) Activity(Icons.Outlined.ArrowUpward, "올림 ${r.uploaded}개", c.accent)
                        if (r.downloaded > 0) Activity(Icons.Outlined.ArrowDownward, "받음 ${r.downloaded}개", c.textSecondary)
                        if (r.deletedLocal + r.deletedRemote > 0) Activity(Icons.Outlined.Delete, "삭제 ${r.deletedLocal + r.deletedRemote}개", c.textSecondary)
                        r.errors.take(5).forEach { Activity(Icons.Outlined.ErrorOutline, it, c.danger) }
                    }
                }
            }
        }
        conflicts.forEach { conflict -> ConflictCard(conflict) { app.resolve(conflict, account.id, it) } }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
            OptionToggle("자동 동기화", account.options.autoSync) { v -> app.updateOptions(account.id) { it.copy(autoSync = v) } }
            HorizontalDivider(color = c.separator)
            OptionToggle("Wi-Fi에서만", account.options.wifiOnly) { v -> app.updateOptions(account.id) { it.copy(wifiOnly = v) } }
            HorizontalDivider(color = c.separator)
            OptionToggle("PDF는 열 때 내려받기", account.options.downloadOnDemand) { v -> app.updateOptions(account.id) { it.copy(downloadOnDemand = v) } }
            HorizontalDivider(color = c.separator)
            OptionToggle("충돌 시 둘 다 보관", account.options.keepBothOnConflict) { v -> app.updateOptions(account.id) { it.copy(keepBothOnConflict = v) } }
            HorizontalDivider(color = c.separator)
            OptionToggle("PDF로도 내보내기", account.options.exportPdfCopies) { v -> app.updateOptions(account.id) { it.copy(exportPdfCopies = v) } }
        }
    }
}

@Composable
private fun Activity(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = NoteTheme.colors.textPrimary, maxLines = 2)
    }
}

@Composable
fun OptionToggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val c = NoteTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NoteTheme.Fonts.body, color = c.textPrimary)
            subtitle?.let { Text(it, style = NoteTheme.Fonts.caption, color = c.textSecondary) }
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

/** "‘물리 실험 보고서’가 이 기기와 NAS에서 모두 수정되었습니다" with the three choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictCard(conflict: SyncConflict, resolve: (ConflictResolution) -> Unit) {
    val c = NoteTheme.colors
    Column(Modifier.fillMaxWidth().background(c.conflictSoft.copy(alpha = 0.5f)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, tint = c.conflict)
            Spacer(Modifier.width(10.dp))
            Text("충돌 · ${conflict.displayName}", style = NoteTheme.Fonts.bodyEmphasized, color = c.textPrimary, maxLines = 1)
        }
        Text("‘${conflict.displayName}’이(가) 이 기기(${conflict.localModified?.let { relativeLabel(it) } ?: "-"})와 " +
            "NAS(${conflict.remoteModified?.let { relativeLabel(it) } ?: "-"})에서 모두 수정되었습니다.", fontSize = 13.sp, color = c.iconMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("이 기기 유지") { resolve(ConflictResolution.KEEP_LOCAL) }
            SecondaryButton("NAS 유지") { resolve(ConflictResolution.KEEP_REMOTE) }
            SecondaryButton("둘 다 보관") { resolve(ConflictResolution.KEEP_BOTH) }
        }
    }
}

// Recents, favorites, trash

@Composable
private fun SimpleHeader(title: String, openMenu: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (openMenu != null) 8.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            openMenu?.let { NoteIconButton(Icons.Outlined.Menu, "메뉴", onClick = it) }
            Text(title, style = NoteTheme.Fonts.folderTitle, color = NoteTheme.colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            trailing()
        }
        Hairline()
    }
}

@Composable
fun CollectionScreen(app: AppModel, recents: Boolean, openMenu: (() -> Unit)?) {
    val c = NoteTheme.colors
    val refs = if (recents) app.recents.map { it.ref } else app.favorites
    val items = remember(refs, app.libraryRevision) { app.existingItems(refs) }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SimpleHeader(if (recents) "최근 문서" else "즐겨찾기", openMenu)
        if (items.isEmpty()) {
            EmptyState(if (recents) Icons.Outlined.Schedule else Icons.Outlined.Star,
                if (recents) "최근에 연 문서가 없습니다" else "즐겨찾기가 없습니다",
                if (recents) "노트나 PDF를 열면 여기에 표시됩니다." else "문서를 길게 눌러 즐겨찾기에 추가하세요.")
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(152.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items.size) { i ->
                    val (ref, entry) = items[i]
                    Column {
                        DocumentCard(app, entry, ref.source, onOpen = { app.open(entry, ref.source) }) { dismiss ->
                            androidx.compose.material3.DropdownMenuItem({ Text(if (app.isFavorite(ref)) "즐겨찾기 해제" else "즐겨찾기") },
                                { dismiss(); app.toggleFavorite(ref) })
                        }
                        Text(app.displayName(ref.source), style = NoteTheme.Fonts.caption, color = c.textTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun TrashScreen(app: AppModel, openMenu: (() -> Unit)?) {
    val c = NoteTheme.colors
    val sources = listOf(LibrarySource.LOCAL) + app.accounts.map { LibrarySource(it.id) }
    var revision by remember { mutableStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val entries = remember(revision, app.libraryRevision, app.accounts) {
        sources.flatMap { s -> app.trash(s).entries().map { s to it } }.sortedByDescending { it.second.deletedAt }
    }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SimpleHeader("휴지통", openMenu) {
            if (entries.isNotEmpty()) TextButton({ confirmEmpty = true }) { Text("비우기", color = c.danger) }
        }
        if (entries.isEmpty()) {
            EmptyState(Icons.Outlined.Delete, "휴지통이 비어 있습니다", "삭제한 노트와 파일은 여기에서 복원할 수 있습니다.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                item {
                    CardGroup {
                        entries.forEachIndexed { i, (source, entry) ->
                            if (i > 0) CardDivider()
                            TrashRow(app, source, entry) { revision++ }
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(onDismissRequest = { confirmEmpty = false }, title = { Text("휴지통을 비울까요?") },
            text = { Text("항목이 영구적으로 삭제됩니다.") },
            confirmButton = {
                TextButton({
                    sources.forEach { runCatching { app.trash(it).empty() } }
                    confirmEmpty = false
                    revision++
                }) { Text("비우기", color = c.danger) }
            },
            dismissButton = { TextButton({ confirmEmpty = false }) { Text("취소") } })
    }
}

@Composable
private fun TrashRow(app: AppModel, source: LibrarySource, entry: TrashBin.Entry, changed: () -> Unit) {
    val c = NoteTheme.colors
    val kind = LibraryItemKind.classify(RemotePath.name(entry.originalPath), false)
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(when (kind) {
            LibraryItemKind.NOTEBOOK -> Icons.Outlined.MenuBook
            LibraryItemKind.PDF -> Icons.Outlined.PictureAsPdf
            else -> Icons.Outlined.Folder
        }, null, tint = c.textSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.displayName, style = NoteTheme.Fonts.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${app.displayName(source)} · ${libraryDateLabel(entry.deletedAt)} 삭제", style = NoteTheme.Fonts.caption, color = c.textSecondary)
        }
        NoteIconButton(Icons.Outlined.Restore, "복원") { app.restore(entry, source); changed() }
        NoteIconButton(Icons.Outlined.DeleteForever, "영구 삭제", tint = c.danger) {
            runCatching { app.trash(source).remove(entry) }
            changed()
        }
    }
}

// Settings

@Composable
fun SettingsScreen(app: AppModel, onEditAccount: (NasAccount) -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val s = app.settings
    NoteSheet("설정", onDismiss, maxWidth = 640.dp, dismissLabel = "완료") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("필기")
            CardGroup {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("스타일러스로만 그리기", s.stylusOnly, "끄면 손가락으로도 그리고, 두 손가락으로 스크롤합니다.") { v -> app.updateSettings { it.copy(stylusOnly = v) } }
                }
                CardDivider()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("두 손가락 탭: 실행 취소 · 세 손가락 탭: 다시 실행", s.tapGesturesUndo) { v -> app.updateSettings { it.copy(tapGesturesUndo = v) } }
                }
                CardDivider()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("낙서로 지우기", s.scribbleToErase, "펜으로 글씨 위를 여러 번 왔다 갔다 문지르면 그 아래 필기가 지워집니다.") { v -> app.updateSettings { it.copy(scribbleToErase = v) } }
                }
                CardDivider()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    OptionToggle("GPU 렌더링", s.gpuRendering,
                        "그래픽 칩으로 필기를 그려 확대·스크롤이 빠르고, 펜 끝 지연이 줄어듭니다. 화면이 이상하면 꺼 보세요.") { v ->
                        app.updateSettings { it.copy(gpuRendering = v) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("새 노트 기본값")
            CardGroup(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("종이", style = NoteTheme.Fonts.body, color = c.textPrimary)
                    PaperTemplateGrid(s.defaultTemplate, s.defaultPaperColor) { t -> app.updateSettings { it.copy(defaultTemplate = t) } }
                    Text("종이 색", style = NoteTheme.Fonts.body, color = c.textPrimary)
                    SegmentedPicker(PaperColor.entries.map { it.displayName }, s.defaultPaperColor.ordinal) { i ->
                        app.updateSettings { it.copy(defaultPaperColor = PaperColor.entries[i]) }
                    }
                    Text("크기", style = NoteTheme.Fonts.body, color = c.textPrimary)
                    SegmentedPicker(PageSizePreset.entries.map { it.displayName }, s.defaultSize.ordinal) { i ->
                        app.updateSettings { it.copy(defaultSize = PageSizePreset.entries[i]) }
                    }
                    OptionToggle("가로 방향", s.defaultLandscape) { v -> app.updateSettings { it.copy(defaultLandscape = v) } }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("NAS")
            CardGroup {
                app.accounts.forEachIndexed { i, account ->
                    if (i > 0) CardDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(account.kind.icon, null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = NoteTheme.Fonts.body, color = c.textPrimary)
                            Text("${account.displayAddress} · ${account.syncFolder}", style = NoteTheme.Fonts.caption, color = c.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton({ onEditAccount(account) }) { Text("설정") }
                        TextButton({ app.removeAccount(account.id) }) { Text("해제", color = c.danger) }
                    }
                }
                if (app.accounts.isNotEmpty()) CardDivider()
                Row(Modifier.fillMaxWidth().clickable { onEditAccount(newAccountDraft()) }.height(52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sync, null, tint = c.accent)
                    Spacer(Modifier.width(12.dp))
                    Text("저장소 연결", color = c.accent, style = NoteTheme.Fonts.bodyEmphasized)
                }
            }
            Text("NAS의 노트 폴더는 이 기기에 미러로 보관되어 오프라인에서도 열리고, 연결되면 양방향으로 동기화됩니다. 암호는 Android 키스토어로 암호화해 저장합니다.",
                style = NoteTheme.Fonts.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
            SectionLabel("정보")
            CardGroup {
                InfoRow("필기 엔진", "InkEngine ${runCatching { InkPage.engineVersion }.getOrDefault("-")}")
                CardDivider()
                InfoRow("앱 버전", com.lemon7679.note.BuildConfig.VERSION_NAME)
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val c = NoteTheme.colors
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = NoteTheme.Fonts.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = NoteTheme.Fonts.body, color = c.textSecondary)
    }
}
