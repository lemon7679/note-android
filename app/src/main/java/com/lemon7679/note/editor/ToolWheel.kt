package com.lemon7679.note.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.ui.NoteTheme
import com.lemon7679.note.ui.hexColor
import com.lemon7679.note.ui.hoverHighlight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry around the anchor (the pen tip), in dp.
 * Angles are degrees on screen: 0 = right, 90 = down, 180 = left, 270 = up.
 */
private object PaletteLayout {
    const val RADIUS = 140f
    const val BAND = 56f
    const val ITEM = 42f
    const val BAND_START = 160f
    const val BAND_END = 297f
    const val UNDO_ANGLE = 173f
    const val DIVIDER_ANGLE = 183.5f
    const val FIRST_TOOL = 194f
    const val TOOL_STEP = 18.5f

    /** Width/color bar attached to the band's top end. */
    const val BAR_HEIGHT = 48f
    const val BAR_SLOT = 36f
    const val BAR_LEAD = 30f
    const val BAR_WIDTH = BAR_LEAD + 4 * BAR_SLOT + 1 + 4 * 4 + 8

    /** Left column (pen settings, pen kinds, redo). */
    const val COLUMN_WIDTH = 48f
    const val COLUMN_HEIGHT = 3 * 40f + 2 * 4 + 8
    val COLUMN_CENTER = Offset(-200f, -62f)
    const val PANEL_WIDTH = 236f

    /** Extent of everything around the anchor, for keeping the palette on screen. */
    const val MIN_X = -230f
    const val MAX_X = 282f
    const val MIN_Y = -172f
    const val MAX_Y = 110f

    fun point(angle: Float, r: Float = RADIUS): Offset {
        val rad = angle * PI / 180
        return Offset((r * cos(rad)).toFloat(), (r * sin(rad)).toFloat())
    }

    /** The band's top end, where the bar starts. */
    val BAR_START = point(BAND_END)

    /** Top-left of the bar, relative to the anchor. */
    val BAR_ORIGIN = Offset(BAR_START.x - 22f, BAR_START.y - BAR_HEIGHT / 2)
    val COLUMN_ORIGIN = Offset(COLUMN_CENTER.x - COLUMN_WIDTH / 2, COLUMN_CENTER.y - COLUMN_HEIGHT / 2)
    val PANEL_ORIGIN = Offset(BAR_ORIGIN.x, BAR_ORIGIN.y + BAR_HEIGHT + 8f)

    /** Whether [p] (relative to the anchor) is on the band, the bar or the column. */
    fun onSurface(p: Offset): Boolean {
        fun inRect(o: Offset, w: Float, h: Float) = p.x in o.x..(o.x + w) && p.y in o.y..(o.y + h)
        if (inRect(BAR_ORIGIN, BAR_WIDTH, BAR_HEIGHT) || inRect(COLUMN_ORIGIN, COLUMN_WIDTH, COLUMN_HEIGHT)) return true
        val ends = listOf(point(BAND_START), point(BAND_END))
        if (ends.any { (p - it).getDistance() <= BAND / 2 }) return true
        if (abs(hypot(p.x, p.y) - RADIUS) > BAND / 2) return false
        val angle = ((atan2(p.y, p.x) * 180 / PI).toFloat() + 360f) % 360f
        return angle in BAND_START..BAND_END
    }
}

private enum class PalettePanel { COLORS, KINDS }

private val PALETTE_TOOLS = listOf(
    EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.ERASER, EditorTool.LASSO, EditorTool.TEXT, EditorTool.LASER,
)

/**
 * Tool palette opened by the stylus side button, shown around the pen tip at [anchor] (px in the
 * canvas; [Offset.Unspecified] = the middle), GoodNotes-style:
 *  - a curved band wrapping the upper left of the tip with the tools, and undo at its lower end;
 *  - at the band's top end, the width presets and the current color (tap the color for all colors);
 *  - a small column to the left: pen settings, pen kinds and redo.
 * Tapping outside closes it, as does picking a tool or a color.
 */
@Composable
fun ToolWheel(model: EditorModel, anchor: Offset, onPenSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }

    val t = model.tools
    val inkTarget = if (t.tool.inks || t.tool == EditorTool.TEXT) t.tool else t.lastInkTool
    val highlighter = inkTarget == EditorTool.HIGHLIGHTER
    val palette = ((if (highlighter) NoteTheme.highlighterColors else NoteTheme.penColors) + t.customColors).distinctBy { it.uppercase() }
    val activeColor = if (highlighter) t.highlighterColor else t.penColor
    val widths = if (highlighter) t.highlighterWidths else t.currentPenWidths
    val widthIndex = (if (highlighter) t.highlighterWidthIndex else t.penWidthIndex).coerceIn(0, widths.size - 1)

    fun ToolState.targeted(): ToolState = if (tool != inkTarget) select(inkTarget) else this

    var panel by remember { mutableStateOf<PalettePanel?>(null) }
    // Name of the option the stylus is hovering over, shown under the palette.
    var hoverLabel by remember { mutableStateOf<String?>(null) }
    fun hovering(label: String): (Boolean) -> Unit = { on ->
        if (on) hoverLabel = label else if (hoverLabel == label) hoverLabel = null
    }

    val pxPerDp = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        // Where the pen was (dp), moved just enough to keep the whole palette on screen.
        val a = run {
            val p = if (anchor.isSpecified) Offset(anchor.x / pxPerDp, anchor.y / pxPerDp) else Offset(w / 2, h / 2)
            fun clamp(v: Float, lo: Float, hi: Float) = if (lo > hi) (lo + hi) / 2 else v.coerceIn(lo, hi)
            val m = 8f
            Offset(clamp(p.x, -PaletteLayout.MIN_X + m, w - PaletteLayout.MAX_X - m),
                clamp(p.y, -PaletteLayout.MIN_Y + m, h - PaletteLayout.MAX_Y - m))
        }

        Box(Modifier.fillMaxSize().pointerInput(a) {
            // Tap outside the palette's surfaces to close it.
            detectTapGestures { p ->
                if (!PaletteLayout.onSurface(Offset(p.x / pxPerDp - a.x, p.y / pxPerDp - a.y))) onDismiss()
            }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(a.x / w, a.y / h)
                scaleX = appear.value; scaleY = appear.value
                alpha = ((appear.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
            }) {
                // Band, bar and column surfaces with a soft shared shadow.
                Canvas(Modifier.fillMaxSize()) {
                    drawSurfaces(a, Color.Black.copy(alpha = 0.035f), spread = 10f, dy = 5f)
                    drawSurfaces(a, Color.Black.copy(alpha = 0.05f), spread = 4f, dy = 3f)
                    drawSurfaces(a, c.surface, spread = 0f, dy = 0f)
                    // Divider between undo and the tools.
                    val d1 = a + PaletteLayout.point(PaletteLayout.DIVIDER_ANGLE, PaletteLayout.RADIUS - 11f)
                    val d2 = a + PaletteLayout.point(PaletteLayout.DIVIDER_ANGLE, PaletteLayout.RADIUS + 11f)
                    drawLine(c.controlBorder, d1 * pxPerDp, d2 * pxPerDp, 1.5.dp.toPx())
                }

                // Band: tools, then undo at the lower end.
                PALETTE_TOOLS.forEachIndexed { i, tool ->
                    val on = t.tool == tool
                    val p = a + PaletteLayout.point(PaletteLayout.FIRST_TOOL + i * PaletteLayout.TOOL_STEP)
                    Box(Modifier.centerAt(p, PaletteLayout.ITEM, pxPerDp)
                        .hoverHighlight(scale = 1.14f, onHover = hovering(tool.label))
                        .size(PaletteLayout.ITEM.dp).clip(CircleShape)
                        .background(if (on) c.accent else Color.Transparent)
                        .clickable {
                            model.updateTools { it.select(tool) }
                            onDismiss()
                        }
                        .semantics { contentDescription = tool.label; selected = on },
                        contentAlignment = Alignment.Center) {
                        Icon(tool.icon, null, Modifier.size(22.dp), tint = if (on) Color.White else c.textPrimary)
                        if (tool.inks) {
                            // Small dot with the tool's current color.
                            Box(Modifier.align(Alignment.BottomEnd).offset((-5).dp, (-5).dp).size(11.dp).clip(CircleShape)
                                .background(Color.White).padding(1.5.dp).clip(CircleShape)
                                .background(hexColor(if (tool == EditorTool.HIGHLIGHTER) t.highlighterColor else t.penColor)))
                        }
                    }
                }
                PaletteIcon(a + PaletteLayout.point(PaletteLayout.UNDO_ANGLE), PaletteLayout.ITEM, Icons.AutoMirrored.Outlined.Undo,
                    "실행 취소", enabled = model.canUndo, hover = hovering("실행 취소")) { model.undo() }

                // Bar at the band's top end: width presets and the current color.
                Row(Modifier.offset { (a + PaletteLayout.BAR_ORIGIN).toIntOffset(pxPerDp) }
                    .size(PaletteLayout.BAR_WIDTH.dp, PaletteLayout.BAR_HEIGHT.dp)
                    .padding(start = PaletteLayout.BAR_LEAD.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val maxW = widths.maxOrNull() ?: 1.0
                    widths.forEachIndexed { i, wv ->
                        val on = i == widthIndex
                        val label = if (highlighter) "%.0fpt".format(wv) else "%.1fmm".format(wv / 72 * 25.4)
                        Box(Modifier.hoverHighlight(scale = 1.12f, onHover = hovering("굵기 $label"))
                            .size(PaletteLayout.BAR_SLOT.dp).clip(CircleShape)
                            .background(if (on) c.accentSoft else Color.Transparent)
                            .clickable {
                                model.updateTools {
                                    val s = it.targeted()
                                    if (highlighter) s.copy(highlighterWidthIndex = i) else s.copy(penWidthIndex = i)
                                }
                            }
                            .semantics { contentDescription = "굵기 $label"; selected = on },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.rotate(-35f).width(18.dp).height(maxOf(2.0, wv / maxW * 8).dp)
                                .clip(CircleShape).background(hexColor(activeColor))
                                .border(1.dp, if (activeColor.equals("#FFFFFF", true)) c.controlBorder else Color.Transparent, CircleShape))
                        }
                    }
                    Box(Modifier.width(1.dp).height(24.dp).background(c.controlBorder))
                    val open = panel == PalettePanel.COLORS
                    Box(Modifier.hoverHighlight(scale = 1.12f, onHover = hovering(if (highlighter) "형광펜 색" else "펜 색"))
                        .size(PaletteLayout.BAR_SLOT.dp).clip(CircleShape)
                        .border(if (open) 2.dp else 1.dp, if (open) c.accent else c.controlBorder, CircleShape)
                        .clickable { panel = if (open) null else PalettePanel.COLORS }
                        .semantics { contentDescription = if (highlighter) "형광펜 색" else "펜 색"; selected = open }
                        .padding(6.dp).clip(CircleShape).background(hexColor(activeColor))
                        .border(1.dp, if (activeColor.equals("#FFFFFF", true)) c.controlBorder else Color.Transparent, CircleShape))
                }

                // Left column: pen settings, pen kinds, redo.
                Column(Modifier.offset { (a + PaletteLayout.COLUMN_ORIGIN).toIntOffset(pxPerDp) }
                    .size(PaletteLayout.COLUMN_WIDTH.dp, PaletteLayout.COLUMN_HEIGHT.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    PaletteIconContent(40f, Icons.Outlined.Tune, "펜 설정", hover = hovering("펜 설정")) {
                        model.updateTools { if (it.tool.inks || it.tool == EditorTool.ERASER) it else it.select(it.lastInkTool) }
                        onDismiss()
                        onPenSettings()
                    }
                    PaletteIconContent(40f, Icons.Outlined.Gesture, "펜 종류", enabled = !highlighter,
                        selected = panel == PalettePanel.KINDS, hover = hovering("펜 종류")) {
                        panel = if (panel == PalettePanel.KINDS) null else PalettePanel.KINDS
                    }
                    PaletteIconContent(40f, Icons.AutoMirrored.Outlined.Redo, "다시 실행", enabled = model.canRedo,
                        hover = hovering("다시 실행")) { model.redo() }
                }

                // Pop-out under the bar: all colors or the pen kinds.
                panel?.let { which ->
                    Box(Modifier.offset { (a + PaletteLayout.PANEL_ORIGIN).toIntOffset(pxPerDp) }
                        .width(PaletteLayout.PANEL_WIDTH.dp)
                        .shadowCard()
                        .background(c.surface, RoundedCornerShape(18.dp))
                        // Taps on the panel's background must not close the palette.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(7.dp)) {
                        when (which) {
                            PalettePanel.COLORS -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                palette.chunked(6).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { hex ->
                                            val sel = activeColor.equals(hex, ignoreCase = true)
                                            Box(Modifier.hoverHighlight(scale = 1.18f, onHover = hovering(if (highlighter) "형광펜 색" else "펜 색"))
                                                .size(32.dp).clip(CircleShape)
                                                .border(2.dp, if (sel) c.accent else Color.Transparent, CircleShape)
                                                .clickable {
                                                    model.updateTools { it.targeted().withColor(hex) }
                                                    onDismiss()
                                                }
                                                .semantics { contentDescription = "색 $hex"; selected = sel }
                                                .padding(4.dp).clip(CircleShape).background(hexColor(hex))
                                                .border(1.dp, if (hex.equals("#FFFFFF", true)) c.controlBorder else Color.Transparent, CircleShape))
                                        }
                                    }
                                }
                            }
                            PalettePanel.KINDS -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PEN_KINDS.chunked(3).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { kind ->
                                            val on = t.pen == kind
                                            Box(Modifier.hoverHighlight(RoundedCornerShape(50), scale = 1.06f, onHover = hovering(kind.label))
                                                .size(70.dp, 34.dp).clip(RoundedCornerShape(50))
                                                .background(if (on) c.accent else c.fieldFill)
                                                .clickable {
                                                    model.updateTools {
                                                        val s = if (it.tool != EditorTool.PEN) it.select(EditorTool.PEN) else it
                                                        s.copy(penKind = kind.raw)
                                                    }
                                                    panel = null
                                                }
                                                .semantics { contentDescription = kind.label; selected = on },
                                                contentAlignment = Alignment.Center) {
                                                Text(kind.label, fontSize = 12.sp, maxLines = 1,
                                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (on) Color.White else c.textPrimary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Hovered option's name, under the palette.
            hoverLabel?.let { label ->
                Text(label, fontSize = 13.sp, color = Color.White, maxLines = 1,
                    modifier = Modifier.offset { Offset(a.x - 110f, minOf(a.y + 82f, h - 34f)).toIntOffset(pxPerDp) }
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

/** Band, bar and column at anchor [a] (dp), grown by [spread] dp and moved down by [dy] dp. */
private fun DrawScope.drawSurfaces(a: Offset, color: Color, spread: Float, dy: Float) {
    val d = density
    val r = PaletteLayout.RADIUS * d
    val center = Offset(a.x * d, (a.y + dy) * d)
    drawArc(color, PaletteLayout.BAND_START, PaletteLayout.BAND_END - PaletteLayout.BAND_START, useCenter = false,
        topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r),
        style = Stroke(width = (PaletteLayout.BAND + spread) * d, cap = StrokeCap.Round))
    fun pill(origin: Offset, w: Float, h: Float) {
        val o = Offset((a.x + origin.x - spread / 2) * d, (a.y + dy + origin.y - spread / 2) * d)
        val radius = (minOf(w, h) + spread) / 2 * d
        drawRoundRect(color, o, Size((w + spread) * d, (h + spread) * d), CornerRadius(radius, radius))
    }
    pill(PaletteLayout.BAR_ORIGIN, PaletteLayout.BAR_WIDTH, PaletteLayout.BAR_HEIGHT)
    pill(PaletteLayout.COLUMN_ORIGIN, PaletteLayout.COLUMN_WIDTH, PaletteLayout.COLUMN_HEIGHT)
}

private fun Offset.toIntOffset(pxPerDp: Float) = IntOffset((x * pxPerDp).roundToInt(), (y * pxPerDp).roundToInt())

/** Positions a [size] dp item so its center is at [center] (dp) in the parent. */
private fun Modifier.centerAt(center: Offset, size: Float, pxPerDp: Float): Modifier =
    offset { Offset(center.x - size / 2, center.y - size / 2).toIntOffset(pxPerDp) }

private fun Modifier.shadowCard(): Modifier =
    this.then(Modifier.graphicsLayer {
        shadowElevation = 12.dp.toPx()
        shape = RoundedCornerShape(18.dp)
        clip = false
    })

/** Round icon button placed with its center at [center] (dp). */
@Composable
private fun PaletteIcon(center: Offset, size: Float, icon: ImageVector, label: String, enabled: Boolean = true,
                        hover: (Boolean) -> Unit, onClick: () -> Unit) {
    val pxPerDp = LocalDensity.current.density
    Box(Modifier.centerAt(center, size, pxPerDp)) {
        PaletteIconContent(size, icon, label, enabled = enabled, hover = hover, onClick = onClick)
    }
}

@Composable
private fun PaletteIconContent(size: Float, icon: ImageVector, label: String, enabled: Boolean = true,
                               selected: Boolean = false, hover: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = NoteTheme.colors
    Box(Modifier.hoverHighlight(scale = 1.12f, onHover = hover)
        .size(size.dp).clip(CircleShape)
        .background(if (selected) c.accentSoft else Color.Transparent)
        .clickable(enabled = enabled) { onClick() }
        .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp),
            tint = (if (selected) c.accent else c.textPrimary).copy(alpha = if (enabled) 1f else 0.35f))
    }
}
