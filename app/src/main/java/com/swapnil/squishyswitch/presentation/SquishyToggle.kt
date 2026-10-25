package com.swapnil.squishyswitch.presentation

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 弹性挤压开关：切换时圆形滑块经历 拉伸→移动→挤压→恢复 四阶段动画。
 *
 * 支持受控/非受控双模式：
 *  - 受控：传入 [checked] + [onCheckedChange]，状态由调用方持有（设置页使用）；
 *  - 非受控：不传 checked，内部维护开关状态。
 */
@Composable
fun SquishyToggleSwitch(
    color: Color,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    containerHeight: Int = 32,
    containerWidth: Int = 60,
    circleSize: Int = 24,
    padding: Int = 4,
    shadowOffset: Int = 5
) {
    // 受控/非受控双模式：传入 checked 即受控（本 app 设置页使用）
    var internalToggle by remember { mutableStateOf(false) }
    val isToggled = checked ?: internalToggle
    val haptics = LocalHapticFeedback.current

    val transition = updateTransition(targetState = isToggled, label = "Switch Transition")
    val trackColor = transition.animateColor(
        transitionSpec = { tween(durationMillis = 250) },
        label = "Track Color"
    ) { checked -> if (checked) color else Color.LightGray }

    // Animatable properties — 初始值跟随外部 checked 状态（修复刷新后白圆停左边）
    val thumbPosition = remember { Animatable(if (isToggled) 1f else 0f) }
    val squishX = remember { Animatable(1f) }
    val squishY = remember { Animatable(1f) }

    // Define easing curves for smooth, jelly-like movement
    val stretchEasing = CubicBezierEasing(0.75f, 0f, 1f, 1f)
    val compressEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /* ⚠️ 动画健壮性改造（原实现用 rememberCoroutineScope().launch 驱动）：
     *
     *  旧写法：每次 flip() 都往同一个 CoroutineScope 里再 launch 一段
     *  「拉伸 → 移动 → 挤压 → 恢复」的四阶段链。快速连点时会有**多条链并发**，
     *  它们抢同一批 Animatable —— Animatable.animateTo 会取消前一个，
     *  于是上一条链的 joinAll 提前返回、直接跳到 Step 4 把形变归位，
     *  与新链的拉伸阶段互相踩踏，最终可能停在 1.15f 的拉伸态再也不回弹。
     *
     *  新写法：改由 `LaunchedEffect(isToggled)` 作为**唯一**动画驱动，
     *  状态翻转即重启整段动画，任何时刻只有一条链在跑；
     *  即使被取消 / 系统关闭动画，最后一步 restore 也保证把 squish 收敛回 1f，
     *  滑块永远不会被「冻」在挤压形变里。
     */
    LaunchedEffect(isToggled) {
        val targetValue = if (isToggled) 1f else 0f

        // Step 1+2 并行：拉伸 + 移动（压缩总时长至 ~400ms）
        val stretchXJob = launch {
            squishX.animateTo(1.15f, animationSpec = tween(180, easing = stretchEasing))
        }
        val compressYJob = launch {
            squishY.animateTo(0.92f, animationSpec = tween(180, easing = stretchEasing))
        }
        val moveJob = launch {
            thumbPosition.animateTo(
                targetValue,
                animationSpec = tween(250, easing = compressEasing)
            )
        }
        joinAll(stretchXJob, compressYJob, moveJob)

        // Step 3: Squash on arrival
        val squashXJob = launch {
            squishX.animateTo(0.95f, animationSpec = tween(150, easing = compressEasing))
        }
        val expandYJob = launch {
            squishY.animateTo(1.05f, animationSpec = tween(150, easing = compressEasing))
        }
        joinAll(squashXJob, expandYJob)

        // Step 4: Restore
        launch { squishX.animateTo(1f, animationSpec = tween(200)) }
        launch { squishY.animateTo(1f, animationSpec = tween(200)) }
    }

    fun flip() {
        val nv = !isToggled
        if (checked == null) internalToggle = nv
        onCheckedChange?.invoke(nv)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // 动画交给上面的 LaunchedEffect(isToggled) 驱动，这里只改状态
    }

    val maxTranslation =
        with(LocalDensity.current) { (containerWidth - circleSize - (padding * 2)).dp.toPx() }

    Box(
        modifier = Modifier
            .size(containerWidth.dp, containerHeight.dp)
            // 第十一轮第 3 条修复：点击区域从"仅滑块小圆"(24dp) 扩大到整个开关轨道。
            // 旧版 clickable 挂在 Canvas（拇指圆）上，点轨道完全不响应——实机表现
            // "开关点不动"（隐私模式/密码保护拨不动的重要原因）。toggleable 同时
            // 提供 Role.Switch + 状态的无障碍语义。
            .toggleable(
                value = isToggled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = { flip() }
            )
            .clip(CircleShape)
            .drawWithCache { onDrawBehind { drawRect(trackColor.value) } }

            .padding(padding.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .size(circleSize.dp)
                .graphicsLayer {
                    scaleX = squishX.value
                    scaleY = squishY.value
                    shape = CircleShape
                    translationX = thumbPosition.value * maxTranslation
                    shadowElevation = 10.dp.toPx()
                }
                .clip(CircleShape)
                .drawWithCache {
                    val paint = Paint().apply {
                        shader = RadialGradientShader(
                            center = Offset(
                                size.width / 2,
                                size.height / 2 - shadowOffset.dp.toPx()
                            ), // Moves it UP
                            radius = size.width / 1.2f, // Slightly smaller than full size
                            colors = listOf(
                                Color.White, // Fades inward
                                Color.White,
                                // Color.White,
                                Color.Black.copy(alpha = 0.4f), // Dark edges (simulating depth)
                            ),
                            colorStops = listOf(0f,0.6f,1f), // Gradual transition
                            tileMode = TileMode.Clamp
                        )
                    }
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2
                    onDrawBehind {
                        drawCircle(color = Color.White, style = Fill)
                        drawIntoCanvas { it.drawCircle(center, radius, paint) }
                    }
                }
        ) { }
    }
}
