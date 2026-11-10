// android.graphics.Vertices / Canvas.drawVertices 鍦?API 30 琚?Mesh / drawMesh 鍙栦唬锛?// 浣?minSdk 24 鍙兘鐢ㄥ畠锛涜涔変笌 ui.Vertices.raw 涓€鑷达紝鏁呮澶勬暣浣撳帇鎺夊純鐢ㄥ憡璀︺€?@file:Suppress("DEPRECATION")

package com.example.ui.wenku

import android.graphics.BitmapShader
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wenku8銆屽皝闈㈢焊寮犻琛岋紙Hero cover flight锛夈€嶅師璇殑 Compose 1:1 绉绘銆? *
 * 鏈枃浠舵槸**鏂板**鏂囦欢锛屼笉鏀瑰姩浠讳綍鏃㈡湁鏂囦欢锛涘澶栧彧渚濊禆 [WenkuMotion] 涓? * [rememberWenkuAccessibility]锛堝悓鍖呭凡鏈夛級锛屼笉鏂板浠讳綍 Gradle 渚濊禆锛? * 鍙娇鐢?`androidx.compose.*` / `android.graphics.*` / `kotlin.*`銆? *
 * 涓?Flutter 鍙傝€冨疄鐜扮殑閫愭瀵圭収锛? *
 * | 鍙傝€冩簮鐮?| 鏈枃浠?|
 * | --- | --- |
 * | `motion_ui.dart:17-40` `NovelMotion` | 澶嶇敤鏃㈡湁鐨?[WenkuMotion]锛?000/840/420/320銆乪aseOutCubic銆乻ettle锛?|
 * | `motion_ui.dart:42-167` `PaperFlightPose` + `PaperCoverRectTween.lerp` | [WenkuFlightPose] |
 * | `motion_ui.dart:200-520` `PaperCoverBend` / `PaperCoverRender` | [WenkuPaperMesh] + `Canvas.drawVertices` |
 * | `motion_ui.dart:520-680` 闈欐€佺汗鐞?/ `vertices.dispose()` / `paintBounds.inflate(28)` | [WenkuPaperBrush] + 缃戞牸澶嶇敤 |
 * | `cover_arrival.dart` 鍏ㄦ枃锛?20ms銆佺湡瀹炶В鐮佸抚銆丒xpando 宸插睍绀虹紦瀛樸€佸钩闈㈠埌杈撅級 | [WenkuCoverArrival] / [WenkuCoverArrivalFrame] / [WenkuCoverArrivalCache] |
 * | `archive_motion.dart:278-436` `ArchivePlaneTransition` | [WenkuPlanePose] |
 *
 * ## 鍗曚綅绾﹀畾锛堝敮涓€闇€瑕佹崲鎹㈢畻鐨勫湴鏂癸級
 *
 * Flutter 鐨?`Canvas` 鍗曚綅鏄?*閫昏緫鍍忕礌**锛? dp锛夛紝Compose 鐨?`Canvas` 鍗曚綅鏄?*璁惧鍍忕礌**銆? * 鍥犳鎵€鏈夈€岄暱搴︿负甯搁噺銆嶇殑閲忊€斺€擿lift` 鐨?18銆佺煩褰㈡姮鍗囩殑 `min(32, 12 + dist * .075)`銆? * `paintBounds` 鐨?`inflate(28)`銆佸钩闈綅绉荤殑 24 / 12鈥斺€旈兘鍦?**dp 绌洪棿**鎸夊師鍏紡姹傚€硷紝
 * 鍐嶄箻 `density` 钀藉埌鐢诲竷锛涜€?`300 / (300 - depth)` 杩欑鏃犻噺绾茬缉鏀俱€乣roll` 鐨勫姬搴︺€? * 缃戞牸鐨?u/v銆乣.12 / .88 / .58 / .42 / .18 / .16` 绛夐槇鍊间笌 density 鏃犲叧锛岄€愬瓧鐓ф惉銆? *
 * ## 鏃堕挓绾﹀畾
 *
 * Flutter 鐨?Hero 浼氬厛缁欒繘搴﹀涓€灞?`fastOutSlowIn` 鏃堕挓锛宍PaperCoverRectTween.lerp`
 * 鍐嶇敤 `PaperFlightPose.heroTimeline`锛?8 娆′簩鍒嗗弽瑙ｏ級鎶婇偅灞傛椂閽熸挙鎺夛紝鑰?`PaperCoverBend`
 * 鐨勫集鏇插Э鎬佺洿鎺ュ悆**琚杩囦竴灞?*鐨勮繘搴︺€傛湰鏂囦欢鐨勬椂閽熸槸鑷繁椹卞姩鐨勭嚎鎬ф椂閽燂紝鎵€浠ワ細
 * - 鐭╁舰鎻掑€煎悆绾挎€ц繘搴︼紙`rectAt(..., value, heroCurve = false)`锛夛紱
 * - 寮洸濮挎€佸悆 [WenkuFlightPose.heroClock]锛坄Cubic(.4, 0, .2, 1)`锛屽嵆 Hero 鐨勬椂閽燂級锛? * 涓よ€呯瓑浠蜂簬 Flutter 渚с€屾挙閿€鍚?/ 鏈挙閿€銆嶇殑涓ゆ潯杩涘害銆? */

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 缃戞牸甯搁噺锛堜笌 Flutter `PaperCoverRender._prepareMesh` 閫愬瓧瀵瑰簲锛?// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/** 缃戞牸鍒楁暟锛欶lutter `const columns = 17`銆?*/
const val WENKU_MESH_COLUMNS: Int = 17

/** 缃戞牸鍩虹琛屾暟锛欶lutter `for (var i = 0; i <= 32; i++) i / 32`锛屽嵆 33 涓噰鏍风偣 = 32 琛屻€?*/
const val WENKU_MESH_ROWS: Int = 32

/** 姣忎晶杈圭紭鍔犲瘑閲囨牱鏁帮細Flutter `for (var i = 1; i <= 8; i++)`锛屼笂涓嬪悇 8 鏉?= 16 鏉¤竟缂樿銆?*/
const val WENKU_MESH_EDGE_SAMPLES: Int = 8

private const val PI_F: Float = 3.1415927f

/** Flutter `PaperCoverFlight.backingColor = const Color(0xFF202020)`銆?*/
private val BACKING_ARGB: Int = 0xFF202020.toInt()

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 绾暟瀛︼細WenkuFlightPose
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * 涓€涓笉渚濊禆 Compose 鐨勩€岀焊寮犻琛屻€嶇函鍑芥暟鐭╁舰锛堝乏/涓?鍙?涓嬶紝閫昏緫鍍忕礌鎴栬澶囧儚绱犵敱璋冪敤鏂圭害瀹氾級銆? *
 * 涔嬫墍浠ヤ笉鐩存帴鐢?`androidx.compose.ui.geometry.Rect`锛屾槸涓轰簡璁?[WenkuFlightPose]
 * 鍙互鍦ㄧ函 JVM 鍗曟祴閲岀洿鎺ヨ皟鐢紙`PaperFlightPose` / `PaperCoverRectTween` 鍚屾牱鏄函 Dart锛夈€? */
data class WenkuFlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * .5f
    val centerY: Float get() = (top + bottom) * .5f

    /** Flutter `(Offset.zero & size).inflate(28)`銆?*/
    fun inflate(delta: Float): WenkuFlightRect =
        WenkuFlightRect(left - delta, top - delta, right + delta, bottom + delta)

    /** Flutter `rect.shift(offset)`銆?*/
    fun shift(dx: Float, dy: Float): WenkuFlightRect =
        WenkuFlightRect(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * `motion_ui.dart:42-167` 鐨勫畬鏁寸Щ妞嶏細`PaperFlightPose`锛坱ravel / heroTimeline / depthAt /
 * lift / roll锛変笌 `PaperCoverRectTween.lerp`锛堜腑鐐规彃鍊?+ 鎶涚墿绾挎姮鍗囷級銆? *
 * 鏈璞?*鍙緷璧?`kotlin.math`**锛屾棤浠讳綍 Compose 渚濊禆锛屽彲鐩存帴鍦?JVM 鍗曟祴閲屾柇瑷€锛? *
 * ```
 * assertEquals(0f, WenkuFlightPose.travel(.12f))          // 璧烽鍓嶄笉鍔? * assertEquals(1f, WenkuFlightPose.travel(.58f))          // 鍒拌揪鍓嶅凡鍒颁綅
 * assertEquals(0f, WenkuFlightPose.depthAt(.5f, .5f, 0f))
 * assertEquals(0f, WenkuFlightPose.depthAt(.5f, .5f, 1f))
 * assertEquals(18f, WenkuFlightPose.lift(.18f), 1e-4f)    // 宄板€煎湪 .18
 * assertTrue(WenkuFlightPose.roll(.5f) < 0f)              // -0.045 * sin(pi/2)
 * assertEquals(49, WenkuFlightPose.bendPoints().size)     // 32 琛?+ 16 杈圭紭 + 绔偣
 * ```
 */
object WenkuFlightPose {

    /** Flutter `PaperCoverFlight.faceSize = const Size(120, 164)` 鐨勯粯璁ゅ€笺€?*/
    const val REFERENCE_FACE_WIDTH: Float = 120f
    const val REFERENCE_FACE_HEIGHT: Float = 164f

    /** Flutter `radiusX = (8 / face.width)` / `radiusY = (8 / face.height)` 閲岀殑 8銆?*/
    const val CORNER_INSET: Float = 8f

    /** 鐭╁舰鎶崌涓婇檺锛欶lutter `math.min(32.0, 12 + distance * .075)`銆?*/
    const val RECT_LIFT_LIMIT: Float = 32f
    const val RECT_LIFT_BASE: Float = 12f
    const val RECT_LIFT_PER_PIXEL: Float = .075f

    /** 寮洸骞呭害鐨勭劍璺濓細Flutter `300 / (300 - depth)`銆?*/
    const val BEND_FOCAL: Float = 300f

    // 鈹€鈹€ 鏇茬嚎 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * 涓夋璐濆灏?`Cubic(x1, y1, x2, y2)` 姹傚€硷紙P0 = (0,0)銆丳3 = (1,1)锛夛紝
     * 涓?`dart:ui` 鐨?`Cubic.transform` 涓€鑷达細鍏堟寜 x 浜屽垎鍙嶈В鍙傛暟 t锛屽啀鍙?y銆?     */
    fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        var t = x
        repeat(20) {
            t = (low + high) * .5f
            if (bezierComponent(t, x1, x2) < x) low = t else high = t
        }
        return bezierComponent(t, y1, y2)
    }

    private fun bezierComponent(t: Float, a: Float, b: Float): Float {
        val remaining = 1f - t
        return 3f * remaining * remaining * t * a + 3f * remaining * t * t * b + t * t * t
    }

    /** `Curves.easeInOutCubic` = `Cubic(.645, .045, .355, 1)`銆?*/
    fun easeInOutCubic(value: Float): Float = bezier(.645f, .045f, .355f, 1f, value)

    /** `Curves.easeOutCubic` = `Cubic(.215, .61, .355, 1)`銆?*/
    fun easeOutCubic(value: Float): Float = bezier(.215f, .61f, .355f, 1f, value)

    /** `Curves.easeInOut` = `Cubic(.42, 0, .58, 1)`锛堥伄鎸″壀褰辩殑 alpha 鐢ㄥ畠锛夈€?*/
    fun easeInOut(value: Float): Float = bezier(.42f, 0f, .58f, 1f, value)

    /** Hero 鑷甫鐨?`fastOutSlowIn` 鏃堕挓 = `Cubic(.4, 0, .2, 1)`銆?*/
    fun heroClock(value: Float): Float = bezier(.4f, 0f, .2f, 1f, value)

    // 鈹€鈹€ PaperFlightPose 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /** Flutter `PaperFlightPose._smooth`锛歚x虏(3 - 2x)`锛屽厛 clamp 鍒?[0, 1]銆?*/
    fun smooth(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Flutter锛歚Curves.easeInOutCubic.transform(((progress - .12) / .46).clamp(0, 1))`銆?     *
     * 璧烽鍚庣煭鏆傜瓑寰呭墺绂诲紑鍦猴紝`.12 鈫?.58` 璧板畬鏁存浣嶇Щ锛沗.58` 涔嬪悗宸插湪缁堢偣锛?     * 鍓╀笅鐨勬椂闂翠氦缁欏瑙掔嚎钀藉湴锛坄depthAt` 鐨?`landing`锛夈€?     */
    fun travel(progress: Float): Float =
        easeInOutCubic(((progress - .12f) / .46f).coerceIn(0f, 1f))

    /**
     * Hero 鑷繁浼氬涓€灞?`fastOutSlowIn`銆傚厛鎶婇偅灞傛椂閽熷弽瑙ｆ帀锛屽啀濂楁垜浠嚜宸辩殑缂栨帓锛?     * 鍚﹀垯浣嶇Щ涓庤惤浣撲細琚紦鍔ㄤ袱娆♀€斺€?8 娆′簩鍒嗗弽瑙?`smootherstep(t) = value`锛?     * 鍐嶆妸 t 鍠傝繘 `Cubic(.4, 0, .2, 1)` 鐨?**x 鍒嗛噺**澶氶」寮忋€?     */
    fun heroTimeline(value: Float): Float {
        if (value <= 0f || value >= 1f) return value.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        repeat(18) {
            val parameter = (low + high) / 2f
            if (smooth(parameter) < value) low = parameter else high = parameter
        }
        val t = (low + high) / 2f
        val remaining = 1f - t
        // x of Cubic(.4, 0, .2, 1), after solving its y above.
        return 1.2f * remaining * remaining * t + .6f * remaining * t * t + t * t * t
    }

    /**
     * 绾稿紶寮洸娣卞害锛堥€昏緫鍍忕礌閲忕骇锛屽叕寮忛€愬瓧鐓ф惉锛夛細
     *
     * ```
     * peel     = _smooth((p - .025 - v * .11) / .11)
     * landing  = clamp((p - .58) / .42)
     * contact  = (1 - v) * .66 + u * .34
     * floating = 1 - _smooth((landing - contact * .70) / .30)
     * breeze   = sin((v * 1.3 - p * 1.6) * 2蟺)
     * depth    = peel * floating * (17 + 7 * sin(v * 蟺) + 3 * breeze * sin(p * 蟺))
     * ```
     *
     * 涓ゆ潯绔偣鎭掍负 0锛氳捣椋炲墠涓庤惤鍦板悗閮芥病鏈夌户缁窇鍔ㄧ殑 ticker銆?     *
     * @param u 鍒楀弬鏁?`[0, 1]`锛團lutter `column`锛?     * @param v 琛屽弬鏁?`[0, 1]`锛團lutter `row`锛?     * @param p 椋炶杩涘害 `[0, 1]`
     */
    fun depthAt(u: Float, v: Float, p: Float): Float {
        if (p <= 0f || p >= 1f) return 0f
        val row = v.coerceIn(0f, 1f)
        val column = u.coerceIn(0f, 1f)
        val peel = smooth((p - .025f - row * .11f) / .11f)
        val landing = ((p - .58f) / .42f).coerceIn(0f, 1f)
        // 宸︿笅瑙掑厛钀藉畾锛屾帴瑙﹂攱闈㈠啀鎵繃绾搁潰锛涘彧鎸夎绠椾細鎶婃暣鏉′笅杈圭紭涓€娆℃€у帇骞炽€?
        val contact = (1f - row) * .66f + column * .34f
        val floating = 1f - smooth((landing - contact * .70f) / .30f)
        val breeze = sin((row * 1.3f - p * 1.6f) * PI_F * 2f)
        return peel * floating * (17f + 7f * sin(row * PI_F) + 3f * breeze * sin(p * PI_F))
    }

    /** Flutter锛歚lift = 18 * _smooth(p / .18) * (1 - _smooth((p - .42) / .16))`銆?*/
    fun lift(p: Float): Float =
        18f * smooth(p / .18f) * (1f - smooth((p - .42f) / .16f))

    /** Flutter锛歚roll = -.045 * sin(clamp(p) * 蟺) * (1 - _smooth((p - .42) / .16))`銆?*/
    fun roll(p: Float): Float =
        -.045f * sin(p.coerceIn(0f, 1f) * PI_F) * (1f - smooth((p - .42f) / .16f))

    /**
     * 杈圭紭鍦嗚鍐呯缉锛團lutter `_prepareMesh` 閲岄€愯璁＄畻鐨?`inset`锛夛細
     *
     * ```
     * corner = min(v, 1 - v) / radiusY
     * inset  = corner >= 1 ? 0 : radiusX * (1 - sqrt(max(0, 1 - (1 - corner)虏)))
     * ```
     */
    fun columnInset(row: Float, radiusX: Float, radiusY: Float): Float {
        if (radiusY <= 0f) return 0f
        val v = row.coerceIn(0f, 1f)
        val corner = min(v, 1f - v) / radiusY
        if (corner >= 1f) return 0f
        return radiusX * (1f - sqrt(max(0f, 1f - (1f - corner) * (1f - corner))))
    }

    /** Flutter `radiusX = (8 / face.width).clamp(0, .5)`锛堥粯璁?120 脳 164 鐨勫皝闈級銆?*/
    fun radiusX(): Float = (CORNER_INSET / REFERENCE_FACE_WIDTH).coerceIn(0f, .5f)

    /** Flutter `radiusY = (8 / face.height).clamp(0, .5)`锛堥粯璁?120 脳 164 鐨勫皝闈級銆?*/
    fun radiusY(): Float = (CORNER_INSET / REFERENCE_FACE_HEIGHT).coerceIn(0f, .5f)

    /**
     * 缃戞牸琛岄噰鏍凤紙鍗囧簭銆佸幓閲嶏級锛?     * 32 娈靛熀纭€琛岋紙`i / 32`锛宨 鈭?0..32锛夛紜 姣忎晶 8 鏉′綑寮﹀姞瀵嗚锛屽叡 49 涓噰鏍风偣銆?     */
    fun meshRows(radiusY: Float): FloatArray {
        val radius = radiusY.coerceIn(0f, .5f)
        val rows = LinkedHashSet<Float>()
        for (i in 0..WENKU_MESH_ROWS) rows.add(i.toFloat() / WENKU_MESH_ROWS.toFloat())
        for (i in 1..WENKU_MESH_EDGE_SAMPLES) {
            val v = radius * (1f - cos(i.toFloat() / WENKU_MESH_EDGE_SAMPLES.toFloat() * PI_F / 2f))
            rows.add(v)
            rows.add(1f - v)
        }
        return rows.sorted().toFloatArray()
    }

    /**
     * 缃戞牸琛屾柇鐐癸紝渚涘崟娴嬫鏌ャ€?7 鍒?脳 32 琛?+ 16 鏉¤竟缂樺姞瀵嗚銆嶏細
     * 姣忎釜 `Pair.first` 鏄鍙傛暟 `v`锛宍Pair.second` 鏄琛岀殑鍦嗚鍐呯缉 `u0`
     * 锛坄u = u0 + (1 - 2 * u0) * column / 16`锛夛紝琛岀殑椤哄簭鍗崇綉鏍艰鐨勯『搴忋€?     */
    fun bendPoints(): Array<Pair<Float, Float>> {
        val rx = radiusX()
        val ry = radiusY()
        val rows = meshRows(ry)
        return Array(rows.size) { index ->
            rows[index] to columnInset(rows[index], rx, ry)
        }
    }

    // 鈹€鈹€ PaperCoverRectTween 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * `PaperCoverRectTween.lerp` 鐨勯€愯绉绘锛氫腑鐐规彃鍊?+ `min(32, 12 + distance * .075) *
     * sin(travel * 蟺)` 鐨勬姏鐗╃嚎鎶崌銆?     *
     * @param begin 璧烽鐭╁舰
     * @param end 钀界偣鐭╁舰锛圚ero 閲忓埌鐨勬槸鐩爣灞€閮ㄥ師鐐癸紝宸€肩敤 [endPaintOffsetX]/[endPaintOffsetY] 琛ワ級
     * @param returning 褰掍綅鏂瑰悜锛歚travel = 1 - travel(1 - t)`锛屽姬绾垮舰鐘朵笉鍙樸€佹椂搴忛暅鍍?     * @param value 鍘熷杩涘害 `[0, 1]`
     * @param heroCurve 杩涘害鏄惁宸茬粡杩?Hero 鐨?`fastOutSlowIn`锛涙槸鍒欏厛鐢?[heroTimeline] 鍙嶈В
     */
    fun rectAt(
        begin: WenkuFlightRect,
        end: WenkuFlightRect,
        returning: Boolean,
        value: Float,
        endPaintOffsetX: Float = 0f,
        endPaintOffsetY: Float = 0f,
        heroCurve: Boolean = false
    ): WenkuFlightRect {
        val target = end.shift(endPaintOffsetX, endPaintOffsetY)
        if (value <= 0f) return begin
        if (value >= 1f) return target
        val t = if (heroCurve) heroTimeline(value) else value
        val journey = if (returning) 1f - travel(1f - t) else travel(t)
        if (journey <= 0f) return begin
        if (journey >= 1f) return target
        val centerX = begin.centerX + (target.centerX - begin.centerX) * journey
        val centerY = begin.centerY + (target.centerY - begin.centerY) * journey
        val distance = hypot(target.centerX - begin.centerX, target.centerY - begin.centerY)
        val lift = min(RECT_LIFT_LIMIT, RECT_LIFT_BASE + distance * RECT_LIFT_PER_PIXEL) *
            sin(journey * PI_F)
        val width = begin.width + (target.width - begin.width) * journey
        val height = begin.height + (target.height - begin.height) * journey
        return WenkuFlightRect(
            left = centerX - width * .5f,
            top = centerY - lift - height * .5f,
            right = centerX + width * .5f,
            bottom = centerY - lift + height * .5f
        )
    }
}

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 绾暟瀛︼細WenkuPlanePose锛坅rchive_motion.dart:278-436 鐨勭嫭绔嬪疄鐜帮級
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * `ArchivePlaneTransition` 鐨勪綅绉讳笌鏂滃垏瑙嗗彛鏁板锛坄kind = incoming`, `direction = 1`锛夈€? *
 * 鍒绘剰**涓?*澶嶇敤 `WenkuMotion.kt` 閲岀殑 `Modifier.wenkuPlaneLayer`锛氶偅杈规槸缁欓〉闈㈠鍣ㄧ敤鐨? * `Modifier`锛岃繖閲岃鐨勬槸鍙互鎸夊抚鍙栨暟鐨勭函鍑芥暟锛屽ソ璁╁皝闈㈠埌杈句笌椤甸潰鍒囨崲鍏辩敤鍚屼竴缁勬暟鍊? * 鍙堜簰涓嶇壍杩炪€傛暟鍊奸€愬瓧鐩稿悓锛? *
 * ```
 * progress    = Cubic(.18, .72, .18, 1).transform(animation.value)
 * displacement = (-24 * dir * (1 - p), 12 * (1 - p))
 * slant        = min(180dp, width * .46)
 * edge         = (width + slant) * p
 * viewport     = (0,0) 鈫?(edge,0) 鈫?(edge - slant,h) 鈫?(0,h)
 * ```
 */
object WenkuPlanePose {

    /** `Cubic(.18, .72, .18, 1)`銆?*/
    fun ease(raw: Float): Float = WenkuFlightPose.bezier(.18f, .72f, .18f, 1f, raw)

    /** incoming锛歚-24 * direction * (1 - progress)`锛坉p锛夈€?*/
    fun offsetX(progress: Float, direction: Int = 1): Float = -24f * direction * (1f - progress)

    /** incoming锛歚12 * (1 - progress)`锛坉p锛夈€?*/
    fun offsetY(progress: Float): Float = 12f * (1f - progress)

    /** `slant = min(180.dp, width * .46)`锛?80dp 鐢辫皟鐢ㄦ柟鎹㈢畻鎴?px 浼犲叆銆?*/
    fun slant(widthPx: Float, maxSlantPx: Float): Float = min(maxSlantPx, widthPx * .46f)

    /**
     * 瑙嗗彛鍥涚偣澶氳竟褰紝鎸?`x, y` 骞抽摵鎴?8 涓?float锛?     * `(0,0) 鈫?(edge,0) 鈫?(edge - slant,h) 鈫?(0,h)`锛宍direction < 0` 鏃舵按骞抽暅鍍忋€?     */
    fun viewportPoints(
        width: Float,
        height: Float,
        progress: Float,
        direction: Int = 1,
        slant: Float
    ): FloatArray {
        val p = progress.coerceIn(0f, 1f)
        val edge = (width + slant) * p
        val x = { value: Float -> if (direction >= 0) value else width - value }
        return floatArrayOf(
            x(0f), 0f,
            x(edge), 0f,
            x(edge - slant), height,
            x(0f), height
        )
    }
}

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 缃戞牸锛歐enkuPaperMesh锛圥aperCoverBend._prepareMesh锛?// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * 涓€寮犵汗鐞嗙綉鏍肩殑褰㈢姸缂撳瓨锛氳/鍒?绱㈠紩/椤剁偣鏁扮粍鍙湪绗竴娆¤繘鍏ユ椂寤轰竴娆★紝
 * 涔嬪悗姣忓抚鍙噸鍐欏潗鏍囷紙`positions`锛変笌绾圭悊鍧愭爣锛坄texCoords`锛夛紝涓嶉噸寤轰綅鍥俱€佷笉閲嶅缓绱㈠紩銆? *
 * 椤剁偣鏁?= 琛岄噰鏍锋暟 脳 17 = 49 脳 17 = 833锛涗笁瑙掑舰绱㈠紩 = 48 脳 16 脳 6 = 4608銆? * 姣忎釜鍥涜竟褰㈢殑椤剁偣椤哄簭涓?Flutter 涓€鑷达細`(tl, bl, tl+1)` / `(tl+1, bl, bl+1)`銆? */
private class WenkuPaperMesh {

    private val columns: Int = WENKU_MESH_COLUMNS
    private val radiusX: Float = WenkuFlightPose.radiusX()
    private val radiusY: Float = WenkuFlightPose.radiusY()
    private var rows: FloatArray = FloatArray(0)

    var positions: FloatArray = FloatArray(0)
        private set
    var texCoords: FloatArray = FloatArray(0)
        private set
    var colors: IntArray = IntArray(0)
        private set
    var indices: ShortArray = ShortArray(0)
        private set

    /** 椤剁偣鏁?= 琛岄噰鏍锋暟 脳 17锛屼緵鏃х殑 raw `drawVertices` 閲嶈浇浣跨敤銆?*/
    val vertexCount: Int get() = rows.size * columns

    /** 网格行数（drawBitmapMesh 要的是「格子数」= 行数 - 1）。 */
    val rowCount: Int get() = rows.size

    /** 涓€娆℃€у缓缃戞牸锛氳閲囨牱銆佺储寮曘€侀《鐐归鑹诧紙濂剁矇搴曡壊锛岄€忔槑灏侀潰涔熶笉浼氶€忓嚭鑳屽悗鐨勫満鏅級銆?*/
    fun prepare() {
        if (rows.isNotEmpty()) return
        rows = WenkuFlightPose.meshRows(radiusY)
        val vertexCount = rows.size * columns
        positions = FloatArray(vertexCount * 2)
        texCoords = FloatArray(vertexCount * 2)
        colors = IntArray(vertexCount)
        // Flutter 里 0xFF202020 是 **paint.color**，有 ImageShader 时被着色器完全覆盖；
        // Android 的 drawVertices 会把顶点色与着色器相乘，照抄颜色就会把封面压到 12% 亮度
        // （R14 实测：整张纸是黑的）。顶点色必须是白色，兜底色只留给没有位图的分支。
        colors.fill(0xFFFFFFFF.toInt())
        indices = ShortArray((rows.size - 1) * (columns - 1) * 6)
        var triangle = 0
        for (row in 0 until rows.size - 1) {
            for (column in 0 until columns - 1) {
                val topLeft = row * columns + column
                val bottomLeft = topLeft + columns
                indices[triangle++] = topLeft.toShort()
                indices[triangle++] = bottomLeft.toShort()
                indices[triangle++] = (topLeft + 1).toShort()
                indices[triangle++] = (topLeft + 1).toShort()
                indices[triangle++] = bottomLeft.toShort()
                indices[triangle++] = (bottomLeft + 1).toShort()
            }
        }
    }

    /**
     * 鎸夊綋鍓嶈繘搴﹂噸鍐欓《鐐瑰潗鏍囥€備笌 Flutter 閫愯涓€鑷达細
     *
     * ```
     * inset = 璇ヨ鍦嗚鍐呯缉
     * u     = inset + (1 - 2 * inset) * column / (columns - 1)
     * scale = 300 / (300 - depthAt(u, v, p))
     * x     = width  * (.5 + (u - .5) * scale)
     * y     = height * (.5 + (v - .5) * scale)
     * tex   = (imageWidth * u, imageHeight * v)
     * ```
     */
    fun fill(
        progress: Float,
        width: Float,
        height: Float,
        textureWidth: Float,
        textureHeight: Float
    ) {
        if (rows.isEmpty()) return
        val lastColumn = (columns - 1).toFloat()
        for (rowIndex in rows.indices) {
            val v = rows[rowIndex]
            val inset = WenkuFlightPose.columnInset(v, radiusX, radiusY)
            for (column in 0 until columns) {
                val u = inset + (1f - 2f * inset) * column / lastColumn
                val scale = WenkuFlightPose.BEND_FOCAL /
                    (WenkuFlightPose.BEND_FOCAL - WenkuFlightPose.depthAt(u, v, progress))
                val base = (rowIndex * columns + column) * 2
                positions[base] = width * (.5f + (u - .5f) * scale)
                positions[base + 1] = height * (.5f + (v - .5f) * scale)
                texCoords[base] = textureWidth * u
                texCoords[base + 1] = textureHeight * v
            }
        }
    }
}

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 绗斿埛锛歐enkuPaperBrush锛堥潤鎬佺汗鐞嗭紝姣忔椋炶鍙粦涓€娆★級
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * `motion_ui.dart:520-680` 鐨勩€岄潤鎬佺汗鐞嗐€嶏細`ImageShader` 鍙湪浣嶅浘鎹汉鏃堕噸寤轰竴娆★紝
 * 涔嬪悗姣忓抚鍙噸绠楅《鐐癸紝绗﹀悎銆屽皝闈綅鍥剧敤涓€娆¤緭鍏ワ紝涓嶈姣忓抚閲嶅缓銆嶃€? */
private class WenkuPaperBrush {

    /** 缃戞牸濉厖绗旓細浣嶅浘鐫€鑹插櫒 + 椤剁偣鑹诧紝`BlendMode.srcOver` 涓?Flutter 渚т竴鑷淬€?*/
    val fill: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 娌℃湁浣嶅浘鏃剁殑鍏滃簳绾搁潰锛團lutter 閲屾槸 `RepaintBoundary.toImageSync` 鐨勬浛韬級銆?*/
    val fallback: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKING_ARGB }

    private var bound: android.graphics.Bitmap? = null

    fun bind(bitmap: android.graphics.Bitmap?) {
        if (bitmap === bound) return
        bound = bitmap
        fill.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
}

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 瀵瑰 API
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/** 璧烽鐐癸細涔︽灦涓婄殑灏侀潰锛坄bounds` 涓庨琛岀敾甯冨悓涓€鍧愭爣绯伙級銆?*/
data class WenkuCoverSource(
    val bounds: Rect,
    val image: ImageBitmap?,
    val cornerRadius: Dp = 3.dp
)

/** 钀界偣锛氶槄璇婚〉 / 璇︽儏椤电殑灏侀潰浣嶃€?*/
data class WenkuCoverTarget(
    val bounds: Rect,
    val image: ImageBitmap?,
    val cornerRadius: Dp = 3.dp
)

/**
 * 灏侀潰绾稿紶椋炶鍥惧眰銆傛斁鍦ㄥ叏灞?overlay 閲岋紝鑷繁鐢讳竴寮犱細寮殑绾革細
 * 涓€涓?`drawVertices` 缃戞牸锛?7 鍒?脳 32 琛?+ 16 鏉¤竟缂樺姞瀵嗚锛夎€屼笉鏄?24 娆℃暣妫靛瓙鏍戠殑缁樺埗銆? *
 * - `visible = false` 鏃朵粈涔堥兘涓嶇敾锛屼篃涓嶅崰鍔ㄧ敾鏃堕棿锛? * - `departing = true`锛氫功鏋?鈫?鐩爣椤碉紝1000ms锛坄WenkuMotion.COVER_FLIGHT_MS`锛夛紱
 * - `departing = false`锛氱洰鏍囬〉 鈫?涔︽灦锛?40ms锛坄WenkuMotion.COVER_RETURN_MS`锛夛紝
 *   鐭╁舰鐨?`travel` 璧伴暅鍍忔椂搴忥紱
 * - `rememberWenkuAccessibility().reduceMotion` 涓虹湡鏃?*涓嶉**锛氳繘搴︾洿鎺ョ疆 1 骞剁珛鍒诲洖璋? *   `onFinished`锛岀敱闆嗘垚鏂圭洿鎺ュ垏椤点€? *
 * 缁撴潫锛堝惈琚彇娑堟椂鐨勯潤榛橈級鐢?`onFinished` 閫氱煡锛涜缁勫悎閿€姣佺殑椋炶涓嶄細鍐嶅洖璋冦€? */
@Composable
fun WenkuCoverFlight(
    visible: Boolean,
    source: WenkuCoverSource?,
    target: WenkuCoverTarget?,
    departing: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    val a11y = rememberWenkuAccessibility()
    val finish = rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    val durationMs = if (departing) WenkuMotion.COVER_FLIGHT_MS else WenkuMotion.COVER_RETURN_MS

    LaunchedEffect(visible, departing, durationMs, a11y.reduceMotion) {
        if (!visible) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (a11y.reduceMotion) {
            // 鍑忓皯鍔ㄦ晥锛氫笉椋炶锛岀洿鎺ヨ惤鍒扮粓鐐瑰苟浜よ繕鎺у埗鏉冦€?
            progress.snapTo(1f)
            finish.value()
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        // 绾挎€ф椂閽燂細浣嶇Щ/寮洸鐨勭紦鍔ㄩ兘鍦?WenkuFlightPose 閲岋紝涓嶈兘鍐嶅涓€灞傘€?
        progress.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
        finish.value()
    }

    if (!visible) return
    val from = source ?: return
    val to = target ?: return

    val begin = if (departing) from.bounds else to.bounds
    val end = if (departing) to.bounds else from.bounds
    val image = from.image ?: to.image
    val cornerRadiusPx = with(LocalDensity.current) { from.cornerRadius.toPx() }
    val bitmap = remember(image) { image?.asAndroidBitmap() }
    val mesh = remember { WenkuPaperMesh().apply { prepare() } }
    val brush = remember { WenkuPaperBrush() }
    val maskPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                drawWenkuCoverFlight(
                    progress = progress.value,
                    begin = begin,
                    end = end,
                    returning = !departing,
                    departing = departing,
                    silhouetteBounds = begin,
                    bitmap = bitmap,
                    cornerRadiusPx = cornerRadiusPx,
                    mesh = mesh,
                    brush = brush,
                    maskPaint = maskPaint
                )
            }
    )
}

/**
 * 灏侀潰鍒拌揪锛?20ms 鐨勩€屼竴璁版祬瀵硅绾垮垏鍏ャ€嶏紙`cover_arrival.dart` 鍏ㄦ枃锛夈€? *
 * 涓夋潯纭鍒欓€愭潯瀵瑰簲锛? * 1. **蹇呴』绛夌湡瀹炶В鐮佸抚**锛歚ready` 涓虹湡锛堟垨鏇剧粡涓虹湡锛夋墠璧疯窇锛屾湭灏辩华鏃朵粈涔堥兘涓嶇洊鈥斺€? *    涓?Flutter 鐨?`_hasFrame |= widget.ready` 鍚岀悊锛岄伩鍏嶆柊瑙ｇ爜灏哄瑙ｆ瀽鏈熼棿鎶婃棫鍍忕礌鐩栧洖楠ㄦ灦锛? * 2. **蹇呴』绛夎繃娓＄ǔ瀹?*锛氬厛璁╁嚭涓€甯э紙`withFrameNanos`锛夊啀纭涓€娆℃墠寮€璺戯紝
 *    瀵瑰簲 `addPostFrameCallback` 閲屽銆孒ero 璺敱鍒?attach 灏辨姤 completed銆嶇殑澶嶆煡锛? * 3. **鍙挱涓€娆?*锛歚rememberSaveable` 璁颁綇鏈鍒拌揪宸插睍绀鸿繃锛涢渶瑕佽法鍒楄〃鍥炴敹澶嶇敤鍚屼竴甯ф椂锛? *    鐢ㄥ甫 `frameKey` 鐨?[WenkuCoverArrivalFrame]锛屽畠鎶婇敭鐧昏杩涘急寮曠敤缂撳瓨
 *    [WenkuCoverArrivalCache]锛團lutter 鐨?`Expando<bool>` 绛変环鐗╋級銆? *
 * 鍑忓皯鍔ㄦ晥鏃剁洿鎺ュ嚭鍐呭锛屼笉璺?420ms銆? */
@Composable
fun WenkuCoverArrival(
    ready: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    WenkuCoverArrivalFrame(frameKey = null, ready = ready, modifier = modifier, content = content)
}

/** [WenkuCoverArrival] 鐨勬樉寮忛敭鐗堟湰锛歚frameKey` 閫氬父鐢ㄥ皝闈㈠瓧鑺傛暟缁勬垨涔︾睄 id銆?*/
@Composable
fun WenkuCoverArrivalFrame(
    frameKey: Any?,
    ready: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    var presented by rememberSaveable(frameKey) {
        mutableStateOf(WenkuCoverArrivalCache.wasPresented(frameKey))
    }
    var everReady by remember(frameKey) { mutableStateOf(ready) }
    var started by remember(frameKey) { mutableStateOf(false) }
    val progress = remember(frameKey) { Animatable(0f) }
    val hasFrame = everReady || ready

    LaunchedEffect(ready) {
        if (ready) everReady = true
    }

    LaunchedEffect(hasFrame, a11y.reduceMotion) {
        if (!hasFrame || presented || started) return@LaunchedEffect
        if (a11y.reduceMotion) {
            presented = true
            WenkuCoverArrivalCache.markPresented(frameKey)
            return@LaunchedEffect
        }
        // 绛夌湡瀹炰竴甯у啀澶嶆煡锛氬垰 attach 鐨勮繃娓′細鍏堟姤涓€娆°€屽凡瀹屾垚銆嶃€?
        withFrameNanos { }
        if (started || presented || !hasFrame) return@LaunchedEffect
        started = true
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = WenkuMotion.ENTER_MS, easing = LinearEasing)
        )
        presented = true
        WenkuCoverArrivalCache.markPresented(frameKey)
    }

    if (!hasFrame) {
        // No decoded frame yet: draw the content untouched so a cover can never be blanked out
        // by the wrapper (a missing `ready` signal must only cost the reveal, not the pixels).
        Box(modifier) { content() }
        return
    }
    if (!started && !presented) {
        // Ready and about to play: hold one frame at the clip origin (the slot underneath keeps
        // showing the page/skeleton, matching cover_arrival.dart).
        Box(modifier) {}
        return
    }
    if (presented) {
        Box(modifier) { content() }
        return
    }
    Box(
        modifier
            .graphicsLayer {
                val p = WenkuPlanePose.ease(progress.value.coerceIn(0f, 1f))
                translationX = WenkuPlanePose.offsetX(p) * density
                translationY = WenkuPlanePose.offsetY(p) * density
            }
            .drawWithCache {
                val viewport = Path()
                val maxSlant = 180.dp.toPx()
                onDrawWithContent {
                    val p = WenkuPlanePose.ease(progress.value.coerceIn(0f, 1f))
                    if (p >= 1f) {
                        drawContent()
                        return@onDrawWithContent
                    }
                    val points = WenkuPlanePose.viewportPoints(
                        width = size.width,
                        height = size.height,
                        progress = p,
                        direction = 1,
                        slant = WenkuPlanePose.slant(size.width, maxSlant)
                    )
                    viewport.rewind()
                    viewport.moveTo(points[0], points[1])
                    viewport.lineTo(points[2], points[3])
                    viewport.lineTo(points[4], points[5])
                    viewport.lineTo(points[6], points[7])
                    viewport.close()
                    clipPath(viewport) { this@onDrawWithContent.drawContent() }
                }
            }
    ) {
        content()
    }
}

/**
 * 銆岃繖涓€甯у凡缁忓睍绀鸿繃銆嶇殑寮遍敭缂撳瓨锛歚cover_arrival.dart` 閲?`Expando<bool>` 鐨勭瓑浠风墿銆? * 閿槸寮卞紩鐢紝璺熺潃璋冪敤鏂圭殑缂撳瓨鐢熷懡鍛ㄦ湡璧帮紝涓嶄細鍙﹀紑涓€浠藉皝闈㈢紦瀛樸€? */
object WenkuCoverArrivalCache {

    private val presented: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun wasPresented(key: Any?): Boolean = key != null && presented[key] == true

    fun markPresented(key: Any?) {
        if (key != null) presented[key] = true
    }

    fun clear(key: Any?) {
        if (key != null) presented.remove(key)
    }
}

// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// 缁樺埗锛氱綉鏍?+ 閬尅鍓奖
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

private fun Rect.toFlightRect(units: Float): WenkuFlightRect =
    WenkuFlightRect(left / units, top / units, right / units, bottom / units)

private fun WenkuFlightRect.toRect(units: Float): Rect =
    Rect(left * units, top * units, right * units, bottom * units)

/**
 * 涓€甯х焊寮犻琛屻€? *
 * 涓?`PaperCoverRender.paint` 鐨勯『搴忎弗鏍间竴鑷达細
 * 1. 鏈夐伄鎸″壀褰辨椂鍏?`saveLayer(paintBounds)`锛坄paintBounds` = 褰撳墠鐭╁舰 `inflate(28)`锛夛紱
 * 2. `translate(涓績 x, 涓績 y - lift)` 鈫?`rotate(roll)` 鈫?`translate(-w/2, -h/2)`锛? * 3. `drawVertices(涓夎褰㈢綉鏍? SRC_OVER, 浣嶅浘鐫€鑹插櫒 Paint)`锛? * 4. 鍓奖鐢?`DST_OUT` 鐨?Paint 鍦ㄥ浘灞傞噷鎿﹂櫎锛屽啀 `restore()`銆? *
 * `android.graphics.Vertices` 娌℃湁 `dispose()` 閭ｇ鐢熷懡鍛ㄦ湡锛團lutter 鐨?`ui.Vertices`
 * 闇€瑕佹樉寮?`dispose()` 鏄洜涓哄畠鎸佹湁鍘熺敓鍙ユ焺锛夛紱杩欓噷鐪熸璺ㄥ抚澶嶇敤銆佷笉閲嶅缓鐨勬槸
 * 琛岄噰鏍枫€佺储寮曘€侀《鐐归鑹叉暟缁勪笌鐫€鑹插櫒銆? */
private fun DrawScope.drawWenkuCoverFlight(
    progress: Float,
    begin: Rect,
    end: Rect,
    returning: Boolean,
    departing: Boolean,
    silhouetteBounds: Rect,
    bitmap: android.graphics.Bitmap?,
    cornerRadiusPx: Float,
    mesh: WenkuPaperMesh,
    brush: WenkuPaperBrush,
    maskPaint: Paint
) {
    if (begin.width <= 0f || begin.height <= 0f) return
    if (end.width <= 0f || end.height <= 0f) return

    // Flutter 鐢诲竷鍗曚綅鏄€昏緫鍍忕礌锛氱煩褰㈡暟瀛﹀湪 dp 绌洪棿姹傚€硷紝鍐嶄箻鍥?density銆?
    val units = density
    val rectDp = WenkuFlightPose.rectAt(
        begin = begin.toFlightRect(units),
        end = end.toFlightRect(units),
        returning = returning,
        value = progress.coerceIn(0f, 1f)
    )
    if (rectDp.width <= 0f || rectDp.height <= 0f) return
    val rect = rectDp.toRect(units)

    // Flutter `Rect get paintBounds => (Offset.zero & size).inflate(28)`銆?
    val paintBounds = rectDp.inflate(28f)

    // 寮洸濮挎€佸悆 Hero 閭ｅ眰鏃堕挓锛涚煩褰㈠悆绾挎€ц繘搴︼紙瑙佹枃浠跺ご銆屾椂閽熺害瀹氥€嶏級銆?
    val pose = WenkuFlightPose.heroClock(progress.coerceIn(0f, 1f))
    val liftPx = WenkuFlightPose.lift(pose) * units
    val rollDegrees = WenkuFlightPose.roll(pose) * (180f / PI_F)

    val occlusion = wenkuCoverSilhouette(
        // 涓?`PaperCoverRender._occlusion` 涓€鏍峰悆銆岃 Hero 濂楄繃涓€灞傘€嶇殑杩涘害銆?
        progress = pose,
        departing = departing,
        bounds = silhouetteBounds,
        lipHeightPx = 28f * units,
        cornerRadiusPx = cornerRadiusPx
    )

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        if (occlusion != null) {
            canvas.saveLayer(
                paintBounds.left * units,
                paintBounds.top * units,
                paintBounds.right * units,
                paintBounds.bottom * units,
                null
            )
        }
        canvas.save()
        canvas.translate(rect.left + rect.width * .5f, rect.top + rect.height * .5f - liftPx)
        canvas.rotate(rollDegrees)
        canvas.translate(-rect.width * .5f, -rect.height * .5f)
        if (bitmap != null) {
            brush.bind(bitmap)
            mesh.fill(
                progress = pose,
                width = rect.width,
                height = rect.height,
                textureWidth = bitmap.width.toFloat(),
                textureHeight = bitmap.height.toFloat()
            )
            // 骞冲彴 jar 閲屾病鏈?android.graphics.Vertices锛堝彧鏈?Canvas.VertexMode + Mesh锛夛紝
            // Android 的 drawVertices + BitmapShader 在模拟器（SwiftShader）上把贴图
            // 采样成灰蓝色块（R14 实测：纸是灰的、封面字只剩淡影）；drawBitmapMesh 是
            // 专门给「规则网格贴图」的 API，走纯位图路径，不经过着色器合成。
            canvas.drawBitmapMesh(
                bitmap,
                WENKU_MESH_COLUMNS - 1,
                mesh.rowCount - 1,
                mesh.positions,
                0,
                null,
                0,
                brush.fill
            )
        } else {
            canvas.drawRoundRect(
                0f,
                0f,
                rect.width,
                rect.height,
                cornerRadiusPx,
                cornerRadiusPx,
                brush.fallback
            )
        }
        canvas.restore()
        if (occlusion != null) {
            maskPaint.alpha = (occlusion.second * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(occlusion.first, maskPaint)
            canvas.restore()
        }
    }
}

/**
 * 閬尅鍓奖锛氱鍦?`p < .12` 鏃朵功鏋跺墠鍞囧帇鍦ㄧ焊涓婏紙alpha `1 鈫?0`锛夛紝
 * 鍒拌揪 `p > .88` 鏃剁焊婧滆繘鐩爣浣嶇殑鍓嶅攪涓嬶紙alpha `0 鈫?1`锛夛紝鏇茬嚎閮芥槸 `Curves.easeInOut`锛? * 涓?`PaperCoverRender._occlusion` 鐨勯槇鍊煎拰鎻掑€奸€愬瓧涓€鑷淬€? *
 * Flutter 鐨?Path 鏉ヨ嚜鍦烘櫙鍓嶆櫙锛坄PaperCoverOcclusionSource.coverOcclusion`锛夛紱鏈枃浠舵病鏈? * 閭ｄ釜涓婁笅鏂囷紝浜庢槸鐢ㄣ€岃捣椋?钀界偣鐭╁舰涓嬫部 + 28 楂樺害鐨勫墠鍞囥€嶅悎鎴愬悓涓€鍧楀墠鏅€斺€? * 鏈哄埗锛坄saveLayer` + `BlendMode.dstOut`锛変笌闃堝€间笉鍙樸€? */
private fun wenkuCoverSilhouette(
    progress: Float,
    departing: Boolean,
    bounds: Rect,
    lipHeightPx: Float,
    cornerRadiusPx: Float
): Pair<AndroidPath, Float>? {
    if (bounds.width <= 0f || bounds.height <= 0f) return null
    val alpha = if (departing) {
        if (progress >= .12f) return null
        1f - WenkuFlightPose.easeInOut((progress / .12f).coerceIn(0f, 1f))
    } else {
        if (progress <= .88f) return null
        WenkuFlightPose.easeInOut(((progress - .88f) / .12f).coerceIn(0f, 1f))
    }
    if (alpha <= 0.001f) return null
    val path = AndroidPath()
    val lip = RectF(bounds.left, bounds.bottom, bounds.right, bounds.bottom + lipHeightPx)
    path.addRoundRect(lip, cornerRadiusPx, cornerRadiusPx, AndroidPath.Direction.CW)
    return path to alpha
}

/**
 * `motion_ui.dart:94-101` lift/roll applied to whatever element is currently flying
 * (the shared-element cover in this app). The shared transition already owns the bounds,
 * so this only adds the paper pose: `lift = 18 * smooth(p/.18) * (1 - smooth((p-.42)/.16))`,
 * `roll = -.045 * sin(pi p) * (1 - smooth((p-.42)/.16))`.
 *
 * Values are kept at half strength in translation so the cover never fights its own
 * bounds interpolation. Inactive state renders nothing (single graphicsLayer, no ticker).
 */
@Composable
fun Modifier.wenkuPaperFlight(active: Boolean, strength: Float = .5f): Modifier {
    val clock = remember { Animatable(1f) }
    val reduce = rememberWenkuAccessibility().reduceMotion
    LaunchedEffect(active, reduce) {
        if (active && !reduce) {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(durationMillis = WenkuMotion.COVER_FLIGHT_MS, easing = LinearEasing))
        } else if (!active) {
            clock.snapTo(1f)
        }
    }
    if (!active) return this
    val pose = WenkuFlightPose.heroClock(clock.value.coerceIn(0f, 1f))
    return this.graphicsLayer {
        translationY = -WenkuFlightPose.lift(pose) * strength * density
        rotationZ = WenkuFlightPose.roll(pose) * (180f / PI_F)
    }
}
