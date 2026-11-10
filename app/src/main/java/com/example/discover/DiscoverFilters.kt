package com.example.discover

import com.example.ui.components.PullOutIconButton
import com.example.ui.components.PullOutVisibility
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import com.example.ui.components.pullOutSelectable
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.example.ui.components.GlassCard
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton

/** Only presentation: callers own every query and selection. */
@Composable
internal fun DiscoverFilters(tabs: List<Pair<String, Boolean>>, onTab: (Int) -> Unit,
    source: String, onSource: () -> Unit, selected: Set<String>, onCategory: () -> Unit,
    onRemove: (String) -> Unit, onClear: () -> Unit, refreshing: Boolean, onRefresh: () -> Unit,
    sorts: List<Pair<String, Boolean>>, onSort: (Int) -> Unit, statuses: List<String>,
    status: String, statusHint: String? = null, onStatus: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accentInk = discoverAccentText()
    val duration = if (LocalReduceMotion.current) 0 else 180
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(start = 14.dp, top = 4.dp, end = 16.dp, bottom = 6.dp)) {
        LazyRow(Modifier.testTag("discover_dimension_tabs"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(tabs.size) { i ->
                val (label, checked) = tabs[i]
                val ink by animateColorAsState(if (checked) accentInk else colors.onSurfaceVariant, tween(duration), label = "discoverTabInk")
                Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp).discoverPressSelect(checked) { onTab(i) }, verticalArrangement = Arrangement.Bottom) {
                    Text(label, Modifier.padding(vertical = 10.dp), fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, color = ink, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (checked) colors.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = .5.dp, color = colors.outline.copy(alpha = .24f))
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WenkuTextButton(
                source, onSource, Modifier.weight(1f), compact = true, wenkuSymbol = WenkuSymbol.Folder,
            )
            WenkuTextButton(
                "分类${if (selected.isNotEmpty()) " ${selected.size}" else ""}",
                onCategory, selected = selected.isNotEmpty(), compact = true, wenkuSymbol = WenkuSymbol.Filter,
            )
            PullOutIconButton(icon = Icons.Outlined.Refresh, contentDescription = "刷新发现", onClick = onRefresh, enabled = !refreshing, glyphSize = 18.dp, tint = colors.onSurfaceVariant)
        }
        PullOutVisibility(selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DiscoverTaxonomy.all.filter { it.id in selected }, key = { it.id }) { category ->
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            Text(category.label, Modifier.clip(CircleShape).background(colors.onSurface.copy(alpha = .06f)).padding(start = 12.dp, end = 40.dp, top = 4.dp, bottom = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurface)
                            PullOutIconButton(
                                icon = Icons.Outlined.Close,
                                contentDescription = "取消${category.label}",
                                onClick = { onRemove(category.id) },
                                modifier = Modifier.align(Alignment.CenterEnd).width(40.dp).height(48.dp),
                                tint = colors.onSurfaceVariant,
                                glyphSize = 14.dp
                            )
                        }
                    }
                }
                VerticalDivider(Modifier.padding(horizontal = 8.dp).height(16.dp), thickness = .5.dp, color = colors.outline.copy(alpha = .24f))
                WenkuTextButton("清除", onClear, compact = true, wenkuSymbol = WenkuSymbol.CloseCircle)
            }
        }
        if (sorts.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorts.size) { i ->
                    val (label, checked) = sorts[i]
                    val tint by animateColorAsState(if (checked) colors.primary.copy(alpha = .16f) else Color.Transparent, tween(duration), label = "discoverSortTint")
                    Box(Modifier.heightIn(min = 48.dp).discoverPressSelect(checked) { onSort(i) }, contentAlignment = Alignment.Center) {
                        Text(label, Modifier.clip(CircleShape).background(tint).border(.5.dp, if (checked) Color.Transparent else colors.outline.copy(alpha = .36f), CircleShape).padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = 14.sp, lineHeight = 18.sp, color = if (checked) accentInk else colors.onSurfaceVariant, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (statuses.isNotEmpty()) {
            Row(Modifier.testTag("discover_status_filters").padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("状态", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).drawBehind {
                    val h = 32.dp.toPx().coerceAtMost(size.height)
                    drawRoundRect(colors.surfaceVariant.copy(alpha = .35f), topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                }.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.forEach { label ->
                        val checked = label == status
                        val tint by animateColorAsState(if (checked) colors.surface else Color.Transparent, tween(duration), label = "discoverStatusTint")
                        Box(Modifier.heightIn(min = 48.dp).discoverPressSelect(checked) { onStatus(label) }, contentAlignment = Alignment.Center) {
                            Text(label, Modifier.clip(CircleShape).background(tint).padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp, lineHeight = 16.sp,
                                color = if (checked) accentInk else colors.onSurfaceVariant, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        statusHint?.let { Text(it, Modifier.padding(top = 4.dp, bottom = 2.dp).testTag("discover_status_hint"),
            fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant) }
    }
}

@Composable
internal fun Modifier.discoverPressSelect(
    checked: Boolean,
    role: Role = Role.Tab,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale = animateFloatAsState(
        targetValue = if (pressed && !reduce) .97f else 1f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "discoverPress"
    )
    return pullOutSelectable(checked, interactionSource = source, indication = null, role = role, onClick = onClick)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
