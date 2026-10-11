package com.example.ui.components

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.LocalNavAnimatedVisibilityScope
import com.example.LocalSharedTransitionScope
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.catalogCoverMotion(sourceId: String, bookId: String): Modifier {
    val shared = LocalSharedTransitionScope.current
    val visible = LocalNavAnimatedVisibilityScope.current
    if (shared == null || visible == null || LocalReduceMotion.current) return this
    val base = this
    return with(shared) {
        base.sharedElement(rememberSharedContentState("catalog_cover_${sourceId}_$bookId"), visible,
            boundsTransform = { _, _ -> tween(360, easing = AppMotion.easeOutCubic) })
    }
}
