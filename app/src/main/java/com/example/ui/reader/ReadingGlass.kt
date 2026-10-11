package com.example.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls

@Immutable
internal data class ReadingGlassStyle(val backdrop: Backdrop? = null, val tint: Color = Color(0xFF232329), val ink: Color = Color.White)
internal val LocalReadingGlass = staticCompositionLocalOf { ReadingGlassStyle() }

/** Every inner surface samples the reading layer, never its own text or another glass card. */
@Composable
internal fun Modifier.readingGlass(
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color? = null,
    strong: Boolean = false,
): Modifier {
    val style = LocalReadingGlass.current
    val quality = LocalRenderQuality.current
    val surface = tint ?: style.tint.copy(alpha = if (strong) .72f else .60f)
    val radius = if (quality == RenderQuality.LOW) 10f else if (strong) 22f else 16f
    val capture = if (style.backdrop != null) Modifier.drawPlainBackdrop(
        backdrop = style.backdrop, shape = { shape },
        effects = { colorControls(saturation = 1.10f); blur(radius.dp.toPx()) },
    ) else Modifier
    return clip(shape).then(capture)
        .background(if (style.backdrop == null) surface.copy(alpha = .84f) else surface)
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .025f), style.ink.copy(alpha = .045f))))
        .border(.7.dp, style.ink.copy(alpha = .13f), shape)
}
