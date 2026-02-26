package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Ridgeline Design System — Motion Tokens
 *
 * 6 named springs, each encoding a personality trait.
 * Use RidgelineMotion springs for app-specific animations.
 * Use MaterialTheme.motionScheme for standard M3 component transitions.
 */
object RidgelineMotion {

    /** Immediate, decisive. Tap feedback, toggles, icon swaps, privacy controls. */
    val Snap: SpringSpec<Float> = spring(
        dampingRatio = 0.75f,
        stiffness = 1500f,
    )

    /** Smooth, no overshoot. Layout shifts, card repositioning, list reorder. */
    val Settle: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 400f,
    )

    /** Quick settle, tiny overshoot. Value counters, metric updates, progress changes. */
    val Respond: SpringSpec<Float> = spring(
        dampingRatio = 0.82f,
        stiffness = 800f,
    )

    /** Celebratory, noticeable bounce. Milestone pops, goal completion, achievement reveals. */
    val Crest: SpringSpec<Float> = spring(
        dampingRatio = 0.55f,
        stiffness = 300f,
    )

    /** Slow drift, dreamy. Empty state float, background parallax, ambient loops. */
    val Drift: SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 50f,
    )

    /** Theatrical, dramatic. State transitions (idle↔tracking), FAB morph. */
    val Surge: SpringSpec<Float> = spring(
        dampingRatio = 0.58f,
        stiffness = 180f,
    )
}

/** Generic typed spring constructors for non-Float animation targets. */
inline fun <reified T> ridgelineSnap(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 1500f)
inline fun <reified T> ridgelineSettle(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 400f)
inline fun <reified T> ridgelineRespond(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 800f)
inline fun <reified T> ridgelineCrest(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 300f)
inline fun <reified T> ridgelineDrift(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 50f)
inline fun <reified T> ridgelineSurge(): SpringSpec<T> = spring(dampingRatio = 0.58f, stiffness = 180f)

/** Duration tokens for tween-based animations. */
object RidgelineDurations {
    const val INSTANT_MS = 50
    const val QUICK_MS = 150
    const val STANDARD_MS = 300
    const val EMPHASIZED_MS = 500
    const val EXPRESSIVE_MS = 800
    const val AMBIENT_MS = 2000
    const val BACKGROUND_LOOP_MS = 4000
}

/** Tween helpers with standard easing. */
fun <T> tweenQuick(): AnimationSpec<T> = tween(RidgelineDurations.QUICK_MS, easing = FastOutSlowInEasing)
fun <T> tweenStandard(): AnimationSpec<T> = tween(RidgelineDurations.STANDARD_MS, easing = FastOutSlowInEasing)
fun <T> tweenEmphasized(): AnimationSpec<T> = tween(RidgelineDurations.EMPHASIZED_MS, easing = FastOutSlowInEasing)
fun <T> tweenExpressive(): AnimationSpec<T> = tween(RidgelineDurations.EXPRESSIVE_MS, easing = FastOutSlowInEasing)

/** Bottom sheet spring specs. */
val SheetExpandSpring: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 600f)
val SheetDismissSpring: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 600f)

/** Loading/skeleton animation tokens. */
object LoadingMotion {
    val EnterDuration = RidgelineDurations.STANDARD_MS
    val ExitDuration = RidgelineDurations.QUICK_MS
    val PulseDuration = 1200
    const val PulseAlphaMin = 0.08f
    const val PulseAlphaMax = 0.16f
}
