package com.example.ui.feedback

import androidx.compose.animation.*
import androidx.compose.animation.core.tween

/** A paired forward/back language, kept quiet enough for shared cover transitions. */
object NavigationMotion {
    fun enter(reduce: Boolean): EnterTransition = if (reduce) EnterTransition.None else
        fadeIn(tween(AppMotion.CONTENT_MS)) + slideInHorizontally(tween(AppMotion.PAGE_MS, easing = AppMotion.easeOutCubic)) { it / 12 }
    fun leave(reduce: Boolean): ExitTransition = if (reduce) ExitTransition.None else fadeOut(tween(140))
    fun returnEnter(reduce: Boolean): EnterTransition = if (reduce) EnterTransition.None else fadeIn(tween(180))
    fun returnLeave(reduce: Boolean): ExitTransition = if (reduce) ExitTransition.None else
        fadeOut(tween(AppMotion.EXIT_MS)) + slideOutHorizontally(tween(220, easing = AppMotion.easeOutCubic)) { it / 12 }
}
