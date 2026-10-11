package com.example.ui.shelf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

/** Captures only visible cards. Removal remains a business operation, independent of the effect. */
@Stable
class ShelfMotionState internal constructor(
    private val snapshot: suspend (GraphicsLayer) -> ImageBitmap = { it.toImageBitmap() },
) {
    internal class Capture(val layer: GraphicsLayer, var origin: Offset = Offset.Zero)
    internal val captures = mutableMapOf<String, Capture>()
    internal val bursts = mutableStateListOf<ShelfBurst>()
    internal val hidden = mutableStateMapOf<String, Boolean>()
    internal var lastCaptureFailure: Throwable? = null
    var hideBooks by mutableStateOf(false)
    var hideFavorites by mutableStateOf(false)

    fun remove(keys: List<String>, scope: CoroutineScope, reduceMotion: Boolean, onRemove: () -> Unit) {
        val fresh = keys.distinct().filterNot { hidden.containsKey(it) }
        if (fresh.isEmpty()) return
        // Reserve keys immediately, but keep the real card visible while a GPU snapshot is pending.
        fresh.forEach { hidden[it] = false }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val created = mutableListOf<ShelfBurst>()
            try {
                try {
                    if (!reduceMotion) fresh.take(8).forEach { key ->
                        if (if (isFavShelfKey(key)) hideFavorites else hideBooks) return@forEach
                        val capture = captures[key] ?: return@forEach
                        val bitmap = try { snapshot(capture.layer) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            lastCaptureFailure = failure
                            android.util.Log.w("ShelfMotion", "Card snapshot unavailable", failure)
                            return@forEach
                        }
                        if (bitmap.width > 0 && bitmap.height > 0) {
                            created += ShelfBurst(key, bitmap, capture.origin, shelfShards(bitmap.width, bitmap.height, key.hashCode()))
                        }
                    }
                } finally {
                    // Start undispatched so a leaving/cancelled screen still performs the confirmed action.
                    fresh.forEach { hidden[it] = true }
                    onRemove()
                }
                bursts.addAll(created)
                if (created.isEmpty()) delay(120) // Debounce duplicate confirmations, including reduced motion.
                created.map { burst -> launch {
                    try { burst.progress.animateTo(1f, tween(660, easing = LinearEasing)) }
                    finally { bursts.remove(burst) }
                } }.joinAll()
            } finally {
                // The snapshot is now independent of the removed item and its graphics layer.
                bursts.removeAll(created.toSet())
                fresh.forEach { hidden.remove(it) }
            }
        }
    }
}

internal class ShelfBurst(val key: String, val bitmap: ImageBitmap, val origin: Offset, val shards: List<ShelfShard>) {
    val progress = Animatable(0f)
}

internal data class ShelfShard(val points: List<Offset>, val center: Offset, val velocity: Offset, val spin: Float, val delay: Float) {
    val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x,it.y) }; close() }
}

/** A jittered tessellation: every triangle is an actual piece of the cover, without gaps at t=0. */
internal fun shelfShards(width: Int, height: Int, seed: Int): List<ShelfShard> {
    val random = Random(seed)
    val columns = 4
    val rows = 6
    val grid = List(rows + 1) { y -> List(columns + 1) { x ->
        Offset((x + if (x in 1 until columns) random.nextFloat() * .5f - .25f else 0f) * width / columns,
            (y + if (y in 1 until rows) random.nextFloat() * .5f - .25f else 0f) * height / rows)
    } }
    return buildList {
        for (y in 0 until rows) for (x in 0 until columns) {
            val a = grid[y][x]; val b = grid[y][x + 1]; val c = grid[y + 1][x + 1]; val d = grid[y + 1][x]
            val triangles = if ((x + y) % 2 == 0) listOf(listOf(a,b,c), listOf(a,c,d)) else listOf(listOf(a,b,d), listOf(b,c,d))
            triangles.forEach { points ->
                val center = points.reduce { sum, p -> sum + p } / 3f
                val radial = (center.x / width - .5f) * width
                add(ShelfShard(points, center, Offset(radial * .75f + (random.nextFloat() - .5f) * width * .35f,
                    -height * (.12f + random.nextFloat() * .18f)), (random.nextFloat() - .5f) * 70f,
                    (center.y / height) * .12f + random.nextFloat() * .04f))
            }
        }
    }
}

@Composable
fun rememberShelfMotionState() = remember { ShelfMotionState() }

/** Call before changing the sort, while the grid still has its old index and offset. */
internal fun keepShelfViewportForSort(state: LazyGridState) {
    state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
}

@Composable
fun Modifier.shelfCapture(state: ShelfMotionState, key: String): Modifier {
    val layer = rememberGraphicsLayer()
    val capture = remember(layer, key) { ShelfMotionState.Capture(layer) }
    DisposableEffect(state, key, capture) {
        state.captures[key] = capture
        onDispose { if (state.captures[key] === capture) state.captures.remove(key) }
    }
    return onGloballyPositioned { capture.origin = it.positionInRoot() }.drawWithContent {
        val private = if (isFavShelfKey(key)) state.hideFavorites else state.hideBooks
        if (state.hidden[key] != true && !private) {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }
}

@Composable
fun ShelfShatterOverlay(state: ShelfMotionState, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        state.bursts.forEach { burst ->
            if (if (isFavShelfKey(burst.key)) state.hideFavorites else state.hideBooks) return@forEach
            val progress = burst.progress.value
            translate(burst.origin.x - origin.x, burst.origin.y - origin.y) {
                burst.shards.forEach { shard ->
                    val t = ((progress - shard.delay) / (1f - shard.delay)).coerceIn(0f, 1f)
                    val travel = 1f - (1f - t).pow(2)
                    val fade = (1f - ((t - .22f) / .78f).coerceIn(0f,1f)).pow(1.5f)
                    translate(shard.velocity.x * travel, shard.velocity.y * travel + burst.bitmap.height * .75f * t * t) {
                        rotate(shard.spin * travel, shard.center) {
                            scale(1f - .24f * t, 1f - .24f * t, shard.center) {
                                clipPath(shard.path) { drawImage(burst.bitmap, alpha = fade) }
                            }
                        }
                    }
                }
            }
        }
    }
}
