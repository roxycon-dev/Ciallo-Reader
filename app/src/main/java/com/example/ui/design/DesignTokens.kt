package com.example.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ciallo Reader 设计规范（全局统一，后续新页面一律按此执行）。
 *
 * 圆角（Corner Radius）
 *  - XS 8dp   小标签、热力格、微型图标容器
 *  - SM 12dp  图表容器、小卡片、缩略图
 *  - MD 16dp  图标容器、次级卡片、设置项卡片
 *  - LG 20dp  主内容卡片（统计页、书库卡）
 *  - XL 24dp  弹窗/底部面板（与 AcrylicBottomOverlay 一致）
 *
 * 间距（Spacing Scale：4 / 8 / 12 / 16 / 20 / 24 / 32）
 *  - 页面外边距 16dp；卡片内边距 16~20dp；组件间隙 8~16dp；
 *  - 卡片之间 16dp；分组标题与内容之间 12dp。
 *
 * 阴影与边框（二选一原则）
 *  - 表现层次优先用阴影：普通卡片 elevation 2dp、浮动层 8dp+；
 *  - 禁止“既有边框又有阴影”混用；分隔线只在列表行内使用（1dp，低透明度）。
 *
 * 色彩层级
 *  - 核心数字：onSurface 20~26sp Bold；辅助文字：onSurfaceVariant 11~13sp；
 *  - 主色 MintPrimary 用于强调/选中/进度；辅色 MintSecondary 用于次级强调；
 *  - MintGold 只用于成就/峰值/连续等“亮点”语义，不能随意铺开；
 *  - 图表主序列用 primary，峰值用 gold，底纹用 primary 低透明度。
 *
 * 毛玻璃规则（Glass Rule）
 *  - 悬浮在内容之上的元素（底部 Tab 栏、弹窗、浮动按钮、TTS 条）统一用真实毛玻璃，
 *    沿用 AppBottomTabBar 参数（blurRadius 8dp、surface alpha 0.45）；
 *  - 承载主要内容的卡片本体（设置项卡、统计卡、预览卡）统一走 GlassCard：
 *    复用 Tab 栏同一套 KMPLiquidGlass 实现，仅调整 blurRadius 24dp、surface alpha 0.78，
 *    保证“背景图透出来但有明显磨砂、文字始终可读”；
 *  - 页面内顶部栏复用书架页顶部栏（Surface + 24dp 圆角 + 8dp 阴影）。
 */
object DesignTokens {
    val RadiusXs = 8.dp
    val RadiusSm = 12.dp
    val RadiusMd = 16.dp
    val RadiusLg = 20.dp
    val RadiusXl = 24.dp

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp
    val SpaceXxl = 24.dp
    val SpacePage = 16.dp

    val CardElevation = 2.dp
    val FloatingElevation = 8.dp

    fun shape(radius: Dp) = RoundedCornerShape(radius)
}
