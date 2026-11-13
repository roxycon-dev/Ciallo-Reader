package com.example.ui

import com.example.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.mascot.MascotSpriteSheet
import com.example.ui.wenku.WenkuContentPanel
import com.example.ui.wenku.WenkuIconButton
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuPlaneTabSwitcher
import com.example.ui.wenku.WenkuSectionHeader
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.wenkuColors
import com.example.ui.wenku.wenkuPagePart

/**
 * 引导页数据。展示文案在**读取时**翻译：这个 list 是顶层 `val`，类加载期就构造好了，
 * 在构造参数里调 `tr()` 会把语言永久冻住。
 */
private data class OnboardingPage(
    private val rawTitle: String,
    private val rawDescription: String,
    val imageRes: Int,
    private val rawLabel: String,
    val code: String,
) {
    val title: String get() = com.example.i18n.tr(rawTitle)
    val description: String get() = com.example.i18n.tr(rawDescription)
    val label: String get() = com.example.i18n.tr(rawLabel)
}

private val onboardingPages = listOf(
    OnboardingPage(
        "欢迎来到 Ciallo Reader",
        "把喜欢的小说和漫画收进书架，按自己的节奏阅读。",
        MascotSpriteSheet.idleDrawable,
        "阅读空间",
        "WELCOME",
    ),
    OnboardingPage(
        "书库与自定义书源",
        "通过书库搜索在线作品，也可以导入自定义书源，或将本地文件加入书架。",
        MascotSpriteSheet.moveDrawable,
        "找书与导入",
        "LIBRARY",
    ),
    OnboardingPage(
        "按你的方式阅读",
        "选择仿真翻页或连续滚动，调整配色、夜间模式和护眼设置。",
        MascotSpriteSheet.readingDrawable,
        "阅读外观",
        "READING",
    ),
    OnboardingPage(
        "和 Roxy 一起阅读",
        "添加书签或整理书架时，Roxy 会用不同的动作回应你。",
        MascotSpriteSheet.happyDrawable,
        "书架伙伴",
        "ROXY",
    ),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = wenkuColors()
    val reduceMotion = rememberWenkuAccessibility().reduceMotion

    fun finish() {
        if (finishing) return
        finishing = true
        com.example.data.StartupDiagnostics.breadcrumb(context, "onboarding:finish")
        onFinished()
    }

    BackHandler(enabled = page > 0 && !finishing) { page-- }
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .testTag("onboarding_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 1060.dp).fillMaxSize().padding(horizontal = 22.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                if (page > 0) {
                    WenkuIconButton(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "上一步",
                        onClick = { if (!finishing && page > 0) page-- },
                        modifier = Modifier.align(Alignment.CenterStart),
                        enabled = !finishing,
                        wenkuSymbol = WenkuSymbol.Back,
                    )
                }
                WenkuTextButton(
                    "跳过",
                    ::finish,
                    Modifier.align(Alignment.CenterEnd).testTag("onboarding_skip_button"),
                    compact = true,
                    enabled = !finishing,
                    wenkuSymbol = WenkuSymbol.Forward,
                )
            }

            WenkuPlaneTabSwitcher(
                targetState = page,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .testTag("onboarding_content_container").wenkuPagePart(1),
                direction = { from, to -> to - from },
            ) { index ->
                OnboardingStory(onboardingPages[index], index)
            }

            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.semantics { contentDescription = com.example.i18n.stepCounter(page + 1, onboardingPages.size) },
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    onboardingPages.indices.forEach { index ->
                        val width by animateFloatAsState(
                            if (index == page) 22f else 7f,
                            if (reduceMotion) snap() else WenkuMotion.settleSpec(),
                            label = "onboardingStep",
                        )
                        Box(Modifier.size(width.dp, 5.dp).background(
                            if (index == page) MaterialTheme.colorScheme.primary else colors.outline,
                            CircleShape,
                        ))
                    }
                }
                val last = page == onboardingPages.lastIndex
                WenkuTextButton(
                    if (last) tr("立即开启阅读之旅") else tr("下一步"),
                    onClick = { if (!finishing) { if (last) finish() else page++ } },
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_action_button"),
                    selected = true,
                    enabled = !finishing,
                    wenkuSymbol = if (last) WenkuSymbol.Book else WenkuSymbol.Forward,
                )
            }
        }
    }
}

@Composable
private fun OnboardingStory(page: OnboardingPage, index: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val imageSize = (viewportHeight * .40f).coerceIn(96.dp, 190.dp)
        val wide = maxWidth >= 760.dp
        val art: @Composable () -> Unit = {
            WenkuContentPanel(
                Modifier.fillMaxWidth().wenkuPagePart(1).testTag("onboarding_illustration_card_$index"),
            ) {
                Image(
                    painterResource(page.imageRes),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(imageSize),
                )
            }
        }
        val description: @Composable () -> Unit = {
            Text(
                page.title,
                modifier = Modifier.fillMaxWidth().wenkuPagePart(2),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center,
            )
            Text(
                page.description,
                modifier = Modifier.fillMaxWidth().wenkuPagePart(3),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 2.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { art() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WenkuSectionHeader(
                "0${index + 1}", page.code, page.label,
            )
                        description()
                    }
                }
            } else {
            WenkuSectionHeader(
                "0${index + 1}", page.code, page.label,
            )
                art()
                description()
            }
        }
    }
}
