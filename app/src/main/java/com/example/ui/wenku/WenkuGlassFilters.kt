package com.example.ui.wenku

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import com.kashif_e.backdrop.BackdropEffectScope
import kotlin.math.roundToInt

/**
 * 玻璃模糊的 RenderEffect 缓存（`native_effects.dart:905-944` 的 Android 等价物）。
 *
 * 参考实现里每一块玻璃按 `physicalBounds + morphProgress` 缓存 `ImageFilter`，因为 Flutter 的
 * `ImageFilter.blur` 会被塞进一层按尺寸创建的 `BackdropFilterLayer`。Android 这边不同：
 * `RenderEffect.createBlurEffect` 与图层尺寸无关，尺寸由 `DrawBackdropNode` 挂载时给的
 * `GraphicsLayer` 决定，所以真正会改变原生对象的只有半径与边缘模式。
 *
 * 但**分配频率**必须压住：`DrawBackdropElement` 每次重组都会 `invalidateDrawCache()` →
 * `apply(effects)`，而 `effects { blur(r) }` 里每次都 `RenderEffect.createBlurEffect(...)`。
 * 玻璃面板每次按压（`collectIsPressedAsState` → 重组）都会新建一个原生 RenderEffect，
 * dock 那种常驻玻璃更是一直在重组。这里按 0.25px 量化半径做极小的 LRU（同时存活的半径
 * 通常只有 1-3 个），于是重组只做一次字段赋值，不再碰原生分配。
 *
 * `RenderEffect` 是不可变对象，可以安全地同时挂到多个图层上。
 */
object WenkuGlassFilters {

    private const val MAX_ENTRIES = 12

    private val cache = LinkedHashMap<Int, RenderEffect>(MAX_ENTRIES, .75f, true)

    /** 半径已按 0.25px 量化；返回 null 表示当前系统不支持（< S）或半径非法。 */
    fun blur(radiusPx: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (radiusPx <= 0f || radiusPx.isNaN() || radiusPx.isInfinite()) return null

        val key = (radiusPx * 4f).roundToInt()
        if (key <= 0) return null
        cache[key]?.let { return it }

        val effect = runCatching {
            RenderEffect.createBlurEffect(key / 4f, key / 4f, Shader.TileMode.CLAMP)
        }.getOrNull() ?: return null

        cache[key] = effect
        while (cache.size > MAX_ENTRIES) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return effect
    }
}

/**
 * `blur(radiusPx)` 的缓存版本：语义与 `com.kashif_e.backdrop.effects.blur` 的单效果分支一致
 * （`TileMode.Clamp`、首个效果不加 padding），只是把原生对象换成 [WenkuGlassFilters] 里的那一个。
 *
 * 只在 `BackdropEffectScope` 内使用，因此调用点必须仍然写在 `drawBackdrop(effects = { ... })` 里。
 */
fun BackdropEffectScope.wenkuGlassBlur(radiusPx: Float) {
    if (radiusPx <= 0f) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    // 与 Blur.kt 相同的 padding 规则：只有链式效果（renderEffect 已存在）才需要外扩。
    if (renderEffect != null && radiusPx > padding) padding = radiusPx
    renderEffect = WenkuGlassFilters.blur(radiusPx)
}
