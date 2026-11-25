package com.lemon7679.note.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Highlights a control while a stylus (or mouse) hovers over it, so you can see which option the
 * pen tip will pick before touching the screen: the control grows slightly and gets a soft accent
 * fill (no outline).
 *
 * Put it before the control's own `clip`/`background` so the fill is drawn outside the clip.
 * [onHover] reports enter/exit, e.g. to show the hovered option's name.
 */
fun Modifier.hoverHighlight(
    shape: Shape = CircleShape,
    scale: Float = 1.08f,
    onHover: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val progress by animateFloatAsState(if (hovered) 1f else 0f, tween(120), label = "hover")
    val accent = NoteTheme.colors.accent
    val callback by rememberUpdatedState(onHover)
    if (onHover != null) LaunchedEffect(hovered) { callback?.invoke(hovered) }

    hoverable(source)
        .graphicsLayer {
            val s = 1f + (scale - 1f) * progress
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            if (progress > 0f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, accent.copy(alpha = 0.16f * progress))
                drawContent()
            } else {
                drawContent()
            }
        }
}
