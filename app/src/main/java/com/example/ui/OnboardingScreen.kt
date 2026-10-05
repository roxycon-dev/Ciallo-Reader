package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.mascot.MascotSpriteSheet
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppButtonVariant
import com.example.ui.theme.MintPrimary
import me.trishiraj.shadowglow.consistentShadow

data class OnboardingPage(
    val title: String,
    val description: String,
    val imageRes: Int,
    val badgeText: String
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = remember {
        listOf(
            OnboardingPage(
    title = "欢迎来到 Ciallo Reader",
                description = "这是一款专为阅读爱好者打造的沉浸式小说与网络文学阅读器。高颜值、极速、无广告，带给您最纯粹的阅读享受。",
                imageRes = MascotSpriteSheet.idleDrawable,
                badgeText = "全新体验"
            ),
            OnboardingPage(
                title = "海量书库，源于自由",
                description = "支持自定义书源导入、网络书源搜索、JSON标准格式配置，更无缝集成 Z-Library 等云端书库，让好书无处遁形。",
                imageRes = MascotSpriteSheet.moveDrawable,
                badgeText = "强大书源"
            ),
            OnboardingPage(
                title = "极致交互，沉浸阅读",
                description = "搭载 3D 仿真翻页 (PageCurl)、平滑覆盖滚动等多种翻页效果，支持防蓝光护眼模式与自动夜间主题，守护您的双眼。",
                imageRes = MascotSpriteSheet.readingDrawable,
                badgeText = "个性定制"
            ),
            OnboardingPage(
                title = "Roxy 灵动吉祥物",
                description = "Roxy 将在您的阅读旅程中时刻陪伴！当您添加书签、归档或删除书籍时，Roxy 都会用俏皮的肢体动画给您最生动的反馈。",
                imageRes = MascotSpriteSheet.happyDrawable,
                badgeText = "趣味交互"
            )
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("onboarding_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            // "Skip" button on top-right
            TextButton(
                onClick = onFinished,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .testTag("onboarding_skip_button")
            ) {
                Text(
                    text = "跳过",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Main Content Area with slide transitions
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(animationSpec = tween(300)) { width -> width } + fadeIn() with
                                slideOutHorizontally(animationSpec = tween(300)) { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally(animationSpec = tween(300)) { width -> -width } + fadeIn() with
                                slideOutHorizontally(animationSpec = tween(300)) { width -> width } + fadeOut()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .testTag("onboarding_content_container")
            ) { pageIndex ->
                val page = pages[pageIndex]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MintPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = page.badgeText,
                            color = MintPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Mascot illustration Card
                    Card(
                        modifier = Modifier
                            .size(200.dp)
                            .testTag("onboarding_illustration_card_${pageIndex}")
                            .consistentShadow(4.dp, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = page.imageRes),
                                contentDescription = "Onboarding illustration",
                                modifier = Modifier
                                    .size(140.dp)
                                    .padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Title
                    Text(
                        text = page.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Description
                    Text(
                        text = page.description,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Bottom controls: Indicators and Action Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    pages.forEachIndexed { index, _ ->
                        val isSelected = index == currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(width = if (isSelected) 18.dp else 8.dp, height = 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MintPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                // Action Button
                AppActionButton(
                    text = if (currentPage == pages.lastIndex) "立即开启阅读之旅" else "下一步",
                    onClick = {
                        if (currentPage < pages.lastIndex) {
                            currentPage++
                        } else {
                            onFinished()
                        }
                    },
                    variant = AppButtonVariant.Primary,
                    buttonSize = AppButtonSize.Large,
                    fullWidth = true,
                    icon = if (currentPage < pages.lastIndex) {
                        Icons.AutoMirrored.Filled.ArrowForward
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("onboarding_action_button")
                )
            }
        }
    }
}
