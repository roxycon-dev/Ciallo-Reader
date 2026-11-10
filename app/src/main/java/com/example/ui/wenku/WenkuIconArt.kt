package com.example.ui.wenku

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Paper & Archive：24 单位光学网格上的手绘纸张图标符号表。
 *
 * 1:1 移植自 Wenku8 Flutter 参考实现的 `ArchiveSymbol`（89 项，保留原中英文标签与分组）。
 * 几何部分对应 `archive_icon_art.dart`（`_Art` 变换栈 / `_Trace` 路径 DSL）、
 * `archive_icon_fold_art.dart`（逐符号手势几何）、`archive_icon_motion.dart`（三时钟采样）。
 */
enum class WenkuSymbol(val label: String, val group: String) {
    Discover("发现", "导航"),
    Shelf("书架", "导航"),
    Book("阅读", "导航"),
    Folder("本地书库", "导航"),
    FolderOpen("打开文件夹", "导航"),
    Profile("我的", "导航"),
    Back("返回", "导航"),
    Forward("前进", "导航"),
    Up("收起", "导航"),
    Down("展开", "导航"),
    ArrowLeft("上一项", "导航"),
    ArrowRight("下一项", "导航"),
    ArrowUp("向上", "导航"),
    ArrowDown("向下", "导航"),
    External("打开链接", "导航"),
    Close("关闭", "导航"),
    More("更多", "导航"),
    Contents("目录", "阅读"),
    Bookmark("书签", "阅读"),
    Bookmarked("已收藏", "阅读"),
    Location("定位章节", "阅读"),
    LocationFilled("跟随朗读", "阅读"),
    Typography("字体", "阅读"),
    Sliders("阅读设置", "阅读"),
    Image("插图", "阅读"),
    Brightness("亮度", "阅读"),
    Moon("夜间阅读", "阅读"),
    Layers("合并卷", "阅读"),
    Quotes("摘录", "阅读"),
    Language("双语", "阅读"),
    Portrait("强制竖屏", "阅读"),
    Headphones("听书", "听书"),
    Play("播放", "听书"),
    Pause("暂停", "听书"),
    Stop("停止", "听书"),
    Previous("上一段", "听书"),
    Next("下一段", "听书"),
    Waveform("声音", "听书"),
    Voice("音色", "听书"),
    Volume("音量", "听书"),
    Speed("速度", "听书"),
    Timer("历史与定时", "听书"),
    Search("搜索", "操作"),
    Filter("筛选排序", "操作"),
    Layout("视图布局", "操作"),
    Grid("网格视图", "操作"),
    DotsGrid("布局", "操作"),
    Add("添加", "操作"),
    AddCircle("添加项目", "操作"),
    RemoveCircle("移出", "操作"),
    Check("完成", "操作"),
    CheckCircle("勾选", "操作"),
    Selected("已选择", "操作"),
    Circle("未选择", "操作"),
    Edit("编辑", "操作"),
    Delete("删除", "操作"),
    Copy("复制", "操作"),
    Share("分享", "操作"),
    Link("关联原文件", "操作"),
    Refresh("刷新重试", "操作"),
    Sync("同步", "文件"),
    Download("下载", "文件"),
    CloudDownload("离线缓存", "文件"),
    Upload("更新", "文件"),
    ImportFile("导入", "文件"),
    Document("文档", "文件"),
    FolderAdd("添加目录", "文件"),
    Wand("整理修复", "文件"),
    Settings("设置", "系统"),
    Info("关于", "系统"),
    Warning("异常", "系统"),
    Wifi("无线连接", "系统"),
    WifiOff("离线", "系统"),
    WifiWarning("连接异常", "系统"),
    Cloud("云书库", "系统"),
    Globe("官网", "系统"),
    Phone("手机", "系统"),
    Laptop("电脑", "系统"),
    Branch("同步冲突", "系统"),
    PersonAdd("注册账号", "系统"),
    Eye("显示", "系统"),
    EyeOff("隐藏", "系统"),
    CloseCircle("清空", "系统"),
    Battery("电量", "系统"),
    Charging("充电", "系统"),
    Tools("故障排查", "系统"),
    Shield("安全", "系统"),
    Help("帮助", "系统"),
    Touch("触摸", "阅读");
}

/**
 * 一整次手势，而不是一份指针压力。纸片的三段（lead / follow / trail）一起离开，
 * 再依次落回。对应 `ArchiveIconGesture`。
 */
data class WenkuIconGesture(
    val lead: Float,
    val follow: Float,
    val trail: Float,
) {
    /** 叠加两次手势；夹取区间与 Dart 端 `clamp(-.3, 1.2)` 一致。 */
    fun plus(other: WenkuIconGesture, weight: Float): WenkuIconGesture = WenkuIconGesture(
        lead = (lead + other.lead * weight).coerceIn(-0.3f, 1.2f),
        follow = (follow + other.follow * weight).coerceIn(-0.3f, 1.2f),
        trail = (trail + other.trail * weight).coerceIn(-0.3f, 1.2f),
    )

    companion object {
        /** 静止姿态。 */
        val Rest = WenkuIconGesture(0f, 0f, 0f)
    }
}

/**
 * 归一化后的绘制姿态：`symbol` 一定是可绘制的族符号，`variant` 是方向/开合变体角，
 * `active` 是 0..1 的选中量。对应 `_IconPose`。
 */
data class WenkuIconPose(
    val symbol: WenkuSymbol,
    val variant: Float = 0f,
    val active: Float = 0f,
)

/** 一条已烘焙（含变换）的描边：路径、是否填充、不透明度、线宽系数。对应 `_Stroke`。 */
data class WenkuIconStroke(
    val path: Path,
    val fill: Boolean,
    val opacity: Float,
    val width: Float,
)

/**
 * 三时钟之一：手势时钟。与 Dart 端 `ArchiveIconMotion` 完全一致——
 * 640ms 一次完整手势，380ms 状态 morph，`stateCurve = Cubic(.18, 1.28, .32, 1)`，
 * `_spring` 关键帧 t=[0,.10,.36,.64,.82,1] v=[0,-.12,1.0,-.20,.065,0]。
 */
object WenkuIconMotion {
    const val DURATION_MS: Int = 640
    const val MORPH_MS: Int = 380

    /** `Cubic(.18, 1.28, .32, 1)`。 */
    val stateCurve: Easing = CubicBezierEasing(0.18f, 1.28f, 0.32f, 1f)

    /** Flutter `Curves.easeInOutCubic` = `Cubic(0.645, 0.045, 0.355, 1.0)`。 */
    private val easeInOutCubic: Easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

    /** 手势相位 → 形态进度（380ms 内完成 morph）。 */
    fun stateAt(phase: Float): Float = stateCurve.transform(
        (phase * DURATION_MS / MORPH_MS.toFloat()).coerceIn(0f, 1f),
    )

    /** 手势相位 → lead / follow / trail 三段位移。 */
    fun frame(phase: Float): WenkuIconGesture = WenkuIconGesture(
        lead = spring(phase),
        follow = spring(((phase - 0.055f) / 0.945f).coerceIn(0f, 1f)),
        trail = spring(((phase - 0.11f) / 0.89f).coerceIn(0f, 1f)),
    )

    private fun spring(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        for (i in 1 until SPRING_TIMES.size) {
            if (t <= SPRING_TIMES[i]) {
                val x = (t - SPRING_TIMES[i - 1]) / (SPRING_TIMES[i] - SPRING_TIMES[i - 1])
                val eased = easeInOutCubic.transform(x)
                return SPRING_VALUES[i - 1] + (SPRING_VALUES[i] - SPRING_VALUES[i - 1]) * eased
            }
        }
        return 0f
    }

    private val SPRING_TIMES = floatArrayOf(0f, 0.10f, 0.36f, 0.64f, 0.82f, 1f)
    private val SPRING_VALUES = floatArrayOf(0f, -0.12f, 1f, -0.20f, 0.065f, 0f)
}

/**
 * 状态归一化表：pause→play、back→forward、arrowLeft→arrowRight 等 13 个变体在这里
 * 折叠成族符号 + 变体角，绘制层因此只需处理 76 个可绘制族符号。对应 `_IconPose.of/lerp`。
 */
object WenkuIconPoses {
    fun of(symbol: WenkuSymbol, active: Boolean): WenkuIconPose {
        val family: WenkuSymbol
        val variant: Float
        when (symbol) {
            WenkuSymbol.Pause -> { family = WenkuSymbol.Play; variant = 1f }
            WenkuSymbol.Back -> { family = WenkuSymbol.Forward; variant = PI.toFloat() }
            WenkuSymbol.Up -> { family = WenkuSymbol.Forward; variant = (-PI / 2).toFloat() }
            WenkuSymbol.Down -> { family = WenkuSymbol.Forward; variant = (PI / 2).toFloat() }
            WenkuSymbol.ArrowLeft -> { family = WenkuSymbol.ArrowRight; variant = PI.toFloat() }
            WenkuSymbol.ArrowUp -> { family = WenkuSymbol.ArrowRight; variant = (-PI / 2).toFloat() }
            WenkuSymbol.ArrowDown -> { family = WenkuSymbol.ArrowRight; variant = (PI / 2).toFloat() }
            WenkuSymbol.External -> { family = WenkuSymbol.ArrowRight; variant = (-PI / 4).toFloat() }
            WenkuSymbol.Bookmarked -> { family = WenkuSymbol.Bookmark; variant = 1f }
            WenkuSymbol.EyeOff -> { family = WenkuSymbol.Eye; variant = 1f }
            WenkuSymbol.CheckCircle -> { family = WenkuSymbol.Circle; variant = 1f }
            WenkuSymbol.Selected -> { family = WenkuSymbol.Circle; variant = 2f }
            WenkuSymbol.LocationFilled -> { family = WenkuSymbol.Location; variant = 1f }
            else -> { family = symbol; variant = 0f }
        }
        return WenkuIconPose(family, variant, if (active) 1f else 0f)
    }

    fun lerp(a: WenkuIconPose, b: WenkuIconPose, t: Float): WenkuIconPose = WenkuIconPose(
        symbol = if (t < 0.5f && a.symbol != b.symbol) a.symbol else b.symbol,
        variant = if (a.symbol == b.symbol) a.variant + (b.variant - a.variant) * t else b.variant,
        active = a.active + (b.active - a.active) * t,
    )
}

/**
 * 绘制入口：把姿态 + 手势烘成一组 `WenkuIconStroke`。坐标系固定 24×24 逻辑单位，
 * 由绘制层按 `size / 24` 缩放。对应 `ArchiveIconArt`。
 */
object WenkuIconArt {
    fun strokes(
        pose: WenkuIconPose,
        contact: Float = 0f,
        gesture: WenkuIconGesture = WenkuIconGesture(contact, contact, contact),
    ): List<WenkuIconStroke> = foldDrawing(pose, gesture)
}

// ---------------------------------------------------------------------------
// 变换栈：Matrix4 的 2D 仿射子集（translate / rotateZ / scale，围绕 pivot）。
// Dart 端是 `previous * T(x+p, y+p) * Rz(angle) * S(scale) * T(-p, -p)`。
// ---------------------------------------------------------------------------

private class WenkuMat(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f,
) {
    /** this ∘ other：先应用 other，再应用 this。 */
    fun times(other: WenkuMat): WenkuMat = WenkuMat(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        e = a * other.e + c * other.f + e,
        f = b * other.e + d * other.f + f,
    )

    fun x(x: Float, y: Float): Float = a * x + c * y + e
    fun y(x: Float, y: Float): Float = b * x + d * y + f

    companion object {
        fun translate(tx: Float, ty: Float): WenkuMat = WenkuMat(e = tx, f = ty)

        fun rotate(angle: Float): WenkuMat {
            val cosA = cos(angle)
            val sinA = sin(angle)
            return WenkuMat(a = cosA, b = sinA, c = -sinA, d = cosA)
        }

        fun scale(s: Float): WenkuMat = WenkuMat(a = s, d = s)
    }
}

/**
 * 路径 DSL：`.m/.l/.q/.c/.z` 在写入时即时套用当前变换矩阵（与 Dart `_Trace` 相同）。
 */
private class WenkuTrace(private val matrix: WenkuMat) {
    val path: Path = Path()

    fun m(x: Float, y: Float): WenkuTrace {
        path.moveTo(matrix.x(x, y), matrix.y(x, y))
        return this
    }

    fun l(x: Float, y: Float): WenkuTrace {
        path.lineTo(matrix.x(x, y), matrix.y(x, y))
        return this
    }

    fun q(x1: Float, y1: Float, x: Float, y: Float): WenkuTrace {
        path.quadraticTo(
            matrix.x(x1, y1),
            matrix.y(x1, y1),
            matrix.x(x, y),
            matrix.y(x, y),
        )
        return this
    }

    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float): WenkuTrace {
        path.cubicTo(
            matrix.x(x1, y1),
            matrix.y(x1, y1),
            matrix.x(x2, y2),
            matrix.y(x2, y2),
            matrix.x(x, y),
            matrix.y(x, y),
        )
        return this
    }

    fun z(): WenkuTrace {
        path.close()
        return this
    }
}

/**
 * `_Art`：图元工厂 + 变换栈。折角、排版墨迹与轻微不对称都来自这些图元。
 */
private class WenkuArtBuilder {
    val strokes = ArrayList<WenkuIconStroke>()
    private var matrix = WenkuMat()

    fun rotate(angle: Float, draw: WenkuArtBuilder.() -> Unit) = transform(angle = angle, draw = draw)

    fun move(x: Float, y: Float, draw: WenkuArtBuilder.() -> Unit) = transform(x = x, y = y, draw = draw)

    fun transform(
        x: Float = 0f,
        y: Float = 0f,
        angle: Float = 0f,
        scale: Float = 1f,
        pivotX: Float = 12f,
        pivotY: Float = 12f,
        draw: WenkuArtBuilder.() -> Unit,
    ) {
        val previous = matrix
        matrix = previous
            .times(WenkuMat.translate(x + pivotX, y + pivotY))
            .times(WenkuMat.rotate(angle))
            .times(WenkuMat.scale(scale))
            .times(WenkuMat.translate(-pivotX, -pivotY))
        try {
            draw()
        } finally {
            matrix = previous
        }
    }

    fun card(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fold: Float = 2f,
        shade: Float = 0.12f,
        outline: Boolean = true,
    ) {
        val f = min(fold, min(w, h) * 0.48f)
        val contour: WenkuTrace.() -> Unit = {
            m(x, y)
                .l(x + w - f, y)
                .l(x + w, y + f)
                .l(x + w, y + h)
                .l(x, y + h)
                .z()
        }
        if (shade > 0f) path(fill = true, opacity = shade, draw = contour)
        if (outline) {
            path(draw = contour)
            poly(xy(x + w - f, y, x + w - f, y + f, x + w, y + f), opacity = 0.7f)
        }
    }

    fun mark(x: Float, y: Float, size: Float, angle: Float = 0f) = transform(
        angle = angle,
        pivotX = x + size / 2f,
        pivotY = y + size / 2f,
        draw = { rect(x, y, size, size, fill = true) },
    )

    fun ring() {
        arc(12f, 12f, 8.4f, 8.4f, -0.48f, PI.toFloat() * 1.83f)
        mark(16.9f, 4.5f, 1.5f, angle = PI.toFloat() / 4f)
    }

    fun path(
        fill: Boolean = false,
        opacity: Float = 1f,
        width: Float = 1f,
        draw: WenkuTrace.() -> Unit,
    ) {
        val trace = WenkuTrace(matrix)
        trace.draw()
        strokes.add(
            WenkuIconStroke(
                path = trace.path,
                fill = fill,
                opacity = opacity.coerceIn(0f, 1f),
                width = width,
            ),
        )
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, opacity: Float = 1f) =
        path(opacity = opacity, draw = { m(x1, y1).l(x2, y2) })

    fun poly(
        points: FloatArray,
        close: Boolean = false,
        fill: Boolean = false,
        opacity: Float = 1f,
    ) = path(fill = fill, opacity = opacity, draw = {
        m(points[0], points[1])
        var i = 2
        while (i < points.size) {
            l(points[i], points[i + 1])
            i += 2
        }
        if (close) z()
    })

    fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = 0f,
        fill: Boolean = false,
        opacity: Float = 1f,
    ) = path(fill = fill, opacity = opacity, draw = {
        m(x + radius, y)
            .l(x + w - radius, y)
            .q(x + w, y, x + w, y + radius)
            .l(x + w, y + h - radius)
            .q(x + w, y + h, x + w - radius, y + h)
            .l(x + radius, y + h)
            .q(x, y + h, x, y + h - radius)
            .l(x, y + radius)
            .q(x, y, x + radius, y)
            .z()
    })

    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false, opacity: Float = 1f) =
        ellipse(x, y, r, r, fill = fill, opacity = opacity)

    fun dot(x: Float, y: Float, r: Float) = circle(x, y, r, fill = true)

    fun ellipse(
        x: Float,
        y: Float,
        rx: Float,
        ry: Float,
        fill: Boolean = false,
        opacity: Float = 1f,
    ) {
        val k = 0.5522847498307936f
        path(fill = fill, opacity = opacity, draw = {
            m(x + rx, y)
                .c(x + rx, y + ry * k, x + rx * k, y + ry, x, y + ry)
                .c(x - rx * k, y + ry, x - rx, y + ry * k, x - rx, y)
                .c(x - rx, y - ry * k, x - rx * k, y - ry, x, y - ry)
                .c(x + rx * k, y - ry, x + rx, y - ry * k, x + rx, y)
                .z()
        })
    }

    fun arc(x: Float, y: Float, rx: Float, ry: Float, start: Float, sweep: Float) = path(draw = {
        m(x + rx * cos(start), y + ry * sin(start))
        val n = ceil(abs(sweep) / (PI.toFloat() / 4f)).toInt()
        val step = sweep / n
        for (i in 0 until n) {
            val a = start + step * i
            val b = a + step
            val k = 4f / 3f * tan(step / 4f)
            c(
                x + rx * (cos(a) - k * sin(a)),
                y + ry * (sin(a) + k * cos(a)),
                x + rx * (cos(b) + k * sin(b)),
                y + ry * (sin(b) - k * cos(b)),
                x + rx * cos(b),
                y + ry * sin(b),
            )
        }
    })

    fun star(x: Float, y: Float, r: Float) = path(draw = {
        m(x, y - r)
            .q(x + r * 0.15f, y - r * 0.15f, x + r, y)
            .q(x + r * 0.15f, y + r * 0.15f, x, y + r)
            .q(x - r * 0.15f, y + r * 0.15f, x - r, y)
            .q(x - r * 0.15f, y - r * 0.15f, x, y - r)
            .z()
    })
}

private fun xy(vararg values: Float): FloatArray = values

// ---------------------------------------------------------------------------
// 逐符号手势几何：`archive_icon_fold_art.dart` 的 `switch (pose.symbol)`。
// 76 个族符号在这里出图；13 个变体符号必须先经 `WenkuIconPoses.of` 归一化。
// ---------------------------------------------------------------------------

private fun foldDrawing(pose: WenkuIconPose, gesture: WenkuIconGesture): List<WenkuIconStroke> {
    val a = WenkuArtBuilder()
    val p = gesture.lead
    val q = gesture.follow
    val r = gesture.trail
    val active = pose.active.coerceIn(0f, 1f)
    val v = pose.variant
    when (pose.symbol) {
        WenkuSymbol.Discover -> {
            a.card(3.6f, 3.1f, 16.8f, 17.8f, fold = 3.6f, shade = 0.09f + active * 0.10f)
            a.rotate(p * 0.80f) {
                a.poly(xy(15.9f, 7.8f, 13.6f, 13.6f, 7.5f, 16.7f, 10.5f, 10.5f), close = true)
                a.poly(
                    xy(15.9f, 7.8f, 13.6f, 13.6f, 10.5f, 10.5f),
                    close = true,
                    fill = true,
                    opacity = 0.65f,
                )
            }
            a.mark(6.6f, 17.8f, 1.15f)
        }

        WenkuSymbol.Shelf -> {
            a.poly(xy(2.5f, 19.6f, 2.5f, 21f, 21.5f, 21f))
            a.move(0f, -1.7f * p) {
                a.card(3.6f, 6f, 4.8f, 13.6f, fold = 1.5f, shade = 0.19f)
                a.mark(5.2f, 9f, 1.3f)
            }
            a.move(0f, -2.4f * q) {
                a.card(10f, 3.9f, 4.5f, 15.7f, fold = 1.5f, shade = 0.09f + active * 0.15f)
                a.line(11.6f, 7f, 12.8f, 7f)
            }
            a.move(0f, -0.8f * r) {
                a.transform(angle = -0.12f - r * 0.15f, pivotX = 19f, pivotY = 20f) {
                    a.card(17.1f, 6.2f, 4.1f, 13.4f, fold = 1.3f, shade = 0.08f)
                }
            }
        }

        WenkuSymbol.Book -> {
            a.path(fill = true, opacity = 0.06f + active * 0.13f) {
                m(3.1f, 6f)
                    .q(7.5f, 4.1f - 0.7f * p, 12f, 7f)
                    .q(16f, 4.3f - 0.7f * r, 20.9f, 6f)
                    .l(20.3f, 18.6f)
                    .q(16.1f, 17.8f, 12f, 20f)
                    .q(7.9f, 17.8f, 3.7f, 18.6f)
                    .z()
            }
            a.path {
                m(3.1f, 6f)
                    .q(7.5f, 4.1f - 0.7f * p, 12f, 7f)
                    .q(16f, 4.3f - 0.7f * r, 20.9f, 6f)
                    .l(20.3f, 18.6f)
                    .q(16.1f, 17.8f, 12f, 20f)
                    .q(7.9f, 17.8f, 3.7f, 18.6f)
                    .z()
            }
            a.poly(xy(3.5f, 21f, 8.1f, 20.3f, 12f, 21.5f, 15.9f, 20.3f, 20.5f, 21f), opacity = 0.5f)
            a.path { m(12f, 7f).l(12f, 20f) }
            // 一片真正翻动的叶子扫过书缝，而不是整本书做缩放。
            a.path {
                m(12f, 7f)
                    .q(16.6f - 8f * q, 2.3f - p, 19.6f - 6.5f * q, 4.7f)
                    .l(19.5f - 6.5f * q, 16.9f)
                    .q(15f - 4f * q, 16.6f, 12f, 20f)
            }
            a.line(5.7f, 9.5f, 8.8f, 10.1f)
        }

        WenkuSymbol.Folder, WenkuSymbol.FolderOpen, WenkuSymbol.FolderAdd -> {
            a.card(6f, 4.6f - 2.6f * q, 12.6f, 13.3f, fold = 2.8f, shade = 0.12f)
            val open = (if (pose.symbol == WenkuSymbol.FolderOpen) 0.6f else 0f) + p
            a.path(fill = true, opacity = 0.11f + active * 0.10f) {
                m(3f, 8.4f + 2.1f * open)
                    .l(8f, 8.4f + 2.1f * open)
                    .l(10.4f, 10f + 2.1f * open)
                    .l(21.5f, 10f + 2.1f * open)
                    .l(19.7f, 20.2f)
                    .l(4.4f, 20.2f)
                    .z()
            }
            a.path {
                m(3f, 8.4f + 2.1f * open)
                    .l(8f, 8.4f + 2.1f * open)
                    .l(10.4f, 10f + 2.1f * open)
                    .l(21.5f, 10f + 2.1f * open)
                    .l(19.7f, 20.2f)
                    .l(4.4f, 20.2f)
                    .z()
            }
            if (pose.symbol == WenkuSymbol.FolderAdd) {
                a.line(10f, 15.4f, 15f, 15.4f)
                a.line(12.5f, 12.9f, 12.5f, 17.9f)
            } else {
                a.mark(6.2f, 16.7f, 1.5f)
                a.line(9.2f, 17.4f, 12.5f, 17.4f)
            }
        }

        WenkuSymbol.Profile -> {
            a.card(4f, 3f, 16f, 18f, fold = 3.5f, shade = 0.07f + active * 0.12f)
            a.circle(10.9f + p * 0.6f, 8.4f - p * 1.5f, 2.35f, fill = true, opacity = 0.17f)
            a.circle(10.9f + p * 0.6f, 8.4f - p * 1.5f, 2.35f)
            a.path { m(6.5f, 16.2f).q(10.8f, 10.3f - q, 15.4f, 16.2f) }
            a.line(7f, 18.2f, 12.2f, 18.2f)
            a.mark(16.1f, 17.4f, 1.25f)
        }

        WenkuSymbol.Forward -> {
            a.rotate(v) {
                a.poly(xy(8.4f + 1.3f * q, 5.6f, 15.4f + 1.5f * p, 12f, 8.4f + 1.3f * q, 18.4f))
                a.mark(5.4f + 1.2f * r, 11.4f, 1.25f)
            }
        }

        WenkuSymbol.ArrowRight -> {
            a.rotate(v) {
                a.line(3.8f - 0.5f * r, 12f, 19f + 1.5f * p, 12f)
                a.poly(xy(13f + 1.2f * q, 5.8f, 19f + 1.5f * p, 12f, 13f + 1.2f * q, 18.2f))
            }
        }

        WenkuSymbol.Close, WenkuSymbol.CloseCircle -> {
            if (pose.symbol == WenkuSymbol.CloseCircle) a.ring()
            val d = if (pose.symbol == WenkuSymbol.CloseCircle) 3.0f else 5.4f
            a.rotate(p * 0.70f) {
                a.line(12f - d, 12f - d, 12f + d, 12f + d)
                a.line(12f + d, 12f - d, 12f - d, 12f + d)
            }
        }

        WenkuSymbol.More -> {
            for (i in 0 until 3) {
                val bounce = floatArrayOf(p, q, r)[i]
                a.mark(
                    4.3f + i * 6.7f,
                    11f - bounce * 3.1f,
                    1.8f,
                    angle = if (i == 1) PI.toFloat() / 4f else 0f,
                )
            }
        }

        WenkuSymbol.Contents -> {
            for (i in 0 until 3) {
                val x = floatArrayOf(p, q, r)[i] * 2.2f
                val y = 5.3f + i * 6.5f
                a.mark(3.4f + x, y, 1.8f)
                a.line(8.2f + x, y + 0.9f, 20.3f - (if (i == 1) 3.3f else 0f), y + 0.9f)
            }
        }

        WenkuSymbol.Bookmark -> {
            val top = 3.2f + 1.8f * q
            val bottom = 21f - 1.1f * r
            a.path(
                fill = true,
                opacity = 0.09f + v.coerceIn(0f, 1f) * 0.28f + active * 0.06f,
            ) {
                m(6f, top)
                    .l(14.8f, top)
                    .l(18f, top + 3.2f)
                    .q(18f + 2.1f * p, 12f, 18f, bottom)
                    .l(12f, bottom - 4.2f)
                    .l(6f, bottom)
                    .z()
            }
            a.path {
                m(6f, top)
                    .l(14.8f, top)
                    .l(18f, top + 3.2f)
                    .q(18f + 2.1f * p, 12f, 18f, bottom)
                    .l(12f, bottom - 4.2f)
                    .l(6f, bottom)
                    .z()
            }
            a.poly(xy(14.8f, top, 14.8f - 1.2f * p, top + 3.2f, 18f, top + 3.2f))
            a.line(9f, top + 5.5f, 12f, top + 5.5f)
        }

        WenkuSymbol.Location -> {
            a.transform(x = 0.8f * p, y = -0.8f * p, angle = -0.13f * q) {
                a.poly(xy(3.4f, 9.8f, 20.4f, 3.6f, 14.2f, 20.6f, 10.6f, 13.4f), close = true)
                a.poly(
                    xy(10.6f, 13.4f, 20.4f, 3.6f, 14.2f, 20.6f),
                    close = true,
                    fill = true,
                    opacity = 0.15f + v.coerceIn(0f, 1f) * 0.2f,
                )
                a.line(10.6f, 13.4f, 20.4f, 3.6f)
            }
        }

        WenkuSymbol.Touch -> {
            // 食指按下时蜷起的手指跟随；手腕保持锚定，读起来是"接触"而不是"移动的指针"。
            val press = p.coerceIn(-0.12f, 1.0f)
            val curl = q.coerceIn(-0.12f, 1.0f)
            a.path(fill = true, opacity = 0.10f + active * 0.12f) {
                m(16.7f, 14.9f)
                    .l(19.3f, 14.5f)
                    .l(19.3f, 15.6f)
                    .q(19.3f, 18.4f, 17.6f, 20.1f)
                    .l(17.6f, 21f)
                    .l(15.8f, 21f)
                    .q(17.4f, 17.5f, 16.7f, 14.9f)
                    .z()
            }
            a.path {
                m(8.2f, 13.2f)
                    .l(8.2f + 0.2f * press, 5.9f + 1.05f * press)
                    .c(
                        8.2f + 0.2f * press,
                        3.7f + press,
                        11.6f + 0.2f * press,
                        3.7f + press,
                        11.6f + 0.2f * press,
                        5.9f + 1.05f * press,
                    )
                    .l(11.6f, 10.4f)
                    .c(12.7f, 9.2f - 0.3f * curl, 14.3f, 9.6f, 14.3f, 11.1f)
                    .c(15.1f, 10f - 0.25f * curl, 16.7f, 10.4f, 16.7f, 11.8f)
                    .c(17.8f, 10.9f, 19.3f, 11.7f, 19.3f, 13.2f)
                    .l(19.3f, 15.6f)
                    .q(19.3f, 18.4f, 17.6f, 20.1f)
                    .l(17.6f, 21f)
                    .l(10f, 21f)
                    .l(10f, 20.4f)
                    .q(8.8f, 19.7f, 7.7f, 18.1f)
                    .l(4.4f + 0.3f * curl, 13.9f)
                    .c(3.4f + 0.3f * curl, 12.6f, 4.9f, 11f, 6.1f, 12f)
                    .l(8.2f, 14.1f)
                    .z()
            }
            a.line(11.6f, 10.4f, 11.6f, 13.1f + 0.4f * curl)
            a.line(14.3f, 11.1f, 14.3f, 13.6f + 0.3f * r)
            a.line(16.7f, 11.8f, 16.7f, 14f)
            a.line(10.2f, 1.6f - 0.3f * r, 10.2f, 2.5f - 0.15f * r)
            a.line(5.6f - 0.35f * r, 3f - 0.25f * r, 6.5f - 0.15f * r, 3.8f - 0.1f * r)
            a.line(14.8f + 0.35f * r, 3f - 0.25f * r, 13.9f + 0.15f * r, 3.8f - 0.1f * r)
        }

        WenkuSymbol.Typography -> {
            a.path { m(3.4f, 7.5f).l(4f, 4.8f - 0.5f * p).l(16.2f, 4.8f - 0.5f * q).l(16.8f, 7.5f) }
            a.path(width = 1.35f) { m(10.1f, 5f).l(10.1f + 1.1f * p, 19.8f) }
            a.line(6.6f, 20f, 14.1f, 20f)
            a.line(17.6f, 12f - 1.2f * r, 21f, 12f - 1.2f * r)
            a.line(17.6f, 16f, 20.2f, 16f)
            a.mark(17.6f, 19.2f, 1.3f)
        }

        WenkuSymbol.Sliders -> {
            for (i in 0 until 3) {
                val x = 5.5f + 6.5f * i
                val y = floatArrayOf(8.0f + 4f * p, 15.0f - 5f * q, 7.0f + 5f * r)[i]
                a.line(x, 3f, x, y - 2.1f)
                a.line(x, y + 2.1f, x, 21f)
                a.poly(
                    xy(
                        x - 2.1f,
                        y - 1.6f,
                        x + 0.9f,
                        y - 1.6f,
                        x + 2.1f,
                        y - 0.4f,
                        x + 2.1f,
                        y + 1.6f,
                        x - 2.1f,
                        y + 1.6f,
                    ),
                    close = true,
                    fill = true,
                    opacity = 0.22f,
                )
                a.poly(
                    xy(
                        x - 2.1f,
                        y - 1.6f,
                        x + 0.9f,
                        y - 1.6f,
                        x + 2.1f,
                        y - 0.4f,
                        x + 2.1f,
                        y + 1.6f,
                        x - 2.1f,
                        y + 1.6f,
                    ),
                    close = true,
                )
            }
        }

        WenkuSymbol.Image -> {
            a.card(3.3f, 4f, 17.4f, 16.2f, fold = 3.1f + p, shade = 0.08f)
            a.circle(8f, 8.9f - 1.7f * q, 1.35f, fill = true)
            a.poly(xy(3.6f, 17f, 8.2f, 12.2f - p, 11.9f, 15.5f, 15.8f, 11.6f - 1.9f * r, 20.4f, 17f))
        }

        WenkuSymbol.Brightness -> {
            a.circle(12f, 12f, 3.8f - 0.6f * q)
            a.path(fill = true, opacity = 0.22f) {
                m(12f, 8.2f).c(6.9f, 8.2f, 6.9f, 15.8f, 12f, 15.8f).z()
            }
            a.rotate(p * 0.62f) {
                for (i in 0 until 8) {
                    val t = i * PI.toFloat() / 4f
                    val inner = 6.7f + (if (i % 2 == 0) p else r) * 0.7f
                    a.line(
                        12f + inner * cos(t),
                        12f + inner * sin(t),
                        12f + 9.2f * cos(t),
                        12f + 9.2f * sin(t),
                    )
                }
            }
        }

        WenkuSymbol.Moon -> {
            a.rotate(p * -0.30f) {
                val moon: WenkuTrace.() -> Unit = {
                    m(17.8f, 15.2f)
                        .c(10.1f, 17.2f, 7f, 10.2f, 9.5f, 3.6f)
                        .c(3.5f, 5.4f, 2.6f, 13f, 6.4f, 17.5f)
                        .c(10.4f, 21.8f, 17f, 21.1f, 20f, 15f)
                        .q(18.8f, 15.8f, 17.8f, 15.2f)
                        .z()
                }
                a.path(fill = true, opacity = 0.13f, draw = moon)
                a.path(draw = moon)
            }
            a.star(17.5f, 6.3f, 1.8f + 0.8f * q)
        }

        WenkuSymbol.Layers -> {
            a.poly(xy(3.5f, 16.4f, 12f, 21f, 20.5f, 16.4f))
            a.poly(xy(3.5f, 12.2f - 1.4f * q, 12f, 16.8f - 1.4f * q, 20.5f, 12.2f - 1.4f * q))
            a.poly(
                xy(
                    3.5f,
                    7.8f - 1.9f * p,
                    12f,
                    3.2f - 1.5f * p,
                    20.5f,
                    7.8f - 1.9f * p,
                    12f,
                    12.4f - 1.9f * p,
                ),
                close = true,
                fill = true,
                opacity = 0.13f,
            )
            a.poly(
                xy(
                    3.5f,
                    7.8f - 1.9f * p,
                    12f,
                    3.2f - 1.5f * p,
                    20.5f,
                    7.8f - 1.9f * p,
                    12f,
                    12.4f - 1.9f * p,
                ),
                close = true,
            )
            a.line(12f, 3.2f - 1.5f * p, 12f, 6.4f - 1.5f * p)
        }

        WenkuSymbol.Quotes -> {
            for (i in 0 until 2) {
                val x = 3.8f + i * 9f
                val y = 5.6f - floatArrayOf(p, q)[i] * 2.2f
                a.card(x, y, 6.8f, 7.3f, fold = 1.9f, shade = 0.26f)
                a.path { m(x + 6.8f, y + 7.3f).q(x + 6f, 18.8f, x + 1.8f, 19.7f) }
            }
        }

        WenkuSymbol.Language -> {
            a.move(0f, -p * 1.4f) {
                a.poly(xy(2.9f, 6.2f, 13.2f, 6.2f))
                a.line(8.1f, 3.2f, 8.1f, 6.2f)
                a.path { m(11.4f, 6.3f).q(10f, 13.5f, 3.3f, 16.3f) }
                a.path { m(5f, 9.5f).q(7.6f, 13.3f, 11.2f, 14.5f) }
            }
            a.move(0f, q * 1.4f) {
                a.poly(xy(12.6f, 20f, 17.1f, 9.2f, 21.5f, 20f))
                a.line(14.1f, 16.6f, 20f, 16.6f)
            }
            a.mark(16.5f, 3.8f, 1.3f, angle = PI.toFloat() / 4f)
        }

        WenkuSymbol.Portrait -> {
            a.card(6.7f, 2.7f, 10.6f, 18.6f, fold = 2.5f, shade = 0.09f)
            a.line(10.4f, 18.5f, 13.6f, 18.5f)
            a.card(9.4f, 9.7f, 5.2f, 4.8f, fold = 1.1f, shade = 0.18f)
            a.path {
                m(10.4f, 9.7f)
                    .l(10.4f, 7.9f - 1.1f * p)
                    .c(10.4f, 5.5f - p, 13.6f, 5.5f - p, 13.6f, 7.9f - 1.1f * p)
                    .l(13.6f, 9.7f)
            }
        }

        WenkuSymbol.Headphones -> {
            a.path {
                m(4.2f, 14f)
                    .l(4.2f, 10.3f)
                    .c(4.2f, 1f + p * 2f, 19.8f, 1f + q * 2f, 19.8f, 10.3f)
                    .l(19.8f, 14f)
            }
            a.card(3.2f, 12f - 1.6f * p, 4.6f, 8f, fold = 1.3f, shade = 0.20f)
            a.card(16.2f, 12f - 1.6f * q, 4.6f, 8f, fold = 1.3f, shade = 0.20f)
        }

        WenkuSymbol.Play -> {
            val t = v.coerceIn(0f, 1f)
            fun mix(x: Float, y: Float): Float = x + (y - x) * t
            a.path(fill = true) {
                m(mix(7.2f, 6.5f), 4.7f)
                    .l(mix(7.2f, 6.5f), 19.3f)
                    .l(mix(12.3f, 10.3f) + 0.9f * p, mix(16.3f, 18.1f))
                    .l(mix(12.3f, 10.3f) - 0.7f * p, mix(7.7f, 4.7f))
                    .z()
            }
            a.path(fill = true, opacity = 0.69f) {
                m(mix(12.3f, 14f) - 0.7f * p, mix(7.7f, 4.7f))
                    .l(mix(12.3f, 14f) + 0.9f * p, mix(16.3f, 19.3f))
                    .l(mix(19.4f, 18.2f) + 0.7f * q, mix(12f, 18.1f))
                    .l(mix(19.4f, 18.2f) - 0.5f * q, mix(12f, 4.7f))
                    .z()
            }
        }

        WenkuSymbol.Stop -> {
            a.card(
                5.3f + p * 0.4f,
                5.3f + q * 0.4f,
                13.4f - p * 0.8f,
                13.4f - q * 0.8f,
                fold = 3f + r,
                shade = 1f,
                outline = false,
            )
        }

        WenkuSymbol.Previous, WenkuSymbol.Next -> {
            a.rotate(if (pose.symbol == WenkuSymbol.Previous) PI.toFloat() else 0f) {
                a.poly(
                    xy(4.8f + 2f * p, 5.6f, 4.8f + 2f * p, 18.4f, 14.6f + 2f * q, 12f),
                    close = true,
                    fill = true,
                    opacity = 0.17f,
                )
                a.poly(
                    xy(4.8f + 2f * p, 5.6f, 4.8f + 2f * p, 18.4f, 14.6f + 2f * q, 12f),
                    close = true,
                )
                a.line(19.5f, 5f, 19.5f - 0.5f * r, 19f)
            }
        }

        WenkuSymbol.Waveform -> {
            for (i in 0 until 5) {
                val h = (
                    floatArrayOf(3.2f, 5.8f, 8.5f, 5.8f, 3.2f)[i] +
                        floatArrayOf(p, q, r, p, q)[i] * (if (i % 2 == 0) -2.2f else 2.6f)
                    ).coerceIn(1.2f, 10.1f)
                a.path(width = if (i == 2) 1.3f else 1f) {
                    m(4f + i * 4f, 12f - h).l(4f + i * 4f, 12f + h)
                }
            }
        }

        WenkuSymbol.Voice -> {
            a.card(9f, 3.1f, 6f, 11f, fold = 2f, shade = 0.15f)
            a.path {
                m(5.7f, 10.5f)
                    .l(5.7f, 12f - p)
                    .c(5.7f, 20f + 1.6f * q, 18.3f, 20f + 1.6f * r, 18.3f, 12f - p)
                    .l(18.3f, 10.5f)
            }
            a.line(12f, 18f, 12f, 21.2f)
            a.line(9f, 21.2f, 15f, 21.2f)
            a.line(11.2f, 6.2f, 12.8f, 6.2f)
        }

        WenkuSymbol.Volume -> {
            a.poly(
                xy(3.2f, 9.3f, 7f, 9.3f, 12f, 4.8f, 12f, 19.2f, 7f, 14.7f, 3.2f, 14.7f),
                close = true,
                fill = true,
                opacity = 0.13f,
            )
            a.poly(
                xy(3.2f, 9.3f, 7f, 9.3f, 12f, 4.8f, 12f, 19.2f, 7f, 14.7f, 3.2f, 14.7f),
                close = true,
            )
            a.path {
                m(15.4f + 0.6f * p, 8.6f - p)
                    .q(20.2f + p, 12f, 15.4f + 0.6f * p, 15.4f + p)
            }
            a.path {
                m(18.2f + 0.7f * q, 5.6f - 0.8f * q)
                    .q(24.4f + 0.4f * q, 12f, 18.2f + 0.7f * q, 18.4f + 0.8f * q)
            }
        }

        WenkuSymbol.Speed -> {
            a.arc(12f, 13.2f, 8.4f, 8.4f, PI.toFloat() * 0.85f, PI.toFloat() * 1.3f)
            a.rotate(p * 0.85f) {
                a.poly(xy(10.9f, 13f, 15.7f, 6.9f, 13f, 14.4f), close = true, fill = true)
            }
            a.mark(11.1f, 12.3f, 1.8f)
            a.line(8f, 20.6f, 16f, 20.6f)
            a.line(6f, 7.9f, 7.1f, 9f)
            a.line(12f, 4.8f, 12f, 6.1f)
            a.line(18f, 7.9f, 16.9f, 9f)
        }

        WenkuSymbol.Timer -> {
            a.ring()
            a.rotate(p * 0.50f) {
                a.poly(xy(12f, 6.5f, 12f, 12f, 16.3f, 14.7f))
            }
            a.mark(11.3f, 11.3f, 1.4f)
        }

        WenkuSymbol.Search -> {
            a.move(0.7f * p, -0.7f * p) {
                a.arc(10.3f, 10.3f, 6.5f - 0.75f * q, 6.5f + 0.35f * q, -0.65f, PI.toFloat() * 1.83f)
                a.mark(13.8f, 4.1f, 1.3f, angle = PI.toFloat() / 4f)
                a.poly(
                    xy(
                        14.8f,
                        16f,
                        16f,
                        14.8f,
                        21f + 0.5f * r,
                        19.8f + 0.5f * r,
                        19.8f + 0.5f * r,
                        21f + 0.5f * r,
                    ),
                    close = true,
                    fill = true,
                )
            }
        }

        WenkuSymbol.Filter -> {
            // 三条校准过的轨道：滑块各自移动，固定导轨始终保留清晰的筛选剪影。
            for (i in 0 until 3) {
                val y = 5.5f + i * 6.5f
                val x = floatArrayOf(8.0f + 5f * p, 16.0f - 5f * q, 10.0f + 4f * r)[i]
                a.path(width = 0.82f) { m(3.5f, y).l(x - 2.5f, y) }
                a.path(width = 0.82f) { m(x + 2.5f, y).l(20.5f, y) }
                a.rect(x - 1.3f, y - 2f, 2.6f, 4f, fill = true)
            }
        }

        WenkuSymbol.Layout -> {
            // 同样三个单元从"侧栏"重排成"页眉 + 两个单元格"；不做整体旋转/缩放。
            val lead = p.coerceIn(-0.12f, 1.0f)
            val follow = q.coerceIn(-0.12f, 1.0f)
            val trail = r.coerceIn(-0.12f, 1.0f)
            a.rect(3.5f, 4f, 5.5f + 11.5f * lead, 16f - 10.5f * lead)
            a.rect(
                3.5f,
                4f,
                5.5f + 11.5f * lead,
                16f - 10.5f * lead,
                fill = true,
                opacity = 0.08f + active * 0.13f,
            )
            a.rect(12f - 8.5f * follow, 4f + 8.5f * follow, 8.5f - 1.5f * follow, 6.5f + follow)
            a.rect(12f + 1.5f * trail, 13.5f - trail, 8.5f - 1.5f * trail, 6.5f + trail)
        }

        WenkuSymbol.Grid -> {
            for (i in 0 until 4) {
                val d = floatArrayOf(p, q, r, q)[i]
                val x = 4f + i % 2 * 10f
                val y = 4f + i / 2 * 10f - d * 1.3f
                a.rect(x, y, 6f, 6f)
                if (i == 0) a.rect(x, y, 6f, 6f, fill = true, opacity = 0.14f + active * 0.12f)
            }
        }

        WenkuSymbol.DotsGrid -> {
            for (i in 0 until 9) {
                val d = floatArrayOf(p, q, r)[i / 3]
                a.mark(
                    4.2f + i % 3 * 7f,
                    4.2f + i / 3 * 7f - d * 1.8f,
                    1.7f,
                    angle = if (i == 4) 0.785f + p * 0.785f else 0f,
                )
            }
        }

        WenkuSymbol.Add, WenkuSymbol.AddCircle, WenkuSymbol.RemoveCircle -> {
            if (pose.symbol != WenkuSymbol.Add) a.ring()
            val length = if (pose.symbol == WenkuSymbol.Add) 6.4f else 3.7f
            a.rotate(p * 0.35f) {
                a.line(12f - length, 12f, 12f + length, 12f)
                if (pose.symbol != WenkuSymbol.RemoveCircle) {
                    a.path(width = 1.13f) { m(12f, 12f - length).l(12f, 12f + length) }
                }
            }
        }

        WenkuSymbol.Check -> {
            a.poly(xy(4.4f, 12.5f, 9.4f, 17.4f + 0.7f * q, 20.2f, 6.3f - 1.2f * p))
            a.mark(18.9f, 5.8f - p, 1.2f, angle = 0.785f)
        }

        WenkuSymbol.Circle -> {
            a.circle(12f, 12f, 8.4f, fill = true, opacity = (v - 1f).coerceIn(0f, 1f) * 0.21f)
            a.ring()
            if (v > 0f) {
                val t = v.coerceIn(0f, 1f)
                val cornerX = 7.5f
                val cornerY = 12.1f
                val middleX = 10.7f
                val middleY = 15.5f + 0.5f * q
                val endX = 16.7f
                val endY = 8.5f - 0.6f * p
                val l1 = sqrt((middleX - cornerX) * (middleX - cornerX) + (middleY - cornerY) * (middleY - cornerY))
                val l2 = sqrt((endX - middleX) * (endX - middleX) + (endY - middleY) * (endY - middleY))
                val d = (l1 + l2) * t
                a.path {
                    m(cornerX, cornerY)
                    if (d <= l1) {
                        val f = d / l1
                        l(cornerX + (middleX - cornerX) * f, cornerY + (middleY - cornerY) * f)
                    } else {
                        val f = (d - l1) / l2
                        l(middleX, middleY)
                        l(middleX + (endX - middleX) * f, middleY + (endY - middleY) * f)
                    }
                }
            }
        }

        WenkuSymbol.Edit -> {
            a.move(0.9f * p, -0.9f * p) {
                a.poly(
                    xy(3.6f, 20.3f, 6.1f, 12.2f, 16f, 3.2f, 20.7f, 7.9f, 11.7f, 17.8f),
                    close = true,
                )
                a.poly(
                    xy(3.6f, 20.3f, 6.1f, 12.2f, 11.7f, 17.8f),
                    close = true,
                    fill = true,
                    opacity = 0.17f,
                )
                a.line(6.8f, 16.8f, 9.4f, 14.2f)
                a.mark(8.9f, 13.5f, 1.1f, angle = 0.785f)
                a.line(13.2f, 5.8f, 18f, 10.6f)
            }
        }

        WenkuSymbol.Delete -> {
            a.path { m(5.5f, 9f).l(6.2f, 20f).l(15.6f, 20f).l(17.7f, 17.8f).l(18.5f, 9f) }
            a.poly(xy(14.6f, 10.7f, 14.2f, 17.4f, 16.4f, 17.4f), close = true, fill = true, opacity = 0.15f)
            a.line(9.3f, 11.2f, 9.7f, 17.1f)
            a.transform(y = -1.65f * p, angle = -0.09f * q, pivotX = 5f, pivotY = 7f) {
                a.line(3.7f, 6.7f, 20.3f, 6.7f)
                a.poly(xy(9f, 6.7f, 9f, 4.1f, 15f, 4.1f, 15f, 6.7f))
            }
        }

        WenkuSymbol.Copy -> {
            a.card(3.5f, 3.2f, 12f, 14f, fold = 2.7f, shade = 0.07f)
            a.move(1.5f * p, -1.9f * q) {
                a.card(8.2f, 7.1f, 12f, 14f, fold = 2.7f, shade = 0.15f)
            }
        }

        WenkuSymbol.Share -> {
            a.poly(xy(4.2f, 11.7f, 4.2f, 18.6f, 6.1f, 20.5f, 19.8f, 20.5f, 19.8f, 11.7f))
            a.line(12f, 15.2f - 0.6f * r, 12f, 3.7f - p)
            a.poly(xy(7.7f, 8f - q, 12f, 3.7f - p, 16.3f, 8f - q))
        }

        WenkuSymbol.Link -> {
            a.move(0.9f * p, -0.9f * p) {
                a.path {
                    m(10f, 7.9f)
                        .l(12.8f, 5.1f)
                        .c(18.2f, -0.2f, 24f, 5.6f, 18.7f, 11f)
                        .l(16.3f, 13.5f)
                }
            }
            a.move(-0.9f * q, 0.9f * q) {
                a.path {
                    m(14f, 16.1f)
                        .l(11.2f, 18.9f)
                        .c(5.8f, 24.2f, 0f, 18.4f, 5.3f, 13f)
                        .l(7.7f, 10.5f)
                }
            }
            a.line(8.6f + 0.8f * r, 15.4f - 0.8f * r, 15.4f - 0.8f * r, 8.6f + 0.8f * r)
        }

        WenkuSymbol.Refresh -> {
            a.rotate(p * PI.toFloat()) {
                a.arc(12f, 12f, 7.8f, 7.8f, -0.6f, PI.toFloat() * 1.77f)
                a.poly(xy(19.1f, 3.5f, 19.1f, 8.3f, 14.3f, 8.3f))
            }
        }

        WenkuSymbol.Sync -> {
            a.rotate(p * PI.toFloat() * 0.8f) {
                a.path { m(4.2f, 9f).c(6f, 1f, 18f, 1f, 19.8f, 9f) }
                a.poly(xy(19.8f, 4.2f, 19.8f, 9f, 15f, 9f))
                a.path { m(19.8f, 15f).c(18f, 23f, 6f, 23f, 4.2f, 15f) }
                a.poly(xy(4.2f, 19.8f, 4.2f, 15f, 9f, 15f))
            }
        }

        WenkuSymbol.Download -> {
            a.poly(xy(3.5f, 16.8f, 3.5f, 18.9f, 5.5f, 20.7f, 20.5f, 20.7f, 20.5f, 16.8f))
            a.line(12f, 3.2f + 2.5f * q, 12f, 14.5f + 2.5f * p)
            a.poly(xy(7.5f, 10f + 2.5f * p, 12f, 14.5f + 2.5f * p, 16.5f, 10f + 2.5f * p))
        }

        WenkuSymbol.CloudDownload -> {
            a.path {
                m(6.4f, 17.3f)
                    .c(-0.5f, 17.3f, 0.7f, 8.2f, 6.7f, 8.5f)
                    .c(6.5f, 1.5f - p, 17.3f, 1.5f - q, 17.8f, 8.4f)
                    .c(23.4f, 8.2f, 24f, 17.3f, 18f, 17.3f)
            }
            a.line(12f, 10.6f + 1.3f * q, 12f, 19.7f + 1.3f * p)
            a.poly(xy(8.8f, 16.5f + 1.3f * p, 12f, 19.7f + 1.3f * p, 15.2f, 16.5f + 1.3f * p))
        }

        WenkuSymbol.Upload -> {
            a.ring()
            a.poly(xy(8f, 11.8f - 1.8f * q, 12f, 7.8f - 1.8f * p, 16f, 11.8f - 1.8f * q))
            a.line(12f, 7.8f - 1.8f * p, 12f, 17.2f)
        }

        WenkuSymbol.ImportFile, WenkuSymbol.Document -> {
            a.card(5f, 3f, 14f, 18f, fold = 4f, shade = 0.11f)
            if (pose.symbol == WenkuSymbol.Document) {
                a.line(8.2f, 11.2f, 15.8f - 2.4f * p, 11.2f)
                a.line(8.2f, 14.7f, 15.8f - 2.4f * q, 14.7f)
                a.line(8.2f, 18.2f, 12.5f - r, 18.2f)
            } else {
                a.line(12f, 10.4f + q, 12f, 17.3f + p)
                a.poly(xy(9f, 14.3f + p, 12f, 17.3f + p, 15f, 14.3f + p))
            }
        }

        WenkuSymbol.Wand -> {
            a.poly(xy(3.8f, 19.5f, 13.4f, 9.9f, 15.5f, 12f, 5.9f, 21.6f), close = true, fill = true, opacity = 0.17f)
            a.poly(xy(3.8f, 19.5f, 13.4f, 9.9f, 15.5f, 12f, 5.9f, 21.6f), close = true)
            a.rotate(p * 0.38f) { a.star(17.4f, 5.8f, 2.5f + 0.9f * q) }
            a.star(5.5f, 6.8f, 1.5f + 0.7f * r)
            a.mark(19.3f, 15.3f, 1.2f, angle = 0.785f)
        }

        WenkuSymbol.Settings -> {
            a.rotate(p * PI.toFloat() / 3f) {
                val points = ArrayList<Float>(72)
                for (i in 0 until 36) {
                    val angle = i * PI.toFloat() / 18f - PI.toFloat() / 2f
                    val radius = if (i % 6 == 0 || i % 6 == 5) 7.1f else 8.9f
                    points.add(12f + radius * cos(angle))
                    points.add(12f + radius * sin(angle))
                }
                a.poly(points.toFloatArray(), close = true)
                a.circle(12f, 12f, 3.3f)
                a.poly(xy(9.7f, 9.7f, 12f, 8.7f, 12f, 15.3f, 9.7f, 14.3f), close = true, fill = true, opacity = 0.25f)
            }
        }

        WenkuSymbol.Info, WenkuSymbol.Help -> {
            a.ring()
            if (pose.symbol == WenkuSymbol.Info) {
                a.mark(11.3f, 6.9f - 1.4f * p, 1.4f)
                a.poly(xy(10.8f, 11f, 12.2f, 11f, 12.2f, 17f, 14f, 17f))
            } else {
                a.path {
                    m(9.1f, 8.2f - p)
                        .c(9.1f, 4.6f - p, 16f, 4.6f - q, 15.1f, 9.3f)
                        .c(14.8f, 10.8f, 12f, 11.1f, 12f, 13.8f)
                }
                a.mark(11.3f, 17f, 1.4f)
            }
        }

        WenkuSymbol.Warning -> {
            a.poly(xy(12f, 3.2f, 21.9f, 20.5f, 4.2f, 20.5f, 2.2f, 18.6f), close = true)
            a.path(width = 1.15f) { m(12f, 8.4f - p).l(12f, 13.4f - p) }
            a.mark(11.2f, 16.6f - 0.5f * q, 1.6f)
        }

        WenkuSymbol.Wifi, WenkuSymbol.WifiOff, WenkuSymbol.WifiWarning -> {
            for (i in 0 until 3) {
                val x = 3.1f + 3f * i
                val y = 8f + i * 3.8f - floatArrayOf(p, q, r)[i] * 1.5f
                a.path { m(x, y).q(12f, y - (6.4f - i * 2.1f), 24f - x, y) }
            }
            a.mark(11.15f, 18.5f, 1.7f, angle = 0.785f)
            if (pose.symbol == WenkuSymbol.WifiOff) a.line(3.2f, 3.5f, 20.8f, 21.1f)
            if (pose.symbol == WenkuSymbol.WifiWarning) {
                a.line(21.2f, 12.4f, 21.2f, 16.3f)
                a.mark(20.6f, 18.8f, 1.2f)
            }
        }

        WenkuSymbol.Cloud -> {
            a.path {
                m(6.4f, 18.6f)
                    .c(-0.3f, 18.6f, 0.5f, 8f, 6.8f, 8.6f)
                    .c(7f, 1.7f - p * 1.5f, 18.2f, 1.7f - q * 1.5f, 18.2f, 9.4f)
                    .c(24f, 10.6f, 22.6f, 18.6f, 18f, 18.6f)
                    .z()
            }
            a.poly(xy(14.4f, 18.6f, 17f, 15.9f, 17f, 18.6f), close = true, fill = true, opacity = 0.23f)
        }

        WenkuSymbol.Globe -> {
            a.ring()
            a.ellipse(12f + 0.9f * p, 12f, 3.9f - 1.8f * q, 8.4f)
            a.line(3.6f, 12f, 20.4f, 12f)
            a.path { m(5.6f, 6.6f).q(12f, 10.3f, 18.4f, 6.6f) }
            a.path { m(5.6f, 17.4f).q(12f, 13.7f, 18.4f, 17.4f) }
        }

        WenkuSymbol.Phone -> {
            a.rotate(p * 0.14f) {
                a.card(6.6f, 2.8f, 10.8f, 18.4f, fold = 2.5f, shade = 0.07f)
                a.line(10f, 5.5f, 13.7f, 5.5f)
                a.line(10.4f, 18.5f, 13.6f, 18.5f)
            }
        }

        WenkuSymbol.Laptop -> {
            a.card(4.5f, 4.2f + 2.8f * p, 15f, 12.2f - 2.8f * p, fold = 2.6f, shade = 0.08f)
            a.poly(xy(4.5f, 16.4f, 2.3f, 20.2f, 21.7f, 20.2f, 19.5f, 16.4f))
            a.line(10f, 18.2f, 14f, 18.2f)
        }

        WenkuSymbol.Branch -> {
            a.line(7f, 6.5f, 7f, 17.5f)
            a.path { m(7f, 14f).c(7f, 10f, 17f + 1.5f * p, 13f, 17f + 1.5f * p, 6.5f) }
            a.card(5.2f, 2.8f, 3.6f, 3.6f, fold = 1f, shade = 0.15f)
            a.card(15.2f + 1.5f * p, 2.8f, 3.6f, 3.6f, fold = 1f, shade = 0.15f)
            a.card(5.2f, 17.6f, 3.6f, 3.6f, fold = 1f, shade = 0.15f)
        }

        WenkuSymbol.PersonAdd -> {
            a.circle(9.2f, 7f - 1.4f * p, 3.1f)
            a.path {
                m(3.3f, 20f)
                    .l(3.3f, 18f)
                    .c(3.3f, 12f - q, 15.1f, 12f - q, 15.1f, 18f)
                    .l(15.1f, 20f)
            }
            a.transform(angle = p * PI.toFloat() / 2f, pivotX = 19f, pivotY = 10f) {
                a.line(19f, 7.2f, 19f, 12.8f)
                a.line(16.2f, 10f, 21.8f, 10f)
            }
        }

        WenkuSymbol.Eye -> {
            val aperture = (1f - p.coerceIn(0f, 1.15f) * 0.80f).coerceIn(0.08f, 1.3f)
            a.path {
                m(2.7f, 12f)
                    .c(7f, 12f - 8f * aperture, 17f, 12f - 8f * aperture, 21.3f, 12f)
                    .c(17f, 12f + 8f * aperture, 7f, 12f + 8f * aperture, 2.7f, 12f)
                    .z()
            }
            a.ellipse(12f, 12f, 2.9f, 2.9f * aperture)
            a.circle(12f, 12f, 0.9f * aperture, fill = true)
            if (v > 0f) {
                a.line(4f, 4f, 4f + v.coerceIn(0f, 1f) * 16f, 4f + v.coerceIn(0f, 1f) * 16f, opacity = v.coerceIn(0f, 1f))
            }
        }

        WenkuSymbol.Battery -> {
            a.card(2.8f, 7f, 16.5f, 10f, fold = 1.7f, shade = 0f)
            a.line(21.8f, 10f, 21.8f, 14f)
            a.rect(5.2f, 9.4f, 11.7f, 5.2f, radius = 0.2f, fill = true)
        }

        WenkuSymbol.Charging -> {
            a.poly(
                xy(13.1f, 2.7f, 5.3f, 12.8f, 11.2f, 12.8f, 10.6f, 21.3f, 18.7f, 10.2f, 12.8f, 10.2f),
                close = true,
                fill = true,
            )
        }

        WenkuSymbol.Tools -> {
            a.transform(angle = p * 0.17f, scale = 0.94f) {
                a.path {
                    m(13f, 5.1f)
                        .c(14.1f, 2.5f, 16.8f, 2f, 19f, 3f)
                        .l(15.5f, 6.5f)
                        .l(17.5f, 8.5f)
                        .l(21f, 5f)
                        .c(23.1f, 10f, 18.7f, 13.1f, 15.3f, 11.8f)
                        .l(7.1f, 20f)
                        .c(4.2f, 22.9f, 1.1f, 19.8f, 4f, 16.9f)
                        .l(12.3f, 8.6f)
                        .q(11.5f, 7f, 13f, 5.1f)
                }
            }
        }

        WenkuSymbol.Shield -> {
            a.path {
                m(12f, 2.9f)
                    .l(19.7f, 5.5f)
                    .l(18.7f, 14f)
                    .q(17.5f, 18.6f, 12f, 21.2f)
                    .q(6.5f, 18.6f, 5.3f, 14f)
                    .l(4.3f, 5.5f)
                    .z()
            }
            a.poly(xy(8f, 11.7f + q, 10.8f, 14.4f + 0.5f * q, 16f, 9.2f - p))
        }

        // 变体符号必须先经 WenkuIconPoses.of 归一化，绘制层不直接处理它们。
        WenkuSymbol.Back,
        WenkuSymbol.Up,
        WenkuSymbol.Down,
        WenkuSymbol.ArrowLeft,
        WenkuSymbol.ArrowUp,
        WenkuSymbol.ArrowDown,
        WenkuSymbol.External,
        WenkuSymbol.Bookmarked,
        WenkuSymbol.LocationFilled,
        WenkuSymbol.Pause,
        WenkuSymbol.CheckCircle,
        WenkuSymbol.Selected,
        WenkuSymbol.EyeOff -> error("Normalize variants before drawing")

        // 以上 76 个族符号 + 13 个变体符号 = 89，编译器在此校验穷尽性。
    }
    return a.strokes
}
