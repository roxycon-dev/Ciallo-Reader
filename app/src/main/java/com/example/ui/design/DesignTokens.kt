package com.example.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

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

    // Compact reader controls retain their established spacing between the main scale steps.
    val SpaceCompact = 6.dp
    val SpaceTight = 10.dp
    val SpaceComfortable = 14.dp
    val SpaceLoose = 18.dp
    val SpaceSection = 32.dp

    val RadiusInner = 10.dp
    val RadiusControl = 14.dp
    val RadiusNavigation = 18.dp
    val RadiusOverlay = 28.dp

    // Reading chrome typography, shared by novel and comic settings. Body text remains user-controlled.
    val TypeMicro = 10.sp
    val TypeCaptionSmall = 11.sp
    val TypeCaption = 12.sp
    val TypeLabel = 13.sp
    val TypeBodySmall = 14.sp
    val TypeSection = 15.sp
    val TypeBody = 16.sp
    val TypeCaptionLineHeight = 18.sp
    val TypeSectionLineHeight = 21.sp
    val TypeTitle = 23.sp
    val TypeDisplay = 25.sp

    val CardElevation = 2.dp
    val FloatingElevation = 8.dp

    fun shape(radius: Dp) = RoundedCornerShape(radius)
}

/** Fixed reading surfaces and glass overlays; theme accents still come from MaterialTheme. */
object ReadingPalette {
    val OnGlassMuted = Color(0xAAFFFFFF)
    val GlassStroke = Color(0x1FFFFFFF)
    val ControlTrack = Color(0x2EFFFFFF)
    val WarmAccent = Color(0xFFF0D9C0)
    val InputStroke = Color(0x22FFFFFF)
    val DialogSurface = Color(0xFF232327)
    val SwitchTrack = Color(0x33FFFFFF)
    val ErrorMuted = Color(0xFFE58B8B)
    val OnGlassStrong = Color(0xCCFFFFFF)
    val SubtleFill = Color(0x14FFFFFF)
    val Favorite = Color(0xFFFFD27D)
    val ScrimStrong = Color(0x99000000)
    val CropGuide = Color(0x66FFFFFF)
    val ChromeSurface = Color(0xD9101012)
    val NovelDarkSurface = Color(0xFF18191C)
    val NovelPaperSurface = Color(0xFFFBF0D9)
    val NovelPaperInk = Color(0xFF5F4B32)
    val NovelDarkInk = Color(0xFFD4D4D4)
    val NovelGreenSurface = Color(0xFFE8F5E9)
    val NovelGreenInk = Color(0xFF1B5E20)
    val NovelNeutralInk = Color(0xFFE0E0E0)
    val Error = Color(0xFFFF9A9A)
}
