package com.lemon7679.note.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.app.AccountSyncStatus
import com.lemon7679.note.app.AppModel
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.isWorkspaceOpen
import com.lemon7679.note.core.NasAccount
import com.lemon7679.note.core.NasKind
import kotlinx.coroutines.launch

sealed class SidebarItem {
    data object Recents : SidebarItem()
    data object Favorites : SidebarItem()
    data object Trash : SidebarItem()
    data class Source(val source: LibrarySource) : SidebarItem()
}

val NasKind.icon: ImageVector
    get() = when (this) {
        NasKind.SYNOLOGY -> Icons.Outlined.Dns
        NasKind.WEBDAV -> Icons.Outlined.Language
        NasKind.FILES_FOLDER -> Icons.Outlined.FolderShared
    }

/** Icon of a storage: a tablet for this device, the NAS kind's icon for an account. */
fun AppModel.sourceIcon(source: LibrarySource): ImageVector =
    source.accountId?.let { account(it)?.kind?.icon ?: Icons.Outlined.Dns } ?: Icons.Outlined.TabletAndroid

fun newAccountDraft() = NasAccount(name = "", kind = NasKind.SYNOLOGY, syncFolder = "/home/Notes")

@Composable
fun RootScreen(app: AppModel) {
    var selection by remember { mutableStateOf<SidebarItem>(SidebarItem.Source(LibrarySource.LOCAL)) }
    var connecting by remember { mutableStateOf<NasAccount?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<NasAccount?>(null) }

    LaunchedEffect(app.accounts.map { it.id }) {
        val s = selection
        if (s is SidebarItem.Source && s.source.accountId != null && app.account(s.source.accountId) == null) {
            selection = SidebarItem.Source(LibrarySource.LOCAL)
        }
    }

    val covered = app.isWorkspaceOpen
    BoxWithConstraints(Modifier.fillMaxSize().background(NoteTheme.colors.background)
        .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
        val wide = maxWidth >= 840.dp
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val sidebar: @Composable () -> Unit = {
            Sidebar(app, selection, onSelect = {
                selection = it
                scope.launch { drawerState.close() }
            }, onConnect = { connecting = it }, onSettings = { showSettings = true }, onBrowse = { browsing = it })
        }
        val detail: @Composable () -> Unit = {
            val openMenu: (() -> Unit)? = if (wide) null else ({ scope.launch { drawerState.open() } })
            when (val s = selection) {
                is SidebarItem.Source -> LibraryBrowser(app, s.source, openMenu)
                SidebarItem.Recents -> CollectionScreen(app, recents = true, openMenu)
                SidebarItem.Favorites -> CollectionScreen(app, recents = false, openMenu)
                SidebarItem.Trash -> TrashScreen(app, openMenu)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(288.dp).fillMaxHeight()) { sidebar() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(NoteTheme.colors.separator))
                Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
            }
        } else {
            BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
            ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
                ModalDrawerSheet(drawerContainerColor = NoteTheme.colors.sidebar, modifier = Modifier.width(300.dp)) { sidebar() }
            }) { detail() }
        }
    }

    // Open documents cover the library: one, two split, and floating windows.
    if (app.isWorkspaceOpen) {
        FullScreen { WorkspaceScreen(app) }
    }
    connecting?.let { account -> NasConnectScreen(app, account) { connecting = null } }
    browsing?.let { account -> RemoteBrowserScreen(app, account) { browsing = null } }
    if (showSettings) SettingsScreen(app, onEditAccount = { connecting = it }) { showSettings = false }

    app.errorMessage?.let { message ->
        AlertDialog(onDismissRequest = { app.errorMessage = null }, title = { Text("알림") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { app.errorMessage = null }) { Text("확인") } })
    }
    app.toast?.let { message ->
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(message, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** Full-window layer above the library (editor, PDF viewer). */
@Composable
fun FullScreen(content: @Composable () -> Unit) {
    // The pointerInput makes this layer the hit target everywhere, so touches on empty parts
    // of the editor never reach the library composed underneath.
    Box(Modifier.fillMaxSize().background(NoteTheme.colors.background).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    app: AppModel,
    selection: SidebarItem,
    onSelect: (SidebarItem) -> Unit,
    onConnect: (NasAccount) -> Unit,
    onSettings: () -> Unit,
    onBrowse: (NasAccount) -> Unit,
) {
    val c = NoteTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.sidebar)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(c.textPrimary), contentAlignment = Alignment.Center) {
                Icon(NoteIcons.pen, null, Modifier.size(18.dp), tint = c.background)
            }
            Spacer(Modifier.width(10.dp))
            Text("Note", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            NoteIconButton(Icons.Outlined.Tune, "설정", onClick = onSettings)
        }
        Spacer(Modifier.size(22.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("라이브러리", Modifier.padding(start = 6.dp, bottom = 4.dp))
                SidebarRow(Icons.Outlined.Schedule, "최근 문서", selection == SidebarItem.Recents) { onSelect(SidebarItem.Recents) }
                SidebarRow(Icons.Outlined.Star, "즐겨찾기", selection == SidebarItem.Favorites) { onSelect(SidebarItem.Favorites) }
                SidebarRow(Icons.Outlined.Delete, "휴지통", selection == SidebarItem.Trash) { onSelect(SidebarItem.Trash) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("저장소", Modifier.padding(start = 6.dp, bottom = 4.dp))
                val local = SidebarItem.Source(LibrarySource.LOCAL)
                SidebarRow(Icons.Outlined.TabletAndroid, "이 기기", selection == local) { onSelect(local) }
                for (account in app.accounts) {
                    var menu by remember { mutableStateOf(false) }
                    val item = SidebarItem.Source(LibrarySource(account.id))
                    Box {
                        SidebarRow(account.kind.icon, account.name, selection == item, subtitle = account.displayAddress,
                            trailing = { AccountTrailing(app, account) }, onLongClick = { menu = true }) { onSelect(item) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("지금 동기화") }, { menu = false; app.sync(account.id, manual = true) },
                                leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                            if (account.kind != NasKind.FILES_FOLDER) {
                                DropdownMenuItem({ Text("NAS 전체 보기") }, { menu = false; onBrowse(account) },
                                    leadingIcon = { Icon(Icons.Outlined.Storage, null) })
                            }
                            DropdownMenuItem({ Text("연결 설정") }, { menu = false; onConnect(account) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                            DropdownMenuItem({ Text("연결 해제", color = c.danger) }, { menu = false; app.removeAccount(account.id) },
                                leadingIcon = { Icon(Icons.Outlined.Close, null, tint = c.danger) })
                        }
                    }
                }
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(NoteTheme.Radius.control))
                        .dashedBorder(c.controlBorder)
                        .clickable { onConnect(newAccountDraft()) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("저장소 연결", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.accent)
                }
                if (app.accounts.isNotEmpty()) {
                    Text("길게 눌러 동기화 · NAS 전체 보기 · 연결 설정", style = NoteTheme.Fonts.caption, color = c.textTertiary,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                }
            }
        }
        val current = (selection as? SidebarItem.Source)?.source?.accountId?.let { app.account(it) } ?: app.accounts.firstOrNull()
        if (current != null) {
            Spacer(Modifier.size(12.dp))
            SyncSummaryCard(current, app.status(current.id), app.conflicts[current.id]?.size ?: 0) { app.sync(current.id, manual = true) }
        }
    }
}

fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    drawRoundRect(color, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
}

@Composable
private fun AccountTrailing(app: AppModel, account: NasAccount) {
    val c = NoteTheme.colors
    val status = app.status(account.id)
    val conflicts = app.conflicts[account.id]?.size ?: 0
    when {
        conflicts > 0 -> StatusPill("충돌 $conflicts", c.conflict, c.conflictSoft)
        status.isSyncing -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
        else -> {
            val color = when (status.phase) {
                is AccountSyncStatus.Phase.Error -> c.danger
                AccountSyncStatus.Phase.Offline, AccountSyncStatus.Phase.WaitingForWifi -> c.offline
                else -> if (status.lastSync == null) c.offline else c.synced
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = NoteTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) 42.dp else 54.dp)
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(NoteTheme.Radius.control)) else Modifier)
            .clip(RoundedCornerShape(NoteTheme.Radius.control))
            .background(if (selected) c.surface else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) c.accent else c.textSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = NoteTheme.Fonts.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let { Spacer(Modifier.width(4.dp)); it() }
    }
}

/** Bottom card (design: "Home NAS 동기화됨 · 2분 전"). */
@Composable
fun SyncSummaryCard(account: NasAccount, status: AccountSyncStatus, conflicts: Int, syncNow: () -> Unit) {
    val c = NoteTheme.colors
    val (icon, color, title) = when (status.phase) {
        is AccountSyncStatus.Phase.Error -> Triple(Icons.Outlined.ErrorOutline, c.danger, "${account.name} 동기화 오류")
        AccountSyncStatus.Phase.Syncing -> Triple(Icons.Outlined.Sync, c.accent, "${account.name} 동기화 중")
        AccountSyncStatus.Phase.Offline -> Triple(Icons.Outlined.WifiOff, c.offline, "${account.name} 오프라인")
        AccountSyncStatus.Phase.WaitingForWifi -> Triple(Icons.Outlined.WifiOff, c.offline, "${account.name} Wi-Fi 대기")
        AccountSyncStatus.Phase.Idle -> if (conflicts > 0) Triple(Icons.Outlined.Warning, c.conflict, account.name)
        else Triple(Icons.Outlined.CheckCircle, c.synced, if (status.lastSync == null) account.name else "${account.name} 동기화됨")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NoteTheme.Radius.card))
            .background(c.surface)
            .border(1.dp, c.separator, RoundedCornerShape(NoteTheme.Radius.card))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            NoteIconButton(Icons.Outlined.Sync, "지금 동기화", enabled = !status.isSyncing, iconSize = 18.dp, tint = c.accent, onClick = syncNow)
        }
        Text(status.summary, style = NoteTheme.Fonts.caption, color = c.textSecondary, maxLines = 2)
        AnimatedVisibility(status.isSyncing) {
            LinearProgressIndicator(progress = { status.progress?.fraction ?: 0f }, Modifier.fillMaxWidth(), color = c.accent,
                trackColor = c.fieldFill)
        }
        if (conflicts > 0) {
            Text("충돌 ${conflicts}건 — 동기화 센터에서 정리하세요", style = NoteTheme.Fonts.caption, color = c.conflict)
        }
    }
}
