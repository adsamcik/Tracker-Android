package com.adsamcik.tracker.dashboard.ui.compose.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineDurations
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion

/**
 * Dashboard motion tokens — delegates to shared [RidgelineMotion] and [RidgelineDurations].
 *
 * Kept for backward compatibility. New code should import from
 * `com.adsamcik.tracker.shared.utils.style.compose` directly.
 */
@Deprecated("Use RidgelineMotion and RidgelineDurations from sutils", ReplaceWith("RidgelineMotion"))
object MotionTokens {

	// ─── SPRING CONFIGURATIONS (delegate to RidgelineMotion) ─────────────

	val Snappy: SpringSpec<Float> get() = RidgelineMotion.Snap
	val Standard: SpringSpec<Float> get() = RidgelineMotion.Settle
	val Responsive: SpringSpec<Float> get() = RidgelineMotion.Respond
	val Bouncy: SpringSpec<Float> get() = RidgelineMotion.Crest
	val Gentle: SpringSpec<Float> get() = RidgelineMotion.Drift
	val Dramatic: SpringSpec<Float> get() = RidgelineMotion.Surge

	// ─── DURATION TOKENS (delegate to RidgelineDurations) ────────────────

	const val INSTANT_MS = RidgelineDurations.INSTANT_MS
	const val QUICK_MS = RidgelineDurations.QUICK_MS
	const val STANDARD_MS = RidgelineDurations.STANDARD_MS
	const val EMPHASIZED_MS = RidgelineDurations.EMPHASIZED_MS
	const val EXPRESSIVE_MS = RidgelineDurations.EXPRESSIVE_MS
	const val AMBIENT_MS = RidgelineDurations.AMBIENT_MS
	const val BACKGROUND_LOOP_MS = RidgelineDurations.BACKGROUND_LOOP_MS

	/** Stagger interval between sequential card entrances. */
	const val STAGGER_MS = 60

	/** Maximum index depth that receives incremental stagger delay. */
	const val STAGGER_MAX_DEPTH = 6

	// ─── TWEEN HELPERS ───────────────────────────────────────────────────

	fun <T> tweenQuick(): AnimationSpec<T> = tween(QUICK_MS, easing = FastOutSlowInEasing)
	fun <T> tweenStandard(): AnimationSpec<T> = tween(STANDARD_MS, easing = FastOutSlowInEasing)
	fun <T> tweenEmphasized(): AnimationSpec<T> = tween(EMPHASIZED_MS, easing = FastOutSlowInEasing)
	fun <T> tweenExpressive(): AnimationSpec<T> = tween(EXPRESSIVE_MS, easing = FastOutSlowInEasing)
	fun <T> tweenAmbient(): AnimationSpec<T> = tween(AMBIENT_MS, easing = LinearEasing)
}
