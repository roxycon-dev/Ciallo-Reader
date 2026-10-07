package com.example.ui.comic

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 阅读背景系统：纯色 / 纸张纹理（程序化生成）/ 沉浸式动态（当前页主色调平滑过渡）。
 */
object ComicReaderBackgrounds {

    /** 生成可平铺纸张纹理（缓存按强度 + 尺寸） */
    private var cachedTexture: Pair<Int, ImageBitmap>? = null

    fun paperTexture(intensity: Int): ImageBitmap {
        val key = intensity.coerceIn(0, 100) / 5 // 5% 一档缓存
        cachedTexture?.let { (k, bmp) -> if (k == key) return bmp }
        val image = generatePaper(key)
        cachedTexture = key to image
        return image
    }

    /**
     * 原始纹理位图（第六轮第 2 条）：Compose 背景层与 CURL 引擎的 GL 背景
     * mesh 共用同一份纹理生成（单一数据源）——两种引擎下纸张背景逐像素一致。
     * 返回的 Bitmap 由缓存持有，调用方不得 recycle。
     */
    fun paperTextureRaw(intensity: Int): Bitmap {
        val key = intensity.coerceIn(0, 100) / 5
        cachedTexture?.let { (k, bmp) -> if (k == key) return bmp.asAndroidBitmap() }
        val image = generatePaper(key)
        cachedTexture = key to image
        return image.asAndroidBitmap()
    }

    private fun generatePaper(key: Int): ImageBitmap {
        val size = 256
        val base = intArrayOf(0xFFF3ECDF.toInt(), 0xFFF1E9DB.toInt())
        val amp = if (key == 0) 0 else 2 + key * 9 / 20
        val pixels = IntArray(size * size)
        val rnd = java.util.Random(20260828L)
        var noise = 0
        for (i in pixels.indices) {
            noise = (noise * 3 + rnd.nextInt()) shr 2
            val n = (noise and 0xFF) - 128
            // 横向纤维：x 相关微扰
            val fiber = ((i % size) % 37 - 18) / 6
            val c = base[if (key == 0) 0 else abs(i / size) % 2]
            val r = (((c shr 16) and 0xFF) + n * amp / 128 + fiber * amp / 24).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n * amp / 128 + fiber * amp / 26).coerceIn(0, 255)
            val b = ((c and 0xFF) + n * amp / 132 + fiber * amp / 28).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp.asImageBitmap()
    }
}

/** 背景渲染：type = BLACK/WHITE/GRAY/PAPER/DYNAMIC(color) */
@Composable
fun ComicReaderBackground(
    bgType: ComicBgType,
    paperIntensity: Int,
    dynamicColor: Color?,
    modifier: Modifier = Modifier,
) {
    val base = modifier.fillMaxSize()
    when (bgType) {
        ComicBgType.BLACK -> Box(base.background(Color.Black))
        ComicBgType.WHITE -> Box(base.background(Color(0xFFF7F7F5)))
        ComicBgType.GRAY -> Box(base.background(Color(0xFF232326)))
        ComicBgType.PAPER -> {
            val texture = remember(paperIntensity) { ComicReaderBackgrounds.paperTexture(paperIntensity) }
            Image(
                bitmap = texture,
                contentDescription = null,
                modifier = base.background(Color(0xFFF3ECDF)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        ComicBgType.DYNAMIC -> {
            // 主色切换渐变 400ms + FastOutSlowIn（第 25 条：200-400ms 区间上限）。
            // 显式 Animatable 驱动（第三轮修复）：animateColorAsState 在本组合中
            // 被观察到不插值直接跳变（录屏相邻 17ms 两帧间暖→冷全切，无中间态，
            // 详见执行记录 9.6）；自驱动画配合 snapshotFlow 信标可逐帧审计。
            val colorAnim = remember {
                androidx.compose.animation.core.Animatable(
                    initialValue = dynamicColor ?: Color(0xFF101014),
                    typeConverter = androidx.compose.animation.core.TwoWayConverter(
                        convertToVector = { c ->
                            androidx.compose.animation.core.AnimationVector4D(c.red, c.green, c.blue, c.alpha)
                        },
                        convertFromVector = { v -> Color(v.v1, v.v2, v.v3, v.v4) },
                    ),
                )
            }
            LaunchedEffect(dynamicColor) {
                // 挂钟驱动的逐帧插值（第三轮修复）：模拟器的 vsync 时间戳会大幅
                // 跳变，时间基准 tween（animateColorAsState / Animatable.animateTo）
                // 在两个粗时间戳帧之间被判定"已超时"直接落到终值——录屏相邻
                // 17ms 两帧间暖→冷全切即此（信标 frame#0→#1 终值无中间态）。
                // 这里 withFrameNanos 只作帧节拍，进度用真实挂钟计算，时间戳
                // 造假不再影响插值；400ms + FastOutSlowIn（第 25 条区间上限）。
                val start = colorAnim.value
                val end = dynamicColor ?: Color(0xFF101014)
                if (start != end) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val dur = 400L
                    while (isActive) {
                        androidx.compose.runtime.withFrameNanos { }
                        val t = (android.os.SystemClock.uptimeMillis() - t0).coerceAtMost(dur) / dur.toFloat()
                        val eased = androidx.compose.animation.core.FastOutSlowInEasing.transform(t)
                        colorAnim.snapTo(androidx.compose.ui.graphics.lerp(start, end, eased))
                        if (t >= 1f) break
                    }
                }
            }
            val animated = colorAnim.value
            if (com.example.BuildConfig.DEBUG) {
                // 渐变采样信标（第三轮）：snapshotFlow 逐帧观察动画值（录屏节拍
                // 无法解析 400ms 中间态时的替代证据通道）
                LaunchedEffect(Unit) {
                    var frame = 0
                    androidx.compose.runtime.snapshotFlow { colorAnim.value }.collect { c ->
                        if (frame < 6 || frame % 6 == 0) {
                            val argb = ((c.alpha * 255).toInt() shl 24) or
                                ((c.red * 255).toInt() shl 16) or
                                ((c.green * 255).toInt() shl 8) or
                                (c.blue * 255).toInt()
                            android.util.Log.d("ComicDynBg", "anim frame#$frame argb=%06X".format(argb))
                        }
                        frame++
                    }
                }
            }
            Box(
                base.background(
                    Brush.verticalGradient(
                        listOf(
                            animated.copy(alpha = 0.92f),
                            animated,
                            animated.copy(alpha = 0.92f)
                        )
                    )
                )
            )
        }
    }
}
