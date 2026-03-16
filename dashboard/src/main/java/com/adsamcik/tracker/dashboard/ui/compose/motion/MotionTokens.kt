package com.adsamcik.tracker.dashboard.ui.compose.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion token system for the Dashboard.
 *
 * Defines 6 named spring configurations and 7 duration tokens that create
 * a consistent motion vocabulary across all dashboard animations.
 *
 * All animations respect the app-level reduced motion preference.
 */
object MotionTokens {

	// ─── SPRING CONFIGURATIONS ───────────────────────────────────────────

	/** Immediate response, minimal overshoot. Tap feedback, icon swaps. */
	val Snappy: SpringSpec<Float> = spring(
		dampingRatio = 0.7f,
		stiffness = 1500f,
	)

	/** Smooth, no overshoot. General layout shifts, card repositioning. */
	val Standard: SpringSpec<Float> = spring(
		dampingRatio = 1.0f,
		stiffness = Spring.StiffnessMediumLow,
	)

	/** Quick settle, tiny overshoot. Value counters, metric updates. */
	val Responsive: SpringSpec<Float> = spring(
		dampingRatio = 0.8f,
		stiffness = 800f,
	)

	/** Celebratory, noticeable overshoot. Milestone pops, goal completion. */
	val Bouncy: SpringSpec<Float> = spring(
		dampingRatio = Spring.DampingRatioMediumBouncy,
		stiffness = Spring.StiffnessLow,
	)

	/** Slow drift, dreamy. Empty state float, background parallax. */
	val Gentle: SpringSpec<Float> = spring(
		dampingRatio = 1.0f,
		stiffness = Spring.StiffnessVeryLow,
	)

	/** Slow + bouncy, theatrical. State transitions (idle↔tracking), FAB morph. */
	val Dramatic: SpringSpec<Float> = spring(
		dampingRatio = 0.6f,
		stiffness = 200f,
	)

	// ─── DURATION TOKENS ─────────────────────────────────────────────────

	/** Immediate visual feedback (press highlight). */
	const val INSTANT_MS = 50

	/** Fade out on exit, icon swap crossfade. */
	const val QUICK_MS = 150

	/** Card entrance fade, content crossfade. */
	const val STANDARD_MS = 300

	/** State transition emphasis, FAB morph. */
	const val EMPHASIZED_MS = 500

	/** Path drawing, goal ring fill. */
	const val EXPRESSIVE_MS = 800

	/** Continuous loops: pulse, float, recording dot. */
	const val AMBIENT_MS = 2000

	/** Glow rotation, wave pattern. */
	const val BACKGROUND_LOOP_MS = 4000

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
