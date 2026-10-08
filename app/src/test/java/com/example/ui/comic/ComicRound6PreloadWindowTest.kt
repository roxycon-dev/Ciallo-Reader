package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 第六轮 5.5（用户回归反馈"本地书翻页仍偶现加载圈"）验收测试：
 *
 * 根因 1（预载互斥取消）：阅读器逐槽多次调用 preload，每次调用的"批次外取消"
 *   会取消同窗口其它槽位刚入队的任务——双页模式只有下一跨最后一个槽位真正预载、
 *   后退翻页几乎从不预载。修复 = 整窗一次 preloadWindow。
 * 根因 2（窗口逐出重解码）：主 LRU 上限 = 堆 1/6（24-96MB），而单页处理结果
 *   最高 ~29MB（增强 2x 档），预载窗口字节量超上限 → 窗口页在翻到之前被逐出。
 *   修复 = ensureWindowCapacity 动态扩容（实测字节优先，尺寸估算兜底，硬上限护栏）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComicRound6PreloadWindowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tone(enhance: ComicEnhanceMode = ComicEnhanceMode.OFF) =
        ComicImagePipeline.Toning(enhanceMode = enhance)

    private fun entry(id: String, path: String = "/nonexistent", enhance: ComicEnhanceMode = ComicEnhanceMode.OFF) =
        ComicPageLoader.WindowEntry(
            ref = ComicPageRef.Local(id, path),
            cacheKey = "$id|FULL|fp|r0",
            geo = ComicImagePipeline.Geometry(),
        )

    private fun realJpeg(id: String, w: Int = 40, h: Int = 60): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val f = File.createTempFile("pwin_$id", ".jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    /* ═══════════ 估算纯函数 ═══════════ */

    @Test
    fun `估算-OFF档不加2x且解码上限生效`() {
        assertEquals(1200L * 1600L * 4, ComicPageLoader(context).estimateProcessedBytes(1200, 1600, ComicEnhanceMode.OFF))
        // 4000×3000 → 解码等比钳 2800 → 2800×2100
        assertEquals(2800L * 2100L * 4, ComicPageLoader(context).estimateProcessedBytes(4000, 3000, ComicEnhanceMode.OFF))
        // CAS 同尺寸处理，无 2x
        assertEquals(1200L * 1600L * 4, ComicPageLoader(context).estimateProcessedBytes(1200, 1600, ComicEnhanceMode.CAS))
    }

    @Test
    fun `估算-各档与实际输出预算一致且不低估大图`() {
        val loader = ComicPageLoader(context)
        for (mode in ComicEnhanceMode.entries) for ((w, h) in listOf(600 to 900, 1200 to 1600, 1200 to 1800, 1801 to 1200, 2800 to 2000)) {
            val (tw, th) = if (mode == ComicEnhanceMode.WAIFU2X || mode == ComicEnhanceMode.SUPER_RES)
                ComicEdgeUpscaler.targetSize(w, h) else w to h
            assertEquals("$mode at ${w}x$h", tw.toLong() * th * 4, loader.estimateProcessedBytes(w, h, mode))
        }
        assertEquals(600L * 900 * 16, loader.estimateProcessedBytes(600, 900, ComicEnhanceMode.SUPER_RES))
        assertEquals(2800L * 2000 * 4, loader.estimateProcessedBytes(2800, 2000, ComicEnhanceMode.WAIFU2X))
        assertEquals(1200L * 1600 * 4, loader.estimateProcessedBytes(1200, 1600, ComicEnhanceMode.ANIME4K))
    }

    @Test
    fun `估算-尺寸未知按档位保守缺省`() {
        assertEquals(2800L * 2000L * 4, ComicPageLoader(context).estimateProcessedBytes(0, 0, ComicEnhanceMode.OFF))
        assertEquals(3200L * 2286L * 4, ComicPageLoader(context).estimateProcessedBytes(0, 0, ComicEnhanceMode.WAIFU2X))
    }

    /* ═══════════ 窗口容量扩容（根因 2）═══════════ */

    @Test
    fun `窗口容量-整窗字节超过基础上限时扩容到可容纳整窗`() {
        val loader = ComicPageLoader(context)
        val base = loader.cacheLimitForTest()
        val ceil = maxOf(base, (Runtime.getRuntime().maxMemory() / 3).toInt().coerceAtMost(128 shl 20))
        // 4 个未知尺寸 WAIFU2X 页 = 4 × 29.3MB ≈ 117.4MB
        loader.ensureWindowCapacity(
            List(4) { entry("cap$it", enhance = ComicEnhanceMode.WAIFU2X) },
            tone(ComicEnhanceMode.WAIFU2X),
        )
        val needed = 4L * 3200L * 2286L * 4L
        val expected = needed.coerceAtLeast(base.toLong()).coerceAtMost(ceil.toLong()).toInt()
        assertEquals("主缓存上限应扩到整窗字节（钳在 [基础, 硬上限]）", expected, loader.cacheLimitForTest())
    }

    @Test
    fun `窗口容量-实测字节优先且窗口缩小时回落不低于基础值`() {
        val loader = ComicPageLoader(context)
        val base = loader.cacheLimitForTest()
        // 先扩到大窗口
        loader.ensureWindowCapacity(
            List(4) { entry("shrink$it", enhance = ComicEnhanceMode.WAIFU2X) },
            tone(ComicEnhanceMode.WAIFU2X),
        )
        assertTrue(loader.cacheLimitForTest() > base)
        // 单个小窗口回落：实测 40×60×4=9600B → 目标=base
        loader.ensureWindowCapacity(listOf(entry("small")), tone())
        assertEquals("窗口缩小时上限回落但不低于基础值", base, loader.cacheLimitForTest())
    }

    @Test
    fun `窗口驻留-扩容后窗口页在字节压力下不被逐出`() {
        val loader = ComicPageLoader(context)
        val base = loader.cacheLimitForTest()
        // 4 页 × 28MB = 112MB；只有扩容后才可能全部驻留（基础上限典型 24-96MB）
        val perPage = 2000L * 3584L * 4L   // ≈ 28.6MB
        val total = 4 * perPage
        assumeTrue("本环境基础上限已超过测试负载，跳过（大堆 JVM）", base < total)
        loader.ensureWindowCapacity(
            List(4) { entry("keep$it", enhance = ComicEnhanceMode.WAIFU2X) },
            tone(ComicEnhanceMode.WAIFU2X),
        )
        assertTrue("扩容后上限必须 ≥ 窗口总字节", loader.cacheLimitForTest() >= total)
        repeat(4) { i ->
            loader.putProcessedForTest("keep$i|FULL|fp|r0", Bitmap.createBitmap(2000, 3584, Bitmap.Config.ARGB_8888))
        }
        repeat(4) { i ->
            assertNotNull("窗口页 keep$i 在扩容上限下必须驻留（旧实现按基础上限会被逐出）",
                loader.peekProcessed("keep$i|FULL|fp|r0"))
        }
    }

    /* ═══════════ 整窗预载语义（根因 1）═══════════ */

    @Test
    fun `整窗预载-窗口内全部槽位真实入队加载`() = runBlocking {
        val loader = ComicPageLoader(context)
        val paths = (0 until 3).map { realJpeg("all$it") }
        val window = paths.mapIndexed { i, p -> entry("all$i", p) }
        loader.preloadWindow(window, tone())
        // 回归契约：旧实现逐槽调用时每次都会取消其它槽位任务，只有最后一个槽位
        // 真正加载；整窗一次提交后 3 页必须全部进入处理结果缓存
        withTimeout(15_000) {
            while (window.any { loader.peekProcessed(it.cacheKey) == null }) delay(50)
        }
        window.forEach { assertNotNull("窗口页 ${it.ref.id} 必须被预载（互斥取消回归）", loader.peekProcessed(it.cacheKey)) }
    }

    @Test
    fun `整窗预载-窗口平移只取消离开窗口的任务`() = runBlocking {
        val loader = ComicPageLoader(context)
        val p = (0 until 4).map { realJpeg("shift$it") }
        val w1 = listOf(entry("shift0", p[0]), entry("shift1", p[1]), entry("shift2", p[2]))
        loader.preloadWindow(w1, tone())
        val w2Keys = setOf(entry("shift1").cacheKey, entry("shift2").cacheKey, entry("shift3", p[3]).cacheKey)
        loader.preloadWindow(
            listOf(entry("shift1", p[1]), entry("shift2", p[2]), entry("shift3", p[3])),
            tone(),
        )
        // 平移后：离开窗口的 shift0 任务必须被取消（节流契约保持），
        // 留在窗口的 shift1/shift2 不受影响，新页 shift3 正常入队
        val active = loader.activePreloadKeysForTest()
        assertTrue("批次外任务应被取消：active=$active", active.all { it in w2Keys })
        withTimeout(15_000) {
            while (loader.peekProcessed(entry("shift3", p[3]).cacheKey) == null) delay(50)
        }
        assertNotNull("新窗口页必须完成预载", loader.peekProcessed(entry("shift3", p[3]).cacheKey))
    }

    @Test
    fun `整窗预载-空窗口与重复键安全`() {
        val loader = ComicPageLoader(context)
        loader.preloadWindow(emptyList(), tone())   // 不抛
        val e = entry("dup")
        loader.preloadWindow(listOf(e, e.copyLike()), tone())
    }

    @Test
    fun `窗口驻留-LRU压力下窗口页免疫逐出（驻留表契约）`() {
        val loader = ComicPageLoader(context)
        // 窗口 3 页（小位图）驻留；随后灌入大量窗口外大位图把 LRU 挤爆——
        // 纯 LRU 方案（仅扩容+探针 bump）在组合期 lookahead put 竞态下会被逐出
        // （模拟器逐出日志实证），驻留表必须对 LRU 逐出免疫。
        val keys = (0 until 3).map { "pin$it|FULL|fp|r0" }
        loader.preloadWindow(
            keys.map { ComicPageLoader.WindowEntry(ComicPageRef.Local(it.substringBefore('|'), "/nonexistent"), it, ComicImagePipeline.Geometry()) },
            tone(),
        )
        keys.forEach { loader.putProcessedForTest(it, Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)) }
        // 再触发一次窗口更新使缓存中的窗口页进入驻留表
        loader.preloadWindow(
            keys.map { ComicPageLoader.WindowEntry(ComicPageRef.Local(it.substringBefore('|'), "/nonexistent"), it, ComicImagePipeline.Geometry()) },
            tone(),
        )
        // LRU 压力：塞入远超容量的窗口外页
        repeat(40) { i ->
            loader.putProcessedForTest("bulk$i|FULL|fp|r0", Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        }
        keys.forEach {
            assertNotNull("驻留窗口页 peekProcessed 必须在 LRU 压力下存活", loader.peekProcessed(it))
        }
    }

    @Test
    fun `窗口驻留-离窗解除与配置清空`() {
        val loader = ComicPageLoader(context)
        val k0 = "w0|FULL|fp|r0"
        val k1 = "w1|FULL|fp|r0"
        fun win(vararg ks: String) = loader.preloadWindow(
            ks.map { ComicPageLoader.WindowEntry(ComicPageRef.Local(it.substringBefore('|'), "/nonexistent"), it, ComicImagePipeline.Geometry()) },
            tone(),
        )
        loader.putProcessedForTest(k0, Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        loader.putProcessedForTest(k1, Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        win(k0, k1)
        win(k1)   // 窗口平移：k0 离窗
        loader.clearProcessedCache()   // 配置变更清空（含驻留）
        assertEquals(null, loader.peekProcessed(k1))
        assertEquals(null, loader.peekProcessed(k0))
    }

    @Test
    fun `窗口驻留-驻留序等于窗口优先级序`() {
        val loader = ComicPageLoader(context)
        fun win(vararg ks: String) = loader.preloadWindow(
            ks.map { ComicPageLoader.WindowEntry(ComicPageRef.Local(it.substringBefore('|'), "/nonexistent"), it, ComicImagePipeline.Geometry()) },
            tone(),
        )
        val k0 = "cur|FULL|fp|r0"; val k1 = "next|FULL|fp|r0"; val k2 = "prev|FULL|fp|r0"
        listOf(k0, k1, k2).forEach { loader.putProcessedForTest(it, Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)) }
        win(k0, k1, k2)   // 调用方按 当前→下一→上一 传入
        assertEquals(listOf(k0, k1, k2), loader.pinnedOrderForTest())
        // 窗口平移后驻留序跟随新窗口序（旧窗口页离窗解除）
        val k3 = "next2|FULL|fp|r0"
        loader.putProcessedForTest(k3, Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888))
        win(k1, k3, k0)
        assertEquals(listOf(k1, k3, k0), loader.pinnedOrderForTest())
    }

    private fun ComicPageLoader.WindowEntry.copyLike() = ComicPageLoader.WindowEntry(ref, cacheKey, geo)
}
