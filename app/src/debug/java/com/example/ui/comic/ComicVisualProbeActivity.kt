package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import java.io.File
import kotlin.random.Random

/**
 * 视觉验证探针（仅 debug 变体，第 3 节 Agent B 用）。
 *
 * 生成 12 张合成漫画页（含 1 张跨页宽图）写入 cacheDir，直接宿主 ComicReaderCore；
 * 配置通过 intent extras 注入全局 store，配合 adb 截图做逐帧视觉审查：
 *
 *   adb shell am start -n com.aistudio.novelreader.kxmpzq/com.example.ui.comic.ComicVisualProbeActivity \
 *     --es mode DOUBLE --es direction RTL --es anim CURL --es fit FIT_PAGE --es spacing 24
 *
 * extras: mode(SINGLE/DOUBLE/WEBTOON/CONTINUOUS/MAGNETIC) direction(LTR/RTL/TTB)
 *         anim(NONE/SLIDE/FADE/CURL) fit page(spacing 值) scene snap(0/1) shiftX shiftY
 */
class ComicVisualProbeActivity : ComponentActivity() {

    /**
     * 与 MainActivity 相同的音量键拦截（第 28 条）：探针 Activity 独立于主 Activity，
     * 不补这段拦截的话音量键永远到不了 ComicVolumeKeyBridge，交互矩阵无法验证。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val isDownAction = event.action == android.view.KeyEvent.ACTION_DOWN
            if (ComicVolumeKeyBridge.dispatch(event.keyCode, isDownAction)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2：纸底带可区分色调（暖米/冷蓝/青绿，亮度均落在 dominantBackground
        // 的 [24,238] 统计区间内）——第 25 条验收要求"色调差异明显的相邻两页"，
        // 旧版近白纸底被近白过滤排除，主色提取退化为小圆点噪声，渐变不可测。
        // 第六轮 5.5：pw/ph 注入页面尺寸（默认 900×1300≈4.7MB ARGB）；大页
        // （如 1960×2800≈22MB）用于压出"预载窗口字节超过主 LRU 上限被逐出"
        // 的重解码路径；缓存目录按尺寸分片，避免与旧小页混用。
        val pw = intent.getIntExtra("pw", 900)
        val ph = intent.getIntExtra("ph", 1300)
        // jp=1：日文+英文文字气泡页（第十五轮漫画翻译端到端验证用），独立缓存目录
        val jp = intent.getBooleanExtra("jp", false)
        val dirName = if (jp) "probe_pages_jp_${pw}x${ph}" else "probe_pages_v2_${pw}x${ph}"
        val dir = File(cacheDir, dirName).apply { mkdirs() }
        val pages = buildList {
            for (i in 0 until 12) {
                val f = File(dir, "p$i.png")
                if (!f.exists()) f.writeBytes(
                    if (jp) makeJpPage(i, pw, ph) else makePage(i, pw, ph)
                )
                add(ComicPageRef.Local("probe$i", f.absolutePath) as ComicPageRef)
            }
            // 跨页宽图（gutter 缝在中央）
            val wf = File(dir, "wide.png")
            if (!wf.exists()) wf.writeBytes(makeWidePage())
            add(ComicPageRef.Local("probeWide", wf.absolutePath) as ComicPageRef)
        }

        val mode = intent.getStringExtra("mode")?.let { runCatching { ComicMode.valueOf(it) }.getOrNull() } ?: ComicMode.SINGLE
        val direction = intent.getStringExtra("direction")?.let { runCatching { ComicDirection.valueOf(it) }.getOrNull() } ?: ComicDirection.RTL
        val anim = intent.getStringExtra("anim")?.let { runCatching { ComicPageAnim.valueOf(it) }.getOrNull() } ?: ComicPageAnim.SLIDE
        val fit = intent.getStringExtra("fit")?.let { runCatching { ComicFit.valueOf(it) }.getOrNull() } ?: ComicFit.FIT_WIDTH
        val spacing = intent.getFloatExtra("spacing", 8f)
        val snap = intent.getBooleanExtra("snap", true)
        val shiftX = intent.getFloatExtra("shiftX", 0f)
        val bg = intent.getStringExtra("bg")?.let { runCatching { ComicBgType.valueOf(it) }.getOrNull() }
        val scene = intent.getStringExtra("scene")?.let { runCatching { ComicScene.valueOf(it) }.getOrNull() } ?: ComicScene.NONE
        val split = intent.getBooleanExtra("split", false)
        // 第七轮第 1 条：增强档位注入（ANIME4K/WAIFU2X/SUPER_RES/CAS/OFF）
        val enhance = intent.getStringExtra("enhance")?.let { runCatching { ComicEnhanceMode.valueOf(it) }.getOrNull() }
            ?: ComicEnhanceMode.OFF
        val enhanceStrength = intent.getIntExtra("enhanceStrength", 60)
        // 新反馈条目4：在线慢网络验证——remoteUrlBase 给定时页列表全部为 Remote 引用
        //（宿主 python 限速服务器经 10.0.2.2 提供图片，Coil 全链路走真实网络）
        val remoteUrlBase = intent.getStringExtra("remoteUrlBase")
        // 第十五轮：漫画翻译端到端（jp=1 生成日文气泡页；translation=true 开启整页翻译）
        val translation = intent.getBooleanExtra("translation", false)

        val store = ComicSettingsStore(this)
        store.saveGlobalConfig(
            ComicReaderConfig(
                mode = mode, direction = direction, pageAnim = anim, fit = fit,
                pageSpacingDp = spacing, webtoonSnap = snap,
                doubleShiftXDp = shiftX, splitWide = split,
                bgType = bg ?: ComicBgType.BLACK, scene = scene,
                enhanceMode = enhance, enhanceStrength = enhanceStrength,
                gestureSwipe = true, doubleTapZoom = true, longPressZoom = true,
                translationEnabled = translation,
            )
        )

        // Remote 模式：合成页写到 cacheDir 由宿主服务器提供？——不，直接给 Remote 引用，
        // 服务器端有独立生成的图片；本地合成页仅 Local 模式使用
        val effectivePages = if (remoteUrlBase != null) {
            val base = remoteUrlBase.trimEnd('/')
            (0 until 12).map { i ->
                ComicPageRef.Remote(
                    id = "u_probe_$i",
                    url = "$base/p$i.png",
                    headers = emptyMap(),
                    referer = null,
                ) as ComicPageRef
            }
        } else pages

        setContent {
            com.example.ui.theme.MyApplicationTheme(darkTheme = false, colorPrimaryIndex = intent.getIntExtra("accent", 4)) {
            ComicReaderCore(
                pages = effectivePages,
                title = "视觉探针",
                chapterTitle = "$mode · $direction · $anim" + (remoteUrlBase?.let { " · REMOTE" } ?: ""),
                bookKey = null,
                initialPage = 0,
                modifier = Modifier.fillMaxSize(),
                onExit = { finish() },
            )
            }
        }
    }

    /** 合成漫画页：纸底 + 面板框 + 页码 + 每页不同的图案（视觉可辨识）。
     *  绘制坐标固定在 900×1300 设计空间，按 pw/ph 目标尺寸整体 scale。 */
    private fun makePage(index: Int, pw: Int = 900, ph: Int = 1300): ByteArray {
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pw / 900f, ph / 1300f)
        val w = 900; val h = 1300
        // 三色轮换纸底：暖米 / 冷蓝 / 青绿（亮度 199-214，进 dominantBackground 统计）
        c.drawColor(
            when (index % 3) {
                0 -> Color.rgb(235, 208, 176)
                1 -> Color.rgb(176, 205, 235)
                else -> Color.rgb(196, 228, 190)
            },
        )
        val p = Paint().apply { isAntiAlias = true }
        // 面板框
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.BLACK
        c.drawRect(60f, 100f, w - 60f, 560f, p)
        c.drawRect(60f, 620f, w - 60f, h - 180f, p)
        // 每页独特内容
        p.style = Paint.Style.FILL
        val rnd = Random(index * 31)
        for (k in 0 until 6) {
            p.color = Color.rgb(60 + rnd.nextInt(180), 60 + rnd.nextInt(160), 120 + rnd.nextInt(120))
            c.drawCircle(
                140f + rnd.nextInt(w - 280).toFloat(),
                150f + rnd.nextInt(h - 400).toFloat(),
                40f + rnd.nextInt(90).toFloat(), p,
            )
        }
        // 大号页码（翻页/方向验证的核心锚点）
        p.color = Color.rgb(30, 30, 30); p.textSize = 220f; p.isFakeBoldText = true
        c.drawText("${index + 1}", w / 2f - 60f, h / 2f + 120f, p)
        return bitmapPng(bmp)
    }

    /** 日文气泡页（翻译验证）：白底气泡 + 日文/英文台词，字号与布局贴近真实漫画。 */
    private fun makeJpPage(index: Int, pw: Int, ph: Int): ByteArray {
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pw / 900f, ph / 1300f)
        c.drawColor(Color.rgb(246, 244, 240))
        val p = Paint().apply { isAntiAlias = true }
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.BLACK
        c.drawRect(60f, 60f, 840f, 620f, p)
        c.drawRect(60f, 660f, 840f, 1240f, p)
        val lines = listOf(
            listOf("おはよう！"),
            listOf("今日はいい天気だね"),
            listOf("そうだね、", "散歩に行こう"),
            listOf("Hello!", "Let's go"),
        )
        p.style = Paint.Style.FILL
        p.color = Color.rgb(20, 20, 20)
        p.textSize = 42f
        p.isFakeBoldText = true
        val bubbles = arrayOf(
            floatArrayOf(110f, 120f, 620f, 210f),
            floatArrayOf(110f, 700f, 700f, 790f),
            floatArrayOf(110f, 830f, 700f, 930f),
            floatArrayOf(110f, 1060f, 560f, 1150f),
        )
        val fill = Paint().apply { color = Color.WHITE }
        lines.forEachIndexed { bi, bubbleLines ->
            val b = bubbles[bi]
            c.drawRoundRect(b[0] - 18f, b[1] - 18f, b[2] + 18f, b[3] + 18f, 40f, 40f, fill)
            bubbleLines.forEachIndexed { li, line ->
                c.drawText(line, b[0], b[1] + 50f + li * 52f, p)
            }
        }
        return bitmapPng(bmp)
    }

    /** 跨页宽图：左右两页图案 + 中央暗缝（gutter） */
    private fun makeWidePage(): ByteArray {
        val w = 2200; val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(245, 243, 238))
        val p = Paint().apply { isAntiAlias = true; color = Color.rgb(80, 80, 80) }
        c.drawRect(w / 2f - 10f, 0f, w / 2f + 10f, h.toFloat(), p) // 装订缝
        p.color = Color.BLACK; p.style = Paint.Style.STROKE; p.strokeWidth = 6f
        c.drawRect(40f, 60f, w / 2f - 50f, h - 60f, p)
        c.drawRect(w / 2f + 50f, 60f, w - 40f, h - 60f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(40, 40, 40); p.textSize = 180f; p.isFakeBoldText = true
        c.drawText("L", w / 4f - 50f, h / 2f + 60f, p)
        c.drawText("R", w * 3 / 4f - 50f, h / 2f + 60f, p)
        return bitmapPng(bmp)
    }

    private fun bitmapPng(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
