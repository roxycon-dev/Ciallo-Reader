package com.example.ui

import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutAlertDialog
import com.example.ui.components.Slider
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.BackupManager
import com.example.data.CategoryEntity
import com.example.data.PreferencesManager
import com.example.ui.pageturn.PageTurnType
import com.example.ui.components.AppButton
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.scrollTiltSource
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.TabScreenHeader
import com.example.ui.components.rememberHeaderCollapsedSource
import com.example.ui.components.SegmentedPillSelector
import com.example.ui.components.DialogLiquidGlass
import com.example.ui.components.GlassCard
import com.ramotion.fluidslider.FluidSlider
import com.example.ui.components.AppSwitch
import com.example.ui.components.PageTurnSelectorRow
import com.example.ui.components.CustomMinutesDialog
import com.example.ui.components.AppIconButton
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.clickableWithFeedback
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuSectionHeader
import com.example.ui.wenku.wenkuPagePart
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.theme.onColor
import com.example.ui.theme.glassTitleColor
import com.example.ui.help.LibraryHelpBottomSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppToast
import com.example.BuildConfig
import com.example.ui.design.DesignTokens as D
import com.example.data.GithubUpdateChecker
import com.example.data.AppUpdateManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabScreen(
    prefs: PreferencesManager,
    backupManager: BackupManager,
    categories: List<CategoryEntity>,
    onAddCategory: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenSourceManager: (() -> Unit)? = null,
    extraBottomPadding: Dp = 0.dp,
    autoNightModeVal: Boolean = prefs.autoNightMode,
    onAutoNightModeChange: (Boolean) -> Unit = { prefs.autoNightMode = it },
    blueLightFilterVal: Boolean = prefs.blueLightFilter,
    onBlueLightFilterChange: (Boolean) -> Unit = { prefs.blueLightFilter = it },
    blueLightAlphaVal: Float = prefs.blueLightAlpha,
    onBlueLightAlphaChange: (Float) -> Unit = { prefs.blueLightAlpha = it },
    onAdultSourcesChange: (Boolean) -> Unit = {},
    orientationLockVal: Int = prefs.screenOrientationLock,
    onOrientationLockChange: (Int) -> Unit = { prefs.screenOrientationLock = it },
    hapticsEnabledVal: Boolean = prefs.hapticsEnabled,
    onHapticsChange: (Boolean) -> Unit = { prefs.hapticsEnabled = it },
    renderQualityVal: Int = prefs.renderQuality,
    onRenderQualityChange: (Int) -> Unit = { prefs.renderQuality = it },
    onOpenThemePalette: (() -> Unit)? = null,
    onOpenCardTweaks: (() -> Unit)? = null,
    onOpenCacheManager: (() -> Unit)? = null,
    /* ── 神回（GodMoment）设置分组 ── */
    godSettings: com.example.god.GodMomentSettingsStore? = null,
    onOpenGodRanking: (() -> Unit)? = null,
    /** true = 从排行榜陈列方式按钮跳入：打开后动画滚动到神回设置分区 */
    focusGodSettings: Boolean = false,
    /* ── 隐私模式（第七轮第 6.4 条）：全部带默认值，未接线时不出现该能力 ── */
    privacyModeEnabled: Boolean = false,
    onEnablePrivacyMode: suspend (String) -> Boolean = { false },
    onDisablePrivacyMode: suspend (String) -> Boolean = { false },
    onVerifyPrivacyPin: suspend (String) -> Boolean = { false },
    onChangePrivacyPin: suspend (String, String) -> Boolean = { _, _ -> false },
    onToggleCategoryProtected: (CategoryEntity, Boolean) -> Unit = { _, _ -> },
    /* ── 第九轮：全局无痕浏览开关（在线/不受保护分类的阅读也可不计统计） ── */
    incognitoBrowsingEnabled: Boolean = false,
    onSetIncognitoBrowsing: (Boolean) -> Unit = {},
    /* ── 「我喜欢的」密码保护（与受保护分类同一套 PIN） ── */
    favoritesProtected: Boolean = false,
    onSetFavoritesProtected: (Boolean) -> Unit = {},
) {
    var showDiscoverSettings by remember { mutableStateOf(false) }
    if (showDiscoverSettings) com.example.discover.DiscoverSettings { showDiscoverSettings = false }
    var showReleaseHistory by remember { mutableStateOf(false) }
    if (showReleaseHistory) com.example.ui.components.ReleaseHistoryDialog { showReleaseHistory = false }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val updateChecker = remember(context.applicationContext) { GithubUpdateChecker(context.applicationContext) }
    val appUpdate = remember(context.applicationContext) { AppUpdateManager.get(context) }
    val appUpdateState by appUpdate.state.collectAsStateWithLifecycle()
    var restReminderMinutes by remember { mutableStateOf(prefs.restReminderMinutes) }
    var showCustomMinutes by remember { mutableStateOf(false) }
    var autoNightMode by remember(autoNightModeVal) { mutableStateOf(autoNightModeVal) }
    var blueLightFilter by remember(blueLightFilterVal) { mutableStateOf(blueLightFilterVal) }
    var blueLightAlpha by remember(blueLightAlphaVal) { mutableStateOf(blueLightAlphaVal) }
    var pageTurnMode by remember { mutableStateOf(prefs.pageTurnMode) }

    var splashPosterUri by remember { mutableStateOf(prefs.customSplashPosterUri) }
    var splashPureMode by remember { mutableStateOf(prefs.splashPureMode) }
    var appBgMode by remember { mutableIntStateOf(prefs.appBackgroundMode) }
    var appBgUri by remember { mutableStateOf(prefs.customAppBackgroundUri) }
    var appBgDim by remember { mutableIntStateOf(prefs.appBackgroundDim) }

    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    var showHelpBottomSheet by remember { mutableStateOf(false) }

    /* ── 隐私模式窗口状态（第七轮第 6.4 条） ── */
    var showPinSetup by remember { mutableStateOf(false) }
    var showManageVerify by remember { mutableStateOf(false) }
    var showManageWindow by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var showDisableVerify by remember { mutableStateOf(false) }
    var closePrivacyManagement by remember { mutableStateOf(false) }
    // 2026-09-21 新增：隐私开关的“乐观选中”态。原先开关把 checked 硬编码成 false，
    // 用户点下去开关纹丝不动、只弹出密码框，看起来像没点上；现在点击后立即置为选中，
    // 若密码设置被取消/失败则回弹为关闭。
    var privacySwitchPending by remember { mutableStateOf(false) }

    val posterLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                // 换新文件名：固定路径下 URI 不变会让图片加载器命中旧缓存（同背景图 bug）
                val file = java.io.File(context.filesDir, "custom_poster_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // 删除旧海报文件
                runCatching {
                    prefs.customSplashPosterUri?.let { old ->
                        val oldFile = java.io.File(Uri.parse(old).path ?: "")
                        if (oldFile.exists() && oldFile.name.startsWith("custom_poster")) oldFile.delete()
                    }
                }
                val localUriStr = Uri.fromFile(file).toString()
                splashPosterUri = localUriStr
                prefs.customSplashPosterUri = localUriStr
                AppToast.makeText(context, "海报已设置", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppToast.makeText(context, "图片设置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 软件背景：选择图片后复制到私有目录（横竖屏统一 Crop 填充，不拉伸变形）。
    // 必须每次换新文件名：固定路径下 URI 字符串不变 → AppBackgroundController 的
    // StateFlow 因配置相等不会重发（页面零重组），Coil 也会按相同 URI 命中旧缓存，
    // 表现为“更换照片后背景未变”。
    val bgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val file = java.io.File(context.filesDir, "custom_app_bg_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                // 删除旧背景文件，避免私有目录残留堆积
                runCatching {
                    prefs.customAppBackgroundUri?.let { old ->
                        val oldFile = java.io.File(Uri.parse(old).path ?: "")
                        if (oldFile.exists() && oldFile.name.startsWith("custom_app_bg")) oldFile.delete()
                    }
                }
                val localUriStr = Uri.fromFile(file).toString()
                appBgUri = localUriStr
                prefs.customAppBackgroundUri = localUriStr
                prefs.appBackgroundMode = 1
                appBgMode = 1
                AppBackgroundController.update(1, localUriStr, appBgDim)
                AppToast.makeText(context, "背景已设置", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppToast.makeText(context, "背景设置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (LocalAppBackgroundActive.current) Color.Transparent
                else MaterialTheme.colorScheme.background
            )
    ) {
    // 滚动联动折叠头部：state 提前声明供 topBar 与列表共用
    val settingsListState = rememberLazyListState()

    // 排行榜「陈列方式」按钮跳入：动画滚动到神回设置分区。
    // 神回 item 下标 = 前 14 个固定 item；成人源已固定启用，不再插入开关卡片。
    // ⚠️ 在神回分区之前增删设置项时，同步更新这里的固定计数。
    LaunchedEffect(focusGodSettings) {
        if (!focusGodSettings) return@LaunchedEffect
        val godIndex = 1 + 14
        settingsListState.animateScrollToItem(godIndex)
    }
    settingsListState.scrollTiltSource()
    val settingsHeaderCollapsedSource = rememberHeaderCollapsedSource(settingsListState)
    val backAction = onBack
    val settingsLeading: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        if (backAction != null) {
            {
                // Wenku 纸片箭头 + 三拍按下手势（图标名走集中映射表）。
                AppIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = backAction,
                    tint = MaterialTheme.colorScheme.onSurface,
                    glyphSize = 22.dp
                )
            }
        } else null
    Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabScreenHeader(
                    collapsedSource = settingsHeaderCollapsedSource,
                    modifier = Modifier.statusBarsPadding(),
                    title = "设置",
                    subtitle = "SETTINGS & PREFERENCES",
                    titleColor = glassTitleColor(),
                    leading = settingsLeading
                )
            }
        ) { padding ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
LazyColumn(
                state = settingsListState,
                modifier = Modifier
                .widthIn(max = AdaptiveSpec.pageContentMaxWidth)
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + extraBottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {


                if (onOpenThemePalette != null || onOpenCardTweaks != null) {
                    item(key = "appearance_heading") {
                        WenkuSectionHeader("01", "APPEARANCE", "外观与主题")
                    }
                    onOpenThemePalette?.let { open ->
                        item(key = "theme_palette") {
                            SettingsNavigationCard(
                                "主题配色方案", WenkuSymbol.Brightness,
                                modifier = Modifier.wenkuPagePart(1),
                                subtitle = "预览和调整主色调与强调色", onClick = open,
                            )
                        }
                    }
                    onOpenCardTweaks?.let { open ->
                        item(key = "card_appearance") {
                            SettingsNavigationCard(
                                "自定义卡片参数", WenkuSymbol.Layers,
                                modifier = Modifier.wenkuPagePart(2),
                                subtitle = "调整玻璃、圆角与按压反馈", onClick = open,
                            )
                        }
                    }
                }

                item(key = "discover_settings") {
                    SettingsSectionHeader("书库与发现")
                    SettingsNavigationCard(
                        title = "发现设置",
                        symbol = WenkuSymbol.Discover,
                        modifier = Modifier.wenkuPagePart(3),
                        subtitle = "浏览布局、推荐偏好与屏蔽管理",
                        onClick = { showDiscoverSettings = true }
                    )
                }

                // Section 1: Splash Poster
                item {
                    SettingsSectionHeader("开屏海报设置")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自定义开屏海报", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                Row {
                                    if (!splashPosterUri.isNullOrEmpty()) {
                                        PullOutTextButton(
                                            onClick = {
                                                splashPosterUri = null
                                                prefs.customSplashPosterUri = null
                                                AppToast.makeText(context, "已还原默认海报", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Text("清除")
                                        }
                                    }
                                    AppActionButton(
                                        text = "选择相册图片",
                                        onClick = { posterLauncher.launch("image/*") },
                                        variant = AppButtonVariant.Primary,
                                        buttonSize = AppButtonSize.Small
                                    )
                                }
                            }

                            if (!splashPosterUri.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = splashPosterUri,
                                        contentDescription = "海报预览",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("纯净模式", fontWeight = FontWeight.SemiBold)
                                AppSwitch(
                                    checked = splashPureMode,
                                    onCheckedChange = {
                                        splashPureMode = it
                                        prefs.splashPureMode = it
                                    }
                                )
                            }
                        }
                    }
                }

                // Section: 软件背景
                item {
                    SettingsSectionHeader("软件背景设置")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("软件背景", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            SegmentedPillSelector(
                                options = listOf(0 to "默认", 1 to "自定义"),
                                selected = appBgMode,
                                onSelect = { mode ->
                                    appBgMode = mode
                                    prefs.appBackgroundMode = mode
                                    AppBackgroundController.update(
                                        mode,
                                        if (mode == 1) appBgUri else null,
                                        if (mode == 1) appBgDim else 0
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (appBgMode == 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                                if (!appBgUri.isNullOrEmpty()) {
                                    // 预览模拟卡：文字颜色实时按卡片底色取对比色
                                    val previewCardColor = Color.White.copy(alpha = 0.92f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        // 实时预览：背景图 + 遮罩 + 模拟内容卡片，直观感受可读性
                                        AsyncImage(
                                            model = appBgUri,
                                            contentDescription = "背景预览",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = appBgDim / 100f))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .fillMaxWidth()
                                                .padding(20.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(previewCardColor)
                                                .padding(12.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    "预览",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = previewCardColor.onColor()
                                                )
                                                Text(
                                                    "背景遮罩 ${appBgDim}%",
                                                    fontSize = 11.sp,
                                                    color = previewCardColor.onColor().copy(alpha = 0.65f)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("背景遮罩强度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text("$appBgDim%", fontSize = 12.sp, color = MintPrimary, fontWeight = FontWeight.Bold)
                                    }
                                    // 原版 FluidSlider（基线样式零改动）；纵向让位由滑条内部方向仲裁承担
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                    FluidSlider(
                                        position = appBgDim / 50f,
                                        onPositionChange = {
                                            appBgDim = (it * 50).toInt().coerceIn(0, 50)
                                            prefs.appBackgroundDim = appBgDim
                                            if (appBgMode == 1 && !appBgUri.isNullOrEmpty()) {
                                                AppBackgroundController.update(1, appBgUri, appBgDim)
                                            }
                                        },
                                        barHeightDp = 32,
                                        bubbleText = "$appBgDim%",
                                        startText = "0%",
                                        // C4：原先左端 "0"、右端 "50"、气泡 "$appBgDim%"，
                                        // 单位与量纲混用；统一成 0%~50%
                                        endText = "50%",
                                        colorBar = MintPrimary,
                                        colorBubble = Color.White,
                                        colorBubbleText = MintPrimary,
                                        colorBarText = glassTitleColor()
                                    )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    AppActionButton(
                                        text = if (appBgUri.isNullOrEmpty()) "选择背景图片" else "更换图片",
                                        onClick = { bgLauncher.launch("image/*") },
                                        variant = AppButtonVariant.Primary,
                                        buttonSize = AppButtonSize.Small,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!appBgUri.isNullOrEmpty()) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        AppActionButton(
                                            text = "恢复默认",
                                            onClick = {
                                                appBgUri = null
                                                prefs.customAppBackgroundUri = null
                                                prefs.appBackgroundMode = 0
                                                appBgMode = 0
                                                AppBackgroundController.update(0, null, appBgDim)
                                                AppToast.makeText(context, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                                            },
                                            variant = AppButtonVariant.Secondary,
                                            buttonSize = AppButtonSize.Small,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: Orientation Lock
                item {
                    SettingsSectionHeader("屏幕方向")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ScreenRotation, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("方向锁定", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            SegmentedPillSelector(
                                options = listOf(0 to "跟随系统", 1 to "锁定竖屏", 2 to "锁定横屏"),
                                selected = orientationLockVal,
                                onSelect = onOrientationLockChange
                            )
                        }
                    }
                }

                // Section 2.5: 画面与性能（渲染画质四档）
                item {
                    SettingsSectionHeader("画面与性能")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MintPrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("渲染画质", fontWeight = FontWeight.SemiBold)
                                    Text("极致在实时玻璃上增加边缘折射", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            SegmentedPillSelector(
                                options = listOf(
                                    -1 to "极简",
                                    0 to "流畅",
                                    1 to "均衡",
                                    2 to "高",
                                    3 to "极致"
                                ),
                                selected = renderQualityVal.coerceIn(-1, 3),
                                onSelect = onRenderQualityChange
                            )
                        }
                    }
                }

                // Section 2.55: 触控反馈（触觉）总开关
                // 此前 LocalHapticsEnabled 在 MainActivity 里恒为 true，设置页根本没有这个入口：
                // 马达坏了 / 开会不想震动 / 省电的用户只能去系统层关触感，或者改代码。
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Vibration, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("触控反馈", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                AppSwitch(
                                    checked = hapticsEnabledVal,
                                    onCheckedChange = { onHapticsChange(it) }
                                )
                            }
                        }
                    }
                }

                // Section 3: Page Turn Effect
                item {
                    SettingsSectionHeader("翻页效果")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PageTurnType.entries.forEach { turnType ->
                                PageTurnSelectorRow(
                                    title = turnType.title,
                                    description = "",
                                    type = turnType,
                                    selected = pageTurnMode == turnType.id,
                                    onClick = {
                                        pageTurnMode = turnType.id
                                        prefs.pageTurnMode = turnType.id
                                    }
                                )
                            }
                        }
                    }
                }

                // 神回（GodMoment）设置分组
                // ⚠️ key 固定为 "god_settings"；focusGodSettings 滚动定位依赖它排查。
                if (godSettings != null) {
                    item(key = "god_settings") {
                        com.example.god.GodSettingsSection(
                            store = godSettings,
                            onOpenRanking = onOpenGodRanking,
                        )
                    }
                }

                // Section 5: Health & Eye Protection
                item {
                    SettingsSectionHeader("护眼与提醒")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("定时休息", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            SegmentedPillSelector(
                                options = listOf(0 to "关", 15 to "15分", 30 to "30分", 45 to "45分", 60 to "60分"),
                                selected = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) restReminderMinutes else -1,
                                onSelect = {
                                    restReminderMinutes = it
                                    prefs.restReminderMinutes = it
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) {
                                        "自定义提醒时间"
                                    } else {
                                        "当前自定义：$restReminderMinutes 分钟"
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                AppActionButton(
                                    text = "自定义",
                                    onClick = { showCustomMinutes = true },
                                    variant = AppButtonVariant.Secondary,
                                    buttonSize = AppButtonSize.Small
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Nightlight, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("夜间模式", fontWeight = FontWeight.SemiBold)
                                }
                                AppSwitch(
                                    checked = autoNightMode,
                                    onCheckedChange = {
                                        autoNightMode = it
                                        onAutoNightModeChange(it)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.RemoveRedEye, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("护眼滤镜", fontWeight = FontWeight.SemiBold)
                                }
                                AppSwitch(
                                    checked = blueLightFilter,
                                    onCheckedChange = {
                                        blueLightFilter = it
                                        onBlueLightFilterChange(it)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // 在线漫画预加载策略：开启后移动网络不提前下载后面的页（省流量）
                            var preloadWifiOnly by remember { mutableStateOf(prefs.preloadWifiOnly) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("仅 Wi-Fi 预加载漫画", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                AppSwitch(
                                    checked = preloadWifiOnly,
                                    onCheckedChange = {
                                        preloadWifiOnly = it
                                        prefs.preloadWifiOnly = it
                                    }
                                )
                            }

                            if (blueLightFilter) {
                                Spacer(modifier = Modifier.height(10.dp))
                                // 静止态也可读：当前强度常驻显示（与遮罩强度行同款式）
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("当前强度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${(blueLightAlpha * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        color = MintPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(modifier = Modifier.fillMaxWidth()) {
                                FluidSlider(
                                    position = blueLightAlpha,
                                    onPositionChange = {
                                        blueLightAlpha = it
                                        onBlueLightAlphaChange(it)
                                    },
                                    bubbleText = "${(blueLightAlpha * 100).toInt()}%",
                                    startText = "0",
                                    endText = "100",
                                    colorBar = MintPrimary,
                                    colorBubble = Color.White,
                                    colorBubbleText = MintPrimary,
                                    colorBarText = glassTitleColor(),
                                    barHeightDp = 28
                                )
                                }
                            }
                        }
                    }
                }

                // Section 5.5: 隐私与安全（第七轮第 6.4 条）
                item {
                    SettingsSectionHeader("隐私与安全")
                }
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MintPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("隐私模式", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    if (privacyModeEnabled) "已开启 · 分类密码保护与无痕阅读"
                                    else "设置 6 位密码，为分类加锁",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 第十一轮第 4 条：隐私模式开启/关闭的行内过渡动画——
                            // 尾部控件（开关 ↔ "管理"入口）与副标题随状态淡入淡出切换，不再硬跳变
                            AnimatedContent(
                                targetState = privacyModeEnabled,
                                transitionSpec = {
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                                        .togetherWith(fadeOut(tween(160)))
                                },
                                label = "privacyRowSwitch"
                            ) { enabled ->
                                if (!enabled) {
                                // 首次开启：先走 6 位密码设置流程（输入 + 二次确认）
                                com.example.ui.components.AppSwitch(
                                    // 2026-09-21 修复：原为硬编码 false（点了开关不动、只弹窗，
                                    // 用户以为没点上）。改为反映点击意图，取消时再回弹。
                                    checked = privacySwitchPending,
                                    onCheckedChange = { wantOn ->
                                        if (wantOn) {
                                            privacySwitchPending = true
                                            showPinSetup = true
                                        } else {
                                            privacySwitchPending = false
                                        }
                                    }
                                )
                            } else {
                                // 已开启：验证密码后进入隐私管理悬浮窗口
                                WenkuTextButton(
                                    "管理",
                                    onClick = { showManageVerify = true },
                                    compact = true,
                                    wenkuSymbol = WenkuSymbol.Shield,
                                )
                            }
                            }
                        }
                    }
                }

                // Section 6: Cache Management
                item {
                    SettingsSectionHeader("存储管理")
                }
                item {
                    onOpenCacheManager?.let { open ->
                        SettingsNavigationCard(
                            title = "缓存管理",
                            subtitle = "查看存储占用与离线文件",
                            symbol = WenkuSymbol.Folder,
                            onClick = open
                        )
                    }
                }

                item(key="user_backup") {
                    SettingsSectionHeader("数据管理")
                    SettingsNavigationCard(
                        "设备互传与备份", WenkuSymbol.Sync,
                        modifier = Modifier.testTag("device_transfer_entry"),
                        onClick = { com.example.transfer.TransferManager.get(context).show() }
                    )
                }

                // Section 7: Library Help & Manual
                item {
                    SettingsSectionHeader("帮助手册")
                }

                item {
                    SettingsNavigationCard(
                        "书库使用手册", WenkuSymbol.Help,
                        modifier = Modifier.testTag("library_help_entry_card"),
                        onClick = { showHelpBottomSheet = true }
                    )
                }

                // Section 8: About
                item { SettingsSectionHeader("关于") }
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth().testTag("software-info"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            val versionName = remember {
                                try {
                                    context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: BuildConfig.VERSION_NAME
                                } catch (_: Exception) { BuildConfig.VERSION_NAME }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoStories, null, tint = MintPrimary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Ciallo Reader", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text("版本 $versionName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(8.dp))
                            WenkuTextButton(
                                text = if (updateChecking) "检查中…" else "检查更新",
                                enabled = !updateChecking,
                                compact = true,
                                wenkuSymbol = WenkuSymbol.Refresh,
                                onClick = {
                                    updateChecking = true
                                    updateMessage = null
                                    scope.launch {
                                        try {
                                            val result = updateChecker.check(versionName)
                                            updateMessage = result.message
                                            result.release?.let { appUpdate.offer(it) }
                                        } finally { updateChecking = false }
                                    }
                                }
                            )
                            }
                            updateMessage?.let { message ->
                                Spacer(Modifier.height(10.dp))
                                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val updateUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            if (updateMessage != null && appUpdateState.release == null && !updateMessage!!.contains("最新版本")) {
                                WenkuTextButton("GitHub 发布页", onClick = {
                                    runCatching { updateUriHandler.openUri(com.example.data.UPDATE_REPOSITORY + "/releases/latest") }.onFailure {
                                        AppToast.makeText(context, "无法打开发布页，请检查浏览器。", Toast.LENGTH_SHORT).show()
                                    }
                                }, compact = true, wenkuSymbol = WenkuSymbol.External)
                            }
                            if (appUpdateState.release != null) {
                                WenkuTextButton(
                                    if (appUpdateState.transferring) "查看下载进度" else if (appUpdateState.hasPackage) "继续安装更新" else "查看更新",
                                    onClick = appUpdate::show,
                                    compact = true,
                                    wenkuSymbol = if (appUpdateState.hasPackage) WenkuSymbol.Upload else WenkuSymbol.Download,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(Modifier.height(12.dp))
                            com.example.ui.components.StartupDiagnosticsExportButton(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                WenkuTextButton("更新目录", onClick = { showReleaseHistory = true },
                                    modifier = Modifier.weight(1f), compact = true, wenkuSymbol = WenkuSymbol.Document)
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                WenkuTextButton("GitHub", onClick = {
                                    runCatching { uriHandler.openUri(com.example.data.UPDATE_REPOSITORY) }
                                        .onFailure { AppToast.makeText(context, "无法打开 GitHub", Toast.LENGTH_SHORT).show() }
                                }, modifier = Modifier.weight(1f), compact = true, wenkuSymbol = WenkuSymbol.External)
                            }
                        }
                    }
                }
            }
            }
        }

    if (showHelpBottomSheet) {
            LibraryHelpBottomSheet(
                onDismissRequest = { showHelpBottomSheet = false },
                onOpenSourceManager = { onOpenSourceManager?.invoke() }
            )
        }
    }

    if (showCustomMinutes) {
        CustomMinutesDialog(
            current = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) 15 else restReminderMinutes,
            onConfirm = {
                restReminderMinutes = it
                prefs.restReminderMinutes = it
                showCustomMinutes = false
            },
            onDismiss = { showCustomMinutes = false }
        )
    }

    if (showAddCategoryDialog) {
        PullOutAlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("新建分类", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newCategoryText,
                    onValueChange = { newCategoryText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                DialogLiquidGlass(fillMaxSize = false) {
                    AppActionButton(
                        text = "新建",
                        onClick = {
                            if (newCategoryText.isNotBlank()) {
                                onAddCategory(newCategoryText.trim())
                                newCategoryText = ""
                                showAddCategoryDialog = false
                                AppToast.makeText(context, "新建成功", Toast.LENGTH_SHORT).show()
                            }
                        },
                        variant = AppButtonVariant.Primary,
                        buttonSize = AppButtonSize.Small
                    )
                }
            },
            dismissButton = {
                PullOutTextButton(onClick = { showAddCategoryDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    /* ── 隐私模式窗口组（第七轮第 6.4 条）：设置 PIN / 验证进入 / 管理悬浮窗 ══ */
    // 首次开启：6 位 PIN 设置流程（输入 + 二次确认），完成后直接跳转隐私管理窗口
    if (showPinSetup) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.SETUP,
            onPinSet = onEnablePrivacyMode,
            onAccepted = { showManageWindow = true },
            onDismiss = {
                // 取消设置：开关回弹到关闭
                showPinSetup = false
                privacySwitchPending = false
            },
        )
    }

    // 已开启后进入管理窗口前的 PIN 验证
    if (showManageVerify) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.VERIFY,
            onPinVerified = onVerifyPrivacyPin,
            onAccepted = { showManageWindow = true },
            onDismiss = { showManageVerify = false },
        )
    }

    // 修改密码：旧密码验证（记录）→ 新密码
    if (showChangePin) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.CHANGE_OLD,
            onPinChanged = onChangePrivacyPin,
            onPinVerified = onVerifyPrivacyPin,
            onAccepted = { AppToast.makeText(context, "密码已更新", Toast.LENGTH_SHORT).show() },
            onDismiss = { showChangePin = false },
        )
    }

    // 隐私管理悬浮毛玻璃窗口（6.4.3：分类密码保护总开关管理）
    if (showManageWindow) {
        com.example.ui.privacy.PrivacyManageOverlay(
            categories = categories,
            privacyModeEnabled = privacyModeEnabled,
            onToggleProtected = { cat, protected -> onToggleCategoryProtected(cat, protected) },
            onToggleIncognito = { enabled -> onSetIncognitoBrowsing(enabled) },
            incognitoBrowsingEnabled = incognitoBrowsingEnabled,
            favoritesProtected = favoritesProtected,
            onToggleFavoritesProtected = { onSetFavoritesProtected(it) },
            onChangePin = { showChangePin = true },
            onDisablePrivacy = { showDisableVerify = true },
            requestDismiss = closePrivacyManagement,
            onDismiss = {
                showManageWindow = false
                if (closePrivacyManagement) {
                    closePrivacyManagement = false
                    AppToast.makeText(context, "隐私模式已关闭", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // 关闭隐私模式的 PIN 验证
    if (showDisableVerify) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.VERIFY,
            onPinVerified = onDisablePrivacyMode,
            onAccepted = { closePrivacyManagement = true },
            onDismiss = { showDisableVerify = false },
        )
    }
}

/**
 * 设置页分区标题（统一 14sp/Bold/adaptiveTitleColor）。
 * 抽取自此前散落 8 处的相同 Text 模式；其中两处原为 16sp，已按多数派统一为 14sp。
 */
@Composable
private fun SettingsNavigationCard(
    title: String,
    symbol: WenkuSymbol,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            WenkuIcon(symbol, size = 24.dp, tint = colors.primary, entrance = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            WenkuIcon(WenkuSymbol.Forward, size = 18.dp, tint = colors.onSurfaceVariant.copy(alpha = .6f), entrance = false)
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    // 2026-09-21：改为 iOS 分组标题规格 —— 13sp 半粗、次级色、轻微正字距。
    // 原来是 14sp Bold + 主色，字号比行标题还大、颜色还重，和下方内容抢视觉重心，
    // 一屏十几个分组时整页“全是标题”，层级糊成一片。
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        color = adaptiveTitleColor().copy(alpha = 0.62f)
    )
}
