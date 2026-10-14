package com.example.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.ui.LocalBackgroundTone
import com.example.ui.components.*

val DiscoverRise = Color(0xFFE5484D)
val DiscoverFall = Color(0xFF2E9E6B)
val DiscoverWarning = Color(0xFFF5A524)
val DiscoverStopped = Color(0xFF9AA0A6)
val DiscoverMedals = listOf(Color(0xFFE8B53A), Color(0xFFB8C0CC), Color(0xFFC98A5B))
fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) /
    (minOf(a.luminance(), b.luminance()) + .05f)
fun contrastInk(background: Color): Color = if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White
fun themeText(primary: Color, background: Color, minimum: Float = 4.5f): Color {
    val target = contrastInk(background)
    var color = primary.copy(alpha = 1f)
    var mix = 0f
    while (contrast(color, background) < minimum && mix < 1f) {
        mix += .02f
        color = androidx.compose.ui.graphics.lerp(primary.copy(alpha = 1f), target, mix.coerceAtMost(1f))
    }
    return color
}

@Composable
fun discoverInk(): Color = MaterialTheme.colorScheme.onSurface
@Composable
fun discoverTint(): Color = MaterialTheme.colorScheme.primary.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < .5f) .22f else .14f)
@Composable
fun discoverAccentText(): Color = themeText(MaterialTheme.colorScheme.primary,
    discoverTint().compositeOver(MaterialTheme.colorScheme.surface), 5.2f)

@Composable
fun DiscoverGlass(modifier: Modifier = Modifier, shape: Shape? = null,
    padding: PaddingValues = PaddingValues(10.dp), content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier = modifier, shape = shape, contentPadding = padding, content = content)
}

@Composable
fun DiscoverEmptyState(mascotResId: Int, title: String, description: String, actionLabel: String, onActionClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(mascotResId), null, Modifier.size(120.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = discoverInk(), textAlign = TextAlign.Center)
        Text(description, fontSize = 13.sp, color = discoverInk().copy(alpha = .64f), textAlign = TextAlign.Center)
        Button(onActionClick, Modifier.heightIn(min = 48.dp), shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(contentColor = contrastInk(MaterialTheme.colorScheme.primary))) { Text(actionLabel) }
    }
}
