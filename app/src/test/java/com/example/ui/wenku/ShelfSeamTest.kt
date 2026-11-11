package com.example.ui.wenku

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **打开 / 归位动效的无缝性回归测试（纯 JVM，不需要模拟器）。**
 *
 * 用户的要求是"两段动画之间一点突变都不能有"。以前那套实现（书架画布转正 →
 * 另一个渲染器放大封面贴图）在结构上做不到：旋转结束时缩放速度要重新起步，
 * 所以任何"整体调曲线"都只是在缩小断点，删不掉断点。
 *
 * 现在整段动画只有一个渲染器 [ShelfVolumeArtist] 和一条路径 [ShelfOpenTimeline]：
 * 书架静止帧 = `poseAt(0)`，覆盖层第一帧也是 `poseAt(0)`；覆盖层最后一帧 = `poseAt(1)`。
 *
 * 这个测试做四件事：
 * 1. **逐像素**比对"书架静止帧"和"飞行第 0 帧"——必须完全相同（mean = 0、max = 0）；
 * 2. 沿时间轴逐帧渲染，统计相邻帧色差，断言没有"跳过去"的一步；
 * 3. 几何单调 + 单帧位移有界 + `p = .78` 时封面真的铺满屏幕；
 * 4. 归位最后一帧精确落在书架静止姿态上（否则书架接回来会跳）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ShelfSeamTest {

    private val viewportWidth = 540
    private val viewportHeight = 1170
    private val density = 2.625f

    private fun art(): ShelfVolumeArt {
        val colors = WenkuColors(
            paper = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            panel = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            milk = androidx.compose.ui.graphics.Color(0xFFFEFDF8),
            raised = androidx.compose.ui.graphics.Color(0xFFF7F5EF),
            raisedHigh = androidx.compose.ui.graphics.Color(0xFFEFEDE6),
            outline = androidx.compose.ui.graphics.Color(0xFFE2DED2),
            ink = androidx.compose.ui.graphics.Color(0xFF161613),
            inkSecondary = androidx.compose.ui.graphics.Color(0xFF646B62),
            isDark = false
        )
        val palette = ShelfPalette(colors, density)
        val cover = generateShelfCover(
            title = "剑来",
            author = "烽火戏诸侯",
            tag = "TXT",
            dark = false,
            widthPx = 128,
            heightPx = 206,
            density = Density(density)
        )
        return ShelfVolumeArt(
            id = 1005,
            coverWidth = 128f,
            body = 206f,
            thickness = 20f,
            topInset = 0f,
            tint = 0xFFEFE9DC.toInt(),
            material = MATERIAL_MATTE,
            cover = cover,
            number = "05",
            fileCode = "WK-01005",
            runs = listOf(SpineRun("剑", false), SpineRun("来", false)),
            truncated = false,
            palette = palette,
            unit = density
        )
    }

    /** 书架上的静止位置（root 坐标，随便取一个偏左下的位置）。 */
    private val restOx = 150f
    private val restOy = 320f

    private fun poseAt(p: Float, alphaOverride: Float? = null): ShelfVolumePose {
        val a = art()
        return ShelfOpenTimeline.poseAt(
            p = p,
            art = a,
            restOx = restOx,
            restOy = restOy,
            targetCenterX = viewportWidth * .5f,
            targetCenterY = viewportHeight * .5f,
            targetScale = ShelfOpenTimeline.fillScale(a, viewportWidth.toFloat(), viewportHeight.toFloat()),
            alphaOverride = alphaOverride
        )
    }

    private fun pivotX(pose: ShelfVolumePose, a: ShelfVolumeArt): Float =
        pose.ox + a.thickness * pose.thicknessScale + a.coverWidth * pose.depthX * .5f

    private fun pivotY(pose: ShelfVolumePose, a: ShelfVolumeArt): Float =
        pose.oy + a.topInset + a.coverWidth * pose.depthY * .5f + a.body * .5f

    private fun render(pose: ShelfVolumePose): Bitmap {
        val bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 浅色底：便于看出书的边缘
        canvas.drawColor(0xFFFDFCF8.toInt())
        ShelfVolumeArtist().draw(canvas, art(), pose)
        return bitmap
    }

    @Test
    fun flightFirstFrameIsPixelIdenticalToShelfRestingFrame() {
        // 书架静止帧 = 静止姿态；飞行第 0 帧 = poseAt(0)。两者必须逐像素相同。
        val shelfPose = RESTING_VOLUME_POSE.copy(ox = restOx, oy = restOy)
        val a = render(shelfPose)
        val b = render(poseAt(0f))
        var diff = 0L
        var max = 0
        for (y in 0 until viewportHeight) {
            for (x in 0 until viewportWidth) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                val d = kotlin.math.abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF)) +
                    kotlin.math.abs((pa shr 8 and 0xFF) - (pb shr 8 and 0xFF)) +
                    kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                diff += d
                if (d > max) max = d
            }
        }
        val mean = diff.toDouble() / (viewportWidth * viewportHeight)
        println("SEAM first-frame mean=$mean max=$max")
        assertEquals("飞行第 0 帧必须与书架静止帧逐像素相同", 0.0, mean, 0.0001)
        assertEquals("飞行第 0 帧存在跳变像素", 0, max)
    }

    @Test
    fun noFrameJumpsAcrossTheWholeOpenTimeline() {
        val steps = 60
        var previous: Bitmap? = null
        val means = ArrayList<Double>(steps)
        var worstMean = 0.0
        var worstChanged = 0.0
        for (i in 0..steps) {
            val p = i.toFloat() / steps
            val frame = render(poseAt(p))
            val prev = previous
            if (prev != null) {
                var diff = 0L
                var changed = 0
                for (y in 0 until viewportHeight step 2) {
                    for (x in 0 until viewportWidth step 2) {
                        val pa = prev.getPixel(x, y)
                        val pb = frame.getPixel(x, y)
                        val d = kotlin.math.abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF)) +
                            kotlin.math.abs((pa shr 8 and 0xFF) - (pb shr 8 and 0xFF)) +
                            kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                        diff += d
                        if (d > 18) changed++
                    }
                }
                val samples = (viewportHeight / 2) * (viewportWidth / 2)
                val mean = diff.toDouble() / samples
                val ratio = changed.toDouble() / samples
                means += mean
                if (mean > worstMean) worstMean = mean
                if (ratio > worstChanged) worstChanged = ratio
            }
            previous = frame
        }
        println("SEAM frames=${means.size} worstMean=%.3f worstChanged=%.4f".format(worstMean, worstChanged))
        println("SEAM means=" + means.joinToString(",") { "%.2f".format(it) })
        // 判据用"局部尖峰"而不是"和全局中位数比"：整段动画本来就一头一尾最慢、中间最快，
        // 拿全局中位数当基准会把正常的加速段判成跳变。真正能识别"两段动画衔接"的
        // 特征是**某一帧突然变了另一帧两倍以上**，而它左右两步都很小。
        var worstSpike = 0.0
        var worstSpikeAt = -1
        for (k in 1 until means.size - 1) {
            val neighbours = maxOf(means[k - 1], means[k + 1])
            val spike = means[k] / (neighbours + .05)
            if (spike > worstSpike) {
                worstSpike = spike
                worstSpikeAt = k
            }
        }
        println("SEAM worstSpike=%.2f at step %d".format(worstSpike, worstSpikeAt))
        assertTrue(
            "第 $worstSpikeAt 步相对邻近帧突增 ${"%.2f".format(worstSpike)}×，说明动画里存在跳变",
            worstSpike < 1.9
        )
        assertTrue("相邻帧有整块画面瞬移（worstChanged=$worstChanged）", worstChanged < .22)
    }

    @Test
    fun geometryIsMonotoneAndArrivesFullScreen() {
        val steps = 60
        val a = art()
        var lastPivotX = Float.NaN
        var lastScale = 0f
        var maxStep = 0f
        var prevPivotX = 0f
        var prevPivotY = 0f
        for (i in 0..steps) {
            val p = i.toFloat() / steps
            val pose = poseAt(p)
            val px = pivotX(pose, a)
            val py = pivotY(pose, a)
            if (!lastPivotX.isNaN()) {
                assertTrue("封面中心在时间轴上回退（x）", px >= lastPivotX - .01f)
                assertTrue("缩放不是单调递增", pose.scale >= lastScale - 1e-4f)
                val step = kotlin.math.hypot(px - prevPivotX, py - prevPivotY)
                if (step > maxStep) maxStep = step
            }
            prevPivotX = px
            prevPivotY = py
            lastPivotX = px
            lastScale = pose.scale
        }
        println("SEAM maxPivotStepPx=%.2f".format(maxStep))
        val diagonal = kotlin.math.hypot(viewportWidth.toFloat(), viewportHeight.toFloat())
        assertTrue("单帧位移过大：$maxStep", maxStep < diagonal * .04f)

        // p = .78 时封面必须已经铺满整个屏幕（之后才切路由、才淡出）。
        val arrive = poseAt(.78f)
        val width = a.coverWidth * arrive.depthX * arrive.scale
        val height = a.body * arrive.scale
        val cx = pivotX(arrive, a)
        val cy = pivotY(arrive, a)
        assertTrue("封面左侧没盖住屏幕", cx - width / 2f <= 0.5f)
        assertTrue("封面右侧没盖住屏幕", cx + width / 2f >= viewportWidth - 0.5f)
        assertTrue("封面上边没盖住屏幕", cy - height / 2f <= 0.5f)
        assertTrue("封面下边没盖住屏幕", cy + height / 2f >= viewportHeight - 0.5f)
        println("SEAM arrive cover=${width}x$height center=($cx,$cy)")
    }

    @Test
    fun handoffPointKeepsTheBookPinnedToTheShelf() {
        // 书架在 progress > HANDOFF_P 之后不再画这本书。交接必须发生在"还没动"的时刻：
        // 用 60fps 下的第一帧进度量化这个误差，超过 1px 就等于让位的一瞬间书跳了一下。
        val a = art()
        val rest = poseAt(0f)
        val oneFrame = 1000f / 60f / ShelfOpenTimeline.OPEN_MS
        val handoff = poseAt(maxOf(WenkuVolumeFlightBridge.HANDOFF_P, oneFrame))
        val dx = kotlin.math.abs(pivotX(handoff, a) - pivotX(rest, a))
        val dy = kotlin.math.abs(pivotY(handoff, a) - pivotY(rest, a))
        println("SEAM handoff(one frame p=${"%.3f".format(oneFrame)}) shift=($dx,$dy) scale=${handoff.scale}")
        assertTrue("交接点位移过大：$dx", dx < 1.0f)
        assertTrue("交接点位移过大：$dy", dy < 1.0f)
        assertEquals("交接点必须还是满不透明", 1f, handoff.alpha, 1e-4f)
    }

    @Test
    fun closeEndsExactlyOnTheShelfPose() {
        // 归位：p 从 1 走回 0，最后一帧必须回到静止姿态（书架随即接回来），透明度也要是 1。
        val end = poseAt(0f, alphaOverride = 1f)
        val rest = RESTING_VOLUME_POSE.copy(ox = restOx, oy = restOy)
        assertEquals(rest.ox, end.ox, .001f)
        assertEquals(rest.oy, end.oy, .001f)
        assertEquals(rest.depthX, end.depthX, .001f)
        assertEquals(rest.depthY, end.depthY, .001f)
        assertEquals(rest.skew, end.skew, .001f)
        assertEquals(1f, end.thicknessScale, .001f)
        assertEquals(1f, end.alpha, .001f)
        assertEquals(1f, end.scale, .001f)
        // 归位一开始必须是"不透明的整屏封面"，否则阅读器会先露一下再被盖住。
        val start = poseAt(1f, alphaOverride = 1f)
        assertEquals(1f, start.alpha, .001f)
        println("SEAM close start scale=${start.scale} alpha=${start.alpha}")
    }

    @Test
    fun coverFillsWholeViewportAtStartOfClose() {
        val a = art()
        val pose = poseAt(1f, alphaOverride = 1f)
        val width = a.coverWidth * pose.depthX * pose.scale
        val height = a.body * pose.scale
        val cx = pivotX(pose, a)
        val cy = pivotY(pose, a)
        val rect = Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
        assertTrue("归位起点没有铺满屏幕：$rect", rect.width >= viewportWidth && rect.height >= viewportHeight)
    }

    /** 把关键帧存成图片，给"这段到底是不是一段动画"留一份可肉眼复核的证据。 */
    @Test
    fun dumpTimelineFrames() {
        val dir = java.io.File("build/tmp/seam")
        dir.mkdirs()
        for (i in 0..10) {
            val p = i / 10f
            val bitmap = render(poseAt(p))
            val file = java.io.File(dir, "frame-%02d.png".format(i))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        println("SEAM frames dumped to ${dir.absolutePath}")
    }
}
