package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import com.example.ui.components.AppIconButton
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.clickableWithFeedback
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import java.util.Calendar

/**
 * 阅读日历：月视图（周一起始，颜色深浅=当天时长）+ 年视图（12 行 x 31 列热力矩阵）。
 * 点击任意一天回调 dateStr（yyyy-MM-dd）。
 */
@Composable
fun ReadingCalendarCard(
    dailyTotals: Map<String, Long>,
    onDayClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { todayCalendar() }
    var viewYear by remember { mutableIntStateOf(today.get(Calendar.YEAR)) }
    var viewMonth by remember { mutableIntStateOf(today.get(Calendar.MONTH)) }
    var showYear by remember { mutableStateOf(false) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 统计卡统一图标语言：36dp 圆形浅底 + 20dp 图标（与本周趋势卡一致）
                Surface(
                    shape = CircleShape,
                    color = MintPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            tint = MintPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("阅读日历", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                SegmentedPillSelector(
                    options = listOf(0 to "月", 1 to "年"),
                    selected = if (showYear) 1 else 0,
                    onSelect = { showYear = it == 1 },
                    modifier = Modifier.width(120.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(onClick = {
                    if (showYear) viewYear-- else {
                        if (viewMonth == Calendar.JANUARY) { viewMonth = Calendar.DECEMBER; viewYear-- } else viewMonth--
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月/年")
                }
                Text(
                    text = if (showYear) "$viewYear 年" else "${viewYear}年${viewMonth + 1}月",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                AppIconButton(onClick = {
                    if (showYear) viewYear++ else {
                        if (viewMonth == Calendar.DECEMBER) { viewMonth = Calendar.JANUARY; viewYear++ } else viewMonth++
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月/年")
                }
                if (viewYear != today.get(Calendar.YEAR) ||
                    (!showYear && viewMonth != today.get(Calendar.MONTH))
                ) {
                    PullOutTextButton(onClick = {
                        viewYear = today.get(Calendar.YEAR)
                        viewMonth = today.get(Calendar.MONTH)
                    }) {
                        Text("回到今天", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 月/年切换：新旧视图交叉淡入淡出 + 轻缩放，替代生硬的清空重绘；
            // 流畅档直接无过渡切换
            val lowQuality = LocalRenderQuality.current <= RenderQuality.LOW
            AnimatedContent(
                targetState = Triple(showYear, viewYear, viewMonth),
                transitionSpec = {
                    if (lowQuality) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(200)) + scaleIn(initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                    }
                },
                label = "calendarSwitch"
            ) { (targetShowYear, targetYear, targetMonth) ->
                // 2026-09-21 修复：原来这里用 `_` 忽略 target、直接读外部 showYear/viewYear/viewMonth。
                // AnimatedContent 在过渡期间会让“旧内容 + 新内容”同时存在，读外部变量会导致
                // 退场中的旧内容瞬间变成新值（动画还没完就跳变）。改用 target 快照才是正确写法。
                if (targetShowYear) {
                    YearHeatmap(dailyTotals = dailyTotals, year = targetYear, onDayClick = onDayClick)
                } else {
                    MonthGrid(
                        dailyTotals = dailyTotals,
                        year = targetYear,
                        month = targetMonth,
                        onDayClick = onDayClick
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    dailyTotals: Map<String, Long>,
    year: Int,
    month: Int,
    onDayClick: (String) -> Unit
) {
    // 2026-09-21：改用跨天自动更新的“今天”，否则 App 跨过午夜后
    // 今日高亮仍停在上一次启动的那一天。
    val todayCal = rememberTodayCalendar()
    val todayStr = remember(todayCal) { dateStrOf(todayCal) }
    val first = remember(year, month) {
        Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0

    // 当月峰值日：金色圆点标记，让"这个月哪天读得最多"一眼可见
    val peakSeconds = remember(dailyTotals, year, month, daysInMonth) {
        (1..daysInMonth)
            .mapNotNull { d -> dailyTotals["%04d-%02d-%02d".format(year, month + 1, d)] }
            .maxOrNull()
            ?.takeIf { it > 0L }
    }

    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    text = it,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        val rows = (leading + daysInMonth + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val day = r * 7 + col - leading + 1
                    if (day in 1..daysInMonth) {
                        val dateStr = "%04d-%02d-%02d".format(year, month + 1, day)
                        val seconds = dailyTotals[dateStr] ?: 0L
                        val isToday = dateStr == todayStr
                        DayCell(
                            day = day,
                            seconds = seconds,
                            isToday = isToday,
                            isPeak = seconds > 0 && seconds == peakSeconds,
                            onClick = { onDayClick(dateStr) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    seconds: Long,
    isToday: Boolean,
    isPeak: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = heatAlpha(seconds)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(shape)
            .background(
                if (seconds > 0) {
                    MintPrimary.copy(alpha = alpha)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .then(
                if (isToday) {
                    Modifier.border(1.5.dp, MintPrimary, shape)
                } else {
                    Modifier
                }
            )
            .clickableWithFeedback(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 当月峰值日：右上角金色圆点
        if (isPeak) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MintGold)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$day",
                fontSize = 10.sp,
                fontWeight = if (seconds > 0) FontWeight.Bold else FontWeight.Normal,
                color = if (seconds > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (seconds > 0) {
                Text(
                    text = formatShortDuration(seconds),
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 年视图：GitHub 贡献图布局 —— 列 = 周（约 53 列），行 = 星期（7 行，周一起始）。
 *
 * 为什么重写：原先是「12 行 x 31 列」矩阵，在手机上每一格只有约 9.5dp 宽，
 * 却要塞进 8sp 的日期数字，横轴标签互相挤压重叠、根本看不清写的是什么；
 * 单元格 .height(20.dp) 配 weight(1f)，宽 9.5 高 20 —— 就是「长方形不好看」。
 *
 * 改成周列布局后：
 * - 单元格用 size(cell) 强制正方形，手机上最小 16dp，宽屏自动放大铺满；
 * - 横轴变成「月份」标签（1月…12月），每个横跨约 4 周，宽度足够、看得清；
 * - 窄屏放不下全年时横向滚动，并自动定位到今天所在的周。
 */
@Composable
private fun YearHeatmap(
    dailyTotals: Map<String, Long>,
    year: Int,
    onDayClick: (String) -> Unit
) {
    // 2026-09-21：改用跨天自动更新的“今天”，否则 App 跨过午夜后
    // 今日高亮仍停在上一次启动的那一天。
    val todayCal = rememberTodayCalendar()
    val todayStr = remember(todayCal) { dateStrOf(todayCal) }
    val reduce = com.example.ui.feedback.LocalReduceMotion.current
    val cellGap = 3.dp
    val minCell = 16.dp          // 手机上保证可点、视觉舒坦的最小边长
    val weekdayLabelWidth = 20.dp

    // 该年全部日期预计算成 (列 x 行) 网格，避免组合期反复创建 Calendar
    val grid = remember(year) { buildYearGrid(year) }

    // 入场：按星期行错峰淡入。每行共用一个动画状态（7 个），
    // 不给 370+ 个格子各起一个 animateFloatAsState。
    var started by remember(year) { mutableStateOf(false) }
    LaunchedEffect(year) { started = true }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth - weekdayLabelWidth
        val minStep = minCell + cellGap
        // 宽屏（平板 / 横屏 / 折叠屏展开）：整年铺满，格子自动变大；
        // 窄屏：保持最小边长，横向滚动查看全年。
        val step = if (minStep * grid.columns <= avail) avail / grid.columns else minStep
        val cell = step - cellGap

        val scrollState = rememberScrollState()
        // 打开年视图先滚到「今天」附近，而不是停在 1 月
        val density = LocalDensity.current
        LaunchedEffect(year, step) {
            val stepPx = with(density) { step.toPx() }
            val target = if (grid.todayColumn >= 0) {
                ((grid.todayColumn - 3) * stepPx).toInt().coerceAtLeast(0)
            } else {
                scrollState.maxValue
            }
            if (reduce) scrollState.scrollTo(target.coerceAtMost(scrollState.maxValue))
            else scrollState.animateScrollTo(target.coerceAtMost(scrollState.maxValue))
        }

        Column {
            Row {
                // 左侧星期标签：只标 一/三/五/日，避免小屏挤成一团
                Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                    Spacer(Modifier.height(16.dp)) // 与顶部月份标签行对齐
                    for (r in 0..6) {
                        Box(
                            modifier = Modifier.height(cell),
                            contentAlignment = Alignment.Center
                        ) {
                            if (r == 0 || r == 2 || r == 4 || r == 6) {
                                Text(
                                    text = WEEKDAY_LABELS[r],
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(weekdayLabelWidth)
                                )
                            }
                        }
                    }
                }

                Column(Modifier.weight(1f).horizontalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(cellGap)) {
                    // ── 横轴：月份标签。每个横跨约 4 周，宽度足够，看得清 ──
                    Box(Modifier.height(16.dp)) {
                        Row {
                            for (m in 0..11) {
                                val start = grid.monthStartColumn[m]
                                val end = if (m == 11) grid.columns else grid.monthStartColumn[m + 1]
                                val span = (end - start).coerceAtLeast(1)
                                val w = step * span
                                Box(Modifier.width(w)) {
                                    // 放不下就不画，宁缺毋滥（避免又挤成一团模糊）
                                    if (w >= 26.dp) {
                                        Text(
                                            text = "${m + 1}月",
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 7 行 x columns 列 的正方形格子 ──
                    for (r in 0..6) {
                        val rowAlpha by animateFloatAsState(
                            targetValue = if (started) 1f else 0f,
                            animationSpec = tween(durationMillis = if (reduce) 0 else 240, delayMillis = if (reduce) 0 else r * 45),
                            label = "yearRowAlpha"
                        )
                        // 2026-09-21 修复：cellGap 之前只参与 step 计算，却没真正加到布局上，
                        // 导致格子零间距紧挨成一片，配合过淡的底色就是“看不见网格”。
                        Row(
                            modifier = Modifier.graphicsLayer { alpha = rowAlpha },
                            horizontalArrangement = Arrangement.spacedBy(cellGap)
                        ) {
                            for (col in 0 until grid.columns) {
                                val dateStr = grid.cellDate(col, r)
                                YearCell(
                                    dateStr = dateStr,
                                    seconds = if (dateStr != null) dailyTotals[dateStr] ?: 0L else 0L,
                                    isToday = dateStr != null && dateStr == todayStr,
                                    cell = cell,
                                    onDayClick = onDayClick
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 图例
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("少", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HEAT_LEVELS.forEach { a ->
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MintPrimary.copy(alpha = a))
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 单个年份格子：正方形，可点击。 */
@Composable
private fun YearCell(
    dateStr: String?,
    seconds: Long,
    isToday: Boolean,
    cell: Dp,
    onDayClick: (String) -> Unit
) {
    if (dateStr == null) {
        // 该年 1 月 1 日之前的空位
        Spacer(modifier = Modifier.requiredSize(cell))
        return
    }
    val shape = RoundedCornerShape(3.dp)
    val filled = seconds > 0
    Box(
        modifier = Modifier
            .requiredSize(cell).testTag("year_heat_cell")
            .clip(shape)
            .background(
                if (filled) {
                    MintPrimary.copy(alpha = heatAlpha(seconds))
                } else {
                    // 2026-09-21 修复：原来是 surfaceVariant.copy(alpha = 0.35f)，
                    // 在浅色主题下淡到几乎与卡片同色，整片年视图看起来是“全空白”。
                    // 改用 onSurface 低透明度（Material3 标准空态底色，GitHub 贡献图同思路），
                    // 浅色下呈淡灰、深色下呈淡白，配合格子间距始终能看出网格。
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                }
            )
            .then(
                if (isToday) Modifier.border(1.5.dp, MintGold, shape) else Modifier
            )
            .clickableWithFeedback { onDayClick(dateStr) }
    )
}

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 年视图网格：列 = 周，行 = 星期（周一起始）。 */
private class YearGrid(
    val columns: Int,
    val daysInYear: Int,
    val todayColumn: Int,
    val monthStartColumn: IntArray
) {
    private val cells: Array<String?> = arrayOfNulls(columns * 7)

    fun put(index: Int, value: String) {
        cells[index] = value
    }

    fun cellDate(col: Int, row: Int): String? {
        val i = col * 7 + row
        return if (i in cells.indices) cells[i] else null
    }
}

private fun buildYearGrid(year: Int): YearGrid {
    val first = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val jan1Dow = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0
    val daysInYear = first.getActualMaximum(Calendar.DAY_OF_YEAR)
    val columns = (jan1Dow + daysInYear + 6) / 7

    val monthStartColumn = IntArray(12)
    for (m in 0..11) {
        val c = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, m)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        monthStartColumn[m] = (jan1Dow + c.get(Calendar.DAY_OF_YEAR) - 1) / 7
    }

    val today = Calendar.getInstance()
    val todayColumn = if (today.get(Calendar.YEAR) == year) {
        (jan1Dow + today.get(Calendar.DAY_OF_YEAR) - 1) / 7
    } else {
        -1
    }

    val grid = YearGrid(columns, daysInYear, todayColumn, monthStartColumn)
    val c = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    for (d in 1..daysInYear) {
        grid.put(
            jan1Dow + d - 1,
            "%04d-%02d-%02d".format(year, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        )
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    return grid
}

/**
 * 分钟数 -> 颜色 alpha（0/1-9/10-29/30-59/60+）。
 *
 * 2026-09-21 调整：最低档原本只有 0.2，在浅色卡片上几乎看不出是绿色，
 * “读了 5 分钟”和“没读”在视觉上分不开 —— 这也是年视图看起来一片空白的原因之一。
 * 对照 GitHub 贡献图的最低档（明显的浅绿），把四档整体抬到 0.32/0.52/0.72/0.95，
 * 保证最低档也与空格子的灰底有清晰区分。
 */
private fun heatAlpha(seconds: Long): Float = when {
    seconds <= 0 -> 0f
    seconds < 600 -> HEAT_LEVELS[0]
    seconds < 1800 -> HEAT_LEVELS[1]
    seconds < 3600 -> HEAT_LEVELS[2]
    else -> HEAT_LEVELS[3]
}

/**
 * 四档热力 alpha（由浅到深）。格子配色与底部图例共用同一份数据，
 * 避免以前那种“图例写死 0.2/0.4/0.65/0.9、格子另算”导致图例和实际颜色对不上的问题。
 */
private val HEAT_LEVELS = listOf(0.32f, 0.52f, 0.72f, 0.95f)
