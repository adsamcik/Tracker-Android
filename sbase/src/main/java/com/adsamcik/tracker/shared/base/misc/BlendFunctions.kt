package com.adsamcik.tracker.common.misc

import kotlin.math.pow

@Suppress("MagicNumber", "Unused")
object BlendFunctions {
	/**
	 * Bézier blend function.
	 */
	fun bezier(time: Float): Float {
		return time.pow(2) * (3.0f - 2.0f * time)
	}

	/**
	 * Parametric blend function.
	 * source: https://math.stackexchange.com/a/121755
	 */
	fun parametric(time: Float): Float {
		val square = time.pow(2)
		return square / (2.0f * (square - time) + 1.0f)
	}

	/**
	 * Linear blend function.
	 */
	fun linear(time: Float): Float {
		return time
	}
}
