package com.example.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * 毛玻璃卡片可调参数。外观设置实时写入，并经 [LocalCardTweaks] 提供给玻璃卡。
 *
 * v2 默认档：为让滑条开箱即有强可感知反馈，出厂值整体上探一档
 * （tilt 6° / 相机 5× / 压力 125%）；配合旧安装的一次性迁移。
 */
data class CardTweaks(
    val blurRadiusDp: Float = 22f,        // 毛玻璃模糊强度
    val cornerRadiusDp: Float = 16f,      // 卡片圆角
    val tiltMaxDeg: Float = 6f,           // 3D 倾斜最大角度
    val cameraDistMult: Float = 5f,       // 相机距离倍率（越小透视越强）

    /**
     * ⚠️ 已停用：触点涟漪扩散层已按「按压不得出现任何扩大圆环」的验收标准整体移除，
     * GlassCard 不再消费此值。字段保留仅为兼容既有持久化数据，请勿在新代码中引用。
     */
    val rippleAlpha: Float = 0.42f,
    val tintMix: Float = 0.08f,           // 主题色调叠加比例
    val pressStrength: Float = 1.25f,     // 压力形变光效强度
    val pressRadius: Float = 1.1f,        // 压力形变光效半径倍率
    val cardAlpha: Float = 1f             // 卡片本体透明度
)

/** 默认实例：未 provide 时 GlassCard 回退到历史视觉。 */
val LocalCardTweaks = compositionLocalOf { CardTweaks() }

/** 从持久化偏好读取当前卡片参数。 */
fun com.example.data.PreferencesManager.readCardTweaks(): CardTweaks = CardTweaks(
    blurRadiusDp = cardBlurRadiusDp,
    cornerRadiusDp = cardCornerRadiusDp,
    tiltMaxDeg = cardTiltMaxDeg,
    cameraDistMult = cardCameraDistMult,
    rippleAlpha = cardRippleAlpha,
    tintMix = cardTintMix,
    pressStrength = cardPressStrength,
    pressRadius = cardPressRadius,
    cardAlpha = cardAlpha
)

/** 全量写回 9 个卡片参数（设置页滑块实时调用，setter 内部自带 coerce）。 */
fun com.example.data.PreferencesManager.writeCardTweaks(t: CardTweaks) {
    cardBlurRadiusDp = t.blurRadiusDp
    cardCornerRadiusDp = t.cornerRadiusDp
    cardTiltMaxDeg = t.tiltMaxDeg
    cardCameraDistMult = t.cameraDistMult
    cardRippleAlpha = t.rippleAlpha
    cardTintMix = t.tintMix
    cardPressStrength = t.pressStrength
    cardPressRadius = t.pressRadius
    cardAlpha = t.cardAlpha
}
