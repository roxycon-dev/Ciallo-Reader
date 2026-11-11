package com.example.ui.shelf

import com.example.i18n.tr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticClick
import androidx.compose.ui.semantics.onLongClick as semanticLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.feedback.HapticKind
import com.example.ui.feedback.LocalHapticsEnabled
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.rememberAppHaptics
import com.example.ui.theme.MintPrimary

/**
 * 书架 / 我喜欢的 共用的分类胶囊。
 *
 * 细节（都是"手指能感觉到"的那一类）：
 * - 按下瞬间缩到 0.94（硬弹簧，跟手），松手弹回 1.0（带一点过冲）；
 * - 切换选中时底色、描边、字重、字色一起过渡，不是硬切；
 * - 点击给一颗 selection 级轻震动（可随系统触觉开关关闭）；
 * - 数量角标跟着选中态换底色，避免"选中后角标看不清"；
 * - 「减少动态效果」开启时全部退化为 0 时长的直接切换。
 */
@Composable
fun CategoryPill(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    locked: Boolean = false,
    accent: Color = MintPrimary,
    onLongClick: (() -> Unit)? = null,
    /**
     * 拖拽悬停在本胶囊上（"松手就放进这里"）。
     *
     * 放置目标就是**页面里这一排分类胶囊本身**，不再另起一个底部 Dock ——
     * 用户明确要求：拖到对应分类就是拖到「我的书架 / 我喜欢的」下面这几个按钮上。
     * 高亮用「填充主色 + 放大 1.08」表达，且**不改文案**，
     * 否则 FlowRow 里的兄弟胶囊会跟着位移甚至换行，手指底下的目标就跑了。
     */
    dropHighlight: Boolean = false,
) {
    val haptics = rememberAppHaptics()
    val hapticEnabled = LocalHapticsEnabled.current
    val reduceMotion = LocalReduceMotion.current

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else if (dropHighlight) 1.08f else 1f,
        animationSpec = if (reduceMotion) {
            tween(0)
        } else {
            spring(dampingRatio = 0.60f, stiffness = 500f, visibilityThreshold = 0.001f)
        },
        label = "chip_press",
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected || dropHighlight) 1f else 0.06f,
        animationSpec = tween(if (reduceMotion) 0 else 170),
        label = "chip_bg",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (dropHighlight) 1f else if (selected) 0f else 0.10f,
        animationSpec = tween(if (reduceMotion) 0 else 170),
        label = "chip_border",
    )
    val contentColor = if (selected || dropHighlight) Color.White
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    Row(
        modifier = modifier
            .height(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected || dropHighlight) accent.copy(alpha = bgAlpha)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = bgAlpha)
            )
            .border(
                if (dropHighlight) 1.5.dp else 0.5.dp,
                if (dropHighlight) accent.copy(alpha = borderAlpha)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = borderAlpha),
                RoundedCornerShape(16.dp),
            )
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = {
                        if (hapticEnabled) haptics.perform(HapticKind.SELECTION)
                        onClick()
                    },
                    onLongPress = { onLongClick?.invoke() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = if (selected) com.example.i18n.categorySelected(name) else com.example.i18n.categoryName(name)
                semanticClick {
                    if (hapticEnabled) haptics.perform(HapticKind.SELECTION)
                    onClick()
                    true
                }
                onLongClick?.let { action -> semanticLongClick { action(); true } }
            }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = name,
            fontSize = 12.5.sp,
            fontWeight = if (selected || dropHighlight) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
        if (locked) {
            Spacer(modifier = Modifier.width(5.dp))
            Icon(
                Icons.Filled.Lock,
                contentDescription = tr("已加密"),
                tint = contentColor.copy(alpha = if (selected) 0.9f else 0.45f),
                modifier = Modifier.size(12.dp),
            )
        }
        if (count != null && count >= 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.24f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = count.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor.copy(alpha = if (selected) 1f else 0.62f),
                )
            }
        }
    }
}

/**
 * 通用「点按反馈」修饰符：按下缩到 [scale]（硬弹簧跟手），松手弹回并触发点击。
 * 苹果几乎所有可点元素都有这一下，缺了它整个界面会显得"没有实体感"。
 */
fun Modifier.pressScale(
    scale: Float = 0.96f,
    enabled: Boolean = true,
    onTap: () -> Unit,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAppHaptics()
    val hapticEnabled = LocalHapticsEnabled.current
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (reduceMotion) {
            tween(0)
        } else {
            spring(dampingRatio = 0.60f, stiffness = 500f, visibilityThreshold = 0.001f)
        },
        label = "press_scale",
    )
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(onTap, enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                    }
                },
                onTap = {
                    if (hapticEnabled) haptics.perform(HapticKind.SELECTION)
                    onTap()
                },
            )
        }
}

/** 「＋新建分类」胶囊：虚线描边 + 按下缩放，明确区别于真实分类。 */
@Composable
fun AddCategoryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "新建",
    accent: Color = MintPrimary,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAppHaptics()
    val hapticEnabled = LocalHapticsEnabled.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (reduceMotion) {
            tween(0)
        } else {
            spring(dampingRatio = 0.60f, stiffness = 500f, visibilityThreshold = 0.001f)
        },
        label = "add_chip_press",
    )
    val dashOn = 6.dp
    val dashOff = 5.dp
    val dashColor = accent.copy(alpha = 0.55f)
    val dashWidth = 1.2.dp

    Row(
        modifier = modifier
            .height(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    style = Stroke(
                        width = dashWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(dashOn.toPx(), dashOff.toPx()),
                            0f,
                        ),
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
            }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = {
                        if (hapticEnabled) haptics.perform(HapticKind.SELECTION)
                        onClick()
                    },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
        )
    }
}
