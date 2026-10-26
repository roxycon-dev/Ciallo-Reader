package com.example.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 渲染画质档位：主要影响玻璃效果强度与前端动效数量。
 *
 * MINIMAL 极简 —— 纯色基础控件，无玻璃采样、装饰动画、漫画特效及增强；小缓存解码。
 * LOW 流畅 —— 无实时模糊/噪点/棱镜描边，单层阴影，装饰性循环动画关闭。
 * MID  均衡 —— 保留基础玻璃配色；按钮、开关、滑条和分段选择使用普通控件，
 *              弹层使用普通弹簧缩放/淡入淡出，底栏用半透明底替代毛玻璃。
 * HIGH 高   —— 完整液态玻璃：22dp 实时背景模糊 + 全部七层光学装饰 + 底栏实时毛玻璃。
 *              保留现有抽纸变形、水滴选择、液态滑条及挤压开关。
 * MAX  极致 —— 高画质之上追加：边缘折射透镜（AGSL，Android 13+ 自动降级）、
 *              更浓郁的色彩饱和、按压凝胶缩放。旗舰机专属。
 */
enum class RenderQuality(val id: Int, val label: String) {
    MINIMAL(-1, "极简"),
    LOW(0, "流畅"),
    MID(1, "均衡"),
    HIGH(2, "高"),
    MAX(3, "极致");

    /** 不支持 RenderEffect 的系统直接用磨砂替代样式，也不创建无效的采样层。 */
    val realtimeGlass: Boolean get() = id >= HIGH.id && supportsRealtimeBlur

    /** Paper deformation and liquid control motion are reserved for HIGH and MAX. */
    val enhancedMotion: Boolean get() = id >= HIGH.id

    val minimal: Boolean get() = this == MINIMAL
    val decorativeEffects: Boolean get() = id > LOW.id

    companion object {
        fun of(id: Int): RenderQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}

/** 当前全局渲染画质，由 MainActivity 根据用户设置下发。 */
val LocalRenderQuality = staticCompositionLocalOf { RenderQuality.HIGH }
