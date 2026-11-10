package com.example.ui.comic

import com.example.ui.components.PullOutIconButton
import com.example.ui.components.AppIconButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutDropdownMenuItem
import com.example.ui.components.PullOutDropdownMenu
import com.example.ui.components.pullOutCombinedClickable
import com.example.ui.wenku.WenkuReaderChromeLayer
import com.example.ui.wenku.WenkuReaderChromePart
import com.example.ui.wenku.WenkuReaderProgressSlider
import com.example.ui.wenku.wenkuReaderCapsule
import androidx.compose.runtime.State
import com.example.ui.wenku.rememberWenkuReaderChrome
import com.example.ui.wenku.WenkuSheetHost
import com.example.ui.wenku.WenkuMotion
import com.example.ui.components.paperSurface
import com.example.ui.components.pullOutClickable
import com.example.ui.design.DesignTokens
import com.example.ui.design.ReadingPalette

import com.example.ui.components.overlayTouchShield

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary
import com.example.god.GodGold
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.example.ui.adaptive.adaptiveSheetWidth
import androidx.compose.runtime.CompositionLocalProvider
import com.example.ui.reader.LocalReadingGlass
import com.example.ui.reader.ReadingGlassStyle
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.AppMotion
import com.example.ui.wenku.LocalWenkuIconContact
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuIconContact
import com.example.ui.wenku.WenkuSymbol

// 第 11 条：半透明面板底。第七轮第 3 条：毛玻璃路径的暗纱从 0xD9（85%）
// 收敛到 0xCC（80%）+ 模糊加深——玻璃质感更通透、不再"接近纯黑的沉闷"；
// backdrop 为 null（CURL 的 GL 层采不到）时仍回退 0xD9 纯半透明
// （GL SurfaceView 叠层下避免更低透明度——透底鬼影会被损伤混叠放大）
internal val PanelBg = Color(0xD91A1A1E)
internal val PanelBgGlass = Color(0xCC1A1A1E)
internal val PanelChipBg = Color(0x16FFFFFF)
internal val PanelChipActiveBg = Color(0x30FFFFFF)
internal val StrokeColor = ReadingPalette.GlassStroke
internal val TextPrimary = Color(0xFFF2F2F4)
// 0xB3 alpha ≈ 70% 白，对 PanelBg 对比度 ≈ 5.9:1（旧 0x99 仅 ~4:1，12sp 标签低于 WCAG AA）
internal val TextSecondary = Color(0xB3FFFFFF)

/**
 * 第 11 条终审版 + 第七轮第 3 条质感升级：面板真毛玻璃背景——采样模式渲染层
 * 做高斯模糊（26dp）+ 轻提饱和（玻璃通透感），再叠 0xCC 暗纱（比旧 0xD9 通透一档，
 * 摆脱"纯黑沉闷"；白纸页最坏情况下文字对比度仍 ≥ AA）。backdrop 为 null
 * （CURL 的 GL 层采不到）时回退纯半透明 PanelBg。
 */
internal fun Modifier.comicPanelGlass(backdrop: Backdrop?, shape: Shape): Modifier =
    // SurfaceView cannot provide a blurred capture; retain a quiet, legible surface.
    if (backdrop == null) background(PanelBg.copy(alpha = 0.96f))
    else drawPlainBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.18f)
            blur(radius = DesignTokens.SpaceTight.toPx())
        },
    ).background(PanelBgGlass)

/** 面板/控件的统一过渡节奏 */
private const val ENTER_MS = 240
private const val EXIT_MS = 180

/**
 * 控制层 + 面板调度（顶层 chrome）。
 * 正常阅读状态只显示漫画；呼出后呈现轻量控制栏；再进入分层设置/目录/预设/裁边面板。
 */
@Composable
fun ComicReaderChrome(
    visible: Boolean,
    panel: ComicPanel,
    onPanelChange: (ComicPanel) -> Unit,
    panelGlassBackdrop: Backdrop? = null,
    settingsInitialTab: Int = 0,
    title: String,
    chapterTitle: String?,
    currentPage: Int,
    totalPages: Int,
    spreadCount: Int,
    currentSpread: Int,
    config: ComicReaderConfig,
    store: ComicSettingsStore,
    perBookConfig: Boolean,
    onPerBookConfigChange: (Boolean) -> Unit,
    onConfigChange: (ComicReaderConfig) -> Unit,
    onJumpToSpread: (Int) -> Unit,
    spreadFirstRaw: (Int) -> Int,
    onJumpToRawPage: (Int) -> Unit,
    onGoPrev: () -> Unit,
    onGoNext: () -> Unit,
    autoRead: Boolean,
    onAutoReadToggle: () -> Unit,
    continuousFraction: Float = -1f,
    continuousFractionSource: (() -> Float)? = null,
    onJumpToScrollFraction: ((Float) -> Unit)? = null,
    spreadAtScrollFraction: (Float) -> Int = { 0 },
    toc: List<ComicTocEntry>,
    currentChapterIndex: Int,
    onJumpToChapter: ((Int) -> Unit)?,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    onGodMoment: (() -> Unit)? = null,
    chapterNavLabel: String,
    pages: List<ComicPageRef>,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    onBookStateChange: (ComicBookState) -> Unit,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
    onDownloadTranslationModels: () -> Unit = {},
    onTranslationRefresh: (Boolean) -> Unit = {},
    onBottomBarHeightChanged: (Int) -> Unit = {},
    onEditTouchZones: () -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val chrome = rememberWenkuReaderChrome(visible)
    val onDismissPanel = { onPanelChange(ComicPanel.NONE) }
    val currentRaw = (currentPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    // 进度条拖动中的缩略图预览目标页（-1 = 关闭）
    var thumbPreviewRaw by remember { mutableStateOf(-1) }
    var thumbPreviewFraction by remember { mutableFloatStateOf(-1f) }

    // 顶部控制栏
    WenkuReaderChromeLayer(chrome, visible) {
        ComicTopBar(
            title = title, chapterTitle = chapterTitle,
            config = config,
            store = store,
            onApplyPresetConfig = { onConfigChange(it) },
            onExit = onExit,
            onGodMoment = onGodMoment,
            onOpenPanel = { onPanelChange(it) },
            // 新反馈条目6：三点菜单"旋转本页 90°"入口已移除（按用户要求）；
            // 旋转能力本体保留在设置面板·图像 Tab（"整本旋转/旋转本页 +90°"），
            // pageRotations 状态模型不受影响（手动裁边/管线指纹仍引用）
            canToggleMerge = currentRaw in bookState.mergeAnchors || currentRaw < pages.lastIndex,
            onToggleMerge = { onBookStateChange(bookState.toggleMerge(currentRaw, pages.size)) },
        )
    }

    // 底部控制栏 + 悬浮缩略图气泡（气泡由 Chrome 层渲染，不占底栏布局）
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.onSizeChanged { onBottomBarHeightChanged(it.height) }) {
            ComicBottomBar(
                chrome = chrome, visible = visible,
                currentPage = currentPage, totalPages = totalPages,
                spreadCount = spreadCount, currentSpread = currentSpread,
                config = config,
                onThumbPreview = { raw, fraction ->
                    thumbPreviewRaw = raw
                    thumbPreviewFraction = fraction
                },
                autoRead = autoRead, onAutoReadToggle = onAutoReadToggle,
                onJumpToSpread = onJumpToSpread,
                spreadFirstRaw = spreadFirstRaw,
                onGoPrev = onGoPrev, onGoNext = onGoNext,
                onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
                chapterNavLabel = chapterNavLabel,
                continuousFraction = continuousFraction,
                continuousFractionSource = continuousFractionSource,
                onJumpToScrollFraction = onJumpToScrollFraction,
                spreadAtScrollFraction = spreadAtScrollFraction,
            )
        }
        if (visible && panel == ComicPanel.NONE && config.showThumbPreview &&
            thumbPreviewRaw in pages.indices && spreadCount > 1
        ) {
            // 气泡水平位置跟随进度滑块拇指（±130dp 内偏移，避免贴边裁切）
            val fraction = if (thumbPreviewFraction in 0f..1f) thumbPreviewFraction
                else (thumbPreviewRaw.toFloat() / (pages.size - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val visualFraction = if (config.direction == ComicDirection.RTL &&
                config.mode != ComicMode.WEBTOON && config.mode != ComicMode.CONTINUOUS) 1f - fraction else fraction
            ComicThumbPreview(
                page = pages[thumbPreviewRaw],
                rawShown = thumbPreviewRaw,
                loader = loader,
                modifier = Modifier
                    .padding(bottom = 316.dp)
                    .offset(x = ((visualFraction - 0.5f) * 260f).dp),
            )
        }
    }

    var displayedPanel by remember { mutableStateOf(panel) }
    if (panel != ComicPanel.NONE) displayedPanel = panel
    WenkuSheetHost(
        visible = panel != ComicPanel.NONE,
        anchorBounds = null,
        onDismiss = onDismissPanel,
        viewportContent = true
    ) {
        // Only the panel boundary samples the comic. Its controls tint the panel already underneath them.
        CompositionLocalProvider(LocalReadingGlass provides ReadingGlassStyle(ink = TextPrimary)) {
            AnimatedContent(
                targetState = displayedPanel,
                transitionSpec = {
                    if (reduceMotion) {
                        androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    } else {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        slideInHorizontally(tween(280, easing = WenkuMotion.plane)) { it * direction } togetherWith
                            slideOutHorizontally(tween(280, easing = WenkuMotion.plane)) { -it * direction }
                    }
                }, modifier = Modifier.fillMaxSize(), label = "comicPanelContent",
            ) { activePanel ->
                when (activePanel) {
                    ComicPanel.SETTINGS -> ComicSettingsSheet(
                        config = config, store = store, perBookConfig = perBookConfig,
                        onPerBookConfigChange = onPerBookConfigChange,
                        onConfigChange = onConfigChange,
                        onOpenPreset = { onPanelChange(ComicPanel.PRESET) },
                        onOpenCrop = { onPanelChange(ComicPanel.CROP) },
                        pages = pages, loader = loader,
                        currentRawPage = currentRaw,
                        bookState = bookState, onBookStateChange = onBookStateChange,
                        onDismiss = onDismissPanel,
                        glassBackdrop = panelGlassBackdrop,
                        onDownloadTranslationModels = onDownloadTranslationModels,
                        onTranslationRefresh = onTranslationRefresh,
                        initialTab = settingsInitialTab,
                        onEditTouchZones = onEditTouchZones,
                    )
                    ComicPanel.TOC -> ComicTocSheet(
                        toc = toc, currentChapterIndex = currentChapterIndex,
                        onJumpToChapter = onJumpToChapter,
                        pages = pages, loader = loader,
                        currentPage = currentPage,
                        onJumpToRawPage = onJumpToRawPage,
                        onDismiss = onDismissPanel,
                        glassBackdrop = panelGlassBackdrop,
                    )
                    ComicPanel.PRESET -> ComicPresetSheet(
                        store = store, currentConfig = config,
                        onApply = { onConfigChange(it) },
                        onDismiss = onDismissPanel,
                        glassBackdrop = panelGlassBackdrop,
                    )
                    ComicPanel.CROP -> {
                        val close = com.example.ui.components.LocalOverlayClose.current
                        ComicCropSheet(
                            page = pages.getOrNull(currentRaw),
                            loader = loader, config = config,
                            onDismiss = onDismissPanel,
                            onApply = { l, t, r, b ->
                                close { onConfigChange(config.copy(manualCrop = listOf(l, t, r, b))) }
                            },
                        )
                    }
                    ComicPanel.NONE -> Unit
                }
            }
        }
    }
}

/* ══════════════ 顶栏 ══════════════ */

@Composable
private fun ComicTopBar(
    title: String,
    chapterTitle: String?,
    config: ComicReaderConfig,
    store: ComicSettingsStore,
    onApplyPresetConfig: (ComicReaderConfig) -> Unit,
    onExit: () -> Unit,
    onOpenPanel: (ComicPanel) -> Unit,
    canToggleMerge: Boolean,
    onToggleMerge: () -> Unit,
    onGodMoment: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 收藏预设快捷应用（第 27 条）：长按"阅读设置"按钮直接弹出收藏列表一键应用
    var favMenuOpen by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(store.favoritePresets()) }
    var settingsAnchor by remember { mutableStateOf<Rect?>(null) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var settingsCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menuCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val settingsInteraction = remember { MutableInteractionSource() }
    val settingsPressed by settingsInteraction.collectIsPressedAsState()
    var settingsActivation by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = DesignTokens.SpaceXl, bottomEnd = DesignTokens.SpaceXl))
            .background(ReadingPalette.ChromeSurface)
            .border(0.5.dp, StrokeColor, RoundedCornerShape(bottomStart = DesignTokens.SpaceXl, bottomEnd = DesignTokens.SpaceXl))
            .statusBarsPadding()
            .padding(horizontal = DesignTokens.SpaceCompact, vertical = DesignTokens.SpaceSm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onExit)
            Spacer(Modifier.width(DesignTokens.SpaceXs))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = TextPrimary, fontSize = DesignTokens.TypeBody, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    chapterTitle ?: ("${config.mode.label} · ${config.direction.label}"),
                    color = TextSecondary, fontSize = DesignTokens.TypeCaptionSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            ChromeIconButton(Icons.Filled.AutoStories, "目录") { onOpenPanel(ComicPanel.TOC) }
            if (onGodMoment != null) {
                ChromeIconButton(Icons.Filled.StarOutline, "标记神回", tint = GodGold.DarkStart, onClick = onGodMoment)
            }
            // 长按 = 收藏预设快捷应用（第 27 条）；点击 = 打开设置面板
            Box {
                Box(
                    Modifier
                        .size(44.dp)
                        .onGloballyPositioned { settingsCoordinates = it }
                        .clip(CircleShape)
                        .semantics { contentDescription = "阅读设置" }
                        .pullOutCombinedClickable(
                            interactionSource = settingsInteraction,
                            role = androidx.compose.ui.semantics.Role.Button,
                            onLongClick = {
                                settingsActivation++
                                favorites = store.favoritePresets()
                                if (favorites.isNotEmpty()) {
                                    settingsAnchor = settingsCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
                                    favMenuOpen = true
                                } else onOpenPanel(ComicPanel.PRESET)
                            },
                            indication = null,
                            onClick = {
                                settingsActivation++
                                onOpenPanel(ComicPanel.SETTINGS)
                            },
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(
                        LocalWenkuIconContact provides WenkuIconContact(settingsPressed, settingsActivation)
                    ) {
                        WenkuIcon(WenkuSymbol.Settings, size = 22.dp, tint = TextPrimary, entrance = false)
                    }
                }
                PullOutDropdownMenu(
                    expanded = favMenuOpen,
                    onDismissRequest = { favMenuOpen = false },
                    containerColor = ReadingPalette.DialogSurface,
                    anchorInWindow = settingsAnchor,
                ) {
                    Text(
                        "收藏预设",
                        color = TextSecondary, fontSize = DesignTokens.TypeCaptionSmall,
                        modifier = Modifier.padding(horizontal = DesignTokens.SpaceLg, vertical = DesignTokens.SpaceXs),
                    )
                    favorites.forEach { p ->
                        PullOutDropdownMenuItem(
                            text = { Text(p.name, color = TextPrimary) },
                            leadingIcon = { Text(p.emoji, color = MintPrimary, fontSize = DesignTokens.TypeCaption) },
                            onClick = com.example.ui.components.paperDismiss { onApplyPresetConfig(p.config) },
                        )
                    }
                }
            }
            Box {
                ChromeIconButton(
                    Icons.Filled.MoreHoriz, "更多",
                    modifier = Modifier.onGloballyPositioned { menuCoordinates = it }
                ) {
                    menuAnchor = menuCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
                    menuOpen = true
                }
                PullOutDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = ReadingPalette.DialogSurface,
                    anchorInWindow = menuAnchor,
                ) {
                    PullOutDropdownMenuItem(
                        text = { Text("临时合页 / 取消", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.AutoStories, null, tint = TextSecondary) },
                        enabled = canToggleMerge,
                        onClick = com.example.ui.components.paperDismiss(onToggleMerge)
                    )
                    PullOutDropdownMenuItem(
                        text = { Text("手动裁边", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Settings, null, tint = TextSecondary) },
                        onClick = com.example.ui.components.paperOpen { menuOpen = false; onOpenPanel(ComicPanel.CROP) },
                    )
                    PullOutDropdownMenuItem(
                        text = { Text("阅读预设", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.ChevronLeft, null, tint = TextSecondary) },
                        onClick = com.example.ui.components.paperOpen { menuOpen = false; onOpenPanel(ComicPanel.PRESET) },
                    )
                }
            }
        }
    }
}

/* ══════════════ 底栏 ══════════════ */

@Composable
private fun ComicBottomBar(
    chrome: State<Float>,
    visible: Boolean,
    currentPage: Int,
    totalPages: Int,
    spreadCount: Int,
    currentSpread: Int,
    config: ComicReaderConfig,
    onThumbPreview: (Int, Float) -> Unit,
    spreadFirstRaw: (Int) -> Int,
    autoRead: Boolean,
    onAutoReadToggle: () -> Unit,
    onJumpToSpread: (Int) -> Unit,
    onGoPrev: () -> Unit,
    onGoNext: () -> Unit,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    chapterNavLabel: String,
    continuousFraction: Float = -1f,
    continuousFractionSource: (() -> Float)? = null,
    onJumpToScrollFraction: ((Float) -> Unit)? = null,
    spreadAtScrollFraction: (Float) -> Int = { 0 },
) {
    val reverse = config.direction == ComicDirection.RTL &&
        config.mode !in setOf(ComicMode.WEBTOON, ComicMode.CONTINUOUS)
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = DesignTokens.SpaceLg, vertical = DesignTokens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WenkuReaderChromeLayer(chrome, visible, part = WenkuReaderChromePart.Progress) {
            val onstage by remember(chrome) {
                derivedStateOf { chrome.value > WenkuReaderChromePart.Progress.start }
            }
            val fraction = if (onstage) continuousFractionSource?.invoke() ?: continuousFraction else continuousFraction
            val scrolling = fraction in 0f..1f && onJumpToScrollFraction != null
            val sliderRange = 0f..if (scrolling) 1f else (spreadCount - 1).coerceAtLeast(1).toFloat()
            val sliderValue = if (scrolling) fraction else currentSpread.toFloat()
            var dragging by remember { mutableStateOf(false) }
            var dragTarget by remember(scrolling) { mutableFloatStateOf(sliderValue) }

            Row(
                Modifier.fillMaxWidth().wenkuReaderCapsule(ReadingPalette.ChromeSurface, StrokeColor)
                    .padding(horizontal = DesignTokens.SpaceComfortable),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$currentPage / $totalPages", color = TextPrimary, fontSize = DesignTokens.TypeBodySmall, fontWeight = FontWeight.Bold)
                WenkuReaderProgressSlider(
                    value = if (dragging) dragTarget else sliderValue,
                    label = "阅读进度",
                    tint = MintPrimary,
                    onValueChange = {
                        dragging = true
                        dragTarget = it
                        val spread = (if (scrolling) spreadAtScrollFraction(it) else it.toInt())
                            .coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
                        onThumbPreview(spreadFirstRaw(spread), it / sliderRange.endInclusive)
                    },
                    onCommit = {
                        if (scrolling) onJumpToScrollFraction?.invoke(dragTarget)
                        else onJumpToSpread(dragTarget.toInt())
                        dragging = false
                        onThumbPreview(-1, -1f)
                    },
                    format = {
                        if (scrolling) "${(it * 100).toInt()}%"
                        else "第 ${spreadFirstRaw(it.toInt().coerceIn(0, (spreadCount - 1).coerceAtLeast(0))) + 1} 页"
                    },
                    enabled = spreadCount > 1 || scrolling,
                    reverse = reverse,
                    valueRange = sliderRange,
                    modifier = Modifier.weight(1f)
                )
                val percent = if (fraction in 0f..1f) fraction else currentPage.toFloat() / totalPages.coerceAtLeast(1)
                Text("· ${(percent * 100).toInt()}%", color = TextSecondary, fontSize = DesignTokens.TypeCaption)
            }
        }
        WenkuReaderChromeLayer(chrome, visible, part = WenkuReaderChromePart.Dock) {
            Column(
                Modifier.fillMaxWidth().wenkuReaderCapsule(ReadingPalette.ChromeSurface, StrokeColor)
                    .padding(horizontal = DesignTokens.SpaceComfortable, vertical = DesignTokens.SpaceXs)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (autoRead) "自动阅读中" else config.mode.label,
                        color = if (autoRead) MintPrimary else TextSecondary,
                        fontSize = DesignTokens.TypeCaptionSmall,
                        modifier = Modifier.weight(1f)
                    )
                    ChromeIconButton(if (reverse) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft, "上一页") { onGoPrev() }
                    ChromeIconButton(
                        if (autoRead) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (autoRead) "暂停自动阅读" else "开始自动阅读"
                    ) { onAutoReadToggle() }
                    ChromeIconButton(if (reverse) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, "下一页") { onGoNext() }
                }
                if (onPrevChapter != null || onNextChapter != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = DesignTokens.SpaceTight),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)
                    ) {
                        ChapterNavButton(
                            "上一$chapterNavLabel", enabled = onPrevChapter != null,
                            modifier = Modifier.weight(1f)
                        ) { onPrevChapter?.invoke() }
                        ChapterNavButton(
                            "下一$chapterNavLabel", enabled = onNextChapter != null,
                            modifier = Modifier.weight(1f)
                        ) { onNextChapter?.invoke() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterNavButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PullOutTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(DesignTokens.RadiusSm),
        colors = ButtonDefaults.textButtonColors(
            containerColor = PanelChipBg,
            contentColor = TextPrimary,
            disabledContainerColor = Color(0x10FFFFFF),
            disabledContentColor = Color(0x55FFFFFF)
        ),
        contentPadding = PaddingValues(vertical = DesignTokens.SpaceTight)
    ) {
        Text(text, fontSize = DesignTokens.TypeCaption)
    }
}

/** 滑条拖动时的页面缩略图气泡（悬浮，不占布局） */
@Composable
internal fun ComicThumbPreview(
    page: ComicPageRef?,
    rawShown: Int,
    loader: ComicPageLoader,
    modifier: Modifier = Modifier,
) {
    val bitmap by com.example.ui.components.rememberAsyncState<Bitmap?>(null, page?.id) {
        page?.let { value = runCatching { loader.loadThumb(it) }.getOrNull() }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(DesignTokens.RadiusSm))
            .background(PanelBg)
            .border(0.5.dp, StrokeColor, RoundedCornerShape(DesignTokens.RadiusSm))
            .padding(DesignTokens.SpaceCompact),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(RoundedCornerShape(DesignTokens.RadiusXs))
                .background(ReadingPalette.InputStroke),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: com.example.ui.components.ChasingDots(
                size = DesignTokens.SpaceXl,
                color = MintPrimary,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpaceXs))
        Text("第 ${rawShown + 1} 页", color = TextPrimary, fontSize = DesignTokens.TypeCaptionSmall)
    }
}

/* ══════════════ 通用控件 ══════════════ */

@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    desc: String,
    tint: Color = TextPrimary.copy(alpha = 0.92f),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AppIconButton(icon, desc, onClick, modifier.size(44.dp), tint = tint)
}

internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pullOutClickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

/** 分段选择器（id 由调用方定义）。
 *  FlowRow 流式布局：每项按文字实际宽度占位，一行放不下自动换行——
 *  修复"7 个手势选项/5 个长 label 挤同一行全部截断"的实机 bug（weight 均分压缩所致）。
 *  第七轮第 3 条：选中态 = 品牌薄荷半透明填充 + 细边框 + SemiBold 文字
 *  （不再只靠文字变色），与 Tab/场景/色卡同一套选中语言。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SegmentRow(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.SpaceXs)) {
        Text(title, color = TextSecondary, fontSize = DesignTokens.TypeCaption)
        Spacer(Modifier.height(DesignTokens.SpaceCompact))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DesignTokens.RadiusSm))
                .background(PanelChipBg)
                .selectableGroup()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEach { (id, label) ->
                val active = id == selected
                val animatedTint by animateColorAsState(if (active) panelSelectedBg() else Color.Transparent, label = "comicOptionTint")
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(animatedTint)
                        .border(
                            0.5.dp,
                            if (active) panelSelectedStroke() else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .selectable(active, role = Role.RadioButton) { onSelect(id) }
                        .heightIn(min = 44.dp)
                        .padding(vertical = DesignTokens.SpaceSm, horizontal = DesignTokens.SpaceTight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) MintPrimary else ReadingPalette.OnGlassMuted,
                        fontSize = DesignTokens.TypeCaption,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        softWrap = true
                    )
                }
            }
        }
    }
}

/** 滑条设置行（trailing lambda = onChange，实时预览；持久化由上层防抖）。
 *  第七轮第 3 条：M3 Slider → 面板统一自绘滑条（细玻璃轨道 + 薄荷填充 +
 *  白色圆钮），全面板唯一滑条样式。 */
@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextSecondary, fontSize = DesignTokens.TypeCaption, modifier = Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            Text(format(value), color = MintPrimary, fontSize = DesignTokens.TypeCaption, fontWeight = FontWeight.Medium)
        }
        PanelSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            formatLabel = format,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = title; stateDescription = format(value) },
        )
    }
}

/** 开关行。第七轮第 3 条：M3 Switch → 面板统一玻璃胶囊开关。 */
@Composable
internal fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = DesignTokens.SpaceCompact),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = DesignTokens.TypeLabel)
            subtitle?.let { Text(it, color = TextSecondary, fontSize = DesignTokens.TypeCaptionSmall) }
        }
        PanelSwitch(checked = checked, onChange = onChange, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** 通用底部面板容器（贴底、圆角、毛玻璃；横屏限宽居中避免占满全屏） */
@Composable
internal fun ComicSheetContainer(
    title: String,
    onDismiss: () -> Unit,
    heightFraction: Float = 0.66f,
    scrollable: Boolean = true,
    glassBackdrop: Backdrop? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
    val wide = maxWidth >= 600.dp || maxWidth > maxHeight
    val sheetShape = RoundedCornerShape(DesignTokens.RadiusOverlay)
    Column(
        Modifier
            // adaptiveSheetWidth = widthIn 必须在 fillMaxWidth 之前：先钳制最大宽度再填充——
            // 反序会让 fillMaxWidth 先撑满屏宽，560dp 背景钉在左侧留出无背景空区。
            // 横屏放宽到 840dp，与阅读设置面板（第 11 条）同一套自适应规范
            .align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter)
            .padding(horizontal = if (wide) DesignTokens.SpaceLg else DesignTokens.SpaceSm, vertical = DesignTokens.SpaceSm)
            .widthIn(max = 620.dp).fillMaxWidth()
            .fillMaxHeight(if (wide) 0.94f else heightFraction)
            .paperSurface()
            .clip(sheetShape)
            .comicPanelGlass(glassBackdrop, sheetShape)
            .border(0.5.dp, StrokeColor, sheetShape)
            .overlayTouchShield()
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = DesignTokens.SpaceTight)
                    .size(width = 40.dp, height = DesignTokens.SpaceXs)
                    .clip(CircleShape)
                    .background(ReadingPalette.ControlTrack)
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = DesignTokens.SpaceXl, end = DesignTokens.SpaceSm), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimary, fontSize = DesignTokens.TypeSectionLineHeight, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PullOutIconButton(
                icon = androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "关闭$title",
                tint = TextSecondary,
                onClick = com.example.ui.components.paperDismiss(onDismiss)
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = DesignTokens.SpaceXl)
        ) {
            content()
        }
    }
    }
}

/** 面板小节标题（次级色：强调色收敛到激活态控件与滑条数值） */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text, color = TextSecondary, fontSize = DesignTokens.TypeCaption, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = DesignTokens.SpaceComfortable, bottom = 2.dp)
    )
}
