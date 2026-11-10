package com.example.ui.wenku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.AsyncImagePainter

/**
 * 书架封面的「飞行贴图」缓存。
 *
 * 封面飞行（`PaperCoverRender`）需要的是一张 `ui.Image`；Flutter 里直接从已解码的
 * `ImageProvider` 拿，Android 这边最省事的方法是把 Coil 解码好的 drawable 光栅化一次，
 * 存成 [ImageBitmap]，点击时直接交给 [WenkuFlightBridge.begin]。
 *
 * 只缓存**已经解码成功**的封面（与屏幕上真正显示的那张同源），键是书籍 id；
 * 容量 32，与书架光栅化缓存（`ArchiveShelf` 的 `ShelfCoverStore`）一致。
 * 没有快照时飞行仍然工作（`WenkuCoverFlight` 会画纸面兜底色），只是没有像素。
 */
object WenkuCoverSnapshots {

    private const val MAX_ENTRIES = 32

    private val entries = LinkedHashMap<String, ImageBitmap>(16, .75f, true)

    @Synchronized
    fun put(key: String, image: ImageBitmap?) {
        if (image == null) {
            entries.remove(key)
            return
        }
        entries[key] = image
        while (entries.size > MAX_ENTRIES) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * 「封面矩形」登记表：给不在书架里的入口用（目前是发现页的小说详情面板）。
 *
 * 这里刻意不用 Compose state —— 写入点在 `onGloballyPositioned`（布局阶段），
 * 布局里写状态会触发「布局 → 重组 → 布局」死循环（R15 实测 ANR）。
 */
object WenkuFlightSources {

    private val bounds = HashMap<String, androidx.compose.ui.geometry.Rect>()

    @Synchronized
    fun put(key: String, rect: androidx.compose.ui.geometry.Rect) {
        bounds[key] = rect
    }

    @Synchronized
    fun get(key: String): androidx.compose.ui.geometry.Rect? = bounds[key]

    @Synchronized
    fun remove(key: String) {
        bounds.remove(key)
    }
}

/**
 * 把某个封面的解码结果登记进 [WenkuCoverSnapshots]。
 *
 * 放在书架卡片里 `rememberAsyncImagePainter` 的旁边：解码状态一变就光栅化一次，
 * 之后点击只用一次查表。`key` 传 `null`（例如占位封面）时什么也不做。
 */
@Composable
fun WenkuCoverSnapshot(
    key: Any?,
    painter: Painter,
    maxEdgePx: Int = 1080
) {
    val result = ((painter as? AsyncImagePainter)?.state as? AsyncImagePainter.State.Success)?.result
    LaunchedEffect(key, result) {
        if (key == null || result == null) return@LaunchedEffect
        val drawable = result.drawable ?: return@LaunchedEffect
        val id = key.toString()
        val first = runCatching { rasterizeWenkuCover(drawable, maxEdgePx) }.getOrNull()
        WenkuCoverSnapshots.put(id, first)
        // Coil 的 `.crossfade(true)` 会把 drawable 包进 CrossfadeDrawable：Success 那一刻
        // 还停在淡入起点，整张接近全透明（R14 实测：纸飞起来是半透明、能透出下面的目录）。
        // 等淡入跑完再光栅化一次，用这一张覆盖掉；失败就保留第一次的结果。
        kotlinx.coroutines.delay(420)
        runCatching { rasterizeWenkuCover(drawable, maxEdgePx) }.getOrNull()?.let { opaque ->
            WenkuCoverSnapshots.put(id, opaque)
        }
    }
}

/**
 * 单次光栅化：按 drawable 的固有尺寸建一张 ARGB_8888 位图（长边不超过 [maxEdgePx]）。
 *
 * 与 `ArchiveShelf.rasterizeShelfCover` 同一套做法；这里保留原始宽高，是因为飞行终点是
 * 阅读器的整屏封面，贴图越大越不容易在 1000ms 的放大阶段看出模糊。
 */
fun rasterizeWenkuCover(
    drawable: android.graphics.drawable.Drawable,
    maxEdgePx: Int = 1080
): ImageBitmap? {
    // 硬件位图画不进软件 Canvas：BitmapDrawable 直接拿底层位图（必要时复制成软件位图）。
    val direct = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    val intrinsicWidth = if (direct != null) direct.width else drawable.intrinsicWidth
    val intrinsicHeight = if (direct != null) direct.height else drawable.intrinsicHeight
    val width = if (intrinsicWidth > 0) intrinsicWidth else 360
    val height = if (intrinsicHeight > 0) intrinsicHeight else (width * 1.4f).toInt()
    if (width <= 0 || height <= 0) return null

    val longest = maxOf(width, height)
    val scale = if (longest > maxEdgePx) maxEdgePx.toFloat() / longest else 1f
    val widthPx = (width * scale).toInt().coerceAtLeast(1)
    val heightPx = (height * scale).toInt().coerceAtLeast(1)

    if (direct != null) {
        val software = if (direct.config == android.graphics.Bitmap.Config.HARDWARE) {
            direct.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else direct
        if (software != null) {
            val scaled = if (software.width != widthPx || software.height != heightPx) {
                android.graphics.Bitmap.createScaledBitmap(software, widthPx, heightPx, true)
            } else software
            return scaled.asImageBitmap()
        }
    }

    val bitmap = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, widthPx, heightPx)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
