package com.example.ui.comic

import org.json.JSONArray
import org.json.JSONObject

/**
 * 漫画阅读器完整配置模型。
 *
 * 全部设置集中在一个 data class 中，序列化为 JSON 持久化：
 * - 全局默认配置（用户手动调整后保存）
 * - 预设（整套配置的快照，可创建/编辑/复制/删除/设为默认）
 * - 每本漫画独立覆盖（重新打开时自动恢复）
 */
data class ComicReaderConfig(
    /* ── 阅读模式 ── */
    val mode: ComicMode = ComicMode.SINGLE,
    val direction: ComicDirection = ComicDirection.RTL,
    /** 条漫模式磁吸到页边界（第 6/7 条：可关闭；无缝滚动恒无磁吸） */
    val webtoonSnap: Boolean = true,

    /* ── 页面显示 ── */
    val fit: ComicFit = ComicFit.FIT_WIDTH,
    /** 自定义缩放档（第 26 条）：基础适配方式 */
    val customFitBase: ComicFit = ComicFit.FIT_PAGE,
    /** 自定义缩放档：在基础适配上的附加缩放（0.5..2.5） */
    val customFitScale: Float = 1.0f,
    val pageSpacingDp: Float = 8f,          // 连续页面间距（滚动/条漫模式）
    val doubleGapDp: Float = 8f,            // 双页左右间距
    val doubleFirstAlone: Boolean = false,  // 双页模式首页单独显示（封面页）
    val doubleAlign: ComicDoubleAlign = ComicDoubleAlign.CENTER,
    val doubleShiftXDp: Float = 0f,         // 双页位置修正（扫描错位）
    val doubleShiftYDp: Float = 0f,

    /* ── 缩放 ── */
    val doubleTapZoom: Boolean = true,      // 双击放大（可关闭）
    val longPressZoom: Boolean = true,      // 长按放大
    val zoomWhileTurn: Boolean = false,     // 放大状态允许翻页

    /* ── 旋转 ── */
    val bookRotation: Int = 0,              // 整本旋转 0/90/180/270

    /* ── 裁边 ── */
    val cropMode: ComicCropMode = ComicCropMode.OFF,
    val manualCrop: List<Float>? = null,    // 手动裁边 [left,top,right,bottom] 归一化，null=未设置

    /* ── 大图拆分 ── */
    val splitWide: Boolean = false,         // 自动拆分宽页（跨页扫描）
    val splitReverse: Boolean = false,      // 拆分后左右顺序反转
    val splitPosition: Float = 0.5f,        // 手动拆分位置（0.3~0.7），0.5=居中

    /* ── 画质增强 ── */
    val enhanceMode: ComicEnhanceMode = ComicEnhanceMode.OFF,
    val enhanceStrength: Int = 60,          // 0..100

    /* ── 滤镜 ── */
    val filterBrightness: Int = 0,          // -100..100
    val filterContrast: Int = 0,            // -100..100
    val filterSaturation: Int = 0,          // -100..100
    val filterHue: Int = 0,                 // -180..180
    val filterGamma: Float = 1.0f,          // 0.5..2.2
    val filterSharpen: Int = 0,             // 0..100 锐化强度
    val filterShadow: Int = 0,              // -100..100 阴影提亮/压暗
    val filterBW: Boolean = false,          // 黑白

    /* ── 阅读背景 ── */
    val bgType: ComicBgType = ComicBgType.BLACK,
    val paperIntensity: Int = 35,           // 纸张纹理强度 0..100

    /* ── 场景 ── */
    val scene: ComicScene = ComicScene.NONE,
    val sceneSound: Boolean = true,         // 场景声音独立开关
    val sceneEffect: Boolean = true,        // 场景特效独立开关
    val sceneVolume: Int = 40,              // 0..100

    /* ── 翻页动画 ── */
    val pageAnim: ComicPageAnim = ComicPageAnim.SLIDE,

    /* ── 自动阅读 ── */
    val autoPageIntervalSec: Float = 6f,    // 自动翻页间隔
    val autoScrollSpeedDp: Float = 40f,     // 条漫自动滚动速度 dp/s

    /* ── 手势 ── */
    val gestureTapLeft: ComicGestureAction = ComicGestureAction.PREV,
    val gestureTapRight: ComicGestureAction = ComicGestureAction.NEXT,
    val gestureTapCenter: ComicGestureAction = ComicGestureAction.TOGGLE_CONTROLS,
    val gestureSwipe: Boolean = true,       // 单指滑动翻页
    val gesturePinchClose: Boolean = true,  // 双指合拢退出
    val gestureEdgeSwipe: Boolean = true,   // 侧边滑动快速关闭
    val gestureLongPressPanel: Boolean = false, // 长按呼出控制层（与长按放大冲突时放大优先）
    /** 音量键翻页（第 28 条）：仅漫画阅读页拦截；随阅读方向反转上下键语义 */
    val volumeKeyTurn: Boolean = true,

    /* ── 其它 ── */
    val showThumbPreview: Boolean = true,   // 进度条拖动缩略图
    val hideSystemBars: Boolean = true,     // 沉浸式

    /* ── 漫画翻译（第十五轮）：OCR 全离线；译文缓存按页持久化 ── */
    val translationEnabled: Boolean = false,
    /** 源语言：auto=按文字系统自动判定 / ja / en / ko */
    val translationLang: String = "auto",
    /** 译文字号缩放（0.8..1.4） */
    val translationTextScale: Float = 1.0f,
    /** 翻译引擎（第十八轮二级导航显式选择）：ai=自定义接口 / online=在线翻译 */
    val translationEngine: String = "online",
) {
    /** Repair imported/old settings before they reach layout or native rendering. */
    fun normalized(): ComicReaderConfig {
        fun Float.bounded(low: Float, high: Float, fallback: Float): Float =
            if (isFinite()) coerceIn(low, high) else fallback
        val crop = manualCrop?.takeIf { it.size == 4 && it.all(Float::isFinite) }?.let {
            val l = it[0].coerceIn(0f, 0.95f)
            val t = it[1].coerceIn(0f, 0.95f)
            val r = it[2].coerceIn(0f, 1f)
            val b = it[3].coerceIn(0f, 1f)
            if (r - l >= 0.05f - 0.00001f && b - t >= 0.05f - 0.00001f) listOf(l, t, r, b) else null
        }
        return copy(
            customFitBase = customFitBase.takeUnless { it == ComicFit.CUSTOM } ?: ComicFit.FIT_PAGE,
            customFitScale = customFitScale.bounded(0.5f, 2.5f, 1f),
            pageSpacingDp = pageSpacingDp.bounded(0f, 40f, 8f),
            doubleGapDp = doubleGapDp.bounded(0f, 40f, 8f),
            doubleShiftXDp = doubleShiftXDp.bounded(-40f, 40f, 0f),
            doubleShiftYDp = doubleShiftYDp.bounded(-40f, 40f, 0f),
            bookRotation = Math.floorMod(bookRotation / 90, 4) * 90,
            manualCrop = crop, splitPosition = splitPosition.bounded(0.3f, 0.7f, 0.5f),
            enhanceStrength = enhanceStrength.coerceIn(0, 100),
            filterBrightness = filterBrightness.coerceIn(-100, 100),
            filterContrast = filterContrast.coerceIn(-100, 100),
            filterSaturation = filterSaturation.coerceIn(-100, 100),
            filterHue = filterHue.coerceIn(-180, 180),
            filterGamma = filterGamma.bounded(0.5f, 2.2f, 1f),
            filterSharpen = filterSharpen.coerceIn(0, 100), filterShadow = filterShadow.coerceIn(-100, 100),
            paperIntensity = paperIntensity.coerceIn(0, 100), sceneVolume = sceneVolume.coerceIn(0, 100),
            autoPageIntervalSec = autoPageIntervalSec.bounded(1f, 120f, 6f),
            autoScrollSpeedDp = autoScrollSpeedDp.bounded(5f, 400f, 40f),
            translationTextScale = translationTextScale.bounded(0.8f, 1.4f, 1f),
            translationLang = translationLang.takeIf { it in setOf("auto", "ja", "en", "ko") } ?: "auto",
            translationEngine = translationEngine.takeIf { it in setOf("ai", "online") } ?: "online",
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("direction", direction.name)
        put("webtoonSnap", webtoonSnap)
        put("fit", fit.name)
        put("customFitBase", customFitBase.name)
        put("customFitScale", customFitScale)
        put("pageSpacingDp", pageSpacingDp)
        put("doubleGapDp", doubleGapDp)
        put("doubleFirstAlone", doubleFirstAlone)
        put("doubleAlign", doubleAlign.name)
        put("doubleShiftXDp", doubleShiftXDp)
        put("doubleShiftYDp", doubleShiftYDp)
        put("doubleTapZoom", doubleTapZoom)
        put("longPressZoom", longPressZoom)
        put("zoomWhileTurn", zoomWhileTurn)
        put("bookRotation", bookRotation)
        put("cropMode", cropMode.name)
        manualCrop?.let { put("manualCrop", JSONArray(it)) }
        put("splitWide", splitWide)
        put("splitReverse", splitReverse)
        put("splitPosition", splitPosition)
        put("enhanceMode", enhanceMode.name)
        put("enhanceStrength", enhanceStrength)
        put("filterBrightness", filterBrightness)
        put("filterContrast", filterContrast)
        put("filterSaturation", filterSaturation)
        put("filterHue", filterHue)
        put("filterGamma", filterGamma)
        put("filterSharpen", filterSharpen)
        put("filterShadow", filterShadow)
        put("filterBW", filterBW)
        put("bgType", bgType.name)
        put("paperIntensity", paperIntensity)
        put("scene", scene.name)
        put("sceneSound", sceneSound)
        put("sceneEffect", sceneEffect)
        put("sceneVolume", sceneVolume)
        put("pageAnim", pageAnim.name)
        put("autoPageIntervalSec", autoPageIntervalSec)
        put("autoScrollSpeedDp", autoScrollSpeedDp)
        put("gestureTapLeft", gestureTapLeft.name)
        put("gestureTapRight", gestureTapRight.name)
        put("gestureTapCenter", gestureTapCenter.name)
        put("gestureSwipe", gestureSwipe)
        put("gesturePinchClose", gesturePinchClose)
        put("gestureEdgeSwipe", gestureEdgeSwipe)
        put("gestureLongPressPanel", gestureLongPressPanel)
        put("volumeKeyTurn", volumeKeyTurn)
        put("showThumbPreview", showThumbPreview)
        put("hideSystemBars", hideSystemBars)
        put("translationEnabled", translationEnabled)
        put("translationLang", translationLang)
        put("translationTextScale", translationTextScale)
        put("translationEngine", translationEngine)
    }

    /** 滤镜/增强/裁边相关的指纹，用于位图处理缓存键。 */
    fun imagePipelineFingerprint(): String = listOf(
        cropMode.name, manualCrop?.joinToString(","), bookRotation,
        splitWide, splitPosition,
        enhanceMode.name, enhanceStrength,
        filterBrightness, filterContrast, filterSaturation, filterHue,
        String.format(java.util.Locale.ROOT, "%.3f", filterGamma), filterSharpen, filterShadow, filterBW
    ).joinToString("|")

    companion object {
        fun fromJson(json: JSONObject): ComicReaderConfig {
            fun <E : Enum<E>> safe(name: String, fallback: E, values: Array<E>): E =
                values.firstOrNull { it.name == json.optString(name, fallback.name) } ?: fallback

            return ComicReaderConfig(
                mode = safe("mode", ComicMode.SINGLE, ComicMode.entries.toTypedArray()),
                direction = safe("direction", ComicDirection.RTL, ComicDirection.entries.toTypedArray()),
                webtoonSnap = json.optBoolean("webtoonSnap", true),
                fit = safe("fit", ComicFit.FIT_WIDTH, ComicFit.entries.toTypedArray()),
                customFitBase = safe("customFitBase", ComicFit.FIT_PAGE, ComicFit.entries.toTypedArray()),
                customFitScale = json.optDouble("customFitScale", 1.0).toFloat().coerceIn(0.5f, 2.5f),
                pageSpacingDp = json.optDouble("pageSpacingDp", 8.0).toFloat(),
                doubleGapDp = json.optDouble("doubleGapDp", 8.0).toFloat(),
                doubleFirstAlone = json.optBoolean("doubleFirstAlone", false),
                doubleAlign = safe("doubleAlign", ComicDoubleAlign.CENTER, ComicDoubleAlign.entries.toTypedArray()),
                doubleShiftXDp = json.optDouble("doubleShiftXDp", 0.0).toFloat(),
                doubleShiftYDp = json.optDouble("doubleShiftYDp", 0.0).toFloat(),
                doubleTapZoom = json.optBoolean("doubleTapZoom", true),
                longPressZoom = json.optBoolean("longPressZoom", true),
                zoomWhileTurn = json.optBoolean("zoomWhileTurn", false),
                bookRotation = json.optInt("bookRotation", 0),
                cropMode = safe("cropMode", ComicCropMode.OFF, ComicCropMode.entries.toTypedArray()),
                manualCrop = runCatching {
                    val arr = json.optJSONArray("manualCrop") ?: return@runCatching null
                    if (arr.length() == 4) FloatArray(4) { arr.getDouble(it).toFloat() }.toList() else null
                }.getOrNull(),
                splitWide = json.optBoolean("splitWide", false),
                splitReverse = json.optBoolean("splitReverse", false),
                splitPosition = json.optDouble("splitPosition", 0.5).toFloat().coerceIn(0.3f, 0.7f),
                enhanceMode = safe("enhanceMode", ComicEnhanceMode.OFF, ComicEnhanceMode.entries.toTypedArray()),
                enhanceStrength = json.optInt("enhanceStrength", 60).coerceIn(0, 100),
                filterBrightness = json.optInt("filterBrightness", 0).coerceIn(-100, 100),
                filterContrast = json.optInt("filterContrast", 0).coerceIn(-100, 100),
                filterSaturation = json.optInt("filterSaturation", 0).coerceIn(-100, 100),
                filterHue = json.optInt("filterHue", 0).coerceIn(-180, 180),
                filterGamma = json.optDouble("filterGamma", 1.0).toFloat().coerceIn(0.5f, 2.2f),
                filterSharpen = json.optInt("filterSharpen", 0).coerceIn(0, 100),
                filterShadow = json.optInt("filterShadow", 0).coerceIn(-100, 100),
                filterBW = json.optBoolean("filterBW", false),
                bgType = safe("bgType", ComicBgType.BLACK, ComicBgType.entries.toTypedArray()),
                paperIntensity = json.optInt("paperIntensity", 35).coerceIn(0, 100),
                scene = safe("scene", ComicScene.NONE, ComicScene.entries.toTypedArray()),
                sceneSound = json.optBoolean("sceneSound", true),
                sceneEffect = json.optBoolean("sceneEffect", true),
                sceneVolume = json.optInt("sceneVolume", 40).coerceIn(0, 100),
                pageAnim = safe("pageAnim", ComicPageAnim.SLIDE, ComicPageAnim.entries.toTypedArray()),
                autoPageIntervalSec = json.optDouble("autoPageIntervalSec", 6.0).toFloat().coerceIn(1f, 120f),
                autoScrollSpeedDp = json.optDouble("autoScrollSpeedDp", 40.0).toFloat().coerceIn(5f, 400f),
                gestureTapLeft = safe("gestureTapLeft", ComicGestureAction.PREV, ComicGestureAction.entries.toTypedArray()),
                gestureTapRight = safe("gestureTapRight", ComicGestureAction.NEXT, ComicGestureAction.entries.toTypedArray()),
                gestureTapCenter = safe("gestureTapCenter", ComicGestureAction.TOGGLE_CONTROLS, ComicGestureAction.entries.toTypedArray()),
                gestureSwipe = json.optBoolean("gestureSwipe", true),
                gesturePinchClose = json.optBoolean("gesturePinchClose", true),
                gestureEdgeSwipe = json.optBoolean("gestureEdgeSwipe", true),
                gestureLongPressPanel = json.optBoolean("gestureLongPressPanel", false),
                volumeKeyTurn = json.optBoolean("volumeKeyTurn", true),
                showThumbPreview = json.optBoolean("showThumbPreview", true),
                hideSystemBars = json.optBoolean("hideSystemBars", true),
                translationEnabled = json.optBoolean("translationEnabled", false),
                translationLang = json.optString("translationLang", "auto")
                    .takeIf { it in setOf("auto", "ja", "en", "ko") } ?: "auto",
                translationTextScale = json.optDouble("translationTextScale", 1.0).toFloat().coerceIn(0.8f, 1.4f),
                translationEngine = json.optString("translationEngine", "online")
                    .takeIf { it in setOf("ai", "online") } ?: "online",
            ).normalized()
        }
    }
}

/** 阅读模式：单页/双页/条漫/无缝滚动/磁吸 */
enum class ComicMode(val label: String) {
    SINGLE("单页"), DOUBLE("双页"), WEBTOON("条漫"), CONTINUOUS("无缝滚动"), MAGNETIC("磁吸")
}

/** 阅读方向 */
enum class ComicDirection(val label: String) {
    LTR("左 → 右"), RTL("右 → 左"), TTB("上 → 下")
}

/**
 * 页面显示方式（第 10 条数学定义，严格实现）：
 * - FIT_PAGE 整页：完整装入容器保持比例（fit-inside，可能有留白）；
 * - FIT_WIDTH 适应宽度：按容器宽缩放，纵向自然溢出（可滚动）；
 * - FIT_HEIGHT 高度：按容器高缩放，宽度可能超出（左右平移查看）；
 * - ORIGINAL 原始：图片原始像素 1:1；
 * - FILL 铺满：填满容器居中裁剪多余（crop-to-fill）；
 * - STRETCH 拉伸：不保持比例强制铺满；
 * - CUSTOM 自定义（第 26 条）：基础档 × 用户缩放系数。
 */
enum class ComicFit(val label: String) {
    FIT_WIDTH("适应宽度"), FIT_HEIGHT("适应高度"), ORIGINAL("原始大小"), FILL("铺满裁切"), STRETCH("拉伸铺满"),
    FIT_PAGE("整页"), CUSTOM("自定义"),
}

/** 双页对齐（两页高度不一致时） */
enum class ComicDoubleAlign(val label: String) {
    TOP("顶部对齐"), CENTER("居中对齐"), BOTTOM("底部对齐")
}

/** 自动裁边模式 */
enum class ComicCropMode(val label: String) {
    OFF("关闭"), WHITE("裁白边"), BLACK("裁黑边"), AUTO("自动识别")
}

/** 画质增强（真实像素级处理，非状态开关）。四档效果取向互异（第六轮第 5 条）：
 * CAS = 同尺寸锐化（最快）；ANIME4K = 同尺寸线条重建+降噪（深线清洁）；
 * WAIFU2X = 低分辨率页 2× 神经网络超分 / 高分辨率页细节强化（最慢，有耗时提示）；
 * SUPER_RES = 低分辨率页 Lanczos 2× 重建+强锐化。 */
enum class ComicEnhanceMode(val label: String, val desc: String) {
    OFF("关闭", ""),
    CAS("锐化增强", "对比度自适应锐化，强化线条与小字（最快，约 0.5 秒/页）"),
    ANIME4K("Anime4K 轻量", "Anime4K Restore CNN 线条重建降噪，不放大（约 2-4 秒/页）"),
    WAIFU2X("超分 完整", "Anime4K Upscale CNN ×2 神经网络超分（低分辨率页收益最大；约 2-6 秒/页，处理时有预计耗时提示）"),
    SUPER_RES("超分辨率", "Lanczos 2× 重建 + 自适应锐化（低分辨率页翻倍清晰度）"),
}

/** 阅读背景 */
enum class ComicBgType(val label: String) {
    BLACK("纯黑"), WHITE("纯白"), GRAY("深灰"), PAPER("纸张纹理"), DYNAMIC("沉浸动态")
}

/** 场景（环境音 + 特效，两者独立开关） */
enum class ComicScene(val label: String) {
    NONE("关闭"),
    RAIN("雨夜"), SNOW("落雪"), SAKURA("樱花"), FIREFLY("萤火"),
    OCEAN("海边"), CAMPFIRE("篝火"), NIGHT("夏夜")
}

/** 翻页动画 */
enum class ComicPageAnim(val label: String) {
    NONE("无动画"), SLIDE("平移"), FADE("渐变"), CURL("仿真翻页")
}

/** 手势动作（可配置） */
enum class ComicGestureAction(val label: String) {
    NONE("无操作"),
    PREV("上一页"), NEXT("下一页"),
    TOGGLE_CONTROLS("呼出/隐藏控制"), TOC("目录"), SETTINGS("设置"), EXIT("退出")
}

/**
 * 每本漫画的阅读状态（独立于配置）：上次读到哪页、单页旋转、临时合页锚点。
 */
data class ComicBookState(
    val lastPage: Int = 0,
    /** lastPage 所属章节的首页 id（换章时不恢复旧页码） */
    val lastChapterSig: String? = null,
    /** 页 id → 旋转角度（0/90/180/270），仅作用于该页 */
    val pageRotations: Map<String, Int> = emptyMap(),
    /** 临时合页：以该页为左页与下一页组成跨页（显示层行为） */
    val mergeAnchors: Set<Int> = emptySet(),
) {
    /** Both the toolbar and settings use one non-overlapping merge rule. */
    fun toggleMerge(rawIndex: Int, pageCount: Int): ComicBookState {
        if (rawIndex in mergeAnchors) return copy(mergeAnchors = mergeAnchors - rawIndex)
        if (rawIndex !in 0 until (pageCount - 1).coerceAtLeast(0)) return this
        return copy(mergeAnchors = (mergeAnchors - (rawIndex - 1) - (rawIndex + 1)) + rawIndex)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("lastPage", lastPage)
        lastChapterSig?.let { put("lastChapterSig", it) }
        val rot = JSONObject()
        pageRotations.forEach { (k, v) -> rot.put(k, v) }
        put("pageRotations", rot)
        put("mergeAnchors", JSONArray(mergeAnchors))
    }

    companion object {
        fun fromJson(json: JSONObject): ComicBookState {
            val rot = runCatching {
                val o = json.optJSONObject("pageRotations") ?: JSONObject()
                o.keys().asSequence().associateWith { o.optInt(it) }
            }.getOrDefault(emptyMap())
            val merges = runCatching {
                val arr = json.optJSONArray("mergeAnchors") ?: JSONArray()
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            }.getOrDefault(emptySet())
            return ComicBookState(
                lastPage = json.optInt("lastPage", 0),
                lastChapterSig = json.optString("lastChapterSig").ifBlank { null },
                pageRotations = rot,
                mergeAnchors = merges
            )
        }
    }
}
