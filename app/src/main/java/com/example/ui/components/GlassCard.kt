package com.example.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import me.trishiraj.shadowglow.consistentShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 性能优化用的装饰层签名：尺寸/按压/主题色任一变化才重建离屏层。
 * 密度与字体缩放折算进键中，滚动等纯位移场景完全命中缓存。
 */
private data class GlassDecoKey(
    val width: Float,
    val height: Float,
    val pressed: Boolean,
    val primary: Color,
    val secondary: Color,
    val shape: Shape,
    val density: Float,
    val fontScale: Float,
    val qualityId: Int,
    val tintMix: Float,
    /** 毛玻璃厚度归一值（−1 更薄 … 0 默认 22dp … +1 最厚），驱动光路弥散；不随厚度变化的层传 0 */
    val thickness: Float
)

/** 按比例缩放某个圆角尺寸（支持 dp/px/percent 三种来源）。 */
private class ScaledCornerSize(private val base: CornerSize, private val factor: Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: androidx.compose.ui.unit.Density): Float =
        base.toPx(shapeSize, density) * factor
}

/**
 * 压痕轮廓（单位域）—— 指腹接触斑形态：
 *  低频瓣状起伏（2/3 次谐波 ±6%/±4%，皮肤软组织形变的主频成分）
 *  + 高频微扰白噪声 ±3%，能量集中低频、贴近真实接触边界的扰动谱；
 *  再做长短轴比 0.87 的椭圆各向异性（轴向每次按压弱随机），消除"圆规圆"。
 * 24 点极坐标采样，相邻点以「顶点作控制点、中点作锚点」的二次贝塞尔衔接，
 * 视觉等价于 View 方案建议的 CornerPathEffect 圆角化，且不需要额外 Paint。
 * 每次按下（DOWN）传入新 seed 重建一次并缓存，同一次按压内形状固定不变。
 */
private fun buildIndentUnitPath(seed: Long): Path {
    val points = 24
    val random = kotlin.random.Random(seed)
    val phaseLobe1 = random.nextFloat() * 2f * PI_F
    val phaseLobe2 = random.nextFloat() * 2f * PI_F
    val microAmplitude = 0.03f
    // 各向异性：指腹接触斑近似椭圆（短/长轴 ≈ 0.87），轴向 ±0.4rad 弱随机
    val axisAngle = (random.nextFloat() - 0.5f) * 0.8f
    val cosA = kotlin.math.cos(axisAngle)
    val sinA = kotlin.math.sin(axisAngle)
    val px = FloatArray(points)
    val py = FloatArray(points)
    for (i in 0 until points) {
        val angle = (i.toFloat() / points.toFloat()) * 2f * PI_F
        val r = 1f +
            0.06f * kotlin.math.sin(2f * angle + phaseLobe1) +
            0.04f * kotlin.math.sin(3f * angle + phaseLobe2) +
            (random.nextFloat() - 0.5f) * 2f * microAmplitude
        val xr = kotlin.math.cos(angle) * r
        val yr = kotlin.math.sin(angle) * r
        // 绕原点旋转轴向并对一根轴压扁成椭圆
        px[i] = xr * cosA - yr * sinA
        py[i] = (xr * sinA + yr * cosA) * 0.87f
    }
    fun midX(i: Int) = (px[i] + px[(i + 1) % points]) / 2f
    fun midY(i: Int) = (py[i] + py[(i + 1) % points]) / 2f
    val path = Path()
    path.moveTo(midX(points - 1), midY(points - 1))
    for (i in 0 until points) {
        val j = (i + 1) % points
        path.quadraticBezierTo(px[j], py[j], midX(i), midY(i))
    }
    path.close()
    return path
}

/** π 的浮点常量（避免引 kotlin.math.PI 全限定名的重复书写）。 */
private const val PI_F = Math.PI.toFloat()

/** 全局固定光源方向（任务书「光源固定修正」指定）：恒为屏幕上方偏后，全 App 所有光照效果共用同一光源保证自洽。 */
private const val LIGHT_DIR_X = 0.0f
private const val LIGHT_DIR_Y = -0.6f

/** 环境光底（任务书建议 0.75~0.85 取中）：把整体明暗压缩到 ~8% 以内的克制观感。 */
private const val DENT_AMBIENT = 0.8f

/**
 * AGSL 着色器（仅 API 33+ 挂载，任务书「光源固定修正」实现）：
 * 明暗 = 固定光源 × 倾斜后表面法线（整卡）＋ 坑壁法线扰动（局部），纯数学、无分支无循环；
 * CPU 每帧只传原始角度弧度，不重复算法线。卡片不倾斜、坑外区域明暗退化为 <1% 常量底噪。
 * 对称性由公式自带：左右按压的明暗镜像对称，无需任何方向特判。
 */
private const val DENT_LIGHT_AGLS = """
    uniform shader inputShader;
    uniform float2 pressCenter;
    uniform float pressRadius;
    uniform float pressIntensity;
    uniform float cardRotationX;
    uniform float cardRotationY;
    uniform float2 lightDir;
    uniform float ambientStrength;

    half4 main(float2 coord) {
        float4 base = float4(inputShader.eval(coord));

        // ① 整卡倾斜后的表面法线：Z 轴单位法线绕 X / Y 旋转（小角模型）。
        //    符号与 Compose 旋转矩阵严格对齐：rotationX>0 = 顶缘向屏幕里倒 → 法线向屏幕上方
        //    （−y）偏，故 X 分量取负 sin；rotationY>0 = 右缘向里倒 → 法线 +x，取正 sin
        float3 tiltNormal = normalize(float3(
            sin(cardRotationY),
            -sin(cardRotationX),
            cos(cardRotationX) * cos(cardRotationY)
        ));

        // ② 压痕局部法线扰动：径向距离 t，坑壁斜率 sin(t·π)×intensity×0.6（任务书公式）；
        //    step 做坑外裁剪，保持无分支
        float2 rel = (coord - pressCenter) / max(pressRadius, 1.0);
        float t = length(rel);
        float inside = 1.0 - step(1.0, t);
        float wall = sin(t * 3.14159265) * pressIntensity * 0.6 * inside;
        float2 radialDir = rel / max(t, 0.0001);
        float3 surfaceNormal = normalize(tiltNormal + float3(radialDir * wall, 0.0));

        // ③ Lambert：固定光源 × 表面法线
        float3 L = normalize(float3(lightDir.x, lightDir.y, 0.7));
        float lighting = max(dot(surfaceNormal, L), 0.0);

        // ④ 明暗合成（任务书系数）：全局倾斜 ×0.08、局部坑壁 ×0.6，环境光底压缩抬底
        float shade = 1.0 - lighting;
        float darken = clamp(shade * 0.08 + max(1.0 - t, 0.0) * shade * 0.6, 0.0, 0.35);
        float lit = 1.0 - darken * (1.0 - ambientStrength);
        return half4(base.rgb * lit, base.a);
    }
"""

/**
 * 把更新完 uniform 的着色器包装为图层 RenderEffect。
 * 调用点位于 shader 非空分支内（shader 仅在 API 33+ 构造成功时非空）。
 */
@androidx.annotation.RequiresApi(33)
private fun dentRenderEffect(shader: RuntimeShader): androidx.compose.ui.graphics.RenderEffect =
    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
        .asComposeRenderEffect()

/**
 * 「卡片圆角」滑条语义 = 全局圆角缩放：
 * 默认锚点 16dp，滑到 2dp 所有卡片趋近直角，48dp 明显圆润；
 * 显式传 shape 的调用方（如 24dp 顶栏）按同比例缩放，保证整页联动。
 */
private fun rescaleCorners(shape: Shape?, cornerRadiusDp: Float): Shape {
    val factor = cornerRadiusDp / 16f
    return when (shape) {
        null -> RoundedCornerShape(cornerRadiusDp.dp)
        is RoundedCornerShape -> if (factor == 1f) shape else shape.copy(
            topStart = ScaledCornerSize(shape.topStart, factor),
            topEnd = ScaledCornerSize(shape.topEnd, factor),
            bottomEnd = ScaledCornerSize(shape.bottomEnd, factor),
            bottomStart = ScaledCornerSize(shape.bottomStart, factor)
        )
        else -> shape
    }
}

private fun DrawScope.glassDecoKey(
    pressed: Boolean,
    primary: Color,
    secondary: Color,
    shape: Shape,
    quality: RenderQuality,
    tintMix: Float,
    thickness: Float
): GlassDecoKey = GlassDecoKey(
    width = size.width,
    height = size.height,
    pressed = pressed,
    primary = primary,
    secondary = secondary,
    shape = shape,
    density = density,
    fontScale = fontScale,
    qualityId = quality.id,
    tintMix = tintMix,
    thickness = thickness
)

/**
 * 旗舰级液态玻璃卡片（Liquid Glass Card Ultimate）：
 *
 * 视觉层级：
 *  1. 真实内容采样磨砂（Backdrop Blur & Vibrancy）——背景层可用时采样底层并施加高斯虚化与色彩饱和度增强；
 *  2. 对角镜面光束 + 顶棱倒角聚光带 + 底部次表面焦散色散光晕；
 *  3. 水晶棱镜彩虹描边 + 物理倒角内凹高光边；
 *  4. 双层浮空物理柔光投影。
 *
 * MAX 极致档交互特效：
 *  A2 极光呼吸辉光；
 *  B1 整卡刚体倾斜（任务书「整卡倾斜」跷跷板模型）：触点归一化偏移 × tiltMaxDeg 绕对应轴
 *     倾斜（按偏左 → 整卡向左下沉、按正中 → 倾角≈0），Medium 刚度 + 低弹回正；
 *     叠加列表滚动惯性倾斜（ScrollTiltController 全局信号，停止后缓慢回正），
 *     两路之和钳制（按压上限+滚动上限）且按压优先；cameraDistance 与 C1 反向视差共用
 *     「立体透视强度」参数；
 *  B2 顶棱高光随触点偏移（静态几何）；材质压痕层（随机 seed 扰动轮廓 + 径向渐变，
 *  范围全程固定，色差仅为卡片底色的 HSL 明度下移 ≤20%），圆心实时跟随手指，
 *  按下硬弹簧干脆形成、松手软弹簧原地缓慢回弹；
 *  光源固定（任务书「光源固定修正」）：全局光源常量 LIGHT_DIR 恒在屏幕上方偏后，压痕明暗
 *  方向由「固定光源 × 倾斜后表面法线」实时算出——API 33+ 经 AGSL RuntimeShader 逐像素
 *  法线光照（整卡倾斜暗化 + 坑壁方向性明暗随倾斜联动），API<33 回退为倾斜角驱动渐变中心
 *  偏移。左右按压镜像对称由公式自带。C2 入场 spring 弹入。
 *  （曾经的 A3 触点涟漪扩散层已按"按压不得出现任何扩大圆环"的验收标准整体移除，
 *   触控反馈只保留静态凹陷 + 静态高光随动， CardTweaks.rippleAlpha 因此停用。）
 *
 * 所有可调数值来自 [LocalCardTweaks]（设置页「自定义卡片参数」实时写入并持久化）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    /** 显式传入时按调用方的形状；不传时使用「自定义卡片参数」里的圆角值（默认 16dp）。 */
    shape: Shape? = null,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (LocalRenderQuality.current.minimal) {
        val flatShape = shape ?: RoundedCornerShape(16.dp)
        Column(modifier.clip(flatShape).background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding), content = content)
        return
    }
    val tweaks = LocalCardTweaks.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    // 任务书二：白色半透明基底叠加低透明度主题色调（默认 8%，滑块 0~30% 可调）
    val themedTint = remember(tint, primary, tweaks.tintMix) {
        lerp(tint, primary, tweaks.tintMix.coerceIn(0f, 1f))
    }
    // 任务书二：描边由纯白改为「白 × 主题色」混合
    val strokeTint = remember(primary) { lerp(Color.White, primary, 0.38f) }
    val prismColors = rememberCrystalPrismColors()
    val backdrop = LocalGlassBackdrop.current
    val preBlurred = LocalPreBlurredGlass.current
    val quality = LocalRenderQuality.current
    val reduceMotion = com.example.ui.feedback.LocalReduceMotion.current
    val glassDensity = androidx.compose.ui.platform.LocalDensity.current
    val cardBlurRadiusPx = remember(glassDensity, tweaks.blurRadiusDp) {
        with(glassDensity) { tweaks.blurRadiusDp.dp.toPx() }
    }

    val effectiveShape = remember(shape, tweaks.cornerRadiusDp) { rescaleCorners(shape, tweaks.cornerRadiusDp) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by if (onClick != null) interactionSource.collectIsPressedAsState() else remember { mutableStateOf(false) }

    // 极致档专属：按压凝胶弹性缩放（其余档位恒为 1，无任何开销）
    val pressScale by animateFloatAsState(
        targetValue = if (!reduceMotion && isPressed) 0.98f else 1f,
        animationSpec = com.example.ui.feedback.AppMotion.press(),
        label = "glassPressScale"
    )

    /* ═══ MAX 完整包（A2/B1/B2/C1/C2 + 压力形变）共用状态 ═══ */
    val isMax = quality == RenderQuality.MAX && !reduceMotion
    val tertiary = MaterialTheme.colorScheme.tertiary

    // A2：极光呼吸相位 —— 【已移除】
    // 原实现在此为每张卡（含非 MAX 档）创建一个 `rememberInfiniteTransition("auroraCard")`
    // 并跑一条 3800ms 的无限动画，但产生的 `auroraBreath` 在全工程内**没有任何读取点**
    // （即渲染结果完全不受它影响）。它唯一的作用是为每张卡片长期挂着一条逐帧协程 +
    // 一个动画器实例。删除它对像素输出零影响，是纯收益。

    // B1/压力形变：触点追踪（按下位置 + 归一化偏移 −1..1）
    var pressPos by remember { mutableStateOf(Offset.Zero) }
    var pressNorm by remember { mutableStateOf(Offset.Zero) }

    // 按压门控：触点观察器仅在 MAX 档挂载，handDown 天然只在 MAX 为真。
    // 落卡即响应（压痕/倾斜/顶棱高光），松手后各自动画缓慢收尾 —— 划过卡片也是一次真实的轻按。
    var handDown by remember { mutableStateOf(false) }
    // 按压起始时刻：松手时按保持时长衰减回弹刚度（应力松弛的廉价模拟）
    var pressStartAt by remember { mutableStateOf(0L) }

    // 压力形变量 = 「深度强度」（任务书 §5）：驱动的是凹陷深浅，绝不是半径。
    // 按下硬弹簧（高刚度、无回弹）干脆落底；松手软弹簧缓慢原地回弹，
    // 且按住越久刚度越低（应力松弛：持久压痕恢复更慢的粘弹性特征）。
    val pressAnim = remember { Animatable(0f) }
    LaunchedEffect(handDown) {
        if (handDown) {
            pressAnim.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
            )
        } else if (pressAnim.value > 0f) {
            val heldMs = (System.currentTimeMillis() - pressStartAt).coerceAtLeast(0L)
            val relaxT = ((heldMs - 300L).coerceAtLeast(0L) / 600f).coerceIn(0f, 1f)
            val relaxStiffness = 200f - 110f * relaxT   // StiffnessLow(200) → 短按不变、久压降至 ~90
            pressAnim.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = relaxStiffness)
            )
        }
    }

    // B1：倾斜角经 spring 追踪触点 —— 跷跷板模型（任务书「整卡倾斜」§1）：
    // 触点归一化偏移 × tiltMaxDeg 绕对应轴倾斜（按偏左 → 整卡向左下沉、按正中 → 倾角≈0），
    // 手感规格 STIFFNESS_MEDIUM + LOW_BOUNCY：响应快、松手回正带轻微回弹不生硬
    val tiltSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val tiltX by animateFloatAsState(
        targetValue = if (isMax && handDown) -pressNorm.y * tweaks.tiltMaxDeg else 0f,
        animationSpec = tiltSpec,
        label = "cardTiltX"
    )
    val tiltY by animateFloatAsState(
        targetValue = if (isMax && handDown) pressNorm.x * tweaks.tiltMaxDeg else 0f,
        animationSpec = tiltSpec,
        label = "cardTiltY"
    )

    // 滚动惯性倾斜信号（任务书「整卡倾斜」§2）：全局共享实例，graphicsLayer 内延迟读取
    val scrollTilt = LocalScrollTilt.current

    // AGSL 着色器（仅 MAX 档 + API 33+）：「固定光源 × 倾斜后法线」逐像素光照，
    // 组合期构造一次，按压期间仅更新 uniform（CPU 不重复算法线）。
    // runCatching：个别 OEM ROM 的 SkSL 编译器若拒绝该源码，静默回退到 Canvas 压痕方案，
    // 绝不让着色器构造失败炸掉整棵组合树（启动看门狗也无法救组合期崩溃）
    val indentShader = remember(isMax) {
        if (isMax && Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(DENT_LIGHT_AGLS) }.getOrNull()
        } else null
    }

    // v3 深度线索：按压倾斜时环境光阴影随之抬升/增强 —— 真实 3D 手感的最大来源。
    // 倾角越大（即手指越靠边）卡片"离桌面越高"，投影范围与强度同步增长。
    val tiltMag = (abs(tiltX) + abs(tiltY)).coerceAtMost(tweaks.tiltMaxDeg * 2f)
    val pressLift by animateFloatAsState(
        targetValue = if (isMax && handDown) 12f + tiltMag * 1.2f else 12f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "cardPressLift"
    )

    val entrance = 1f

    // Layer 2/3/4（物理光路）与 Layer 5/6（棱镜描边 + 内倒角）的预录缓存层
    val lightPathLayer = rememberGraphicsLayer()
    var lightPathKey by remember { mutableStateOf<GlassDecoKey?>(null) }
    val edgeLayer = rememberGraphicsLayer()
    var edgeKey by remember { mutableStateOf<GlassDecoKey?>(null) }

    // 压痕状态：seed 仅在手指落下（DOWN）时刷新 → 轮廓每按一次重建一次并缓存，
    // 同一次按压过程中 Path 固定不变，绘制期零重建（任务书 §3/§6.1）。
    // 注意：此随机仅用于视觉扰动噪声，与安全无关。
    var indentSeed by remember { mutableStateOf(System.currentTimeMillis()) }
    val dentOutline = remember(indentSeed) { buildIndentUnitPath(indentSeed) }
    // HSL 转换缓冲：组合期分配一次，绘制期每帧复用（避免按压动画期间逐帧小分配）
    val hslBuf = remember { FloatArray(3) }

    Column(
        modifier = modifier
            // C2 入场 + 卡片透明度 + B1 3D 倾斜
            .graphicsLayer {
                val e = if (isMax) entrance else 1f
                alpha = e * tweaks.cardAlpha
                translationY = (1f - e) * 12.dp.toPx()
                scaleX = pressScale * (0.96f + 0.04f * e)
                scaleY = pressScale * (0.96f + 0.04f * e)
                if (isMax) {
                    // 按压倾斜 + 滚动惯性叠加，总和钳制（任务书 §3）：
                    // 上限 = 滑条按压上限 + 滚动惯性上限，保证「3D 倾斜最大角度」滑条
                    // 拉满时 15° 本身不被静默削顶，只钳两路叠加的超出部分。
                    // 按压优先：手指按在卡上时滚动信号不参与，两路动效不打架
                    val scrollTiltDeg = if (handDown) 0f else scrollTilt.tiltDeg.floatValue
                    val maxTotal = tweaks.tiltMaxDeg + ScrollTiltController.SCROLL_TILT_MAX_DEG
                    rotationX = (tiltX + scrollTiltDeg).coerceIn(-maxTotal, maxTotal)
                    rotationY = tiltY.coerceIn(-maxTotal, maxTotal)
                    // 相机距离与卡体尺寸绑定（设备无关的物理光学基准）：
                    // 「平面↔立体」滑条把距离从 4.2×短边 收紧到 0.42×短边 ——
                    // 近端卡片随倾斜产生明显的近大远小，远端几乎无透视畸变。
                    // 整卡倾斜与 AGSL 光照共用这一套透视（任务书 §5.4，不另造相机）
                    val shortSide = min(size.width, size.height).coerceAtLeast(1f)
                    cameraDistance = shortSide *
                        (0.42f + 3.8f * ((tweaks.cameraDistMult - 3f) / 9f).coerceIn(0f, 1f))

                    // 固定光源法线光照（任务书「光源固定修正」）：API 33+ 挂 AGSL RuntimeShader，
                    // 由「固定光源 × 倾斜后表面法线」实时算明暗。仅按压深度可见期间挂载，
                    // 松手收尾后立即卸载，无按压时零 GPU 开销。
                    // 本块读的 pressAnim/tiltX/tiltY/handDown 均为延迟读取：只重建图层参数不触发重组
                    val indentVal = pressAnim.value.coerceIn(0f, 1f)
                    val strengthMult = tweaks.pressStrength.coerceIn(0f, 2f)
                    val shader = indentShader
                    if (Build.VERSION.SDK_INT >= 33 && shader != null && strengthMult > 0f && indentVal > 0.004f) {
                        val cx = if (pressPos == Offset.Zero) size.width / 2f
                                 else pressPos.x.coerceIn(0f, size.width)
                        val cy = if (pressPos == Offset.Zero) size.height / 2f
                                 else pressPos.y.coerceIn(0f, size.height)
                        val rSafe = tweaks.pressRadius.coerceIn(0.25f, 2.5f)
                        val rPx = min(shortSide * 0.52f * rSafe, shortSide * 0.78f)
                        shader.setFloatUniform("pressCenter", cx, cy)
                        shader.setFloatUniform("pressRadius", rPx)
                        shader.setFloatUniform("pressIntensity", (indentVal * strengthMult).coerceIn(0f, 1f))
                        // 任务书：传原始角度换算弧度即可，法线在 shader 内计算
                        val degToRad = PI_F / 180f
                        shader.setFloatUniform("cardRotationX", rotationX * degToRad)
                        shader.setFloatUniform("cardRotationY", rotationY * degToRad)
                        shader.setFloatUniform("lightDir", LIGHT_DIR_X, LIGHT_DIR_Y)
                        shader.setFloatUniform("ambientStrength", DENT_AMBIENT)
                        renderEffect = dentRenderEffect(shader)
                    } else {
                        renderEffect = null
                    }
                } else {
                    // 画质档切换残留防线：离开 MAX 档时清掉本图层此前挂上的效果与旋转
                    rotationX = 0f
                    rotationY = 0f
                    renderEffect = null
                }
            }
            // B1/压力形变触点追踪（MAX 档挂载；不消费事件，纯观察）
            .then(
                if (isMax) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            handDown = true
                            indentSeed = System.currentTimeMillis()
                            pressStartAt = indentSeed
                            pressPos = down.position
                            // 归一化偏移钳制在 ±1：长按拖出卡外时倾斜/视差不随距离无限放大
                            pressNorm = Offset(
                                (((down.position.x / size.width) - 0.5f) * 2f).coerceIn(-1f, 1f),
                                (((down.position.y / size.height) - 0.5f) * 2f).coerceIn(-1f, 1f)
                            )
                            var consumedStreak = 0
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    // 滚动门控：列表开始消费移动事件（滚动接管）后立即熄灭全套按压
                                    // 特效 —— 划过/拖动列表不再被误判为"按压卡片"
                                    if (ev.changes.any { it.isConsumed }) consumedStreak++ else consumedStreak = 0
                                    if (consumedStreak >= 3) break
                                    val ch = ev.changes.firstOrNull() ?: break
                                    if (!ch.pressed) break
                                    pressPos = ch.position
                                    pressNorm = Offset(
                                        (((ch.position.x / size.width) - 0.5f) * 2f).coerceIn(-1f, 1f),
                                        (((ch.position.y / size.height) - 0.5f) * 2f).coerceIn(-1f, 1f)
                                    )
                                }
                            } finally {
                                // 抬手/指针离场/手势取消/滚动接管/协程异常 → 触点全部归还（特效按各自恢复曲线回落）
                                handDown = false
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (quality <= RenderQuality.LOW) {
                    // 流畅档：单层轻阴影（双层 HWUI 投影是低端机大项），并补一层保持滚动隔离
                    Modifier.graphicsLayer { }
                        .consistentShadow(
                            elevation = 4.dp,
                            shape = effectiveShape,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.12f)
                        )
                } else {
                    Modifier
                        // Layer 7A: 宽域环境扩散彩色柔光（Atmospheric Bloom）；极致档更浓
                        .consistentShadow(
                            elevation = pressLift.dp,
                            shape = effectiveShape,
                            ambientColor = primary.copy(alpha = if (quality == RenderQuality.MAX) 0.16f else 0.10f),
                            spotColor = primary.copy(
                                alpha = if (quality == RenderQuality.MAX) (0.20f + 0.10f * tiltMag / 12f).coerceAtMost(0.30f) else 0.14f
                            )
                        )
                        // Layer 7B: 近距离接触暗部阴影（Ambient Occlusion）
                        .consistentShadow(
                            elevation = 4.dp,
                            shape = effectiveShape,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.15f)
                        )
                }
            )
            // MAX 档：A2 极光呼吸辉光（secondary 与 tertiary 混合 → 彩色主题色点缀）
            .then(
                if (isMax) {
                    val auroraSecondary = Color(
                        red = secondary.red + (tertiary.red - secondary.red) * 0.45f,
                        green = secondary.green + (tertiary.green - secondary.green) * 0.45f,
                        blue = secondary.blue + (tertiary.blue - secondary.blue) * 0.45f,
                        alpha = secondary.alpha
                    )
                    Modifier.maxCardAura(primary = primary, secondary = auroraSecondary)
                } else {
                    Modifier
                }
            )
            .clip(effectiveShape)
            // Layer 1: 真实背景采样模糊（高/极致）｜半透明基底（流畅/均衡，不做实时模糊）
            .then(
                when {
                    quality <= RenderQuality.MID -> Modifier.background(
                        if (quality <= RenderQuality.LOW) themedTint.copy(alpha = 0.92f)
                        else themedTint.copy(alpha = 0.78f)
                    )
                    preBlurred != null && quality >= RenderQuality.HIGH -> Modifier.liquidGlassStatic(
                        backdrop = preBlurred,
                        shape = effectiveShape,
                        surfaceColor = themedTint.copy(alpha = 0.70f),
                        blurRadiusPx = cardBlurRadiusPx
                    )
                    backdrop != null -> Modifier.liquidGlass(
                        backdrop = backdrop,
                        shape = effectiveShape,
                        surfaceColor = themedTint.copy(alpha = 0.70f),
                        blurRadius = tweaks.blurRadiusDp.dp,
                        // 极致档开启 AGSL 折射透镜（API<33 由 vendor 自动跳过）。
                        // 参数已驯化（10/18dp），配合三重安全网：
                        // ① vendor 构建异常 try/catch 降级；② 启动看门狗连崩两次自动回"高"；
                        // ③ 折射仅作用于被裁剪的边缘环，主体模糊不受影响。
                        refraction = quality == RenderQuality.MAX,
                        refractionHeight = if (quality == RenderQuality.MAX) 10.dp else 16.dp,
                        refractionAmount = if (quality == RenderQuality.MAX) 18.dp else 28.dp,
                        saturation = if (quality == RenderQuality.MAX) 1.45f else 1.30f
                    )
                    else -> Modifier.background(themedTint)
                }
            )
            // 极致档专属：每 ~6s 一道柔和光带斜向掠过卡面（被卡片圆角自动裁剪）
            .then(if (quality == RenderQuality.MAX) Modifier.glassSheen() else Modifier)
            // MAX 档：珠光微光层（ShimmerFy 思路）
            .then(
                if (quality == RenderQuality.MAX) {
                    Modifier.shimmerPearl(baseColor = primary)
                } else {
                    Modifier
                }
            )
            // MAX 压力形变层（任务书四，静态凹陷）+ 顶棱高光随动（B2）
            .drawWithContent {
                drawContent()

                val minDim = min(size.width, size.height)

                /* ── 四、手指按压"材质压痕"（任务书规格版：静态凹陷 ≠ 水波） ──
                 * ① 范围固定：半径 R 只由「压力形变半径」参数决定，按压全程不变，
                 *    绝不做任何"半径随时间增大"的扩散逻辑；
                 * ② 只有 [pressAnim] 承载的深度 intensity 在动（按下硬弹簧/松手软弹簧），
                 *    手指移动时压痕整体跟随触点平移，按住不动时形状与范围完全静止；
                 * ③ 颜色基于卡片底色 themedTint 做 HSL 明度下移（≤20%），无纯黑纯白端点；
                 * ④ 渐变为单调凹面衰减：中心最深向外连续过渡到透明，无环形波带，
                 *    且在到达轮廓前已衰减至 ~4%，等值线随扰动轮廓走，肉眼无分界线。 */
                // 松手软弹簧带轻微低弹过冲，钳制防止出现负强度（负 alpha / 反向提亮）
                val indentIntensity = pressAnim.value.coerceIn(0f, 1f)
                val strengthMult = tweaks.pressStrength.coerceIn(0f, 2f)
                if (isMax && indentIntensity > 0.004f && strengthMult > 0f) {
                    val cx = if (pressPos == Offset.Zero) size.width / 2f
                             else pressPos.x.coerceIn(0f, size.width)
                    val cy = if (pressPos == Offset.Zero) size.height / 2f
                             else pressPos.y.coerceIn(0f, size.height)
                    // 静态物理基准半径（任务书 §2）：整个按压过程保持不变；本地防线防脏数据致 R≤0
                    val pressRadiusSafe = tweaks.pressRadius.coerceIn(0.25f, 2.5f)
                    val R = min(minDim * 0.52f * pressRadiusSafe, minDim * 0.78f)

                    // 任务书 §4：HSL 明度位移公式 lightnessDelta = −0.12 × intensity × 强度倍率；
                    // 位移总量钳制在 −20% 以内（任务书口径），滑条拉满也不越界；
                    // 转换缓冲组合期分配一次、绘制期每帧复用，零逐帧分配
                    val lightnessDelta = (-0.12f * indentIntensity * strengthMult).coerceAtLeast(-0.20f)
                    androidx.core.graphics.ColorUtils.colorToHSL(themedTint.toArgb(), hslBuf)
                    hslBuf[2] = (hslBuf[2] + lightnessDelta).coerceIn(0f, 1f)
                    val dentColor = Color(androidx.core.graphics.ColorUtils.HSLToColor(hslBuf))
                    // View 方案画在实心底上可直接用不透明色；玻璃卡是半透明材质，
                    // 需把同等深度换算为叠加 alpha（这是平台差异的必要移植，非新增视觉语义）
                    val peakAlpha = (0.21f * indentIntensity * strengthMult).coerceAtMost(0.46f)

                    withTransform({
                        translate(cx, cy)
                        scale(R, R, pivot = Offset.Zero)
                    }) {
                        drawPath(
                            path = dentOutline,
                            brush = Brush.radialGradient(
                                // 单调凹面衰减 + 向心收缩：能量集中到被指尖遮挡的核心，
                                // 晕环淡而窄，靠近轮廓处已 <2%，肉眼无任何分界线（清单 #3）
                                colorStops = arrayOf(
                                    0f to dentColor.copy(alpha = peakAlpha),
                                    0.42f to dentColor.copy(alpha = peakAlpha * 0.36f),
                                    0.72f to dentColor.copy(alpha = peakAlpha * 0.12f),
                                    0.92f to dentColor.copy(alpha = peakAlpha * 0.02f),
                                    1f to Color.Transparent
                                ),
                                // 光源固定修正（任务书）：全局光源 LIGHT_DIR 恒在屏幕上方，
                                // 旧“左上 45° 光向”写死已废除，暗核方向分两档——
                                // API 33+：坑壁方向性明暗由 AGSL 按「固定光源 × 倾斜法线」逐像素
                                //   实时算出（暗壁恒在光源背侧=下壁，随倾斜联动），渐变暗核只需
                                //   恒定偏向光源背侧（略向下）与之同向叠加，左右按压天然镜像；
                                // API<33 回退（任务书指定方案）：无着色器，改用实时倾斜角驱动
                                //   中心偏移，暗核偏向「沉入侧」，倾斜方向即明暗方向
                                center = if (indentShader != null) {
                                    Offset(0f, 0.08f)
                                } else {
                                    Offset(
                                        (tiltY / tweaks.tiltMaxDeg.coerceAtLeast(0.01f))
                                            .coerceIn(-1f, 1f) * 0.10f,
                                        (-tiltX / tweaks.tiltMaxDeg.coerceAtLeast(0.01f))
                                            .coerceIn(-1f, 1f) * 0.10f
                                    )
                                },
                                radius = 1.06f
                            )
                        )
                    }
                }

                /* ── B1 光学反馈：倾转受光面响应（镜像对称版）──
                 * 模型：固定顶光（LIGHT_DIR）+ 跷跷板倾斜 → 沉入屏幕的远缘背离光收暗、
                 * 翘向观察者的近缘迎光提亮。按左/按右、按上/按下严格镜像，对称由
                 * fx/fy 的正负分支自带，无任何方向特判（任务书「光源固定修正」）。
                 * 旧实现"按右暗左缘/按左亮右缘"是旧左上光源时代的非镜像残留，已修正。
                 * 幅度由倾角直接驱动（/15f 固定基准），滑条拉高即整体增强。 */
                val tDeg = abs(tiltX) + abs(tiltY)
                if (isMax && tDeg > 0.15f) {
                    val lit = (tDeg / 15f).coerceIn(0f, 1f)
                    val fx = (tiltX / 15f).coerceIn(-1f, 1f)   // X 轴俯仰（>0 = 顶缘沉入）
                    val fy = (tiltY / 15f).coerceIn(-1f, 1f)   // Y 轴左右（>0 = 右缘沉入）
                    val pitchDown = max(fx, 0f); val pitchUp = max(-fx, 0f)
                    val rollRight = max(fy, 0f); val rollLeft = max(-fy, 0f)

                    // 垂直向：fx>0（顶沉）→ 底亮 + 顶暗；fx<0（底沉）→ 顶亮 + 底暗
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = (0.18f * lit * pitchDown).coerceAtMost(0.16f))
                            ),
                            startY = size.height * 0.55f,
                            endY = size.height
                        ),
                        topLeft = Offset(0f, size.height * 0.55f),
                        size = Size(size.width, size.height * 0.45f)
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = (0.14f * lit * pitchDown).coerceAtMost(0.14f)),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height * 0.45f
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height * 0.45f)
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = (0.18f * lit * pitchUp).coerceAtMost(0.16f)),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height * 0.45f
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height * 0.45f)
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = (0.14f * lit * pitchUp).coerceAtMost(0.14f))
                            ),
                            startY = size.height * 0.55f,
                            endY = size.height
                        ),
                        topLeft = Offset(0f, size.height * 0.55f),
                        size = Size(size.width, size.height * 0.45f)
                    )
                    // 水平向：fy>0（右沉）→ 左亮 + 右暗；fy<0（左沉）→ 右亮 + 左暗
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = (0.16f * lit * rollRight).coerceAtMost(0.14f)),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = size.width * 0.45f
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width * 0.45f, size.height)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = (0.12f * lit * rollRight).coerceAtMost(0.12f))
                            ),
                            startX = size.width * 0.55f,
                            endX = size.width
                        ),
                        topLeft = Offset(size.width * 0.55f, 0f),
                        size = Size(size.width * 0.45f, size.height)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = (0.16f * lit * rollLeft).coerceAtMost(0.14f))
                            ),
                            startX = size.width * 0.55f,
                            endX = size.width
                        ),
                        topLeft = Offset(size.width * 0.55f, 0f),
                        size = Size(size.width * 0.45f, size.height)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = (0.12f * lit * rollLeft).coerceAtMost(0.12f)),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = size.width * 0.45f
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width * 0.45f, size.height)
                    )
                }

                /* ── B2：顶棱聚光带中心随触点 x 偏移 ──
                 * 静态几何：固定 4dp 高光带，无任何扩散/增长；
                 * 原实现寄生在涟漪进度的 fade 上，涟漪层整体移除后改由 pressAnim 驱动。 */
                val b2Fade = pressAnim.value.coerceIn(0f, 1f)
                if (isMax && b2Fade > 0.02f) {
                    val bandCx = if (pressPos == Offset.Zero) 0.5f else (pressPos.x / size.width).coerceIn(0f, 1f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.16f * b2Fade),
                                Color.Transparent
                            ),
                            startX = size.width * (bandCx - 0.35f),
                            endX = size.width * (bandCx + 0.35f)
                        ),
                        topLeft = Offset.Zero,
                        size = Size(size.width, 4.dp.toPx())
                    )
                }
            }
            // Layer 2, 3, 4: 物理光路（对角高光 + 顶棱聚光 + 底部焦散晕染）
            .drawWithContent {
                // 毛玻璃厚度感知补偿（无需背景图也可见）：
                // 真实磨砂玻璃板越厚 → 透光越散、表面镜面反射越弥散；越薄则反射越锐利。
                // 以出厂默认 22dp 为中性零点双向映射（默认视觉与历史版本完全一致），
                // 与有背景源时的采样模糊形成同一参数的双通道感知。
                val thick = ((tweaks.blurRadiusDp - 22f) / 18f).coerceIn(-1f, 1f)
                val beamGain = (1f - 0.52f * thick).coerceIn(0.42f, 1.30f)   // 厚→弥散变暗，薄→锐利增亮
                val bevelGain = (1f - 0.45f * thick).coerceIn(0.48f, 1.26f)  // 棱边高光同向联动

                val key = glassDecoKey(
                    isPressed, primary, secondary, effectiveShape, quality, tweaks.tintMix, thick
                )
                if (key != lightPathKey) {
                    lightPathKey = key
                    val w = size.width
                    val h = size.height
                    val bevelBandPx = 2.5.dp.toPx()

                    // 2. 对角镜面光束（极致档更亮；毛玻璃越厚反射越弥散 → beamGain 衰减）
                    val beamBrush = Brush.linearGradient(
                        colors = listOf(
                            strokeTint.copy(
                                alpha = (if (isPressed) 0.30f
                                else if (quality == RenderQuality.MAX) 0.27f
                                else 0.20f) * beamGain
                            ),
                            strokeTint.copy(alpha = 0.06f * beamGain),
                            Color.Transparent,
                            strokeTint.copy(alpha = 0.025f * beamGain),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w * 0.95f, h * 0.95f)
                    )

                    // 3. 顶部倒角抛光边缘反光带（Top Chamfered Bevel Rim）—— 厚玻璃弥散
                    val bevelBrush = Brush.horizontalGradient(
                        colors = listOf(
                            strokeTint.copy(alpha = 0.06f * bevelGain),
                            strokeTint.copy(alpha = 0.45f * bevelGain),
                            strokeTint.copy(alpha = 0.68f * bevelGain),
                            strokeTint.copy(alpha = 0.45f * bevelGain),
                            strokeTint.copy(alpha = 0.06f * bevelGain)
                        )
                    )

                    // 4. 底部次表面焦散色散光晕（Sub-surface Caustic Pool）
                    val causticBrush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.035f),
                            secondary.copy(alpha = 0.065f)
                        ),
                        startY = h * 0.55f,
                        endY = h
                    )

                    lightPathLayer.record(
                        size = IntSize(ceil(w).toInt(), ceil(h).toInt()),
                        layoutDirection = layoutDirection,
                        density = this
                    ) {
                        drawRect(brush = beamBrush, size = Size(w, h))
                        drawRect(brush = bevelBrush, topLeft = Offset.Zero, size = Size(w, bevelBandPx))
                        drawRect(brush = causticBrush, size = Size(w, h))
                    }
                }
                drawLayer(lightPathLayer)
                drawContent()
            }
            // Layer 5: 微结构噪点纹理（Film Grain）——Overlay 混合依赖下层像素，保持原位直绘；
            // 毛玻璃越厚散射介质感越重，颗粒随之增强；默认 22dp 为中性点
            .then(
                if (quality > RenderQuality.LOW) {
                    val t = ((tweaks.blurRadiusDp - 22f) / 18f).coerceIn(-1f, 1f)
                    Modifier.filmGrain(alpha = 0.032f + 0.03f * t.coerceAtLeast(0f))
                } else Modifier
            )
            // Layer 6 + 内倒角：水晶棱镜色散边框 + 物理倒角内凹高光边（共用一次 Outline 创建）
            // 流畅档降级为单色细描边（省掉 sweep 渐变与双层描边录制）
            .then(
                if (quality <= RenderQuality.LOW) {
                    Modifier.border(1.dp, strokeTint.copy(alpha = 0.28f), effectiveShape)
                } else {
                    Modifier.drawWithContent {
                        // 描边/内倒角不随毛玻璃厚度变化 → thickness 恒 0
                        val key = glassDecoKey(isPressed, primary, secondary, effectiveShape, quality, tweaks.tintMix, 0f)
                        if (key != edgeKey) {
                            edgeKey = key
                            val w = size.width
                            val h = size.height
                            val borderAlpha = ((if (isPressed) 0.55f else 0.40f) *
                                if (quality == RenderQuality.MAX) 1.35f else 1f).coerceAtMost(0.8f)
                            val borderWidthPx = 1.3.dp.toPx()
                            val bevelWidthPx = 1.dp.toPx()
                            // 用节点精确尺寸建 Outline（层尺寸向上取整仅防子像素裁边，不参与几何计算）
                            val outline = effectiveShape.createOutline(Size(w, h), layoutDirection, this)

                            edgeLayer.record(
                                size = IntSize(ceil(w).toInt(), ceil(h).toInt()),
                                layoutDirection = layoutDirection,
                                density = this
                            ) {
                                // 6. 全向水晶棱镜色散描边
                                val prismBrush = Brush.sweepGradient(
                                    colors = prismColors.map { it.copy(alpha = borderAlpha) },
                                    center = Offset(w / 2f, h / 2f)
                                )
                                when (outline) {
                                    is Outline.Rounded -> drawRoundRect(
                                        brush = prismBrush,
                                        cornerRadius = outline.roundRect.topLeftCornerRadius,
                                        style = Stroke(width = borderWidthPx)
                                    )
                                    is Outline.Rectangle -> drawRect(
                                        brush = prismBrush,
                                        style = Stroke(width = borderWidthPx)
                                    )
                                    else -> Unit
                                }

                                // 物理倒角内凹高光边（Inner Bevel Light）—— 白 × 主题色混合
                                val lightBrush = Brush.linearGradient(
                                    colors = listOf(
                                        strokeTint.copy(alpha = 0.35f),
                                        strokeTint.copy(alpha = 0.12f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.08f)
                                    ),
                                    start = Offset(0f, 0f),
                                    end = Offset(w, h)
                                )
                                when (outline) {
                                    is Outline.Rounded -> drawRoundRect(
                                        brush = lightBrush,
                                        cornerRadius = outline.roundRect.topLeftCornerRadius,
                                        style = Stroke(width = bevelWidthPx)
                                    )
                                    is Outline.Rectangle -> drawRect(
                                        brush = lightBrush,
                                        style = Stroke(width = bevelWidthPx)
                                    )
                                    else -> Unit
                                }
                            }
                        }
                        drawLayer(edgeLayer)
                        drawContent()
                    }
                }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(contentPadding)
            // 性能优化（视觉零变化）：内容包一层独立 RenderNode。
            // C1 内容视差：卡内内容像悬浮在玻璃下的字膜 —— 跟随触点向内沉降，
            // 并以更近的相机反向轻旋，与外层倾斜构成双层视差景深。
            .graphicsLayer {
                if (isMax) {
                    val k = pressAnim.value.coerceIn(0f, 1f)   // 低弹过冲为负时防止视差反向
                    translationX = pressNorm.x * 3.dp.toPx() * k
                    translationY = pressNorm.y * 3.dp.toPx() * k
                    rotationX = -tiltX * 0.22f
                    rotationY = -tiltY * 0.22f
                    val shortSide = min(size.width, size.height).coerceAtLeast(1f)
                    cameraDistance = shortSide *
                        (0.42f + 3.8f * ((tweaks.cameraDistMult - 3f) / 9f).coerceIn(0f, 1f)) * 0.5f
                } else {
                    // 画质降档残留防线：与外层 else 对称，清掉视差与反向旋转
                    translationX = 0f
                    translationY = 0f
                    rotationX = 0f
                    rotationY = 0f
                }
            }
    ) {
        content()
    }
}
