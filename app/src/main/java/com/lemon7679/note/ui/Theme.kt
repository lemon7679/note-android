package com.lemon7679.note.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Quiet Ink" design tokens, translated 1:1 from note-ipad's Theme/NoteTheme.swift
// (the Claude Design canvas "Tokens" artboard). Light values are the design's;
// dark values keep each role's contrast.

fun hexColor(hex: String): Color {
    val s = hex.removePrefix("#")
    val v = s.toLong(16)
    return if (s.length == 8) Color(((v and 0xFF) shl 24 or (v shr 8)).toInt()) else Color(0xFF000000 or v)
}

@Immutable
data class NoteColors(
    val background: Color,
    val sidebar: Color,
    val desk: Color,
    val surface: Color,
    val separator: Color,
    val controlBorder: Color,
    val fieldFill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val iconMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val synced: Color,
    val syncedSoft: Color,
    val conflict: Color,
    val conflictSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val offline: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
)

private fun colors(dark: Boolean): NoteColors {
    fun c(light: String, darkHex: String) = hexColor(if (dark) darkHex else light)
    return NoteColors(
        background = c("#F6F7F9", "#111317"),
        sidebar = c("#EEF0F3", "#171A1F"),
        desk = c("#E9EBEF", "#0B0C0F"),
        surface = c("#FFFFFF", "#1E2128"),
        separator = c("#E2E5EA", "#2C3038"),
        controlBorder = c("#D5D9E0", "#3A3F4A"),
        fieldFill = c("#E8EBF0", "#262A32"),
        textPrimary = c("#15171C", "#F2F3F5"),
        textSecondary = c("#5B616E", "#A3A9B5"),
        textTertiary = c("#6B7280", "#8A909C"),
        iconMuted = c("#3A3F4A", "#C9CDD5"),
        accent = c("#2F5BD3", "#7A9CFF"),
        accentSoft = c("#E8EEFC", "#1F2A47"),
        synced = c("#1F7A55", "#4CC08C"),
        syncedSoft = c("#E6F3EE", "#173A2C"),
        conflict = c("#B45309", "#F0A453"),
        conflictSoft = c("#FDF1E3", "#3A2A16"),
        danger = c("#C0362C", "#FF7A6E"),
        dangerSoft = c("#FBEAE8", "#3D1C19"),
        offline = c("#9AA0AC", "#6B7280"),
        chipSelected = c("#15171C", "#F2F3F5"),
        chipSelectedText = c("#FFFFFF", "#15171C"),
    )
}

val LocalNoteColors = staticCompositionLocalOf { colors(false) }

object NoteTheme {
    val colors: NoteColors @Composable get() = LocalNoteColors.current

    val penColors = listOf("#1C1C1E", "#1F4FD1", "#D12F2F", "#12805C", "#7A3FC4", "#E07A1F")
    val highlighterColors = listOf("#FFE45C", "#8EF0A8", "#7FD3FF", "#FF9ECF")

    object Radius {
        val control = 10.dp
        val card = 14.dp
        val popover = 18.dp
        val sheet = 20.dp
    }

    object Space {
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val page = 28.dp
    }

    val minTouch = 44.dp

    object Fonts {
        val largeTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val folderTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        val sheetTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val body = TextStyle(fontSize = 15.sp)
        val bodyEmphasized = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val cardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val sectionLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val caption = TextStyle(fontSize = 12.sp)
    }
}

@Composable
fun NoteAppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = colors(dark)
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = Color(0xFF15171C), background = c.background, surface = c.surface,
            onSurface = c.textPrimary, onBackground = c.textPrimary, surfaceVariant = c.fieldFill,
            onSurfaceVariant = c.textSecondary, outline = c.controlBorder, outlineVariant = c.separator,
            error = c.danger, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.fieldFill,
            surfaceContainerLow = c.background)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = Color.White, background = c.background, surface = c.surface,
            onSurface = c.textPrimary, onBackground = c.textPrimary, surfaceVariant = c.fieldFill,
            onSurfaceVariant = c.textSecondary, outline = c.controlBorder, outlineVariant = c.separator,
            error = c.danger, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.fieldFill,
            surfaceContainerLow = c.background)
    }
    CompositionLocalProvider(LocalNoteColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/** Line icons the Material set lacks (eraser, lasso, laser pointer, pen nib). */
object NoteIcons {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            if (i in filled) b.addPath(nodes, fill = SolidColor(Color.Black))
            else b.addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    val pen: ImageVector by lazy {
        icon("pen", "M12 3 L16.5 11.5 L15 20 L9 20 L7.5 11.5 Z", "M12 9 L12 14", "M9 20 L15 20")
    }
    val eraser: ImageVector by lazy {
        icon("eraser", "M15.5 4.5 L20 9 L10.5 18.5 L6 18.5 L3.5 16 Z", "M9.5 10.5 L14 15", "M10.5 18.5 L20.5 18.5")
    }
    val lasso: ImageVector by lazy {
        icon("lasso", "M12 4 C17 4 20.5 6.5 20.5 9.8 C20.5 13.1 16.8 15.6 12 15.6 C7.2 15.6 3.5 13.1 3.5 9.8 C3.5 6.5 7 4 12 4 Z",
            "M8.5 15 C7.2 16.6 7.8 18.6 9.8 18.9 C11.2 19.1 11.8 20.2 11 21.5")
    }
    val laser: ImageVector by lazy {
        icon("laser", "M12 9.5 A2.5 2.5 0 1 0 12 14.5 A2.5 2.5 0 1 0 12 9.5 Z",
            "M12 3 L12 6", "M12 18 L12 21", "M3 12 L6 12", "M18 12 L21 12", "M5.6 5.6 L7.7 7.7", "M16.3 16.3 L18.4 18.4",
            "M5.6 18.4 L7.7 16.3", "M16.3 7.7 L18.4 5.6", filled = setOf(0))
    }
    val shapes: ImageVector by lazy {
        icon("shapes", "M3.5 3.5 H12.5 V12.5 H3.5 Z", "M15 11 A5.5 5.5 0 1 0 15 22 A5.5 5.5 0 1 0 15 11 Z")
    }
}
