package com.example.ui.favorite

import com.example.ui.components.PullOutTextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.favorite.ComicReadingLogic
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.GradientActionButton
import com.example.ui.theme.MintPrimary

/**
 * 漫画主页面的固定底部操作栏（设计稿 §1.1 的拇指位方案）：
 *
 * 层级：**继续阅读（主按钮，右） > 喜欢（次，图标+文字，左） > 下载**。
 * 选「底部固定操作栏」而不是"封面信息区下方一排"的原因：
 * 1. 章节列表是长列表，信息区会随滚动离屏，主操作跟着消失；
 * 2. 底部栏是拇指自然落点，与列表滚动不冲突；
 * 3. 顶部栏在滚动后另出小心形（见 [FavoriteHeartIcon]），保证任何位置都能一键喜欢。
 */
@Composable
fun ComicBottomActionBar(
    favorite: Boolean,
    favoriteEnabled: Boolean,
    continueLabel: String,
    isUpToDate: Boolean,
    onToggleFavorite: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onDownload: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FavoriteHeartButton(
            favorite = favorite,
            enabled = favoriteEnabled,
            showLabel = false,
            onToggle = onToggleFavorite,
            onLongPress = onFavoriteLongPress,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (isUpToDate) {
            AppActionButton(
                text = continueLabel,
                onClick = onContinue,
                variant = AppButtonVariant.Secondary,
                buttonSize = AppButtonSize.Medium,
                modifier = Modifier.weight(1f),
            )
            PullOutTextButton(onClick = onContinue) {
                Text("从头重读", fontSize = 12.sp, color = MintPrimary, fontWeight = FontWeight.SemiBold)
            }
        } else {
            GradientActionButton(
                text = continueLabel,
                onClick = onContinue,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        AppActionButton(
            text = "下载",
            onClick = onDownload,
            variant = AppButtonVariant.Secondary,
            buttonSize = AppButtonSize.Small,
            icon = Icons.Filled.Download,
        )
    }
}

/** 来源失效提示条：保留缓存信息与已读状态，提供「重试」「换源」。 */
@Composable
fun SourceUnavailableBanner(
    message: String,
    onRetry: () -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        PullOutTextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
        PullOutTextButton(onClick = onChangeSource) { Text("换源", fontSize = 12.sp) }
    }
}

/** 「继续阅读」文案（纯函数，供单测与 UI 共用）。 */
fun continueReadingLabel(
    target: ComicReadingLogic.ContinueTarget,
    chapters: List<com.example.source.ComicChapter>,
): String = ComicReadingLogic.continueLabel(target, chapters)
