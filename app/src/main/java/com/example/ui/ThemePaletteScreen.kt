package com.example.ui

import com.example.i18n.tr

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.glasskit.GlassManagementPage
import com.example.ui.theme.BasePrimaryColors
import com.example.ui.theme.BaseSecondaryColors
import com.example.ui.wenku.WenkuContentPanel
import com.example.ui.wenku.WenkuGlassSurface
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuSectionHeader
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.wenkuPagePart

private val paletteLabels = listOf("蓝", "紫", "绿", "粉", "橙").map { com.example.i18n.tr(it) }

@Composable
internal fun ThemePaletteScreen(
    primaryIndex: Int,
    secondaryIndex: Int,
    onPrimaryChange: (Int) -> Unit,
    onSecondaryChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    GlassManagementPage(com.example.i18n.tr("主题配色方案"), "APPEARANCE / PALETTE", onBack) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val preview: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WenkuSectionHeader("01", "TONAL PALETTE", tr("实时预览"))
                        Box(Modifier.wenkuPagePart(1)) { ThemePalettePreview(palette) }
                    }
            }
            com.example.ui.adaptive.AdaptiveWorkspace(
                modifier = Modifier.widthIn(max = 1060.dp).fillMaxSize(),
                controls = { split ->
                    if (split) Box(Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp)) { preview() }
                },
            ) { split ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!split) item("preview") { preview() }
                item("primary") {
                    PaletteOptions(
                        "02", "PRIMARY COLOR", com.example.i18n.tr("主色调"), BasePrimaryColors, primaryIndex,
                        Modifier.wenkuPagePart(2), onPrimaryChange,
                    )
                }
                item("secondary") {
                    PaletteOptions(
                        "03", "ACCENT COLOR", com.example.i18n.tr("强调色"), BaseSecondaryColors, secondaryIndex,
                        Modifier.wenkuPagePart(3), onSecondaryChange,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ThemePalettePreview(colors: ColorScheme) {
    WenkuContentPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WenkuIcon(WenkuSymbol.Book, size = 24.dp, tint = colors.primary, entrance = false)
                Text(tr("Ciallo 阅览室"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                com.example.i18n.tr("配色会即时应用到页面、玻璃卡片和控件。"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    colors.primary,
                    colors.secondary,
                    lerp(colors.surface, colors.primary, .22f),
                    lerp(colors.surface, colors.secondary, .16f),
                    colors.surfaceContainerHighest,
                ).forEachIndexed { index, color ->
                    Box(
                        Modifier.weight(1f).height(25.dp)
                            .testTag("theme_palette_preview_$index")
                            .semantics { stateDescription = "#%06X".format(color.toArgb() and 0xFFFFFF) }
                            .background(color, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteOptions(
    number: String,
    code: String,
    title: String,
    palette: List<Color>,
    selectedIndex: Int,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WenkuSectionHeader(number, code, title)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val spacing = 7.dp
            val totalSpacing = spacing * (palette.size - 1)
            @Composable
            fun Swatch(index: Int, tileModifier: Modifier) {
                val label = paletteLabels[index]
                PaletteSwatch(
                    color = palette[index],
                    label = label,
                    description = "$title · $label",
                    modifier = tileModifier,
                    checked = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
            if (maxWidth >= 48.dp * palette.size + totalSpacing) {
                Row(
                    Modifier.widthIn(max = 82.dp * palette.size + totalSpacing).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    palette.indices.forEach { Swatch(it, Modifier.weight(1f)) }
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    palette.indices.forEach { Swatch(it, Modifier.width(56.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PaletteSwatch(
    color: Color,
    label: String,
    description: String,
    modifier: Modifier,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    val shape = RoundedCornerShape(8.dp)
    WenkuGlassSurface(
        modifier = modifier.semantics(mergeDescendants = true) {
            selected = checked
            stateDescription = if (checked) tr("已选择") else tr("未选择")
            contentDescription = description
        },
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        blur = 0.dp,
        contentPadding = PaddingValues(4.dp),
        role = Role.RadioButton,
    ) {
        Box(
            Modifier.fillMaxWidth().height(42.dp)
                .border(if (checked) 1.6.dp else .7.dp, if (checked) colors.primary else colors.outlineVariant, shape)
                .padding(4.dp).clip(RoundedCornerShape(4.dp)),
        ) {
            Row(Modifier.fillMaxSize()) {
                listOf(color, lerp(colors.surface, color, .48f), lerp(colors.surface, color, .22f)).forEach { tone ->
                    Box(Modifier.fillMaxHeight().weight(1f).background(tone))
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = checked,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = if (reduceMotion) EnterTransition.None else fadeIn() + scaleIn(initialScale = .72f),
                exit = if (reduceMotion) ExitTransition.None else fadeOut() + scaleOut(targetScale = .72f),
            ) {
                Box(
                    Modifier.padding(2.dp).size(18.dp).background(colors.surface, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    WenkuIcon(WenkuSymbol.Check, size = 14.dp, tint = colors.onSurface, entrance = false)
                }
            }
        }
        Text(
            label,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
