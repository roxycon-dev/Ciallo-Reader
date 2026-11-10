package com.example.ui.wenku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 参考 `app_ui.dart:2047-2096`：玻璃菜单从**触发控件在 Overlay 里的矩形**长出来。
 * Compose 侧等价物是 root 坐标下的 `Rect`，所以这里只在布局阶段记录一次几何，
 * 不进重组循环。
 */
@Composable
fun rememberWenkuAnchor(): Pair<Rect?, (Rect) -> Unit> {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    return anchor to { anchor = it }
}

/** 记录控件在 root 坐标系里的矩形，供 `WenkuMenuStretch` / `WenkuSheetHost` 的锚点使用。 */
fun Modifier.wenkuAnchor(onAnchor: (Rect) -> Unit): Modifier = composed {
    onGloballyPositioned { onAnchor(it.boundsInRoot()) }
}
