package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.theme.MintPrimary
import com.swapnil.squishyswitch.presentation.SquishyToggleSwitch

/**
 * 全局唯一开关组件（第十一轮第 3 条：开关统一）。
 *
 * 此前项目里并存三套开关实现（SquishyToggleSwitch / AppLiquidSwitch / JellySwitch），
 * 隐私模式与设置页视觉交互不一致。现在全部收敛到本组件：
 * - 核心 = SquishyToggleSwitch（设置页一直使用的四阶段弹性挤压动画 + 触觉反馈）；
 * - 颜色 = 品牌薄荷 MintPrimary，全 App 一致；
 * - 所有页面（设置 / 隐私 / 书架 / 阅读器 / 书源管理）一律使用 AppSwitch，
 *   不允许再直接引用其它开关实现。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 轨道尺寸与滑块尺寸。**默认值 = SquishyToggleSwitch 出厂值**，因此既有 13 处
     * 调用点视觉零回归；书源管理页按该页设计规范传 44×26 / 滑块 20 / 内边距 3。
     *
     * ⚠️ 三者必须成套修改：位移量 = containerWidth − circleSize − padding×2，
     * 任一项单独改动都会让滑块行程对不上轨道（滑出轨道或走不满）。
     */
    containerWidth: Int = 60,
    containerHeight: Int = 32,
    circleSize: Int = 24,
    padding: Int = 4,
) {
    if (LocalRenderQuality.current.minimal) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier)
        return
    }
    // 2026-09-21 修复：modifier 此前接收后从未向下传递 —— 调用方传入的尺寸/间距
    // 修饰会被静默丢弃（目前 13 处调用恰好都没传，所以还没暴露，但迟早踩坑）。
    // SquishyToggleSwitch 本身不接受 modifier，用 Box 承接；
    // propagateMinConstraints 保证外部给的最小尺寸仍能传到开关上。
    Box(modifier = modifier, propagateMinConstraints = true) {
        SquishyToggleSwitch(
            color = MintPrimary,
            checked = checked,
            onCheckedChange = onCheckedChange,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            circleSize = circleSize,
            padding = padding,
        )
    }
}
