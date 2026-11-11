package com.example.ui.wenku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class WenkuFlightRequest(
    val bookKey: String,
    val sourceBounds: Rect,
    val sourceImage: ImageBitmap?,
    val sourceRadius: Dp,
    val targetBounds: Rect? = null,
    val returning: Boolean = false,
    /**
     * 归位落点。3D 书架上封面是一个斜切四边形，书本"立正"之后才是正对读者的矩形：
     * 去程必须从后者起飞（否则飞行一开始就要把斜的书硬拉正，观感就是扭曲），
     * 回程则应落回书架上的位置。两者不是同一个矩形，所以分开存。
     */
    val returnBounds: Rect? = null,
    /** 3D 书架接手：时间轴不能有起步停顿。 */
    val seamless: Boolean = false,
)

/** The source and target always refer to the shelf and reader, respectively. */
object WenkuFlightBridge {
    var request by mutableStateOf<WenkuFlightRequest?>(null, referentialEqualityPolicy())
        private set
    private var last: WenkuFlightRequest? = null

    fun begin(
        bookKey: String,
        bounds: Rect,
        image: ImageBitmap?,
        radius: Dp = 14.dp,
        returnBounds: Rect? = null,
        seamless: Boolean = false
    ) {
        last = null
        request = if (bounds.width > 0f && bounds.height > 0f) {
            WenkuFlightRequest(
                bookKey, bounds, image, radius,
                returnBounds = returnBounds, seamless = seamless
            )
        } else null
    }

    internal fun arrive(current: WenkuFlightRequest, viewport: Rect) {
        if (request !== current || current.targetBounds != null) return
        request = current.copy(targetBounds = viewport)
    }

    fun reverse(bookKey: String) {
        val previous = last
        last = null
        request = previous?.takeIf { it.bookKey == bookKey }?.copy(returning = true)
    }

    internal fun finished(current: WenkuFlightRequest) {
        if (request !== current) return
        if (!current.returning) last = current
        request = null
    }

    fun cancel() {
        request = null
        last = null
    }

    internal fun cancel(current: WenkuFlightRequest) {
        if (request === current) request = null
    }
}

/** Uniformly scale the cover inside its available bounds, keeping its centre fixed. */
internal fun fitWenkuCover(bounds: Rect, coverSize: Size): Rect {
    val scale = minOf(bounds.width / coverSize.width, bounds.height / coverSize.height)
    val halfWidth = coverSize.width * scale / 2
    val halfHeight = coverSize.height * scale / 2
    return Rect(
        bounds.center.x - halfWidth,
        bounds.center.y - halfHeight,
        bounds.center.x + halfWidth,
        bounds.center.y + halfHeight,
    )
}

/**
 * 把封面中心裁切到 `bounds` 的比例。
 *
 * 飞行层只做"整张图等比缩放"，而列表卡片的封面是 `ContentScale.Crop`（铺满、裁掉溢出）。
 * 两者比例不一致时，起飞第一帧就会看到封面比卡片小一圈、位置也对不上 —— 用户说的
 * "位置不太对得上 / 第一帧突变" 就是它。这里在起飞前把贴图裁成卡片比例，
 * 之后 `fitWenkuCover` 得到的就是卡片本身的矩形，首帧严丝合缝。
 */
internal fun cropCoverToAspect(image: ImageBitmap?, bounds: Rect): ImageBitmap? {
    if (image == null) return null
    if (bounds.width <= 0f || bounds.height <= 0f) return image
    val source = image.asAndroidBitmap()
    if (source.width <= 0 || source.height <= 0) return image
    val targetAspect = bounds.width / bounds.height
    val sourceAspect = source.width.toFloat() / source.height.toFloat()
    if (kotlin.math.abs(targetAspect - sourceAspect) / sourceAspect < .02f) return image
    val cropWidth = if (sourceAspect > targetAspect) {
        (source.height * targetAspect).toInt()
    } else source.width
    val cropHeight = if (sourceAspect > targetAspect) {
        source.height
    } else (source.width / targetAspect).toInt()
    val width = cropWidth.coerceIn(1, source.width)
    val height = cropHeight.coerceIn(1, source.height)
    val x = (source.width - width) / 2
    val y = (source.height - height) / 2
    return runCatching {
        android.graphics.Bitmap.createBitmap(source, x, y, width, height).asImageBitmap()
    }.getOrNull() ?: image
}

/** The root viewport supplies the reader landing bounds independently of its route slide. */
@Composable
fun WenkuFlightOverlay(readerBookKey: String?, modifier: Modifier = Modifier) {
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val current = WenkuFlightBridge.request
    DisposableEffect(Unit) { onDispose { WenkuFlightBridge.cancel() } }
    LaunchedEffect(readerBookKey, current, viewport, reduceMotion) {
        if (reduceMotion) {
            WenkuFlightBridge.cancel()
        } else if (current != null && !current.returning) {
            // 立刻起飞：不等阅读器挂载。等 readerBookKey 到位再画的话，
            // 观众会看到"转身结束 → 空白一瞬 → 封面才出现"，这就是两段动画的观感来源。
            viewport?.let { WenkuFlightBridge.arrive(current, it) }
            delay(WenkuMotion.COVER_FLIGHT_MS.toLong() + 240)
            WenkuFlightBridge.cancel(current)
        }
    }

    Box(
        modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            val size = coordinates.size
            if (size.width > 0 && size.height > 0) {
                val bounds = Rect(coordinates.positionInRoot(), Size(size.width.toFloat(), size.height.toFloat()))
                // A resize (fold, rotation or split screen) invalidates the shelf geometry as well.
                if (viewport != null && viewport != bounds) WenkuFlightBridge.cancel()
                viewport = bounds
            }
        }
    ) {
        val root = viewport ?: return@Box
        val request = current ?: return@Box
        // 视口本身就是落点：不再等 arrive() 回调，避免"动画开始前空一帧"。
        val target = request.targetBounds ?: root
        if (reduceMotion) return@Box

        // 书架一侧的真实矩形：去程是卡片封面，回程是书架落点。
        val shelfBounds = request.returnBounds ?: request.sourceBounds
        // 贴图先裁成书架封面的比例，起飞/落地两帧才和卡片完全重合（见 cropCoverToAspect）。
        val image = remember(request) { cropCoverToAspect(request.sourceImage, shelfBounds) }
        // With no snapshot, keep the same paper shape as the source card.
        val coverSize = image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: shelfBounds.size
        key(request) {
            WenkuCoverFlight(
                visible = true,
                source = WenkuCoverSource(
                    bounds = fitWenkuCover(shelfBounds, coverSize)
                        .translate(-root.left, -root.top),
                    image = image,
                    cornerRadius = request.sourceRadius,
                ),
                target = WenkuCoverTarget(
                    bounds = fitWenkuCover(target, coverSize).translate(-root.left, -root.top),
                    image = image,
                    cornerRadius = 0.dp,
                ),
                departing = !request.returning,
                seamless = request.seamless,
                onFinished = { WenkuFlightBridge.finished(request) },
            )
        }
    }
}
