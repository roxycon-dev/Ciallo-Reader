package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 骨架屏：移动高光的浅灰渐变块，用于封面网格加载过渡。 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12
) {
    if (LocalRenderQuality.current.minimal) {
        Box(modifier.clip(RoundedCornerShape(cornerRadius.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -300f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    // 由主题派生，避免在深色模式下出现刺眼的亮白块
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val glow = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    // 修复：原实现只计算了 Modifier 却没有可组合节点承载，函数实际不渲染任何东西，
    // 导致所有骨架屏占位都是纯空白（书库聚合搜索加载时可见 4 个空档位）。
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(base, glow, base),
                    start = Offset(x - 240f, 0f),
                    end = Offset(x, 260f)
                )
            )
    )
}
