/*
 * Color State Morph —— 完整复制自 skydoves/compose-animations（Apache-2.0）
 * https://github.com/skydoves/compose-animations/blob/main/app/src/main/kotlin/com/skydoves/hotreloadanimations/animations/AnimationExample4.kt
 * 尺寸 / 旋转 / 圆角 / 颜色 四项全部用同一组弹簧参数动画，无省略。
 */
package com.example.ui.components


import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.clickableWithFeedback
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Color State Morph：原样复刻 AnimationExample4 的 FAB 形变——
 * 尺寸 74→196dp、旋转 0→135°、圆角 →24dp、颜色 primary→secondary，
 * 弹簧 DampingRatioMediumBouncy + StiffnessMediumLow。
 */
@Composable
fun ColorStateMorph(
    collapsedColor: Color = MaterialTheme.colorScheme.primary,
    expandedColor: Color = MaterialTheme.colorScheme.secondary,
    label: String = "+"
) {
  val springStiffness = Spring.StiffnessMediumLow // try Spring.StiffnessLow / StiffnessHigh
  val springDamping = Spring.DampingRatioMediumBouncy // try NoBouncy / HighBouncy

  val collapsedSize = 74.dp // idle FAB size
  val expandedSize = 196.dp // morphed FAB size
  val collapsedRotation = 0f // idle rotation in degrees
  val expandedRotation = 135f // morphed rotation (try 45f / 180f / 360f)
  val expandedCornerDp = 24.dp // morphed corner radius

  var morphed by remember { mutableStateOf(false) }

  val size by animateDpAsState(
    targetValue = if (morphed) expandedSize else collapsedSize,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "size",
  )
  val rotation by animateFloatAsState(
    targetValue = if (morphed) expandedRotation else collapsedRotation,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "rotation",
  )
  val cornerDp by animateDpAsState(
    targetValue = if (morphed) expandedCornerDp else collapsedSize / 2,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "corner",
  )
  val color by animateColorAsState(
    targetValue = if (morphed) expandedColor else collapsedColor,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "color",
  )

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(240.dp),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier = Modifier
          .size(size)
          .rotate(rotation)
          .clip(RoundedCornerShape(cornerDp))
          .background(color = color, shape = RoundedCornerShape(cornerDp))
          .pullOutClickable { morphed = !morphed },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          color = Color.White,
          fontSize = 32.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/**
 * 主题色选择器用的 Color State Morph：把 Example4 的
 * 尺寸 / 旋转 / 圆角 / 颜色 四项形变完整保留（缩放到色块尺寸），
 * 并叠加勾选图标的弹簧显现。
 */
@Composable
fun ColorMorphSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp
) {
  val springStiffness = Spring.StiffnessMediumLow
  val springDamping = Spring.DampingRatioMediumBouncy

  val collapsedSize = size
  val expandedSize = size + 14.dp
  val collapsedRotation = 0f
  val expandedRotation = 90f
  val expandedCornerDp = 14.dp

  val morphSize by animateDpAsState(
    targetValue = if (selected) expandedSize else collapsedSize,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "swatchSize",
  )
  val rotation by animateFloatAsState(
    targetValue = if (selected) expandedRotation else collapsedRotation,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "swatchRotation",
  )
  val cornerDp by animateDpAsState(
    targetValue = if (selected) expandedCornerDp else collapsedSize / 2,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "swatchCorner",
  )
  val morphColor by animateColorAsState(
    targetValue = if (selected) color else color.copy(alpha = 0.72f),
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "swatchColor",
  )
  val checkAlpha by animateFloatAsState(
    targetValue = if (selected) 1f else 0f,
    animationSpec = spring(dampingRatio = springDamping, stiffness = springStiffness),
    label = "swatchCheck",
  )

  Box(
    modifier = modifier
      .size(morphSize)
      .clip(RoundedCornerShape(cornerDp))
      // 2026-09-21：原来是默认 clickable —— Material 水波纹 + 无触觉反馈。
      // 色板是高频点击控件，改为全 App 统一的 iOS 反馈（变淡 + 轻缩 + 轻震动）。
      .clickableWithFeedback(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    // 旋转/圆角/颜色形变只作用于背景层，勾选图标保持正立
    Box(
      modifier = Modifier
        .matchParentSize()
        .rotate(rotation)
        .clip(RoundedCornerShape(cornerDp))
        .background(color = morphColor, shape = RoundedCornerShape(cornerDp))
    )
    Icon(
      imageVector = Icons.Filled.Check,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier
        .size(20.dp)
        .alpha(checkAlpha),
    )
  }
}
