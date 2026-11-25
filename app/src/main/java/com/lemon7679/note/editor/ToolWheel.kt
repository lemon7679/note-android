package com.lemon7679.note.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon7679.note.ui.NoteTheme
import com.lemon7679.note.ui.hexColor
import kotlin.math.PI
import kotlin.math.cos
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

private const val OUTER_RADIUS = 100f
private const val INNER_RADIUS = 54f
private const val ITEM_SIZE = 48f
private const val WHEEL_DIAMETER = (OUTER_RADIUS + ITEM_SIZE / 2 + 8) * 2

/**
 * Radial tool menu opened by the stylus side button at the pen tip.
 *
 * Outer ring: tools, undo/redo and pen settings. Inner ring: colors of the current pen
 * (or highlighter). Center: the width presets, tapped to cycle. Tapping outside closes it.
 *
 * [anchorX]/[anchorY] are view pixels inside this composable's bounds (null = centered).
 */
@Composable
fun ToolWheel(model: EditorModel, anchorX: Float?, anchorY: Float?, onPenSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = NoteTheme.colors
    val density = LocalDensity.current
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(140)) }

    val t = model.tools
    val inkTarget = if (t.tool.inks || t.tool == EditorTool.TEXT) t.tool else t.lastInkTool
    val highlighter = inkTarget == EditorTool.HIGHLIGHTER
    val palette = ((if (highlighter) NoteTheme.highlighterColors else NoteTheme.penColors) + t.customColors.take(2)).take(6)
    val activeColor = if (highlighter) t.highlighterColor else t.penColor

    fun ToolState.targeted(): ToolState = if (tool != inkTarget) select(inkTarget) else this

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val r = with(density) { (WHEEL_DIAMETER / 2 + 8).dp.toPx() }
        val cx = (anchorX ?: (wPx / 2)).coerceIn(r, maxOf(r, wPx - r))
        val cy = (anchorY ?: (hPx / 2)).coerceIn(r, maxOf(r, hPx - r))
        val half = with(density) { (WHEEL_DIAMETER / 2).dp.toPx() }

        Box(Modifier.offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
            .size(WHEEL_DIAMETER.dp)
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = (appear.value - 0.6f) / 0.4f }
            .shadow(16.dp, CircleShape).clip(CircleShape).background(c.surface.copy(alpha = 0.97f))
            .border(1.dp, c.separator, CircleShape)
            // Taps on the wheel's own background must not close it.
            .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(((INNER_RADIUS + 22) * 2).dp).clip(CircleShape).background(c.fieldFill.copy(alpha = 0.6f)))

            WHEEL_ITEMS.forEachIndexed { i, item ->
                val on = item is WheelItem.Tool && t.tool == item.tool
                val enabled = when (item) {
                    WheelItem.Undo -> model.canUndo
                    WheelItem.Redo -> model.canRedo
                    else -> true
                }
                Box(Modifier.ringOffset(i, WHEEL_ITEMS.size, OUTER_RADIUS).size(ITEM_SIZE.dp).clip(CircleShape)
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

            palette.forEachIndexed { i, hex ->
                val selectedColor = activeColor.equals(hex, ignoreCase = true)
                Box(Modifier.ringOffset(i, palette.size, INNER_RADIUS).size(34.dp).clip(CircleShape)
                    .border(2.dp, if (selectedColor) c.accent else Color.Transparent, CircleShape)
                    .clickable {
                        model.updateTools { it.targeted().withColor(hex) }
                        onDismiss()
                    }
                    .semantics { contentDescription = "색 $hex"; selected = selectedColor }
                    .padding(4.dp).clip(CircleShape).background(hexColor(hex))
                    .border(1.dp, if (hex.equals("#FFFFFF", true)) c.controlBorder else Color.Transparent, CircleShape))
            }

            // Center: current width preset; each tap moves to the next one.
            val widths = if (highlighter) t.highlighterWidths else t.currentPenWidths
            val index = (if (highlighter) t.highlighterWidthIndex else t.penWidthIndex).coerceIn(0, widths.size - 1)
            val width = widths[index]
            val maxWidth = widths.maxOrNull() ?: 1.0
            Column(Modifier.size(52.dp).clip(CircleShape).background(c.surface).border(1.dp, c.controlBorder, CircleShape)
                .clickable {
                    val next = (index + 1) % widths.size
                    model.updateTools {
                        val s = it.targeted()
                        if (highlighter) s.copy(highlighterWidthIndex = next) else s.copy(penWidthIndex = next)
                    }
                }
                .semantics { contentDescription = "굵기 ${index + 1} / ${widths.size}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                Box(Modifier.width(22.dp).height(maxOf(2.0, width / maxWidth * 9).dp).clip(CircleShape).background(hexColor(activeColor)))
                Text(if (highlighter) "%.0fpt".format(width) else "%.1fmm".format(width / 72 * 25.4), fontSize = 10.sp, color = c.textSecondary)
            }
        }
    }
}

private fun Modifier.ringOffset(index: Int, count: Int, radius: Float): Modifier {
    val angle = -PI / 2 + index.toDouble() / maxOf(count, 1) * 2 * PI
    return offset((cos(angle) * radius).dp, (sin(angle) * radius).dp)
}
