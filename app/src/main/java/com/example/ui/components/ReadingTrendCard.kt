package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReadingSession
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.clickableWithFeedback
import java.util.Calendar

/**
 * 阅读趋势：折线+面积图（周/月/年三档）+ 高峰时段分布（24 小时条形）。
 * 图表用 Canvas 手绘，不引入额外依赖。
 */
@Composable
fun ReadingTrendCard(
    dailyTotals: Map<String, Long>,
    sessions: List<ReadingSession>,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableIntStateOf(0) } // 0=周 1=月 2=年
    var selectedPoint by remember { mutableIntStateOf(-1) }
    var selectedHour by remember { mutableIntStateOf(-1) }
    val today = remember { todayCalendar() }

    val series = remember(dailyTotals, tab) {
        val to = today.clone() as Calendar
        when (tab) {
            0 -> {
                val from = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -13) }
                dailySeries(dailyTotals, from, to)
            }
            1 -> {
                val from = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -29) }
                dailySeries(dailyTotals, from, to)
            }
            else -> monthlySeries(dailyTotals, 12, today)
        }
    }
    val seriesLabel = listOf("近14天", "近30天", "近12个月")[tab]

    val peakFrom = remember { (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -29) } }
    val peak = remember(sessions) { peakHourTotals(sessions, peakFrom, today) }
    val peakMaxHour = peak.indices.maxByOrNull { peak[it] }
    val peakTotal = peak.sum()

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 统计卡统一图标语言：36dp 圆形浅底 + 20dp 图标（与本周趋势卡一致）
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MintPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.ShowChart,
                            contentDescription = null,
                            tint = MintPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("阅读趋势", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("时长走势与高峰时段", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                SegmentedPillSelector(
                    options = listOf(0 to "周", 1 to "月", 2 to "年"),
                    selected = tab,
                    onSelect = { tab = it },
                    // A7：原固定 150.dp，窄屏 / 大字体下会把标题挤到换行。
                    // 改为"最多 150dp"，空间不足时自动让给标题。
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row {
                Text("阅读时长 · $seriesLabel", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "峰值 ${formatShortDuration(series.maxOfOrNull { it.second } ?: 0)}",
                    fontSize = 11.sp,
                    color = MintPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (selectedPoint in series.indices) {
                val (date, sec) = series[selectedPoint]
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MintPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "$date · ${formatShortDuration(sec)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MintPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(150.dp)
            ) {
                TrendLineChart(
                    series = series,
                    selectedIndex = selectedPoint,
                    onSelectIndex = { selectedPoint = it },
                    modifier = Modifier.fillMaxSize()
                )
                // 满刻度数值锚点：让"线有多高"可被量化
                val maxSeconds = series.maxOfOrNull { it.second } ?: 0L
                if (maxSeconds > 0L) {
                    Text(
                        text = "满刻度 ${formatShortDuration(maxSeconds)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 4.dp)
                    )
                }
                if (series.all { it.second <= 0L }) {
                    Text(
                        text = "还没有阅读时长——去读一页，让曲线开始生长",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读高峰时段", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (peakTotal > 0 && peakMaxHour != null) {
                    Text(
                        text = "高峰 ${peakMaxHour}:00 - ${peakMaxHour + 1}:00",
                        fontSize = 11.sp,
                        color = MintGold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (peakTotal > 0) {
                if (selectedHour in 0..23 && peak[selectedHour] > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MintGold.copy(alpha = 0.14f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "${selectedHour}:00 - ${selectedHour + 1}:00 · ${formatShortDuration(peak[selectedHour])}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MintGold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                PeakHoursBars(
                    peak = peak,
                    selectedHour = selectedHour,
                    onSelectHour = { selectedHour = it },
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                )
            } else {
                Text(
                    text = "暂无时段数据（更新后开始记录每次阅读的起止时间）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TrendLineChart(
    series: List<Pair<String, Long>>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 复用全软件统一弹簧（AppBottomTabBar/DownloadGlassCard 同款：
    // MediumBouncy + StiffnessMediumLow），选中点切换时做一次扩散脉冲反馈。
    val pointPulse = remember { Animatable(0f) }
    LaunchedEffect(selectedIndex) {
        pointPulse.snapTo(0f)
        pointPulse.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }
    val maxVal = (series.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val primary = MintPrimary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val peakIndex = series.indices.maxByOrNull { series[it].second }

    // 先纸后墨：数据自左向右描绘进场；流畅档直接定格终态
    val lowQuality = LocalRenderQuality.current <= RenderQuality.LOW
    val reveal = remember { Animatable(1f) }
    val reduce = com.example.ui.feedback.LocalReduceMotion.current
    LaunchedEffect(lowQuality, reduce) {
        if (lowQuality || reduce) {
            reveal.snapTo(1f)
        } else {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(700, easing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)))
        }
    }

    Canvas(
        modifier = modifier.pointerInput(series.size) {
            detectTapGestures { offset ->
                if (series.size > 1) {
                    val w = size.width
                    val stepX = w / (series.size - 1)
                    val idx = (offset.x / stepX).toInt().coerceIn(0, series.size - 1)
                    onSelectIndex(idx)
                }
            }
        }
    ) {
        if (series.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val topPad = 10f
        val bottomPad = 8f

        // 先纸：水平参考网格 + 基线（静态骨架，不参与 reveal）
        for (g in 1..3) {
            val gy = topPad + (h - topPad - bottomPad) * g / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }
        drawLine(
            color = gridColor.copy(alpha = gridColor.alpha * 2.2f),
            start = Offset(0f, h - bottomPad),
            end = Offset(w, h - bottomPad),
            strokeWidth = 1f
        )

        val stepX = w / (series.size - 1)
        val points = series.mapIndexed { i, (_, v) ->
            Offset(i * stepX, h - bottomPad - (v.toFloat() / maxVal) * (h - topPad - bottomPad))
        }
        val linePath = Path().apply {
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }

        // 后墨：折线/面积/峰值随 reveal 因果生长（峰值点在描绘经过时才落墨）
        val rv = reveal.value
        clipRect(left = 0f, top = 0f, right = w * rv, bottom = h) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.30f), Color.Transparent)
                )
            )
            drawPath(path = linePath, color = primary, style = Stroke(width = 2.5f))
            peakIndex?.let { pi ->
                drawCircle(MintGold, radius = 7f, center = points[pi])
                drawCircle(Color.White, radius = 3f, center = points[pi])
            }
            if (selectedIndex !in points.indices) {
                drawCircle(primary, radius = 4f, center = points.first())
                drawCircle(primary, radius = 4f, center = points.last())
            }
        }

        // 选中点脉冲：始终即时响应点击（不参与 reveal）
        if (selectedIndex in points.indices) {
            val p = points[selectedIndex]
            drawCircle(
                color = primary.copy(alpha = 0.35f * (1f - pointPulse.value)),
                radius = 5f + 7f * pointPulse.value,
                center = p
            )
            drawCircle(primary, radius = 5f, center = p)
            drawCircle(Color.White, radius = 2f, center = p)
        }
    }
}

@Composable
private fun PeakHoursBars(
    peak: LongArray,
    selectedHour: Int,
    onSelectHour: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val max = (peak.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        for (hour in 0 until 24) {
            val v = peak[hour]
            val ratio = v.toFloat() / max
            val isMax = v > 0 && v == max
            // B4：原先 fillMaxHeight(ratio) 是即时计算，切「周/月/年」时 24 根柱
            // 全部瞬间跳变——与同文件 TrendLineChart 的 reveal 生长动画落差极大。
            // 改为每根柱独立过渡，并按小时错峰 12ms 形成"波浪式"刷新。
            val animatedRatio by animateFloatAsState(
                targetValue = ratio.coerceAtLeast(0.04f),
                animationSpec = tween(
                    durationMillis = 420,
                    delayMillis = hour * 12,
                    easing = FastOutSlowInEasing
                ),
                label = "peakBar_$hour"
            )
            // 选中/峰值高亮也过渡，避免点选小时时颜色硬切
            val barColor by animateColorAsState(
                targetValue = if (selectedHour == hour) {
                    MintGold.copy(alpha = 0.95f)
                } else if (isMax) {
                    MintGold.copy(alpha = 0.65f)
                } else {
                    MintPrimary.copy(alpha = 0.55f)
                },
                animationSpec = tween(260),
                label = "peakColor_$hour"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickableWithFeedback { onSelectHour(hour) },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.5.dp)
                        .fillMaxHeight(animatedRatio)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(barColor)
                )
            }
        }
    }
}
