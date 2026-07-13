package com.adsamcik.tracker.map.presentation.bridge

import kotlin.math.pow

/**
 * Samples a looping comet trail into monotonic MapLibre line-gradient stops. The geometry and source
 * stay unchanged while only these layer-property colours move at render time.
 */
internal fun buildFlowGradientStops(
	colorArgb: Int,
	phase: Float,
	trailFraction: Float,
	sampleCount: Int = 48,
): List<Pair<Float, Int>> {
	require(sampleCount >= 2) { "sampleCount must be at least 2" }
	val clampedTrail = trailFraction.coerceIn(0.02f, 0.95f)
	val wrappedPhase = phase.mod(1f).let { if (it < 0f) it + 1f else it }
	val baseAlpha = (colorArgb ushr 24) and 0xFF
	val rgb = colorArgb and 0x00FFFFFF
	return List(sampleCount) { index ->
		val progress = index.toFloat() / (sampleCount - 1)
		val distanceBehind = (wrappedPhase - progress).mod(1f).let { if (it < 0f) it + 1f else it }
		val intensity = if (distanceBehind <= clampedTrail) {
			(1f - distanceBehind / clampedTrail).pow(2)
		} else {
			0f
		}
		val alpha = (baseAlpha * intensity).toInt().coerceIn(0, 255)
		progress to ((alpha shl 24) or rgb)
	}
}
