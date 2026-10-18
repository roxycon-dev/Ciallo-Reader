package com.example.ui.glasskit

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIconButton

/** Shared by source management and device transfer, including collapse typography and transparent chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassManagementTopBar(title:String,scrollBehavior:TopAppBarScrollBehavior,onBack:()->Unit,
    modifier:Modifier=Modifier,actions:@Composable RowScope.()->Unit={}) {
    LargeTopAppBar(
        title={Text(title,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface,
            fontSize=(28f-8f*scrollBehavior.state.collapsedFraction).sp)},
        navigationIcon={AppIconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},
        actions=actions,scrollBehavior=scrollBehavior,
        colors=TopAppBarDefaults.largeTopAppBarColors(containerColor=Color.Transparent,scrolledContainerColor=Color.Transparent),
        modifier=modifier
    )
}
