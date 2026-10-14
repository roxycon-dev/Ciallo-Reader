package com.example.ui.reader

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.max
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.ui.components.AppToast

/**
 * 小说插图全屏查看器：双指缩放、拖动平移、双击复位/放大、保存到相册。
 */
@Composable
fun NovelImageFullscreenViewer(
    path: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val close = { visibility.targetState = false }
    LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    var loaded by remember(path) { mutableStateOf(false) }
    val bitmap by com.example.ui.components.rememberAsyncState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (path.startsWith("epzip:")) {
                    NovelInlineImages.NovelImageCache.decodeEpubImage(path, 2048)
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, opts)
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= 2048) sample *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(path, o2)
                }
            }.getOrNull()
        }
        loaded = true
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AnimatedVisibility(visibleState = visibility,
            enter = fadeIn(tween(if (reduce) 0 else 180)) + scaleIn(tween(if (reduce) 0 else 220), initialScale = .97f),
            exit = fadeOut(tween(if (reduce) 0 else 150)) + scaleOut(tween(if (reduce) 0 else 150), targetScale = .98f)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val bmp = bitmap
            if (bmp == null && !loaded) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            } else if (bmp == null) {
                androidx.compose.material3.Text(
                    text = "图片读取失败，请重新导入书籍",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val scale = remember { mutableFloatStateOf(1f) }
                val offsetX = remember { mutableFloatStateOf(0f) }
                val offsetY = remember { mutableFloatStateOf(0f) }
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                var zoomJob by remember { mutableStateOf<Job?>(null) }
                fun bound(offset: Offset, atScale: Float): Offset {
                    val fit = min(viewport.width.toFloat() / bmp.width, viewport.height.toFloat() / bmp.height)
                    val maxX = max(0f, (bmp.width * fit * atScale - viewport.width) / 2f)
                    val maxY = max(0f, (bmp.height * fit * atScale - viewport.height) / 2f)
                    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
                val image = bmp.asImageBitmap()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .pointerInput(path) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                zoomJob?.cancel()
                                val next = (scale.value * zoom).coerceIn(1f, 6f)
                                val focal = centroid - Offset(size.width / 2f, size.height / 2f)
                                val offset = Offset(offsetX.value, offsetY.value)
                                val target = bound(focal - (focal - offset) * (next / scale.value) + pan, next)
                                scale.value = next
                                offsetX.value = target.x
                                offsetY.value = target.y
                            }
                        }
                        .pointerInput(path) {
                            detectTapGestures(
                                onDoubleTap = { point ->
                                    zoomJob?.cancel()
                                    val fromScale = scale.value
                                    val fromOffset = Offset(offsetX.value, offsetY.value)
                                    val targetScale = if (fromScale > 1.05f) 1f else 2.5f
                                    val focal = point - Offset(size.width / 2f, size.height / 2f)
                                    val toOffset = if (targetScale == 1f) Offset.Zero else bound(focal - (focal - fromOffset) * (targetScale / fromScale), targetScale)
                                    zoomJob = scope.launch {
                                        Animatable(0f).animateTo(1f, tween(if (reduce) 0 else 220, easing = AppMotion.easeOutCubic)) {
                                            scale.value = fromScale + (targetScale - fromScale) * value
                                            offsetX.value = fromOffset.x + (toOffset.x - fromOffset.x) * value
                                            offsetY.value = fromOffset.y + (toOffset.y - fromOffset.y) * value
                                        }
                                    }
                                }
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                        }
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = "插图全屏",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // 顶栏操作条（在图片层之上）
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Text(
                    text = "保存",
                    color = Color.White,
                    modifier = Modifier
                        .clickable {
                            val msg = saveNovelImageToGallery(context, path)
                            AppToast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
                androidx.compose.material3.Text(
                    text = "关闭",
                    color = Color.White,
                    modifier = Modifier
                        .clickable { close() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        }
    }
}

private fun saveNovelImageToGallery(context: Context, path: String): String {
    val bytes = if (path.startsWith("epzip:")) {
        NovelInlineImages.NovelImageCache.readEpubImageBytes(path)
    } else {
        runCatching { File(path).readBytes() }.getOrNull()
    }
    if (bytes == null) return "图片读取失败"
    val fileName = "novel_img_${System.currentTimeMillis()}.jpg"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Ciallo Reader")
        }
        val uri: Uri? = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
        if (uri == null) return "保存失败"
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        "已保存到相册 Pictures/Ciallo Reader"
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Ciallo Reader"
        )
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, fileName)
        bytes.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        "已保存：${target.absolutePath}"
    }
}
