package com.example.ui.reader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.components.AppToast
import com.example.ui.components.dismissPaperPage
import com.example.ui.components.openPaperPage
import com.example.ui.components.paperComposable
import com.example.ui.components.PullOutDialog
import com.example.ui.components.PullOutVisibility
import com.example.ui.components.rememberPaperClose
import com.example.ui.wenku.WenkuIconButton
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.wenkuColors
import com.example.ui.wenku.wenkuPagePart
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal fun NavController.openCoverGallery(cover: String, bookTitle: String, title: String) {
    openPaperPage("cover_gallery/${Uri.encode(cover)}/${Uri.encode(bookTitle)}/${Uri.encode(title)}")
}

internal fun NavGraphBuilder.coverGalleryDestination(navController: NavController) {
    paperComposable(
        "cover_gallery/{cover}/{bookTitle}/{title}",
        navController,
        arguments = listOf("cover", "bookTitle", "title").map { key ->
            navArgument(key) { type = NavType.StringType }
        },
        revealContent = true,
    ) { entry ->
        val context = LocalContext.current
        val request = remember(entry, context) {
            ImageRequest.Builder(context).data(entry.arguments?.getString("cover")).build()
        }
        CoverImageGallery(
            title = entry.arguments?.getString("title").orEmpty(),
            bookTitle = entry.arguments?.getString("bookTitle").orEmpty(),
            request = request,
            onBack = { navController.dismissPaperPage() },
        )
    }
}

@Composable
fun NovelImageFullscreenViewer(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (path.startsWith("epzip:")) {
                    NovelInlineImages.NovelImageCache.decodeEpubImage(path, 2048)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
        loading = false
    }

    ImageGalleryDialog(onDismiss) { close ->
        ArchiveImageGallery(
            title = "章节插图",
            bitmap = bitmap,
            loading = loading,
            onBack = close,
            onSave = {
                val bytes = if (path.startsWith("epzip:")) {
                    NovelInlineImages.NovelImageCache.readEpubImageBytes(path)
                } else {
                    File(path).readBytes()
                }
                bytes != null && writeGalleryImage(context, "novel_img", "image/jpeg") {
                    it.write(bytes)
                    true
                }
            },
        )
    }
}

@Composable
internal fun CoverImageGallery(
    title: String,
    bookTitle: String,
    request: ImageRequest,
    imageLoader: ImageLoader = LocalContext.current.imageLoader,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var retry by remember(request) { mutableIntStateOf(0) }
    var bitmap by remember(request, retry) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(request, retry) { mutableStateOf(true) }
    LaunchedEffect(request, imageLoader, retry) {
        val preview = request.newBuilder().size(2048).allowHardware(false).build()
        val result = imageLoader.execute(preview)
        bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
        loading = false
    }
    ArchiveImageGallery(
        title = title,
        bitmap = bitmap,
        loading = loading,
        onBack = onBack,
        onRetry = { retry++ },
        onSave = {
            val full = request.newBuilder().size(coil.size.Size.ORIGINAL).allowHardware(false).build()
            val image = (imageLoader.execute(full) as? SuccessResult)?.drawable?.toBitmap()
            image != null && writeGalleryImage(context, bookTitle, "image/png") {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        },
        cover = true,
    )
}

@Composable
internal fun ImageGalleryDialog(
    onDismiss: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = rememberPaperClose(visibility, onDismiss)
    val close = { dismiss {} }
    PullOutDialog(
        animateContent = false,
        lifecycleState = visibility,
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PullOutVisibility(visibleState = visibility) { content(close) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveImageGallery(
    title: String,
    bitmap: Bitmap?,
    loading: Boolean,
    onBack: () -> Unit,
    onSave: suspend () -> Boolean,
    onRetry: (() -> Unit)? = null,
    cover: Boolean = false,
) {
    val colors = wenkuColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = colors.paper,
        topBar = {
            CenterAlignedTopAppBar(
                expandedHeight = 52.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.paper),
                navigationIcon = {
                    WenkuIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack, tint = colors.ink)
                },
                title = {
                    Column(Modifier.wenkuPagePart(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Text("IMAGE / 1 OF 1", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    }
                },
                actions = {
                    WenkuIconButton(
                        Icons.Filled.Download,
                        if (saving) "正在保存图片" else "保存图片",
                        onClick = {
                            if (!saving && bitmap != null) {
                                saving = true
                                scope.launch {
                                    try {
                                        val saved = withContext(Dispatchers.IO) {
                                            try { onSave() } catch (error: Exception) {
                                                if (error is kotlinx.coroutines.CancellationException) throw error
                                                false
                                            }
                                        }
                                        AppToast.makeText(context, if (saved) "已保存到相册" else "保存图片失败", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        },
                        tint = colors.ink,
                        enabled = bitmap != null && !saving,
                        wenkuSymbol = WenkuSymbol.Download,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> ZoomableGalleryImage(
                    bitmap,
                    contentDescription = title,
                    initialScale = if (cover) .9f else 1f,
                    maxScale = if (cover) 5f else 6f,
                    doubleTapScale = if (cover) 2f else 2.5f,
                    onTap = onBack.takeIf { cover },
                )
                loading -> CircularProgressIndicator(color = colors.ink)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("图片读取失败", color = colors.inkSecondary)
                    if (onRetry != null) WenkuTextButton("重试", onRetry, wenkuSymbol = WenkuSymbol.Refresh)
                }
            }
        }
    }
}

@Composable
private fun ZoomableGalleryImage(
    bitmap: Bitmap,
    contentDescription: String,
    initialScale: Float,
    maxScale: Float,
    doubleTapScale: Float,
    onTap: (() -> Unit)?,
) {
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onTap)
    val scale = remember(bitmap) { mutableFloatStateOf(initialScale) }
    val translation = remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var settle by remember { mutableStateOf<Job?>(null) }
    fun bound(offset: Offset, atScale: Float): Offset {
        val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = max(0f, (bitmap.width * fit * atScale - viewport.width) / 2f)
        val maxY = max(0f, (bitmap.height * fit * atScale - viewport.height) / 2f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .onSizeChanged {
                viewport = it
                translation.value = bound(translation.value, scale.floatValue)
            }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    settle?.cancel()
                    val current = scale.floatValue
                    val next = (current * zoom).coerceIn(if (initialScale < 1f) .25f else 1f, maxScale)
                    val focal = centroid - Offset(size.width / 2f, size.height / 2f)
                    translation.value = bound(focal - (focal - translation.value) * (next / current) + pan, next)
                    scale.floatValue = next
                }
            }
            .pointerInput(bitmap, reduceMotion, onTap != null) {
                detectTapGestures(
                    onTap = if (onTap != null) ({ close?.invoke() }) else null,
                    onDoubleTap = { point ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        settle?.cancel()
                        val fromScale = scale.floatValue
                        val fromOffset = translation.value
                        val toScale = if (kotlin.math.abs(fromScale - initialScale) > .05f) initialScale else doubleTapScale
                        val focal = point - Offset(size.width / 2f, size.height / 2f)
                        val toOffset = bound(focal - (focal - fromOffset) * (toScale / fromScale), toScale)
                        if (reduceMotion) {
                            scale.floatValue = toScale
                            translation.value = toOffset
                        } else {
                            settle = scope.launch {
                                Animatable(0f).animateTo(1f, tween(140, easing = WenkuMotion.ease)) {
                                    scale.floatValue = fromScale + (toScale - fromScale) * value
                                    translation.value = fromOffset + (toOffset - fromOffset) * value
                                }
                            }
                        }
                    },
                )
            },
    ) {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.floatValue
                scaleY = scale.floatValue
                translationX = translation.value.x
                translationY = translation.value.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

private fun writeGalleryImage(
    context: Context,
    title: String,
    mimeType: String,
    write: (OutputStream) -> Boolean,
): Boolean {
    val prefix = title.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("").take(70).ifBlank { "ciallo" }
    val extension = if (mimeType == "image/png") "png" else "jpg"
    val name = "${prefix}_${System.currentTimeMillis()}.$extension"
    val album = "Ciallo Reader"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: return false
        var saved = false
        try {
            if (resolver.openOutputStream(uri)?.use(write) != true) return false
            saved = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) > 0
            return saved
        } finally {
            if (!saved) resolver.delete(uri, null, null)
        }
    }
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album)
    if (!dir.isDirectory && !dir.mkdirs()) return false
    val file = File(dir, name)
    var saved = false
    try {
        saved = file.outputStream().use(write)
        if (saved) android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mimeType), null)
        return saved
    } finally {
        if (!saved) file.delete()
    }
}
