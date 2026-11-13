package com.example.ui.pageturn

import com.example.i18n.tr

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.MintGold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 真实翻纸声播放器：res/raw/page_flip.ogg 经 SoundPool 低延迟播放，
 * 支持快速掠页时的高频重叠触发。
 */
private class PaperFlipPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundId: Int = soundPool.load(context, R.raw.page_flip, 1)
    private var loaded = false

    init {
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
    }

    fun play() {
        if (loaded) {
            soundPool.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}

/**
 * 串珠式快速翻页遮罩（圆柱内壁 · 等距刚体模型）：
 * 观察者（相机）站在圆心，所有页面作为**完整刚体**贴在半径 kR 的圆弧内壁上，
 * 正中一页正对观察者，两侧页面沿弧向前卷曲——外缘朝观察者前倾放大，
 * 像墙面在视野边缘包抄过来。滑动时整个环绕垂直轴转动，页面沿弧滑过，
 * 视觉上就是"看一段弧在转"。
 *
 * 几何模型（每页仅一次 3D 变换，绝不切条拼接）：
 * - 每页是不可切割的刚体，只做一次绕 Y 轴旋转，不做任何整体 scale 模拟远近；
 *   侧页变窄完全是旋转本身的透视收窄（foreshortening），页面高度不随位置变化
 * - 变换分解（Compose 的 GraphicsLayerScope 无 z 轴平移/3D 轴心，
 *   "绕圆心轴旋转"以"绕页心旋转 + 弧投影平移"等价表达）：
 *     rotationY = −φ       （内壁朝向：页面法线恒指向圆心，侧页外缘前倾）
 *     translationX = r·sinφ （页面中心平移到该页圆心角的弧投影位置）
 *   竖边旋转后仍竖直，页面边缘是完整连续的直线，无条带拼接痕迹。
 *
 * 动画与手感细节：
 * - 进场：翻纸声 + 振动；弹簧带轻微过冲——当前页从满屏缩成环上一页，
 *   其余页从中心"弹扇形展开"成弧，两侧随距离渐暗（圆柱内壁光照）
 * - 拖动跟手；两端橡皮筋阻力 + 到边振动；甩动以初速度弹簧吸附目标页；
 *   每掠过一页振动 + 翻纸声（串珠手感）
 * - 点击某页进入：目标页提升到最上层，环转正该页（260ms），120ms 后
 *   其余页快速淡出、目标页放大冲向满屏，两段重叠衔接顺滑
 * - 取消（按钮/返回/点空白处）：环转回原页与收拢动画重叠收束回满屏
 * - 中心页贴近时有轻微放大 pop；页码胶囊数字随滑动方向横滑切换 + 金色进度条
 */
@Composable
fun PageScrubberOverlay(
    pageCount: Int,
    initialPage: Int,
    pageContent: @Composable (Int) -> Unit,
    onDismiss: () -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pageCount < 1) return

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val paperFlipPlayer = remember { PaperFlipPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { paperFlipPlayer.release() }
    }
    var committed by remember { mutableStateOf<Int?>(null) }
    var edgePulsed by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        // 页面缩放：必须与内容纵横比一致（内容按满屏排版，均匀缩放进页卡）。
        // 0.60 → 页宽略小于弦距，两侧各露出约一页的斜面，恰好看清环中三页
        val pageScale = 0.60f
        val pageW = screenW * pageScale
        val pageH = screenH * pageScale
        val pageWDp = with(density) { pageW.toDp() }
        val pageHDp = with(density) { pageH.toDp() }
        val topY = (screenH - pageH) / 2f
        val baseX = (screenW - pageW) / 2f

        // ===== 圆柱内壁（串珠环）参数——等距刚体模型 =====
        // 观察者（相机）站在圆心，所有页面作为完整刚体贴在半径 kR 的圆弧内壁上：
        // - 每页不可切割，仅一次绕 Y 轴旋转，旋转轴心统一在圆心（=相机处）
        // - cameraDistance = 环半径 → 相机在圆心，页面到圆心等距，无远近缩放；
        //   侧页变窄完全是旋转的透视收窄（foreshortening），不做任何 scale 模拟远近
        // - 内壁朝向：rotationY 取负 → 页面法线指向圆心，侧页外缘朝观察者前倾
        val slotAngle = 30f                    // 相邻页圆心角（度）
        val kR = screenW * 1.24f               // 环半径：侧页内缘与中心页留 ~65px 间隙、可见约 150px
        // 弧长步进：中心页屏幕位移 ≈ offset 位移（拖动跟手 1:1）
        val stride = kR * Math.toRadians(slotAngle.toDouble()).toFloat()
        // Compose cameraDistance 单位为 dp，环半径换算成 dp 数值
        val camR = with(density) { kR.toDp().value }

        fun offsetFor(i: Int): Float = -i * stride

        val minOff = offsetFor(pageCount - 1)
        val maxOff = offsetFor(0)

        val initial = initialPage.coerceIn(0, pageCount - 1)
        val offset = remember(pageCount, initial) { Animatable(offsetFor(initial)) }
        val enter = remember { Animatable(0f) }

        val centerIdx by remember {
            derivedStateOf {
                (-offset.value / stride).roundToInt().coerceIn(0, pageCount - 1)
            }
        }

        val specialPage = committed ?: initial

        fun cancelScrubInternal() {
            if (committed != null) return
            committed = initial
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            paperFlipPlayer.play()
            scope.launch {
                // 环转回原页 与 收拢放大回满屏 重叠进行，收束更顺滑
                launch {
                    offset.animateTo(offsetFor(initial), tween(220, easing = FastOutSlowInEasing))
                }
                delay(90)
                enter.animateTo(0f, tween(230, easing = FastOutSlowInEasing))
                onDismiss()
            }
        }

        fun commit(target: Int) {
            if (committed != null) return
            committed = target.coerceIn(0, pageCount - 1)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            paperFlipPlayer.play()
            scope.launch {
                // 环转正目标页；120ms 后其余页快速退场、目标页放大冲向满屏
                launch {
                    offset.animateTo(
                        offsetFor(committed!!),
                        tween(260, easing = FastOutSlowInEasing)
                    )
                }
                delay(120)
                enter.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                onPageSelected(committed!!)
                onDismiss()
            }
        }

        BackHandler { cancelScrubInternal() }

        // 进场：翻纸声 + 振动；弹簧轻微过冲 = 环"扇形弹开"的手感
        LaunchedEffect(Unit) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            paperFlipPlayer.play()
            enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 380f))
        }

        // 每掠过一页：振动 + 翻纸声（串珠手感）
        LaunchedEffect(Unit) {
            var last = centerIdx
            snapshotFlow { centerIdx }.collect { c ->
                if (c != last) {
                    last = c
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    paperFlipPlayer.play()
                }
            }
        }

        val velocityTracker = remember { VelocityTracker() }

        // 甩动惯性：带初速度的弹簧吸附到目标页——只吸附，不进入（进入必须点击某页）
        // velocity 单位为 px/s（VelocityTracker 惯例），惯性投影距离 = 速度 × 减速
        // 时间常数 0.7s：慢拖松手（~400px/s）→ 不足一页，吸附当前页；普通快甩
        // （~2500px/s）→ 2~3 页；用力甩（~6000px/s）→ 6 页；极速封顶 12 页
        suspend fun settle(velocity: Float) {
            val current = centerIdx
            val dist = velocity * 0.7f
            val pages = (abs(dist) / stride).roundToInt().coerceAtMost(12)
            val target = if (pages == 0) {
                current
            } else {
                (current + if (dist < 0) pages else -pages).coerceIn(0, pageCount - 1)
            }
            // 贴边目标加大阻尼，避免过冲到空白弧段
            val atEdge = target == 0 || target == pageCount - 1
            offset.animateTo(
                offsetFor(target),
                spring(
                    dampingRatio = if (atEdge) 0.95f else 0.85f,
                    stiffness = 380f
                ),
                initialVelocity = velocity
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 近乎不透明的深底 + 环心金色柔光 + 四周渐晕：强化"看进圈内"的隧道纵深
                .drawBehind {
                    val e = enter.value.coerceIn(0f, 1f)
                    drawRect(Color(0xF70F1115), alpha = e)
                    val glow = Brush.radialGradient(
                        colors = listOf(MintGold.copy(alpha = 0.08f * e), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.75f
                    )
                    drawRect(glow)
                    val vignette = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f)
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension
                    )
                    drawRect(vignette, alpha = e)
                }
                .pointerInput(Unit) {
                    // 点击空白处 = 取消（落在页卡上的点按由页卡自己消费）
                    detectTapGestures { cancelScrubInternal() }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            velocityTracker.resetTracking()
                            edgePulsed = false
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (committed == null) {
                                val raw = offset.value + amount.x
                                // 两端橡皮筋阻力：越拉越紧 + 到边振动一次
                                val target = when {
                                    raw > maxOff -> {
                                        if (!edgePulsed) {
                                            edgePulsed = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        maxOff + (raw - maxOff) * 0.32f
                                    }
                                    raw < minOff -> {
                                        if (!edgePulsed) {
                                            edgePulsed = true
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        minOff + (raw - minOff) * 0.32f
                                    }
                                    else -> raw
                                }
                                scope.launch { offset.snapTo(target) }
                            }
                        },
                        onDragEnd = {
                            val v = velocityTracker.calculateVelocity().x
                            if (committed == null) {
                                scope.launch { settle(v) }
                            }
                        },
                        onDragCancel = { cancelScrubInternal() }
                    )
                }
        ) {
            // 串珠线（金色细线，横贯环带中线，衬在页卡之后）
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                drawLine(
                    color = MintGold.copy(alpha = 0.26f * enter.value.coerceIn(0f, 1f)),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = size.height.coerceAtLeast(1f)
                )
            }

            // 绘制顺序按离中心距离降序：中心页最后画（压最上层），弧上两侧层层后退。
            // 进入/取消时，目标页强制提到最上层飞行。
            // 只组合中心附近 ±6 页（内容仅 ±3 页），长章节不至组合上百节点
            val renderOrder = remember(centerIdx, committed) {
                val c = committed
                (0 until pageCount)
                    .filter { abs(it - centerIdx) <= 6 }
                    .sortedByDescending { abs(it - centerIdx) }
                    .let { list ->
                        if (c != null && list.contains(c)) list.filterNot { it == c } + c else list
                    }
            }
            renderOrder.forEach { i ->
                key(i) {
                    val inWindow = abs(i - centerIdx) <= 3
                    val isSpecial = i == specialPage
                    val isCentered = i == centerIdx && committed == null
                    // ===== 整页刚体：每页完整不可切割，仅一次 rotateY + 平移 =====
                    // 每页只做一次绕 Y 轴的旋转 + 沿弧投影位置的平移：
                    //   rotationY = -φ         （内壁朝向：页面法线恒指向圆心，侧页外缘朝观察者前倾）
                    //   translationX = r·sinφ  （页面中心平移到该页圆心角的弧投影位置）
                    // 不做任何 scale：侧页变窄完全是旋转本身的透视收窄（foreshortening），
                    // 页面高度不随位置变化，竖边旋转后仍竖直、边缘完整连续。
                    // 注：Compose 的 GraphicsLayerScope 不暴露 z 轴平移/3D 轴心，
                    // "绕圆心轴旋转"以"绕页心旋转 + 弧投影平移"等价表达。
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(baseX.roundToInt(), topY.roundToInt()) }
                            .size(pageWDp, pageHDp)
                            .graphicsLayer {
                                val e = enter.value
                                val d = i + offset.value / stride
                                val phi = d * slotAngle * e
                                val rad = Math.toRadians(phi.toDouble())
                                // 进场/退场：目标页在"满屏 ↔ 环上一页"之间收缩/放大
                                val flying = committed != null || e < 0.999f
                                val s = if (isSpecial && flying) {
                                    1f + (1f - e) * (1f / pageScale - 1f)
                                } else 1f
                                // 中心页贴近时轻微 pop（手感强调，非远近模拟）
                                val pop = 1f + 0.035f *
                                    (1f - abs(d).coerceAtMost(1f)) *
                                    e.coerceIn(0f, 1f)
                                val k = s * pop
                                rotationY = -phi
                                translationX = k * kR * sin(rad).toFloat()
                                scaleX = k
                                scaleY = k
                                cameraDistance = camR
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                            // 金边/阴影直接画在刚体页卡上，随同一 3D 变换透视变形，
                            // 始终精确贴合页面，无需外接盒追踪
                            .then(
                                if (isCentered) Modifier
                                    .shadow(14.dp, RoundedCornerShape(12.dp), clip = false)
                                    .border(2.dp, MintGold, RoundedCornerShape(12.dp))
                                else Modifier
                            )
                            .clip(RoundedCornerShape(12.dp))
                            // 圆柱内壁光照：离中心视线越远蒙上越深暗纱；进场/退场时
                            // 暗纱加重，在深底上等效整页淡入淡出（不用 graphicsLayer
                            // alpha，避免离屏合成裁掉越界的侧页投影）
                            .drawWithContent {
                                drawContent()
                                val flying = committed != null || enter.value < 0.999f
                                if (!(isSpecial && flying)) {
                                    val e = enter.value.coerceIn(0f, 1f)
                                    val d = i + offset.value / stride
                                    val shade = ((abs(d) - 0.22f) / 3.2f).coerceIn(0f, 0.45f) * e +
                                        (1f - e) * 0.55f
                                    if (shade > 0.004f) {
                                        drawRect(Color(0xFF0A0C11), alpha = shade.coerceIn(0f, 1f))
                                    }
                                }
                            }
                            // 点击热区跟随 3D 变换（translation/rotation 参与命中测试），
                            // 点击可见的侧页即进入该页
                            .pointerInput(i) {
                                detectTapGestures {
                                    if (committed == null) commit(i)
                                }
                            }
                    ) {
                        if (inWindow) {
                            Box(
                                modifier = Modifier
                                    .layout { measurable, _ ->
                                        // 满屏约束排版（分页与阅读器完全一致）
                                        val p = measurable.measure(
                                            Constraints.fixed(screenW.toInt(), screenH.toInt())
                                        )
                                        // 上报尺寸 = 页卡尺寸：子项与父容器一致，
                                        // TopStart 落在 (0,0)，杜绝居中偏移
                                        layout(pageW.roundToInt(), pageH.roundToInt()) {
                                            p.placeRelative(0, 0)
                                        }
                                    }
                                    .graphicsLayer {
                                        // 以左上角为锚点缩至页卡，视觉上正好铺满页卡
                                        scaleX = pageW / screenW
                                        scaleY = pageH / screenH
                                        transformOrigin = TransformOrigin(0f, 0f)
                                    }
                            ) {
                                pageContent(i)
                            }
                        }
                    }
                }
            }

            // 顶部页码胶囊：数字随滑动方向横滑 + 金色进度条
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE1C1F26),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    AnimatedContent(
                        targetState = centerIdx,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally { dir * it / 2 } + fadeIn(tween(130))) togetherWith
                                (slideOutHorizontally { -dir * it / 2 } + fadeOut(tween(130)))
                        },
                        label = "pageNum"
                    ) { idx ->
                        Text(
                            text = "第 ${idx + 1} / $pageCount 页",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = tr("左右滑动选页 · 点击进入"),
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp
                    )
                    Canvas(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .width(132.dp)
                            .height(3.dp)
                    ) {
                        val w = size.width * ((centerIdx + 1f) / pageCount)
                        drawRoundRect(
                            color = Color(0x30FFFFFF),
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                        drawRoundRect(
                            color = MintGold,
                            size = Size(w, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    }
                }
            }

            // 底部取消：胶囊按钮
            Surface(
                onClick = { cancelScrubInternal() },
                shape = RoundedCornerShape(22.dp),
                color = Color(0x1AFFFFFF),
                contentColor = Color(0xE6FFFFFF),
                border = BorderStroke(1.dp, Color(0x2EFFFFFF)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
            ) {
                Text(
                    text = tr("取消"),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 9.dp)
                )
            }
        }
    }
}
