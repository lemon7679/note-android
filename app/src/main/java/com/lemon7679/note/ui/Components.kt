package com.lemon7679.note.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.app.SyncBadgeState
import com.lemon7679.note.core.PageSize
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.render.PaperRenderer

/** 44×44 icon button; `isOn` shows the selected tool state (accent on soft accent). */
@Composable
fun NoteIconButton(
    icon: ImageVector,
    label: String,
    isOn: Boolean = false,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val c = NoteTheme.colors
    Box(
        Modifier
            .size(NoteTheme.minTouch)
            .then(if (enabled) Modifier.hoverHighlight(RoundedCornerShape(NoteTheme.Radius.control)) else Modifier)
            .clip(RoundedCornerShape(NoteTheme.Radius.control))
            .background(if (isOn) c.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; selected = isOn },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize),
            tint = tint ?: if (isOn) c.accent else if (enabled) c.iconMuted else c.offline)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null,
                  onClick: () -> Unit) {
    val c = NoteTheme.colors
    Row(
        modifier
            .heightIn(min = 40.dp)
            .then(if (enabled) Modifier.hoverHighlight(RoundedCornerShape(NoteTheme.Radius.control), scale = 1.03f) else Modifier)
            .clip(RoundedCornerShape(NoteTheme.Radius.control))
            .background(if (enabled) c.accent else c.offline)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp).padding(end = 0.dp), tint = Color.White); Spacer(Modifier.width(6.dp)) }
        Text(text, style = NoteTheme.Fonts.bodyEmphasized, color = Color.White, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = NoteTheme.colors
    Box(
        modifier
            .heightIn(min = 40.dp)
            .then(if (enabled) Modifier.hoverHighlight(RoundedCornerShape(NoteTheme.Radius.control), scale = 1.03f) else Modifier)
            .clip(RoundedCornerShape(NoteTheme.Radius.control))
            .background(c.surface)
            .border(1.dp, c.controlBorder, RoundedCornerShape(NoteTheme.Radius.control))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) c.textPrimary else c.offline,
            maxLines = 1)
    }
}

/** Filter chip (전체 / 노트 / PDF / 폴더). */
@Composable
fun NoteChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = NoteTheme.colors
    Box(
        Modifier
            .height(34.dp)
            .hoverHighlight(CircleShape, scale = 1.05f)
            .clip(CircleShape)
            .background(if (selected) c.chipSelected else c.surface)
            .border(1.dp, if (selected) Color.Transparent else c.controlBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) c.chipSelectedText else c.iconMuted)
    }
}

val SyncBadgeState.icon: ImageVector
    get() = when (this) {
        SyncBadgeState.SYNCED -> Icons.Outlined.CheckCircle
        SyncBadgeState.SYNCING -> Icons.Outlined.Sync
        SyncBadgeState.CONFLICT -> Icons.Outlined.Warning
        SyncBadgeState.LOCAL_ONLY -> Icons.Outlined.CloudOff
        SyncBadgeState.CLOUD_ONLY -> Icons.Outlined.CloudDownload
        SyncBadgeState.ERROR -> Icons.Outlined.ErrorOutline
    }

@Composable
fun SyncBadgeState.color(): Color {
    val c = NoteTheme.colors
    return when (this) {
        SyncBadgeState.SYNCED -> c.synced
        SyncBadgeState.SYNCING, SyncBadgeState.CLOUD_ONLY -> c.accent
        SyncBadgeState.CONFLICT -> c.conflict
        SyncBadgeState.LOCAL_ONLY -> c.offline
        SyncBadgeState.ERROR -> c.danger
    }
}

@Composable
fun SyncBadge(state: SyncBadgeState, size: Dp = 16.dp) {
    Icon(state.icon, state.label, Modifier.size(size), tint = state.color())
}

/** Pill used for statuses ("동기화됨", "충돌 1"). */
@Composable
fun StatusPill(text: String, foreground: Color, background: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(background).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(11.dp), tint = foreground); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = foreground, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp), style = NoteTheme.Fonts.sectionLabel, color = NoteTheme.colors.textSecondary)
}

/** Grouped settings-style container (card with hairline rows). */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = NoteTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(NoteTheme.Radius.card))
            .background(c.surface)
            .border(1.dp, c.separator, RoundedCornerShape(NoteTheme.Radius.card)),
        content = content,
    )
}

@Composable
fun CardDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(NoteTheme.colors.separator))
}

/** Colored notebook cover with a label (design: radius 6/12, spine line, white label). */
@Composable
fun NotebookCover(title: String, colorHex: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    Box(modifier.shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.14f)).clip(shape).background(hexColor(colorHex))) {
        Box(Modifier.padding(start = 10.dp).width(2.dp).fillMaxSize().background(Color.White.copy(alpha = 0.22f)))
        Box(
            Modifier
                .padding(start = 24.dp, end = 16.dp, top = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hexColor("#15171C"), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Live preview of a paper template (fills the box, drawn in page units). */
@Composable
fun PaperPreview(paper: PaperStyle, size: PageSize, modifier: Modifier = Modifier) {
    Canvas(modifier.background(hexColor(paper.color.hex))) {
        val scale = this.size.width / size.width.toFloat()
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.scale(scale, scale)
            PaperRenderer.draw(paper, PageSize(size.width, this.size.height / scale.toDouble()), nc)
            nc.restore()
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector?, title: String, message: String, actions: @Composable () -> Unit = {}) {
    val c = NoteTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(40.dp), tint = c.offline) }
        Text(title, style = NoteTheme.Fonts.sheetTitle, color = c.textPrimary)
        Text(message, style = NoteTheme.Fonts.body, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(NoteTheme.colors.separator))
}

@Composable
fun SegmentedPicker(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = NoteTheme.colors
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(9.dp)).background(c.fieldFill).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, title ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .hoverHighlight(RoundedCornerShape(7.dp), scale = 1f)
                    .clip(RoundedCornerShape(7.dp))
                    .then(if (on) Modifier.background(c.surface).border(BorderStroke(0.5.dp, c.separator), RoundedCornerShape(7.dp)) else Modifier)
                    .clickable { onSelect(i) }
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = c.textPrimary, maxLines = 1)
            }
        }
    }
}
