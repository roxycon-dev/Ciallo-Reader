package com.example.ui.perf

import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, self-driven real input. No Espresso/Compose-test runtime enters the measured process. */
@androidx.annotation.RequiresApi(31)
class MotionResourceProbeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val bounds = HashMap<String, Rect>()
    private val measuring = AtomicBoolean(false)
    private val frames = Collections.synchronizedList(mutableListOf<LongArray>())
    private var metricsThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var actionCount = 0
    private var downTime = 0L
    private var startWall = 0L
    private var startCpu = 0L
    private var startProcessCpu = 0L
    private var startAllocated = 0L
    private var startRender: Map<Int, Long> = emptyMap()
    private var started = false
    private var coldWall = 0L
    private var coldCpu = 0L
    private var coldProcessCpu = 0L
    private var coldAllocated = 0L
    private var coldResult = JSONObject()
    private val coldFrames = Collections.synchronizedList(mutableListOf<LongArray>())
    private val coldMeasuring = AtomicBoolean(true)
    private val quality get() = intent.getStringExtra("quality")?.let { RenderQuality.valueOf(it) } ?: RenderQuality.HIGH
    private val scene get() = intent.getStringExtra("scene") ?: "mixed"
    private var rootRecords = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coldWall = SystemClock.elapsedRealtimeNanos()
        coldCpu = Debug.threadCpuTimeNanos()
        coldProcessCpu = android.os.Process.getElapsedCpuTime()
        coldAllocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
        val metrics = HandlerThread("probe-frame-metrics").apply { start() }
        metricsThread = metrics
        listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val target = if (measuring.get()) frames else if (coldMeasuring.get()) coldFrames else null
            target?.add(longArrayOf(frame.getMetric(FrameMetrics.TOTAL_DURATION), frame.getMetric(FrameMetrics.DRAW_DURATION),
                frame.getMetric(FrameMetrics.GPU_DURATION), frame.getMetric(FrameMetrics.DEADLINE)))
        }.also { window.addOnFrameMetricsAvailableListener(it, Handler(metrics.looper)) }
        setContent { MyApplicationTheme(darkTheme = false) {
            CompositionLocalProvider(LocalRenderQuality provides quality) {
                DialogLiquidGlass {
                    val scroll = rememberScrollState()
                    val distance = with(LocalDensity.current) { 16.dp.roundToPx() }
                    LaunchedEffect(scene) {
                        if (scene == "scroll") while (isActive) {
                            scroll.animateScrollTo(distance, tween(600, easing = LinearEasing))
                            scroll.animateScrollTo(0, tween(600, easing = LinearEasing))
                        }
                    }
                    Column(Modifier.fillMaxSize().background(Color(0xffeee8e0)).padding(16.dp)
                        .drawWithContent { rootRecords++; drawContent() }
                        .then(if (scene == "scroll") Modifier.verticalScroll(scroll) else Modifier)) {
                        Text("固定动效资源采样")
                        if (scene == "primary") {
                            AppActionButton("动作 0", { actionCount++ }, fullWidth = true,
                                modifier = Modifier.onGloballyPositioned { bounds["action-0"] = it.boundsInWindow() })
                        } else {
                        repeat(3) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { col ->
                                    val index = row * 4 + col
                                    var checked by remember { mutableStateOf(index % 2 == 0) }
                                    AppSwitch(checked, { checked = it }, Modifier.onGloballyPositioned { bounds["switch-$index"] = it.boundsInWindow() })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        AppButtonVariant.entries.forEachIndexed { index, variant ->
                            AppActionButton("动作 $index", { actionCount++ }, fullWidth = true, variant = variant,
                                modifier = Modifier.onGloballyPositioned { bounds["action-$index"] = it.boundsInWindow() })
                            Spacer(Modifier.height(8.dp))
                        }
                        var value by remember { mutableStateOf(.25f) }
                        LiquidSlider(value, { value = it }, steps = 7,
                            modifier = Modifier.onGloballyPositioned { bounds["slider"] = it.boundsInWindow() })
                        }
                        if (scene == "scroll") Spacer(Modifier.height(900.dp))
                    }
                }
            }
        } }
        handler.postDelayed(::awaitLayout, 600)
    }

    private fun awaitLayout() {
        if (bounds.size < if (scene == "primary") 1 else 17) { handler.postDelayed(::awaitLayout, 50); return }
        if (started) return
        started = true
        // Exercise every control path once before the measured fixed sequence.
        scheduleSequence(SystemClock.uptimeMillis() + 100)
        handler.postDelayed(::finishCold, 4200)
        handler.postDelayed(::beginMeasure, 6600)
    }

    private fun finishCold() {
        coldMeasuring.set(false)
        val data = synchronized(coldFrames) { coldFrames.toList() }
        val sorted = data.map { it[0] }.sorted()
        coldResult = JSONObject().put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - coldWall) / 1e6)
            .put("mainCpuMs", (Debug.threadCpuTimeNanos() - coldCpu) / 1e6)
            .put("processCpuMs", android.os.Process.getElapsedCpuTime() - coldProcessCpu)
            .put("allocatedBytes", (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: coldAllocated) - coldAllocated)
            .put("actions", actionCount).put("frames", data.size)
            .put("rootRecords", rootRecords)
            .put("p95Ms", if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * .95).toInt()] / 1e6)
    }

    private fun raw(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        window.decorView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun tap(key: String, at: Long, hold: Long) {
        handler.postAtTime({ bounds[key]?.center?.let { raw(MotionEvent.ACTION_DOWN, it.x, it.y) } }, at)
        handler.postAtTime({ bounds[key]?.center?.let { raw(MotionEvent.ACTION_UP, it.x, it.y) } }, at + hold)
    }

    private fun scheduleSequence(at: Long) {
        if (scene == "primary") {
            repeat(12) { tap("action-0", at + it * 300L, 180) }
            return
        }
        repeat(12) { index ->
            val time = at + index * 300L
            tap("switch-$index", time, 16)
            tap("action-${index % 4}", time + 60, 48)
            if (index % 3 == 0) {
                repeat(11) { step ->
                    handler.postAtTime({ bounds["slider"]?.let { box ->
                        val fraction = if (index % 2 == 0) .25f + step * .05f else .75f - step * .05f
                        raw(if (step == 0) MotionEvent.ACTION_DOWN else if (step == 10) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                            box.left + box.width * fraction, box.top + box.height * .62f)
                    } }, time + 120 + step * 16L)
                }
            }
        }
    }

    private fun renderTicks(): Map<Int, Long> = File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
        runCatching {
            if (File(task, "comm").readText().trim() != "RenderThread") return@mapNotNull null
            val fields = File(task, "stat").readText().substringAfterLast(')').trim().split(' ')
            task.name.toInt() to (fields[11].toLong() + fields[12].toLong())
        }.getOrNull()
    }.toMap()

    private fun beginMeasure() {
        actionCount = 0
        rootRecords = 0
        startRender = renderTicks()
        startAllocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
        startCpu = Debug.threadCpuTimeNanos()
        startProcessCpu = android.os.Process.getElapsedCpuTime()
        startWall = SystemClock.elapsedRealtimeNanos()
        Trace.beginAsyncSection("motion_cpu_gpu_measured", 42)
        measuring.set(true)
        scheduleSequence(SystemClock.uptimeMillis() + 50)
        handler.postDelayed(::finishMeasure, 4300)
    }

    private fun finishMeasure() {
        measuring.set(false)
        Trace.endAsyncSection("motion_cpu_gpu_measured", 42)
        val cpu = Debug.threadCpuTimeNanos() - startCpu
        val processCpu = android.os.Process.getElapsedCpuTime() - startProcessCpu
        val wall = SystemClock.elapsedRealtimeNanos() - startWall
        val allocated = (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: startAllocated) - startAllocated
        val render = renderTicks().map { (tid, ticks) -> ticks - (startRender[tid] ?: ticks) }.sum()
        listener?.let(window::removeOnFrameMetricsAvailableListener)
        metricsThread?.quitSafely()
        val data = synchronized(frames) { frames.toList() }
        fun percentile(index: Int, q: Double): Double {
            val sorted = data.map { it[index] }.filter { it >= 0 }.sorted()
            return if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * q).toInt()] / 1e6
        }
        val result = JSONObject().put("variant", intent.getStringExtra("variant") ?: "optimized")
            .put("probeRevision", 3).put("quality", quality.name).put("scene", scene).put("coldUiSequence", coldResult)
            .put("rootRecords", rootRecords)
            .put("iteration", intent.getIntExtra("iteration", 0)).put("actions", actionCount).put("frames", data.size)
            .put("elapsedMs", wall / 1e6).put("mainCpuMs", cpu / 1e6)
            .put("processCpuMs", processCpu)
            .put("renderCpuMs", render * 1000.0 / Os.sysconf(OsConstants._SC_CLK_TCK))
            .put("allocatedBytes", allocated).put("pssKb", Debug.getPss())
            .put("p50Ms", percentile(0, .5)).put("p95Ms", percentile(0, .95)).put("gpuP95Ms", percentile(2, .95))
            .put("gpuTotalMs", data.sumOf { it[2].coerceAtLeast(0) } / 1e6)
            .put("deadlineRatio", if (data.isEmpty()) -1.0 else data.count { it[3] > 0 && it[0] > it[3] }.toDouble() / data.size)
            .put("rawFrames", JSONArray(data.map { JSONArray(it.toList()) }))
            .put("nativeAvailable", nativeAvailable())
        val directory = File(getExternalFilesDir(null), "motion-resource").apply { mkdirs() }
        val file = File(directory, "${result.getString("variant")}-${result.getInt("iteration")}.json")
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(result.toString(2))
        check(temporary.renameTo(file))
        android.util.Log.i("MotionResource", "complete " + result.toString())
    }

    private fun nativeAvailable(): Boolean = try {
        val type = Class.forName("me.trishiraj.shadowglow.ShadowBlurNative")
        type.getMethod("getAvailable").invoke(type.getField("INSTANCE").get(null)) as Boolean
    } catch (_: ReflectiveOperationException) { false }

    override fun onDestroy() {
        measuring.set(false)
        coldMeasuring.set(false)
        handler.removeCallbacksAndMessages(null)
        listener?.let(window::removeOnFrameMetricsAvailableListener)
        metricsThread?.quitSafely()
        super.onDestroy()
    }
}
