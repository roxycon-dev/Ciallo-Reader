package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.max
import kotlin.random.Random

/**
 * `app_language.dart:239-369` 的逐字符折变（`_AppTextState`）。
 *
 * 参考实现在**语言区域切换**时播一次：780ms 时钟，每字符延迟
 * `Random(data.hashCode ^ ++generation).nextDouble() * .52`，单字符
 * `t = ((clock - delay) / .48).clamp(0, 1)`，alpha 走
 * `t < .5 ? 1 - 1.84t : .08 + 1.84(t - .5)`（夹在 `.08..1`），前半程用旧字、
 * 后半程用新字 —— 先淡出旧字再淡入新字，不做字符位移，因此不改变行高与换行。
 *
 * 本工程没有切换语言区域的入口，改为**文本内容变化**时触发同一条时钟；
 * `morphOnChange = false` 时退化成普通 `Text`（用于纯静态标题，避免无谓 ticker）。
 *
 * @param from 起始文本，默认取上一次的 [text]。
 */
@Composable
fun WenkuMorphText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    morphOnChange: Boolean = true,
    maxChars: Int = 512,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    val animated = morphOnChange && !a11y.reduceMotion && decorative

    var previous by remember { mutableStateOf(text) }
    var from by remember { mutableStateOf(text) }
    var to by remember { mutableStateOf(text) }
    var delays by remember { mutableStateOf(FloatArray(0)) }
    var generation by remember { mutableIntStateOf(0) }
    val clock = remember { Animatable(1f) }

    LaunchedEffect(text, animated) {
        if (previous == text) return@LaunchedEffect
        previous = text
        if (!animated || text.length > maxChars) {
            clock.snapTo(1f)
            from = text
            to = text
            delays = FloatArray(0)
            return@LaunchedEffect
        }
        from = to
        to = text
        generation += 1
        // Random(data.hashCode ^ ++generation)：Kotlin 侧同构即可（参考只要求可复现的伪随机）。
        val random = Random(text.hashCode() xor generation)
        val count = max(from.length, to.length)
        delays = FloatArray(count) { random.nextDouble().toFloat() * .52f }
        clock.snapTo(0f)
        clock.animateTo(1f, tween(durationMillis = TEXT_FOLD_MS, easing = LinearEasing))
    }

    val c = clock.value
    if (delays.isEmpty() || c >= 1f) {
        Text(text = to, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
        return
    }

    val resolved = if (color == Color.Unspecified) style.color else color
    val spans: AnnotatedString = buildAnnotatedString {
        for (index in delays.indices) {
            val t = ((c - delays[index]) / .48f).coerceIn(0f, 1f)
            val alpha = (if (t < .5f) 1f - t * 1.84f else .08f + (t - .5f) * 1.84f)
                .coerceIn(.08f, 1f)
            val source = if (t < .5f) from else to
            if (index >= source.length) continue
            withStyle(SpanStyle(color = resolved.copy(alpha = resolved.alpha * alpha))) {
                append(source[index])
            }
        }
    }
    Text(text = spans, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
}

/** `app_language.dart:264-265` 的 780ms。 */
private const val TEXT_FOLD_MS = 780

/**
 * `reading_statistics_shared_charts.dart:299-315`：数值 0→1 用 `NovelMotion.settle`
 * 驱动 340ms，环图/柱高按同一进度放大；这里作为纯文本数字的等价实现。
 */
@Composable
fun WenkuCountUp(
    value: Float,
    modifier: Modifier = Modifier,
    durationMillis: Int = 340,
    format: (Float) -> String = { it.toInt().toString() },
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
): String {
    val a11y = rememberWenkuAccessibility()
    val animated = !a11y.reduceMotion && LocalWenkuDecorativeMotion.current
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(value, animated) {
        if (!animated) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = durationMillis, easing = WenkuMotion.settle))
    }
    return format(value * progress.value.coerceIn(0f, 1f))
}
