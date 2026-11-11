package com.example.ui

import com.example.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.CardTweaks
import com.example.ui.components.GlassCard
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.LocalCardTweaks
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.glasskit.GlassManagementPage
import com.example.ui.wenku.WenkuContentPanel
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.wenkuPagePart
import com.ramotion.fluidslider.FluidSlider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardAppearanceScreen(
    tw: CardTweaks,
    updateCardTweaks: ((CardTweaks) -> CardTweaks) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pressPreview = !LocalReduceMotion.current && !LocalRenderQuality.current.minimal
    GlassManagementPage("自定义卡片参数", "APPEARANCE / CARDS", onBack) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AdaptiveSpec.pageContentMaxWidth).fillMaxSize()) {
                // Recreate only the sample layer when its painted surface changes.
                key(tw.cornerRadiusDp, tw.blurRadiusDp, tw.tintMix, tw.cardAlpha) {
                    CompositionLocalProvider(
                        LocalCardTweaks provides tw,
                        com.example.ui.wenku.LocalWenkuMainSurface provides true,
                    ) {
                        GlassCard(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp).wenkuPagePart(1),
                            onClick = if (pressPreview) ({}) else null,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 72.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WenkuIcon(WenkuSymbol.Layers, size = 24.dp, tint = colors.primary, entrance = false)
                                Column {
                                    Text(tr("实时预览"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (pressPreview) tr("按住卡片边缘，查看倾斜与压力反馈") else tr("圆角、色调与不透明度即时更新"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
                ) {
                    item(key = "finish") {
                        WenkuContentPanel(Modifier.fillMaxWidth().wenkuPagePart(2)) {
                            Text(tr("表面与色调"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            CardTweakSlider(
                                label = tr("壁纸玻璃模糊（自定义壁纸＋高或极致画质）"),
                                valueText = "${tw.blurRadiusDp.toInt()}dp",
                                position = (tw.blurRadiusDp - 8f) / 32f,
                                startText = "8",
                                endText = "40",
                            ) { v -> updateCardTweaks { it.copy(blurRadiusDp = 8f + v * 32f) } }
                            CardTweakSlider(
                                label = tr("卡片圆角"),
                                valueText = "${tw.cornerRadiusDp.toInt()}dp",
                                position = (tw.cornerRadiusDp - 2f) / 46f,
                                startText = "2",
                                endText = "48",
                            ) { v -> updateCardTweaks { it.copy(cornerRadiusDp = 2f + v * 46f) } }
                            CardTweakSlider(
                                label = tr("主题色叠加浓度"),
                                valueText = "${(tw.tintMix * 100).toInt()}%",
                                position = tw.tintMix / 0.3f,
                                startText = "0%",
                                endText = "30%",
                            ) { v -> updateCardTweaks { it.copy(tintMix = v * 0.3f) } }
                            CardTweakSlider(
                                label = tr("卡片不透明度"),
                                valueText = "${(tw.cardAlpha * 100).toInt()}%",
                                position = (tw.cardAlpha - 0.4f) / 0.6f,
                                startText = "40%",
                                endText = "100%",
                            ) { v -> updateCardTweaks { it.copy(cardAlpha = 0.4f + v * 0.6f) } }
                        }
                    }
                    item(key = "contact") {
                        WenkuContentPanel(Modifier.fillMaxWidth().wenkuPagePart(3)) {
                            Text(tr("倾斜与按压"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            CardTweakSlider(
                                label = tr("3D 倾斜最大角度"),
                                valueText = "%.1f°".format(tw.tiltMaxDeg),
                                position = kotlin.math.sqrt(tw.tiltMaxDeg / 15f),
                                startText = "0°",
                                endText = "15°",
                            ) { v -> updateCardTweaks { it.copy(tiltMaxDeg = v * v * 15f) } }
                            CardTweakSlider(
                                label = tr("立体透视强度（越大越立体）"),
                                valueText = "${((12f - tw.cameraDistMult) / 9f * 100).toInt()}%",
                                position = (12f - tw.cameraDistMult) / 9f,
                                startText = "平面",
                                endText = "立体",
                            ) { v -> updateCardTweaks { it.copy(cameraDistMult = 12f - v * 9f) } }
                            CardTweakSlider(
                                label = tr("压力形变强度"),
                                valueText = "${(tw.pressStrength * 100).toInt()}%",
                                position = tw.pressStrength / 2f,
                                startText = "0%",
                                endText = "200%",
                            ) { v -> updateCardTweaks { it.copy(pressStrength = v * 2f) } }
                            CardTweakSlider(
                                label = tr("压力形变半径"),
                                valueText = "×%.1f".format(tw.pressRadius),
                                position = (tw.pressRadius - 0.5f) / 2.0f,
                                startText = "0.5×",
                                endText = "2.5×",
                            ) { v -> updateCardTweaks { it.copy(pressRadius = 0.5f + v * 2.0f) } }
                        }
                    }
                    item(key = "reset") {
                        WenkuTextButton(
                            "重置默认", { updateCardTweaks { CardTweaks() } },
                            modifier = Modifier.fillMaxWidth().wenkuPagePart(4), wenkuSymbol = WenkuSymbol.Refresh
                        )
                    }
                }
            }
        }
    }
}

/** Keep labels outside the slider's reserved bubble and deformation area. */
@Composable
internal fun CardTweakSlider(
    label: String,
    valueText: String,
    position: Float,
    startText: String,
    endText: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp,
                lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(valueText, fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        FluidSlider(position = position.coerceIn(0f, 1f), onPositionChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
            bubbleText = valueText, startText = startText, endText = endText)
    }
}
