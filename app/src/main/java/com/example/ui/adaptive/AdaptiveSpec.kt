package com.example.ui.adaptive

import androidx.annotation.DimenRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * 全 App 统一的多设备自适应宽度规范（第十四轮 UI 一致性整改）。
 *
 * 背景：此前各弹窗/面板宽度策略各自为政——Acrylic 系用比例宽（0.86f/0.9f/0.92f）
 * 无上限钳制，平板上拉满整屏；漫画设置面板 560/840dp 但 TOC/预设面板固定 560dp；
 * M3 ModalBottomSheet 全是默认全宽。目标是手机观感不变，平板/横屏/折叠屏一律
 * 居中限宽，所有表面遵循同一套数值。
 *
 * 不引入 material3-window-size-class 依赖：MainActivity 已声明
 * configChanges 包含 orientation|screenSize，旋转不重建，LocalConfiguration
 * 实时反映当前窗口，BoxWithConstraints/Configuration 两条路径都能用。
 *
 * ══ 第十五轮（多形态屏幕体检）补充 ══
 *
 * 数值真源下沉到 res/dimen（values/dimens.xml + values-sw600dp/ + values-sw840dp/）：
 * [rememberAdaptiveSizing] 在组合期按当前 configuration 读取，sw 限定符目录可以
 * 按形态覆盖，不再需要改 Kotlin。[AdaptiveSpec] 常量保留两用：
 *  1) 非组合上下文（如 [adaptiveDialogWidth] 这类 Modifier 工厂）的编译期兜底；
 *  2) 资源缺失（理论上不会）时的回退值。
 * 两条路径数值必须一致，见 dimens.xml 顶部的同步纪律注释。
 *
 * 体检结论（详见 docs/adaptive-screen-audit.md）：五种形态（小屏 360dp / 基准
 * 393dp / 平板 960dp / 横屏 914dp / 分屏近似 411dp）实测断点数值本身无需调整，
 * 本轮只补机制与页面级容器助手，不改变任何观感。
 */

@Immutable
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** 仿 M3 断点：<600dp 手机 / 600-839dp 折叠展开与小平板 / >=840dp 平板与横屏。 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp >= 840 -> WindowWidthClass.EXPANDED
            configuration.screenWidthDp >= 600 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.COMPACT
        }
    }
}

object AdaptiveSpec {
    /** 居中弹窗最大宽（手机 0.86~0.92f 比例宽不受影响，平板钳到 560dp）。 */
    val dialogMaxWidth: Dp = 560.dp

    /** 底部面板最大宽（竖屏/窄窗）；与漫画阅读器设置面板一致。 */
    val sheetMaxWidth: Dp = 560.dp

    /** 底部面板横屏最大宽；横屏纵向空间宝贵，放宽换并排内容（漫画设置面板第 11 条同源）。 */
    val sheetMaxWidthLandscape: Dp = 840.dp

    /** 全屏滚动页（设置/统计/缓存管理）在平板上的内容最大宽，居中呈现。 */
    val pageContentMaxWidth: Dp = 720.dp
}

/** 四个关键宽度的运行时快照（资源真源 + 常量兜底），见 [rememberAdaptiveSizing]。 */
@Immutable
data class AdaptiveSizing(
    val dialogMaxWidth: Dp,
    val sheetMaxWidth: Dp,
    val sheetMaxWidthLandscape: Dp,
    val pageContentMaxWidth: Dp,
)

/**
 * 组合期读取当前窗口适用的自适应宽度表。
 *
 * 数值来自 res/dimen（可被 values-sw600dp/、values-sw840dp/ 覆盖）；
 * 读取失败时回退 [AdaptiveSpec] 同名常量，保证永不抛异常。
 * 以 configuration 为 key 记忆：折叠屏开合/分屏/旋转（configChanges 不重建）
 * 时 configuration 变化 → 自动重算。
 */
@Composable
fun rememberAdaptiveSizing(): AdaptiveSizing {
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    val density = LocalDensity.current.density
    return remember(configuration, density, resources) {
        fun dimen(@DimenRes id: Int, fallback: Dp): Dp = runCatching {
            Dp(resources.getDimension(id) / density)
        }.getOrDefault(fallback)

        AdaptiveSizing(
            dialogMaxWidth = dimen(R.dimen.adaptive_dialog_max_width, AdaptiveSpec.dialogMaxWidth),
            sheetMaxWidth = dimen(R.dimen.adaptive_sheet_max_width, AdaptiveSpec.sheetMaxWidth),
            sheetMaxWidthLandscape = dimen(
                R.dimen.adaptive_sheet_max_width_landscape,
                AdaptiveSpec.sheetMaxWidthLandscape,
            ),
            pageContentMaxWidth = dimen(
                R.dimen.adaptive_page_max_width,
                AdaptiveSpec.pageContentMaxWidth,
            ),
        )
    }
}

/** 弹窗内容宽：保留调用方比例宽的手机观感，平板/横屏钳到 560dp 居中。 */
fun Modifier.adaptiveDialogWidth(fraction: Float = 0.86f): Modifier =
    fillMaxWidth(fraction).widthIn(max = AdaptiveSpec.dialogMaxWidth)

/** 贴底面板内容宽：竖屏 560dp、横屏 840dp（须挂在 BottomCenter 宿主内）。 */
@Composable
fun Modifier.adaptiveSheetWidth(): Modifier {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sizing = rememberAdaptiveSizing()
    val max = if (landscape) sizing.sheetMaxWidthLandscape else sizing.sheetMaxWidth
    return widthIn(max = max).fillMaxWidth()
}

/**
 * 页面内容宽（第十五轮新增）：给全屏滚动页/页头等内容级容器钳制最大宽度，
 * 须挂在居中宿主（`Box(contentAlignment = Alignment.TopCenter)` 或
 * [AdaptivePageContent]）内使用。设置/统计/缓存管理三页已内联同款写法，
 * 此助手供后续页面与整改统一引用（如 TabScreenHeader 宽屏对齐内容）。
 */
fun Modifier.adaptivePageWidth(): Modifier =
    widthIn(max = AdaptiveSpec.pageContentMaxWidth).fillMaxWidth()

/**
 * M3 ModalBottomSheet 内容宿主：sheet 窗口本身全宽，此包装让内容在宽屏设备上
 * 居中限宽，手机全宽不变。fillMaxHeight 的 sheet（0.6f/0.9f 高度型）传 fillHeight。
 */
@Composable
fun AdaptiveSheetContent(
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    maxInnerWidth: Dp = AdaptiveSpec.sheetMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier then Modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxSize() else Modifier),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier
                .widthIn(max = maxInnerWidth)
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxSize() else Modifier),
            content = content
        )
    }
}

/** 全屏滚动页内容宿主：平板/横屏居中限宽 720dp，手机全宽。 */
@Composable
fun AdaptivePageContent(
    modifier: Modifier = Modifier,
    maxInnerWidth: Dp = AdaptiveSpec.pageContentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier then Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxInnerWidth).fillMaxSize(), content = content)
    }
}
