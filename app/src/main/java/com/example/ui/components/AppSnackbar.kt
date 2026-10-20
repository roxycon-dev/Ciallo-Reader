package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import me.trishiraj.shadowglow.consistentShadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary

/**
 * Snackbar 的**语义分级**。
 *
 * ⚠️ 翻车史：SnackbarHost 曾经一律把消息渲染成 [AppErrorSnackbar]（红色错误卡 +
 * 固定标题「操作出错」）。结果「已移动 2 本到『悬疑』」这种**成功 + 撤销**提示也顶着
 * 一个红色错误框弹出来，用户原话：「操作总是出错，问题是操作根本没有出错」。
 * 所以每条 snackbar 必须自带语义，宿主再按语义挑皮肤。
 */
enum class AppSnackKind { ERROR, TOAST }

/**
 * 带语义的 SnackbarVisuals：message 保持**纯净**（「复制日志」复制到的就是它，
 * 不能为了分流往文本里塞前缀），语义走独立的 [kind] 字段。
 */
class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
    val kind: AppSnackKind,
) : SnackbarVisuals

/** 统一的 snackbar 入口：默认按「普通提示」走，错误才显式声明。 */
suspend fun SnackbarHostState.showAppSnackbar(
    message: String,
    kind: AppSnackKind = AppSnackKind.TOAST,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult = showSnackbar(
    AppSnackbarVisuals(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = false,
        duration = duration,
        kind = kind,
    )
)

/**
 * 普通 / 成功的提示卡（取代原来"所有提示都套红色错误框"的做法）。
 *
 * 与 [AppErrorSnackbar] 刻意拉开差距：主色是薄荷而不是 error 红，左侧是勾选而不是
 * 感叹号，**没有「操作出错」这个标题**——成功提示不该有任何"出事了"的暗示。
 */
@Composable
fun AppToastSnackbar(
    message: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onDismissClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .consistentShadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
            )
            .testTag("app_toast_snackbar"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MintPrimary.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MintPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = message,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (actionLabel != null && onActionClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                PullOutTextButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = 12.sp,
                        color = MintPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (onDismissClick != null) {
                PullOutIconButton(onClick = onDismissClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
