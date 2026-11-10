package com.example.ui.privacy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.CategoryEntity
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppSwitch
import com.example.ui.components.paperDismiss
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.wenku.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class PinEntryMode { SETUP, CONFIRM, VERIFY, CHANGE_OLD, CHANGE_NEW }

@Composable
fun PrivacyPinOverlay(
    mode: PinEntryMode,
    onPinSet: suspend (String) -> Boolean = { false },
    onPinVerified: suspend (String) -> Boolean = { false },
    onPinChanged: suspend (String, String) -> Boolean = { _, _ -> false },
    onAccepted: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var stage by remember(mode) { mutableStateOf(mode) }
    var entered by remember(mode) { mutableStateOf("") }
    var firstPin by remember(mode) { mutableStateOf<String?>(null) }
    var oldPin by remember(mode) { mutableStateOf<String?>(null) }
    var error by remember(mode) { mutableStateOf<String?>(null) }
    var busy by remember(mode) { mutableStateOf(false) }

    fun reset(message: String? = null) {
        entered = ""
        error = message
    }

    WenkuSheetDialog(onDismissRequest = onDismiss, dismissGesturesEnabled = !busy, fitKeyboard = true) {
        val accepted = paperDismiss(onAccepted)
        val scope = rememberCoroutineScope()
        fun submitPin(pin: String) {
            when (stage) {
                PinEntryMode.SETUP, PinEntryMode.CHANGE_NEW -> {
                    firstPin = pin
                    stage = PinEntryMode.CONFIRM
                    reset()
                }
                PinEntryMode.CONFIRM -> {
                    if (pin != firstPin) {
                        firstPin = null
                        stage = if (oldPin == null) PinEntryMode.SETUP else PinEntryMode.CHANGE_NEW
                        reset(if (oldPin == null) "两次输入不一致，请重新设置" else "两次输入不一致，请重新输入新密码")
                        return
                    }
                    busy = true
                    scope.launch {
                        val previous = oldPin
                        val saved = pinResult {
                            if (previous == null) onPinSet(pin) else onPinChanged(previous, pin)
                        }
                        firstPin = null
                        if (saved) {
                            oldPin = null
                            reset()
                            accepted()
                        } else {
                            stage = if (previous == null) PinEntryMode.SETUP else PinEntryMode.CHANGE_NEW
                            reset("密码保存失败，请重试")
                            busy = false
                        }
                    }
                }
                PinEntryMode.VERIFY, PinEntryMode.CHANGE_OLD -> {
                    val changing = stage == PinEntryMode.CHANGE_OLD
                    busy = true
                    scope.launch {
                        when {
                            !pinResult { onPinVerified(pin) } -> {
                                reset("密码错误，请重试")
                                busy = false
                            }
                            changing -> {
                                oldPin = pin
                                stage = PinEntryMode.CHANGE_NEW
                                reset()
                                busy = false
                            }
                            else -> {
                                reset()
                                accepted()
                            }
                        }
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxHeight < 440.dp
            val keyHeight = if (compact) 48.dp else 58.dp
            val spacing = if (compact) 6.dp else 10.dp
            val reduce = LocalReduceMotion.current
            val colors = MaterialTheme.colorScheme
            val close = paperDismiss {}
            WenkuLoadedContentSequence {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().wenkuPagePart(), verticalAlignment = Alignment.CenterVertically) {
                        if (!compact) {
                            WenkuIcon(WenkuSymbol.Shield, size = 24.dp, tint = colors.primary, entrance = false)
                            Spacer(Modifier.width(10.dp))
                        }
                        AnimatedContent(
                            targetState = stage,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                                else (slideInVertically(tween(200, easing = WenkuMotion.ease)) { it / 4 } + fadeIn(tween(200))) togetherWith
                                    (slideOutVertically(tween(140)) { -it / 5 } + fadeOut(tween(140)))
                            },
                            label = "privacyPinStage",
                        ) { current ->
                            Column {
                                val title = when (current) {
                                    PinEntryMode.SETUP -> "设置隐私密码"
                                    PinEntryMode.CONFIRM -> "再次输入以确认"
                                    PinEntryMode.VERIFY -> "输入隐私密码"
                                    PinEntryMode.CHANGE_OLD -> "输入当前密码"
                                    PinEntryMode.CHANGE_NEW -> "输入新密码"
                                }
                                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text("6 位数字密码", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        AppIconButton(Icons.Filled.Close, "关闭面板", onClick = close, enabled = !busy)
                    }
                    Spacer(Modifier.height(spacing))
                    PinDots(entered.length, error != null, Modifier.wenkuPagePart(1))
                    Box(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        contentAlignment = Alignment.Center) {
                        val status = when {
                            error != null -> error.orEmpty()
                            !busy -> ""
                            stage == PinEntryMode.CONFIRM -> "正在保存…"
                            else -> "正在验证…"
                        }
                        Text(status, color = if (error == null) colors.onSurfaceVariant else colors.error,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    Column(Modifier.widthIn(max = 300.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                        for (row in 0..3) {
                            Row(Modifier.fillMaxWidth().wenkuPagePart(row + 2), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                for (column in 0..2) {
                                    val position = row * 3 + column
                                    val keyModifier = Modifier.weight(1f).height(keyHeight)
                                    when (position) {
                                        9 -> Spacer(keyModifier)
                                        11 -> PinKey(null, keyModifier, !busy && entered.isNotEmpty()) {
                                            entered = entered.dropLast(1)
                                            error = null
                                        }
                                        else -> {
                                            val digit = if (position == 10) "0" else (position + 1).toString()
                                            PinKey(digit, keyModifier, !busy && entered.length < 6) {
                                                if (!busy && entered.length < 6) {
                                                    entered += digit
                                                    error = null
                                                    if (entered.length == 6) submitPin(entered)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun pinResult(action: suspend () -> Boolean): Boolean = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

@Composable
private fun PinDots(filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val offset = remember { Animatable(0f) }
    LaunchedEffect(error, reduce) {
        offset.snapTo(0f)
        if (error && !reduce) {
            listOf(-10f, 10f, -6f, 6f, -3f, 0f).forEach { offset.animateTo(it, tween(42)) }
        }
    }
    Row(
        modifier.graphicsLayer { translationX = offset.value }.semantics {
            stateDescription = "已输入 $filled 位，共 6 位"
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(6) { index ->
            val color = when {
                error -> colors.error
                index < filled -> colors.primary
                else -> colors.outlineVariant
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun PinKey(digit: String?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var activation by remember { mutableIntStateOf(0) }
    WenkuGlassSurface(
        modifier = modifier.semantics(mergeDescendants = true) {
            if (digit == null) contentDescription = "删除一位"
            if (!enabled) {
                role = Role.Button
                disabled()
            }
        },
        shape = RoundedCornerShape(16.dp),
        blur = 0.dp,
        interactionSource = interaction,
        onClick = if (enabled) ({
            activation++
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ink = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = .45f)
            if (digit != null) {
                Text(digit, color = ink, style = MaterialTheme.typography.titleLarge)
            } else {
                CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed && enabled, activation)) {
                    WenkuIcon(WenkuSymbol.Back, size = 21.dp, tint = ink, entrance = false)
                }
            }
        }
    }
}

@Composable
fun PrivacyManageOverlay(
    categories: List<CategoryEntity>,
    privacyModeEnabled: Boolean,
    onToggleProtected: (CategoryEntity, Boolean) -> Unit,
    onToggleIncognito: (Boolean) -> Unit = {},
    incognitoBrowsingEnabled: Boolean = false,
    favoritesProtected: Boolean = false,
    onToggleFavoritesProtected: (Boolean) -> Unit = {},
    onChangePin: () -> Unit,
    onDisablePrivacy: () -> Unit,
    onDismiss: () -> Unit,
    requestDismiss: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    WenkuActionSheet("隐私模式", onDismiss, fitKeyboard = true, requestDismiss = requestDismiss) {
        Text("密码保护与无痕阅读", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        WenkuContentPanel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrivacySwitchRow("无痕浏览", "所有阅读不计入统计，阅读进度继续保存",
                    WenkuSymbol.EyeOff, incognitoBrowsingEnabled, onToggleIncognito)
                PrivacySwitchRow("「我喜欢的」需要密码", "验证密码后显示收藏",
                    WenkuSymbol.Bookmarked, favoritesProtected, onToggleFavoritesProtected)
            }
        }
        if (categories.isNotEmpty()) {
            Text("书架分类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp))
            WenkuContentPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    categories.forEach { category ->
                        key(category.id) {
                            PrivacySwitchRow(category.name, if (category.isProtected) "验证密码后显示 · 无痕阅读" else null,
                                if (category.isProtected) WenkuSymbol.Shield else WenkuSymbol.Folder,
                                category.isProtected, { onToggleProtected(category, it) })
                        }
                    }
                }
            }
        }
        WenkuTextButton("修改密码", onChangePin, Modifier.fillMaxWidth(), enabled = privacyModeEnabled,
            wenkuSymbol = WenkuSymbol.Edit)
        WenkuTextButton("关闭隐私模式", onDisablePrivacy, Modifier.fillMaxWidth(), enabled = privacyModeEnabled,
            color = colors.error, wenkuSymbol = WenkuSymbol.Shield)
        WenkuTextButton("完成", paperDismiss {}, Modifier.fillMaxWidth(), selected = true,
            wenkuSymbol = WenkuSymbol.Check)
    }
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String?,
    icon: WenkuSymbol,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WenkuIcon(icon, size = 19.dp, tint = if (checked) colors.primary else colors.onSurfaceVariant, entrance = false)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        AppSwitch(checked, onChange, Modifier.semantics { contentDescription = title })
    }
}
