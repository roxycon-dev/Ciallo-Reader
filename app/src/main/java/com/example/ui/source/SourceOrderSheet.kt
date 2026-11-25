package com.example.ui.source

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.tr
import com.example.source.BookSource
import com.example.source.SourceOrdering
import com.example.source.SourceSortMode
import com.example.ui.components.AppIconButton
import com.example.ui.components.GlassCard
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.glasskit.GlassKitCard
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton

private fun SourceSortMode.label(): String = when (this) {
    SourceSortMode.DEFAULT -> tr("默认排序")
    SourceSortMode.CUSTOM -> tr("自定义排序")
    SourceSortMode.SPEED -> tr("按加载速度")
}

private fun SourceSortMode.symbol(): WenkuSymbol = when (this) {
    SourceSortMode.DEFAULT -> WenkuSymbol.Contents
    SourceSortMode.CUSTOM -> WenkuSymbol.Filter
    SourceSortMode.SPEED -> WenkuSymbol.Speed
}

@Composable
internal fun SourceOrderEntry(mode: SourceSortMode, onClick: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    GlassKitCard(shape = RoundedCornerShape(22.dp), onClick = onClick, interactiveHighlight = true) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(mode, transitionSpec = {
                fadeIn(tween(if (reduceMotion) 0 else 180)) togetherWith fadeOut(tween(if (reduceMotion) 0 else 120))
            }, label = "sourceOrderIcon") {
                WenkuIcon(it.symbol(), size = 20.dp, tint = MaterialTheme.colorScheme.primary)
            }
            Text(tr("书源排序"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            AnimatedContent(mode, transitionSpec = {
                fadeIn(tween(if (reduceMotion) 0 else 180)) togetherWith fadeOut(tween(if (reduceMotion) 0 else 120))
            }, label = "sourceOrderLabel") {
                Text(it.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WenkuIcon(WenkuSymbol.Forward, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, entrance = false)
        }
    }
}

@Composable
internal fun SourceOrderSheet(
    ordering: SourceOrdering,
    sources: List<BookSource>,
    onDismiss: () -> Unit,
    onMode: (SourceSortMode) -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var closing by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    WenkuActionSheet(tr("书源排序"), onDismiss = onDismiss, requestDismiss = closing,
        dismissEnabled = !closing) {
        BackHandler(enabled = editing && !closing) { editing = false }
        AnimatedContent(editing, modifier = Modifier.fillMaxWidth(), transitionSpec = {
            if (reduceMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
            else {
                val direction = if (targetState) 1 else -1
                (slideInHorizontally(tween(260, easing = WenkuMotion.ease)) { it * direction / 6 } +
                    fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180, easing = WenkuMotion.ease)) { -it * direction / 8 } +
                        fadeOut(tween(150))) using SizeTransform { _, _ -> tween(280, easing = WenkuMotion.ease) }
            }
        }, label = "sourceOrderPage") { editPage ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editPage) {
                    SourceSortMode.entries.forEach { option ->
                        val selected = ordering.mode == option
                        WenkuTextButton(option.label(), { onMode(option) }, Modifier.fillMaxWidth(), selected = selected,
                            wenkuSymbol = if (selected) WenkuSymbol.Selected else option.symbol())
                    }
                    AnimatedContent(ordering.mode, modifier = Modifier.fillMaxWidth(), transitionSpec = {
                        if (reduceMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        else (fadeIn(tween(200, delayMillis = 45)) togetherWith fadeOut(tween(130)))
                            .using(SizeTransform { _, _ -> tween(250, easing = WenkuMotion.ease) })
                    }, label = "sourceOrderOptions") { mode ->
                        when (mode) {
                            SourceSortMode.DEFAULT -> Unit
                            SourceSortMode.SPEED -> Text(tr("按实际搜索的成功响应耗时排序，未测量的书源排在后面。"),
                                Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SourceSortMode.CUSTOM -> WenkuTextButton(tr("调整顺序"), {
                                draft = ordering.applyTo(sources, SourceSortMode.CUSTOM).map { it.id }
                                editing = true
                            }, Modifier.fillMaxWidth(), enabled = sources.size > 1, wenkuSymbol = WenkuSymbol.Up)
                        }
                    }
                } else {
                    Text(tr("调整顺序"), style = MaterialTheme.typography.titleMedium)
                    val names = sources.associate { it.id to it.name }
                    val visible = draft.filter(names::containsKey)
                    Text(tr("用箭头移动书源，保存后立即生效。"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        itemsIndexed(visible, key = { _, id -> id }) { index, id ->
                            val surface = MaterialTheme.colorScheme
                            GlassCard(
                                modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                                shape = RoundedCornerShape(14.dp), tint = surface.surfaceVariant.copy(alpha = .48f),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(names.getValue(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    AppIconButton(Icons.Filled.ArrowUpward, "${tr("向上移动")} ${names.getValue(id)}",
                                        onClick = { draft = visible.toMutableList().apply { add(index - 1, removeAt(index)) } },
                                        enabled = index > 0, wenkuSymbol = WenkuSymbol.ArrowUp)
                                    AppIconButton(Icons.Filled.ArrowDownward, "${tr("向下移动")} ${names.getValue(id)}",
                                        onClick = { draft = visible.toMutableList().apply { add(index + 1, removeAt(index)) } },
                                        enabled = index < visible.lastIndex, wenkuSymbol = WenkuSymbol.ArrowDown)
                                }
                            }
                        }
                    }
                    WenkuTextButton(tr("保存顺序"), {
                        closing = true
                        onSave(visible)
                    }, Modifier.fillMaxWidth(), enabled = !closing, selected = true, wenkuSymbol = WenkuSymbol.Check)
                    WenkuTextButton(tr("取消"), { editing = false }, Modifier.fillMaxWidth(), wenkuSymbol = WenkuSymbol.Close)
                }
            }
        }
    }
}
