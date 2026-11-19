package com.example.ui.reader

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ListeningChapter
import com.example.data.ListeningState
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.data.SpeechSource
import com.example.data.CustomVoiceService
import com.example.data.EdgeVoice
import kotlinx.coroutines.CancellationException
import com.example.i18n.tr
import com.example.ui.components.AppIconButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.wenku.LocalWenkuDecorativeMotion
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuReaderProgressSlider
import com.example.ui.wenku.WenkuSheetDialog
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ReaderListeningPanel(
    manager: TtsManager,
    state: ListeningState,
    chapter: ListeningChapter?,
    prefs: PreferencesManager,
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    onPlay: (Int?) -> Unit,
    onPlayFromReading: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    var close by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(prefs.ttsSpeed) }
    var pitch by remember { mutableFloatStateOf(prefs.ttsPitch) }
    var gap by remember { mutableIntStateOf(manager.gapMillis) }
    var voice by remember { mutableStateOf(manager.voiceName) }
    var provider by remember { mutableStateOf(manager.source) }
    var showCustom by remember { mutableStateOf(false) }
    var edgeVoice by remember { mutableStateOf(manager.edgeVoice) }
    var edgeVoices by remember { mutableStateOf(emptyList<EdgeVoice>()) }
    var edgeLoading by remember { mutableStateOf(false) }
    var edgeError by remember { mutableStateOf(false) }
    var edgeRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(voices, provider, edgeRetry) {
        if (!voices || provider != SpeechSource.Edge) return@LaunchedEffect
        edgeLoading = true
        edgeError = false
        try {
            edgeVoices = manager.availableEdgeVoices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            edgeError = true
        } finally {
            edgeLoading = false
        }
    }
    val target = state.chapter ?: chapter
    val playing = state.playing || state.preparing
    val count = target?.paragraphs?.size ?: 0
    val paragraph = if (state.chapter != null) state.paragraph else chapter?.let(manager::savedParagraph) ?: 0
    var preview by remember(target) { mutableStateOf<Int?>(null) }
    var queued by remember(target) { mutableStateOf<Int?>(null) }
    val selected = preview ?: queued ?: paragraph
    val last = (count - 1).coerceAtLeast(0)
    val systemVoices = remember(state.engineReady, voices) {
        if (voices && state.engineReady) manager.availableVoices(refresh = true) else emptyList()
    }

    LaunchedEffect(manager) { manager.prepare() }
    WenkuSheetDialog(onDismissRequest = onDismiss, requestDismiss = close, fitKeyboard = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Headphones, null, tint = colors.onPrimaryContainer)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tr("听书"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(target?.bookTitle.orEmpty(), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AppIconButton(Icons.Filled.Close, tr("关闭"), { close = true })
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant.copy(alpha = .68f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(target?.title.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val excerpt = target?.paragraphs?.getOrNull(selected)?.text
                    if (excerpt != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(excerpt, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    WenkuReaderProgressSlider(
                        value = selected.toFloat() / last.coerceAtLeast(1),
                        label = tr("本章进度"),
                        format = { "${(it * last).roundToInt() + 1} / $count" },
                        tint = colors.primary,
                        enabled = count > 1,
                        modifier = Modifier.fillMaxWidth(),
                        onValueChange = { preview = (it * last).roundToInt() },
                        onCommit = {
                            preview?.let { if (state.chapter == null) queued = it else manager.seek(it) }
                            preview = null
                        }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (count > 0) "${selected + 1} / $count ${tr("段")}" else tr("本章没有可朗读的正文"),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(if (state.preparing) tr("正在准备语音") else if (state.playing) tr("朗读中") else if (state.finished && state.chapter != null) tr("本章已播完") else
                            if (state.chapter != null || chapter?.let(manager::savedParagraph) != null) tr("可续听") else "",
                            style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(Icons.Filled.SkipPrevious, tr("上一段"), { manager.moveParagraph(-1) },
                    enabled = state.chapter != null && paragraph > 0)
                PullOutTextButton(onClick = { manager.skipSeconds(-15) }, enabled = state.chapter != null && !state.finished,
                    modifier = Modifier.semantics { contentDescription = tr("后退 15 秒") }) { Text("−15") }
                AppIconButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    tr(if (playing) "暂停" else if (state.chapter != null && !state.finished) "继续听书" else "播放"),
                    { if (playing) manager.pause() else onPlay(queued); queued = null },
                    modifier = Modifier.size(62.dp).clip(CircleShape).background(colors.primary),
                    enabled = count > 0, active = playing, tint = colors.onPrimary, glyphSize = 27.dp)
                PullOutTextButton(onClick = { manager.skipSeconds(15) }, enabled = state.chapter != null && !state.finished,
                    modifier = Modifier.semantics { contentDescription = tr("前进 15 秒") }) { Text("+15") }
                AppIconButton(Icons.Filled.SkipNext, tr("下一段"), { manager.moveParagraph(1) },
                    enabled = state.chapter != null && paragraph < last)
            }
            if (state.error != null) {
                Text(tr(state.error), color = colors.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                PullOutTextButton(onClick = {
                    val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    if (runCatching { context.startActivity(install) }.isFailure) {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    }
                }) { Text(tr("管理系统语音")) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PullOutTextButton(onClick = { queued = null; onPlayFromReading() }, enabled = chapter?.paragraphs?.isNotEmpty() == true) {
                    Text(tr("从阅读位置听起"))
                }
                PullOutTextButton(onClick = manager::stop, enabled = state.chapter != null) { Text(tr("停止听书")) }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PullOutTextButton(onClick = { settings = !settings; if (!settings) voices = false }) {
                    Text(tr(if (settings) "收起播放设置" else "播放设置"))
                }
                Text("${String.format(Locale.ROOT, "%.2f", speed)}× · ${timerLabel(state.timer)}",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            AnimatedVisibility(
                settings,
                enter = if (motion) expandVertically(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) + fadeIn() else EnterTransition.None,
                exit = if (motion) shrinkVertically(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) + fadeOut() else ExitTransition.None
            ) {
                Column(Modifier.animateContentSize(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease))) {
                    ListeningValue("语速", speed, .5f..3f, 24, { speed = it }) {
                        prefs.ttsSpeed = speed
                        manager.setSpeech(speed, pitch)
                    }
                    ListeningValue("音调", pitch, .5f..2f, 14, { pitch = it }) {
                        prefs.ttsPitch = pitch
                        manager.setSpeech(speed, pitch)
                    }
                    Text(tr("段落间隔"), style = MaterialTheme.typography.labelLarge)
                    Row {
                        listOf(0, 500, 1000, 2000).forEach { ms ->
                            ListeningOption(if (ms == 0) tr("无") else "${ms / 1000f}s", gap == ms) {
                                gap = ms
                                manager.gapMillis = ms
                            }
                        }
                    }
                    ListeningOption(tr("跟随朗读段落"), follow) { onFollow(!follow) }
                    Text(tr("定时停止"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        listOf(0, 15, 30, 60).forEach { minutes ->
                            ListeningOption(if (minutes == 0) tr("关闭") else "$minutes min", state.timer == minutes) {
                                manager.setTimer(minutes)
                            }
                        }
                    }
                    ListeningOption(tr("本章结束"), state.timer == -1) { manager.setTimer(if (state.timer == -1) 0 else -1) }
                    Text(tr("语音来源"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        listOf(SpeechSource.System to "系统语音", SpeechSource.Edge to "Edge 语音",
                            SpeechSource.Custom to "自定义语音").forEach { (source, label) ->
                            ListeningOption(tr(label), provider == source) {
                                provider = source
                                voices = false
                                showCustom = false
                                manager.selectSource(source)
                            }
                        }
                    }
                    if (provider != SpeechSource.System) {
                        Text(tr("联网朗读会将当前语段发送给所选语音服务。"),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (provider == SpeechSource.Custom) {
                        PullOutTextButton(onClick = { showCustom = !showCustom }) { Text(tr("配置自定义语音")) }
                        AnimatedVisibility(
                            showCustom,
                            enter = if (motion) expandVertically(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) + fadeIn() else EnterTransition.None,
                            exit = if (motion) shrinkVertically(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) + fadeOut() else ExitTransition.None
                        ) {
                            CustomVoiceSettings(manager.customService) {
                                manager.configureCustomVoice(it)
                                showCustom = false
                            }
                        }
                    } else {
                        PullOutTextButton(onClick = { voices = !voices },
                            enabled = provider == SpeechSource.Edge || state.engineReady) {
                            Text(tr(if (voices) "收起音色" else "选择音色"))
                        }
                    }
                    AnimatedVisibility(
                        voices && provider == SpeechSource.Edge,
                        enter = if (motion) expandVertically(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) + fadeIn() else EnterTransition.None,
                        exit = if (motion) shrinkVertically(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) + fadeOut() else ExitTransition.None
                    ) {
                        ListeningOption(tr("自动匹配"), edgeVoice.isEmpty()) {
                            edgeVoice = ""
                            manager.selectEdgeVoice("")
                            voices = false
                        }
                        if (edgeLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (edgeError) PullOutTextButton(onClick = { edgeRetry++ }) { Text(tr("重试在线音色")) }
                        var filter by remember { mutableStateOf("") }
                        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(),
                            label = { Text(tr("搜索语言或音色")) }, singleLine = true)
                        val defaultVoices = listOf(
                            EdgeVoice("zh-CN-XiaoxiaoNeural", "zh-CN", "晓晓"),
                            EdgeVoice("zh-CN-YunxiNeural", "zh-CN", "云希"),
                            EdgeVoice("ja-JP-NanamiNeural", "ja-JP", "Nanami"),
                            EdgeVoice("en-US-EmmaMultilingualNeural", "en-US", "Emma")
                        )
                        (defaultVoices + edgeVoices).distinctBy { it.name }.filter {
                            it.name.contains(filter, true) || it.label.contains(filter, true) || it.locale.contains(filter, true)
                        }.take(30).forEach { item ->
                            ListeningOption(item.label + " · " + item.locale, edgeVoice == item.name) {
                                edgeVoice = item.name
                                manager.selectEdgeVoice(item.name)
                                voices = false
                            }
                        }
                    }
                    AnimatedVisibility(
                        voices && provider == SpeechSource.System,
                        enter = if (motion) expandVertically(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) + fadeIn() else EnterTransition.None,
                        exit = if (motion) shrinkVertically(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) + fadeOut() else ExitTransition.None
                    ) {
                        ListeningOption(tr("自动匹配"), voice.isEmpty()) {
                            voice = ""
                            manager.selectVoice("")
                            voices = false
                        }
                        var filter by remember { mutableStateOf("") }
                        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(),
                            label = { Text(tr("搜索语言或音色")) }, singleLine = true)
                        val matches = remember(systemVoices, filter, voice) {
                            systemVoices.filter {
                                it.name.contains(filter, true) || it.locale.displayName.contains(filter, true) ||
                                    it.locale.toLanguageTag().contains(filter, true)
                            }.sortedBy { it.name != voice }.take(24)
                        }
                        if (matches.isEmpty()) {
                            Text(tr("没有匹配的系统音色"), style = MaterialTheme.typography.bodySmall)
                        }
                        matches.forEach { item ->
                            ListeningOption("${item.locale.displayName} · ${item.name}", voice == item.name) {
                                voice = item.name
                                manager.selectVoice(item.name)
                                voices = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun timerLabel(timer: Int) = when {
    timer < 0 -> tr("本章结束")
    timer > 0 -> "$timer min"
    else -> tr("定时关闭")
}

@Composable
private fun ListeningValue(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onCommit: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tr(label), style = MaterialTheme.typography.labelLarge)
        Text(String.format(Locale.ROOT, "%.2f", value), style = MaterialTheme.typography.labelMedium)
    }
    Slider(value.coerceIn(range), onChange, valueRange = range, steps = steps, onValueChangeFinished = onCommit)
}

@Composable
private fun ListeningOption(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (active) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f)
    val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val tint = if (motion) animateColorAsState(background, label = "listeningOption").value else background
    PullOutTextButton(onClick = onClick, modifier = Modifier.semantics { selected = active }) {
        Text(label, modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(tint)
            .padding(horizontal = 6.dp, vertical = 4.dp),
            color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CustomVoiceSettings(initial: CustomVoiceService, onSave: (CustomVoiceService) -> Unit) {
    var url by remember(initial) { mutableStateOf(initial.url) }
    var key by remember(initial) { mutableStateOf(initial.token) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var voice by remember(initial) { mutableStateOf(initial.voice) }
    val value = CustomVoiceService(url.trim(), key.trim(), model.trim(), voice.trim())
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(url, { url = it.take(2048) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(tr("语音接口地址")) }, placeholder = { Text("https://example.org/v1/audio/speech") })
        OutlinedTextField(key, { key = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(), label = { Text(tr("访问密钥（可选）")) })
        OutlinedTextField(model, { model = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(tr("语音模型")) })
        OutlinedTextField(voice, { voice = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(tr("音色名称")) })
        Text(tr("接口接受 JSON 格式的朗读请求，并返回音频。"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PullOutTextButton(onClick = { onSave(value) }, enabled = value.valid()) { Text(tr("保存")) }
    }
}
