package com.example.ui.glasskit

import com.example.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIconButton
import com.example.ui.wenku.wenkuTabTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassManagementPage(
    title: String,
    code: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    val headerTravel = with(LocalDensity.current) { 48.dp.toPx() }
    GlassKitHost(topGradientHeight = headerHeight + 24.dp) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                Box {
                    GlassTopBarStrip(
                        (-scroll.state.contentOffset / headerTravel).coerceIn(0f, 1f),
                        Modifier.fillMaxWidth().height(headerHeight)
                    )
                    GlassManagementTopBar(title, code, scroll, onBack)
                }
            },
            content = content,
        )
    }
}

/** ArchiveHeaderBar and ArchiveRouteTitle use a compact, centered label and archive code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassManagementTopBar(
    title: String,
    code: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    CenterAlignedTopAppBar(
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, Modifier.wenkuTabTitle(0), fontWeight = FontWeight.Bold, color = colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(code, Modifier.wenkuTabTitle(1).clearAndSetSemantics { }, color = colors.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .7.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr("返回"), onClick = onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
        expandedHeight = 48.dp,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        modifier = modifier
    )
}
