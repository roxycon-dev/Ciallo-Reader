package com.example.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ComicReaderTestActivity
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.InvocationTargetException
import java.io.File
import kotlin.random.Random

/** The fixture is frozen from HEAD before this optimization, never used by production. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-160dpi")
class FoldMotionPaginationParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private lateinit var density: Density
    private lateinit var resolver: FontFamily.Resolver

    private fun setup() {
        compose.setContent {
            density = LocalDensity.current
            resolver = LocalFontFamilyResolver.current
        }
        compose.waitForIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun paginate(
        legacy: Boolean,
        text: String,
        width: Int = 560,
        height: Int = 800,
        style: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
        titleReserve: Int = 0,
        check: () -> Unit = {}
    ): List<String> {
        val pkg = if (legacy) "com.example.ui.paginationbaseline" else "com.example.ui"
        val paramsClass = Class.forName("$pkg.LayoutParams")
        val constructor = paramsClass.declaredConstructors.single().apply { isAccessible = true }
        val params = constructor.newInstance(width, height, (height - titleReserve).coerceAtLeast(80), style,
            with(density) { style.fontSize.toPx() }, with(density) { style.lineHeight.toPx() }, density, resolver)
        val owner = Class.forName(if (legacy) "$pkg.BaselineReaderPaginationKt" else "$pkg.ReaderPaginationKt")
        val method = owner.declaredMethods.single { it.name == "paginateChunked" }.apply { isAccessible = true }
        try {
            return method.invoke(null, text, params, null, check) as List<String>
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }

    @Test fun deterministicShortChaptersKeepEveryOriginalPageBreak() {
        setup()
        val random = Random(20261026)
        val atoms = listOf("这是一行中文正文，保留字形和标点。", "Latin gypq text 012345 ", "😀𠮷", "\n", "\n\n", "[IMG:/sample.png|800|400]")
        repeat(16) { case ->
            val text = buildString { repeat(random.nextInt(20, 100)) { append(atoms[random.nextInt(atoms.size)]) } }
            val width = listOf(320, 560, 1024)[case % 3]
            val height = listOf(480, 800)[case % 2]
            val style = TextStyle(fontSize = listOf(20, 24, 32)[case % 3].sp,
                lineHeight = listOf(20, 28, 40)[case % 3].sp,
                fontFamily = if (case % 2 == 0) FontFamily.Serif else FontFamily.SansSerif,
                fontWeight = if (case % 4 == 0) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = (case % 3).sp, platformStyle = PlatformTextStyle(includeFontPadding = false))
            val old = paginate(true, text, width, height, style, titleReserve = 120)
            val actual = paginate(false, text, width, height, style, titleReserve = 120)
            assertEquals("case $case page boundaries", old, actual)
            assertEquals(text, actual.joinToString(""))
        }
    }

    @Test fun largeChapterRestoresOnlyTheNewlinesLostAtOldChunkBoundaries() {
        setup()
        val text = (1..6000).joinToString("\n") { "第 $it 行：中文正文，用于保留完整锚点。" }
        val old = paginate(true, text, width = 1000, height = 1800)
        val actual = paginate(false, text, width = 1000, height = 1800)
        assertEquals(text, actual.joinToString(""))
        assertNotEquals(text, old.joinToString(""))
        val dropped = buildString {
            var start = 0
            while (start < text.length) {
                val end = minOf(text.length, start + 40_000)
                val newline = if (end < text.length) text.lastIndexOf('\n', end) else -1
                if (newline > start + 20_000) {
                    append(text, start, newline)
                    start = newline + 1
                } else {
                    append(text, start, end)
                    start = end
                }
            }
        }
        assertEquals("Frozen legacy defect is exactly the dropped boundary newline", dropped, old.joinToString(""))
        assertEquals(dropped.replace("\n", ""), actual.joinToString("").replace("\n", ""))
    }

    @Test fun cancellationStopsInsideTheCurrentChunkInsteadOfFinishingEveryPage() {
        setup()
        var checks = 0
        try {
            paginate(false, "单章取消检测正文。".repeat(3000), check = {
                checks++
                if (checks == 8) throw CancellationException("newer window requested")
            })
            fail("Cancelled layout must not produce a completed page set")
        } catch (expected: CancellationException) {
            assertEquals(8, checks)
        }
    }

    @Test fun localCpuAndManagedAllocationPairsRecordEquivalentWork() {
        setup()
        // No chunk-boundary bug in this workload; both methods must produce identical pages.
        val text = "正文性能采样 gypq，算法保持行边界。".repeat(500)
        assertEquals(paginate(true, text), paginate(false, text))
        // Android's compile boot classpath omits JVM management APIs; invoke the host
        // interfaces reflectively, just like the existing local slider CPU benchmark.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuType = Class.forName("java.lang.management.ThreadMXBean")
        val cpuMethod = cpuType.getMethod("getCurrentThreadCpuTime")
        assertTrue(cpuType.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) as Boolean)
        val allocationType = Class.forName("com.sun.management.ThreadMXBean")
        assertTrue(allocationType.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        if (!(allocationType.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean)) {
            allocationType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType!!).invoke(bean, true)
        }
        val allocationMethod = allocationType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType!!)
        val thread = Thread.currentThread().id
        fun cpuNow() = cpuMethod.invoke(bean) as Long
        fun allocatedNow() = allocationMethod.invoke(bean, thread) as Long
        val layoutsPerSample = 60 // Windows thread CPU clocks advance in ~15.625 ms ticks.
        fun sample(legacy: Boolean): Pair<Long, Long> {
            val bytes = allocatedNow()
            val cpu = cpuNow()
            repeat(layoutsPerSample) { paginate(legacy, text) }
            return (cpuNow() - cpu) to (allocatedNow() - bytes)
        }
        repeat(4) { sample(it % 2 == 0) }
        val old = mutableListOf<Pair<Long, Long>>()
        val actual = mutableListOf<Pair<Long, Long>>()
        repeat(10) { pair ->
            if (pair % 2 == 0) { old += sample(true); actual += sample(false) }
            else { actual += sample(false); old += sample(true) }
        }
        fun values(runs: List<Pair<Long, Long>>) = runs.joinToString(",", "[", "]") { "{\"cpuNs\":${it.first},\"managedBytes\":${it.second}}" }
        val out = File("../artifacts/performance-stability-2026-10-26/pagination-pairs.json")
        out.parentFile.mkdirs()
        out.writeText("{\"environment\":\"Robolectric API34 native text; Windows JVM managed allocation; local layout only\",\"pairs\":10,\"layoutsPerSample\":$layoutsPerSample,\"baseline\":${values(old)},\"optimized\":${values(actual)}}")
    }
}
