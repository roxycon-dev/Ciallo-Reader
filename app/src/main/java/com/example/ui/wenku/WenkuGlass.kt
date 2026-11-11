package com.example.ui.wenku

import com.example.i18n.tr

import com.example.ui.components.RenderQuality
import com.example.ui.components.LocalRenderQuality
import com.kashif_e.backdrop.effects.lens
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LocalGlassBackdrop
import com.example.ui.components.pullOutClickable
import com.example.ui.feedback.LocalHapticsEnabled
import com.example.ui.theme.AppFonts
import com.kashif_e.backdrop.drawBackdrop
import me.trishiraj.shadowglow.consistentShadow

// Stable no-op slots: passing fresh `{ null }` lambdas would make DrawBackdropElement unequal on
// every recomposition and re-apply the effects for nothing.
private val WenkuNoHighlight: () -> com.kashif_e.backdrop.highlight.Highlight? = { null }
private val WenkuNoShadow: () -> com.kashif_e.backdrop.shadow.Shadow? = { null }

/**
  * 鐠囷箒顫?docs/wenku8-ui-port-plan.md
  * 鐠囷箒顫?docs/wenku8-ui-port-plan.md */
@Composable
fun WenkuGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = WenkuTokens.glassShape,
    tint: Color? = null,
    blur: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    cardTweaks: com.example.ui.components.CardTweaks? = null,
    interactionSource: MutableInteractionSource? = null,
    role: Role = Role.Button,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = wenkuColors()
    val edgeRefraction = LocalRenderQuality.current == RenderQuality.MAX && cardTweaks != null &&
        shape is CornerBasedShape && android.os.Build.VERSION.SDK_INT >= 33
    val a11y = rememberWenkuAccessibility()
    val backdrop = LocalGlassBackdrop.current
    val outlinePath = remember { Path() }
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contact = animateFloatAsState(
        targetValue = if (pressed && !a11y.reduceMotion) 1f else 0f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "wenkuGlassPress"
    )
    val pressPosition = remember { mutableStateOf(Offset.Unspecified) }
    if (onClick != null && cardTweaks != null && !a11y.reduceMotion) {
        LaunchedEffect(interaction) {
            interaction.interactions.collect { event ->
                if (event is PressInteraction.Press) pressPosition.value = event.pressPosition
            }
        }
    }
    val motion = if (onClick == null || a11y.reduceMotion) Modifier else Modifier.graphicsLayer {
        val p = contact.value
        val strength = cardTweaks?.pressStrength ?: 1f
        scaleX = 1f - .03f * p * strength
        scaleY = scaleX
        if (cardTweaks != null && size.width > 0f && size.height > 0f) {
            val touch = pressPosition.value
            if (touch != Offset.Unspecified) {
                rotationX = (1f - 2f * (touch.y / size.height).coerceIn(0f, 1f)) * cardTweaks.tiltMaxDeg * p
                rotationY = (2f * (touch.x / size.width).coerceIn(0f, 1f) - 1f) * cardTweaks.tiltMaxDeg * p
            }
            cameraDistance = size.maxDimension * cardTweaks.cameraDistMult
        }
    }
    val pressureLight = if (onClick == null || cardTweaks == null || a11y.reduceMotion) Modifier
        else Modifier.drawWithContent {
            val strength = contact.value * cardTweaks.pressStrength
            if (strength > 0f && size.minDimension > 0f) {
                val touch = pressPosition.value
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.ink.copy(alpha = (.16f * strength).coerceAtMost(.28f)), Color.Transparent),
                        center = if (touch == Offset.Unspecified) center else touch,
                        radius = size.minDimension * .52f * cardTweaks.pressRadius
                    ),
                    radius = size.maxDimension * 2f
                )
            }
            drawContent()
        }
    val realtime = backdrop != null && a11y.blurEnabled && !a11y.highContrast && blur > 0.dp
    val solid = colors.panel
    val surfaceTint = when {
        a11y.highContrast -> solid
        tint != null -> tint
        a11y.blurEnabled -> colors.panel.copy(alpha = if (colors.isDark) 0.86f else 0.72f)
        else -> colors.panel.copy(alpha = 0.95f)
    }
    val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { blur.toPx() }
    // 6.3：整条玻璃 Modifier 提进 remember —— `drawBackdrop` 每次调用都会造一个新的
    // ShapeProvider，元素 equals 永远不成立 ⇒ 每次重组都 invalidateDrawCache + apply(effects)。
    // 提成常量后同一块的 modifiers 引用不变，重组不再触碰 RenderEffect；模糊本身则走
    // `wenkuGlassBlur` 的缓存（effects 里再也不会 createBlurEffect）。
    // 非实时分支要调 @Composable 的 consistentShadow，所以留在 remember 外面。
    val glassModifier = if (realtime) {
        remember(
            backdrop, shape, surfaceTint, blurPx, colors.outline, colors.isDark, edgeRefraction
        ) {
            val layerBackdrop = backdrop ?: return@remember Modifier
            var outlineSize = androidx.compose.ui.geometry.Size.Unspecified
            var outlineDirection: androidx.compose.ui.unit.LayoutDirection? = null
            Modifier
                .drawBackdrop(
                    backdrop = layerBackdrop,
                    shape = { shape },
                    effects = {
                        wenkuGlassBlur(blurPx)
                        if (edgeRefraction) lens(
                            refractionHeight = 12.dp.toPx(),
                            refractionAmount = 18.dp.toPx(),
                            depthEffect = true,
                        )
                    },
                    highlight = WenkuNoHighlight,
                    shadow = WenkuNoShadow,
                    onDrawSurface = { drawRect(surfaceTint) },
                    onDrawFront = {
                        if (outlineSize != size || outlineDirection != layoutDirection) {
                            outlineSize = size
                            outlineDirection = layoutDirection
                            outlinePath.rewind()
                            outlinePath.addOutline(shape.createOutline(size, layoutDirection, this))
                        }
                        drawPath(outlinePath, colors.outline.copy(alpha = if (colors.isDark) 0.55f else 0.42f), style = Stroke(0.7.dp.toPx()))
                    }
                )
        }
    } else {
        Modifier
            .consistentShadow(
                elevation = if (a11y.highContrast) 0.dp else 4.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .clip(shape)
            .background(surfaceTint)
            .border(0.7.dp, colors.outline.copy(alpha = if (colors.isDark) 0.55f else 0.38f), shape)
    }
    val cardActivation = if (onClick != null && cardTweaks != null) remember { mutableIntStateOf(0) } else null
    val clickModifier = if (onClick != null) {
        Modifier.pullOutClickable(
            interactionSource = interaction,
            indication = null,
            role = role,
            onClick = {
                if (cardActivation != null) cardActivation.intValue++
                onClick()
            }
        )
    } else Modifier
    Column(
        modifier = modifier.then(clickModifier).then(motion).then(glassModifier).then(pressureLight).padding(contentPadding)
    ) {
        if (cardActivation != null) {
            CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed, cardActivation.intValue)) {
                content()
            }
        } else content()
    }
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    wenkuSymbol: WenkuSymbol? = null
) {
    val symbol = wenkuSymbol ?: icon.wenkuSymbolOrNull()
    val a11y = rememberWenkuAccessibility()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !a11y.reduceMotion) 0.97f else 1f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "wenkuIconPress"
    )
    var activation by remember { mutableIntStateOf(0) }
    val gesture = remember { Animatable(1f) }
    LaunchedEffect(activation) {
        if (symbol == null && activation > 0) {
            if (a11y.reduceMotion) {
                gesture.snapTo(1f)
            } else {
                gesture.snapTo(0f)
                gesture.animateTo(1f, tween(640, easing = LinearEasing))
            }
        }
    }
    Box(
        modifier = modifier
            .size(WenkuTokens.IconButtonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(WenkuTokens.iconButtonShape)
            .background(if (a11y.highContrast) wenkuColors().panel else wenkuColors().ink.copy(alpha = if (wenkuColors().isDark) 0.06f else 0.04f))
            .pullOutClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    if (enabled) {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        activation++
                        onClick()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val glyphTint = (if (tint == Color.Unspecified) wenkuColors().ink else tint)
            .copy(alpha = if (enabled) 1f else .35f)
        val glyphMotion = Modifier.graphicsLayer {
            val p = kotlin.math.sin(gesture.value * Math.PI).toFloat()
            scaleX = 1f + .06f * p
            scaleY = scaleX
            rotationZ = 14f * p
        }
        if (symbol != null) {
            CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed && enabled, activation)) {
                WenkuIcon(
                    symbol = symbol,
                    modifier = Modifier.semantics {
                        if (contentDescription != null) this.contentDescription = contentDescription
                    },
                    size = WenkuTokens.IconGlyph,
                    tint = glyphTint,
                    entrance = false
                )
            }
        } else {
            Icon(icon, contentDescription, tint = glyphTint, modifier = glyphMotion.size(WenkuTokens.IconGlyph))
        }
    }
}

/**
 * 閸欏倽鈧?`GlassTextButton`閿涙艾宕愬?22 閻ㄥ嫭绁悳鑽ゆ嫅閵嗕焦娓剁亸?64鑴?8閵?4sp/500 鐎涙鍣搁妴?
 * 闁鑵戦幀浣稿涧閹?action 閼瑰弶璐╅崗?10%閿涘奔绗夐崝鐘殿儑娴滃苯婀€閹诲繗绔熼妴?
 */
@Composable
fun WenkuTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
    color: Color = Color.Unspecified,
    wenkuSymbol: WenkuSymbol? = null
) {
    val symbol = wenkuSymbol ?: icon?.wenkuSymbolOrNull()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = wenkuColors()
    val resolvedColor = if (color == Color.Unspecified) colors.ink else color
    val a11y = rememberWenkuAccessibility()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var activation by remember { mutableIntStateOf(0) }
    val gesture = remember { Animatable(1f) }
    LaunchedEffect(activation) {
        if (symbol == null && icon != null && activation > 0) {
            if (a11y.reduceMotion) {
                gesture.snapTo(1f)
            } else {
                gesture.snapTo(0f)
                gesture.animateTo(1f, tween(640, easing = LinearEasing))
            }
        }
    }
    WenkuGlassSurface(
        modifier = if (enabled) modifier else modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            disabled()
        },
        shape = RoundedCornerShape(22.dp),
        tint = if (selected) androidx.compose.ui.graphics.lerp(
            colors.panel, androidx.compose.material3.MaterialTheme.colorScheme.primary, .17f
        ) else null,
        interactionSource = interaction,
        blur = 0.dp,
        onClick = if (enabled) {
            {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                activation++
                onClick()
            }
        } else null,
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (symbol != null) {
                CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed && enabled, activation)) {
                    WenkuIcon(
                        symbol = symbol,
                        size = 18.dp,
                        tint = resolvedColor.copy(alpha = if (enabled) 1f else .35f),
                        active = selected,
                        entrance = false
                    )
                }
                Spacer(Modifier.size(8.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedColor.copy(alpha = if (enabled) 1f else .35f),
                    modifier = Modifier.size(16.dp).graphicsLayer {
                        val p = kotlin.math.sin(gesture.value * Math.PI).toFloat()
                        val s = 1f + .07f * p
                        scaleX = s
                        scaleY = s
                        translationY = -1.6f * p * density
                        rotationZ = if (text.contains("导入") || text.contains("新建")) 20f * p else 0f
                    }
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = text,
                color = resolvedColor.copy(alpha = if (enabled) 1f else .35f),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                fontFamily = AppFonts.Default,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 閸欏倽鈧?`AppSearchField`閿涙矮绔存稉顏呮￥ Material 鎼存洝澹婇惃鍕翻閸忋儻绱濋弨鎯ф躬閸氬奔绔撮崸妤冨箵閻犲啯婢橀崘鍛偓?
 * 閸撳秴顕?44dp 閹兼粎鍌ㄩ崶鐐垼閿涘本顒滈弬?14sp閿涘本绔婚梽銈嗗瘻闁?40鑴?6閿涘苯鐔柈銊ュ讲閺€鎯у彠闂傤厼濮╂担婧库偓?
 */
@Composable
fun rememberWenkuSearchMotion(expanded: Boolean): androidx.compose.runtime.State<Float> {
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    return animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = when {
                reduceMotion -> 0
                expanded -> WenkuMotion.ENTER_MS
                else -> WenkuMotion.EXIT_MS
            },
            easing = LinearEasing
        ),
        label = "searchExpansion"
    )
}

/** Right-anchored expansion from pages.dart. The field stays at its final layout width. */
@Composable
fun BoxScope.WenkuExpandableSearch(
    expanded: Boolean,
    motion: androidx.compose.runtime.State<Float>,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    compactFraction: Float = 0f,
    requestKeyboard: Boolean = true,
    onKeyboardRequested: () -> Unit = {}
) {
    val mounted by remember(expanded, motion) {
        androidx.compose.runtime.derivedStateOf { expanded || motion.value > 0f }
    }
    if (!mounted) {
        WenkuIconButton(
            icon = Icons.Outlined.Search,
            contentDescription = tr("搜索书架"),
            onClick = onOpen,
            modifier = Modifier.align(Alignment.CenterEnd).size(46.dp).graphicsLayer {
                scaleX = 1f - (5f / 24f) * compactFraction
                scaleY = scaleX
            }
        )
        return
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded, requestKeyboard) {
        if (expanded && requestKeyboard) {
            androidx.compose.runtime.snapshotFlow { motion.value }.first { it >= 1f }
            focusRequester.requestFocus()
            keyboard?.show()
            onKeyboardRequested()
        }
    }
    WenkuSearchField(
        value = value,
        onValueChange = onValueChange,
        hint = hint,
        focusRequester = focusRequester,
        onClose = onClose,
        enabled = expanded,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
            .graphicsLayer {
                val wobble = WenkuMotion.jelly(motion.value)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, .5f)
                scaleX = 1f + .026f * wobble
                scaleY = 1f - .042f * wobble
            }
            .clip(RoundedCornerShape(24.dp))
            .layout { measurable, constraints ->
                val fullWidth = constraints.maxWidth
                val minWidth = 46.dp.roundToPx().coerceAtMost(fullWidth)
                val expansion = WenkuMotion.ease.transform(motion.value)
                val width = androidx.compose.ui.util.lerp(minWidth, fullWidth, expansion)
                val field = measurable.measure(constraints.copy(minWidth = fullWidth))
                layout(width, field.height) { field.placeRelative(0, 0) }
            }
    )
}

@Composable
fun WenkuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClose: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val colors = wenkuColors()
    val focus = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    WenkuGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 44.dp, height = 46.dp), contentAlignment = Alignment.Center) {
                WenkuIcon(WenkuSymbol.Search, size = 19.dp, tint = colors.inkSecondary, animated = false)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).then(focus).padding(vertical = 8.dp),
                singleLine = true,
                enabled = enabled,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = colors.ink,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.ink),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { keyboard?.hide() }
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = hint,
                                color = colors.inkSecondary,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                WenkuIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = tr("清空搜索"),
                    onClick = { onValueChange("") },
                    wenkuSymbol = WenkuSymbol.CloseCircle,
                    modifier = Modifier.size(40.dp),
                    enabled = enabled
                )
            }
            if (onClose != null) {
                WenkuIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = tr("收起搜索"),
                    onClick = onClose,
                    modifier = Modifier.size(40.dp),
                    enabled = enabled
                )
            } else {
                Spacer(Modifier.size(12.dp))
            }
        }
    }
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = wenkuColors()
    val a11y = rememberWenkuAccessibility()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !a11y.reduceMotion) 0.975f else 1f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "wenkuPillPress"
    )
    val base = if (primary) colors.ink else colors.raised
    val fg = if (primary) colors.paper else colors.ink
    var activation by remember { mutableIntStateOf(0) }
    Row(
        modifier = modifier
            .height(WenkuTokens.PillHeight)
            .widthIn(min = if (primary) 148.dp else 106.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(WenkuTokens.pillShape)
            .background(base.copy(alpha = if (enabled) 1f else 0.42f))
            .border(0.7.dp, colors.ink.copy(alpha = if (primary) 0.08f else 0.12f), WenkuTokens.pillShape)
            .pullOutClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    if (enabled) {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        activation++
                        onClick()
                    }
                }
            )
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed && enabled, activation)) {
                WenkuButtonGlyph(icon, 18.dp, fg)
            }
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AppFonts.Default,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuJellyTapSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !a11y.reduceMotion) 0.985f else 1f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "wenkuJellyPress"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pullOutClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        content = content
    )
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuContentPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = wenkuColors()
    val a11y = rememberWenkuAccessibility()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null && !a11y.reduceMotion) 0.975f else 1f,
        animationSpec = if (pressed) WenkuMotion.contactSpec() else WenkuMotion.settleSpec(),
        label = "wenkuPanelPress"
    )
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(WenkuTokens.shapes.Panel)
            .background(if (a11y.highContrast) colors.panel else colors.panel.copy(alpha = 0.82f))
            .border(0.7.dp, colors.outline.copy(alpha = 0.42f), WenkuTokens.shapes.Panel)
            .then(
                if (onClick != null) Modifier.pullOutClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(contentPadding),
        content = content
    )
}

/**
  * 鐠囷箒顫?docs/wenku8-ui-port-plan.md */
@Composable
fun WenkuMenu(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = wenkuColors()
    Column(
        modifier = modifier
            .widthIn(min = WenkuTokens.MenuWidth, max = WenkuTokens.MenuMaxOverlayWidth)
            .clip(WenkuTokens.shapes.Menu)
            .background(colors.panel)
            .border(0.7.dp, colors.outline.copy(alpha = 0.5f), WenkuTokens.shapes.Menu)
            .padding(vertical = 4.dp),
        content = content
    )
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuSectionHeader(
    index: String,
    latin: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = wenkuColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$index / $latin",
                modifier = Modifier.wenkuArchivePart(.08f, .68f, travel = 4f),
                color = colors.inkSecondary,
                fontSize = 10.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = AppFonts.Default,
                maxLines = 1,
            )
            Box(
                Modifier.weight(1f).height(.5.dp)
                    .wenkuArchivePart(0f, .46f, travel = 0f)
                    .background(colors.hairline),
            )
            Box(Modifier.size(3.dp).wenkuArchivePart(.05f, .5f, travel = 0f).background(colors.ink))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f).wenkuArchivePart(.16f, .82f),
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFonts.Serif,
            )
            trailing?.invoke()
        }
    }
}

/** 鐠囷箒顫?docs/wenku8-ui-port-plan.md閵?*/
@Composable
fun WenkuHairline(modifier: Modifier = Modifier) {
    val colors = wenkuColors()
    Box(modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
}



