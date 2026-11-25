package com.lemon7679.note.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.ink.BrushKind
import com.lemon7679.note.ui.NoteTheme
import com.lemon7679.note.ui.hexColor
import com.lemon7679.note.ui.hoverHighlight
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private sealed class WheelItem(val label: String) {
    class Tool(val tool: EditorTool) : WheelItem(tool.label)
    data object Undo : WheelItem("실행 취소")
    data object Redo : WheelItem("다시 실행")
    data object Settings : WheelItem("펜 설정")

    val icon: ImageVector
        get() = when (this) {
            is Tool -> tool.icon
            Undo -> Icons.AutoMirrored.Outlined.Undo
            Redo -> Icons.AutoMirrored.Outlined.Redo
            Settings -> Icons.Outlined.Tune
        }
}

private val WHEEL_ITEMS: List<WheelItem> = listOf(
    WheelItem.Tool(EditorTool.PEN), WheelItem.Tool(EditorTool.HIGHLIGHTER), WheelItem.Tool(EditorTool.ERASER),
    WheelItem.Tool(EditorTool.LASSO), WheelItem.Tool(EditorTool.TEXT), WheelItem.Tool(EditorTool.LASER),
    WheelItem.Undo, WheelItem.Redo, WheelItem.Settings,
)

/** Short pen names for the small wheel buttons. */
private val BrushKind.shortLabel: String
    get() = if (this == BrushKind.CALLIGRAPHY) "캘리" else label

/** Radius of the quarter disk in the corner, in dp. */
private const val WHEEL_RADIUS = 268f

/** One arc of options around the top-left corner. Angles are degrees from the top edge (0°) to the left edge (90°). */
private class Arc(val radius: Float, val step: Float, val itemSize: Float) {
    /** Angle of the first item when not scrolled. */
    val start: Float get() = step * 0.6f

    /** Largest scroll angle for [count] items: the last one sits as far from the left edge as the first from the top. */
    fun maxScroll(count: Int): Float = maxOf(0f, (count - 1) * step + 2 * start - 90f)

    fun angle(index: Int, scroll: Float): Float = start + index * step - scroll
}

private val TOOLS_ARC = Arc(radius = 222f, step = 17f, itemSize = 46f)
private val COLORS_ARC = Arc(radius = 158f, step = 18f, itemSize = 36f)
private val OPTIONS_ARC = Arc(radius = 98f, step = 27f, itemSize = 40f)
private val ARCS = listOf(TOOLS_ARC, COLORS_ARC, OPTIONS_ARC)

/** Which arc a touch at [distance] dp from the corner belongs to (null = the corner button). */
private fun arcAt(distance: Float): Int? = when {
    distance >= (TOOLS_ARC.radius + COLORS_ARC.radius) / 2 -> 0
    distance >= (COLORS_ARC.radius + OPTIONS_ARC.radius) / 2 -> 1
    distance >= 56f -> 2
    else -> null
}

/**
 * Tool menu opened by the stylus side button, docked in the top-left corner of the page area as a
 * quarter circle so it covers little of the page.
 *
 * Three arcs, each dragged along its curve to show more options:
 *  - outer: tools, undo/redo and pen settings;
 *  - middle: all colors of the current pen (or highlighter), including recent custom colors;
 *  - inner: width presets and, for the pen, the pen kinds.
 * The corner button (current tool) closes it, as does tapping outside.
 */
@Composable
fun ToolWheel(model: EditorModel, onPenSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val appear = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }

    val t = model.tools
    val inkTarget = if (t.tool.inks || t.tool == EditorTool.TEXT) t.tool else t.lastInkTool
    val highlighter = inkTarget == EditorTool.HIGHLIGHTER
    val palette = ((if (highlighter) NoteTheme.highlighterColors else NoteTheme.penColors) + t.customColors).distinctBy { it.uppercase() }
    val activeColor = if (highlighter) t.highlighterColor else t.penColor
    val widths = if (highlighter) t.highlighterWidths else t.currentPenWidths
    val widthIndex = (if (highlighter) t.highlighterWidthIndex else t.penWidthIndex).coerceIn(0, widths.size - 1)
    val kinds = if (highlighter) emptyList() else PEN_KINDS
    val counts = listOf(WHEEL_ITEMS.size, palette.size, widths.size + kinds.size)

    fun ToolState.targeted(): ToolState = if (tool != inkTarget) select(inkTarget) else this

    // Scroll angle of each arc; start with the current tool / color visible.
    val scroll = remember {
        val toolIndex = WHEEL_ITEMS.indexOfFirst { it is WheelItem.Tool && it.tool == t.tool }.coerceAtLeast(0)
        val colorIndex = palette.indexOfFirst { it.equals(activeColor, ignoreCase = true) }.coerceAtLeast(0)
        val kindIndex = widths.size + kinds.indexOf(t.pen).coerceAtLeast(0)
        fun initial(arc: Arc, index: Int, count: Int) = (arc.angle(index, 0f) - 60f).coerceIn(0f, arc.maxScroll(count))
        mutableStateListOf(
            initial(TOOLS_ARC, toolIndex, counts[0]),
            initial(COLORS_ARC, colorIndex, counts[1]),
            if (kinds.isEmpty()) 0f else initial(OPTIONS_ARC, kindIndex, counts[2]),
        )
    }

    // Name of the option the stylus is hovering over, shown next to the wheel.
    var hoverLabel by remember { mutableStateOf<String?>(null) }
    fun hovering(label: String): (Boolean) -> Unit = { on ->
        if (on) hoverLabel = label else if (hoverLabel == label) hoverLabel = null
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        Box(Modifier.size(WHEEL_RADIUS.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = appear.value; scaleY = appear.value
                alpha = ((appear.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
            }
            .shadow(14.dp, QuarterShape).clip(QuarterShape).background(c.surface.copy(alpha = 0.97f))
            // Taps on the wheel's own background must not close it.
            .pointerInput(Unit) { detectTapGestures { } }
            // Dragging along an arc scrolls that arc.
            .pointerInput(counts) {
                var arc: Int? = null
                var lastAngle = 0f
                fun angleOf(p: Offset) = (atan2(p.y, p.x) * 180 / PI).toFloat()
                detectDragGestures(
                    onDragStart = { p ->
                        arc = arcAt(hypot(p.x, p.y) / density)
                        lastAngle = angleOf(p)
                    },
                    onDrag = { change, _ ->
                        val a = angleOf(change.position)
                        arc?.let { i -> scroll[i] = (scroll[i] - (a - lastAngle)).coerceIn(0f, ARCS[i].maxScroll(counts[i])) }
                        lastAngle = a
                        change.consume()
                    },
                )
            }) {
            // Tracks behind each arc.
            Canvas(Modifier.fillMaxSize()) {
                ARCS.forEach { arc ->
                    drawCircle(c.fieldFill.copy(alpha = 0.55f), radius = arc.radius.dp.toPx(), center = Offset.Zero,
                        style = Stroke((arc.itemSize + 12).dp.toPx()))
                }
            }

            WHEEL_ITEMS.forEachIndexed { i, item ->
                val on = item is WheelItem.Tool && t.tool == item.tool
                val enabled = when (item) {
                    WheelItem.Undo -> model.canUndo
                    WheelItem.Redo -> model.canRedo
                    else -> true
                }
                ArcSlot(TOOLS_ARC, i, scroll[0]) {
                    Box(Modifier.hoverHighlight(scale = 1.14f, onHover = hovering(item.label))
                        .size(TOOLS_ARC.itemSize.dp).clip(CircleShape)
                        .background(if (on) c.accent else c.surface)
                        .border(1.dp, if (on) Color.Transparent else c.controlBorder, CircleShape)
                        .clickable(enabled = enabled) {
                            when (item) {
                                is WheelItem.Tool -> {
                                    model.updateTools { it.select(item.tool) }
                                    onDismiss()
                                }
                                WheelItem.Undo -> model.undo()
                                WheelItem.Redo -> model.redo()
                                WheelItem.Settings -> {
                                    model.updateTools {
                                        if (it.tool.inks || it.tool == EditorTool.ERASER) it else it.select(it.lastInkTool)
                                    }
                                    onDismiss()
                                    onPenSettings()
                                }
                            }
                        }
                        .semantics { contentDescription = item.label; selected = on },
                        contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, Modifier.size(22.dp),
                            tint = (if (on) Color.White else c.textPrimary).copy(alpha = if (enabled) 1f else 0.35f))
                    }
                }
            }

            palette.forEachIndexed { i, hex ->
                val selectedColor = activeColor.equals(hex, ignoreCase = true)
                ArcSlot(COLORS_ARC, i, scroll[1]) {
                    Box(Modifier.hoverHighlight(scale = 1.18f, onHover = hovering(if (highlighter) "형광펜 색" else "펜 색"))
                        .size(COLORS_ARC.itemSize.dp).clip(CircleShape)
                        .border(2.dp, if (selectedColor) c.accent else Color.Transparent, CircleShape)
                        .clickable {
                            model.updateTools { it.targeted().withColor(hex) }
                            onDismiss()
                        }
                        .semantics { contentDescription = "색 $hex"; selected = selectedColor }
                        .padding(4.dp).clip(CircleShape).background(hexColor(hex))
                        .border(1.dp, if (hex.equals("#FFFFFF", true)) c.controlBorder else Color.Transparent, CircleShape))
                }
            }

            // Inner arc: width presets, then (pen only) the pen kinds.
            val maxWidth = widths.maxOrNull() ?: 1.0
            widths.forEachIndexed { i, w ->
                val on = i == widthIndex
                val label = if (highlighter) "%.0fpt".format(w) else "%.1fmm".format(w / 72 * 25.4)
                ArcSlot(OPTIONS_ARC, i, scroll[2]) {
                    Column(Modifier.hoverHighlight(scale = 1.12f, onHover = hovering("굵기 $label"))
                        .size(OPTIONS_ARC.itemSize.dp).clip(CircleShape)
                        .background(if (on) c.accentSoft else c.surface)
                        .border(1.dp, if (on) c.accent else c.controlBorder, CircleShape)
                        .clickable {
                            model.updateTools {
                                val s = it.targeted()
                                if (highlighter) s.copy(highlighterWidthIndex = i) else s.copy(penWidthIndex = i)
                            }
                        }
                        .semantics { contentDescription = "굵기 $label"; selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        Box(Modifier.width(20.dp).height(maxOf(2.0, w / maxWidth * 8).dp).clip(CircleShape).background(hexColor(activeColor)))
                        Text(label, fontSize = 9.sp, color = c.textSecondary, maxLines = 1)
                    }
                }
            }
            kinds.forEachIndexed { k, kind ->
                val on = t.pen == kind
                ArcSlot(OPTIONS_ARC, widths.size + k, scroll[2]) {
                    Box(Modifier.hoverHighlight(scale = 1.12f, onHover = hovering(kind.label))
                        .size(OPTIONS_ARC.itemSize.dp).clip(CircleShape)
                        .background(if (on) c.accent else c.surface)
                        .border(1.dp, if (on) Color.Transparent else c.controlBorder, CircleShape)
                        .clickable { model.updateTools { it.targeted().copy(penKind = kind.raw) } }
                        .semantics { contentDescription = kind.label; selected = on },
                        contentAlignment = Alignment.Center) {
                        Text(kind.shortLabel, fontSize = 11.sp, maxLines = 1,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) Color.White else c.textPrimary)
                    }
                }
            }

            // Corner: the current tool; tap to close.
            Box(Modifier.offset(6.dp, 6.dp).hoverHighlight(scale = 1.1f, onHover = hovering("닫기"))
                .size(44.dp).clip(CircleShape).background(c.accent)
                .clickable { onDismiss() }
                .semantics { contentDescription = "도구 휠 닫기" },
                contentAlignment = Alignment.Center) {
                Icon(t.tool.icon, null, Modifier.size(20.dp), tint = Color.White)
            }
        }

        // Hovered option's name, right of the wheel's top end.
        hoverLabel?.let { label ->
            Text(label, fontSize = 13.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.offset((WHEEL_RADIUS + 10).dp, 10.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

/** Quarter of a circle centered on the top-left corner of the box. */
private val QuarterShape = RoundedCornerShape(bottomEndPercent = 100)

/**
 * Places [content] (an item of [arc]'s size) at position [index] on [arc], scrolled by [scroll] degrees.
 * Items fade out near the top and left edges and are not composed once fully outside.
 */
@Composable
private fun BoxScope.ArcSlot(arc: Arc, index: Int, scroll: Float, content: @Composable () -> Unit) {
    val angle = arc.angle(index, scroll)
    val fade = 9f
    val alpha = (minOf(angle + 2f, 92f - angle) / fade).coerceIn(0f, 1f)
    if (alpha <= 0.05f) return
    val rad = angle * PI / 180
    val half = arc.itemSize / 2
    Box(Modifier.align(Alignment.TopStart)
        .offset { IntOffset(((arc.radius * cos(rad) - half) * density).roundToInt(), ((arc.radius * sin(rad) - half) * density).roundToInt()) }
        .graphicsLayer { this.alpha = alpha }) {
        content()
    }
}
